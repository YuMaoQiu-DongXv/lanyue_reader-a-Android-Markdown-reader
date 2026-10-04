package com.dsh.mdreader;

import android.webkit.JavascriptInterface;

/**
 * 注入给网页的原生桥（网页里叫 window.Lanyue）。
 *
 * 安全前提：页面只从 https://appassets.local/ 加载，而该域名全部由 AssetServer 就地应答
 * （不申请 INTERNET 权限，任何未被拦截的请求都必然失败），因此不存在第三方内容调用此桥的路径。
 *
 * 线程：JS 调用发生在 WebView 的 JavaBridge 线程，所有涉及 UI/文件的操作都 post 回主线程。
 */
public final class AppBridge {

    private final MainActivity activity;

    public AppBridge(MainActivity activity) {
        this.activity = activity;
    }

    private void ui(Runnable r) {
        activity.runOnUiThread(r);
    }

    /* ---------------- 状态 ---------------- */

    @JavascriptInterface
    public String getState() {
        return activity.buildStateJson();
    }

    /* ---------------- 文件 ---------------- */

    @JavascriptInterface
    public void openFile() {
        ui(activity::doOpenFile);
    }

    @JavascriptInterface
    public void openFolder() {
        ui(activity::doOpenFolder);
    }

    @JavascriptInterface
    public void openRecent(String uri) {
        ui(() -> activity.doOpenRecent(uri));
    }

    @JavascriptInterface
    public void saveText(String text) {
        ui(() -> activity.doSaveCurrent(text));
    }

    @JavascriptInterface
    public void saveAs(String name, String text) {
        ui(() -> activity.doSaveAs(name, text));
    }

    @JavascriptInterface
    public void editDoc(String text) {
        ui(() -> activity.doEditDoc(text));
    }

    /* ---------------- 导出 ---------------- */

    @JavascriptInterface
    public void exportPdf(final boolean highQuality) {
        ui(() -> activity.doExportPdf(highQuality));
    }

    @JavascriptInterface
    public void exportImages(String mode, String payloadJson) {
        ui(() -> activity.doExportImages(mode, payloadJson));
    }

    @JavascriptInterface
    public void shareLast() {
        ui(activity::doShareLast);
    }

    /* ---------------- 设置 / 反馈 ---------------- */

    @JavascriptInterface
    public void setSetting(String key, String value) {
        ui(() -> activity.doSetSetting(key, value));
    }

    @JavascriptInterface
    public void toast(String msg) {
        ui(() -> activity.doToast(msg));
    }

    @JavascriptInterface
    public void log(String msg) {
        activity.doLog(msg);
    }

    @JavascriptInterface
    public void openUrl(String url) {
        ui(() -> activity.doOpenUrl(url));
    }

    /* ---------------- 网页 → 原生 的状态上报 ---------------- */

    @JavascriptInterface
    public void docReady(String statsJson) {
        activity.doDocReady(statsJson);
    }

    @JavascriptInterface
    public void layout(int contentHeight, int viewportHeight, int scrollY) {
        activity.doLayout(contentHeight, viewportHeight, scrollY);
    }

    @JavascriptInterface
    public void viewChanged(String view) {
        activity.doViewChanged(view);
    }
}
