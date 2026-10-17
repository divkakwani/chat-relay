package dev.chatrelay;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class RelayApi {
    static final int MAX_MEDIA = 16 * 1024 * 1024;
    final String base, token;
    RelayApi(String base, String token) { this.base = base; this.token = token; }
    static String encode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    private HttpURLConnection open(String path, String method) throws IOException {
        if (!path.startsWith("/api/")) throw new IOException("Invalid request path");
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setInstanceFollowRedirects(false); c.setRequestMethod(method);
        c.setConnectTimeout(10000); c.setReadTimeout(90000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }
    JSONObject json(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = open(path, method);
        try {
            if (body != null) {
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int status = c.getResponseCode();
            try (InputStream stream = status < 300 ? c.getInputStream() : c.getErrorStream()) {
                JSONObject result = new JSONObject(new String(read(stream, 8 * 1024 * 1024), StandardCharsets.UTF_8));
                if (status < 200 || status >= 300) throw new IOException(result.optString("error", "Server error " + status));
                return result;
            }
        } finally { c.disconnect(); }
    }
    JSONObject upload(String path, byte[] bytes, String mimetype, String filename, String caption) throws Exception {
        HttpURLConnection c = open(path, "POST");
        try {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", mimetype);
            c.setRequestProperty("X-Relay-Caption", encode(caption)); c.setRequestProperty("X-Relay-Filename", encode(filename));
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            try (InputStream stream = status < 300 ? c.getInputStream() : c.getErrorStream()) {
                JSONObject result = new JSONObject(new String(read(stream, 20000), StandardCharsets.UTF_8));
                if (status < 200 || status >= 300) throw new IOException(result.optString("error", "Upload failed"));
                return result;
            }
        } finally { c.disconnect(); }
    }
    byte[] bytes(String path) throws Exception {
        HttpURLConnection c = open(path, "GET");
        try {
            int status = c.getResponseCode();
            if (status != 200) {
                try (InputStream stream = c.getErrorStream()) {
                    String message = new JSONObject(new String(read(stream, 20000), StandardCharsets.UTF_8)).optString("error", "Attachment unavailable");
                    throw new IOException(message);
                }
            }
            long size = c.getContentLengthLong(); if (size > MAX_MEDIA) throw new IOException("Attachment exceeds 16 MiB");
            try (InputStream stream = c.getInputStream()) { return read(stream, MAX_MEDIA); }
        } finally { c.disconnect(); }
    }
    static byte[] read(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException("No response from relay");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int size = 0, count;
        while ((count = input.read(buffer)) != -1) {
            size += count; if (size > limit) throw new IOException("File is too large (maximum 16 MiB)");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
