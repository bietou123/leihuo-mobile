package com.leihuo.chat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Base64InputStream;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int FILE_PICKER = 101;
    private static final int SPEECH_INPUT = 102;
    private static final int EXPORT_CHATS = 103;
    private static final int IMPORT_CHATS = 104;
    private static final int MEDIA_EXPORT = 105;
    private static final int INTERFACE_SYNC = 106;
    private static final int IMAGE_PICKER = 107;
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final String ASSET_HOST = "leihuo.local";
    private static final String START_PAGE = "https://leihuo.local/index.html";

    private WebView webView;
    private FrameLayout frame;
    private ConfigStore config;
    private ChatStore chats;
    private SharedPreferences interfacePrefs;
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private final Map<String, RequestTask> active = new ConcurrentHashMap<>();
    private ValueCallback<Uri[]> fileCallback;
    private String pendingExport;
    private File pendingMedia;
    private boolean mediaBusy;
    private boolean imagePickActive;
    private JSONObject pendingPickedImageEvent;
    private TextToSpeech textToSpeech;
    private boolean speechReady;
    private boolean darkTheme;

    private static final class RequestTask {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        volatile Future<?> future;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        config = new ConfigStore(this);
        chats = new ChatStore(this);
        interfacePrefs = getSharedPreferences("interfaces", MODE_PRIVATE);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.WHITE);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        frame.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            frame.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            frame.setFitsSystemWindows(true);
        }
        applyTheme(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new NativeBridge(), "Native");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                if ("https".equals(request.getUrl().getScheme()) && ASSET_HOST.equals(request.getUrl().getHost())) return false;
                if (request.isForMainFrame()) openExternalUrl(target);
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("https".equals(scheme) && ASSET_HOST.equals(uri.getHost())) {
                    if (uri.getPath() != null && uri.getPath().startsWith("/generated-media/")) return loadGeneratedMedia(request);
                    return loadAsset(uri);
                }
                if ("data".equals(scheme) || "blob".equals(scheme)) return null;
                if ("https".equals(scheme)) return loadPublicMedia(request);
                // Remote documents and scripts cannot inherit the bridge.
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_PICKER);
                } catch (ActivityNotFoundException failure) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    emit("attachment", "error", "手机上没有可用的文件选择器", 0, null);
                }
                return true;
            }
        });
        textToSpeech = new TextToSpeech(this, status -> {
            speechReady = status == TextToSpeech.SUCCESS;
            if (speechReady) textToSpeech.setLanguage(Locale.SIMPLIFIED_CHINESE);
        });
        webView.loadUrl(START_PAGE);
    }

    private WebResourceResponse loadAsset(Uri uri) {
        try {
            String path = uri.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            if (!path.startsWith("/") || path.contains("..") || path.contains("\\")) throw new IllegalArgumentException();
            String name = path.substring(1);
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String mime;
            switch (extension) {
                case "html": mime = "text/html"; break;
                case "css": mime = "text/css"; break;
                case "js": case "mjs": mime = "application/javascript"; break;
                case "json": case "map": mime = "application/json"; break;
                case "svg": mime = "image/svg+xml"; break;
                case "png": mime = "image/png"; break;
                case "jpg": case "jpeg": mime = "image/jpeg"; break;
                case "webp": mime = "image/webp"; break;
                case "woff": mime = "font/woff"; break;
                case "woff2": mime = "font/woff2"; break;
                case "ttf": mime = "font/ttf"; break;
                case "wasm": mime = "application/wasm"; break;
                default: mime = "application/octet-stream";
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            headers.put("X-Content-Type-Options", "nosniff");
            return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(name));
        } catch (Exception failure) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private WebResourceResponse loadPublicMedia(WebResourceRequest request) {
        HttpURLConnection connection = null;
        try {
            String method = request.getMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) throw new IllegalArgumentException();
            URL target = new URL(request.getUrl().toString());
            Map<String, String> rangeHeaders = new HashMap<>();
            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                String name = header.getKey();
                String value = header.getValue();
                if (name == null || value == null || value.length() > 2048) continue;
                if ("Range".equalsIgnoreCase(name) && value.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)(?: *, *(?:[0-9]+-[0-9]*|-[0-9]+))*")) {
                    rangeHeaders.put("Range", value);
                } else if ("If-Range".equalsIgnoreCase(name) && !value.isEmpty() && value.matches("[\\x20-\\x7E]+")) {
                    rangeHeaders.put("If-Range", value);
                }
            }
            for (int redirects = 0; redirects < 4; redirects++) {
                if (!"https".equalsIgnoreCase(target.getProtocol()) || target.getUserInfo() != null) break;
                connection = (HttpURLConnection) target.openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method.toUpperCase(Locale.ROOT));
                connection.setRequestProperty("Accept-Encoding", "identity");
                // Generated media URLs are fetched without the gateway API key or cookies.
                for (Map.Entry<String, String> header : rangeHeaders.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null) break;
                    target = new URL(target, location);
                    continue;
                }
                String mime = connection.getContentType();
                if (status < 200 || status >= 300 || mime == null) break;
                mime = mime.toLowerCase(Locale.ROOT).split(";")[0].trim();
                if (mime.equals("image/svg+xml") || (!mime.startsWith("image/") && !mime.startsWith("audio/") && !mime.startsWith("video/"))) break;
                Map<String, String> responseHeaders = new HashMap<>();
                for (String name : new String[]{"Content-Type", "Content-Range", "Accept-Ranges", "Content-Length", "Cache-Control", "ETag", "Last-Modified", "Expires"}) {
                    String value = connection.getHeaderField(name);
                    if (value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) responseHeaders.put(name, value);
                }
                responseHeaders.put("X-Content-Type-Options", "nosniff");
                String reason = connection.getResponseMessage();
                if (reason != null) reason = reason.replaceAll("[^\\x20-\\x7E]", "").trim();
                if (reason == null || reason.isEmpty()) reason = status == 206 ? "Partial Content" : "OK";
                HttpURLConnection source = connection;
                InputStream input = new FilterInputStream(connection.getInputStream()) {
                    @Override public void close() throws java.io.IOException {
                        try { super.close(); } finally { source.disconnect(); }
                    }
                };
                return new WebResourceResponse(mime, null, status, reason, responseHeaders, input);
            }
        } catch (Exception ignored) {
            // Invalid or unavailable media is represented by the browser's empty media state.
        }
        if (connection != null) connection.disconnect();
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse loadGeneratedMedia(WebResourceRequest request) {
        try {
            String method = request.getMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) throw new IllegalArgumentException();
            String path = request.getUrl().getPath();
            if (path == null || !path.matches("/generated-media/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mp3|wav|flac|ogg|m4a|aac|pcm|mp4|webm|weba|bin)")) throw new IllegalArgumentException();
            String name = path.substring("/generated-media/".length());
            File directory = new File(getFilesDir(), "generated-media");
            File file = new File(directory, name);
            if (!file.isFile() || !file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) throw new IllegalArgumentException();
            String mime;
            try (InputStream metadata = new FileInputStream(new File(directory, name + ".json"))) {
                mime = new JSONObject(new String(readAll(metadata, 1024), StandardCharsets.UTF_8)).getString("mime");
            }
            if (!mime.matches("(?:audio|video)/[A-Za-z0-9.+-]+")) throw new IllegalArgumentException();
            long total = file.length();
            String etag = "\"" + name + "-" + total + "\"";
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", mime);
            headers.put("Accept-Ranges", "bytes");
            headers.put("Cache-Control", "private, no-cache");
            headers.put("ETag", etag);
            headers.put("X-Content-Type-Options", "nosniff");
            long start = 0, end = total - 1;
            String range = requestHeader(request, "Range");
            String ifRange = requestHeader(request, "If-Range");
            if (ifRange != null && !etag.equals(ifRange)) range = null;
            boolean partial = range != null;
            if (partial) {
                try {
                    if (range.length() > 128 || !range.matches("bytes=[0-9]*-[0-9]*")) throw new IllegalArgumentException();
                    String[] pieces = range.substring(6).split("-", -1);
                    if (pieces[0].isEmpty()) {
                        long suffix = Long.parseLong(pieces[1]);
                        if (suffix <= 0) throw new IllegalArgumentException();
                        start = Math.max(0, total - suffix);
                    } else {
                        start = Long.parseLong(pieces[0]);
                        if (!pieces[1].isEmpty()) end = Math.min(end, Long.parseLong(pieces[1]));
                    }
                    if (start < 0 || start >= total || end < start) throw new IllegalArgumentException();
                } catch (Exception invalidRange) {
                    headers.put("Content-Range", "bytes */" + total);
                    headers.put("Content-Length", "0");
                    return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, new ByteArrayInputStream(new byte[0]));
                }
            }
            long length = Math.max(0, end - start + 1);
            headers.put("Content-Length", String.valueOf(length));
            if (partial) headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
            InputStream input;
            if ("HEAD".equalsIgnoreCase(method)) input = new ByteArrayInputStream(new byte[0]);
            else {
                FileInputStream fileInput = new FileInputStream(file);
                fileInput.getChannel().position(start);
                final long requestedLength = length;
                input = new FilterInputStream(fileInput) {
                    private long remaining = requestedLength;
                    @Override public int read() throws java.io.IOException {
                        if (remaining == 0) return -1;
                        int result = in.read();
                        if (result != -1) remaining--;
                        return result;
                    }
                    @Override public int read(byte[] buffer, int offset, int count) throws java.io.IOException {
                        if (count == 0) return 0;
                        if (remaining == 0) return -1;
                        int result = in.read(buffer, offset, (int) Math.min(count, remaining));
                        if (result > 0) remaining -= result;
                        return result;
                    }
                    @Override public long skip(long count) throws java.io.IOException {
                        long skipped = in.skip(Math.min(Math.max(0, count), remaining));
                        remaining -= skipped;
                        return skipped;
                    }
                    @Override public int available() throws java.io.IOException { return (int) Math.min(in.available(), remaining); }
                };
            }
            return new WebResourceResponse(mime, null, partial ? 206 : 200, partial ? "Partial Content" : "OK", headers, input);
        } catch (Exception failure) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String requestHeader(WebResourceRequest request, String name) {
        for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
            if (name.equalsIgnoreCase(header.getKey())) return header.getValue();
        }
        return null;
    }

    private JSONObject saveGeneratedMedia(InputStream input, String mime, long declaredLength, RequestTask task) throws Exception {
        if (declaredLength > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("音视频响应超过 64 MB，请使用网关返回的文件链接");
        return saveGeneratedMediaFile(mime, task, output -> {
            try (InputStream source = input) {
                byte[] buffer = new byte[16384];
                int count, total = 0;
                while ((count = source.read(buffer)) != -1) {
                    if (task.cancelled) throw new IllegalStateException("请求已停止");
                    if (total > MAX_RESPONSE_BYTES - count) throw new IllegalArgumentException("音视频响应超过 64 MB，请使用网关返回的文件链接");
                    output.write(buffer, 0, count);
                    total += count;
                }
                if (total == 0) throw new IllegalArgumentException("网关返回的音视频为空");
            }
        });
    }

    private interface MediaWriter { void write(OutputStream output) throws Exception; }

    private JSONObject saveGeneratedMediaFile(String mime, RequestTask task, MediaWriter writer) throws Exception {
        if (!mime.matches("(?:audio|video)/[A-Za-z0-9.+-]+")) throw new IllegalArgumentException("音视频响应类型无效");
        String extension;
        switch (mime) {
            case "audio/mpeg": case "audio/mp3": extension = "mp3"; break;
            case "audio/wav": case "audio/x-wav": case "audio/wave": extension = "wav"; break;
            case "audio/flac": case "audio/x-flac": extension = "flac"; break;
            case "audio/ogg": extension = "ogg"; break;
            case "audio/mp4": case "audio/x-m4a": extension = "m4a"; break;
            case "audio/aac": extension = "aac"; break;
            case "audio/pcm": case "audio/l16": extension = "pcm"; break;
            case "video/mp4": extension = "mp4"; break;
            case "video/webm": extension = "webm"; break;
            case "audio/webm": extension = "weba"; break;
            default: extension = "bin";
        }
        File directory = new File(getFilesDir(), "generated-media");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法保存生成的音视频");
        String name = UUID.randomUUID() + "." + extension;
        File pending = new File(directory, name + ".pending");
        File ready = new File(directory, name);
        File metadata = new File(directory, name + ".json");
        boolean saved = false;
        try {
            try (OutputStream output = new FileOutputStream(pending)) {
                writer.write(output);
            }
            if (task.cancelled) throw new IllegalStateException("请求已停止");
            if (!pending.renameTo(ready)) throw new IllegalStateException("无法保存生成的音视频");
            try (OutputStream output = new FileOutputStream(metadata)) {
                output.write(new JSONObject().put("mime", mime).toString().getBytes(StandardCharsets.UTF_8));
            }
            saved = true;
            return new JSONObject().put("__localMedia", true).put("mime", mime).put("url", "https://" + ASSET_HOST + "/generated-media/" + name);
        } finally {
            pending.delete();
            if (!saved) { ready.delete(); metadata.delete(); }
        }
    }

    private JSONObject readAudioChunks(InputStream input, String mime, RequestTask task) throws Exception {
        mime = mime.toLowerCase(Locale.ROOT).trim();
        if (!mime.matches("audio/(mpeg|mp3|aac|wav|x-wav|wave|flac|x-flac|ogg|opus|pcm|l16|mp4|x-m4a|webm)")) {
            throw new IllegalArgumentException("音频输出格式无效");
        }
        return saveGeneratedMediaFile(mime, task, output -> {
            int total = 0;
            StringBuilder pendingJson = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (task.cancelled) throw new IllegalStateException("请求已停止");
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) continue;
                    if (line.startsWith("data:")) line = line.substring(5).trim();
                    if (line.equals("[DONE]")) break;
                    if ((long) pendingJson.length() + line.length() > 90L * 1024 * 1024) throw new IllegalArgumentException("音频响应超过大小限制");
                    pendingJson.append(line);
                    JSONObject chunk;
                    try { chunk = new JSONObject(pendingJson.toString()); }
                    catch (org.json.JSONException incomplete) { continue; }
                    pendingJson.setLength(0);
                    if (!chunk.has("code")) throw new IllegalArgumentException("网关未返回已知格式的音频流");
                    long code = chunk.getLong("code");
                    if (code != 0 && code != 20000000) {
                        throw new IllegalArgumentException("语音生成失败（" + code + "）：" + chunk.optString("message", chunk.optString("msg", "请稍后重试")));
                    }
                    Object value = chunk.opt("data");
                    if (value instanceof String && !((String) value).isEmpty()) {
                        String data = (String) value;
                        if ((long) data.length() * 3 / 4 > MAX_RESPONSE_BYTES - total + 2L) throw new IllegalArgumentException("音频响应超过 64 MB");
                        try (InputStream decoded = base64AudioStream(data)) {
                            byte[] buffer = new byte[16384];
                            int count;
                            while ((count = decoded.read(buffer)) != -1) {
                                if (task.cancelled) throw new IllegalStateException("请求已停止");
                                if (total > MAX_RESPONSE_BYTES - count) throw new IllegalArgumentException("音频响应超过 64 MB");
                                output.write(buffer, 0, count);
                                total += count;
                            }
                        }
                    }
                    if (code == 20000000) break;
                }
                if (pendingJson.length() != 0) throw new IllegalArgumentException("网关返回的音频流不完整");
                if (total == 0) throw new IllegalArgumentException("网关未返回音频，请检查语音模型参数");
            }
        });
    }

    private String loadInterfaceSnapshot() {
        try {
            if (!config.baseUrl().equals(interfacePrefs.getString("gateway", ""))) return "{\"endpoints\":{},\"syncedAt\":0}";
            JSONObject saved = new JSONObject(interfacePrefs.getString("snapshot", "{}"));
            JSONObject endpoints = GatewayLoginActivity.sanitizeEndpoints(saved.optJSONObject("endpoints"));
            return new JSONObject().put("endpoints", endpoints).put("syncedAt", saved.optLong("syncedAt", 0)).toString();
        } catch (Exception failure) { return "{\"endpoints\":{},\"syncedAt\":0}"; }
    }

    private JSONObject extractJsonAudio(JSONObject response, JSONObject options, RequestTask task) throws Exception {
        String data = null;
        String mime = options.optString("audioMime", "audio/wav").toLowerCase(Locale.ROOT).trim();
        JSONArray choices = response.optJSONArray("choices");
        if (choices != null) {
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.optJSONObject(i);
                JSONObject message = choice == null ? null : choice.optJSONObject("message");
                JSONObject audio = message == null ? null : message.optJSONObject("audio");
                if (audio == null || !(audio.opt("data") instanceof String)) continue;
                if (data == null && !audio.getString("data").isEmpty()) {
                    data = audio.getString("data");
                    String format = audio.optString("format", "").toLowerCase(Locale.ROOT).trim();
                    if (!format.isEmpty()) {
                        switch (format) {
                            case "mp3": case "mpeg": mime = "audio/mpeg"; break;
                            case "wav": case "wave": mime = "audio/wav"; break;
                            case "aac": mime = "audio/aac"; break;
                            case "flac": mime = "audio/flac"; break;
                            case "ogg": case "opus": mime = "audio/ogg"; break;
                            case "pcm": case "pcm16": mime = "audio/pcm"; break;
                            default: if (format.startsWith("audio/")) mime = format;
                        }
                    }
                }
                audio.remove("data");
            }
        }
        if (data == null && response.opt("audio") instanceof String && !response.getString("audio").isEmpty()) {
            if (response.has("code") && response.optLong("code", -1) != 0 && response.optLong("code", -1) != 20000000) {
                throw new IllegalArgumentException("语音生成失败，请检查模型参数");
            }
            data = response.getString("audio");
            response.remove("audio");
        }
        if (data == null) return null;
        if (!mime.matches("audio/(mpeg|mp3|aac|wav|x-wav|wave|flac|x-flac|ogg|opus|pcm|l16|mp4|x-m4a|webm)")) throw new IllegalArgumentException("音频输出格式无效");
        if ((long) data.length() * 3 / 4 > MAX_RESPONSE_BYTES + 2L) throw new IllegalArgumentException("音频响应超过 64 MB");
        JSONObject media = saveGeneratedMedia(base64AudioStream(data), mime, -1, task);
        return response.put("__localMedia", true).put("mime", media.getString("mime")).put("url", media.getString("url"));
    }

    private static InputStream base64AudioStream(String encodedAudio) {
        InputStream encoded = new InputStream() {
            private int offset;
            @Override public int read() throws java.io.IOException {
                if (offset == encodedAudio.length()) return -1;
                char value = encodedAudio.charAt(offset++);
                if (value > 127) throw new java.io.IOException("音频编码无效");
                return value;
            }
            @Override public int read(byte[] buffer, int start, int length) throws java.io.IOException {
                if (length == 0) return 0;
                if (offset == encodedAudio.length()) return -1;
                int count = Math.min(length, encodedAudio.length() - offset);
                for (int i = 0; i < count; i++) buffer[start + i] = (byte) read();
                return count;
            }
        };
        return new Base64InputStream(encoded, Base64.DEFAULT);
    }

    private void emit(String id, String type, String data, int status, String event) {
        try {
            JSONObject object = new JSONObject().put("id", id).put("type", type);
            if (data != null) object.put("data", data);
            if (status != 0) object.put("status", status);
            if (event != null && !event.isEmpty()) object.put("event", event);
            String script = "window.__nativeEvent && window.__nativeEvent(" + object + ");";
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) webView.evaluateJavascript(script, null);
            });
        } catch (Exception ignored) {
            // No request content or gateway secret is written to Android logs.
        }
    }

    private void startRequest(String id, JSONObject options) {
        if (id == null || id.isEmpty()) return;
        if (active.containsKey(id)) {
            emit(id, "error", "请求编号已存在", 0, null);
            return;
        }
        RequestTask task = new RequestTask();
        active.put(id, task);
        task.future = requests.submit(() -> executeRequest(id, options, task));
    }

    private void executeRequest(String id, JSONObject options, RequestTask task) {
        String secret = "";
        try {
            secret = config.apiKey();
            if (secret.isEmpty()) throw new IllegalArgumentException("请先在设置中填写雷火网关密钥");
            String endpoint = options.getString("endpoint");
            if (!endpoint.startsWith("/") || endpoint.startsWith("//") || endpoint.contains("\\")
                    || endpoint.contains("..") || endpoint.indexOf('\r') >= 0 || endpoint.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("API 路径必须是当前网关的绝对路径");
            }
            URL root = new URL(config.baseUrl());
            URL target = new URL(config.baseUrl() + endpoint);
            if (!"https".equalsIgnoreCase(target.getProtocol()) || !root.getHost().equalsIgnoreCase(target.getHost())
                    || root.getPort() != target.getPort()) throw new IllegalArgumentException("请求地址不属于已设置的网关");
            String method = options.optString("method", "POST").toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("POST") && !method.equals("DELETE") && !method.equals("PUT") && !method.equals("PATCH")) {
                throw new IllegalArgumentException("不支持的请求方法");
            }
            if (task.cancelled) return;
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            task.connection = connection;
            connection.setRequestMethod(method);
            connection.setConnectTimeout(25000);
            connection.setReadTimeout(600000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + secret);
            if (endpoint.contains("/messages")) connection.setRequestProperty("x-api-key", secret);
            if (endpoint.startsWith("/v1beta/")) connection.setRequestProperty("x-goog-api-key", secret);
            connection.setRequestProperty("Accept", options.optBoolean("stream", false) ? "text/event-stream, application/json" : "application/json, */*");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "LeihuoMobile/1.0 Android");
            JSONObject headers = options.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> names = headers.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    String lower = name.toLowerCase(Locale.ROOT);
                    String value = headers.optString(name);
                    if (!lower.equals("authorization") && !lower.equals("x-api-key") && !lower.equals("x-goog-api-key")
                            && !lower.equals("host") && !lower.equals("cookie") && !lower.equals("content-length")
                            && name.matches("[A-Za-z0-9-]+") && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
                        connection.setRequestProperty(name, value);
                    }
                }
            }
            Object bodyObject = options.opt("body");
            String body = bodyObject == null || bodyObject == JSONObject.NULL ? "" : bodyObject.toString();
            JSONObject multipart = options.optJSONObject("multipart");
            byte[] requestBytes = null;
            if (multipart != null) {
                if (!method.equals("POST") && !method.equals("PUT")) throw new IllegalArgumentException("文件上传需要 POST 或 PUT 请求");
                String boundary = "Leihuo-" + UUID.randomUUID();
                requestBytes = encodeMultipart(multipart, boundary);
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            } else if (!method.equals("GET") && !body.isEmpty()) {
                requestBytes = body.getBytes(StandardCharsets.UTF_8);
            }
            if (requestBytes != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(requestBytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(requestBytes); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (status < 200 || status >= 300) {
                String error = input == null ? "网关请求失败" : new String(readAll(input, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
                if (status >= 300 && status < 400) error = "网关返回了重定向，请在设置中填写最终 HTTPS 网关地址";
                emit(id, "error", redact(error, secret), status, null);
                return;
            }
            String mime = connection.getContentType();
            mime = mime == null ? "application/json" : mime.toLowerCase(Locale.ROOT);
            if (input == null) {
                emit(id, "data", "", status, null);
            } else if (options.optBoolean("audioChunkStream", false)) {
                JSONObject media = readAudioChunks(input, options.optString("audioMime", "audio/mpeg"), task);
                if (!task.cancelled) emit(id, "data", media.toString(), status, null);
            } else if (mime.contains("text/event-stream")) {
                readEvents(id, input, task, status, secret);
            } else if (options.optBoolean("responseAsMedia", false) && (mime.startsWith("audio/") || mime.startsWith("video/"))) {
                JSONObject media = saveGeneratedMedia(input, mime.split(";")[0].trim(), connection.getContentLengthLong(), task);
                if (!task.cancelled) emit(id, "data", media.toString(), status, null);
            } else {
                byte[] bytes = readAll(input, MAX_RESPONSE_BYTES);
                if (mime.contains("json") || mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")) {
                    JSONObject structured = null;
                    if (options.optBoolean("responseAsMedia", false) && mime.contains("json")) {
                        try { structured = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
                        catch (org.json.JSONException ignored) { /* Ordinary JSON responses retain their existing representation. */ }
                    }
                    JSONObject media = structured == null ? null : extractJsonAudio(structured, options, task);
                    if (media != null) emit(id, "data", redact(media.toString(), secret), status, null);
                    else emit(id, "data", redact(new String(bytes, StandardCharsets.UTF_8), secret), status, null);
                } else {
                    JSONObject binary = new JSONObject().put("__binary", true).put("mime", mime.split(";")[0])
                            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
                    emit(id, "data", binary.toString(), status, null);
                }
            }
            if (!task.cancelled) emit(id, "done", null, status, null);
        } catch (Exception failure) {
            if (!task.cancelled) {
                String message = failure.getMessage();
                if (message == null || message.isEmpty()) message = "网络请求失败，请检查网关地址和连接";
                emit(id, "error", redact(message, secret), 0, null);
            }
        } finally {
            if (task.connection != null) task.connection.disconnect();
            active.remove(id, task);
        }
    }

    private static byte[] encodeMultipart(JSONObject multipart, String boundary) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            JSONObject fields = multipart.optJSONObject("fields");
            int fieldBytes = 0;
            if (fields != null) {
                Iterator<String> names = fields.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    Object value = fields.get(name);
                    if (!(value instanceof String)) throw new IllegalArgumentException("上传表单字段必须是字符串");
                    byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    fieldBytes += bytes.length;
                    if (fieldBytes > 2 * 1024 * 1024) throw new IllegalArgumentException("上传表单内容过大");
                    writeUtf8(output, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + safePartName(name) + "\"\r\n\r\n");
                    output.write(bytes);
                    writeUtf8(output, "\r\n");
                }
            }
            JSONArray files = multipart.optJSONArray("files");
            int fileBytes = 0;
            if (files != null) {
                if (files.length() > 8) throw new IllegalArgumentException("一次最多上传 8 个文件");
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.getJSONObject(i);
                    String encoded = file.getString("base64");
                    if (encoded.startsWith("data:")) encoded = encoded.substring(encoded.indexOf(',') + 1);
                    if (encoded.length() > 22 * 1024 * 1024) throw new IllegalArgumentException("上传文件合计不能超过 15 MB");
                    byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                    fileBytes += bytes.length;
                    if (fileBytes > 15 * 1024 * 1024) throw new IllegalArgumentException("上传文件合计不能超过 15 MB");
                    String mime = file.optString("mime", "application/octet-stream");
                    if (!mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) throw new IllegalArgumentException("文件类型无效");
                    writeUtf8(output, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + safePartName(file.optString("field", "file"))
                            + "\"; filename=\"" + safePartName(file.optString("name", "upload")) + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
                    output.write(bytes);
                    writeUtf8(output, "\r\n");
                }
            }
            writeUtf8(output, "--" + boundary + "--\r\n");
            return output.toByteArray();
        }
    }

    private static String safePartName(String value) {
        if (value.isEmpty() || value.length() > 255 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException("上传字段或文件名无效");
        return value.replace("\\", "_").replace("\"", "_");
    }

    private static void writeUtf8(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private void readEvents(String id, InputStream input, RequestTask task, int status, String secret) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String event = "";
            String line;
            while (!task.cancelled && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() != 0) {
                        String payload = data.substring(0, data.length() - 1);
                        emit(id, "data", redact(payload, secret), status, event);
                        data.setLength(0);
                        if (payload.equals("[DONE]")) break;
                    }
                    event = "";
                } else if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    data.append(value).append('\n');
                    if (data.length() > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("单条响应超过大小限制");
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                }
            }
            if (!task.cancelled && data.length() != 0) emit(id, "data", redact(data.substring(0, data.length() - 1), secret), status, event);
        }
    }

    private static byte[] readAll(InputStream input, int limit) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (result.size() + count > limit) throw new IllegalArgumentException("响应超过 64 MB，请使用网关返回的文件链接");
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }

    private static String redact(String text, String secret) {
        return secret == null || secret.isEmpty() ? text : text.replace(secret, "[密钥已隐藏]");
    }

    private void pickImages() {
        if (imagePickActive || isFinishing() || isDestroyed()) return;
        clearPickedImages();
        imagePickActive = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, IMAGE_PICKER);
        } catch (ActivityNotFoundException missingDocuments) {
            try {
                intent.setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(intent, "选择图片"), IMAGE_PICKER);
            } catch (Exception failure) {
                imagePickActive = false;
                dispatchPickedImages("error", "无法打开系统相册或文件选择器", false);
            }
        } catch (Exception failure) {
            imagePickActive = false;
            dispatchPickedImages("error", "无法打开图片选择器，请重试", false);
        }
    }

    private synchronized void clearPickedImages() { pendingPickedImageEvent = null; }

    private synchronized String takePickedImages() {
        JSONObject event = pendingPickedImageEvent;
        pendingPickedImageEvent = null;
        if (event == null) return "";
        try { return event.toString(); }
        catch (OutOfMemoryError tooLarge) {
            return "{\"id\":\"imagePicker\",\"type\":\"error\",\"restore\":true,\"data\":\"手机内存不足，请减少选取的图片数量\"}";
        }
    }

    private void dispatchPickedImages(String type, String data, boolean restored) {
        try {
            JSONObject event = new JSONObject().put("id", "imagePicker").put("type", type)
                    .put("restore", restored).put("pickerEventId", UUID.randomUUID().toString());
            if (data != null) event.put("data", data);
            synchronized (this) { pendingPickedImageEvent = event; }
            String script = "window.__nativeEvent && window.__nativeEvent(" + event + ");";
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) webView.evaluateJavascript(script, null);
            });
        } catch (OutOfMemoryError tooLarge) {
            if (!"error".equals(type)) dispatchPickedImages("error", "手机内存不足，请减少选取的图片数量", restored);
        } catch (Exception failure) {
            emit("imagePicker", "error", "读取图片失败，请重试", 0, null);
        }
    }

    private String selectedImageName(Uri uri, int index) {
        String fallback = "照片-" + (index + 1) + ".jpg";
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    String clean = name.replaceAll("[\\x00-\\x1F\\x7F]", "");
                    return clean.isEmpty() ? fallback : clean.substring(0, Math.min(clean.length(), 180));
                }
            }
        } catch (Exception ignored) { /* Some photo providers do not expose a display name. */ }
        return fallback;
    }

    private JSONObject readSelectedImage(Uri uri, String name) throws Exception {
        String scheme = uri.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme)) {
            throw new IllegalArgumentException("请选择手机相册或文件中的图片");
        }
        byte[] bytes;
        try (InputStream source = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream raw = new ByteArrayOutputStream()) {
            if (source == null) throw new IllegalArgumentException("无法读取照片，请重新选择");
            byte[] buffer = new byte[16384];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (raw.size() > MAX_IMAGE_BYTES - count) throw new IllegalArgumentException("图片超过 15 MB，请选择更小的图片");
                raw.write(buffer, 0, count);
            }
            bytes = raw.toByteArray();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("手机无法读取这种图片格式，请换用 JPEG、PNG 或 WebP");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IllegalArgumentException("照片解码失败，请重新选择或转换为 JPEG");
        try {
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream metadata = new ByteArrayInputStream(bytes)) {
                orientation = new ExifInterface(metadata).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Exception ignored) { /* Images without EXIF keep their decoded orientation. */ }
            float scale = Math.min(1f, 1600f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            Bitmap resized = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (resized != bitmap) { bitmap.recycle(); bitmap = resized; }
            Matrix transform = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(270); transform.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(270); break;
                default: break;
            }
            if (!transform.isIdentity()) {
                Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
                if (oriented != bitmap) { bitmap.recycle(); bitmap = oriented; }
            }
            if (bitmap.hasAlpha()) {
                Bitmap opaque = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, 0, 0, null);
                bitmap.recycle(); bitmap = opaque;
            }
            try (ByteArrayOutputStream jpeg = new ByteArrayOutputStream()) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 86, jpeg)) throw new IllegalArgumentException("照片转换失败，请重试");
                return new JSONObject().put("type", "image").put("name", name)
                        .put("data", "data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP));
            }
        } finally { bitmap.recycle(); }
    }

    private void receivePickedImages(int resultCode, Intent intent) {
        boolean restored = !imagePickActive;
        if (resultCode != RESULT_OK || intent == null) {
            imagePickActive = false;
            dispatchPickedImages("done", null, restored);
            return;
        }
        imagePickActive = true;
        ArrayList<Uri> selected = new ArrayList<>();
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount() && selected.size() < 5; index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null && !selected.contains(uri)) selected.add(uri);
            }
        }
        if (intent.getData() != null && !selected.contains(intent.getData()) && selected.size() < 5) selected.add(intent.getData());
        requests.submit(() -> {
            JSONArray files = new JSONArray(), errors = new JSONArray();
            if (clip != null && clip.getItemCount() > 5) errors.put("每次最多添加 5 张图片，其余图片未添加");
            if (selected.isEmpty()) errors.put("相册没有返回图片，请重新选择");
            for (int index = 0; index < selected.size(); index++) {
                String name = selectedImageName(selected.get(index), index);
                try { files.put(readSelectedImage(selected.get(index), name)); }
                catch (Exception failure) { errors.put(name + "：" + (failure instanceof IllegalArgumentException ? failure.getMessage() : "无法读取照片，请重新选择")); }
                catch (OutOfMemoryError tooLarge) { errors.put(name + "：图片过大，手机内存不足，请选择更小的图片"); }
            }
            try { dispatchPickedImages("data", new JSONObject().put("files", files).put("errors", errors).toString(), restored); }
            catch (OutOfMemoryError tooLarge) { dispatchPickedImages("error", "手机内存不足，请减少选取的图片数量", restored); }
            catch (Exception failure) { dispatchPickedImages("error", "读取图片失败，请重试", restored); }
            finally { runOnUiThread(() -> imagePickActive = false); }
        });
    }

    private void openExternalUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return;
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            emit("external", "error", "无法打开外部链接", 0, null);
        }
    }

    private void prepareMediaSave(String url) {
        if (mediaBusy) {
            Toast.makeText(this, "请先完成当前图片保存", Toast.LENGTH_SHORT).show();
            return;
        }
        mediaBusy = true;
        Toast.makeText(this, "正在准备图片…", Toast.LENGTH_SHORT).show();
        requests.submit(() -> {
            File temporary = null;
            HttpURLConnection connection = null;
            try {
                temporary = File.createTempFile("leihuo-image-", ".tmp", getCacheDir());
                String mime;
                InputStream input;
                if (url != null && url.startsWith("data:")) {
                    int comma = url.indexOf(',');
                    if (comma < 0) throw new IllegalArgumentException("图片数据无效");
                    String header = url.substring(0, comma).toLowerCase(Locale.ROOT);
                    if (!header.equals("data:image/png;base64") && !header.equals("data:image/jpeg;base64")
                            && !header.equals("data:image/webp;base64")) throw new IllegalArgumentException("只支持保存 PNG、JPEG 和 WebP 图片数据");
                    if (url.length() - comma > 90 * 1024 * 1024) throw new IllegalArgumentException("图片不能超过 64 MB");
                    mime = header.substring(5, header.indexOf(';'));
                    input = new Base64InputStream(new ByteArrayInputStream(url.substring(comma + 1).getBytes(StandardCharsets.US_ASCII)), Base64.DEFAULT);
                } else {
                    URL target = new URL(url == null ? "" : url);
                    mime = null;
                    input = null;
                    for (int redirects = 0; redirects < 5; redirects++) {
                        if (!"https".equalsIgnoreCase(target.getProtocol()) || target.getUserInfo() != null) throw new IllegalArgumentException("图片链接必须是 HTTPS 地址");
                        connection = (HttpURLConnection) target.openConnection();
                        connection.setConnectTimeout(20000);
                        connection.setReadTimeout(60000);
                        connection.setInstanceFollowRedirects(false);
                        // Public image downloads never receive gateway authentication or cookies.
                        int status = connection.getResponseCode();
                        if (status >= 300 && status < 400) {
                            String location = connection.getHeaderField("Location");
                            connection.disconnect();
                            if (location == null) throw new IllegalArgumentException("图片链接重定向无效");
                            target = new URL(target, location);
                            continue;
                        }
                        if (status < 200 || status >= 300) throw new IllegalArgumentException("无法下载图片，请稍后重试");
                        String type = connection.getContentType();
                        mime = type == null ? "" : type.toLowerCase(Locale.ROOT).split(";")[0].trim();
                        if (!mime.startsWith("image/") || mime.contains("svg")) throw new IllegalArgumentException("该链接不是可保存的图片");
                        if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("图片不能超过 64 MB");
                        input = connection.getInputStream();
                        break;
                    }
                    if (input == null || mime == null) throw new IllegalArgumentException("图片链接重定向次数过多");
                }
                try (InputStream source = input; OutputStream output = new FileOutputStream(temporary)) {
                    copyLimited(source, output, MAX_RESPONSE_BYTES);
                }
                File ready = temporary;
                String readyMime = mime;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) { ready.delete(); mediaBusy = false; return; }
                    try {
                        pendingMedia = ready;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(readyMime);
                        String extension = readyMime.equals("image/jpeg") ? "jpg" : readyMime.substring(6).replaceAll("[^a-z0-9]", "");
                        intent.putExtra(Intent.EXTRA_TITLE, "雷火图片-" + System.currentTimeMillis() + "." + extension);
                        startActivityForResult(intent, MEDIA_EXPORT);
                    } catch (Exception failure) {
                        ready.delete(); pendingMedia = null; mediaBusy = false;
                        Toast.makeText(MainActivity.this, "无法打开图片保存位置", Toast.LENGTH_SHORT).show();
                        emit("media", "error", "无法打开图片保存位置", 0, null);
                    }
                });
                temporary = null;
            } catch (Exception failure) {
                if (temporary != null) temporary.delete();
                String message = failure instanceof IllegalArgumentException ? failure.getMessage() : "图片保存失败，请检查链接后重试";
                runOnUiThread(() -> { mediaBusy = false; Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show(); });
                emit("media", "error", message, 0, null);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static void copyLimited(InputStream input, OutputStream output, int limit) throws Exception {
        byte[] buffer = new byte[16384];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (total > limit - count) throw new IllegalArgumentException("图片不能超过 64 MB");
            total += count;
            output.write(buffer, 0, count);
        }
        if (total == 0) throw new IllegalArgumentException("图片内容为空");
    }

    private void applyTheme(boolean dark) {
        darkTheme = dark;
        int background = dark ? Color.rgb(33, 33, 33) : Color.WHITE;
        frame.setBackgroundColor(background);
        webView.setBackgroundColor(background);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private final class NativeBridge {
        @JavascriptInterface public String loadChats() { return chats.load(); }

        @JavascriptInterface public void pickImages() { runOnUiThread(() -> MainActivity.this.pickImages()); }

        @JavascriptInterface public String takePickedImages() { return MainActivity.this.takePickedImages(); }

        @JavascriptInterface public void clearPickedImages() { MainActivity.this.clearPickedImages(); }

        @JavascriptInterface public String loadInterfaces() { return loadInterfaceSnapshot(); }

        @JavascriptInterface public void syncInterfaces() {
            runOnUiThread(() -> {
                if (!GatewayLoginActivity.isGatewayRoot(config.baseUrl())) {
                    emit("interfaces", "error", "接口同步仅支持网易雷火官方网关地址", 0, null);
                    return;
                }
                Intent intent = new Intent(MainActivity.this, GatewayLoginActivity.class);
                intent.putExtra("gateway", config.baseUrl());
                startActivityForResult(intent, INTERFACE_SYNC);
            });
        }

        @JavascriptInterface public String saveChats(String json) { return chats.save(json); }

        @JavascriptInterface public String getConfig() {
            try { return config.publicConfig().toString(); }
            catch (Exception failure) { return "{\"baseUrl\":\"https://ai.leihuo.netease.com\",\"hasKey\":false,\"error\":\"无法读取密钥，请重新填写\"}"; }
        }

        @JavascriptInterface public String saveConfig(String json) {
            try { return new JSONObject().put("ok", true).put("config", config.save(new JSONObject(json))).toString(); }
            catch (Exception failure) {
                try { return new JSONObject().put("ok", false).put("error", failure instanceof IllegalArgumentException ? failure.getMessage() : "无法保存密钥，请重试").toString(); }
                catch (Exception ignored) { return "{\"ok\":false}"; }
            }
        }

        @JavascriptInterface public void request(String id, String endpoint, String bodyJson, boolean stream) {
            try {
                startRequest(id, new JSONObject().put("endpoint", endpoint).put("method", bodyJson == null || bodyJson.isEmpty() ? "GET" : "POST")
                        .put("body", bodyJson == null ? "" : bodyJson).put("stream", stream));
            } catch (Exception failure) { emit(id, "error", "请求参数无效", 0, null); }
        }

        @JavascriptInterface public void requestWithOptions(String id, String optionsJson) {
            try { startRequest(id, new JSONObject(optionsJson)); }
            catch (Exception failure) { emit(id, "error", "请求参数无效", 0, null); }
        }

        @JavascriptInterface public void cancel(String id) {
            RequestTask task = active.remove(id);
            if (task != null) {
                task.cancelled = true;
                if (task.connection != null) task.connection.disconnect();
                if (task.future != null) task.future.cancel(true);
                emit(id, "done", null, 0, null);
            }
        }

        @JavascriptInterface public void openExternal(String url) { runOnUiThread(() -> openExternalUrl(url)); }

        @JavascriptInterface public void saveMedia(String url) { runOnUiThread(() -> prepareMediaSave(url)); }

        @JavascriptInterface public void copy(String text) {
            runOnUiThread(() -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("雷火", text)));
        }

        @JavascriptInterface public void setTheme(String theme) { runOnUiThread(() -> applyTheme("dark".equals(theme))); }

        @JavascriptInterface public void speech() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
                    intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你的问题");
                    startActivityForResult(intent, SPEECH_INPUT);
                } catch (ActivityNotFoundException failure) {
                    emit("speech", "error", "手机上没有可用的语音输入服务，可使用键盘的语音输入", 0, null);
                }
            });
        }

        @JavascriptInterface public void speak(String text) {
            runOnUiThread(() -> {
                if (speechReady) textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "leihuo-reading");
                else emit("speak", "error", "手机上没有可用的朗读服务", 0, null);
            });
        }

        @JavascriptInterface public void stopSpeech() { runOnUiThread(() -> { if (textToSpeech != null) textToSpeech.stop(); }); }

        @JavascriptInterface public void exportChats(String json) {
            runOnUiThread(() -> {
                try {
                    new JSONObject(json);
                    pendingExport = json;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, "雷火聊天记录.json");
                    startActivityForResult(intent, EXPORT_CHATS);
                } catch (Exception failure) { emit("export", "error", "无法导出聊天记录", 0, null); }
            });
        }

        @JavascriptInterface public void importChats() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"});
                    startActivityForResult(intent, IMPORT_CHATS);
                } catch (Exception failure) { emit("import", "error", "无法打开文件选择器", 0, null); }
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == INTERFACE_SYNC) {
            if (resultCode == RESULT_OK && intent != null) {
                try {
                    String gateway = intent.getStringExtra("gateway");
                    String raw = intent.getStringExtra("endpoints");
                    if (!config.baseUrl().equals(gateway) || !GatewayLoginActivity.isGatewayRoot(gateway)
                            || raw == null || raw.length() > GatewayLoginActivity.MAX_PAYLOAD_CHARS) throw new IllegalArgumentException();
                    JSONObject endpoints = GatewayLoginActivity.sanitizeEndpoints(new JSONObject(raw));
                    if (endpoints.length() == 0) throw new IllegalArgumentException();
                    JSONObject snapshot = new JSONObject().put("endpoints", endpoints).put("syncedAt", System.currentTimeMillis());
                    if (!interfacePrefs.edit().putString("gateway", gateway).putString("snapshot", snapshot.toString()).commit()) throw new IllegalStateException();
                    emit("interfaces", "data", snapshot.toString(), 0, null);
                    emit("interfaces", "done", null, 0, null);
                } catch (Exception failure) {
                    emit("interfaces", "error", "接口映射无法保存，请重新读取官方接口", 0, null);
                }
            } else emit("interfaces", "done", null, 0, null);
        } else if (requestCode == IMAGE_PICKER) {
            receivePickedImages(resultCode, intent);
        } else if (requestCode == FILE_PICKER) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
                fileCallback = null;
            }
        } else if (requestCode == SPEECH_INPUT) {
            if (resultCode == RESULT_OK && intent != null) {
                ArrayList<String> result = intent.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (result != null && !result.isEmpty()) emit("speech", "data", result.get(0), 0, null);
            } else emit("speech", "done", null, 0, null);
        } else if (requestCode == EXPORT_CHATS) {
            String content = pendingExport;
            pendingExport = null;
            if (resultCode == RESULT_OK && intent != null && intent.getData() != null && content != null) {
                Uri uri = intent.getData();
                requests.submit(() -> {
                    try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IllegalStateException();
                        output.write(content.getBytes(StandardCharsets.UTF_8));
                        emit("export", "done", null, 0, null);
                    } catch (Exception failure) { emit("export", "error", "导出失败，请重试", 0, null); }
                });
            } else emit("export", "done", null, 0, null);
        } else if (requestCode == MEDIA_EXPORT) {
            File source = pendingMedia;
            pendingMedia = null;
            if (resultCode == RESULT_OK && intent != null && intent.getData() != null && source != null) {
                Uri destination = intent.getData();
                requests.submit(() -> {
                    try {
                        try (InputStream input = new FileInputStream(source); OutputStream output = getContentResolver().openOutputStream(destination)) {
                            if (output == null) throw new IllegalStateException();
                            copyLimited(input, output, MAX_RESPONSE_BYTES);
                        }
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "图片已保存", Toast.LENGTH_SHORT).show());
                        emit("media", "done", "图片已保存", 0, null);
                    } catch (Exception failure) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "图片保存失败，请重试", Toast.LENGTH_SHORT).show());
                        emit("media", "error", "图片保存失败，请重试", 0, null);
                    } finally {
                        source.delete();
                        runOnUiThread(() -> mediaBusy = false);
                    }
                });
            } else {
                if (source != null) source.delete();
                mediaBusy = false;
                emit("media", "done", null, 0, null);
            }
        } else if (requestCode == IMPORT_CHATS) {
            if (resultCode == RESULT_OK && intent != null && intent.getData() != null) {
                Uri uri = intent.getData();
                requests.submit(() -> {
                    try {
                        InputStream input = getContentResolver().openInputStream(uri);
                        if (input == null) throw new IllegalStateException();
                        String content = new String(readAll(input, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
                        new JSONObject(content);
                        emit("import", "data", content, 0, null);
                        emit("import", "done", null, 0, null);
                    } catch (Exception failure) { emit("import", "error", "文件不是有效的聊天备份 JSON", 0, null); }
                });
            } else emit("import", "done", null, 0, null);
        }
    }

    @Override public void onBackPressed() {
        webView.evaluateJavascript("typeof window.__nativeBack === 'function' ? !!window.__nativeBack() : false", result -> {
            if (!"true".equals(result)) finish();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        // Login cookies are accepted only while the separate official login page is open.
        CookieManager.getInstance().setAcceptCookie(false);
    }

    @Override protected void onDestroy() {
        for (RequestTask task : active.values()) {
            task.cancelled = true;
            if (task.connection != null) task.connection.disconnect();
            if (task.future != null) task.future.cancel(true);
        }
        active.clear();
        requests.shutdownNow();
        if (pendingMedia != null) pendingMedia.delete();
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (textToSpeech != null) { textToSpeech.stop(); textToSpeech.shutdown(); }
        webView.removeJavascriptInterface("Native");
        webView.destroy();
        super.onDestroy();
    }
}
