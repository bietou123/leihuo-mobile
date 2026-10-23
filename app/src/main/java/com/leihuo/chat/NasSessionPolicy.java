package com.leihuo.chat;

import java.net.URI;
import java.net.URL;
import java.util.Locale;

/** Pure URL checks shared by NAS login and explicitly scoped session requests. */
final class NasSessionPolicy {
    private NasSessionPolicy() {}

    static boolean isNasGateway(String base) {
        try {
            URI uri = secureUri(base);
            return uri != null && "/app/leihuo".equals(uri.getRawPath())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (Exception invalid) { return false; }
    }

    static String defaultLoginUrl(String base) {
        try {
            URI uri = secureUri(base);
            if (uri == null || !isNasGateway(base)) return "";
            return new URI("https", null, uri.getHost(), uri.getPort(), "/", null, null).toString();
        } catch (Exception invalid) { return ""; }
    }

    static boolean allowedLoginUrl(String base, String login) {
        try {
            URI target = secureUri(login);
            return isNasGateway(base) && target != null && target.getRawQuery() == null
                    && target.getRawFragment() == null && (sameOrigin(secureUri(base), target) || fnosOrigin(target));
        } catch (Exception invalid) { return false; }
    }

    static boolean allowedNavigation(String base, String target) {
        try {
            URI uri = secureUri(target);
            return isNasGateway(base) && uri != null && (sameOrigin(secureUri(base), uri) || fnosOrigin(uri));
        } catch (Exception invalid) { return false; }
    }

    static boolean shouldAttach(String base, URL target) {
        try {
            if (!isNasGateway(base)) return false;
            URI root = secureUri(base), uri = secureUri(target.toExternalForm());
            if (uri == null || !sameOrigin(root, uri)) return false;
            String rawPath = uri.getRawPath();
            if (!safePath(rawPath)) return false;
            return rawPath.startsWith(root.getRawPath() + "/v1/")
                    || rawPath.startsWith(root.getRawPath() + "/v1beta/models/");
        } catch (Exception invalid) { return false; }
    }

    static boolean isVideoContent(String base, URL target) {
        try {
            URI root = secureUri(base), uri = secureUri(target.toExternalForm());
            if (root == null || uri == null || !sameOrigin(root, uri)) return false;
            String prefix = root.getRawPath() == null ? "" : root.getRawPath().replaceAll("/+$", "");
            String rawPath = uri.getRawPath();
            if (!safePath(rawPath) || !rawPath.startsWith(prefix + "/v1/videos/")) return false;
            String remainder = rawPath.substring(prefix.length());
            return remainder.matches("/v1/videos/[A-Za-z0-9._~-]+/content");
        } catch (Exception invalid) { return false; }
    }

    private static URI secureUri(String raw) throws Exception {
        if (raw == null || raw.length() > 4096 || raw.indexOf('\\') >= 0 || raw.matches("(?s).*[\\x00-\\x20\\x7F].*")) return null;
        URI uri = new URI(raw);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null ? uri : null;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first != null && second != null && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

    private static boolean fnosOrigin(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return effectivePort(uri) == 443 && (host.equals("fnos.net") || host.endsWith(".fnos.net"));
    }

    private static boolean safePath(String rawPath) {
        if (rawPath == null || rawPath.contains("..") || rawPath.contains("\\")) return false;
        String lower = rawPath.toLowerCase(Locale.ROOT);
        // Refuse encoded separators, traversal and nested encoding before session attachment.
        return !lower.matches(".*%(?:2e|2f|5c|25|0[0-9a-f]|1[0-9a-f]|20|7f).*" );
    }
}
