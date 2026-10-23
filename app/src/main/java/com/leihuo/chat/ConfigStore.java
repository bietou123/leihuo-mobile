package com.leihuo.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.json.JSONObject;

final class ConfigStore {
    static final String DEFAULT_BASE_URL = "https://ai.leihuo.netease.com";
    private static final String KEY_ALIAS = "leihuo.gateway.key.v1";
    private final SharedPreferences prefs;

    ConfigStore(Context context) {
        prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE);
    }

    synchronized String baseUrl() {
        return prefs.getString("baseUrl", DEFAULT_BASE_URL);
    }

    synchronized JSONObject publicConfig() throws Exception {
        return new JSONObject().put("baseUrl", baseUrl()).put("hasKey", !apiKey().isEmpty())
                .put("nasSessionEnabled", NasSessionPolicy.isNasGateway(baseUrl())).put("nasLoginUrl", nasLoginUrl());
    }

    synchronized String nasLoginUrl() {
        String saved = prefs.getString("nasLoginUrl", "");
        return NasSessionPolicy.allowedLoginUrl(baseUrl(), saved) ? saved : NasSessionPolicy.defaultLoginUrl(baseUrl());
    }

    synchronized String apiKey() throws Exception {
        String value = prefs.getString("encryptedKey", "");
        String iv = prefs.getString("keyIv", "");
        if (value.isEmpty() || iv.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    synchronized JSONObject save(JSONObject input) throws Exception {
        String rawBase = input.optString("baseUrl", baseUrl()).trim();
        while (rawBase.endsWith("/")) rawBase = rawBase.substring(0, rawBase.length() - 1);
        // The mobile client sends complete API paths, so the saved value is the gateway root.
        if (rawBase.endsWith("/v1")) rawBase = rawBase.substring(0, rawBase.length() - 3);
        URI uri = new URI(rawBase);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("请输入有效的 HTTPS 网关地址");
        }
        SharedPreferences.Editor edit = prefs.edit().putString("baseUrl", rawBase);
        if (input.has("nasLoginUrl")) {
            String login = input.optString("nasLoginUrl").trim();
            if (login.isEmpty()) edit.remove("nasLoginUrl");
            else if (NasSessionPolicy.allowedLoginUrl(rawBase, login)) edit.putString("nasLoginUrl", login);
            else throw new IllegalArgumentException("NAS 登录地址必须是当前 NAS 或 fnos.net 的 HTTPS 地址，不能包含账号、查询参数或片段");
        }
        if (input.optBoolean("clearKey", false)) {
            edit.remove("encryptedKey").remove("keyIv");
        } else if (input.has("apiKey") && !input.optString("apiKey").trim().isEmpty()) {
            String secret = input.getString("apiKey").trim();
            if (secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("密钥不能包含换行");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            edit.putString("encryptedKey", Base64.encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
            edit.putString("keyIv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        }
        if (!edit.commit()) throw new IllegalStateException("无法保存网关设置");
        return publicConfig();
    }

    private SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
