package com.dsh.mdreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 文档内图片的解码器。
 *
 * 为什么必须有这一层：样本文档引用了 75 张图、合计 102 MB，
 * 其中若干是 3840×2160 的截图（单张解码后 31 MB）。全部原图解码 ≈ 566 MB，
 * 手机必然 OOM。所以：
 *   1) 默认按「屏幕宽度 × 2」降采样（inSampleSize），滚动列表里永远是小图；
 *   2) 结果做 LRU 缓存（按字节数限流），只保留可见区域附近的图；
 *   3) 只有点开放大 / 导出（hq）时才解码原图。
 * 另外不透明图统一转 JPEG 重编码：截图类 PNG 体积能降一个数量级，而肉眼几乎无差。
 */
public final class ImageLoader {

    /** 显示档（正文滚动里的小图） */
    public static final int TIER_DISPLAY = 0;
    /** 查看档（点开放的这张）：比显示档大，但**绝不给原图** */
    public static final int TIER_ZOOM = 1;
    /** 导出档（仅"高质量导出"打开时）：原图 */
    public static final int TIER_EXPORT = 2;

    /** 查看档的长边上限。原图 3696px 的截图解码后 25.7MB，会让 WebView 分块光栅化出现残块并卡顿。 */
    private static final int ZOOM_MAX_EDGE = 2560;
    /** 查看档的像素预算（约 6MP）——超过就继续降采样 */
    private static final long ZOOM_MAX_PIXELS = 6L * 1000 * 1000;

    private final Context ctx;
    private final FileGate files;
    private final LruCache<String, byte[]> cache;
    private final int screenW;

    public ImageLoader(Context ctx, FileGate files) {
        this.ctx = ctx;
        this.files = files;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        this.screenW = Math.max(320, dm.widthPixels);
        int maxBytes = (int) Math.min(24L * 1024 * 1024, Math.max(4L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10));
        this.cache = new LruCache<String, byte[]>(maxBytes) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value == null ? 0 : value.length;
            }
        };
    }

    /** relPath 形如 "images/001.png"；解析不到或解码失败返回 null */
    public byte[] load(String relPath, int tier) {
        if (relPath == null || relPath.isEmpty()) return null;
        final String key = tier + "|" + relPath;
        byte[] hit = cache.get(key);
        if (hit != null) return hit;
        Uri uri = files != null ? files.resolveImage(relPath) : null;
        if (uri == null) return null;
        byte[] out = decode(uri, tier);
        if (out != null) cache.put(key, out);
        return out;
    }

    /** 各档的目标长边；0 表示不限（导出档）。 */
    private int targetEdge(int tier) {
        if (tier == TIER_EXPORT) return 0;
        int displayEdge = Math.max(480, (int) (screenW * 1.5f));
        if (tier == TIER_DISPLAY) return displayEdge;
        // 查看档：给一点余量（<=ZOOM_MAX_EDGE），但不超过屏幕的 3 倍
        return Math.max(displayEdge, Math.min(ZOOM_MAX_EDGE, screenW * 3));
    }

    private byte[] decode(Uri uri, int tier) {
        InputStream in = null;
        Bitmap bmp = null;
        try {
            // ① 只读边界，拿到原始尺寸
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            closeQuietly(in);
            in = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            // ② 计算采样率（保持 2 的幂，解码器最快）
            int target = targetEdge(tier);
            int sample = 1;
            if (target > 0) {
                int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
                while (longEdge / (sample * 2) >= target) sample *= 2;
                // 再按像素预算兜一层：有些图长边不大但像素极多
                while ((long)(bounds.outWidth / sample) * (bounds.outHeight / sample) > ZOOM_MAX_PIXELS
                        && tier != TIER_EXPORT) {
                    int next = sample * 2;
                    if (longEdge / next < 512) break;      // 别把图降得看不清
                    sample = next;
                }
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            bmp = BitmapFactory.decodeStream(in, null, opts);
            if (bmp == null) return null;

            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64 * 1024, bmp.getByteCount() / 4));
            boolean alpha = bmp.hasAlpha();
            Bitmap.CompressFormat fmt = alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
            int quality = alpha ? 100 : (tier == TIER_EXPORT ? 92 : (tier == TIER_ZOOM ? 88 : 82));
            bmp.compress(fmt, quality, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;      // 图片坏了不该影响整篇文档
        } finally {
            closeQuietly(in);
            if (bmp != null) bmp.recycle();
        }
    }

    public void clearCache() {
        cache.evictAll();
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Exception ignored) {
        }
    }
}
