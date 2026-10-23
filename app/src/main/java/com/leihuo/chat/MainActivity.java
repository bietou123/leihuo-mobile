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
import java.io.BufferedInputStream;
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
import java.util.LinkedHashMap;
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
    private static final int NAS_LOGIN = 108;
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final String ASSET_HOST = "leihuo.local";
    private static final String START_PAGE = "https://leihuo.local/index.html";

    private WebView webView;
    private FrameLayout frame;
    private ConfigStore config;
    private GatewayNetwork gatewayNetwork;
    private ChatStore chats;
    private MemoryStore memory;
    private SharedPreferences interfacePrefs;
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private final Map<String, RequestTask> active = new ConcurrentHashMap<>();
    private final Map<String, String> mediaMimes = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 64; }
    };
    private ValueCallback<Uri[]> fileCallback;
    private String pendingExport;
    private String pendingExportTitle;
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
        gatewayNetwork = new GatewayNetwork(this);
        chats = new ChatStore(this);
        memory = new MemoryStore(this);
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
            String originalMediaUrl = target.toExternalForm();
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
                connection = gatewayNetwork.open(target);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method.toUpperCase(Locale.ROOT));
                connection.setRequestProperty("Accept-Encoding", "identity");
                // Only the current gateway's exact video-content route may receive its key.
                // A new connection and origin/path check are required on every redirect.
                String mediaKey = gatewayVideoContentKey(target);
                if (!mediaKey.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + mediaKey);
                attachNasSession(connection, target);
                // Session attachment is independently checked for every NAS API redirect hop.
                // External media and CDN URLs receive no browser cookies.
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
                InputStream responseInput = null;
                boolean detectedMime = false;
                if (mime.equals("application/octet-stream")) {
                    String mediaUrl = target.toExternalForm();
                    String contentRange = connection.getHeaderField("Content-Range");
                    boolean startsAtZero = startsAtByteZero(status, contentRange);
                    if (!"HEAD".equalsIgnoreCase(method) && startsAtZero) {
                        BufferedInputStream buffered = new BufferedInputStream(connection.getInputStream());
                        responseInput = buffered;
                        mime = sniffMediaMime(buffered);
                        // A browser may initially ask for only one byte, which cannot identify a container.
                        if (mime == null && status == 206) mime = cachedMediaMime(mediaUrl, originalMediaUrl);
                        if (mime == null && status == 206) mime = probeMediaMime(target, originalMediaUrl);
                    } else {
                        mime = cachedMediaMime(mediaUrl, originalMediaUrl);
                        if (mime == null) mime = probeMediaMime(target, originalMediaUrl);
                    }
                    if (mime == null) break;
                    detectedMime = true;
                }
                if (mime.equals("image/svg+xml") || (!mime.startsWith("image/") && !mime.startsWith("audio/") && !mime.startsWith("video/"))) break;
                rememberMediaMime(target.toExternalForm(), originalMediaUrl, mime);
                Map<String, String> responseHeaders = new HashMap<>();
                for (String name : new String[]{"Content-Type", "Content-Range", "Accept-Ranges", "Content-Length", "Cache-Control", "ETag", "Last-Modified", "Expires"}) {
                    String value = connection.getHeaderField(name);
                    if (value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) responseHeaders.put(name, value);
                }
                if (detectedMime) responseHeaders.put("Content-Type", mime);
                responseHeaders.put("X-Content-Type-Options", "nosniff");
                String reason = connection.getResponseMessage();
                if (reason != null) reason = reason.replaceAll("[^\\x20-\\x7E]", "").trim();
                if (reason == null || reason.isEmpty()) reason = status == 206 ? "Partial Content" : "OK";
                HttpURLConnection source = connection;
                InputStream body = "HEAD".equalsIgnoreCase(method) ? new ByteArrayInputStream(new byte[0])
                        : responseInput == null ? connection.getInputStream() : responseInput;
                InputStream input = new FilterInputStream(body) {
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

    private String gatewayVideoContentKey(URL target) throws Exception {
        synchronized (config) {
            return isGatewayVideoContent(target) ? config.apiKey() : "";
        }
    }

    private boolean isGatewayVideoContent(URL target) throws Exception {
        synchronized (config) {
            return NasSessionPolicy.isVideoContent(config.baseUrl(), target);
        }
    }

    private void attachNasSession(HttpURLConnection connection, URL target) throws java.io.IOException {
        synchronized (config) {
            if (!NasSessionPolicy.shouldAttach(config.baseUrl(), target)) return;
            try {
                String session = CookieManager.getInstance().getCookie(target.toExternalForm());
                if (session != null && !session.isEmpty() && session.length() <= 16384
                        && session.indexOf('\r') < 0 && session.indexOf('\n') < 0) {
                    connection.setRequestProperty("Cookie", session);
                }
            } catch (Exception unavailable) {
                // Do not propagate cookie contents or header validation errors to the UI.
                throw new java.io.IOException("无法读取 NAS 登录会话，请在设置中重新登录 NAS");
            }
        }
    }

    private boolean canReferenceVideoContentRedirect(URL target, String method, JSONObject options, int status, String location) throws Exception {
        if (!"GET".equals(method) || !options.optBoolean("responseAsMedia", false)
                || (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                || !isGatewayVideoContent(target) || location == null || location.trim().isEmpty()
                || location.indexOf('\r') >= 0 || location.indexOf('\n') >= 0) return false;
        try {
            URL destination = new URL(target, location);
            java.net.URI uri = destination.toURI();
            return "https".equalsIgnoreCase(destination.getProtocol()) && destination.getUserInfo() == null
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && (destination.getPort() == -1 || (destination.getPort() > 0 && destination.getPort() <= 65535));
        } catch (Exception invalidLocation) {
            return false;
        }
    }

    private static int effectiveHttpsPort(URL url) { return url.getPort() == -1 ? 443 : url.getPort(); }

    private static boolean startsAtByteZero(int status, String contentRange) {
        return status != 206 || (contentRange != null && contentRange.matches("(?i)bytes +0-[0-9]+/(?:[0-9]+|\\*)"));
    }

    private String cachedMediaMime(String mediaUrl, String originalMediaUrl) {
        synchronized (mediaMimes) {
            String mime = mediaMimes.get(mediaUrl);
            return mime == null ? mediaMimes.get(originalMediaUrl) : mime;
        }
    }

    private void rememberMediaMime(String mediaUrl, String originalMediaUrl, String mime) {
        synchronized (mediaMimes) {
            mediaMimes.put(mediaUrl, mime);
            mediaMimes.put(originalMediaUrl, mime);
        }
    }

    private String probeMediaMime(URL initialTarget, String originalMediaUrl) {
        HttpURLConnection probe = null;
        try {
            URL target = initialTarget;
            for (int redirects = 0; redirects < 4; redirects++) {
                if (!"https".equalsIgnoreCase(target.getProtocol()) || target.getUserInfo() != null) return null;
                probe = gatewayNetwork.open(target);
                probe.setConnectTimeout(15000);
                probe.setReadTimeout(20000);
                probe.setInstanceFollowRedirects(false);
                probe.setRequestMethod("GET");
                probe.setRequestProperty("Range", "bytes=0-1023");
                probe.setRequestProperty("Accept-Encoding", "identity");
                String mediaKey = gatewayVideoContentKey(target);
                if (!mediaKey.isEmpty()) probe.setRequestProperty("Authorization", "Bearer " + mediaKey);
                attachNasSession(probe, target);
                // Only the configured NAS API path may receive its login session.
                int status = probe.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = probe.getHeaderField("Location");
                    probe.disconnect();
                    probe = null;
                    if (location == null) return null;
                    target = new URL(target, location);
                    continue;
                }
                if (status < 200 || status >= 300 || !startsAtByteZero(status, probe.getHeaderField("Content-Range"))) return null;
                // Even when a server ignores Range and returns 200, consume at most 1 KiB.
                try (BufferedInputStream input = new BufferedInputStream(probe.getInputStream(), 1024)) {
                    try {
                        String mime = sniffMediaMime(input);
                        if (mime != null) {
                            rememberMediaMime(target.toExternalForm(), originalMediaUrl, mime);
                            rememberMediaMime(initialTarget.toExternalForm(), originalMediaUrl, mime);
                        }
                        return mime;
                    } finally {
                        // Abort the remainder before closing the stream, so connection reuse
                        // cannot drain an entire response from a server that ignored Range.
                        probe.disconnect();
                        probe = null;
                    }
                }
            }
        } catch (Exception ignored) {
            // A failed signature probe never grants an unknown binary a playable media type.
        } finally {
            if (probe != null) probe.disconnect();
        }
        return null;
    }

    private static String sniffMediaMime(BufferedInputStream input) throws java.io.IOException {
        byte[] prefix = new byte[1024];
        int count = 0;
        input.mark(prefix.length);
        try {
            while (count < prefix.length) {
                int read = input.read(prefix, count, prefix.length - count);
                if (read == -1) break;
                if (read == 0) break;
                count += read;
            }
        } finally { input.reset(); }
        if (count >= 8 && (prefix[0] & 255) == 0x89 && prefix[1] == 'P' && prefix[2] == 'N' && prefix[3] == 'G'
                && prefix[4] == 13 && prefix[5] == 10 && prefix[6] == 26 && prefix[7] == 10) return "image/png";
        if (count >= 3 && (prefix[0] & 255) == 0xff && (prefix[1] & 255) == 0xd8 && (prefix[2] & 255) == 0xff) return "image/jpeg";
        if (count >= 12 && prefixEquals(prefix, 0, count, "RIFF")) {
            if (count >= 16 && prefixEquals(prefix, 8, count, "WEBP")
                    && (prefixEquals(prefix, 12, count, "VP8 ") || prefixEquals(prefix, 12, count, "VP8L")
                    || prefixEquals(prefix, 12, count, "VP8X"))) return "image/webp";
            if (prefixEquals(prefix, 8, count, "WAVE")) return "audio/wav";
        }
        if (count >= 8 && prefixEquals(prefix, 0, count, "fLaC") && (prefix[4] & 127) == 0
                && prefix[5] == 0 && prefix[6] == 0 && prefix[7] == 34) return "audio/flac";
        if (count >= 10 && prefixEquals(prefix, 0, count, "ID3")) {
            int version = prefix[3] & 255;
            int allowedFlags = version == 2 ? 0xc0 : version == 3 ? 0xe0 : version == 4 ? 0xf0 : 0;
            if (allowedFlags != 0 && (prefix[4] & 255) != 255 && ((prefix[5] & 255) & ~allowedFlags) == 0
                    && (prefix[6] & 128) == 0 && (prefix[7] & 128) == 0
                    && (prefix[8] & 128) == 0 && (prefix[9] & 128) == 0) return "audio/mpeg";
        }
        if (count >= 4 && (prefix[0] & 255) == 255 && (prefix[1] & 224) == 224) {
            int version = (prefix[1] >> 3) & 3, layer = (prefix[1] >> 1) & 3;
            int bitrate = (prefix[2] >> 4) & 15, sampleRate = (prefix[2] >> 2) & 3;
            // Reserved values and AAC ADTS headers cannot be accepted as MP3.
            if (version != 1 && layer == 1 && bitrate != 0 && bitrate != 15 && sampleRate != 3
                    && (prefix[3] & 3) != 2) return "audio/mpeg";
        }
        if (count >= 27 && prefixEquals(prefix, 0, count, "OggS") && prefix[4] == 0
                && (prefix[5] & 2) != 0 && (prefix[5] & 0xf9) == 0) {
            int segments = prefix[26] & 255, payload = 27 + segments;
            if (segments > 0 && payload <= count) {
                int packetSize = 0;
                for (int index = 0; index < segments; index++) {
                    int size = prefix[27 + index] & 255;
                    packetSize += size;
                    if (size < 255) break;
                }
                if (packetSize >= 19 && payload + 19 <= count && prefixEquals(prefix, payload, count, "OpusHead")
                        && prefix[payload + 8] == 1 && (prefix[payload + 9] & 255) > 0) return "audio/ogg";
                if (packetSize >= 30 && payload + 30 <= count && prefix[payload] == 1
                        && prefixEquals(prefix, payload + 1, count, "vorbis")
                        && prefix[payload + 7] == 0 && prefix[payload + 8] == 0
                        && prefix[payload + 9] == 0 && prefix[payload + 10] == 0
                        && (prefix[payload + 11] & 255) > 0 && prefix[payload + 29] == 1) {
                    int smallBlock = prefix[payload + 28] & 15, largeBlock = (prefix[payload + 28] >> 4) & 15;
                    if (smallBlock >= 6 && largeBlock <= 13 && largeBlock >= smallBlock
                            && (prefix[payload + 12] != 0 || prefix[payload + 13] != 0
                            || prefix[payload + 14] != 0 || prefix[payload + 15] != 0)) return "audio/ogg";
                }
            }
        }
        if (count >= 16 && prefix[4] == 'f' && prefix[5] == 't' && prefix[6] == 'y' && prefix[7] == 'p') {
            long boxSize = ((prefix[0] & 255L) << 24) | ((prefix[1] & 255L) << 16) | ((prefix[2] & 255L) << 8) | (prefix[3] & 255L);
            if (boxSize >= 16) {
                String majorBrand = new String(prefix, 8, 4, StandardCharsets.US_ASCII);
                if (majorBrand.matches("(?:M4[ABPR] |F4[AB] )")) return "audio/mp4";
                if (majorBrand.matches("(?:avif|avis|heic|heix|hevc|hevx|mif1|msf1)")) return null;
                int limit = (int) Math.min(boxSize, count);
                for (int offset = 8; offset + 4 <= limit; offset += offset == 8 ? 8 : 4) {
                    String brand = new String(prefix, offset, 4, StandardCharsets.US_ASCII);
                    if (brand.matches("(?:isom|iso[2-9]|mp4[12]|avc1|hvc1|hev1|av01|dash|cmfc|cmfs)")) return "video/mp4";
                }
            }
        }
        if (count >= 11 && (prefix[0] & 255) == 0x1a && (prefix[1] & 255) == 0x45
                && (prefix[2] & 255) == 0xdf && (prefix[3] & 255) == 0xa3) {
            // WebM declares its DocType in the EBML header; the magic alone also matches Matroska.
            for (int offset = 4; offset + 7 <= count; offset++) {
                if ((prefix[offset] & 255) == 0x42 && (prefix[offset + 1] & 255) == 0x82
                        && (prefix[offset + 2] & 255) == 0x84 && prefix[offset + 3] == 'w'
                        && prefix[offset + 4] == 'e' && prefix[offset + 5] == 'b' && prefix[offset + 6] == 'm') return "video/webm";
            }
        }
        return null;
    }

    private static boolean prefixEquals(byte[] prefix, int offset, int count, String value) {
        if (offset < 0 || offset > count - value.length()) return false;
        for (int index = 0; index < value.length(); index++) {
            if ((prefix[offset + index] & 255) != value.charAt(index)) return false;
        }
        return true;
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
        int receivedStatus = 0;
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
            HttpURLConnection connection = gatewayNetwork.open(target);
            task.connection = connection;
            connection.setRequestMethod(method);
            connection.setConnectTimeout(25000);
            long requestedReadTimeout = options.optLong("readTimeoutMs", 600000L);
            connection.setReadTimeout((int) Math.max(1000L, Math.min(600000L, requestedReadTimeout)));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + secret);
            if (endpoint.contains("/messages")) connection.setRequestProperty("x-api-key", secret);
            if (endpoint.startsWith("/v1beta/")) connection.setRequestProperty("x-goog-api-key", secret);
            attachNasSession(connection, target);
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
            receivedStatus = status > 0 ? status : 0;
            if (canReferenceVideoContentRedirect(target, method, options, status, connection.getHeaderField("Location"))) {
                // The API bridge does not follow the redirect or send its key to a new origin.
                // Playback reopens this protected URL through the media proxy's per-hop checks.
                JSONObject media = new JSONObject().put("__remoteMedia", true).put("type", "video").put("url", target.toExternalForm());
                if (!task.cancelled) {
                    emit(id, "data", redact(media.toString(), secret), status, null);
                    emit(id, "done", null, status, null);
                }
                return;
            }
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (status < 200 || status >= 300) {
                String error = input == null ? "网关请求失败" : new String(readAll(input, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
                if (status >= 300 && status < 400) error = NasSessionPolicy.isNasGateway(config.baseUrl())
                        ? "NAS 要求登录，请在设置中点“登录 NAS”，完成 FN Connect 连接和飞牛登录后再刷新模型"
                        : "网关返回了重定向，请在设置中填写最终 HTTPS 网关地址";
                else if (NasSessionPolicy.isNasGateway(config.baseUrl()) && (status == 401 || status == 403))
                    error = "NAS 拒绝了请求，请检查 NAS API 访问密钥，并在设置中重新登录 NAS";
                emit(id, "error", redact(error, secret), status, null);
                return;
            }
            String mime = connection.getContentType();
            mime = mime == null ? "application/json" : mime.toLowerCase(Locale.ROOT);
            if (input != null && options.optBoolean("responseAsMedia", false)
                    && mime.split(";")[0].trim().equals("application/octet-stream")) {
                BufferedInputStream buffered = new BufferedInputStream(input);
                input = buffered;
                String detectedMime = sniffMediaMime(buffered);
                if (detectedMime != null) mime = detectedMime;
            }
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
                emit(id, "error", nativeRequestFailure(failure, secret), receivedStatus, null);
            }
        } finally {
            if (task.connection != null) task.connection.disconnect();
            active.remove(id, task);
        }
    }

    private static String nativeRequestFailure(Throwable failure, String secret) {
        Throwable selected = failure;
        int selectedPriority = 0;
        String code = "native_unknown", source = "unknown", fallback = "请求失败，请检查网关地址和手机连接";
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            int priority = 0;
            String causeCode = "native_unknown", causeSource = "unknown", causeFallback = fallback;
            if (cause instanceof java.net.SocketTimeoutException) {
                priority = 100; causeCode = "native_timeout"; causeSource = "network_timeout"; causeFallback = "网络读取或连接超时";
            } else if (cause instanceof java.net.UnknownHostException) {
                priority = 100; causeCode = "native_dns"; causeSource = "network_dns"; causeFallback = "无法解析网关域名";
            } else if (cause instanceof javax.net.ssl.SSLException) {
                priority = 120; causeCode = "native_tls"; causeSource = "network_tls"; causeFallback = "HTTPS 安全连接失败";
            } else if (cause instanceof java.net.ConnectException) {
                priority = 100; causeCode = "native_connect"; causeSource = "network_connect"; causeFallback = "无法连接网关";
            } else if (cause instanceof org.json.JSONException) {
                priority = 70; causeCode = "native_parse"; causeSource = "local_parse"; causeFallback = "请求或返回的数据无法解析";
            } else if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
                    || cause instanceof java.net.MalformedURLException || cause instanceof java.net.URISyntaxException) {
                priority = 60; causeCode = "native_validation"; causeSource = "local_validation"; causeFallback = "请求参数或本地状态无效";
            } else if (cause instanceof java.io.IOException) {
                priority = 50; causeCode = "native_io"; causeSource = "io"; causeFallback = "读取或保存请求数据失败";
            }
            if (priority >= selectedPriority) {
                selected = cause; selectedPriority = priority; code = causeCode; source = causeSource; fallback = causeFallback;
            }
            Throwable next = cause.getCause();
            if (next == cause) break;
            cause = next;
        }
        String message = selected == null ? null : selected.getMessage();
        if (message == null || message.trim().isEmpty()) message = fallback;
        if (selectedPriority >= 100) {
            // Socket/TLS exception messages can include resolved private addresses.
            // The stable code and source retain the network failure category.
            message = fallback;
        }
        if (selected instanceof org.json.JSONException) {
            // Android JSON exceptions can append the complete input or offending body value.
            int inputMarker = message.indexOf(" at character ");
            if (inputMarker >= 0) message = message.substring(0, inputMarker);
            else if (!message.matches("No value for [A-Za-z0-9_.-]{1,64}")) message = fallback;
        }
        message = redact(message, secret)
                .replaceAll("(?<![A-Za-z0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![A-Za-z0-9])", "[网络地址已隐藏]")
                .replaceAll("(?i)(https?://[^\\s?#]+)\\?[^\\s]*", "$1?[查询参数已隐藏]")
                .replaceAll("(?is)(\\b(?:header(?: name| value)?|[A-Za-z0-9-]+ value)\\s*[:=]).*", "$1 [已隐藏]")
                .replaceAll("[\\x00-\\x1F\\x7F]", " ").trim();
        if (message.length() > 1024) message = message.substring(0, 1024) + "…";
        try {
            return new JSONObject().put("error", new JSONObject().put("message", message).put("code", code).put("source", source)).toString();
        } catch (Exception ignored) {
            return "{\"error\":{\"message\":\"请求失败\",\"code\":\"native_unknown\",\"source\":\"unknown\"}}";
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
                        connection = gatewayNetwork.open(target);
                        connection.setConnectTimeout(20000);
                        connection.setReadTimeout(60000);
                        connection.setInstanceFollowRedirects(false);
                        attachNasSession(connection, target);
                        // Public image and CDN paths never receive gateway authentication or cookies.
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
                        if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("图片不能超过 64 MB");
                        if (mime.equals("application/octet-stream")) {
                            BufferedInputStream buffered = new BufferedInputStream(connection.getInputStream());
                            input = buffered;
                            mime = sniffMediaMime(buffered);
                        } else input = connection.getInputStream();
                        if (mime == null || !mime.startsWith("image/") || mime.contains("svg")) throw new IllegalArgumentException("该链接不是可保存的图片");
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

        @JavascriptInterface public String loadMemory() {
            try { return sanitizeMemoryJson(memory.load()); }
            catch (Exception failure) { return MemoryStore.emptyWithError("综合记忆无法安全读取，请重新填写网关密钥后重试"); }
            catch (OutOfMemoryError exhausted) { return MemoryStore.RESOURCE_LOAD_ERROR_JSON; }
        }

        @JavascriptInterface public String saveMemory(String json) {
            try { return memory.save(sanitizeMemoryJson(json)); }
            catch (IllegalArgumentException invalid) { return MemoryStore.error(invalid.getMessage()); }
            catch (Exception failure) { return MemoryStore.error("无法读取网关密钥，综合记忆未保存，请重新填写密钥后重试"); }
            catch (OutOfMemoryError exhausted) { return MemoryStore.RESOURCE_ERROR_JSON; }
        }

        @JavascriptInterface public String sanitizeMemoryText(String text) {
            try { return redact(text == null ? "" : text, config.apiKey()); }
            catch (Exception failure) { return "[综合记忆无法安全读取，请重新填写网关密钥]"; }
            catch (OutOfMemoryError exhausted) { return "[综合记忆处理超出可用内存，请整理记忆后重试]"; }
        }

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

        @JavascriptInterface public void loginNas() {
            runOnUiThread(() -> {
                String base = config.baseUrl();
                String login = config.nasLoginUrl();
                if (!NasSessionPolicy.allowedLoginUrl(base, login)) {
                    emit("nas-login", "error", "请先保存包含 /app/leihuo 的 NAS HTTPS 地址和有效登录地址", 0, null);
                    return;
                }
                Intent intent = new Intent(MainActivity.this, NasLoginActivity.class);
                intent.putExtra("gateway", base);
                intent.putExtra("loginUrl", login);
                startActivityForResult(intent, NAS_LOGIN);
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
            try {
                new JSONObject(json);
                beginJsonExport(json, "雷火聊天记录.json");
            } catch (Exception failure) { emit("export", "error", "无法导出聊天记录", 0, null); }
        }

        @JavascriptInterface public void exportMemory(String json) {
            try {
                beginJsonExport(sanitizeMemoryJson(json), "雷火综合记忆.json");
            } catch (Exception failure) {
                emit("export", "error", failure instanceof IllegalArgumentException ? failure.getMessage() : "无法安全导出综合记忆，请重新填写网关密钥后重试", 0, null);
            } catch (OutOfMemoryError exhausted) {
                emitExportResourceError();
            }
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

    private String sanitizeMemoryJson(String json) throws Exception {
        String validated = MemoryStore.validatedJson(json);
        String key = config.apiKey();
        if (key.isEmpty()) return validated;
        JSONObject sanitized = (JSONObject) redactMemoryValue(new JSONObject(validated), key);
        return MemoryStore.validatedJson(sanitized.toString());
    }

    private static Object redactMemoryValue(Object value, String key) throws Exception {
        if (value instanceof String) return redact((String) value, key);
        if (value instanceof JSONArray) {
            JSONArray sanitized = new JSONArray();
            JSONArray original = (JSONArray) value;
            for (int index = 0; index < original.length(); index++) {
                sanitized.put(redactMemoryValue(original.get(index), key));
            }
            return sanitized;
        }
        if (value instanceof JSONObject) {
            JSONObject sanitized = new JSONObject();
            JSONObject original = (JSONObject) value;
            Iterator<String> keys = original.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                String safeName = redact(name, key);
                if (sanitized.has(safeName)) throw new IllegalArgumentException("综合记忆脱敏后存在重复字段，请整理后重试");
                sanitized.put(safeName, redactMemoryValue(original.get(name), key));
            }
            return sanitized;
        }
        return value;
    }

    private void beginJsonExport(String json, String title) {
        runOnUiThread(() -> {
            if (pendingExport != null) {
                emit("export", "error", "已有导出正在进行，请先完成或取消", 0, null);
                return;
            }
            try {
                pendingExport = json;
                pendingExportTitle = title;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, title);
                startActivityForResult(intent, EXPORT_CHATS);
            } catch (Exception failure) {
                pendingExport = null;
                pendingExportTitle = null;
                emit("export", "error", "无法打开导出文件选择器", 0, null);
            } catch (OutOfMemoryError exhausted) {
                pendingExport = null;
                pendingExportTitle = null;
                emitExportResourceError();
            }
        });
    }

    private void emitExportResourceError() {
        // A fixed script avoids allocating another JSON tree under memory pressure.
        final String script = "window.__nativeEvent && window.__nativeEvent({\"id\":\"export\",\"type\":\"error\",\"data\":\"导出超出可用内存，请先整理数据后重试\"});";
        try {
            runOnUiThread(() -> {
                try {
                    if (!isFinishing() && !isDestroyed()) webView.evaluateJavascript(script, null);
                } catch (OutOfMemoryError exhausted) { /* Preserve the process if the WebView cannot display the error. */ }
            });
        }
        catch (OutOfMemoryError exhausted) { /* Do not crash the bridge while reporting memory pressure. */ }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == NAS_LOGIN) {
            if (resultCode == RESULT_OK) emit("nas-login", "data", "NAS 登录页面已关闭，正在重新读取模型目录", 0, null);
            else emit("nas-login", "done", null, 0, null);
        } else if (requestCode == INTERFACE_SYNC) {
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
            String title = pendingExportTitle;
            pendingExport = null;
            pendingExportTitle = null;
            if (resultCode == RESULT_OK && intent != null && intent.getData() != null && content != null) {
                Uri uri = intent.getData();
                requests.submit(() -> {
                    try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IllegalStateException();
                        output.write(content.getBytes(StandardCharsets.UTF_8));
                        emit("export", "done", null, 0, null);
                    } catch (Exception failure) { emit("export", "error", (title == null ? "文件" : title) + "导出失败，请重试", 0, null); }
                    catch (OutOfMemoryError exhausted) { emitExportResourceError(); }
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
