package com.leihuo.chat;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Private, independent memory storage. Saving never truncates an existing file. */
final class MemoryStore {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final String DEFAULT_JSON = "{\"version\":1,\"enabled\":true,\"notes\":\"\",\"entries\":[],\"ignored\":[]}";
    private static final String TOO_LARGE = "综合记忆超过 32 MB，请先导出备份并整理记忆";
    static final String RESOURCE_ERROR_JSON = "{\"ok\":false,\"error\":\"综合记忆处理超出可用内存，原有记忆已保留，请整理记忆后重试\"}";
    static final String RESOURCE_LOAD_ERROR_JSON = "{\"version\":1,\"enabled\":true,\"notes\":\"\",\"entries\":[],\"ignored\":[],\"error\":\"综合记忆读取超出可用内存，原文件已保留，请先整理记忆后重试\"}";
    private final AtomicFile storage;

    MemoryStore(Context context) {
        storage = new AtomicFile(new File(context.getFilesDir(), "memory.json"));
    }

    synchronized String load() {
        try (FileInputStream input = storage.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > MAX_BYTES - count) throw new IllegalArgumentException(TOO_LARGE);
                output.write(buffer, 0, count);
            }
            return validatedJson(output.toString(StandardCharsets.UTF_8.name()));
        } catch (FileNotFoundException missing) {
            if (!storage.getBaseFile().exists()) return DEFAULT_JSON;
            return loadFailure();
        } catch (Exception failure) {
            return loadFailure();
        } catch (OutOfMemoryError exhausted) {
            return RESOURCE_LOAD_ERROR_JSON;
        }
    }

    synchronized String save(String value) {
        FileOutputStream output = null;
        try {
            byte[] bytes = validatedJson(value).getBytes(StandardCharsets.UTF_8);
            output = storage.startWrite();
            output.write(bytes);
            storage.finishWrite(output);
            return "{\"ok\":true}";
        } catch (Exception failure) {
            if (output != null) storage.failWrite(output);
            return error(failure instanceof IllegalArgumentException ? failure.getMessage()
                    : "综合记忆保存失败，原有记忆已保留，请导出备份后重试");
        } catch (OutOfMemoryError exhausted) {
            if (output != null) {
                try { storage.failWrite(output); }
                catch (OutOfMemoryError cleanupExhausted) { /* AtomicFile retains the previous backup. */ }
            }
            return RESOURCE_ERROR_JSON;
        }
    }

    /** Returns canonical ordinary JSON and rejects malformed or oversized input. */
    static String validatedJson(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("综合记忆数据为空");
        if (value.length() > MAX_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(TOO_LARGE);
        }
        try {
            JSONTokener tokener = new JSONTokener(value);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new IllegalArgumentException("综合记忆格式无效，请使用完整的记忆 JSON 文件");
            }
            JSONObject memory = (JSONObject) parsed;
            Object version = memory.opt("version");
            if (!(version instanceof Number) || ((Number) version).doubleValue() != 1) {
                throw new IllegalArgumentException("综合记忆版本不兼容，当前仅支持版本 1");
            }
            if (!(memory.opt("enabled") instanceof Boolean)
                    || !(memory.opt("notes") instanceof String)
                    || !(memory.opt("entries") instanceof JSONArray)
                    || !(memory.opt("ignored") instanceof JSONArray)) {
                throw new IllegalArgumentException("综合记忆格式无效：enabled、notes、entries 或 ignored 类型不正确");
            }
            String normalized = memory.toString();
            if (normalized == null) throw new IllegalArgumentException("综合记忆包含无法保存的 JSON 数据");
            if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException(TOO_LARGE);
            return normalized;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("综合记忆格式无效，请使用完整的记忆 JSON 文件");
        }
    }

    private static String loadFailure() {
        return emptyWithError("综合记忆读取失败，原文件已保留，请先备份后重试");
    }

    static String emptyWithError(String message) {
        try {
            return new JSONObject(DEFAULT_JSON).put("error", message).toString();
        } catch (Exception impossible) {
            return DEFAULT_JSON;
        } catch (OutOfMemoryError exhausted) {
            return RESOURCE_LOAD_ERROR_JSON;
        }
    }

    static String error(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Exception impossible) {
            return "{\"ok\":false,\"error\":\"综合记忆保存失败\"}";
        } catch (OutOfMemoryError exhausted) {
            return RESOURCE_ERROR_JSON;
        }
    }
}
