package dev.chatrelay;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final int ink = Color.rgb(28, 48, 43), teal = Color.rgb(23, 107, 98), muted = Color.rgb(113, 127, 120), paper = Color.rgb(248, 250, 247);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root, content;
    private TextView status;
    private EditText search, composer;
    private ScrollView scroll;
    private String base = "", token = "", screen = "", chatId = "", chatName = "", rendered = "";
    private JSONArray chats = new JSONArray();
    private boolean active, fetching, sending;
    private int generation;
    private final Runnable poll = new Runnable() {
        @Override public void run() { if (active) { refresh(); handler.postDelayed(this, 4000); } }
    };
    interface Result { void done(JSONObject data); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        base = getSharedPreferences("relay", 0).getString("url", "");
        try { token = SecretStore.load(this); } catch (Exception e) { token = ""; }
        if (base.isEmpty() || token.isEmpty()) settings(); else inbox();
    }
    @Override protected void onResume() { super.onResume(); active = true; handler.post(poll); }
    @Override protected void onPause() { active = false; handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (screen.equals("chat")) inbox();
        else if (screen.equals("settings") && !token.isEmpty()) inbox();
        else super.onBackPressed();
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable bg(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private void padding(View v, int h, int y) { v.setPadding(dp(h), dp(y), dp(h), dp(y)); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private TextView action(String label, Runnable run) {
        TextView v = text(label, 15, teal, true); padding(v, 14, 14); v.setMinHeight(dp(48)); v.setGravity(Gravity.CENTER); v.setOnClickListener(w -> run.run()); return v;
    }
    private void shell(String next) {
        generation++; rendered = ""; sending = false; screen = next;
        root = column(); root.setBackgroundColor(paper);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
    }
    private void banner(String message) { if (status != null) { status.setText(message); status.setVisibility(View.VISIBLE); } }
    private void makeStatus() {
        status = text("Connecting to your relay…", 12, teal, false); padding(status, 24, 9); status.setBackgroundColor(Color.rgb(233, 242, 235)); root.addView(status);
    }
    private void inbox() {
        shell("inbox");
        LinearLayout heading = row(); padding(heading, 24, 14);
        LinearLayout brand = column(); brand.addView(text("Chat Relay", 30, ink, true)); brand.addView(text("Your conversations, closer.", 13, muted, false));
        heading.addView(brand, weight()); heading.addView(action("Settings", this::settings)); root.addView(heading);
        search = new EditText(this); search.setSingleLine(); search.setTextSize(15); search.setHint("Search conversations"); search.setTextColor(ink); search.setHintTextColor(muted);
        search.setBackground(bg(Color.rgb(235, 239, 233), 18)); padding(search, 18, 12);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50)); sp.setMargins(dp(24), dp(8), dp(24), dp(18)); root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { renderChats(); }
            public void afterTextChanged(Editable e) {}
        });
        makeStatus();
        scroll = new ScrollView(this); scroll.setFillViewport(true); content = column(); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView footer = text("◈  Tailscale + encrypted HTTPS", 11, muted, false); footer.setGravity(Gravity.CENTER); padding(footer, 12, 14); root.addView(footer);
        renderChats(); refresh();
    }
    private String preview(JSONObject message) {
        if (message == null) return "Start a conversation";
        String value = message.optString("text");
        if (value.isEmpty()) value = message.optBoolean("hasMedia") ? "[Media attachment]" : "[" + message.optString("type", "Message") + "]";
        return (message.optBoolean("fromMe") ? "You: " : "") + value;
    }
    private String time(long seconds, boolean full) {
        if (seconds <= 0) return "";
        Date date = new Date(seconds * 1000);
        String pattern = full ? "MMM d · HH:mm" : (System.currentTimeMillis() - seconds * 1000 > 86400000 ? "MMM d" : "HH:mm");
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(date);
    }
    private void renderChats() {
        if (!screen.equals("inbox")) return;
        content.removeAllViews();
        String query = search.getText().toString().toLowerCase(Locale.ROOT); int count = 0;
        for (int i = 0; i < chats.length(); i++) {
            JSONObject chat = chats.optJSONObject(i); if (chat == null) continue;
            String name = chat.optString("name", "Conversation");
            if (!name.toLowerCase(Locale.ROOT).contains(query) && !preview(chat.optJSONObject("lastMessage")).toLowerCase(Locale.ROOT).contains(query)) continue;
            count++;
            LinearLayout item = row(); padding(item, 24, 16);
            int[] colors = {0xffdce9dc, 0xffe7e2d5, 0xffdce5ea, 0xffeaddd7};
            TextView avatar = text(initials(name), 18, teal, true); avatar.setGravity(Gravity.CENTER); avatar.setBackground(bg(colors[Math.floorMod(name.hashCode(), colors.length)], 22));
            item.addView(avatar, new LinearLayout.LayoutParams(dp(52), dp(52)));
            LinearLayout lines = column(); padding(lines, 14, 0);
            TextView title = text(name, 17, ink, true); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.END); lines.addView(title);
            TextView subtitle = text(preview(chat.optJSONObject("lastMessage")), 13, muted, false); subtitle.setSingleLine(); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END); subtitle.setPadding(0, dp(7), 0, 0); lines.addView(subtitle); item.addView(lines, weight());
            LinearLayout trailing = column(); trailing.setGravity(Gravity.END); trailing.addView(text(time(chat.optLong("timestamp"), false), 11, muted, false));
            int unread = chat.optInt("unread");
            if (unread > 0) { TextView badge = text(unread > 99 ? "99+" : "" + unread, 11, Color.WHITE, true); badge.setGravity(Gravity.CENTER); padding(badge, 7, 3); badge.setBackground(bg(teal, 20)); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.topMargin = dp(8); trailing.addView(badge, bp); }
            item.addView(trailing); item.setOnClickListener(v -> openChat(chat.optString("id"), name)); content.addView(item);
            View divider = new View(this); divider.setBackgroundColor(0xffe9eee7); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1)); lp.leftMargin = dp(90); lp.rightMargin = dp(24); content.addView(divider, lp);
        }
        if (count == 0) { TextView empty = text(query.isEmpty() ? "A little quiet here\n\nYour WhatsApp conversations will appear once your server is connected and linked." : "No conversations found", 16, muted, false); empty.setGravity(Gravity.CENTER); padding(empty, 32, 60); content.addView(empty); }
    }
    private String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return "?";
        String value = parts[0].substring(0, parts[0].offsetByCodePoints(0, 1));
        if (parts.length > 1) value += parts[parts.length - 1].substring(0, parts[parts.length - 1].offsetByCodePoints(0, 1));
        return value.toUpperCase(Locale.ROOT);
    }
    private void openChat(String id, String name) {
        chatId = id; chatName = name; shell("chat");
        LinearLayout header = row(); padding(header, 10, 10); header.addView(action("‹ Back", this::inbox));
        LinearLayout title = column(); title.addView(text(name, 19, ink, true)); title.addView(text("WhatsApp · via your relay", 12, muted, false)); header.addView(title, weight()); root.addView(header); makeStatus();
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xfff0f2eb); content = column(); padding(content, 16, 18); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout compose = row(); padding(compose, 14, 12);
        composer = new EditText(this); composer.setHint("Write a message…"); composer.setTextSize(16); composer.setMaxLines(5); composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); composer.setBackground(bg(Color.WHITE, 22)); padding(composer, 16, 10); compose.addView(composer, weight());
        TextView send = action("Send ↑", this::send); compose.addView(send); root.addView(compose);
        refresh(); request("POST", "/api/chats/" + encode(chatId) + "/read", new JSONObject(), d -> {});
    }
    private void renderMessages(JSONArray messages) {
        String value = messages.toString(); if (value.equals(rendered)) return;
        boolean bottom = rendered.isEmpty() || scroll.getChildAt(0).getHeight() - (scroll.getScrollY() + scroll.getHeight()) < dp(100);
        rendered = value; content.removeAllViews();
        if (messages.length() == 0) { TextView empty = text("Say hello\nMessages sent here appear in WhatsApp.", 15, muted, false); padding(empty, 16, 32); content.addView(empty); }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i); if (message == null) continue;
            boolean mine = message.optBoolean("fromMe");
            LinearLayout line = row(); line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
            LinearLayout bubble = column(); padding(bubble, 14, 10); bubble.setBackground(bg(mine ? 0xffdcebdc : Color.WHITE, 18));
            if (!mine && !message.optString("author").isEmpty()) bubble.addView(text(message.optString("author").split("@")[0], 11, teal, true));
            String body = message.optString("text");
            if (message.optBoolean("hasMedia")) body = "[Media · view in WhatsApp]" + (body.isEmpty() ? "" : "\n" + body);
            if (body.isEmpty()) body = "[" + message.optString("type", "Unsupported message") + "]";
            TextView messageText = text(body, 16, ink, false); messageText.setMaxWidth(dp(270)); messageText.setTextIsSelectable(true); bubble.addView(messageText);
            TextView stamp = text(time(message.optLong("timestamp"), true), 10, muted, false); stamp.setGravity(Gravity.END); stamp.setPadding(0, dp(6), 0, 0); bubble.addView(stamp);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.bottomMargin = dp(10); line.addView(bubble, bp); content.addView(line);
        }
        if (bottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void send() {
        String value = composer.getText().toString().trim();
        if (value.isEmpty() || sending) return;
        if (value.length() > 4096) { banner("Messages can contain up to 4096 characters."); return; }
        JSONObject body = new JSONObject(); try { body.put("text", value); } catch (JSONException ignored) {}
        sending = true; composer.setEnabled(false); banner("Sending…");
        request("POST", "/api/chats/" + encode(chatId) + "/messages", body, result -> { composer.setText(""); refresh(); });
    }
    private void settings() {
        shell("settings"); status = null;
        ScrollView sc = new ScrollView(this); LinearLayout form = column(); padding(form, 26, 24); sc.addView(form); root.addView(sc);
        if (!token.isEmpty()) form.addView(action("‹ Conversations", this::inbox));
        form.addView(text("A private line\nto your messages.", 32, ink, true));
        TextView intro = text("Connect to the relay running on your computer. Keep Tailscale connected on both devices.", 16, muted, false); padding(intro, 0, 22); form.addView(intro);
        form.addView(text("SERVER ADDRESS", 11, teal, true));
        EditText address = new EditText(this); address.setSingleLine(); address.setHint("https://computer.tailnet.ts.net"); address.setText(base); address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); form.addView(address);
        TextView label = text("ACCESS TOKEN", 11, teal, true); padding(label, 0, 18); form.addView(label);
        EditText secret = new EditText(this); secret.setSingleLine(); secret.setHint(token.isEmpty() ? "Paste your server token" : "Leave blank to keep saved token"); secret.setInputType(129); secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); form.addView(secret);
        TextView error = text("", 13, 0xff9b3b2e, false); padding(error, 0, 12); form.addView(error);
        TextView save = action("Connect to relay  →", () -> {
            try {
                URI uri = URI.create(address.getText().toString().trim());
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().endsWith(".ts.net") || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443) || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new Exception("Use the HTTPS .ts.net address printed by Tailscale Serve, with no path.");
                String nextToken = secret.getText().toString().trim(); if (nextToken.isEmpty()) nextToken = token;
                if (nextToken.length() < 32) throw new Exception("Paste the server access token (at least 32 characters).");
                SecretStore.save(this, nextToken); token = nextToken; base = "https://" + uri.getHost();
                getSharedPreferences("relay", 0).edit().putString("url", base).apply(); chats = new JSONArray(); inbox();
            } catch (Exception e) { error.setText(e.getMessage() == null ? "Could not save connection." : e.getMessage()); }
        });
        save.setTextColor(Color.WHITE); save.setBackground(bg(teal, 16)); form.addView(save, new LinearLayout.LayoutParams(-1, dp(54)));
        TextView note = text("PRIVATE BY DESIGN\n\nYour computer links to WhatsApp. Tailscale carries the connection to this phone. The access token stays encrypted on this device.\n\nScan the WhatsApp QR code in your server terminal before opening conversations.", 13, muted, false); padding(note, 0, 30); form.addView(note);
    }
    private static String encode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    private void refresh() {
        if (screen.equals("settings") || fetching || token.isEmpty()) return;
        fetching = true;
        request("GET", "/api/status", null, result -> {
            if (!result.optBoolean("ready")) { banner("WhatsApp: " + result.optString("state") + " · Check your server terminal"); return; }
            banner("◈  Private connection · WhatsApp ready");
            if (screen.equals("inbox")) request("GET", "/api/chats", null, data -> { chats = data.optJSONArray("chats"); if (chats == null) chats = new JSONArray(); renderChats(); });
            else if (screen.equals("chat")) request("GET", "/api/chats/" + encode(chatId) + "/messages", null, data -> { JSONArray messages = data.optJSONArray("messages"); if (messages != null) renderMessages(messages); });
        });
    }
    private void request(String method, String path, JSONObject body, Result callback) {
        final int version = generation; final String endpoint = base + path, access = token;
        worker.execute(() -> {
            JSONObject data = null; String failure = null; HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method); connection.setConnectTimeout(10000); connection.setReadTimeout(25000);
                connection.setRequestProperty("Authorization", "Bearer " + access);
                if (body != null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); } }
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null) throw new IOException("No response");
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) { response.append(line); if (response.length() > 8000000) throw new IOException("Response too large"); } }
                data = new JSONObject(response.toString());
                if (code < 200 || code >= 300) throw new IOException(data.optString("error", "Server error " + code));
            } catch (Exception e) { failure = e instanceof IOException && e.getMessage() != null ? e.getMessage() : "Connection failed. Check Tailscale, server address and access token."; }
            finally { if (connection != null) connection.disconnect(); }
            final JSONObject result = data; final String error = failure;
            handler.post(() -> {
                fetching = false;
                if (version != generation || isDestroyed()) return;
                if (method.equals("POST") && path.endsWith("/messages")) { sending = false; if (composer != null) composer.setEnabled(true); }
                if (error != null) banner((method.equals("POST") && path.endsWith("/messages") ? "Send unconfirmed. Check conversation before retrying. " : "") + error);
                else callback.done(result);
            });
        });
    }
}
