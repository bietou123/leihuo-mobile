package com.leihuo.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded, credential-free DNS recovery for the official gateway only. */
public final class GatewayDns implements Dns {
    private static final String GATEWAY_HOST = "ai.leihuo.netease.com";
    private static final String[] RESOLVERS = {
            "https://1.1.1.1/dns-query?name=" + GATEWAY_HOST + "&type=A",
            "https://8.8.8.8/resolve?name=" + GATEWAY_HOST + "&type=A"
    };
    private static final long SYSTEM_TTL_SECONDS = 60;
    private static final long MAX_TTL_SECONDS = 15 * 60;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final int MAX_ADDRESSES = 32;

    private final SharedPreferences cache;
    private final OkHttpClient resolverClient;

    public GatewayDns(Context context) {
        cache = context.getApplicationContext().getSharedPreferences("gateway_dns", Context.MODE_PRIVATE);
        // This client never inherits API headers, cookies, credentials or gateway DNS.
        // IP bootstrap still uses the platform's default TLS and IP certificate checks.
        resolverClient = new OkHttpClient.Builder()
                .dns(Dns.SYSTEM)
                .callTimeout(2, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .addNetworkInterceptor(chain -> {
                    ResolverAttempt attempt = chain.request().tag(ResolverAttempt.class);
                    if (attempt == null || !attempt.sent.compareAndSet(false, true)) {
                        throw new IOException("DNS request followup blocked");
                    }
                    return chain.proceed(chain.request());
                })
                .build();
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (hostname == null || hostname.isEmpty()) throw new UnknownHostException("Empty hostname");
        UnknownHostException systemFailure;
        try {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            if (addresses.isEmpty()) throw new UnknownHostException("No system DNS addresses");
            if (GATEWAY_HOST.equalsIgnoreCase(hostname)) saveCache(addresses, SYSTEM_TTL_SECONDS);
            return addresses;
        } catch (UnknownHostException failure) {
            systemFailure = failure;
        }
        // Custom gateways and public media hosts retain their system DNS behavior.
        if (!GATEWAY_HOST.equalsIgnoreCase(hostname)) throw systemFailure;

        List<InetAddress> saved = readCache();
        if (!saved.isEmpty()) return saved;
        // Do not serialize network recovery: concurrent failed lookups must not queue
        // for unbounded multiples of the resolver timeout.
        for (String resolver : RESOLVERS) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                ResolvedAddresses resolved = queryResolver(resolver);
                saveCache(resolved.addresses, resolved.ttlSeconds);
                return resolved.addresses;
            } catch (IOException | JSONException | IllegalArgumentException ignored) {
                // Never log returned addresses or forward API authentication to DNS.
            }
        }
        UnknownHostException unavailable = new UnknownHostException("Unable to resolve host " + GATEWAY_HOST);
        unavailable.initCause(systemFailure);
        throw unavailable;
    }

    private ResolvedAddresses queryResolver(String endpoint) throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(endpoint)
                .header("Accept", "application/dns-json")
                .header("Accept-Encoding", "identity")
                .tag(ResolverAttempt.class, new ResolverAttempt())
                .get()
                .build();
        try (Response response = resolverClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
                throw new IOException("DNS response unavailable");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = body.byteStream()) {
                byte[] chunk = new byte[1024];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) throw new IOException("DNS response too large");
                    bytes.write(chunk, 0, count);
                }
            }
            JSONObject json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (json.optInt("Status", -1) != 0) throw new IOException("DNS lookup failed");
            JSONArray answers = json.optJSONArray("Answer");
            if (answers == null || answers.length() > 128) throw new IOException("No valid DNS answer");

            Set<String> acceptedNames = new HashSet<>();
            acceptedNames.add(GATEWAY_HOST);
            long ttlSeconds = MAX_TTL_SECONDS;
            // CNAMEs may legitimately lead to A records under a different DNS name.
            for (int pass = 0; pass < answers.length(); pass++) {
                boolean changed = false;
                for (int index = 0; index < answers.length(); index++) {
                    JSONObject answer = answers.optJSONObject(index);
                    if (answer == null || answer.optInt("type", -1) != 5
                            || !acceptedNames.contains(normalizeName(answer.optString("name")))) continue;
                    String canonical = normalizeName(answer.optString("data"));
                    if (!isDnsName(canonical)) continue;
                    ttlSeconds = Math.min(ttlSeconds, boundedTtl(answer));
                    changed |= acceptedNames.add(canonical);
                }
                if (!changed) break;
            }
            List<InetAddress> addresses = new ArrayList<>();
            for (int index = 0; index < answers.length(); index++) {
                JSONObject answer = answers.optJSONObject(index);
                if (answer == null || answer.optInt("type", -1) != 1
                        || !acceptedNames.contains(normalizeName(answer.optString("name")))) continue;
                byte[] address = parseIpv4(answer.optString("data"));
                if (address == null) continue;
                // Private addresses are valid: the gateway can require the user's VPN.
                InetAddress resolved = InetAddress.getByAddress(GATEWAY_HOST, address);
                if (!addresses.contains(resolved)) addresses.add(resolved);
                ttlSeconds = Math.min(ttlSeconds, boundedTtl(answer));
                if (addresses.size() >= MAX_ADDRESSES) break;
            }
            if (addresses.isEmpty()) throw new IOException("No valid DNS addresses");
            return new ResolvedAddresses(addresses, ttlSeconds);
        }
    }

    private List<InetAddress> readCache() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            long savedAt = cache.getLong("saved_at", 0);
            long expiresAt = cache.getLong("expires_at", 0);
            if (savedAt <= 0 || savedAt > now || expiresAt <= now
                    || expiresAt - savedAt > MAX_TTL_SECONDS * 1000) return addresses;
            String encoded = cache.getString("addresses", "[]");
            if (encoded == null || encoded.length() > 4096) return addresses;
            JSONArray items = new JSONArray(encoded);
            if (items.length() > MAX_ADDRESSES) return addresses;
            for (int index = 0; index < items.length(); index++) {
                byte[] address = Base64.decode(items.getString(index), Base64.NO_WRAP);
                if (address.length != 4 && address.length != 16) return new ArrayList<>();
                addresses.add(InetAddress.getByAddress(GATEWAY_HOST, address));
            }
        } catch (JSONException | IllegalArgumentException | ClassCastException | UnknownHostException ignored) {
            addresses.clear();
        }
        return addresses;
    }

    private void saveCache(List<InetAddress> addresses, long ttlSeconds) {
        ttlSeconds = Math.min(MAX_TTL_SECONDS, ttlSeconds);
        if (ttlSeconds <= 0) {
            // A zero TTL may be used now, but must not leave an older cached answer.
            cache.edit().clear().apply();
            return;
        }
        JSONArray encoded = new JSONArray();
        for (InetAddress address : addresses) {
            if (encoded.length() >= MAX_ADDRESSES) break;
            byte[] raw = address.getAddress();
            if (raw.length == 4 || raw.length == 16) encoded.put(Base64.encodeToString(raw, Base64.NO_WRAP));
        }
        if (encoded.length() == 0) return;
        long now = System.currentTimeMillis();
        cache.edit().putString("addresses", encoded.toString()).putLong("saved_at", now)
                .putLong("expires_at", now + ttlSeconds * 1000).apply();
    }

    private static long boundedTtl(JSONObject answer) {
        return Math.max(0, Math.min(MAX_TTL_SECONDS, answer.optLong("TTL", 0)));
    }

    private static String normalizeName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static boolean isDnsName(String name) {
        if (name.isEmpty() || name.length() > 253) return false;
        for (String label : name.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
        }
        return true;
    }

    private static byte[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        for (int index = 0; index < 4; index++) {
            String part = parts[index];
            if (!part.matches("[0-9]{1,3}") || (part.length() > 1 && part.charAt(0) == '0')) return null;
            int value = Integer.parseInt(part);
            if (value > 255) return null;
            address[index] = (byte) value;
        }
        return address;
    }

    private static final class ResolvedAddresses {
        final List<InetAddress> addresses;
        final long ttlSeconds;

        ResolvedAddresses(List<InetAddress> addresses, long ttlSeconds) {
            this.addresses = addresses;
            this.ttlSeconds = ttlSeconds;
        }
    }

    private static final class ResolverAttempt {
        final AtomicBoolean sent = new AtomicBoolean();
    }
}
