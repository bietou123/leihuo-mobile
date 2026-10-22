package com.leihuo.chat;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/** Keeps existing bridge requests on their original HTTPS URL, including Host/SNI. */
public final class GatewayNetwork {
    private final OkHttpClient client;

    public GatewayNetwork(Context context) {
        client = new OkHttpClient.Builder()
                .dns(new GatewayDns(context))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .build();
    }

    public HttpURLConnection open(URL target) throws IOException {
        if (!"https".equalsIgnoreCase(target.getProtocol()) || target.getUserInfo() != null) {
            throw new ProtocolException("Requests require an HTTPS URL without embedded credentials");
        }
        return new Connection(target, client);
    }

    private static final class Connection extends HttpURLConnection {
        private final OkHttpClient client;
        private final Object stateLock = new Object();
        private final Headers.Builder requestHeaders = new Headers.Builder();
        private final ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
        private OutputStream requestOutput;
        private boolean outputClosed;
        private boolean requestStarted;
        private boolean disconnected;
        private Call activeCall;
        private Response response;
        private IOException executionFailure;

        Connection(URL target, OkHttpClient client) {
            super(target);
            this.client = client;
            super.setInstanceFollowRedirects(false);
        }

        @Override
        public void setRequestMethod(String method) throws ProtocolException {
            synchronized (stateLock) {
                if (requestStarted || disconnected) throw new ProtocolException("Request already started");
                if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)
                        && !"PUT".equals(method) && !"PATCH".equals(method) && !"DELETE".equals(method)
                        && !"OPTIONS".equals(method) && !"TRACE".equals(method)) {
                    throw new ProtocolException("Unsupported HTTP method");
                }
                this.method = method;
            }
        }

        @Override
        public void setRequestProperty(String name, String value) {
            synchronized (stateLock) {
                requireMutable();
                requestHeaders.set(name, value);
            }
        }

        @Override
        public void addRequestProperty(String name, String value) {
            synchronized (stateLock) {
                requireMutable();
                requestHeaders.add(name, value);
            }
        }

        @Override
        public String getRequestProperty(String name) {
            synchronized (stateLock) {
                requireMutable();
                return requestHeaders.get(name);
            }
        }

        @Override
        public Map<String, List<String>> getRequestProperties() {
            synchronized (stateLock) {
                requireMutable();
                return immutableHeaders(requestHeaders.build(), null);
            }
        }

        @Override
        public void setConnectTimeout(int timeout) {
            synchronized (stateLock) {
                requireMutable();
                super.setConnectTimeout(timeout);
            }
        }

        @Override
        public void setReadTimeout(int timeout) {
            synchronized (stateLock) {
                requireMutable();
                super.setReadTimeout(timeout);
            }
        }

        @Override
        public void setDoOutput(boolean enabled) {
            synchronized (stateLock) {
                requireMutable();
                super.setDoOutput(enabled);
            }
        }

        @Override
        public void setFixedLengthStreamingMode(int length) {
            synchronized (stateLock) {
                requireMutable();
                super.setFixedLengthStreamingMode(length);
            }
        }

        @Override
        public void setFixedLengthStreamingMode(long length) {
            synchronized (stateLock) {
                requireMutable();
                super.setFixedLengthStreamingMode(length);
            }
        }

        @Override
        public void setInstanceFollowRedirects(boolean enabled) {
            synchronized (stateLock) {
                requireMutable();
                if (enabled) throw new IllegalArgumentException("Redirects require an explicit new request");
                super.setInstanceFollowRedirects(false);
            }
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            synchronized (stateLock) {
                if (requestStarted || disconnected) throw new IOException("Request already started or cancelled");
                if (!doOutput) throw new ProtocolException("Output is disabled");
                if ("GET".equals(method) || "HEAD".equals(method)) throw new ProtocolException("This HTTP method cannot contain a body");
                if (requestOutput == null) {
                    requestOutput = new OutputStream() {
                        @Override public void write(int value) throws IOException {
                            byte[] one = {(byte) value};
                            write(one, 0, 1);
                        }

                        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                            if (bytes == null) throw new NullPointerException("bytes");
                            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
                            synchronized (stateLock) {
                                if (outputClosed || requestStarted || disconnected) throw new IOException("Request output closed");
                                long declared = declaredLength();
                                if (declared >= 0 && (long) requestBytes.size() + length > declared) {
                                    throw new ProtocolException("Request body exceeds declared length");
                                }
                                requestBytes.write(bytes, offset, length);
                            }
                        }

                        @Override public void close() {
                            synchronized (stateLock) { outputClosed = true; }
                        }
                    };
                }
                return requestOutput;
            }
        }

        @Override
        public void connect() throws IOException {
            execute();
        }

        @Override
        public int getResponseCode() throws IOException {
            return execute().code();
        }

        @Override
        public String getResponseMessage() throws IOException {
            return execute().message();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            Response current = execute();
            if (current.code() >= 400) throw new FileNotFoundException(url.toExternalForm());
            return responseStream(current);
        }

        @Override
        public InputStream getErrorStream() {
            Response current = responseOrNull();
            return current != null && current.code() >= 400 ? responseStream(current) : null;
        }

        @Override
        public String getHeaderField(String name) {
            Response current = responseOrNull();
            if (current == null) return null;
            return name == null ? statusLine(current) : current.header(name);
        }

        @Override
        public String getHeaderField(int index) {
            Response current = responseOrNull();
            if (current == null || index < 0) return null;
            if (index == 0) return statusLine(current);
            return index <= current.headers().size() ? current.headers().value(index - 1) : null;
        }

        @Override
        public String getHeaderFieldKey(int index) {
            Response current = responseOrNull();
            if (current == null || index <= 0 || index > current.headers().size()) return null;
            return current.headers().name(index - 1);
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            Response current = responseOrNull();
            return current == null ? Collections.emptyMap() : immutableHeaders(current.headers(), statusLine(current));
        }

        @Override
        public String getContentType() {
            return getHeaderField("Content-Type");
        }

        @Override
        public long getContentLengthLong() {
            return getHeaderFieldLong("Content-Length", -1);
        }

        @Override
        public int getContentLength() {
            long length = getContentLengthLong();
            return length > Integer.MAX_VALUE ? -1 : (int) length;
        }

        @Override
        public void disconnect() {
            Call call;
            Response current;
            synchronized (stateLock) {
                disconnected = true;
                call = activeCall;
                current = response;
                stateLock.notifyAll();
            }
            // Neither cancellation nor response closure waits for the execute() lock.
            if (call != null) call.cancel();
            if (current != null) current.close();
        }

        @Override
        public boolean usingProxy() {
            Proxy proxy = client.proxy();
            return proxy != null && proxy.type() != Proxy.Type.DIRECT;
        }

        private Response execute() throws IOException {
            Call call;
            synchronized (stateLock) {
                while (requestStarted && response == null && executionFailure == null && !disconnected) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Interrupted while awaiting response");
                    }
                }
                if (disconnected) throw new IOException("Request cancelled");
                if (response != null) return response;
                if (executionFailure != null) throw executionFailure;
                requestStarted = true;
                connected = true;
                try {
                    Request request = buildRequest();
                    OkHttpClient configured = client.newBuilder()
                            .connectTimeout(getConnectTimeout(), TimeUnit.MILLISECONDS)
                            .readTimeout(getReadTimeout(), TimeUnit.MILLISECONDS)
                            .writeTimeout(getReadTimeout(), TimeUnit.MILLISECONDS)
                            .build();
                    call = configured.newCall(request);
                    activeCall = call;
                } catch (IOException | IllegalArgumentException failure) {
                    executionFailure = failure instanceof IOException ? (IOException) failure : new IOException("Invalid HTTP request", failure);
                    stateLock.notifyAll();
                    throw executionFailure;
                }
            }
            Response incoming;
            try {
                // Do not hold stateLock here: another thread must be able to cancel.
                incoming = call.execute();
            } catch (IOException failure) {
                synchronized (stateLock) {
                    executionFailure = failure;
                    stateLock.notifyAll();
                }
                throw failure; // Preserve TLS/network exception types for bridge diagnosis.
            } catch (RuntimeException failure) {
                IOException wrapped = new IOException("HTTP request failed", failure);
                synchronized (stateLock) {
                    executionFailure = wrapped;
                    stateLock.notifyAll();
                }
                throw wrapped;
            }
            synchronized (stateLock) {
                if (!disconnected) {
                    response = incoming;
                    responseCode = incoming.code();
                    responseMessage = incoming.message();
                    stateLock.notifyAll();
                    return incoming;
                }
                stateLock.notifyAll();
            }
            incoming.close();
            throw new IOException("Request cancelled");
        }

        private Request buildRequest() throws IOException {
            boolean prohibitsBody = "GET".equals(method) || "HEAD".equals(method);
            if (prohibitsBody && (doOutput || requestBytes.size() > 0)) throw new ProtocolException("This HTTP method cannot contain a body");
            long declared = declaredLength();
            if (declared >= 0 && declared != requestBytes.size()) throw new ProtocolException("Request body length differs from declared length");
            boolean requiresBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
            RequestBody body = null;
            if (!prohibitsBody && (requiresBody || doOutput || requestBytes.size() > 0)) {
                String contentType = requestHeaders.get("Content-Type");
                MediaType type = contentType == null ? null : MediaType.parse(contentType);
                byte[] payload = requestBytes.toByteArray();
                body = new RequestBody() {
                    @Override public MediaType contentType() { return type; }
                    @Override public long contentLength() { return payload.length; }
                    // Also block HTTP 503/421 followups from replaying a generation.
                    @Override public boolean isOneShot() { return true; }
                    @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(payload); }
                };
                requestBytes.reset();
            }
            return new Request.Builder().url(url).headers(requestHeaders.build()).method(method, body).build();
        }

        private long declaredLength() {
            return fixedContentLengthLong >= 0 ? fixedContentLengthLong : fixedContentLength;
        }

        private void requireMutable() {
            if (requestStarted || disconnected) throw new IllegalStateException("Request already started or cancelled");
        }

        private Response responseOrNull() {
            try { return execute(); }
            catch (IOException ignored) { return null; }
        }

        private static InputStream responseStream(Response current) {
            ResponseBody body = current.body();
            return body == null ? new ByteArrayInputStream(new byte[0]) : body.byteStream();
        }

        private static String statusLine(Response current) {
            String protocol = current.protocol() == Protocol.HTTP_1_0 ? "HTTP/1.0"
                    : current.protocol() == Protocol.HTTP_1_1 ? "HTTP/1.1" : "HTTP/2";
            return protocol + " " + current.code() + (current.message().isEmpty() ? "" : " " + current.message());
        }

        private static Map<String, List<String>> immutableHeaders(Headers headers, String status) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            if (status != null) values.put(null, Collections.singletonList(status));
            for (Map.Entry<String, List<String>> entry : headers.toMultimap().entrySet()) {
                values.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(values);
        }
    }
}
