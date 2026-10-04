package com.dsh.mdreader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 蓝阅 · 文件闸门：SAF(Storage Access Framework) 打开 / 另存 / 覆盖保存 / 相对图片解析。
 *
 * <p>硬约束与取舍：
 * <ul>
 *   <li>只用 {@link Activity#startActivityForResult}（禁止 AndroidX 的
 *       registerForActivityResult / ActivityResultLauncher）。</li>
 *   <li>一次只允许一个 pending 请求：发起新请求会覆盖旧回调，旧回调**不会被调用**
 *       （用户若同时点两次打开，只有最后一次生效，避免回调错位）。</li>
 *   <li>所有 IO 都 try/catch，错误只从 {@code cb.onError(msg)} 出去，绝不向调用方抛异常，
 *       也绝不崩溃。</li>
 *   <li>{@link #openUri} 是**同步**读文件的（回调在调用者线程上触发）。这样 MainActivity
 *       不必担心回调线程切换；代价是超大文件会占用主线程一小段时间（见 {@link #MAX_BYTES}）。</li>
 *   <li>本类**不**写 Settings 的最近文件（recent）；那是 MainActivity/AppBridge 的策略，
 *       避免临时分享 Uri 污染最近列表。唯一例外是 {@link #saveAs} 成功后需要把"当前文档"
 *       指向新文件（接口的 VoidCallback 不返回 Uri，没有别的办法），见该方法注释。</li>
 * </ul>
 */
public final class FileGate {

    /** 一份已打开的文档。 */
    public static final class Doc {
        public String  uri;
        public String  name;
        public String  text;
        /** 展示用编码名，如 "UTF-8" / "GB18030"。 */
        public String  encoding;
        public boolean canWrite;
        public long    size;
        /** uri 的稳定哈希（SHA-256 前 16 位十六进制），用于位置记忆。 */
        public String  docKey;
    }

    public interface DocCallback { void onDoc(Doc doc); void onError(String msg); }

    public interface VoidCallback { void onDone(boolean ok, String msg); }

    /** 请求码：三个入口各自独立，便于分发。 */
    private static final int REQ_OPEN_FILE   = 0x4D01;
    private static final int REQ_OPEN_FOLDER = 0x4D02;
    private static final int REQ_SAVE_AS     = 0x4D03;

    /** 打开时放宽的 MIME 白名单（不少设备把 .md 注册成 text/plain）。 */
    private static final String[] OPEN_MIME = {
            "text/markdown", "text/x-markdown", "text/plain"
    };

    private static final String MIME_MD = "text/markdown";

    /** 单次读取上限，防 OOM（OOM 也是"崩溃"，必须挡住）。 */
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private static final String K_TREE = "tree_uri";

    private final Activity activity;
    private final Settings settings;
    private final ContentResolver resolver;

    // ---- pending（一次只有一个） ----
    private int         pendingCode = 0;
    private DocCallback pendingDoc;
    private VoidCallback pendingSave;
    private String      pendingSaveName = "";
    private String      pendingSaveText = "";

    // ---- 当前文档（供 saveToCurrent / resolveImage 使用） ----
    private Uri     currentUri;
    private boolean currentCanWrite;

    public FileGate(Activity activity, Settings settings) {
        this.activity = activity;
        this.settings = settings;
        this.resolver = activity.getContentResolver();
    }

    // ================================================================ 打开文件

    /** ACTION_OPEN_DOCUMENT：mime 白名单 + CATEGORY_OPENABLE。 */
    public void openFile(DocCallback cb) {
        if (cb == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, OPEN_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (!launch(i, REQ_OPEN_FILE)) {
            cb.onError("没有可用的文件选择器");
            return;
        }
        startPending(REQ_OPEN_FILE, cb);
    }

    /** ACTION_OPEN_DOCUMENT_TREE：授权一个文件夹，永久记住，用于解析文内相对图片。 */
    public void openFolder(DocCallback cb) {
        if (cb == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (!launch(i, REQ_OPEN_FOLDER)) {
            cb.onError("没有可用的文件夹选择器");
            return;
        }
        startPending(REQ_OPEN_FOLDER, cb);
    }

    /** 直接打开一个已知 Uri（最近文件、外部 Intent 都用它）。 */
    public void openUri(Uri uri, String mime, DocCallback cb) {
        if (cb == null) {
            return;
        }
        if (uri == null) {
            cb.onError("没有可用的文件地址");
            return;
        }
        // mime 仅作提示：真实类型由 ContentResolver/DocumentsContract 决定，故此处不参与判定
        try {
            byte[] raw = readBytes(uri);
            CodecGate.Decoded dec = CodecGate.decode(raw);
            Doc d = new Doc();
            d.uri = uri.toString();
            d.name = displayName(uri);
            d.text = dec.text;
            d.encoding = dec.encoding;
            d.size = raw.length;
            d.canWrite = testWritable(uri);
            d.docKey = sha16(d.uri);
            currentUri = uri;
            currentCanWrite = d.canWrite;
            cb.onDoc(d);
        } catch (OutOfMemoryError oom) {
            cb.onError("文件太大，内存不足");
        } catch (Throwable t) {
            cb.onError("打开失败：" + describe(t));
        }
    }

    // ================================================================ 保存

    /**
     * ACTION_CREATE_DOCUMENT + {@code text/markdown}；内容以 UTF-8 写入。
     *
     * <p>注意（接口限制）：{@code VoidCallback} 不带回新 Uri，所以保存成功后本类会把
     * "当前文档"切到新文件（{@link #saveToCurrent} 之后写的就是它），并更新
     * {@code lastDocUri} 与最近列表。若产品语义是"另存为一份副本、当前文档不动"，
     * 这块需要接口扩展一个带 Uri 的回调。
     */
    public void saveAs(String suggestedName, String text, VoidCallback cb) {
        if (cb == null) {
            return;
        }
        String name = ensureMd(suggestedName);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(MIME_MD);
        i.putExtra(Intent.EXTRA_TITLE, name);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (!launch(i, REQ_SAVE_AS)) {
            cb.onDone(false, "没有可用的保存位置选择器");
            return;
        }
        startPending(REQ_SAVE_AS, null);
        pendingSave = cb;
        pendingSaveName = name;
        pendingSaveText = (text == null) ? "" : text;
    }

    /** 覆盖保存到当前文档（仅 canWrite 时）；任何异常都返回 false，不抛。 */
    public boolean saveToCurrent(String text) {
        Uri uri = currentUri;
        boolean writable = currentCanWrite;
        if (uri == null) {
            // 进程被回收后重新进入：用 Settings 记住的 lastDocUri 兜底，并实测一次写权限
            String last = settings.getLastDocUri();
            if (last != null && last.length() > 0) {
                try {
                    Uri u = Uri.parse(last);
                    if (testWritable(u)) {
                        uri = u;
                        writable = true;
                    }
                } catch (Exception ignore) {
                    // 解析不了就走下面的 false
                }
            }
        }
        if (uri == null || !writable) {
            return false;
        }
        return writeText(uri, (text == null) ? "" : text);
    }

    // ================================================================ 图片解析

    /**
     * 把文档内的相对路径解析成可读 Uri（本类最关键的方法）。
     *
     * <p>思路：文档 uri 形如
     * {@code content://com.android.externalstorage.documents/document/primary%3ADownload%2Ffoo%2Fbar.md}，
     * 取出 documentId {@code primary:Download/foo/bar.md}，把最后一个路径段换成 relPath，
     * 再构造成兄弟文档 uri。<b>返回前一定实测可读</b>（openInputStream 读 1 字节，失败再退化为
     * query SIZE），打不开就返回 null，交给 UI 显示"授权文件夹"。
     *
     * @param relPath 形如 "images/001.png"；允许 URL 编码（会二选一都试）
     * @return 可读 Uri，或 null
     */
    public Uri resolveImage(String relPath) {
        String rel = normalizeRel(relPath);
        String decoded = normalizeRel(urlDecode(relPath));
        String[] candidates = (decoded != null && !decoded.equals(rel))
                ? new String[]{rel, decoded}
                : new String[]{rel};
        if (rel == null && decoded == null) {
            return null;
        }
        for (String r : candidates) {
            if (r == null) {
                continue;
            }
            // 1) 兄弟文档：相对当前文档所在目录
            Uri sib = siblingUri(currentUri, r);
            if (sib != null && isReadable(sib)) {
                return sib;
            }
            // 2) 回退：已授权 tree 内的相对路径
            Uri inTree = treeChildUri(getAuthorizedTreeUri(), r);
            if (inTree != null && isReadable(inTree)) {
                return inTree;
            }
        }
        return null; // 3) 都不行
    }

    /** @return 已授权 tree uri 字符串；没有则 ""。 */
    public String getAuthorizedTreeUri() {
        String v = settings.get(K_TREE, "");
        return (v == null) ? "" : v;
    }

    public void setAuthorizedTreeUri(String uri) {
        settings.set(K_TREE, (uri == null) ? "" : uri);
    }

    // ================================================================ 外部 Intent

    /** 处理 ACTION_VIEW / ACTION_SEND / ACTION_EDIT 带进来的文档。 */
    public void handleIntent(Intent intent, DocCallback cb) {
        if (cb == null) {
            return;
        }
        if (intent == null) {
            cb.onError("没有可用的文件");
            return;
        }
        Uri data = intent.getData();
        Uri stream = extraStream(intent);
        String action = intent.getAction();
        Uri target;
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            target = (data != null) ? data : stream;
        } else {
            target = (data != null) ? data : stream;
        }
        if (target == null) {
            cb.onError("没有可用的文件");
            return;
        }
        if ("file".equals(target.getScheme())) {
            // file:// 没有 SAF 授权，读写都不受控；仍然允许只读打开（兼容老应用分享）
            try {
                takePermission(target);
            } catch (Exception ignore) {
                // 非致命
            }
        } else {
            try {
                takePermission(target);
            } catch (Exception ignore) {
                // 外部 Intent 的临时授权通常不能持久化，失败非致命
            }
        }
        openUri(target, intent.getType(), cb);
    }

    // ================================================================ 结果分发

    /** 由 Activity 转发；requestCode 与当前 pending 不符的直接忽略（防串台）。 */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != pendingCode) {
            return;
        }
        switch (requestCode) {
            case REQ_OPEN_FILE:
                finishOpenFile(resultCode, data);
                return;
            case REQ_OPEN_FOLDER:
                finishOpenFolder(resultCode, data);
                return;
            case REQ_SAVE_AS:
                finishSaveAs(resultCode, data);
                return;
            default:
                clearPending();
        }
    }

    /** 本应用零权限（不申请任何运行时权限），此方法仅为满足接口而存在。 */
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        // 不需要运行时权限：SAF 走用户选择授权，"所有文件访问"走系统设置页
    }

    /** 当前 pending 的另存文件名（含 .md），供 UI 显示；无 pending 时返回 ""。 */
    public String getPendingSaveName() {
        return pendingSaveName;
    }

    // ================================================================ 内部实现

    private void finishOpenFile(int resultCode, Intent data) {
        DocCallback cb = pendingDoc;
        clearPending();
        if (cb == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            cb.onError("已取消");
            return;
        }
        Uri uri = data.getData();
        takePermission(uri);
        openUri(uri, data.getType(), cb);
    }

    private void finishOpenFolder(int resultCode, Intent data) {
        DocCallback cb = pendingDoc;
        clearPending();
        if (cb == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            cb.onError("已取消");
            return;
        }
        Uri tree = data.getData();
        takePermission(tree);
        setAuthorizedTreeUri(tree.toString());

        // 授权文件夹**不改变当前文档**。接口只给了 DocCallback 一个回传通道，而调用方
        // （MainActivity）一收到 onDoc 就会整篇重渲染 —— 这正是"授权后图片能显示"所必需的。
        // 所以这里把**当前文档重新读一遍**回传：内容、标题、docKey、位置记忆全都不变，
        // 只是 resolveImage 从此多了一条已授权 tree 的退路。
        // 当前没有打开的文档时不回调（没有需要重渲染的东西；tree 已持久化到 Settings）。
        Uri cur = currentUri;
        if (cur != null) {
            openUri(cur, null, cb);
        }
    }

    private void finishSaveAs(int resultCode, Intent data) {
        VoidCallback cb = pendingSave;
        String name = pendingSaveName;
        String text = pendingSaveText;
        clearPending();
        if (cb == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            cb.onDone(false, "已取消");
            return;
        }
        Uri uri = data.getData();
        takePermission(uri);
        boolean ok = writeText(uri, text);
        if (ok) {
            // 见 saveAs 注释：接口不回传 Uri，这里把当前文档切到新文件，保存链路才自洽
            currentUri = uri;
            currentCanWrite = true;
            try {
                settings.setLastDocUri(uri.toString());
                settings.addRecent(uri.toString(), name, CodecGate.utf8(text).length);
            } catch (Exception ignore) {
                // 统计失败不影响保存结果
            }
        }
        cb.onDone(ok, ok ? ("已保存 " + name) : "保存失败");
    }

    private void startPending(int code, DocCallback cb) {
        pendingCode = code;
        pendingDoc = cb;
        pendingSave = null;
        pendingSaveName = "";
        pendingSaveText = "";
    }

    private void clearPending() {
        pendingCode = 0;
        pendingDoc = null;
        pendingSave = null;
        pendingSaveName = "";
        pendingSaveText = "";
    }

    private boolean launch(Intent intent, int code) {
        try {
            activity.startActivityForResult(intent, code);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读+写都尝试持久化；写失败（只读来源）不致命。 */
    private void takePermission(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            resolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return;
        } catch (Exception ignore) {
            // 落到只读
        }
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignore) {
            // 临时授权/不可持久化：非致命
        }
    }

    /** @deprecated 兼容 API 26 必须用旧版 {@code getParcelableExtra(String)}，
     * 新版带 Class 参数的重载要 API 33 才存在，minSdk 26 上会 NoSuchMethodError。 */
    @SuppressWarnings("deprecation")
    private static Uri extraStream(Intent intent) {
        try {
            Object o = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return (o instanceof Uri) ? (Uri) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- IO

    private byte[] readBytes(Uri uri) throws IOException {
        InputStream in = null;
        try {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) {
                    throw new IOException("无效的文件路径");
                }
                in = new FileInputStream(new File(path));
            } else {
                in = resolver.openInputStream(uri);
            }
            if (in == null) {
                throw new IOException("无法打开输入流");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("文件超过 32 MB 上限");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    /** 以 "wt"（截断）写 UTF-8；任何异常返回 false。 */
    private boolean writeText(Uri uri, String text) {
        OutputStream os = null;
        try {
            os = resolver.openOutputStream(uri, "wt");
            if (os == null) {
                return false;
            }
            os.write(CodecGate.utf8(text));
            os.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(os);
        }
    }

    /**
     * 实测可写：先用 DocumentsContract 的 FLAG_SUPPORTS_WRITE 读一次，
     * 再无条件用 {@code openFileDescriptor(uri,"rw")} 真开一次——只有真开成功才算可写
     * （很多 provider 的 flag 与实际权限不一致）。全程 catch，绝不外抛。
     */
    private boolean testWritable(Uri uri) {
        if (uri == null) {
            return false;
        }
        try {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) {
                    return false;
                }
                File f = new File(path);
                return f.isFile() && f.canWrite();
            }
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw");
            if (pfd == null) {
                return false;
            }
            try {
                pfd.close();
            } catch (IOException ignore) {
                // 关不上不影响"能打开"这个事实
            }
            return true;
        } catch (Throwable t) {
            // SecurityException / FileNotFoundException / IllegalArgumentException / Provider 崩坏
            return false;
        }
    }

    /**
     * 验证候选 Uri 真的能读：openInputStream + 读 1 字节（-1 表示空文件，也算能读）；
     * 打不开再退化为 query SIZE 探一次。
     */
    private boolean isReadable(Uri uri) {
        if (uri == null) {
            return false;
        }
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) {
                return false;
            }
            in.read(); // 触发真正的读取（有的 provider 在 read 时才抛）
            return true;
        } catch (Exception e) {
            return querySize(uri) >= 0;
        } catch (OutOfMemoryError oom) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignore) {
            // 无权限 / 非文档 provider
        } finally {
            closeQuietly(c);
        }
        return -1L;
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String s = c.getString(idx);
                    if (s != null && s.length() > 0) {
                        return s;
                    }
                }
            }
        } catch (Exception ignore) {
            // 退化为从 uri 末段取名
        } finally {
            closeQuietly(c);
        }
        String last = uri.getLastPathSegment();
        if (last == null || last.length() == 0) {
            return "未命名.md";
        }
        int cut = Math.max(last.lastIndexOf('/'), last.lastIndexOf(':'));
        if (cut >= 0 && cut + 1 < last.length()) {
            last = last.substring(cut + 1);
        }
        return last;
    }

    // ---------------------------------------------------------------- 相对路径

    /**
     * 兄弟文档 uri：把 documentId 的最后一段换成 relPath。
     *
     * <p>两种 id 形态都要能处理：
     * <ul>
     *   <li>{@code primary:Download/foo/bar.md} → 优先在最后一个 '/' 处切；</li>
     *   <li>{@code primary:bar.md}（卷根目录）→ 没有 '/'，退到最后一个 ':' 处切，
     *       得到 {@code primary:img.png}——这正是 externalstorage provider 的语义。</li>
     * </ul>
     */
    private Uri siblingUri(Uri docUri, String rel) {
        if (docUri == null || rel == null || rel.length() == 0) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(docUri);
        } catch (Exception e) {
            return null; // file:// 或非 DocumentsProvider：没有 documentId 概念
        }
        if (docId == null || docId.length() == 0) {
            return null;
        }
        int slash = docId.lastIndexOf('/');
        int colon = docId.lastIndexOf(':');
        int cut = Math.max(slash, colon);
        String dir = (cut >= 0) ? docId.substring(0, cut + 1) : "";
        String parent;
        if (cut < 0) {
            return null; // 既无路径分隔也无卷前缀：无法定位同级目录
        } else if (docId.charAt(cut) == ':') {
            parent = dir;                       // "primary:" + rel
        } else {
            parent = dir;                       // "primary:Download/foo/" + rel
        }
        String newId = parent + rel;

        // tree 形式的 uri 用 buildDocumentUriUsingTree；普通 document uri 会抛
        // IllegalArgumentException（getTreeDocumentId 要求 /tree/ 段），退到 buildDocumentUri。
        Uri cand = null;
        try {
            cand = DocumentsContract.buildDocumentUriUsingTree(docUri, newId);
        } catch (Exception ignore) {
            cand = null;
        }
        if (cand == null) {
            try {
                cand = DocumentsContract.buildDocumentUri(docUri.getAuthority(), newId);
            } catch (Exception ignore) {
                cand = null;
            }
        }
        return cand;
    }

    /** 已授权 tree 内的相对路径 → uri。 */
    private Uri treeChildUri(String treeUriStr, String rel) {
        if (treeUriStr == null || treeUriStr.length() == 0 || rel == null) {
            return null;
        }
        try {
            Uri tree = Uri.parse(treeUriStr);
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            if (rootId == null || rootId.length() == 0) {
                return null;
            }
            String newId = rootId.endsWith("/") ? (rootId + rel) : (rootId + "/" + rel);
            return DocumentsContract.buildDocumentUriUsingTree(tree, newId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 规范化相对路径：去 query/fragment、反斜杠转正斜杠、去 "./" 与开头 "/"；
     * 含 ".." 一律拒绝（不允许跳出文档所在目录）。返回 null 表示不可用。
     */
    private static String normalizeRel(String rel) {
        if (rel == null) {
            return null;
        }
        String s = rel.trim();
        if (s.length() == 0) {
            return null;
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        s = s.replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.length() == 0) {
            return null;
        }
        String[] parts = s.split("/");
        StringBuilder sb = new StringBuilder(s.length());
        for (String p : parts) {
            if (p.length() == 0 || ".".equals(p)) {
                continue;
            }
            if ("..".equals(p)) {
                return null; // 目录穿越：直接拒绝
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        return (sb.length() == 0) ? null : sb.toString();
    }

    /** 宽容的 URL 解码：只处理 %XX，失败原样返回。 */
    private static String urlDecode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        try {
            return Uri.decode(s);
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------- 杂项

    /** SHA-256 前 8 字节 → 16 位十六进制（稳定 docKey，跨进程一致）。 */
    private static String sha16(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(16);
            final char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 8; i++) {
                int b = dig[i] & 0xFF;
                sb.append(hex[b >>> 4]).append(hex[b & 0x0F]);
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上不可达（UTF-8 与 SHA-256 都是必备算法）
            return Integer.toHexString(s.hashCode());
        }
    }

    /** 建议名保证以 .md 结尾，且去掉路径分隔符等非法字符。 */
    private static String ensureMd(String name) {
        String s = (name == null) ? "" : name.trim();
        s = s.replace('/', '_').replace('\\', '_');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 ? '_' : c);
        }
        s = sb.toString().trim();
        while (s.startsWith(".") && s.length() > 1 && !s.toLowerCase(Locale.US).endsWith(".md")) {
            break; // 保留隐藏名，不做额外处理
        }
        if (s.length() == 0) {
            s = "untitled";
        }
        if (!s.toLowerCase(Locale.US).endsWith(".md")) {
            s = s + ".md";
        }
        return s;
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            return t.getClass().getSimpleName();
        }
        return m;
    }

    private static void closeQuietly(Object c) {
        if (c == null) {
            return;
        }
        try {
            if (c instanceof InputStream) {
                ((InputStream) c).close();
            } else if (c instanceof OutputStream) {
                ((OutputStream) c).close();
            } else if (c instanceof ParcelFileDescriptor) {
                ((ParcelFileDescriptor) c).close();
            } else if (c instanceof Cursor) {
                ((Cursor) c).close();
            }
        } catch (Exception ignore) {
            // 关闭失败无意义
        }
    }
}
