package com.dsh.mdreader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 蓝阅 · 极简分享 Provider：只暴露 {@code cacheDir/share/} 下的文件，供 ACTION_SEND 分享。
 *
 * <p>安全边界（唯一重要的事）：
 * <ul>
 *   <li>Uri 只带文件名，不带路径：{@code content://com.dsh.mdreader.share/f/<URL编码的文件名>}。</li>
 *   <li>{@link #uriFor} 丢弃 File 的父目录，所以即使传入 share 目录之外的文件，
 *       生成的 Uri 也无法指向它（openFile 会拒绝）。</li>
 *   <li>{@link #openFile} 一律做 canonical path 前缀校验（必须落在 shareDir 之内），
 *       拒绝 ".."、绝对路径、子目录等一切穿越尝试。</li>
 *   <li>只读：无论调用方传什么 mode，都按 MODE_READ_ONLY 打开。</li>
 * </ul>
 *
 * <p>必须在 AndroidManifest 中声明（exported=false，grantUriPermissions=true）：
 * <pre>
 * &lt;provider android:name="com.dsh.mdreader.ShareProvider"
 *           android:authorities="com.dsh.mdreader.share"
 *           android:exported="false"
 *           android:grantUriPermissions="true" /&gt;
 * </pre>
 */
public final class ShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.dsh.mdreader.share";

    /** 路径段：/{@value #SEG}/{文件名}。 */
    private static final String SEG = "f";

    private static final String MIME_PDF  = "application/pdf";
    private static final String MIME_PNG  = "image/png";
    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_WEBP = "image/webp";
    private static final String MIME_MD   = "text/markdown";
    private static final String MIME_BIN  = "application/octet-stream";

    /** onCreate 里初始化；为空说明 provider 生命周期异常，所有操作直接拒绝。 */
    private File shareDir;

    /**
     * 确保目录存在，返回 {@code <cacheDir>/share}（cacheDir 不可用时退化到 filesDir）。
     */
    public static File shareDir(Context ctx) {
        File base = ctx.getCacheDir();
        if (base == null) {
            base = ctx.getFilesDir();
        }
        if (base == null) {
            base = ctx.getDir("sharecache", Context.MODE_PRIVATE);
        }
        File dir = new File(base, "share");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 把 File 描述为可分享的 content:// Uri。
     *
     * <p>只取文件名（URL 编码），父目录被刻意丢弃——这样这个 Uri 天生只能指向
     * share 目录内的文件，无法被用来探测其他路径。
     */
    public static Uri uriFor(Context ctx, File file) {
        if (ctx == null || file == null) {
            return null;
        }
        String name = file.getName();
        if (name == null || name.length() == 0) {
            return null;
        }
        return Uri.parse("content://" + AUTHORITY + "/" + SEG + "/" + Uri.encode(name));
    }

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) {
            return false;
        }
        shareDir = shareDir(ctx);
        return true;
    }

    /** 本 provider 不提供查询能力（Exporter 只需要 Uri）。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    /** 按扩展名给出 MIME；未知 uri/扩展名返回 null 或二进制类型，绝不抛。 */
    @Override
    public String getType(Uri uri) {
        try {
            File f = fileFor(uri, false);
            if (f == null) {
                return null;
            }
            String name = f.getName().toLowerCase(Locale.US);
            if (name.endsWith(".pdf")) {
                return MIME_PDF;
            }
            if (name.endsWith(".png")) {
                return MIME_PNG;
            }
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                return MIME_JPEG;
            }
            if (name.endsWith(".webp")) {
                return MIME_WEBP;
            }
            if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")) {
                return MIME_MD;
            }
            return MIME_BIN;
        } catch (Exception e) {
            return null;
        }
    }

    /** 只读打开；文件不存在或越界一律 FileNotFoundException。 */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri, true);
        if (!f.exists() || !f.isFile()) {
            throw new FileNotFoundException("文件不存在：" + uri);
        }
        // 分享场景只读；忽略 mode 里的写请求（写入会得到 EBADF，这是有意的）
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 不支持写入。 */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    /** 不支持删除。 */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    /** 不支持更新。 */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * Uri → File，并做严格的边界校验。
     *
     * @param strict true 时越界/非法直接抛 FileNotFoundException（openFile 用）；
     *               false 时返回 null（getType 用，保持宽容）
     */
    private File fileFor(Uri uri, boolean strict) throws FileNotFoundException {
        File base = shareDir;
        if (base == null) {
            return reject(strict, "provider 尚未初始化");
        }
        if (uri == null || !"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())) {
            return reject(strict, "非本 provider 的 uri");
        }
        List<String> segs;
        try {
            segs = uri.getPathSegments();
        } catch (Exception e) {
            return reject(strict, "路径段解析失败");
        }
        if (segs == null || segs.size() != 2 || !SEG.equals(segs.get(0))) {
            return reject(strict, "路径形状非法（只接受 /f/<文件名>）");
        }
        String name = segs.get(1);
        if (name.length() == 0 || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            return reject(strict, "文件名非法");
        }
        File f = new File(base, name);
        try {
            String canon = f.getCanonicalPath();
            String baseCanon = base.getCanonicalPath();
            if (!canon.startsWith(baseCanon + File.separator)) {
                return reject(strict, "越界访问被拒绝");
            }
        } catch (IOException e) {
            return reject(strict, "路径解析失败");
        }
        return f;
    }

    private File reject(boolean strict, String msg) throws FileNotFoundException {
        if (strict) {
            throw new FileNotFoundException(msg);
        }
        return null;
    }
}
