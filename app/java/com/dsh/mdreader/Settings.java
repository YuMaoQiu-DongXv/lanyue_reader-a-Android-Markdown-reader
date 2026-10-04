package com.dsh.mdreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 蓝阅 · 轻量配置存储（纯 framework，SharedPreferences，prefs 名 {@code lanyue}）。
 *
 * <p>约定：
 * <ul>
 *   <li>所有 getter 都有默认值，绝不返回 null（字符串返回 ""，JSON 返回 "[]" / "{}"）。</li>
 *   <li>所有写操作走 {@code apply()}（异步落盘），不会阻塞 UI 线程。</li>
 *   <li>最近文件是 JSON 数组字符串，元素 {@code {"uri","name","size","when"}}，
 *       最多 {@value #MAX_RECENT} 条、按时间倒序、同 uri 去重（重复打开提到最前）。</li>
 *   <li>{@code when} 格式固定 {@code yyyy-MM-dd HH:mm}（Locale.US，保证 ASCII 数字）。</li>
 * </ul>
 */
public final class Settings {

    /** SharedPreferences 文件名。 */
    private static final String PREFS = "lanyue";

    private static final String K_THEME       = "theme";
    private static final String K_FONT_SCALE  = "fontScale";
    private static final String K_AUTOSAVE    = "autosave";
    private static final String K_HQ_EXPORT   = "hqExport";
    private static final String K_ALL_FILES   = "allFilesAccess";
    private static final String K_RECENT      = "recent";
    private static final String K_LAST_DOC    = "lastDocUri";
    private static final String K_POS_PREFIX  = "pos_";

    private static final String THEME_SYSTEM = "system";
    private static final String THEME_LIGHT  = "light";
    private static final String THEME_DARK   = "dark";

    private static final int    MAX_RECENT  = 12;
    private static final float  DEF_FONT    = 1.0f;
    private static final float  MIN_FONT    = 0.85f;
    private static final float  MAX_FONT    = 1.7f;

    private static final String EMPTY_ARRAY  = "[]";
    private static final String EMPTY_OBJECT = "{}";
    private static final String EMPTY        = "";
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm";

    private final SharedPreferences sp;

    public Settings(Context ctx) {
        Context app = ctx.getApplicationContext();
        Context base = (app != null) ? app : ctx;
        this.sp = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 主题

    /** @return "system" | "light" | "dark"，默认 "system"（非法值也回落 "system"）。 */
    public String getTheme() {
        String v = sp.getString(K_THEME, THEME_SYSTEM);
        if (THEME_LIGHT.equals(v) || THEME_DARK.equals(v) || THEME_SYSTEM.equals(v)) {
            return v;
        }
        return THEME_SYSTEM;
    }

    public void setTheme(String v) {
        String norm = THEME_SYSTEM;
        if (THEME_LIGHT.equals(v) || THEME_DARK.equals(v)) {
            norm = v;
        }
        sp.edit().putString(K_THEME, norm).apply();
    }

    // ---------------------------------------------------------------- 字号

    /** @return 0.85 ~ 1.7，默认 1.0。 */
    public float getFontScale() {
        float v = sp.getFloat(K_FONT_SCALE, DEF_FONT);
        if (Float.isNaN(v)) {
            return DEF_FONT;
        }
        return clampFont(v);
    }

    public void setFontScale(float v) {
        if (Float.isNaN(v)) {
            return;
        }
        sp.edit().putFloat(K_FONT_SCALE, clampFont(v)).apply();
    }

    private static float clampFont(float v) {
        if (v < MIN_FONT) {
            return MIN_FONT;
        }
        if (v > MAX_FONT) {
            return MAX_FONT;
        }
        return v;
    }

    // ---------------------------------------------------------------- 开关

    /** 自动保存，默认 true。 */
    public boolean isAutosave() {
        return sp.getBoolean(K_AUTOSAVE, true);
    }

    public void setAutosave(boolean v) {
        sp.edit().putBoolean(K_AUTOSAVE, v).apply();
    }

    /** 高质量导出（图片原图），默认 false。 */
    public boolean isHqExport() {
        return sp.getBoolean(K_HQ_EXPORT, false);
    }

    public void setHqExport(boolean v) {
        sp.edit().putBoolean(K_HQ_EXPORT, v).apply();
    }

    /**
     * 主题是否实际为深色（"system" 时跟随系统 uiMode）。
     *
     * <p>说明：这是 INTERFACES.md 冻结清单之外的**附加**方法（MainActivity 需要它来切
     * 状态栏/导航栏图标明暗），只新增、不改动任何冻结签名。
     */
    public boolean isDarkEffective(Context ctx) {
        String t = getTheme();
        if (THEME_DARK.equals(t)) {
            return true;
        }
        if (THEME_LIGHT.equals(t)) {
            return false;
        }
        try {
            Configuration cfg = ctx.getResources().getConfiguration();
            return (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return false;
        }
    }

    /** 「所有文件访问」高级开关，默认 false（首选项仍是 SAF）。 */
    public boolean isAllFilesAccess() {
        return sp.getBoolean(K_ALL_FILES, false);
    }

    public void setAllFilesAccess(boolean v) {
        sp.edit().putBoolean(K_ALL_FILES, v).apply();
    }

    // ------------------------------------------------------------ 通用读写

    /** 读任意字符串键；缺省返回 {@code def}（可为 null）。 */
    public String get(String key, String def) {
        if (key == null) {
            return def;
        }
        String v = sp.getString(key, def);
        return (v == null) ? def : v;
    }

    /** 写任意字符串键；{@code value == null} 等于删除该键（避免存进 null 造成后续 NPE）。 */
    public void set(String key, String value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            sp.edit().remove(key).apply();
        } else {
            sp.edit().putString(key, value).apply();
        }
    }

    // ------------------------------------------------------------ 最近文件

    /** @return JSON 数组字符串；损坏/缺失时返回 "[]"，绝不返回 null。 */
    public String getRecentJson() {
        String raw = sp.getString(K_RECENT, EMPTY_ARRAY);
        if (raw == null || raw.length() == 0) {
            return EMPTY_ARRAY;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            return arr.toString();
        } catch (Exception e) {
            // 数据损坏：不抛给调用方，返回空数组（真正的修复发生在下一次 addRecent）
            return EMPTY_ARRAY;
        }
    }

    /**
     * 记录一次打开：该 uri 提到最前（去重），超出 12 条丢弃最旧的。
     *
     * @param uri  文档 uri 字符串，空则忽略
     * @param name 展示名，可为 null
     * @param size 字节数，未知传 -1
     */
    public void addRecent(String uri, String name, long size) {
        if (uri == null || uri.length() == 0) {
            return;
        }
        JSONArray next = new JSONArray();
        try {
            next.put(entry(uri, name, size, stamp()));
        } catch (Exception e) {
            return;
        }
        JSONArray old = parseRecent();
        for (int i = 0; i < old.length() && next.length() < MAX_RECENT; i++) {
            JSONObject it = old.optJSONObject(i);
            if (it == null) {
                continue;
            }
            String u = it.optString("uri", EMPTY);
            if (u.length() == 0 || u.equals(uri)) {
                continue; // 同 uri 去重
            }
            try {
                next.put(entry(u, it.optString("name", EMPTY), it.optLong("size", -1L),
                        it.optString("when", EMPTY)));
            } catch (Exception ignore) {
                // 单条坏数据跳过，不影响其余条目
            }
        }
        sp.edit().putString(K_RECENT, next.toString()).apply();
    }

    private static JSONObject entry(String uri, String name, long size, String when)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("uri", uri);
        o.put("name", (name == null) ? EMPTY : name);
        o.put("size", size);
        o.put("when", (when == null) ? EMPTY : when);
        return o;
    }

    private JSONArray parseRecent() {
        String raw = sp.getString(K_RECENT, EMPTY_ARRAY);
        if (raw == null || raw.length() == 0) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 当前时间，格式 {@code yyyy-MM-dd HH:mm}。 */
    public static String stamp() {
        // Locale.US + 纯数字模式 → 恒为 ASCII 数字，不受系统区域设置影响
        return new SimpleDateFormat(DATE_PATTERN, Locale.US).format(new Date());
    }

    // ------------------------------------------------------------ 上次文档

    /** @return 上次打开的文档 uri；没有则返回 ""。 */
    public String getLastDocUri() {
        String v = sp.getString(K_LAST_DOC, EMPTY);
        return (v == null) ? EMPTY : v;
    }

    public void setLastDocUri(String uri) {
        sp.edit().putString(K_LAST_DOC, (uri == null) ? EMPTY : uri).apply();
    }

    // ------------------------------------------------------------ 阅读位置

    /**
     * 阅读位置：调用方自己用 uri 的哈希（docKey）当键，值原样存取（JSON 字符串）。
     *
     * @return 之前 setPos 存的内容；没有记录时返回 "{}"（保证 JS 侧 JSON.parse 不掉坑）
     */
    public String getPos(String docKey) {
        if (docKey == null || docKey.length() == 0) {
            return EMPTY_OBJECT;
        }
        String v = sp.getString(K_POS_PREFIX + docKey, EMPTY_OBJECT);
        if (v == null || v.length() == 0) {
            return EMPTY_OBJECT;
        }
        return v;
    }

    public void setPos(String docKey, String json) {
        if (docKey == null || docKey.length() == 0) {
            return;
        }
        sp.edit().putString(K_POS_PREFIX + docKey, (json == null) ? EMPTY_OBJECT : json).apply();
    }
}
