package com.dsh.mdreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;

/**
 * 单 Activity：
 *   - 浏览/首页 = WebView（HTML 渲染，见 assets/web）
 *   - 编辑      = 原生 EditText + 格式工具栏（覆盖层）
 * 原生侧只做 WebView 喂不进去的事：文件读写、编码转换、图片解码、导出、Insets。
 */
public final class MainActivity extends Activity implements AssetServer.DocSource, Exporter.Layout {

    private static final String TAG = "Lanyue";

    /* 文档状态 */
    private byte[] docUtf8;
    private String fileName = "";
    private String docKey = "";
    private boolean canWrite = false;
    private String encodingName = "UTF-8";
    private String encodingNotice = "";

    /* 布局上报（JS 用 CSS 像素上报，导出时需要物理像素换算） */
    private int cssContentHeight = 1;
    private int cssViewportHeight = 1;
    private int cssScrollY = 0;

    /* 视图 */
    private FrameLayout root;
    private WebView web;
    private View editorPanel;
    private EditText editor;
    private TextView editorNote;
    private Button editorSave;

    private int insetTopPx = 0;
    private int insetBottomPx = 0;

    private Settings settings;
    private FileGate files;
    private ImageLoader images;
    private AssetServer assets;
    private Exporter exporter;

    private View fatalView;
    private boolean editorVisible = false;
    private boolean editorDirty = false;
    private String editorOriginal = "";
    private boolean showingDoc = false;

    /* ==================================================================
       生命周期
       ================================================================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        files = new FileGate(this, settings);
        images = new ImageLoader(this, files);
        assets = new AssetServer(this, this, images);

        web = createWebView();
        exporter = new Exporter(this, web, settings, this, assets);

        root = new FrameLayout(this);
        root.setBackgroundColor(color(R.color.bg));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editorPanel = buildEditorPanel();
        editorPanel.setVisibility(View.GONE);
        root.addView(editorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        applyEdgeToEdge();
        web.loadUrl(AssetServer.INDEX);

        Intent it = getIntent();
        if (it != null) files.handleIntent(it, docCallback);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) files.handleIntent(intent, docCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exporter != null) exporter.onActivityResumed();
        pushStateToWeb();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 自动保存开关打开时，退到后台就把编辑中的内容落盘
        if (editorVisible && editorDirty && settings.isAutosave()) {
            saveEditorSilently();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("(function(){try{return LanyueApp.savePositionNow();}catch(e){return ''}})()", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        files.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        files.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyThemeToSystemBars();
        pushStateToWeb();
    }

    @SuppressWarnings("deprecation")   // framework-only（无 AndroidX）拿不到 OnBackPressedDispatcher，
                                        // 且本应用不开启 enableOnBackInvokedCallback，故沿用 onBackPressed。
    @Override
    public void onBackPressed() {
        if (editorVisible) {
            exitEditor();
            return;
        }
        web.evaluateJavascript("(function(){try{return LanyueApp.back();}catch(e){return false;}})()", value -> {
            if ("true".equals(value)) return;             // 页面自己关掉了面板/灯箱
            if (showingDoc) {
                web.evaluateJavascript("(function(){try{return LanyueApp.goHome();}catch(e){return false;}})()", null);
            } else {
                finish();
            }
        });
    }

    /* ==================================================================
       WebView
       ================================================================== */

    private WebView createWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);          // 只走 appassets 拦截，不留 file:// 后门
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);                   // 字号由应用内设置控制，避免被系统字体缩放二次放大
        w.setBackgroundColor(Color.TRANSPARENT);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false); // 进度条由页面自己画
        w.setHorizontalScrollBarEnabled(false);
        w.addJavascriptInterface(new AppBridge(this), "Lanyue");

        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.handle(request);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 最坏失败模式是"白屏且没有任何线索"。这里把它变成可读的原生错误页。
                if (request != null && request.isForMainFrame()) {
                    showFatal("页面加载失败：错误码 " + error.getErrorCode() + "，"
                            + (error.getDescription() == null ? "" : error.getDescription())
                            + "。若反复出现，请执行 adb logcat -s LanyueAsset:LanyueWeb");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null) return false;
                String url = u.toString();
                if (url.startsWith(AssetServer.ORIGIN)) return false;
                openExternal(url);
                return true;
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.i("LanyueWeb", cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber());
                return true;
            }
        });
        return w;
    }

    /** 原生错误页：只在主框架加载失败时出现，避免"白屏无提示" */
    private void showFatal(String message) {
        Log.e(TAG, message);
        if (fatalView != null) {
            ((TextView) ((LinearLayout) fatalView).getChildAt(0)).setText(message);
            return;
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(color(R.color.bg));
        col.setPadding(dp(24), dp(48) + insetTopPx, dp(24), dp(24));
        TextView t = new TextView(this);
        t.setText(message);
        t.setTextSize(14);
        t.setTextColor(color(R.color.text));
        Button retry = flatButton("重新加载", 15);
        retry.setTextColor(color(R.color.primary));
        retry.setPadding(dp(12), dp(10), dp(12), dp(10));
        retry.setOnClickListener(v -> {
            root.removeView(col);
            fatalView = null;
            web.loadUrl(AssetServer.INDEX);
        });
        col.addView(t);
        col.addView(retry);
        fatalView = col;
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openExternal(String url) {
        if (url == null) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("没有可打开该链接的应用");
        }
    }

    /* ==================================================================
       Insets / 系统栏
       ================================================================== */

    @SuppressWarnings("deprecation")   // minSdk 26 必须同时走两套 API：30+ 用 WindowInsets 新接口，
                                        // 26~29 只能用 setSystemUiVisibility/setStatusBarColor 这套旧接口，
                                        // 它们没有非废弃替代品可覆盖低版本。
    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        applyThemeToSystemBars();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            if (top != insetTopPx || bottom != insetBottomPx) {
                insetTopPx = top;
                insetBottomPx = bottom;
                applyInsetsToEditor();
                pushStateToWeb();
            }
            return insets;
        });
    }

    @SuppressWarnings("deprecation")   // SYSTEM_UI_FLAG_LIGHT_STATUS_BAR 在 30 后被 WindowInsetsController 取代，
                                        // 但我们要兼容 minSdk 26，只能走这条兼容路径
    private void applyThemeToSystemBars() {
        boolean dark = settings.isDarkEffective(this);   // 主题解析只在 Settings 里实现一处，避免两套判断漂移
        View d = getWindow().getDecorView();
        int flags = d.getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(flags);
        root.setBackgroundColor(color(R.color.bg));
    }

    private int cssInsetTop() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(insetTopPx / Math.max(0.5f, density));
    }

    private int cssInsetBottom() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(insetBottomPx / Math.max(0.5f, density));
    }

    /* ==================================================================
       原生编辑器
       ================================================================== */

    private View buildEditorPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(color(R.color.editor_bg));

        // 顶栏：返回 / 标题 / 保存
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), dp(4), dp(10), dp(4));
        bar.setBackgroundColor(color(R.color.surface));
        Button back = flatButton("‹", 22);
        back.setOnClickListener(v -> exitEditor());
        TextView title = new TextView(this);
        title.setText(R.string.editor_title);
        title.setTextSize(15);
        title.setTextColor(color(R.color.text));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(4), 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        editorNote = new TextView(this);
        editorNote.setTextSize(11);
        editorNote.setTextColor(color(R.color.text_muted));
        editorNote.setPadding(0, 0, dp(8), 0);
        editorSave = flatButton(getString(R.string.editor_save), 15);
        editorSave.setTextColor(color(R.color.primary));
        editorSave.setOnClickListener(v -> saveEditorExplicit());
        bar.addView(back);
        bar.addView(title);
        bar.addView(editorNote);
        bar.addView(editorSave);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // 格式工具栏（横向滚动）
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(color(R.color.surface));
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(dp(8), dp(2), dp(8), dp(6));
        addTool(tools, R.string.fmt_bold, new Runnable() {
            public void run() {
                wrap("**", "**", "加粗");
            }
        });
        addTool(tools, R.string.fmt_italic, new Runnable() {
            public void run() {
                wrap("*", "*", "斜体");
            }
        });
        addTool(tools, R.string.fmt_h2, new Runnable() {
            public void run() {
                linePrefix("## ");
            }
        });
        addTool(tools, R.string.fmt_list, new Runnable() {
            public void run() {
                linePrefix("- ");
            }
        });
        addTool(tools, R.string.fmt_quote, new Runnable() {
            public void run() {
                linePrefix("> ");
            }
        });
        addTool(tools, R.string.fmt_code, new Runnable() {
            public void run() {
                wrap("\n```\n", "\n```\n", "代码");
            }
        });
        addTool(tools, R.string.fmt_link, new Runnable() {
            public void run() {
                wrap("[", "](https://)", "链接文字");
            }
        });
        addTool(tools, R.string.fmt_image, new Runnable() {
            public void run() {
                insert("![](images/图片名.png)");
            }
        });
        addTool(tools, R.string.fmt_table, new Runnable() {
            public void run() {
                insert("\n| 列1 | 列2 |\n|---|---|\n| a | b |\n");
            }
        });
        hs.addView(tools, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new EditText(this);
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(15);
        editor.setTextColor(color(R.color.text));
        editor.setHintTextColor(color(R.color.text_muted));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setHorizontallyScrolling(false);
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                editorDirty = !s.toString().equals(editorOriginal);
                updateEditorChrome();
            }
        });
        col.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        applyInsetsToEditor(col, hs);
        return col;
    }

    private View editorCol;
    private View editorToolsRow;

    private void applyInsetsToEditor(View col, View tools) {
        editorCol = col;
        editorToolsRow = tools;
        applyInsetsToEditor();
    }

    private void applyInsetsToEditor() {
        if (editorCol == null) return;
        View first = ((LinearLayout) editorCol).getChildAt(0);
        if (first != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) first.getLayoutParams();
            if (lp.height != dp(48) + insetTopPx) {
                lp.height = dp(48) + insetTopPx;
                first.setPadding(dp(2), dp(4) + insetTopPx, dp(10), dp(4));
                first.setLayoutParams(lp);
            }
        }
        if (editor != null) {
            editor.setPadding(dp(12), dp(12), dp(12), dp(12) + insetBottomPx);
        }
    }

    private Button flatButton(String text, float sizeSp) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(sizeSp);
        b.setAllCaps(false);
        b.setBackground(null);
        b.setMinWidth(dp(40));
        b.setMinimumWidth(dp(40));
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setTextColor(color(R.color.text));
        return b;
    }

    private void addTool(LinearLayout parent, int labelRes, final Runnable action) {
        Button b = flatButton(getString(labelRes), 13);
        b.setTextColor(color(R.color.primary));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(v -> action.run());
        parent.addView(b);
    }

    private void wrap(String pre, String post, String placeholder) {
        int s = editor.getSelectionStart();
        int e = editor.getSelectionEnd();
        if (s < 0) s = editor.getText().length();
        if (e < 0) e = s;
        int a = Math.min(s, e), b = Math.max(s, e);
        String sel = editor.getText().subSequence(a, b).toString();
        if (sel.isEmpty()) sel = placeholder;
        editor.getText().replace(a, b, pre + sel + post);
        editor.setSelection(a + pre.length(), a + pre.length() + sel.length());
    }

    private void linePrefix(String prefix) {
        int s = editor.getSelectionStart();
        Editable t = editor.getText();
        int lineStart = s <= 0 ? 0 : t.toString().lastIndexOf('\n', Math.max(0, s - 1)) + 1;
        t.insert(lineStart, prefix);
    }

    private void insert(String text) {
        int s = editor.getSelectionStart();
        if (s < 0) s = editor.getText().length();
        editor.getText().insert(s, text);
    }

    private void updateEditorChrome() {
        if (editorSave == null) return;
        editorSave.setAlpha(editorDirty ? 1f : 0.45f);
        if (editorNote == null) return;
        if (!canWrite) {
            editorNote.setText(R.string.editor_readonly_note);
        } else if (editorDirty) {
            editorNote.setText("● 未保存");
        } else {
            editorNote.setText("");
        }
    }

    /* 进入编辑模式（JS 通过桥调用） */
    void doEditDoc(String text) {
        if (text == null) text = docUtf8 == null ? "" : new String(docUtf8, Charset.forName("UTF-8"));
        editorOriginal = text;
        editor.setText(text);
        editorDirty = false;
        editorVisible = true;
        updateEditorChrome();
        editorPanel.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        editor.requestFocus();
    }

    private void exitEditor() {
        if (editorDirty) {
            if (settings.isAutosave() && canWrite) {
                saveEditorSilently();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.editor_unsaved_title)
                        .setMessage(R.string.editor_unsaved_msg)
                        .setPositiveButton(R.string.editor_save_and_exit, (d, w) -> saveEditorAndExit())
                        .setNeutralButton(R.string.editor_discard, (d, w) -> hideEditor())
                        .setNegativeButton(R.string.editor_cancel, (DialogInterface d, int w) -> {
                        })
                        .show();
                return;
            }
        }
        hideEditor();
    }

    private void saveEditorAndExit() {
        String text = editor.getText().toString();
        if (canWrite && files.saveToCurrent(text)) {
            applySavedText(text);
            toast(getString(R.string.editor_saved));
            hideEditor();
            return;
        }
        files.saveAs(suggestedName(), text, (ok, msg) -> {
            if (ok) applySavedText(text);
            toast(msg);
            if (ok) {
                hideEditor();
                adoptSavedCopy();
            }
        });
    }

    /**
     * 「另存为」成功之后，把新文件认作当前文档。
     *
     * 为什么必须做：FileGate.saveAs 成功时已把内部 currentUri 指向新文件（否则后续覆盖保存会写回旧文件），
     * 但 MainActivity 这边的 fileName / canWrite / docKey 还停在旧文档上。不同步的后果是：
     * 界面仍显示"只读，保存将另存为"，而下一次保存会因为 canWrite=false 再次走另存为 —— 反复产出新文件。
     * 这里借 FileGate 写入的 lastDocUri 重新打开一次，把两侧状态对齐（文件此时可写，标题与位置记忆也随之更新）。
     */
    private void adoptSavedCopy() {
        String last = settings.getLastDocUri();
        if (last == null || last.isEmpty()) return;
        try {
            files.openUri(Uri.parse(last), "text/markdown", docCallback);
        } catch (Exception e) {
            Log.w(TAG, "adoptSavedCopy failed", e);
        }
    }

    private void saveEditorSilently() {
        String text = editor.getText().toString();
        if (files.saveToCurrent(text)) {
            applySavedText(text);
            editorDirty = false;
            updateEditorChrome();
            toast(getString(R.string.editor_saved));
        } else {
            toast("保存失败，请在浏览器模式下另存为");
        }
    }

    private void saveEditorExplicit() {
        if (!editorDirty) {
            toast("没有改动");
            return;
        }
        saveEditorSilently();
    }

    private void applySavedText(String text) {
        docUtf8 = text.getBytes(Charset.forName("UTF-8"));
        encodingName = "UTF-8";
        encodingNotice = "";
        editorOriginal = text;
        editorDirty = false;
        updateEditorChrome();
    }

    private String suggestedName() {
        if (fileName == null || fileName.isEmpty()) return "untitled.md";
        return fileName.toLowerCase().endsWith(".md") || fileName.toLowerCase().endsWith(".markdown")
                ? fileName : fileName + ".md";
    }

    private void hideEditor() {
        editorVisible = false;
        editorPanel.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.evaluateJavascript("(function(){try{return LanyueApp.reloadDoc();}catch(e){return 0;}})()", null);
    }

    /* ==================================================================
       文件回调
       ================================================================== */

    private final FileGate.DocCallback docCallback = new FileGate.DocCallback() {
        @Override
        public void onDoc(FileGate.Doc doc) {
            if (doc == null) return;
            docUtf8 = doc.text == null ? new byte[0] : doc.text.getBytes(Charset.forName("UTF-8"));
            fileName = doc.name == null ? "" : doc.name;
            docKey = doc.docKey == null ? "" : doc.docKey;
            canWrite = doc.canWrite;
            encodingName = doc.encoding == null ? "UTF-8" : doc.encoding;
            encodingNotice = "";
            if (!"UTF-8".equals(encodingName)) {
                encodingNotice = "检测到 " + encodingName + " 编码，已按此显示；保存时会转为 UTF-8。";
            }
            settings.set("fileName", fileName);
            settings.addRecent(doc.uri, fileName, doc.size);
            showingDoc = true;
            images.clearCache();
            pushStateToWeb();
            web.evaluateJavascript("(function(){try{return LanyueApp.reloadDoc();}catch(e){return 0;}})()", null);
        }

        @Override
        public void onError(String msg) {
            toast(getString(R.string.open_failed) + "：" + msg);
        }
    };

    /* ==================================================================
       桥接出去的动作
       ================================================================== */

    void doOpenFile() {
        files.openFile(docCallback);
    }

    void doOpenFolder() {
        // 场景分流（这是接口缺"授权成功"专用回调导致的必要处理）：
        //  - 阅读中：授权后让 FileGate 重读当前文档，图片立刻能显示 → 用 docCallback
        //  - 首页：只想要授权，不应把用户拽回"上次打开过的那个文档" → 用静默回调
        if (docUtf8 == null) {
            files.openFolder(new FileGate.DocCallback() {
                @Override
                public void onDoc(FileGate.Doc doc) {
                    toast("已授权该文件夹，文档内的图片将可直接显示");
                }

                @Override
                public void onError(String msg) {
                    toast(msg);
                }
            });
        } else {
            files.openFolder(docCallback);
        }
    }

    void doOpenRecent(String uri) {
        if (uri == null || uri.isEmpty()) return;
        files.openUri(Uri.parse(uri), "*/*", docCallback);
    }

    void doSaveAs(String name, String text) {
        files.saveAs(name == null || name.isEmpty() ? suggestedName() : name, text, (ok, msg) -> {
            toast(msg);
            if (ok) adoptSavedCopy();
        });
    }

    boolean doSaveCurrent(String text) {
        if (!canWrite) return false;
        boolean ok = files.saveToCurrent(text);
        if (ok) {
            applySavedText(text);
            toast(getString(R.string.editor_saved));
        }
        return ok;
    }

    void doExportPdf(boolean hq) {
        exporter.exportPdf(hq, exportCallback);
    }

    void doExportImages(String mode, String payload) {
        exporter.exportImages(mode, payload, exportCallback);
    }

    void doShareLast() {
        exporter.shareLast(exportCallback);
    }

    private final Exporter.Callback exportCallback = new Exporter.Callback() {
        @Override
        public void done(boolean ok, String msg, String shareUri) {
            toast(msg);
            notifyWeb("export", ok, msg);
        }
    };

    void doSetSetting(String key, String value) {
        if (key == null) return;
        if ("theme".equals(key)) settings.setTheme(value);
        else if ("fontScale".equals(key)) {
            try {
                settings.setFontScale(Float.parseFloat(value));
            } catch (NumberFormatException ignored) {
            }
        } else if ("autosave".equals(key)) settings.setAutosave("true".equals(value));
        else if ("hqExport".equals(key)) settings.setHqExport("true".equals(value));
        else if ("allFilesAccess".equals(key)) settings.setAllFilesAccess("true".equals(value));
        else settings.set(key, value);
        if ("theme".equals(key)) applyThemeToSystemBars();
        pushStateToWeb();
    }

    void doLog(String msg) {
        Log.i(TAG, "[web] " + msg);
    }

    void doToast(String msg) {
        toast(msg);
    }

    void doLayout(int h, int vh, int y) {
        cssContentHeight = Math.max(1, h);
        cssViewportHeight = Math.max(1, vh);
        cssScrollY = Math.max(0, y);
    }

    void doDocReady(String statsJson) {
        Log.i(TAG, "docReady " + statsJson);
        showingDoc = true;
    }

    void doViewChanged(String view) {
        showingDoc = "doc".equals(view);
    }

    void doOpenUrl(String url) {
        openExternal(url);
    }

    /* ==================================================================
       状态推送
       ================================================================== */

    void pushStateToWeb() {
        final String json = buildStateJson();
        web.post(() -> web.evaluateJavascript("(function(){try{return LanyueApp.applyState(" + json + ");}catch(e){return 0;}})()", null));
    }

    private void notifyWeb(String kind, boolean ok, String msg) {
        final String payload = "[" + JSONObject.quote(kind) + "," + ok + "," + JSONObject.quote(msg == null ? "" : msg) + "]";
        web.post(() -> web.evaluateJavascript("(function(){try{return LanyueApp.notify.apply(null," + payload + ");}catch(e){return 0;}})()", null));
    }

    String buildStateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("hasDoc", docUtf8 != null);
            o.put("docUrl", AssetServer.ORIGIN + "/doc.md");
            o.put("imgBase", AssetServer.ORIGIN + "/img/");
            o.put("docKey", docKey);
            o.put("fileName", fileName);
            o.put("canWrite", canWrite);
            o.put("encoding", encodingName);
            o.put("encodingNotice", encodingNotice);
            o.put("theme", settings.getTheme());
            o.put("fontScale", settings.getFontScale());
            o.put("insets", new JSONObject().put("top", cssInsetTop()).put("bottom", cssInsetBottom()));
            o.put("settings", new JSONObject()
                    .put("autosave", settings.isAutosave())
                    .put("hqExport", settings.isHqExport())
                    .put("allFilesAccess", settings.isAllFilesAccess()));
            JSONArray recent;
            try {
                recent = new JSONArray(settings.getRecentJson());
            } catch (Exception e) {
                recent = new JSONArray();
            }
            o.put("recent", recent);
            o.put("appVersion", versionName());
            o.put("webview", WebSettings.getDefaultUserAgent(this));
            return o.toString();
        } catch (Exception e) {
            return "{\"hasDoc\":false,\"theme\":\"system\",\"fontScale\":1,\"insets\":{\"top\":0,\"bottom\":0},\"settings\":{},\"recent\":[]}";
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    /* ==================================================================
       Exporter.Layout 实现
       ================================================================== */

    @Override
    public int contentHeight() {
        return cssContentHeight;
    }

    @Override
    public int viewportHeight() {
        return cssViewportHeight;
    }

    @Override
    public int scrollY() {
        return cssScrollY;
    }

    @Override
    public void scrollTo(int cssY) {
        float density = getResources().getDisplayMetrics().density;
        final int px = Math.round(cssY * density);
        web.post(() -> web.scrollTo(0, px));
    }

    /* ==================================================================
       AssetServer.DocSource
       ================================================================== */

    @Override
    public byte[] docBytes() {
        return docUtf8;
    }

    /* ==================================================================
       杂项
       ================================================================== */

    private void toast(String msg) {
        if (msg == null || msg.isEmpty()) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int color(int res) {
        return getColor(res);   // Context.getColor 需 API 23+；本应用 minSdk 26，无需兼容分支
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
