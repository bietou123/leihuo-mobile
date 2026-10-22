package com.leihuo.chat;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class ChatStore {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final AtomicFile storage;

    ChatStore(Context context) {
        storage = new AtomicFile(new File(context.getFilesDir(), "chats.json"));
    }

    synchronized String load() {
        if (!storage.getBaseFile().exists()) return "[]";
        try (FileInputStream input = storage.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IllegalArgumentException();
                output.write(buffer, 0, count);
            }
            String value = output.toString(StandardCharsets.UTF_8.name());
            new JSONArray(value);
            return value;
        } catch (Exception failure) {
            return "[]";
        }
    }

    synchronized String save(String value) {
        FileOutputStream output = null;
        try {
            if (value == null) throw new IllegalArgumentException();
            if (value.length() > MAX_BYTES) return "{\"ok\":false,\"error\":\"聊天记录超过 32 MB，请先导出并清理旧记录\"}";
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) return "{\"ok\":false,\"error\":\"聊天记录超过 32 MB，请先导出并清理旧记录\"}";
            new JSONArray(value);
            output = storage.startWrite();
            output.write(bytes);
            storage.finishWrite(output);
            return "{\"ok\":true}";
        } catch (Exception failure) {
            if (output != null) storage.failWrite(output);
            return "{\"ok\":false,\"error\":\"聊天记录保存失败，请导出备份后重试\"}";
        }
    }
}
