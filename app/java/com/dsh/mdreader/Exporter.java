package com.dsh.mdreader;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出：PDF（系统打印链路）、分片长图、当前屏、选区。
 *
 * 长图的现实约束（用样本量出来的）：那篇文档渲染出来约 6~8 万像素高，
 * 一张 1080×70000 的位图 = 290 MB，任何手机都分配不出来。
 * 所以必须「逐屏抓取 + 拼接 + 分片输出」，并且每片高度按可用堆内存动态收敛。
 *
 * 另一个必须处理的点：页面顶栏是 position:fixed，逐屏抓取会让它出现在每一片里。
 * 因此导出前 JS 会给 <html> 加 .exporting 类把顶栏隐藏，导出结束后（exportFinished）恢复。
 */
public final class Exporter {

    private static final String TAG = "LanyueExport";
    private static final int REQ_CHUNK_PX = 12000;      // 用户批准的每片上限（输出像素）
    private static final int ACK_EXTRA_MS = 40;         // 收到滚动回执后再等一小会儿，让画面落地
    private static final int ACK_TIMEOUT_MS = 700;      // 回执兜底时限（页面没回也要继续，别卡死）
    private static final float BLANK_RATIO = 0.9985f;   // 「整屏同色」判定阈值（识别未合成的空白帧）
    private static final int BLANK_RETRY = 2;           // 疑似空白帧的重试次数

    public interface Callback {
        /** shareUri 非空表示可直接分享（cacheDir/share 下的文件） */
        void done(boolean ok, String msg, String shareUri);
    }

    /**
     * 由 MainActivity 提供 WebView 的几何信息。
     *
     * **单位必须显式写清**：CSS 像素（网页里的 px）与物理像素（View 的 px）相差一个 density，
     * 早期版本把物理像素传给了"按 CSS 像素换算"的 scrollTo()，于是每步滚动被多乘了一次 density，
     * 结果就是"长图只有第一格有内容，后面全白"。方法名里带 Css / Phys 就是为了不再犯。
     */
    public interface Layout {
        /** 正文总高度（CSS px） */
        int contentHeightCss();

        /** 视口高度（CSS px） */
        int viewportHeightCss();

        /** 当前滚动位置（CSS px） */
        int scrollYCss();

        /** 滚动到指定位置（**物理像素**） */
        void scrollToPhys(int physY);
    }

    private final Activity activity;
    private final WebView web;
    private final Settings settings;
    private final Layout layout;
    private final AssetServer assets;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Uri> lastExported = new ArrayList<Uri>();
    private boolean busy = false;
    private int savedScrollYcss = 0;
    private int contentPhysPx = 0;              // 正文总高（物理像素），startCapture 里写入
    private volatile boolean capturePending = false;
    private Runnable captureAction;
    private volatile int lastAckCssY = Integer.MIN_VALUE;
    private Callback printCb;
    private Runnable hqReset;

    public Exporter(Activity activity, WebView web, Settings settings, Layout layout, AssetServer assets) {
        this.activity = activity;
        this.web = web;
        this.settings = settings;
        this.layout = layout;
        this.assets = assets;
    }

    public boolean isBusy() {
        return busy;
    }

    /* ==================================================================
       PDF：走系统打印链路（PrintManager + WebView 的打印适配器）
       ================================================================== */
    public void exportPdf(final boolean highQuality, final Callback cb) {
        if (web == null) {
            cb.done(false, "页面未就绪", null);
            return;
        }
        printCb = cb;
        final String jobName = docNameForPrint();
        assets.setHighQuality(highQuality);
        // 打印渲染期间图片按高分辨率提供；打印没有完成回调，用超时回落 + onResume 兜底复位
        scheduleHqReset(60_000);

        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    PrintManager pm = (PrintManager) activity.getSystemService(Context.PRINT_SERVICE);
                    if (pm == null) {
                        cb.done(false, "此设备不支持系统打印", null);
                        return;
                    }
                    PrintDocumentAdapter adapter = web.createPrintDocumentAdapter(jobName);
                    // 边距不在原生侧设：WebView 的打印适配器会忽略 MinMargins，
                    // 真机实测表现为"内容顶满左右两侧"。改由 CSS 的 @page 控制（见 app.css 打印段），
                    // 这里显式给 0 以免某些机型把两套边距叠加。
                    PrintAttributes attrs = new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                            .build();
                    pm.print(jobName, adapter, attrs);
                    cb.done(true, highQuality ? "已打开系统打印（高质量图片）" : "已打开系统打印", null);
                } catch (Throwable t) {
                    Log.w(TAG, "print failed", t);
                    cb.done(false, "打开打印失败：" + t.getMessage(), null);
                }
            }
        });
    }

    private static int mmToMils(int mm) {
        return (int) Math.round(mm / 25.4 * 1000);
    }

    private String docNameForPrint() {
        String n = activity.getSharedPreferences("lanyue", Context.MODE_PRIVATE).getString("fileName", null);
        return (n == null || n.isEmpty() ? "文档" : n) + " · 蓝阅";
    }

    private void scheduleHqReset(long delayMs) {
        if (hqReset != null) ui.removeCallbacks(hqReset);
        hqReset = new Runnable() {
            @Override
            public void run() {
                assets.setHighQuality(false);
            }
        };
        ui.postDelayed(hqReset, delayMs);
    }

    /** 由 Activity.onResume 调用：用户从打印界面回来后立刻恢复低分辨率图 */
    public void onActivityResumed() {
        if (hqReset != null) {
            ui.removeCallbacks(hqReset);
            hqReset = null;
        }
        assets.setHighQuality(false);
    }

    /* ==================================================================
       图片导出
       ================================================================== */
    public void exportImages(String mode, String payloadJson, Callback cb) {
        if (busy) {
            cb.done(false, "正在导出中，请稍候", null);
            return;
        }
        if (web == null || web.getWidth() <= 0) {
            cb.done(false, "页面未就绪", null);
            return;
        }
        int vpCss = Math.max(1, layout.viewportHeightCss());
        int vpH = web.getHeight();
        final float scale = (float) vpH / (float) vpCss;          // 物理像素 / CSS 像素
        final int w = web.getWidth();
        final int contentPhys = Math.max(vpH, Math.round(layout.contentHeightCss() * scale));
        final int savedCss = layout.scrollYCss();                 // 记住原位置（CSS px）

        int top = 0, bottom = contentPhys;
        int chunkHint = REQ_CHUNK_PX;
        try {
            JSONObject p = payloadJson == null || payloadJson.isEmpty() ? new JSONObject() : new JSONObject(payloadJson);
            if (p.has("chunkHeight")) chunkHint = p.getInt("chunkHeight");
            if ("selection".equals(mode)) {
                top = Math.round((float) p.optDouble("top", 0) * scale);
                bottom = Math.round((float) p.optDouble("bottom", contentPhys) * scale);
                if (bottom <= top) {
                    cb.done(false, "选区为空", null);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        top = clamp(top, 0, contentPhys - 1);
        bottom = clamp(bottom, top + 1, contentPhys);

        final boolean hq = settings.isHqExport();
        assets.setHighQuality(hq);

        // 每片高度按可用堆内存收敛：位图 = w * chunkH * 4 字节
        long maxHeap = Runtime.getRuntime().maxMemory();
        long budget = Math.max(8L * 1024 * 1024, (long) (maxHeap * 0.30));
        int memCap = (int) Math.max(1200, budget / (4L * (long) w));
        final int chunkH = Math.max(400, Math.min(Math.round(chunkHint * scale), Math.min(REQ_CHUNK_PX, memCap)));

        final int fTop = top, fBottom = bottom;
        final String fMode = mode;

        busy = true;
        savedScrollYcss = savedCss;
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setTouchLocked(true);
        lastExported.clear();

        ui.post(new Runnable() {
            @Override
            public void run() {
                startCapture(fMode, w, chunkH, fTop, fBottom, vpH, contentPhys, cb);
            }
        });
    }

    private void startCapture(String mode, int w, int chunkH, int top, int bottom, int vpH, int contentPhys, Callback cb) {
        List<int[]> ranges = new ArrayList<int[]>();
        if ("viewport".equals(mode)) {
            float scale = (float) vpH / (float) Math.max(1, layout.viewportHeightCss());
            int y = clamp(Math.round(layout.scrollYCss() * scale), 0, Math.max(0, contentPhys - vpH));
            ranges.add(new int[]{y, y + vpH});
        } else {
            for (int y = top; y < bottom; y += chunkH) {
                ranges.add(new int[]{y, Math.min(bottom, y + chunkH)});
            }
        }
        // 这条日志自带"单位自证"：真机复测长图导出时用它核对换算是否正确
        // （vpCss/内容高是 CSS px；content/vp/chunkH 是物理 px；scale 应约等于屏幕密度）
        contentPhysPx = contentPhys;
        Log.i(TAG, "capture ranges=" + ranges.size() + " chunkH=" + chunkH + "px"
                + " content=" + contentPhys + "px vp=" + vpH + "px"
                + " | vpCss=" + layout.viewportHeightCss() + " contentCss=" + layout.contentHeightCss()
                + " scrollCss=" + layout.scrollYCss()
                + " scale=" + String.format(java.util.Locale.US, "%.3f", (float) vpH / Math.max(1, layout.viewportHeightCss()))
                + " | mode=" + mode);
        captureRange(mode, w, vpH, ranges, 0, 0, new ArrayList<Uri>(), 0, cb);
    }

    private void captureRange(final String mode, final int w, final int vpH, final List<int[]> ranges,
                              final int rangeIndex, final int tileIndex, final List<Uri> out,
                              final int truncatedCount, final Callback cb) {
        if (rangeIndex >= ranges.size()) {
            finishImages(mode, out, truncatedCount, cb);
            return;
        }
        final int[] r = ranges.get(rangeIndex);
        final int rangeH = r[1] - r[0];
        final Bitmap chunk = Bitmap.createBitmap(w, rangeH, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(chunk);
        captureTile(mode, w, vpH, ranges, rangeIndex, tileIndex, out, truncatedCount,
                chunk, c, r[0], rangeH, cb, 0);
    }

    /**
     * 抓一屏。
     *
     * 时序不靠固定延时，而是「布防 → 滚动 → 页面回执（滚动后两帧）→ 再抓」：
     * Chromium 的合成是异步的，只按固定延时抓，很容易抓到还没重新合成的空白帧
     * （真机 bug「长图只有第一格有内容、之后全白」的成因之一）。
     */
    private void captureTile(final String mode, final int w, final int vpH, final List<int[]> ranges,
                             final int rangeIndex, final int tileIndex, final List<Uri> out,
                             final int truncatedCount, final Bitmap chunk, final Canvas canvas,
                             final int rangeTop, final int rangeH, final Callback cb, final int attempt) {
        final int[] r = ranges.get(rangeIndex);
        final int y = rangeTop + tileIndex * vpH;
        if (y >= r[1]) {
            // 本片抓完：写盘
            saveChunk(mode, chunk, rangeIndex, ranges.size(), new ChunkSaver() {
                @Override
                public void onSaved(Uri uri, int truncated) {
                    chunk.recycle();
                    List<Uri> next = out;
                    if (uri != null) next.add(uri);
                    captureRange(mode, w, vpH, ranges, rangeIndex + 1, 0, next, truncatedCount + truncated, cb);
                }
            });
            return;
        }
        // 滚到底部时 WebView 会钳制在最大滚动位置：这时改用「重叠贴图」，
        // 按实际位置把这一屏画进片里，避免最后一片重复最后一屏、或者抓成空白。
        final int maxScroll = Math.max(0, contentPhysPx - vpH);
        final int target = clamp(y, 0, maxScroll);
        final int dstOffset = Math.max(0, target - rangeTop);
        final int drawH = Math.min(vpH, Math.max(0, rangeH - dstOffset));
        final float scale = (float) vpH / (float) Math.max(1, layout.viewportHeightCss());
        final int targetCss = Math.round(target / Math.max(0.01f, scale));

        capturePending = true;
        captureAction = new Runnable() {
            @Override
            public void run() {
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        doCaptureTile(mode, w, vpH, ranges, rangeIndex, tileIndex, out, truncatedCount,
                                chunk, canvas, rangeTop, rangeH, cb, attempt, y, target, dstOffset, drawH);
                    }
                }, ACK_EXTRA_MS);
            }
        };
        // 先布防回执再滚动（顺序不能反：布防时若已经就在目标位置，页面会立刻回执）
        web.evaluateJavascript("(function(){try{return LanyueApp.armScrollAck(" + targetCss
                + ");}catch(e){return 0;}})()", null);
        layout.scrollToPhys(target);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (capturePending) {
                    Log.w(TAG, "滚动回执超时，仍然继续抓图 y=" + target);
                    fireCapture();
                }
            }
        }, ACK_TIMEOUT_MS);
    }

    /** 页面滚动回执（由 AppBridge 转发）：与超时兜底只认先到的那个 */
    public void onScrollSettled(int cssY) {
        lastAckCssY = cssY;
        if (busy && capturePending) fireCapture();
    }

    private void fireCapture() {
        if (!capturePending) return;
        capturePending = false;
        Runnable a = captureAction;
        captureAction = null;
        if (a != null) a.run();
    }

    private void doCaptureTile(final String mode, final int w, final int vpH, final List<int[]> ranges,
                               final int rangeIndex, final int tileIndex, final List<Uri> out,
                               final int truncatedCount, final Bitmap chunk, final Canvas canvas,
                               final int rangeTop, final int rangeH, final Callback cb, final int attempt,
                               final int requestedY, final int target, final int dstOffset, final int drawH) {
        Bitmap tile = null;
        try {
            tile = Bitmap.createBitmap(w, vpH, Bitmap.Config.ARGB_8888);
            web.draw(new Canvas(tile));
            float blank = blankRatio(tile);
            Log.i(TAG, "tile " + rangeIndex + "." + tileIndex + " req=" + requestedY + " actual=" + target
                    + " ackCss=" + lastAckCssY + " blank="
                    + String.format(java.util.Locale.US, "%.3f", blank)
                    + (attempt > 0 ? " retry=" + attempt : ""));
            if (blank >= BLANK_RATIO && attempt < BLANK_RETRY) {
                // 整屏同色：大概率是滚动后画面还没产出。等久一点重抓；
                // 重试后仍为同色就接受（那片可能确实是空白区域）。
                tile.recycle();
                tile = null;
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        captureTile(mode, w, vpH, ranges, rangeIndex, tileIndex, out, truncatedCount,
                                chunk, canvas, rangeTop, rangeH, cb, attempt + 1);
                    }
                }, 300L * (attempt + 1));
                return;
            }
            if (drawH > 0) {
                android.graphics.Rect src = new android.graphics.Rect(0, 0, w, drawH);
                android.graphics.Rect dst = new android.graphics.Rect(0, dstOffset, w, dstOffset + drawH);
                canvas.drawBitmap(tile, src, dst, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "tile capture failed", t);
        } finally {
            if (tile != null) tile.recycle();
        }
        captureTile(mode, w, vpH, ranges, rangeIndex, tileIndex + 1, out, truncatedCount,
                chunk, canvas, rangeTop, rangeH, cb, 0);
    }

    /** 采样统计「整屏同色」比例：用来识别「滚动后尚未重新合成」的空白帧 */
    private static float blankRatio(Bitmap bmp) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w <= 0 || h <= 0) return 1f;
            int first = bmp.getPixel(0, 0);
            int total = 0, same = 0;
            int xStep = Math.max(1, w / 24), yStep = Math.max(1, h / 40);
            for (int yy = 0; yy < h; yy += yStep) {
                for (int xx = 0; xx < w; xx += xStep) {
                    total++;
                    if (bmp.getPixel(xx, yy) == first) same++;
                }
            }
            return total == 0 ? 1f : (float) same / (float) total;
        } catch (Throwable t) {
            return 0f;      // 采样本身失败就当「有内容」，不要因此反复重试
        }
    }

    private interface ChunkSaver {
        void onSaved(Uri uri, int truncated);
    }

    private void saveChunk(final String mode, final Bitmap chunk, final int index, final int total, final ChunkSaver saver) {
        final String name = "lanyue-" + stamp() + (total > 1 ? "-" + pad(index + 1, 3) : "") + ".png";
        // ② 优先写进系统相册（API 29+ 免权限）；失败或低版本则落到 cacheDir/share 供分享
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = insertIntoMediaStore(chunk, name);
            if (uri != null) {
                saver.onSaved(uri, 0);
                return;
            }
        }
        File dir = ShareProvider.shareDir(activity);
        File f = new File(dir, name);
        OutputStream os = null;
        try {
            os = new FileOutputStream(f);
            chunk.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
            os = null;
            lastExported.add(ShareProvider.uriFor(activity, f));
            Log.i(TAG, "chunk " + (index + 1) + "/" + total + " " + name + " bytes=" + f.length());
            saver.onSaved(ShareProvider.uriFor(activity, f), 0);
        } catch (Throwable t) {
            Log.w(TAG, "save chunk failed", t);
            saver.onSaved(null, 1);
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Uri insertIntoMediaStore(Bitmap bmp, String name) {
        OutputStream os = null;
        Uri uri = null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/蓝阅");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            os = activity.getContentResolver().openOutputStream(uri);
            if (os == null) return null;
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
            os = null;
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            activity.getContentResolver().update(uri, done, null, null);
            lastExported.add(uri);
            return uri;
        } catch (Throwable t) {
            Log.w(TAG, "mediastore insert failed", t);
            return null;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void finishImages(String mode, List<Uri> out, int truncated, Callback cb) {
        float scale = (float) Math.max(1, web.getHeight()) / (float) Math.max(1, layout.viewportHeightCss());
        layout.scrollToPhys(Math.round(savedScrollYcss * scale));
        assets.setHighQuality(false);
        setTouchLocked(false);
        busy = false;
        if (out.isEmpty()) {
            cb.done(false, truncated > 0 ? "导出失败（内存不足）" : "没有任何内容可导出", null);
            return;
        }
        String where = Build.VERSION.SDK_INT >= 29 ? "已保存到相册 Pictures/蓝阅" : "已保存到应用缓存（可分享）";
        String msg = out.size() == 1
                ? "已导出 1 张，" + where
                : "已导出 " + out.size() + " 张" + ("viewport".equals(mode) ? "" : "（自动分片）") + "，" + where;
        cb.done(true, msg, out.size() > 0 ? out.get(0).toString() : null);
    }

    /* ==================================================================
       分享
       ================================================================== */
    public void shareLast(Callback cb) {
        if (lastExported.isEmpty()) {
            cb.done(false, "还没有导出记录", null);
            return;
        }
        try {
            Intent intent;
            if (lastExported.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mimeOfUri(lastExported.get(0)));
                intent.putExtra(Intent.EXTRA_STREAM, lastExported.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.setType("image/png");
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<Uri>(lastExported));
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "分享导出结果"));
            cb.done(true, "已打开分享", null);
        } catch (Throwable t) {
            cb.done(false, "分享失败：" + t.getMessage(), null);
        }
    }

    private static String mimeOfUri(Uri u) {
        String s = u.toString().toLowerCase();
        if (s.endsWith(".pdf")) return "application/pdf";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".md")) return "text/markdown";
        return "*/*";
    }

    /* ==================================================================
       小工具
       ================================================================== */
    private void setTouchLocked(boolean locked) {
        if (locked) {
            web.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return true;    // 导出期间禁止用户滚动干扰抓取
                }
            });
        } else {
            web.setOnTouchListener(null);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String pad(int v, int n) {
        String s = String.valueOf(v);
        while (s.length() < n) s = "0" + s;
        return s;
    }

    private static String stamp() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
        return f.format(new java.util.Date());
    }
}
