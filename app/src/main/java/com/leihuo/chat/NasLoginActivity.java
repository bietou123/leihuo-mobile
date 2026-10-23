package com.leihuo.chat;

import android.app.Activity;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** NAS and FN Connect login browser. Never exposes an application JavaScript bridge. */
public final class NasLoginActivity extends Activity {
    private WebView webView;
    private String gateway;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        gateway = getIntent().getStringExtra("gateway");
        String login = getIntent().getStringExtra("loginUrl");
        if (!NasSessionPolicy.allowedLoginUrl(gateway, login)) { finish(); return; }
        setResult(RESULT_CANCELED);
        int spacing = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        layout.setFitsSystemWindows(true);
        TextView explanation = new TextView(this);
        explanation.setText("请先完成 FN Connect 连接，再登录你的飞牛 NAS。登录会话只用于当前 NAS 的模型 API，完成后点右侧按钮返回。此页面不会读取账号密码，也不会把会话传给其他网站。");
        explanation.setTextColor(Color.rgb(40, 40, 40));
        explanation.setPadding(spacing, spacing, spacing, spacing);
        layout.addView(explanation);
        LinearLayout toolbar = new LinearLayout(this);
        Button reopen = new Button(this);
        reopen.setText("返回 NAS 登录页");
        reopen.setOnClickListener(view -> webView.loadUrl(login));
        toolbar.addView(reopen, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = new Button(this);
        done.setText("完成，返回 App");
        done.setOnClickListener(view -> {
            CookieManager.getInstance().flush();
            setResult(RESULT_OK); finish();
        });
        toolbar.addView(done, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(toolbar);
        TextView status = new TextView(this);
        status.setText("正在打开 NAS 登录页面…");
        status.setPadding(spacing, spacing / 2, spacing, spacing / 2);
        layout.addView(status);
        webView = new WebView(this);
        layout.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (NasSessionPolicy.allowedNavigation(gateway, request.getUrl().toString())) return false;
                if (request.isForMainFrame()) status.setText("此登录页仅允许当前 NAS 和 FN Connect 的 HTTPS 页面。请检查设置中的 NAS 登录地址。");
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // POST navigations do not always reach shouldOverrideUrlLoading.
                if (request.isForMainFrame() && !NasSessionPolicy.allowedNavigation(gateway, request.getUrl().toString())) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("此登录地址不在当前 NAS 或 FN Connect 的允许范围内。".getBytes(StandardCharsets.UTF_8)));
                }
                return null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (!NasSessionPolicy.allowedNavigation(gateway, url)) {
                    view.stopLoading(); status.setText("已停止打开当前 NAS 与 FN Connect 之外的页面。");
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (NasSessionPolicy.allowedNavigation(gateway, url)) status.setText("完成 NAS 登录后，点“完成，返回 App”，App 会重新读取模型目录。");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); status.setText("NAS 页面证书验证失败，请检查手机时间和网络。");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) status.setText("NAS 登录页打开失败，请检查 FN Connect 连接和手机网络。");
            }
        });
        webView.loadUrl(login);
    }

    @Override protected void onResume() { super.onResume(); CookieManager.getInstance().setAcceptCookie(true); }
    @Override protected void onStop() { CookieManager.getInstance().flush(); CookieManager.getInstance().setAcceptCookie(false); super.onStop(); }
    @Override public void onBackPressed() { if (webView != null && webView.canGoBack()) webView.goBack(); else finish(); }
    @Override protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        CookieManager.getInstance().setAcceptCookie(false); super.onDestroy();
    }
}
