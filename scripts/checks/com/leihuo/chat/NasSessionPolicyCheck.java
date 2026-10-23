package com.leihuo.chat;

import java.net.URL;

public final class NasSessionPolicyCheck {
    private static final String NAS = "https://nas.example/app/leihuo";
    private static int checks;

    private static void expect(boolean expected, boolean actual, String name) {
        checks++;
        if (actual != expected) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        expect(true, NasSessionPolicy.isNasGateway(NAS), "NAS app root");
        expect(false, NasSessionPolicy.isNasGateway("https://ai.leihuo.netease.com"), "Official gateway has no NAS session");
        expect(false, NasSessionPolicy.isNasGateway(NAS + "/other"), "NAS scope is exact");
        expect(false, NasSessionPolicy.isNasGateway("http://nas.example/app/leihuo"), "HTTPS required");
        expect(false, NasSessionPolicy.isNasGateway("https://user@nas.example/app/leihuo"), "No embedded credentials");
        expect(true, "https://nas.example/".equals(NasSessionPolicy.defaultLoginUrl(NAS)), "Default login root");

        for (String route : new String[] { "/app/leihuo/v1/models", "/app/leihuo/v1/chat/completions", "/app/leihuo/v1beta/models/gemini:generateContent", "/app/leihuo/v1/videos/task_01/content" }) {
            expect(true, NasSessionPolicy.shouldAttach(NAS, new URL("https://nas.example" + route)), "Allowed application API");
            expect(true, NasSessionPolicy.shouldAttach(NAS, new URL("https://NAS.EXAMPLE:443" + route)), "Equivalent HTTPS origin");
        }
        for (String url : new String[] {
                "http://nas.example/app/leihuo/v1/models",
                "https://nas.example:8443/app/leihuo/v1/models",
                "https://cdn.example/app/leihuo/v1/models",
                "https://child.nas.example/app/leihuo/v1/models",
                "https://nas.example.evil/app/leihuo/v1/models",
                "https://user@nas.example/app/leihuo/v1/models",
                "https://nas.example/v1/models",
                "https://nas.example/files/video.mp4",
                "https://nas.example/app/leihuo2/v1/models",
                "https://nas.example/app/leihuo/v1evil/models",
                "https://nas.example/app/leihuo/v1beta/other",
                "https://nas.example/app/leihuo/v1/../private",
                "https://nas.example/app/leihuo/v1/%2e%2e/private",
                "https://nas.example/app/leihuo/v1/%252e%252e/private",
                "https://nas.example/app/leihuo/v1/x%2Fy",
                "https://nas.example/app/leihuo/v1/x%5cy",
                "https://nas.example/app/leihuo/v1/%0d%0aCookie:evil"
        }) expect(false, NasSessionPolicy.shouldAttach(NAS, new URL(url)), "Rejected cookie boundary");

        expect(true, NasSessionPolicy.isVideoContent("https://ai.leihuo.netease.com", new URL("https://ai.leihuo.netease.com/v1/videos/task_01/content")), "Official video route preserved");
        expect(true, NasSessionPolicy.isVideoContent(NAS, new URL(NAS + "/v1/videos/task_01/content")), "NAS video route");
        expect(false, NasSessionPolicy.isVideoContent(NAS, new URL("https://cdn.example/app/leihuo/v1/videos/task_01/content")), "Video origin protected");
        expect(false, NasSessionPolicy.isVideoContent(NAS, new URL(NAS + "/v1/videos/a%2fb/content")), "Encoded video separator rejected");
        expect(false, NasSessionPolicy.isVideoContent(NAS, new URL(NAS + "/v1/videos/../content")), "Video traversal rejected");

        expect(true, NasSessionPolicy.allowedLoginUrl(NAS, "https://nas.example/login"), "Same NAS login");
        expect(true, NasSessionPolicy.allowedLoginUrl(NAS, "https://www.fnos.net/"), "FN Connect login");
        expect(false, NasSessionPolicy.allowedLoginUrl(NAS, "https://fnos.net/?token=transient"), "Do not persist query credentials");
        expect(false, NasSessionPolicy.allowedLoginUrl(NAS, "https://fnos.net/#token"), "Do not persist fragment credentials");
        expect(true, NasSessionPolicy.allowedNavigation(NAS, "https://fnos.net/login?state=temporary"), "FN Connect navigation may contain transient state");
        expect(true, NasSessionPolicy.allowedNavigation(NAS, "https://nas.example/#/login"), "NAS SPA navigation");
        for (String url : new String[] { "http://nas.example/", "https://fnos.net.evil/", "https://fnos.net:8443/", "https://evil.example/", "https://user@fnos.net/", "file:///tmp/login" })
            expect(false, NasSessionPolicy.allowedNavigation(NAS, url), "Restricted login navigation");
        System.out.println("NAS session boundary checks passed: " + checks);
    }
}
