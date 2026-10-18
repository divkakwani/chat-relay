package dev.chatrelay;

import android.content.Context;
import android.graphics.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;

/** Uses synthetic contacts on the isolated CI emulator; never a linked account. */
public class AppearanceTest {
    private Context context;
    private static Field field(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f; }
    private static Object call(MainActivity a,String name) throws Exception { Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(a); }
    @Before public void clearSettings() { context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.getSharedPreferences("relay",0).edit().clear().putString("theme","light").commit(); }
    private void seed(MainActivity activity) throws Exception {
        String[] names={"Ananya Sharma","Rohan Mehta","Ishaan Patel","Meera Kapoor","Arjun Verma","Sara Nair"};
        String[] previews={"Found a lovely café for Saturday ☕","Sending it over in a minute","That sounds like a great plan!","The photos turned out so well","See you at the trailhead","Thanks for the recommendation :)"};
        JSONArray chats=new JSONArray();long now=System.currentTimeMillis()/1000;
        for(int i=0;i<names.length;i++)chats.put(new JSONObject().put("id",(10000+i)+"@c.us").put("name",names[i]).put("isGroup",false).put("unread",i==0?2:i==2?1:0).put("timestamp",now-i*3900).put("lastMessage",new JSONObject().put("text",previews[i]).put("fromMe",i==1||i==4)));
        chats.put(new JSONObject().put("id","123-456@g.us").put("name","Weekend plans").put("isGroup",true).put("timestamp",now).put("lastMessage",new JSONObject().put("text","Saturday works for everyone")));
        field("chats").set(activity,chats);field("firstSync").set(activity,false);call(activity,"inbox");
        ((TextView)field("status").get(activity)).setVisibility(android.view.View.GONE);
    }
    private void capture(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(400);
        Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
        try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name+".png"))) { screenshot.compress(Bitmap.CompressFormat.PNG,100,out); }
        screenshot.recycle();
    }
    @Test public void themesAndTabsRenderAndSurviveRecreation() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{try{seed(a);assertEquals(6,((ListView)field("list").get(a)).getAdapter().getCount());assertFalse(field("dark").getBoolean(a));}catch(Exception e){throw new RuntimeException(e);}});capture("inbox-light");
            scenario.onActivity(a->{try{context.getSharedPreferences("relay",0).edit().putString("theme","dark").commit();call(a,"configureTheme");call(a,"inbox");((TextView)field("status").get(a)).setVisibility(android.view.View.GONE);assertTrue(field("dark").getBoolean(a));}catch(Exception e){throw new RuntimeException(e);}});capture("inbox-dark");
            scenario.onActivity(a->{try{field("tab").set(a,"Groups");call(a,"renderInbox");assertEquals(1,((ListView)field("list").get(a)).getAdapter().getCount());}catch(Exception e){throw new RuntimeException(e);}});
            scenario.recreate();scenario.onActivity(a->{try{assertTrue(field("dark").getBoolean(a));assertEquals("Groups",field("tab").get(a));}catch(Exception e){throw new RuntimeException(e);}});
            scenario.onActivity(a->{try{context.getSharedPreferences("relay",0).edit().putString("theme","system").commit();call(a,"configureTheme");boolean systemDark=(a.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;assertEquals(systemDark,field("dark").getBoolean(a));}catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    @Test public void conversationAndDraftNavigateWithoutLosingText() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{try{
                seed(a);Method open=MainActivity.class.getDeclaredMethod("openChat",String.class,String.class);open.setAccessible(true);open.invoke(a,"10000@c.us","Ananya Sharma");
                long now=System.currentTimeMillis()/1000;
                JSONArray messages=new JSONArray().put(new JSONObject().put("id","hello").put("text","Found a lovely café for Saturday ☕").put("timestamp",now-120).put("fromMe",false)).put(new JSONObject().put("id","reply").put("text","Sounds perfect. See you there!").put("timestamp",now).put("fromMe",true).put("ack",3));
                Method render=MainActivity.class.getDeclaredMethod("renderMessages",JSONArray.class);render.setAccessible(true);render.invoke(a,messages);
                ((TextView)field("status").get(a)).setVisibility(android.view.View.GONE);
                ((EditText)field("composer").get(a)).setText("Remember this draft");
            }catch(Exception e){throw new RuntimeException(e);}});capture("conversation-light");
            scenario.onActivity(a->{try{call(a,"inbox");Method open=MainActivity.class.getDeclaredMethod("openChat",String.class,String.class);open.setAccessible(true);open.invoke(a,"10000@c.us","Ananya Sharma");assertEquals("Remember this draft",((EditText)field("composer").get(a)).getText().toString());}catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    @Test public void contactPickerPrefersNamesAndKeepsNumberSearch() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{try{
                field("contacts").set(a,new JSONArray()
                    .put(new JSONObject().put("id","1@c.us").put("name","+91 12345 67890").put("number","911234567890"))
                    .put(new JSONObject().put("id","2@c.us").put("name","Zoya"))
                    .put(new JSONObject().put("id","3@c.us").put("name","+91 99999 99999").put("number","919999999999")));
                field("chats").set(a,new JSONArray()
                    .put(new JSONObject().put("id","1@c.us").put("name","Ananya"))
                    .put(new JSONObject().put("id","4@c.us").put("name","+91 88888 88888"))
                    .put(new JSONObject().put("id","5@g.us").put("name","Friends").put("isGroup",true)));
                Method choices=MainActivity.class.getDeclaredMethod("contactChoices",String.class);choices.setAccessible(true);
                java.util.List<?> named=(java.util.List<?>)choices.invoke(a,"");
                assertEquals(2,named.size());assertEquals("Ananya",((JSONObject)named.get(0)).optString("name"));
                assertEquals("Zoya",((JSONObject)named.get(1)).optString("name"));
                assertEquals(1,((java.util.List<?>)choices.invoke(a,"99999")).size());
                assertEquals(1,((java.util.List<?>)choices.invoke(a,"12345")).size());
                assertEquals(1,((java.util.List<?>)choices.invoke(a,"zoya")).size());
            }catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    @Test public void imageDecodingBoundsMemoryAndRejectsInvalidData() {
        Bitmap original=Bitmap.createBitmap(2400,1600,Bitmap.Config.ARGB_8888);original.eraseColor(0xff80976d);
        ByteArrayOutputStream out=new ByteArrayOutputStream();original.compress(Bitmap.CompressFormat.PNG,100,out);original.recycle();
        Bitmap thumbnail=ImageLoader.decode(out.toByteArray(),600);assertNotNull(thumbnail);assertTrue(thumbnail.getWidth()<=600);assertTrue(thumbnail.getHeight()<=600);thumbnail.recycle();
        assertNull(ImageLoader.decode(new byte[]{1,2,3,4},600));
    }

}
