package dev.chatrelay;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import org.json.*;
import java.net.URI;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private int ink, green, muted, paper, surface, line, outgoing, errorColor;
    private boolean dark, active, fetching, sending, stickyError, firstSync = true;
    private Typeface regular, medium, bold;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private ImageLoader images = new ImageLoader();
    private LinearLayout root, messageList, tabs;
    private TextView status;
    private EditText search, composer;
    private ListView list;
    private ScrollView scroll;
    private FrameLayout myAvatar;
    private ChatAdapter adapter;
    private RelayApi api;
    private String base = "", token = "", screen = "", chatId = "", chatName = "", rendered = "", inboxSnapshot = "", tab = "DMs", query = "", accountId = "", accountName = "You", pickerChat = "";
    private JSONArray chats = new JSONArray(), calls = new JSONArray(), contacts = new JSONArray();
    private final Map<String,String> drafts = new HashMap<>();
    private final Set<String> seenCalls = new HashSet<>();
    private int generation;
    private AlertDialog incomingDialog;
    private final Runnable poll = new Runnable() { public void run() { if (active) { refresh(); handler.postDelayed(this, 4000); } } };
    interface Result { void done(JSONObject result); }

    @Override public void onCreate(Bundle saved) {
        configureTheme(); super.onCreate(saved);
        try {
            regular = new Typeface.Builder(getAssets(), "fonts/Inter.ttf").setFontVariationSettings("'wght' 400, 'opsz' 14").build();
            medium = new Typeface.Builder(getAssets(), "fonts/Inter.ttf").setFontVariationSettings("'wght' 600, 'opsz' 14").build();
            bold = new Typeface.Builder(getAssets(), "fonts/Inter.ttf").setFontVariationSettings("'wght' 700, 'opsz' 24").build();
        } catch (Exception e) { regular = Typeface.create("sans-serif",Typeface.NORMAL); medium = Typeface.create("sans-serif-medium",Typeface.NORMAL); bold = Typeface.create("sans-serif",Typeface.BOLD); }
        base = getSharedPreferences("relay",0).getString("url","");
        try { token = SecretStore.load(this); } catch (Exception ignored) {}
        api = new RelayApi(base,token);
        if (saved != null) {
            tab = saved.getString("tab","DMs"); if(tab.equals("Calls"))tab="DMs"; query = saved.getString("query",""); pickerChat = saved.getString("pickerChat","");
            chatId = saved.getString("chatId",""); chatName = saved.getString("chatName","");
            drafts.put(chatId, saved.getString("draft",""));
        }
        if (base.isEmpty() || token.isEmpty()) settings();
        else if (saved != null && saved.getString("screen","").equals("chat")) openChat(chatId,chatName);
        else if (saved != null && saved.getString("screen","").equals("settings")) settings();
        else inbox();
    }
    private void configureTheme() {
        String mode = getSharedPreferences("relay",0).getString("theme","system");
        dark = mode.equals("dark") || (mode.equals("system") && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
        ink = dark ? 0xffe8e8ed : 0xff292a32; green = dark ? 0xffb6b7dc : 0xff626584;
        muted = dark ? 0xff92939f : 0xff7c7d88; paper = dark ? 0xff121318 : 0xfffaf9f7;
        surface = dark ? 0xff1e1f27 : 0xffefeeed; line = dark ? 0xff2b2c35 : 0xffe4e3e3;
        outgoing = dark ? 0xff282c43 : 0xffe9e9f3; errorColor = dark ? 0xffffb4a6 : 0xff9d3528;
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("screen",screen); state.putString("tab",tab); state.putString("query",query);
        state.putString("chatId",chatId); state.putString("chatName",chatName); state.putString("pickerChat",pickerChat);
        state.putString("draft",composer != null ? composer.getText().toString() : drafts.getOrDefault(chatId,""));
    }
    @Override protected void onResume() { super.onResume(); active = true; handler.removeCallbacks(poll); handler.post(poll); }
    @Override protected void onPause() { active = false; handler.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { generation++; if (incomingDialog != null) incomingDialog.dismiss(); images.close(); worker.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() { if (!screen.equals("inbox") && !token.isEmpty()) inbox(); else super.onBackPressed(); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable bg(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void pad(View view, int h, int v) { view.setPadding(dp(h),dp(v),dp(h),dp(v)); }
    private LinearLayout col() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0,-2,1); }
    private TextView text(String value, int size, int color, boolean strong) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setTypeface(strong ? (size >= 22 ? bold : medium) : regular); v.setIncludeFontPadding(false); v.setLineSpacing(dp(3),1); return v;
    }
    private TextView action(String label, Runnable run) { TextView v = text(label,14,green,true); pad(v,14,16); v.setMinHeight(dp(48)); v.setGravity(Gravity.CENTER); v.setOnClickListener(w -> run.run()); return v; }
    private View icon(String name, Runnable run) { Icons v = new Icons(this,name,green); v.setLayoutParams(new LinearLayout.LayoutParams(dp(46),dp(48))); v.setOnClickListener(w -> run.run()); v.setFocusable(true); return v; }
    private void shell(String next) {
        if (screen.equals("chat") && composer != null) drafts.put(chatId,composer.getText().toString());
        generation++; screen = next; fetching = false; sending = false; stickyError = false; rendered = ""; inboxSnapshot = ""; composer = null; status = null;
        root = col(); root.setBackgroundColor(paper);
        getWindow().setStatusBarColor(paper); getWindow().setNavigationBarColor(dark || Build.VERSION.SDK_INT < 27 ? 0xff121318 : paper);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 27 && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        root.setOnApplyWindowInsetsListener((v,insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        setContentView(root); root.requestApplyInsets();
    }
    private void statusView() { status = text("Connecting…",12,muted,false); pad(status,24,8); root.addView(status); }
    private void banner(String value) { if (status != null) { status.setText(value); status.setTextColor(errorColor); status.setVisibility(View.VISIBLE); } }
    private TextView wordmark() {
        TextView v = text("chatrelay.",27,ink,false); v.setLetterSpacing(-.045f);
        return v;
    }
    private FrameLayout avatar(String name, String id, int size) {
        FrameLayout frame = new FrameLayout(this); frame.setBackground(bg(surface,size/2)); frame.setClipToOutline(true);
        TextView initials = text(initials(name),size > 45 ? 15 : 13,green,true); initials.setGravity(Gravity.CENTER); frame.addView(initials,new FrameLayout.LayoutParams(-1,-1));
        ImageView photo = new ImageView(this); photo.setScaleType(ImageView.ScaleType.CENTER_CROP); photo.setContentDescription(name + " profile photo"); frame.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        if (!id.isEmpty() && !base.isEmpty()) images.load(api,"/api/chats/"+RelayApi.encode(id)+"/avatar",photo);
        return frame;
    }
    private String initials(String name) {
        String[] words = name.trim().split("\\s+"); if (words[0].isEmpty()) return "?";
        String a = words[0].substring(0,words[0].offsetByCodePoints(0,1));
        if (words.length > 1) { String last = words[words.length-1]; a += last.substring(0,last.offsetByCodePoints(0,1)); }
        return a.toUpperCase(Locale.ROOT);
    }
    private void inbox() {
        shell("inbox");
        LinearLayout heading = row(); pad(heading,24,24); heading.addView(wordmark(),weight());
        heading.addView(icon("Search",this::toggleSearch));
        myAvatar = avatar(accountName,accountId,40); myAvatar.setContentDescription("Settings and appearance"); myAvatar.setOnClickListener(v -> settings()); heading.addView(myAvatar,new LinearLayout.LayoutParams(dp(40),dp(40))); root.addView(heading);
        LinearLayout searchRow = row(); searchRow.setBackground(bg(surface,28)); searchRow.addView(icon("Search",() -> search.requestFocus()));
        search = new EditText(this); search.setSingleLine(); search.setTextSize(14); search.setTypeface(regular); search.setTextColor(ink); search.setHintTextColor(muted); search.setHint("Search conversations"); search.setBackgroundColor(Color.TRANSPARENT); search.setPadding(0,0,dp(16),0); searchRow.addView(search,weight());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1,dp(50)); sl.setMargins(dp(22),0,dp(22),dp(14)); root.addView(searchRow,sl); searchRow.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE); search.setText(query);
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a) {} public void afterTextChanged(Editable e) {} public void onTextChanged(CharSequence s,int st,int b,int c) { query = s.toString(); renderInbox(); } });
        tabs = row(); pad(tabs,22,0); root.addView(tabs); renderTabs();
        statusView();
        FrameLayout body = new FrameLayout(this); list = new ListView(this); list.setDivider(null); list.setCacheColorHint(Color.TRANSPARENT); list.setClipToPadding(false); list.setPadding(0,dp(8),0,dp(92)); list.setSelector(bg(surface,0));
        adapter = new ChatAdapter(); list.setAdapter(adapter); body.addView(list,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout composeButton = new FrameLayout(this); composeButton.setBackground(bg(outgoing,30)); composeButton.setElevation(dp(0)); composeButton.setContentDescription("New conversation");
        Icons composeIcon = new Icons(this,"Compose",green); composeButton.addView(composeIcon,new FrameLayout.LayoutParams(-1,-1)); composeButton.setOnClickListener(v -> newConversation());
        FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(dp(58),dp(58),Gravity.BOTTOM|Gravity.END); fab.setMargins(0,0,dp(24),dp(20)); body.addView(composeButton,fab);
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        root.setFocusableInTouchMode(true); root.requestFocus(); renderInbox(); refresh();
    }
    private void toggleSearch() {
        View box=(View)search.getParent(); boolean show=box.getVisibility()!=View.VISIBLE;
        box.setVisibility(show?View.VISIBLE:View.GONE);
        if(show){search.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);}
        else{search.setText("");((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}
    }
    private void conversationOptions() {
        new AlertDialog.Builder(this).setTitle(chatName).setItems(new String[]{"Voice call", "Video call"},(d,which)->callOptions(which==1)).show();
    }
    private void renderTabs() {
        tabs.removeAllViews();
        for (String label : new String[]{"DMs","Groups","Business"}) {
            int unread=0; for(int i=0;i<chats.length();i++){JSONObject c=chats.optJSONObject(i);if(c!=null&&category(c).equals(label)&&c.optInt("unread")>0)unread++;}
            LinearLayout container = col(); container.setGravity(Gravity.CENTER); boolean selected = tab.equals(label);
            TextView title = text(label + (unread>0 ? "  " + unread : ""),14,selected?ink:muted,false); title.setGravity(Gravity.CENTER); pad(title,0,16); container.addView(title,new LinearLayout.LayoutParams(-1,-2));
            View underline = new View(this); underline.setBackground(bg(selected?green:line,2)); container.addView(underline,new LinearLayout.LayoutParams(-1,dp(selected?2:1)));
            container.setOnClickListener(v -> { tab=label; renderTabs(); renderInbox(); }); tabs.addView(container,new LinearLayout.LayoutParams(0,-1,1));
        }
    }
    private String category(JSONObject chat) { return chat.optBoolean("isGroup") ? "Groups" : chat.optBoolean("isBusiness") ? "Business" : "DMs"; }
    private String preview(JSONObject message) {
        if (message==null) return "Start a conversation";
        String value = message.optString("text");
        if (value.isEmpty()) { String mime = message.optString("mimetype"); value = mime.startsWith("video/") || message.optString("type").equals("video") ? "Video" : message.optBoolean("hasMedia") ? "Photo or attachment" : "Message"; }
        return (message.optBoolean("fromMe")?"You: ":!message.optString("author").isEmpty()&&!message.optString("authorName").isEmpty()?message.optString("authorName")+": ":"")+value;
    }
    private String time(long seconds, boolean full) {
        if (seconds<=0) return ""; long days = (System.currentTimeMillis()-seconds*1000)/86400000;
        if (!full && days==1) return "Yesterday";
        return new SimpleDateFormat(full?"HH:mm":days<1?"HH:mm":days<7?"EEE":"MMM d",Locale.getDefault()).format(new Date(seconds*1000));
    }
    private String nameFor(String id) { for(int i=0;i<chats.length();i++) { JSONObject c=chats.optJSONObject(i); if(c.optString("id").equals(id)) return c.optString("name"); } return id.split("@")[0]; }
    private final ArrayList<JSONObject> visible = new ArrayList<>();
    private void renderInbox() {
        if(!screen.equals("inbox") || adapter==null) return;
        String snapshot=tab+"|"+query+"|"+(tab.equals("Calls")?calls.toString():chats.toString());
        if(snapshot.equals(inboxSnapshot))return;inboxSnapshot=snapshot;
        visible.clear(); String q=query.toLowerCase(Locale.ROOT);
        JSONArray source=tab.equals("Calls")?calls:chats;
        for(int i=0;i<source.length();i++) {
            JSONObject item=source.optJSONObject(i); if(item==null)continue;
            boolean isCall=tab.equals("Calls");
            if(!isCall && !category(item).equals(tab))continue;
            String name=isCall?nameFor(item.optString("from")):item.optString("name");
            if(!(name+" "+(isCall?"":preview(item.optJSONObject("lastMessage")))).toLowerCase(Locale.ROOT).contains(q))continue;
            visible.add(item);
        }
        adapter.notifyDataSetChanged();
    }
    private class ChatAdapter extends BaseAdapter {
        public int getCount(){return Math.max(1,visible.size());} public Object getItem(int p){return visible.isEmpty()?null:visible.get(p);} public long getItemId(int p){return p;}
        @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
            if(visible.isEmpty()) {
                TextView empty=text(query.isEmpty()?(tab.equals("Calls")?"No call activity yet\n\nIncoming voice and video calls appear here while your server is running. Answer calls in WhatsApp.":firstSync?"Getting your conversations…":"All quiet for now\n\nYour conversations will appear here."):"No matches found",15,muted,false); pad(empty,32,52); empty.setGravity(Gravity.CENTER); return empty;
            }
            JSONObject item=visible.get(position); boolean isCall=tab.equals("Calls"); String id=item.optString(isCall?"from":"id"), name=isCall?nameFor(id):item.optString("name");
            LinearLayout cell=row(); pad(cell,24,20); cell.setMinimumHeight(dp(96)); cell.addView(avatar(name,id,46),new LinearLayout.LayoutParams(dp(46),dp(46)));
            LinearLayout lines=col(); pad(lines,15,0); TextView title=text(name,16,ink,false); title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END); lines.addView(title);
            String subtitle=isCall?(item.optBoolean("isVideo")?"Video call":"Voice call")+" · "+item.optString("status"):preview(item.optJSONObject("lastMessage"));
            TextView sub=text(subtitle,13,muted,false); sub.setSingleLine(); sub.setEllipsize(TextUtils.TruncateAt.END); sub.setPadding(0,dp(7),0,0); lines.addView(sub); cell.addView(lines,weight());
            LinearLayout trailing=col(); trailing.setGravity(Gravity.END); trailing.addView(text(time(item.optLong("timestamp"),false),11,item.optInt("unread")>0?green:muted,false));
            int unread=item.optInt("unread"); if(unread>0) { TextView badge=text(unread>99?"99+":""+unread,10,dark?paper:Color.WHITE,true); badge.setGravity(Gravity.CENTER); pad(badge,6,4); badge.setBackground(bg(outgoing,16)); badge.setTextColor(green); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2); bp.topMargin=dp(12); trailing.addView(badge,bp); }
            cell.addView(trailing); cell.setOnClickListener(v -> { if(isCall) showCall(item); else openChat(id,name); }); return cell;
        }
    }
    private boolean hasContactName(JSONObject contact) {
        String name=contact.optString("name").trim();
        return !name.isEmpty()&&!name.equalsIgnoreCase("Unknown contact")&&!name.matches("[+\\d\\s().-]+");
    }
    private ArrayList<JSONObject> contactChoices(String q) {
        Map<String,JSONObject> all=new LinkedHashMap<>();
        for(int i=0;i<contacts.length();i++){JSONObject c=contacts.optJSONObject(i);if(c!=null)all.put(c.optString("id"),c);}
        for(int i=0;i<chats.length();i++){
            JSONObject c=chats.optJSONObject(i);
            if(c!=null&&!c.optBoolean("isGroup")&&hasContactName(c)){
                JSONObject existing=all.get(c.optString("id"));
                if(existing==null||!hasContactName(existing))all.put(c.optString("id"),c);
            }
        }
        ArrayList<JSONObject> sorted=new ArrayList<>();
        for(JSONObject c:all.values())if((!q.isEmpty()||hasContactName(c))&&(c.optString("name")+" "+c.optString("number")).toLowerCase(Locale.ROOT).contains(q))sorted.add(c);
        sorted.sort((a,b)->{boolean an=hasContactName(a),bn=hasContactName(b);return an!=bn?(an?-1:1):a.optString("name").compareToIgnoreCase(b.optString("name"));});
        return sorted;
    }
    private void newConversation() {
        LinearLayout content=col(); pad(content,20,8);
        EditText filter=field("Search name or number"); filter.setSingleLine(); content.addView(filter);
        TextView feedback=text("Loading contacts…",13,muted,false); pad(feedback,0,12); content.addView(feedback);
        ListView choices=new ListView(this); choices.setDivider(null); content.addView(choices,new LinearLayout.LayoutParams(-1,dp(340)));
        ArrayList<JSONObject> matches=new ArrayList<>();
        ArrayAdapter<String> names=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>());
        choices.setAdapter(names);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("New message").setView(content).setNegativeButton("Cancel",null).setNeutralButton("Use phone number",(d,w)->newNumber()).create();
        Runnable update=()->{
            matches.clear(); names.clear(); String q=filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            for(JSONObject c:contactChoices(q)){matches.add(c);names.add(c.optString("name")+(c.optBoolean("isBusiness")?" · Business":""));}
            feedback.setText(matches.isEmpty()?"No named contacts found. Search a number or use a phone number.":"Choose a contact"); names.notifyDataSetChanged();
        };
        choices.setOnItemClickListener((parent,view,position,id)->{JSONObject c=matches.get(position);dialog.dismiss();openChat(c.optString("id"),c.optString("name"));});
        filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void afterTextChanged(Editable e){update.run();} public void onTextChanged(CharSequence s,int st,int b,int c){}});
        dialog.show(); update.run(); feedback.setText("Loading contacts…"); RelayApi client=api;
        worker.execute(()->{try{JSONArray data=client.json("GET","/api/contacts",null).optJSONArray("contacts");runOnUiThread(()->{if(!dialog.isShowing()||isDestroyed())return;contacts=data==null?new JSONArray():data;update.run();});}
            catch(Exception e){runOnUiThread(()->{if(dialog.isShowing()){update.run();feedback.setText("Could not load contacts. Showing recent chats.");}});}});
    }
    private void newNumber() {
        EditText phone=field("Phone number with country code"); phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(this).setTitle("New conversation").setMessage("Enter an international WhatsApp number, including its country code.").setView(phone).setNegativeButton("Cancel",null).setPositiveButton("Open",(d,w)->{
            String number=phone.getText().toString().replaceAll("[+ ()-]","");
            if(!number.matches("[0-9]{7,15}")) { Toast.makeText(this,"Enter 7–15 digits with country code",Toast.LENGTH_LONG).show(); return; }
            openChat(number+"@c.us","+"+number);
        }).show();
    }
    private void openChat(String id,String name) {
        // Save the previous draft before changing its conversation key.
        if(screen.equals("chat") && composer!=null) drafts.put(chatId,composer.getText().toString());
        composer=null; chatId=id; chatName=name; shell("chat");
        LinearLayout header=row(); pad(header,6,8); header.addView(icon("Back",this::inbox)); header.addView(avatar(name,id,38),new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout titles=col(); pad(titles,12,0); TextView title=text(name,17,ink,true); title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END); titles.addView(title);  header.addView(titles,weight());
        header.addView(icon("More",this::conversationOptions)); root.addView(header); statusView();
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(paper); messageList=col(); pad(messageList,24,20); scroll.addView(messageList); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout compose=row(); pad(compose,6,6); compose.setBackground(bg(surface,30));
        composer=field("Write a message…"); composer.setBackgroundColor(Color.TRANSPARENT); composer.setTextSize(16); composer.setSingleLine(false); composer.setMaxLines(5); composer.setText(drafts.getOrDefault(id,"")); composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); compose.addView(composer,weight());
        compose.addView(icon("Attach",this::pickMedia)); View send=icon("Send",this::send); send.setBackground(bg(outgoing,24)); compose.addView(send);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.setMargins(dp(20),dp(10),dp(20),dp(16)); root.addView(compose,cp); refresh();
        if(!token.isEmpty())mutate("/api/chats/"+RelayApi.encode(id)+"/read",new JSONObject(),false,d->{});
    }
    private void renderMessages(JSONArray messages) {
        if(messages.toString().equals(rendered))return;
        boolean bottom=rendered.isEmpty() || messageList.getHeight()-(scroll.getScrollY()+scroll.getHeight())<dp(120); rendered=messages.toString(); messageList.removeAllViews();
        if(messages.length()==0){TextView quiet=text("Say hello\nYour messages sync with WhatsApp.",14,muted,false); quiet.setGravity(Gravity.CENTER); pad(quiet,20,40); messageList.addView(quiet);}
        String previousDay="";
        for(int i=0;i<messages.length();i++) {
            JSONObject m=messages.optJSONObject(i); if(m==null)continue;
            String day=new SimpleDateFormat("EEE, MMM d",Locale.getDefault()).format(new Date(m.optLong("timestamp")*1000));
            if(!day.equals(previousDay)){TextView date=text(day,10,muted,true); date.setGravity(Gravity.CENTER); pad(date,0,18); messageList.addView(date); previousDay=day;}
            boolean mine=m.optBoolean("fromMe"); LinearLayout wrap=row(); wrap.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);
            LinearLayout bubble=col(); pad(bubble,18,14); bubble.setBackground(bg(mine?outgoing:surface,24));
            if(!mine && !m.optString("author").isEmpty()){TextView author=text(m.optString("authorName").isEmpty()?nameFor(m.optString("author")):m.optString("authorName"),12,green,true); author.setMaxWidth(dp(245)); bubble.addView(author);}
            String mime=m.optString("mimetype"), type=m.optString("type");
            boolean video=mime.startsWith("video/")||type.equals("video"), image=mime.startsWith("image/")||type.equals("image");
            if(m.optBoolean("hasMedia")) {
                String path="/api/chats/"+RelayApi.encode(chatId)+"/media/"+RelayApi.encode(m.optString("id"));
                if(image){FrameLayout preview=new FrameLayout(this); preview.setBackground(bg(surface,12)); preview.setClipToOutline(true); ImageView picture=new ImageView(this); picture.setScaleType(ImageView.ScaleType.CENTER_CROP); picture.setContentDescription("Photo · tap to view"); preview.addView(picture,new FrameLayout.LayoutParams(-1,-1)); TextView hint=text("Photo · tap to open",10,green,true); hint.setBackground(bg(surface,8)); pad(hint,8,5); FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START); hp.setMargins(dp(8),0,0,dp(8)); preview.addView(hint,hp); bubble.addView(preview,new LinearLayout.LayoutParams(dp(240),dp(190))); images.load(api,path,picture); preview.setOnClickListener(v->openMedia(path,false));}
                else if(video){LinearLayout tile=row(); tile.setBackground(bg(surface,12)); tile.addView(icon("Play",()->openMedia(path,true))); LinearLayout labels=col(); labels.addView(text("Video",14,ink,true)); labels.addView(text("Tap to download & play",10,muted,false)); tile.addView(labels); bubble.addView(tile,new LinearLayout.LayoutParams(dp(240),dp(80))); tile.setOnClickListener(v->openMedia(path,true));}
                else bubble.addView(text("Attachment · view in WhatsApp",12,muted,false));
            }
            String body=m.optString("text"); if(!body.isEmpty() || !m.optBoolean("hasMedia")){TextView bodyText=text(body.isEmpty()?"["+type+"]":body,16,ink,false); bodyText.setMaxWidth(dp(245)); bodyText.setTextIsSelectable(true); bodyText.setPadding(0,dp(5),0,0); bubble.addView(bodyText);}
            int ack=m.optInt("ack"); String receipt=mine?(ack>=2?"  ✓✓":ack>=1?"  ✓":"  ◷"):"";
            TextView stamp=text(time(m.optLong("timestamp"),true)+receipt,11,ack>=3?green:muted,false); stamp.setGravity(Gravity.END); stamp.setPadding(0,dp(8),0,0); bubble.addView(stamp);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2); lp.bottomMargin=dp(14); wrap.addView(bubble,lp); messageList.addView(wrap);
        }
        if(bottom)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void openMedia(String path,boolean video) { Intent intent=new Intent(this,MediaActivity.class); intent.putExtra("path",path); intent.putExtra("video",video); startActivity(intent); }
    private void send() {
        if(sending)return; String value=composer.getText().toString().trim(); if(value.isEmpty())return;
        if(value.length()>4096){banner("Messages can contain up to 4096 characters.");return;}
        JSONObject body=new JSONObject(); try{body.put("text",value);}catch(JSONException ignored){}
        mutate("/api/chats/"+RelayApi.encode(chatId)+"/messages",body,true,result->{composer.setText("");drafts.remove(chatId);rendered="";refresh();});
    }
    private void pickMedia() {
        if(sending)return; pickerChat=chatId;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp","image/gif","video/mp4","video/webm"}); startActivityForResult(intent,10);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=10 || resultCode!=RESULT_OK || data==null || data.getData()==null || !screen.equals("chat") || !chatId.equals(pickerChat))return;
        Uri uri=data.getData(); int version=generation; banner("Preparing attachment…");
        worker.execute(()->{
            try {
                String mime=getContentResolver().getType(uri); String filename="attachment";
                try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
                    if(cursor!=null && cursor.moveToFirst()){filename=cursor.getString(0);if(!cursor.isNull(1)&&cursor.getLong(1)>RelayApi.MAX_MEDIA)throw new IOException("Choose a file smaller than 16 MiB.");}
                }
                byte[] bytes; try(InputStream input=getContentResolver().openInputStream(uri)){bytes=RelayApi.read(input,RelayApi.MAX_MEDIA);}
                String type=mime==null?"application/octet-stream":mime; String name=filename;
                runOnUiThread(()->{if(version==generation && !isDestroyed())confirmAttachment(bytes,type,name);});
            } catch(Exception e){runOnUiThread(()->{if(version==generation)banner(e.getMessage()==null?"Could not open attachment":e.getMessage());});}
        });
    }
    private void confirmAttachment(byte[] data,String mime,String filename) {
        boolean supported=Arrays.asList("image/jpeg","image/png","image/webp","image/gif","video/mp4","video/webm").contains(mime);
        if(!supported){banner("Choose a JPEG, PNG, WebP, GIF, MP4 or WebM file.");return;}
        LinearLayout preview=col(); pad(preview,24,8);
        if(mime.startsWith("image/")){ImageView image=new ImageView(this); image.setImageBitmap(ImageLoader.decode(data,600)); image.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.addView(image,new LinearLayout.LayoutParams(-1,dp(200)));}
        preview.addView(text(filename+" · "+String.format(Locale.getDefault(),"%.1f MiB",data.length/1048576f),12,muted,false));
        EditText caption=field("Add a caption (optional)"); caption.setText(composer.getText()); preview.addView(caption);
        int version=generation;
        new AlertDialog.Builder(this).setTitle(mime.startsWith("video/")?"Send video":"Send photo").setView(preview).setNegativeButton("Cancel",null).setPositiveButton("Send",(d,w)->{
            if(version!=generation)return;
            if(caption.length()>4096){banner("Caption is too long (maximum 4096 characters).");return;}
            String captionValue=caption.getText().toString(), target=chatId;RelayApi client=api;
            sending=true;composer.setEnabled(false);banner("Sending attachment…");
            // Stream the binary body directly; avoid several base64/JSON copies
            // of a potentially 16 MiB video on a phone's limited heap.
            worker.execute(()->{
                String failure=null;
                try { client.upload("/api/chats/"+RelayApi.encode(target)+"/media",data,mime,filename,captionValue); }
                catch(Exception e){failure=e.getMessage()==null?"Attachment upload failed":e.getMessage();}
                String error=failure;
                runOnUiThread(()->{
                    if(version!=generation||isDestroyed())return;sending=false;composer.setEnabled(true);
                    if(error!=null){stickyError=true;banner("Send unconfirmed. Check the conversation before retrying. "+error);}
                    else{stickyError=false;composer.setText("");drafts.remove(target);rendered="";if(status!=null)status.setVisibility(View.GONE);refresh();}
                });
            });
        }).show();
    }
    private void callOptions(boolean video) {
        new AlertDialog.Builder(this).setTitle(video?"Video call":"Voice call").setMessage("Calls take place in the official WhatsApp app. Open the conversation there, then tap its "+(video?"video":"voice")+" call button. You can also create a call link to share.").setNegativeButton("Cancel",null).setPositiveButton("Open WhatsApp",(d,w)->openWhatsApp(chatId)).setNeutralButton("Create link",(d,w)->createCallLink(video)).show();
    }
    private void openWhatsApp(String id) {
        String number=id.endsWith("@c.us")?id.split("@")[0]:"";
        for(String packageName:new String[]{"com.whatsapp","com.whatsapp.w4b"}) {
            Intent intent=number.matches("[0-9]+")?new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+number)).setPackage(packageName):getPackageManager().getLaunchIntentForPackage(packageName);
            if(intent==null)continue;
            try{startActivity(intent);return;}catch(ActivityNotFoundException ignored){}
        }
        new AlertDialog.Builder(this).setTitle("WhatsApp is needed for calls").setMessage("Install WhatsApp on this phone. Relay supports call activity and declining incoming calls; call audio and video run in WhatsApp.").setPositiveButton("OK",null).show();
    }
    private void openExternal(Intent intent) { try { startActivity(intent); } catch (ActivityNotFoundException e) { Toast.makeText(this,"No compatible app is installed to open this link.",Toast.LENGTH_LONG).show(); } }
    private void createCallLink(boolean video) {
        JSONObject body=new JSONObject();try{body.put("type",video?"video":"voice");}catch(JSONException ignored){}
        mutate("/api/call-links",body,false,result->{String url=result.optString("url");new AlertDialog.Builder(this).setTitle("Call link ready").setMessage(url).setNegativeButton("Close",null).setPositiveButton("Open in WhatsApp",(d,w)->openExternal(new Intent(Intent.ACTION_VIEW,Uri.parse(url)))).setNeutralButton("Share",(d,w)->{Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");share.putExtra(Intent.EXTRA_TEXT,url);openExternal(Intent.createChooser(share,"Share call link"));}).show();});
    }
    private void showCall(JSONObject call) {
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle((call.optBoolean("isVideo")?"Video":"Voice")+" call · "+nameFor(call.optString("from"))).setMessage("Answer or call back using the official WhatsApp app. Call activity reflects events received by this server; it is not a live call connection.").setNegativeButton("Close",null).setPositiveButton("Open WhatsApp",(d,w)->openWhatsApp(call.optString("from")));
        if(call.optBoolean("canReject"))dialog.setNeutralButton("Decline",(d,w)->mutate("/api/calls/"+RelayApi.encode(call.optString("id"))+"/reject",new JSONObject(),false,result->refresh()));
        incomingDialog=dialog.show();
    }
    private EditText field(String hint) { EditText v=new EditText(this);v.setTextSize(14);v.setTypeface(regular);v.setTextColor(ink);v.setHintTextColor(muted);v.setHint(hint);v.setBackground(bg(surface,18));pad(v,16,14);return v; }
    private void settings() {
        shell("settings"); ScrollView sc=new ScrollView(this);LinearLayout form=col();pad(form,24,20);sc.addView(form);root.addView(sc);
        if(!token.isEmpty())form.addView(action("‹  Back to chats",this::inbox));form.addView(wordmark());
        TextView heading=text("Make yourself at home.",22,ink,true);pad(heading,0,24);form.addView(heading);
        form.addView(text("APPEARANCE",10,muted,true));LinearLayout themes=row();String saved=getSharedPreferences("relay",0).getString("theme","system");
        for(String mode:new String[]{"light","dark","system"}){TextView choice=action(mode.substring(0,1).toUpperCase(Locale.ROOT)+mode.substring(1),()->{getSharedPreferences("relay",0).edit().putString("theme",mode).apply();configureTheme();settings();});choice.setBackground(bg(mode.equals(saved)?outgoing:surface,16));themes.addView(choice,new LinearLayout.LayoutParams(0,-2,1));}LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(12);tp.bottomMargin=dp(30);form.addView(themes,tp);
        form.addView(text("YOUR RELAY",10,muted,true));TextView description=text("Keep Tailscale connected on this phone and your computer.",13,muted,false);pad(description,0,14);form.addView(description);
        EditText address=field("https://computer.tailnet.ts.net");address.setText(base);address.setSingleLine();address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);form.addView(address);
        EditText secret=field(token.isEmpty()?"Access token":"Access token · blank keeps saved token");secret.setSingleLine();secret.setInputType(129);secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);LinearLayout.LayoutParams secretLp=new LinearLayout.LayoutParams(-1,-2);secretLp.topMargin=dp(12);form.addView(secret,secretLp);
        TextView error=text("",12,errorColor,false);pad(error,0,12);form.addView(error);
        TextView save=action("Connect to relay  →",()->{try {
            URI uri=URI.create(address.getText().toString().trim());
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||!uri.getHost().endsWith(".ts.net")||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new Exception("Use the HTTPS .ts.net address printed by Tailscale Serve, with no path.");
            String nextBase="https://"+uri.getHost(),nextToken=secret.getText().toString().trim();
            if(nextToken.isEmpty()&&!base.isEmpty()&&!base.equals(nextBase))throw new Exception("Enter the access token when changing servers.");
            if(nextToken.isEmpty())nextToken=token;if(nextToken.length()<32)throw new Exception("Paste your server token (at least 32 characters).");
            SecretStore.save(this,nextToken);token=nextToken;base=nextBase;api=new RelayApi(base,token);getSharedPreferences("relay",0).edit().putString("url",base).apply();images.close();images=new ImageLoader();chats=new JSONArray();calls=new JSONArray();contacts=new JSONArray();drafts.clear();seenCalls.clear();accountId="";accountName="You";firstSync=true;inbox();
        }catch(Exception e){error.setText(e.getMessage()==null?"Could not save connection":e.getMessage());}});save.setBackground(bg(green,18));save.setTextColor(dark?paper:Color.WHITE);form.addView(save,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView note=text("PRIVATE BY DESIGN\n\nPhotos and videos travel through your authenticated relay. Profile photos follow WhatsApp privacy settings.\n\nCalls open in the official WhatsApp app. Incoming call alerts work while Chat Relay is open; background ringing and answering are not supported.\n\nInter font · SIL Open Font License",12,muted,false);pad(note,0,28);form.addView(note);
    }
    private void refresh() {
        if(screen.equals("settings")||fetching||token.isEmpty())return;
        fetching=true;int version=generation;String page=screen,id=chatId;RelayApi client=api;
        worker.execute(()->{
            try {
                JSONObject state=client.json("GET","/api/status",null);
                if(!state.optBoolean("ready")){runOnUiThread(()->{if(version==generation){fetching=false;banner("WhatsApp: "+state.optString("state")+" · Check your server terminal");}});return;}
                JSONObject data=client.json("GET",page.equals("chat")?"/api/chats/"+RelayApi.encode(id)+"/messages":"/api/chats",null);
                JSONArray callData=null;try{callData=client.json("GET","/api/calls",null).optJSONArray("calls");}catch(Exception ignored){}
                JSONArray events=callData;
                runOnUiThread(()->{
                    if(version!=generation||isDestroyed())return;fetching=false;firstSync=false;if(!sending&&!stickyError&&status!=null)status.setVisibility(View.GONE);
                    JSONObject account=state.optJSONObject("account");if(account!=null){accountId=account.optString("id");accountName=account.optString("name","You");}
                    if(events!=null)calls=events;
                    if(page.equals("inbox")){chats=data.optJSONArray("chats");if(chats==null)chats=new JSONArray();renderTabs();renderInbox();if(myAvatar!=null&&!accountId.isEmpty()){myAvatar.removeAllViews();myAvatar.addView(avatar(accountName,accountId,40),new FrameLayout.LayoutParams(-1,-1));}}
                    else {JSONArray messages=data.optJSONArray("messages");if(messages!=null)renderMessages(messages);}
                    for(int i=calls.length()-1;i>=0;i--){JSONObject call=calls.optJSONObject(i);String callId=call.optString("id");if(seenCalls.add(callId)&&call.optBoolean("canReject")&&active&&(incomingDialog==null||!incomingDialog.isShowing()))showCall(call);}
                });
            }catch(Exception e){runOnUiThread(()->{if(version==generation){fetching=false;banner(e.getMessage()==null?"Connection failed. Check Tailscale and your server.":e.getMessage());}});}
        });
    }
    private void mutate(String path,JSONObject body,boolean message,Result callback) {
        if(message&&sending)return;
        int version=generation;RelayApi client=api;if(message){sending=true;if(composer!=null)composer.setEnabled(false);banner("Sending…");}
        worker.execute(()->{
            JSONObject result=null;String error=null;
            try{result=client.json("POST",path,body);}catch(Exception e){error=e.getMessage()==null?"Request failed":e.getMessage();}
            JSONObject response=result;String failure=error;
            runOnUiThread(()->{
                if(version!=generation||isDestroyed())return;
                if(message){sending=false;if(composer!=null)composer.setEnabled(true);}
                if(failure!=null){stickyError=true;banner((message?"Send unconfirmed. Check the conversation before retrying. ":"")+failure);}
                else {stickyError=false;if(status!=null)status.setVisibility(View.GONE);callback.done(response);}
            });
        });
    }
}
