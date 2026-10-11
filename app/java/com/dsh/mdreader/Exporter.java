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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出：PDF（系统打印链路）与「当前屏」图片。
 *
 * <h3>为什么不再有「长图导出」</h3>
 * 这个功能做过、也反复修过多轮，最终整体移除 —— 它和本项目「零权限 + 轻量」的定位不划算：
 * <ol>
 *   <li><b>滚动抓取在 WebView 上不可靠</b>：硬件加速下 WebView 把内容渲染到自己的 surface，
 *       {@code View.draw()} 拿到的是「已经合成好的那一帧」，而滚动是异步的（Chromium 在合成线程干活）。
 *       抓早了就是整片空白 —— 真机表现正是「只有开头一段有内容、之后全白」。
 *       后来补的「滚动回执 + 空白重试」只是把等待做得更巧，机制没变。</li>
 *   <li><b>更好的一条路被平台堵死</b>：让 WebView 的 {@link PrintDocumentAdapter} 把整篇排版成
 *       「一页超长 PDF」再栅格化，本可以一趟排版、完全不滚动。但
 *       {@code PrintDocumentAdapter.LayoutResultCallback} / {@code WriteResultCallback} 的构造函数是
 *       <b>包内可见</b>的（javac 实测：is not public in LayoutResultCallback; cannot be accessed from
 *       outside package）—— 框架刻意不允许应用手动驱动打印流程；网上那些「WebView 直接存 PDF」的做法
 *       都靠反射非公开成员，属隐藏 API，行为不受保证。</li>
 *   <li><b>软件渲染层方案仍要 O(N) 次滚动</b>：能保证抓得准，但依旧慢、依旧需要遮挡，
 *       而它换来的收益（一张超长图）用「导出 PDF」就能覆盖。</li>
 * </ol>
 * 需要整篇留档时请用 <b>PDF 导出</b>（系统打印 → 另存为 PDF）—— 那条链路权威且可靠。
 *
 * <p>保留下来的「当前屏」导出不存在上述任何问题：屏幕上已经渲染好的这一帧<strong>直接同步绘制</strong>，
 * 不滚动、不等时序、不需要额外权限。
 */
public final class Exporter {

    private static final String TAG = "LanyueExport";
    /** 等固定顶栏隐藏后的重排落定（JS 在调用前刚把它藏起来，否则顶栏会被拍进图里） */
    private static final int CAPTURE_DELAY_MS = 60;

    public interface Callback {
        /** shareUri 非空表示可直接分享（cacheDir/share 下的文件） */
        void done(boolean ok, String msg, String shareUri);
    }

    private final Activity activity;
    private final WebView web;
    private final Settings settings;
    private final AssetServer assets;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Uri> lastExported = new ArrayList<Uri>();
    private Callback printCb;
    private Runnable hqReset;

    public Exporter(Activity activity, WebView web, Settings settings, AssetServer assets) {
        this.activity = activity;
        this.web = web;
        this.settings = settings;
        this.assets = assets;
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
       当前屏：屏幕上这一帧已经渲染好了，直接同步绘制
       ================================================================== */
    public void exportScreen(final Callback cb) {
        if (web == null || web.getWidth() <= 0) {
            cb.done(false, "页面未就绪", null);
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    bmp = Bitmap.createBitmap(web.getWidth(), web.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    c.drawColor(Color.WHITE);      // WebView 可能是透明底，先铺白
                    web.draw(c);
                    Uri uri = saveImage(bmp, "lanyue-" + stamp() + ".png");
                    if (uri == null) {
                        cb.done(false, "保存失败（存储空间不足？）", null);
                        return;
                    }
                    lastExported.clear();
                    lastExported.add(uri);
                    String where = Build.VERSION.SDK_INT >= 29
                            ? "已保存到相册 Pictures/蓝阅" : "已保存到应用缓存（可分享）";
                    Log.i(TAG, "screen exported " + bmp.getWidth() + "x" + bmp.getHeight());
                    cb.done(true, "已导出当前屏，" + where, uri.toString());
                } catch (Throwable t) {
                    Log.w(TAG, "capture screen failed", t);
                    cb.done(false, "导出失败：" + t.getMessage(), null);
                } finally {
                    if (bmp != null) bmp.recycle();
                }
            }
        }, CAPTURE_DELAY_MS);
    }

    /** API 29+ 免权限写相册；失败或低版本落到 cacheDir/share 供分享 */
    private Uri saveImage(Bitmap bmp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = insertIntoMediaStore(bmp, name);
                if (uri != null) return uri;
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
                Log.i(TAG, "saved " + name + " bytes=" + f.length());
                return ShareProvider.uriFor(activity, f);
            } catch (Throwable t) {
                Log.w(TAG, "save image failed", t);
                return null;
            } finally {
                closeQuietly(os);
            }
        } catch (Throwable t) {
            Log.w(TAG, "saveImage failed", t);
            return null;
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
            return uri;
        } catch (Throwable t) {
            Log.w(TAG, "mediastore insert failed", t);
            return null;
        } finally {
            closeQuietly(os);
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
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeOfUri(lastExported.get(0)));
            intent.putExtra(Intent.EXTRA_STREAM, lastExported.get(0));
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
