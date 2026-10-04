package com.dsh.mdreader;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把 https://appassets.local/ 的全部请求就地应答，不产生任何真实网络访问。
 *
 * 之所以用 https + 假域名（而不是 file:// 或自定义 scheme）：
 *   1) 网页的 fetch()/XHR 在同源 https 下不受限（自定义 scheme 会被 fetch 拒绝）；
 *   2) DOM storage / Clipboard API 需要「安全上下文」，https 满足；
 *   3) 应用不申请 INTERNET 权限，任何未被拦截的请求必然失败 —— 等于天然的断网保险。
 *
 * 路由：
 *   /doc.md            当前文档正文（UTF-8 字节，已由 CodecGate 转过码）
 *   /img/<相对路径>     文档内的图片（按屏宽×2 降采样；?hq=1 或导出期间为原图）
 *   /vendor/…  /app.js  /app.css  /pipeline.js  /index.html  → assets/web/
 */
public final class AssetServer {

    public static final String ORIGIN = "https://appassets.local";
    public static final String INDEX = ORIGIN + "/index.html";

    /** 当前文档正文的提供者（由 MainActivity 实现） */
    public interface DocSource {
        byte[] docBytes();
    }

    private final Context ctx;
    private final DocSource docSource;
    private final ImageLoader images;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile boolean highQuality = false;

    public AssetServer(Context ctx, DocSource docSource, ImageLoader images) {
        this.ctx = ctx;
        this.docSource = docSource;
        this.images = images;
    }

    /** 导出（PDF / 长图）期间置 true，让图片以更高分辨率进入渲染 */
    public void setHighQuality(boolean hq) {
        this.highQuality = hq;
    }

    public boolean isHighQuality() {
        return highQuality;
    }

    /** 由 WebViewClient.shouldInterceptRequest 调用；返回 null 表示不接管 */
    public WebResourceResponse handle(WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return null;
        String url = request.getUrl().toString();
        if (!url.startsWith(ORIGIN)) return null;

        String path = url.substring(ORIGIN.length());
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }
        path = decode(path);
        if (path.isEmpty() || "/".equals(path)) path = "/index.html";

        // 真机排查用证据：前 8 次拦截逐条打印，之后的 404 也会告警。
        // 若页面白屏，先看 adb logcat -s LanyueAsset，就能判断到底是没被拦截、还是资源没找到。
        int n = hits.incrementAndGet();
        if (n <= 8) android.util.Log.i("LanyueAsset", "INTERCEPT #" + n + " " + path);

        if ("/doc.md".equals(path)) {
            byte[] body = docSource != null ? docSource.docBytes() : null;
            if (body == null) body = "文档未打开".getBytes();
            return response("text/markdown", "utf-8", body, null);
        }

        if (path.startsWith("/img/")) {
            String rel = path.substring("/img/".length());
            boolean hq = highQuality || query.contains("hq=1");
            byte[] body = images != null ? images.load(rel, hq) : null;
            if (body == null) {
                android.util.Log.w("LanyueAsset", "IMG MISS " + rel + "（同级目录未授权或文件不存在）");
                return notFound();
            }
            return response(mimeOf(rel), null, body, null);
        }

        String assetPath = path.startsWith("/") ? path.substring(1) : path;
        InputStream in = openAsset(assetPath);
        if (in == null) {
            android.util.Log.w("LanyueAsset", "ASSET MISS " + assetPath);
            return notFound();
        }
        String mime = mimeOf(assetPath);
        return new WebResourceResponse(mime, isText(mime) ? "utf-8" : null, 200, "OK", noStore(null), in);
    }

    /**
     * 从 assets/web/ 打开文件。
     *
     * 兜底说明：在 Windows 上 aapt2 link -A 会把子目录条目的名字写成反斜杠
     * （assets/web\app.js），而 AssetManager 只认正斜杠 —— 那时第一种方式必然失败。
     * 构建脚本里有一步把这个名字改回来；这里的反斜杠兜底是第二道保险，
     * 保证即使构建期修复被跳过，运行时也不会整个前端 404。
     */
    private InputStream openAsset(String path) {
        try {
            return ctx.getAssets().open("web/" + path);
        } catch (IOException e) {
            try {
                return ctx.getAssets().open("web\\" + path.replace('/', '\\'));
            } catch (IOException e2) {
                return null;
            }
        }
    }

    /**
     * 统一附加 no-store。
     *
     * 这一步不是洁癖，是必要的：图片第一次请求失败会返回 404，而 Chromium 对没有缓存头的
     * 响应会做启发式缓存 —— 用户随后"授权文件夹"再重新渲染时，同一个图片 URL 可能直接命中
     * 那个缓存的 404，于是**永远显示不出来**。全量禁缓存后，授权后重新渲染必定重新请求。
     */
    private static Map<String, String> noStore(Map<String, String> extra) {
        Map<String, String> h = (extra == null) ? new HashMap<String, String>() : extra;
        h.put("Cache-Control", "no-store, no-cache, must-revalidate");
        h.put("Pragma", "no-cache");
        return h;
    }

    private static WebResourceResponse response(String mime, String encoding, byte[] body, Map<String, String> headers) {
        return new WebResourceResponse(mime, encoding, 200, "OK", noStore(headers), new ByteArrayInputStream(body));
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", noStore(null),
                new ByteArrayInputStream("404".getBytes()));
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        } catch (IllegalArgumentException e) {
            return s;   // 非法百分号编码
        }
    }

    private static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")
                || mime.contains("svg");
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".md") || n.endsWith(".markdown")) return "text/markdown";
        return "application/octet-stream";
    }
}
