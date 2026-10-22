package com.leihuo.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.net.URL;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/** Official login pages run in a WebView with no application JavaScript bridge. */
public final class GatewayLoginActivity extends Activity {
    static final int MAX_PAYLOAD_CHARS = 256 * 1024;
    private static final String GATEWAY_HOST = "ai.leihuo.netease.com";
    private static final String ADMIN_HOST = "admin.ai.leihuo.netease.com";
    private static final String LOGIN_URL = "https://" + GATEWAY_HOST + "/usercenter";
    private static final String PRICING_URL = "https://" + ADMIN_HOST + "/pricing";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private TextView status;
    private Button readButton;
    private String gateway;
    private String activeSlot;
    private String activeOrigin;
    private int navigation;
    private int syncNavigation;
    private boolean syncing;
    private boolean pageFailed;
    private Runnable polling;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        gateway = getIntent().getStringExtra("gateway");
        if (!isGatewayRoot(gateway)) { finish(); return; }
        setResult(RESULT_CANCELED);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        layout.setFitsSystemWindows(true);
        int spacing = (int) (12 * getResources().getDisplayMetrics().density);
        TextView explanation = new TextView(this);
        explanation.setText("请在雷火官方页面登录，再点“读取接口”。本 App 只保存接口路径和请求方法，登录资料留在官方网页。");
        explanation.setTextColor(Color.rgb(40, 40, 40));
        explanation.setTextSize(14);
        explanation.setPadding(spacing, spacing, spacing, spacing / 2);
        layout.addView(explanation);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setPadding(spacing, 0, spacing, 0);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        readButton = new Button(this);
        readButton.setText("读取接口");
        readButton.setOnClickListener(view -> startSync());
        toolbar.addView(readButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = new Button(this);
        done.setText("完成");
        done.setOnClickListener(view -> finish());
        toolbar.addView(done, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(toolbar);
        status = new TextView(this);
        status.setText("正在打开官方用户中心…");
        status.setTextColor(Color.rgb(90, 90, 90));
        status.setTextSize(12);
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
        // Never register addJavascriptInterface on this remote page.
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (safeHttps(request.getUrl().toString())) return false;
                if (request.isForMainFrame()) status.setText("该登录跳转不是 HTTPS 地址，无法在这里打开。");
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                navigation++;
                pageFailed = false;
                if (syncing) stopSync("页面已跳转，请完成登录后重新读取接口。");
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!syncing && !pageFailed) {
                    if (isOfficialPage(url)) status.setText("官方页面已打开，登录后点“读取接口”。");
                    else status.setText("请在身份验证页面完成登录，返回雷火官方页面后读取接口。");
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                sslHandler.cancel();
                pageFailed = true;
                stopSync("官方页面证书验证失败，请检查手机时间和网络。");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    stopSync("官方页面打开失败，请检查网络后重试。");
                }
            }
        });
        webView.loadUrl(LOGIN_URL);
    }

    private void startSync() {
        if (syncing) return;
        String page = webView.getUrl();
        if (!isOfficialPage(page)) {
            status.setText("请先完成身份验证，返回雷火官方页面后读取接口。");
            return;
        }
        String origin;
        try { origin = "https://" + new URL(page).getHost().toLowerCase(Locale.ROOT); }
        catch (Exception invalidPage) { return; }
        activeSlot = "__leihuo_interface_" + UUID.randomUUID().toString().replace("-", "");
        activeOrigin = origin;
        syncNavigation = navigation;
        syncing = true;
        readButton.setEnabled(false);
        status.setText("正在读取官方接口映射…");
        final String slot = activeSlot;
        final String expectedOrigin = activeOrigin;
        String script = "(() => {"
                + "if(location.origin!==" + JSONObject.quote(expectedOrigin) + ")return false;"
                + "const slot=" + JSONObject.quote(slot) + ";window[slot]={state:'pending'};"
                + "const headers={'Cache-Control':'no-store'};"
                + "try{const uid=localStorage.getItem('uid');if(/^[0-9]{1,20}$/.test(uid||''))headers['New-Api-User']=uid;}catch(_){}"
                + "const abort=new AbortController();const timeout=setTimeout(()=>abort.abort(),25000);"
                + "fetch('/api/pricing',{method:'GET',credentials:'include',cache:'no-store',headers,signal:abort.signal})"
                + ".then(response=>{if(!response.ok)throw Error('login');return response.json();})"
                + ".then(body=>{"
                + "const source=body&&body.supported_endpoint;const endpoints=Object.create(null);"
                + "if(source&&typeof source==='object'&&!Array.isArray(source)){"
                + "for(const type of Object.keys(source).slice(0,512)){"
                + "const value=source[type];if(!/^[A-Za-z0-9._-]{1,128}$/.test(type)||!value||typeof value!=='object')continue;"
                + "const path=value.path;const method=String(value.method||'POST').toUpperCase();"
                + "if(typeof path!=='string'||path.length>2048||!path.startsWith('/')||path.startsWith('//'))continue;"
                + "if(!['GET','POST','PUT','PATCH','DELETE'].includes(method))continue;"
                + "endpoints[type]={path,method};if(Object.keys(endpoints).length>=256)break;}}"
                + "window[slot]=Object.keys(endpoints).length?{state:'ready',endpoints}:{state:'empty'};"
                + "}).catch(()=>{window[slot]={state:'failed'};}).finally(()=>clearTimeout(timeout));return true;})()";
        webView.evaluateJavascript(script, value -> {
            if (!sameOperation(slot, expectedOrigin)) return;
            if (!"true".equals(value)) { syncFailed(expectedOrigin); return; }
            pollSync(slot, expectedOrigin, 0);
        });
    }

    private void pollSync(String slot, String origin, int attempt) {
        if (!sameOperation(slot, origin)) return;
        if (attempt >= 100) { deleteSlot(slot); syncFailed(origin); return; }
        String script = "(() => {if(location.origin!==" + JSONObject.quote(origin)
                + ")return null;const value=window[" + JSONObject.quote(slot)
                + "];return value?JSON.stringify(value):null;})()";
        webView.evaluateJavascript(script, value -> {
            if (!sameOperation(slot, origin)) return;
            try {
                if (value == null || value.length() > MAX_PAYLOAD_CHARS * 2) throw new IllegalArgumentException();
                Object decoded = new JSONTokener(value).nextValue();
                if (!(decoded instanceof String) || ((String) decoded).length() > MAX_PAYLOAD_CHARS) throw new IllegalArgumentException();
                JSONObject result = new JSONObject((String) decoded);
                String state = result.optString("state");
                if ("pending".equals(state)) {
                    polling = () -> pollSync(slot, origin, attempt + 1);
                    handler.postDelayed(polling, 300);
                    return;
                }
                deleteSlot(slot);
                if (!"ready".equals(state)) { syncFailed(origin); return; }
                JSONObject endpoints = sanitizeEndpoints(result.optJSONObject("endpoints"));
                if (endpoints.length() == 0) { syncFailed(origin); return; }
                Intent resultIntent = new Intent();
                resultIntent.putExtra("gateway", gateway);
                resultIntent.putExtra("endpoints", endpoints.toString());
                setResult(RESULT_OK, resultIntent);
                stopSync("已读取 " + endpoints.length() + " 类接口。点“完成”返回 App。");
                Toast.makeText(this, "官方接口已读取", Toast.LENGTH_SHORT).show();
            } catch (Exception invalidResponse) {
                deleteSlot(slot);
                syncFailed(origin);
            }
        });
    }

    private boolean sameOperation(String slot, String origin) {
        if (isFinishing() || isDestroyed() || !syncing || !slot.equals(activeSlot)
                || !origin.equals(activeOrigin) || syncNavigation != navigation) return false;
        String url = webView.getUrl();
        if (!isOfficialPage(url)) { stopSync("页面已跳转，请返回官方页面后重新读取。"); return false; }
        try {
            if (!origin.equals("https://" + new URL(url).getHost().toLowerCase(Locale.ROOT))) {
                stopSync("页面已跳转，请返回官方页面后重新读取。");
                return false;
            }
        } catch (Exception invalidPage) { return false; }
        return true;
    }

    private void syncFailed(String origin) {
        if (("https://" + GATEWAY_HOST).equals(origin)) {
            stopSync("用户中心未返回接口。已打开官方管理页，登录后再次点“读取接口”。");
            webView.loadUrl(PRICING_URL);
        } else stopSync("未取得接口映射，请在官方管理页完成登录后重新读取。");
    }

    private void stopSync(String message) {
        syncing = false;
        activeSlot = null;
        activeOrigin = null;
        if (polling != null) handler.removeCallbacks(polling);
        polling = null;
        if (readButton != null) readButton.setEnabled(true);
        if (status != null) status.setText(message);
    }

    private void deleteSlot(String slot) {
        if (webView != null && !isDestroyed()) webView.evaluateJavascript("delete window[" + JSONObject.quote(slot) + "]", null);
    }

    static boolean isGatewayRoot(String raw) {
        if (raw == null || !isOfficialPage(raw)) return false;
        try {
            URI uri = new URI(raw);
            String path = uri.getPath();
            return (path == null || path.isEmpty() || "/".equals(path)) && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception invalid) { return false; }
    }

    private static boolean safeHttps(String raw) {
        try {
            URL url = new URL(raw);
            return "https".equalsIgnoreCase(url.getProtocol()) && url.getHost() != null && !url.getHost().isEmpty() && url.getUserInfo() == null;
        } catch (Exception invalid) { return false; }
    }

    private static boolean isOfficialPage(String raw) {
        if (!safeHttps(raw)) return false;
        try {
            URL url = new URL(raw);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            return (url.getPort() == -1 || url.getPort() == 443) && (GATEWAY_HOST.equals(host) || ADMIN_HOST.equals(host));
        } catch (Exception invalid) { return false; }
    }

    static JSONObject sanitizeEndpoints(JSONObject input) throws Exception {
        JSONObject clean = new JSONObject();
        if (input == null || input.length() > 512) return clean;
        Iterator<String> types = input.keys();
        while (types.hasNext() && clean.length() < 256) {
            String type = types.next();
            if (!type.matches("[A-Za-z0-9._-]{1,128}")) continue;
            JSONObject endpoint = input.optJSONObject(type);
            if (endpoint == null || !(endpoint.opt("path") instanceof String)) continue;
            String path = endpoint.getString("path");
            String method = endpoint.optString("method", "POST").toUpperCase(Locale.ROOT);
            if (path.isEmpty() || path.length() > 2048 || !path.startsWith("/") || path.startsWith("//")
                    || path.contains("\\") || path.contains("..") || path.matches("(?s).*[\\x00-\\x20\\x7F].*")) continue;
            if (!method.matches("GET|POST|PUT|PATCH|DELETE")) continue;
            clean.put(type, new JSONObject().put("path", path).put("method", method));
        }
        return clean;
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) CookieManager.getInstance().setAcceptCookie(true);
    }

    @Override protected void onStop() {
        CookieManager.getInstance().flush();
        CookieManager.getInstance().setAcceptCookie(false);
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        CookieManager.getInstance().setAcceptCookie(false);
        super.onDestroy();
    }
}
