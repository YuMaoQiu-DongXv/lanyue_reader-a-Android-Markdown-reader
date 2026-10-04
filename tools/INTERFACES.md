# 蓝阅 · Java 模块接口（冻结版 v1）

> 目的：并行开发时不返工。**任何人不得修改下列签名**；需要变更必须先改本文件。
> 所有类都在包 `com.dsh.mdreader`，**只允许用 framework API（禁止 AndroidX / Kotlin）**，
> Java 8 源码级别（`javac --release 8`），编译目标 `minSdk 26`。

## 目录布局

```
app/AndroidManifest.xml
app/java/com/dsh/mdreader/*.java
app/res/{values,values-en,drawable,mipmap-anydpi-v26}/
app/assets/web/{index.html,app.css,app.js,pipeline.js,vendor/…}
build.sh          （免 Gradle：aapt2 + javac + d8 + zipalign + apksigner）
```

## 共享约定

- 编码：所有源文件 UTF-8，`javac -encoding UTF-8`。
- JSON：统一用 `org.json`（framework 自带）。
- 不抛异常给调用方：错误通过回调/返回值传递。
- WebView 侧一律从 `https://appassets.local/` 加载（由 `AssetServer` 拦截），禁止 file:// 与 content:// 直载。

---

## 1. Settings.java

```java
public final class Settings {
    public Settings(Context ctx);

    public String  getTheme();          // "system" | "light" | "dark"
    public void    setTheme(String v);

    public float   getFontScale();      // 0.85 ~ 1.7
    public void    setFontScale(float v);

    public boolean isAutosave();        public void setAutosave(boolean v);
    public boolean isHqExport();        public void setHqExport(boolean v);
    public boolean isAllFilesAccess();  public void setAllFilesAccess(boolean v);

    /** 当前生效的深浅色（theme=system 时跟随系统 uiMode）——主题解析只此一处 */
    public boolean isDarkEffective(Context ctx);

    public String  get(String key, String def);
    public void    set(String key, String value);

    /** 最近文件（JSON 数组，元素 {"uri","name","size","when"}），最多 12 条，按时间倒序 */
    public String  getRecentJson();
    public void    addRecent(String uri, String name, long size);

    public String  getLastDocUri();     public void setLastDocUri(String uri);

    /** 阅读位置（由 WebView 侧上报，key = docKey），值原样存 JSON 字符串 */
    public String  getPos(String docKey);  public void setPos(String docKey, String json);
}
```

## 2. CodecGate.java

```java
public final class CodecGate {
    public static final class Decoded {
        public final String  text;        // 已解码文本（\r\n 已归一化为 \n，BOM 已剥离）
        public final String  encoding;    // "UTF-8" | "GB18030" | "UTF-16LE" | "UTF-16BE"
        public final boolean converted;    // true = 非 UTF-8，保存时会转成 UTF-8，UI 需提示
    }
    /** BOM → 严格 UTF-8 解码 → GB18030 兜底；全失败时按 UTF-8 宽松解码并标记 */
    public static Decoded decode(byte[] bytes);
    public static byte[] utf8(String text);
}
```

## 3. FileGate.java

```java
public final class FileGate {
    public static final class Doc {
        public String  uri;
        public String  name;
        public String  text;
        public String  encoding;   // 展示用
        public boolean canWrite;
        public long    size;
        public String  docKey;     // uri 的稳定哈希，用于位置记忆
    }
    public interface DocCallback { void onDoc(Doc doc); void onError(String msg); }
    public interface VoidCallback { void onDone(boolean ok, String msg); }

    public FileGate(Activity activity, Settings settings);

    public void openFile(DocCallback cb);          // ACTION_OPEN_DOCUMENT，mime text/markdown + text/*
    /**
     * ACTION_OPEN_DOCUMENT_TREE，用于解析相对图片。
     * 语义约定（重要）：授权成功后**不改动当前文档**——
     *   - 若当前有文档：把它重读一遍经 onDoc 回传（内容/docKey 不变，只是 resolveImage 多一条 tree 退路）；
     *   - 若当前没有文档：静默成功（tree 已持久化到 Settings），不回传 onDoc。
     * 这样调用方的"收到 onDoc 就整篇重渲染"逻辑不会被清空正文。
     */
    public void openFolder(DocCallback cb);
    public void openUri(Uri uri, String mime, DocCallback cb);
    public void saveAs(String suggestedName, String text, VoidCallback cb);  // ACTION_CREATE_DOCUMENT
    public boolean saveToCurrent(String text);     // 覆盖保存（canWrite 时），失败返回 false

    /** 把文档内的相对路径解析成可读 Uri；解析不到返回 null（UI 显示"授权文件夹"兜底） */
    public Uri resolveImage(String relPath);

    public String getAuthorizedTreeUri();  public void setAuthorizedTreeUri(String uri);

    /** 从外部 Intent 得到的文档 Uri（ACTION_VIEW / SEND），可能为 null */
    public void handleIntent(Intent intent, DocCallback cb);

    public void onActivityResult(int requestCode, int resultCode, Intent data);  // 由 Activity 转发
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results);
    public String getPendingSaveName();
}
```

## 4. ShareProvider.java

```java
/** 极简 ContentProvider：只暴露 cacheDir/share/ 下的文件，供 ACTION_SEND 分享 */
public final class ShareProvider extends ContentProvider {
    public static final String AUTHORITY = "com.dsh.mdreader.share";
    /** 把 File 描述为可分享的 content:// Uri */
    public static Uri uriFor(Context ctx, File file);
    /** 确保目录存在，返回 cacheDir/share */
    public static File shareDir(Context ctx);
}
```

## 5. AssetServer.java（我负责）

```java
public final class AssetServer {
    public interface DocSource { byte[] docBytes(); }          // 当前文档 UTF-8 字节
    public AssetServer(Context ctx, DocSource doc, ImageLoader images);
    public WebResourceResponse handle(WebResourceRequest req);  // 由 WebViewClient.shouldInterceptRequest 调用
    public static final String ORIGIN = "https://appassets.local";
    public void setHighQuality(boolean hq);                     // 导出期间置 true → 图片原图
}
```

## 6. ImageLoader.java（我负责）

```java
public final class ImageLoader {
    public ImageLoader(Context ctx, FileGate files);
    /** relPath 形如 "images/001.png"；返回解码后的图片字节（默认按屏宽×2 降采样） */
    public byte[] load(String relPath, boolean highQuality);
}
```

## 7. Exporter.java（我负责）

```java
public final class Exporter {
    public interface Callback { void done(boolean ok, String msg, String shareUri); }
    public interface Layout { int contentHeight(); int viewportHeight(); int scrollY(); void scrollTo(int y); }
    public Exporter(Activity a, WebView wv, Settings s, Layout layout, ShareProvider ignored);
    public void exportPdf(boolean hq, Callback cb);
    public void exportImages(String mode, String payloadJson, Callback cb);  // pages|viewport|selection
    public void shareLast(Callback cb);
}
```

## 8. MainActivity / AppBridge（我负责）

JS 侧契约（`window.Lanyue`，由 `AppBridge` 以 `addJavascriptInterface(bridge, "Lanyue")` 注入）：

| JS 调用 | 语义 |
|---|---|
| `getState()` → JSON 字符串 | 主题/字号/编码/最近文件/insets/文件名/settings/webview 版本 |
| `openFile()` / `openFolder()` / `openRecent(uri)` | SAF 打开、目录授权、打开最近 |
| `saveText(text)` / `saveAs(name, text)` | 保存 / 另存为 |
| `editDoc(text)` | 切到原生编辑模式 |
| `exportPdf(hq)` / `exportImages(mode, payloadJson)` / `shareLast()` | 导出 |
| `setSetting(key, value)` / `toast(msg)` / `log(msg)` | 设置与提示 |
| `docReady(statsJson)` / `layout(h, vh, y)` | 渲染完成统计 / 布局上报（导出几何用） |
| `openUrl(url)` | 外链交系统浏览器 |

Activity → JS：`LanyueApp.applyState(json)`、`LanyueApp.toast(msg)`、`LanyueApp.notify(kind, ok, msg)`、`LanyueApp.reloadDoc()`、`LanyueApp.scrollToTop()`。
