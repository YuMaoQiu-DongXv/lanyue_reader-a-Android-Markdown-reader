package com.dsh.mdreader;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出：PDF（系统打印链路）与长图。
 *
 * <h3>长图：为什么不是"硬件合成帧"路线</h3>
 * 最早的实现是"滚动一屏 → {@code web.draw()} 抓一屏 → 拼接"。它有两个死结：
 * <ol>
 *   <li><b>抓不准</b>：{@code draw()} 对硬件加速的 WebView 拿到的是**已经合成好的那一帧**，
 *       而滚动是异步的（Chromium 在合成线程上干活）。滚动之后能不能抓到新画面全看时序，
 *       表现就是"只有开头一段有内容、之后全白"。</li>
 *   <li><b>观感差</b>：天然是 O(N) 次滚动 + 等待，慢，而且屏幕上能看见页面在滚。</li>
 * </ol>
 *
 * <h3>现在的机制：软件渲染层 + 全屏遮挡 + 同步绘制</h3>
 * <ol>
 *   <li>先把 WebView 切成 {@link android.view.View#LAYER_TYPE_SOFTWARE}：
 *       软件路径下 {@code draw()} 是**同步确定性**的 —— 它拿的就是"当前滚动位置上该有的内容"，
 *       不再和合成器赛跑（这是本方案与旧实现唯一的、也是最关键的区别）；</li>
 *   <li>同时盖一层不透明的原生覆盖层：用户看到的是"正在导出 N/M"，而不是页面在滚；</li>
 *   <li>逐屏：滚动 → 等两帧（软件路径下光栅化发生在 draw 时，等两帧只是让滚动状态落定）
 *       → 同步 draw 抓一屏 → 贴进分片位图；</li>
 *   <li>抓到的整屏若同色（说明这一屏确实还没画出来）就重试至多两次；</li>
 *   <li>分片高度按可用堆内存收敛，写相册。</li>
 * </ol>
 *
 * <p>另外记录一条已研究过、但 API 层面走不通的路线，免得以后有人再撞：
 * 让 WebView 的 PrintDocumentAdapter 把整篇排版成"一页超长 PDF"，再用 PdfRenderer 分带栅格化。
 * 这条路在 API 层面做不了 —— LayoutResultCallback / WriteResultCallback 的构造函数是**包内可见**的
 * （框架刻意不允许应用手动驱动打印流程），网上那些"WebView 直接存 PDF"的做法都靠反射非公开成员，
 * 而反射非公开框架成员属于隐藏 API，行为不受保证。结论：不弹系统打印界面就拿不到那个 PDF。
 */
public final class Exporter {

    private static final String TAG = "LanyueExport";

    /** 每片的输出像素上限（用户批准的值） */
    private static final int REQ_CHUNK_PX = 12000;
    /** 滚动后等待的毫秒数（软件路径下 draw 时才光栅化，这里只是让滚动状态落定） */
    private static final int SETTLE_MS = 60;
    /** 「整屏同色」判定阈值 */
    private static final float BLANK_RATIO = 0.9985f;
    /** 疑似空白帧的重试次数 */
    private static final int BLANK_RETRY = 2;
    /** 切软件层后等它生效的时间 */
    private static final int LAYER_SWITCH_MS = 150;
    /** 整个导出流程的看门狗 */
    private static final long WATCHDOG_MS = 180_000;

    public interface Callback {
        /** shareUri 非空表示可直接分享（cacheDir/share 下的文件） */
        void done(boolean ok, String msg, String shareUri);
    }

    /** 由 MainActivity 提供宿主能力（几何、滚动、覆盖层、渲染层切换） */
    public interface Host {
        /** 正文总高度（CSS px） */
        int contentHeightCss();

        /** 视口高度（CSS px） */
        int viewportHeightCss();

        /** 当前滚动位置（CSS px） */
        int scrollYCss();

        /** 滚动到指定位置（**物理像素**；入参单位写在名字里，别再乘 density） */
        void scrollToPhys(int physY);

        /** 显示不透明导出覆盖层（遮住滚动过程） */
        void showExportOverlay(String title);

        /** 更新覆盖层进度 */
        void updateExportOverlay(int done, int total);

        /** 移除覆盖层 */
        void hideExportOverlay();

        /** 切换软件渲染层（导出期间开启，结束后必须恢复） */
        void setSoftwareLayer(boolean on);
    }

    private final Activity activity;
    private final WebView web;
    private final Settings settings;
    private final Host host;
    private final AssetServer assets;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Uri> lastExported = new ArrayList<Uri>();
    private boolean busy = false;
    private int savedScrollYcss = 0;
    private int contentPhysPx = 0;
    private Runnable watchdog;
    private Callback printCb;
    private Runnable hqReset;

    public Exporter(Activity activity, WebView web, Settings settings, Host host, AssetServer assets) {
        this.activity = activity;
        this.web = web;
        this.settings = settings;
        this.host = host;
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
       长图：软件渲染层 + 同步逐屏绘制
       ================================================================== */
    public void exportImages(String mode, String payloadJson, final Callback cb) {
        if (busy) {
            cb.done(false, "正在导出中，请稍候", null);
            return;
        }
        if (web == null || web.getWidth() <= 0) {
            cb.done(false, "页面未就绪", null);
            return;
        }

        int viewportWcss = 0, docHcss = 0, scrollYcss = 0, topCss = 0, bottomCss = 0;
        try {
            JSONObject p = (payloadJson == null || payloadJson.isEmpty())
                    ? new JSONObject() : new JSONObject(payloadJson);
            viewportWcss = p.optInt("viewportW", 0);
            docHcss = p.optInt("contentH", 0);
            scrollYcss = p.optInt("scrollY", 0);
            topCss = p.optInt("top", 0);
            bottomCss = p.optInt("bottom", 0);
        } catch (Throwable ignored) {
        }
        int vpHcss = Math.max(1, host.viewportHeightCss());
        if (docHcss <= 0) docHcss = Math.max(host.contentHeightCss(), vpHcss);
        if ("viewport".equals(mode)) {
            topCss = scrollYcss;
            bottomCss = scrollYcss + vpHcss;
        } else if (!"selection".equals(mode)) {
            topCss = 0;
            bottomCss = docHcss;
        }
        topCss = clamp(topCss, 0, Math.max(0, docHcss - 1));
        bottomCss = clamp(bottomCss, topCss + 1, docHcss);

        final int w = web.getWidth();
        final int vpH = web.getHeight();
        final float scale = (float) vpH / (float) vpHcss;                  // 物理像素 / CSS 像素
        final int contentPhys = Math.max(vpH, Math.round(docHcss * scale));
        final int topPhys = clamp(Math.round(topCss * scale), 0, Math.max(0, contentPhys - 1));
        final int bottomPhys = clamp(Math.round(bottomCss * scale), topPhys + 1, contentPhys);
        contentPhysPx = contentPhys;
        savedScrollYcss = host.scrollYCss();

        // 每片高度按可用堆内存收敛：位图 = w × chunkH × 4 字节
        long maxHeap = Runtime.getRuntime().maxMemory();
        long budget = Math.max(8L * 1024 * 1024, (long) (maxHeap * 0.28));
        int memCap = (int) Math.max(1200, budget / (4L * Math.max(1, w)));
        final int chunkH = Math.max(400, Math.min(REQ_CHUNK_PX, memCap));

        List<int[]> ranges = new ArrayList<int[]>();
        for (int y = topPhys; y < bottomPhys; y += chunkH) {
            ranges.add(new int[]{y, Math.min(bottomPhys, y + chunkH)});
        }
        Log.i(TAG, "long-image start mode=" + mode + " chunks=" + ranges.size() + " chunkH=" + chunkH
                + " content=" + contentPhys + "px vp=" + vpH + "px region=" + topPhys + ".." + bottomPhys
                + " | contentCss=" + docHcss + " vpCss=" + vpHcss + " viewportWcss=" + viewportWcss
                + " scale=" + String.format(java.util.Locale.US, "%.3f", scale));

        busy = true;
        lastExported.clear();
        armWatchdog(cb);
        host.showExportOverlay("正在导出长图…");
        host.setSoftwareLayer(true);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                captureRange(ranges, 0, 0, new ArrayList<Uri>(), w, vpH, chunkH, cb);
            }
        }, LAYER_SWITCH_MS);
    }

    private void captureRange(final List<int[]> ranges, final int rangeIndex, final int tileIndex,
                              final List<Uri> out, final int w, final int vpH, final int chunkH, final Callback cb) {
        if (rangeIndex >= ranges.size()) {
            finishImages(out, cb);
            return;
        }
        final int[] r = ranges.get(rangeIndex);
        final int rangeH = r[1] - r[0];
        final Bitmap chunk = Bitmap.createBitmap(w, rangeH, Bitmap.Config.ARGB_8888);
        new Canvas(chunk).drawColor(Color.WHITE);
        captureTile(ranges, rangeIndex, tileIndex, out, w, vpH, chunkH, chunk, r[0], rangeH, cb, 0);
    }

    private void captureTile(final List<int[]> ranges, final int rangeIndex, final int tileIndex,
                             final List<Uri> out, final int w, final int vpH, final int chunkH,
                             final Bitmap chunk, final int rangeTop, final int rangeH,
                             final Callback cb, final int attempt) {
        final int[] r = ranges.get(rangeIndex);
        final int y = rangeTop + tileIndex * vpH;
        if (y >= r[1]) {
            // 本片抓完：写盘
            saveImage(chunk, rangeIndex + 1, ranges.size());
            chunk.recycle();
            captureRange(ranges, rangeIndex + 1, 0, out, w, vpH, chunkH, cb);
            return;
        }
        // 滚到底部时 WebView 会钳制在最大滚动位置：按"实际位置"贴图，末片才不会重复最后一屏
        final int maxScroll = Math.max(0, contentPhysPx - vpH);
        final int target = clamp(y, 0, maxScroll);
        final int dstOffset = Math.max(0, target - rangeTop);
        final int drawH = Math.min(vpH, Math.max(0, rangeH - dstOffset));

        host.scrollToPhys(target);
        host.updateExportOverlay(rangeIndex + 1, ranges.size());
        // 软件路径下 draw() 才是真正的光栅化时点，所以这里等两帧只是让滚动状态落定
        web.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                web.postOnAnimation(new Runnable() {
                    @Override
                    public void run() {
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                drawTile(ranges, rangeIndex, tileIndex, out, w, vpH, chunkH, chunk,
                                        rangeTop, rangeH, cb, attempt, y, target, dstOffset, drawH);
                            }
                        }, SETTLE_MS);
                    }
                });
            }
        });
    }

    private void drawTile(final List<int[]> ranges, final int rangeIndex, final int tileIndex,
                          final List<Uri> out, final int w, final int vpH, final int chunkH,
                          final Bitmap chunk, final int rangeTop, final int rangeH, final Callback cb,
                          final int attempt, final int requestedY, final int target,
                          final int dstOffset, final int drawH) {
        Bitmap tile = null;
        try {
            tile = Bitmap.createBitmap(w, vpH, Bitmap.Config.ARGB_8888);
            web.draw(new Canvas(tile));
            float blank = blankRatio(tile);
            Log.i(TAG, "tile " + rangeIndex + "." + tileIndex + " req=" + requestedY + " actual=" + target
                    + " blank=" + String.format(java.util.Locale.US, "%.3f", blank)
                    + (attempt > 0 ? " retry=" + attempt : ""));
            if (blank >= BLANK_RATIO && attempt < BLANK_RETRY) {
                tile.recycle();
                tile = null;
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        captureTile(ranges, rangeIndex, tileIndex, out, w, vpH, chunkH, chunk,
                                rangeTop, rangeH, cb, attempt + 1);
                    }
                }, 250L * (attempt + 1));
                return;
            }
            if (drawH > 0) {
                android.graphics.Rect src = new android.graphics.Rect(0, 0, w, drawH);
                android.graphics.Rect dst = new android.graphics.Rect(0, dstOffset, w, dstOffset + drawH);
                new Canvas(chunk).drawBitmap(tile, src, dst, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "tile draw failed", t);
        } finally {
            if (tile != null) tile.recycle();
        }
        captureTile(ranges, rangeIndex, tileIndex + 1, out, w, vpH, chunkH, chunk,
                rangeTop, rangeH, cb, 0);
    }

    /** 采样统计「整屏同色」比例：识别"这一屏压根没画出来" */
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
            return 0f;      // 采样失败就当"有内容"，不要因此反复重试
        }
    }

    /* ==================================================================
       落盘与收尾
       ================================================================== */
    private void saveImage(Bitmap bmp, int index, int total) {
        final String name = "lanyue-" + stamp() + (total > 1 ? "-" + pad(index, 3) : "") + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = insertIntoMediaStore(bmp, name);
                if (uri != null) {
                    Log.i(TAG, "saved " + name + " " + bmp.getWidth() + "x" + bmp.getHeight());
                    return;
                }
            }
            File dir = ShareProvider.shareDir(activity);
            File f = new File(dir, name);
            OutputStream os = null;
            try {
                os = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.flush();
                os.close();
                os = null;
                lastExported.add(ShareProvider.uriFor(activity, f));
                Log.i(TAG, "saved " + name + " bytes=" + f.length());
            } catch (Throwable t) {
                Log.w(TAG, "save image failed", t);
            } finally {
                closeQuietly(os);
            }
        } catch (Throwable t) {
            Log.w(TAG, "saveImage failed", t);
        }
    }

    private Uri insertIntoMediaStore(Bitmap bmp, String name) {
        OutputStream os = null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/蓝阅");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
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
            closeQuietly(os);
        }
    }

    private void finishImages(List<Uri> out, Callback cb) {
        endImageMode();
        clearWatchdog();
        busy = false;
        assets.setHighQuality(false);
        if (out == null || out.isEmpty()) {
            cb.done(false, "没有任何内容可导出", null);
            return;
        }
        String where = Build.VERSION.SDK_INT >= 29 ? "已保存到相册 Pictures/蓝阅" : "已保存到应用缓存（可分享）";
        String msg = out.size() == 1 ? "已导出 1 张，" + where
                : "已导出 " + out.size() + " 张（自动分片），" + where;
        cb.done(true, msg, out.get(0).toString());
    }

    private void fail(Callback cb, String msg) {
        endImageMode();
        clearWatchdog();
        busy = false;
        assets.setHighQuality(false);
        cb.done(false, msg, null);
    }

    /** 收尾：恢复硬件渲染层、恢复滚动位置、隐藏覆盖层 */
    private void endImageMode() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (contentPhysPx > 0) {
                    float scale = (float) Math.max(1, web.getHeight())
                            / (float) Math.max(1, host.viewportHeightCss());
                    host.scrollToPhys(Math.round(savedScrollYcss * scale));
                }
                host.setSoftwareLayer(false);
                host.hideExportOverlay();
            }
        });
    }

    private void armWatchdog(final Callback cb) {
        clearWatchdog();
        watchdog = new Runnable() {
            @Override
            public void run() {
                Log.w(TAG, "watchdog fired");
                fail(cb, "导出超时，请重试");
            }
        };
        ui.postDelayed(watchdog, WATCHDOG_MS);
    }

    private void clearWatchdog() {
        if (watchdog != null) {
            ui.removeCallbacks(watchdog);
            watchdog = null;
        }
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

    private static void closeQuietly(Object c) {
        if (c == null) return;
        try {
            if (c instanceof OutputStream) ((OutputStream) c).close();
        } catch (Throwable ignored) {
        }
    }
}
