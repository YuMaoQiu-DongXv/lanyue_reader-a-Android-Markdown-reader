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
    private static final int SETTLE_MS = 130;           // 滚动后等待渲染稳定的时间

    public interface Callback {
        /** shareUri 非空表示可直接分享（cacheDir/share 下的文件） */
        void done(boolean ok, String msg, String shareUri);
    }

    /** 由 MainActivity 提供 WebView 的几何信息（数值来自 JS 上报的 CSS 像素） */
    public interface Layout {
        int contentHeight();

        int viewportHeight();

        int scrollY();

        void scrollTo(int y);
    }

    private final Activity activity;
    private final WebView web;
    private final Settings settings;
    private final Layout layout;
    private final AssetServer assets;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Uri> lastExported = new ArrayList<Uri>();
    private boolean busy = false;
    private int savedScrollY = 0;
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
                    PrintAttributes attrs = new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setMinMargins(new PrintAttributes.Margins(mmToMils(15), mmToMils(15), mmToMils(15), mmToMils(15)))
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
        int vpCss = Math.max(1, layout.viewportHeight());
        int vpH = web.getHeight();
        final float scale = (float) vpH / (float) vpCss;          // 物理像素 / CSS 像素
        final int w = web.getWidth();
        final int contentPhys = Math.max(vpH, Math.round(layout.contentHeight() * scale));

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
        savedScrollY = layout.scrollY();
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
            int y = clamp(layout.scrollY(), 0, Math.max(0, contentPhys - vpH));
            ranges.add(new int[]{y, y + vpH});
        } else {
            for (int y = top; y < bottom; y += chunkH) {
                ranges.add(new int[]{y, Math.min(bottom, y + chunkH)});
            }
        }
        Log.i(TAG, "capture ranges=" + ranges.size() + " chunkH=" + chunkH + " content=" + contentPhys + " vp=" + vpH);
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
        captureTile(mode, w, vpH, ranges, rangeIndex, tileIndex, out, truncatedCount, chunk, c, r[0], rangeH, cb);
    }

    private void captureTile(final String mode, final int w, final int vpH, final List<int[]> ranges,
                             final int rangeIndex, final int tileIndex, final List<Uri> out,
                             final int truncatedCount, final Bitmap chunk, final Canvas canvas,
                             final int rangeTop, final int rangeH, final Callback cb) {
        final int[] r = ranges.get(rangeIndex);
        int y = rangeTop + tileIndex * vpH;
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
        final int scrollTarget = y;
        layout.scrollTo(scrollTarget);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap tile = Bitmap.createBitmap(w, vpH, Bitmap.Config.ARGB_8888);
                    web.draw(new Canvas(tile));
                    int dy = tileIndex * vpH;                        // 本屏在这一片里的纵向偏移
                    int drawH = Math.min(vpH, Math.max(0, rangeH - dy));
                    if (drawH > 0) {
                        android.graphics.Rect src = new android.graphics.Rect(0, 0, w, drawH);
                        android.graphics.Rect dst = new android.graphics.Rect(0, dy, w, dy + drawH);
                        canvas.drawBitmap(tile, src, dst, null);
                    }
                    tile.recycle();
                } catch (Throwable t) {
                    Log.w(TAG, "tile capture failed", t);
                }
                captureTile(mode, w, vpH, ranges, rangeIndex, tileIndex + 1, out, truncatedCount, chunk, canvas, rangeTop, rangeH, cb);
            }
        }, SETTLE_MS);
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
        layout.scrollTo(savedScrollY);
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
