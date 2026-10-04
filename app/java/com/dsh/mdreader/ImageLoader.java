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

    private final Context ctx;
    private final FileGate files;
    private final LruCache<String, byte[]> cache;
    private final int targetWidth;

    public ImageLoader(Context ctx, FileGate files) {
        this.ctx = ctx;
        this.files = files;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        this.targetWidth = Math.max(480, dm.widthPixels * 2);
        int maxBytes = (int) Math.min(24L * 1024 * 1024, Math.max(4L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10));
        this.cache = new LruCache<String, byte[]>(maxBytes) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value == null ? 0 : value.length;
            }
        };
    }

    /** relPath 形如 "images/001.png"；解析不到或解码失败返回 null */
    public byte[] load(String relPath, boolean highQuality) {
        if (relPath == null || relPath.isEmpty()) return null;
        final String key = (highQuality ? "HQ|" : "LQ|") + relPath;
        byte[] hit = cache.get(key);
        if (hit != null) return hit;
        Uri uri = files != null ? files.resolveImage(relPath) : null;
        if (uri == null) return null;
        byte[] out = decode(uri, highQuality);
        if (out != null) cache.put(key, out);
        return out;
    }

    private byte[] decode(Uri uri, boolean highQuality) {
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
            int sample = 1;
            if (!highQuality) {
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
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
            int quality = alpha ? 100 : (highQuality ? 92 : 82);
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
