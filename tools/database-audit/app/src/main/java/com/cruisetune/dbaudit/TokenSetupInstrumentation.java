package com.cruisetune.dbaudit;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.webkit.CookieManager;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static com.cruisetune.dbaudit.NetworkAuditInstrumentation.call;

/** Mutates only the explicitly selected emulator test app; never part of the player APK. */
public final class TokenSetupInstrumentation extends Instrumentation {
    private final CountDownLatch ready = new CountDownLatch(1);
    private Bundle arguments;
    private ClassLoader loader;
    @Override public void onCreate(Bundle args) { arguments=args; start(); }
    @Override public void callApplicationOnCreate(Application app) { ready.countDown(); }
    @Override public void onStart() {
        JSONObject report=new JSONObject();
        try {
            if(!"true".equals(arguments.getString("confirmTokenSetup")))throw new IllegalStateException("explicit setup argument required");
            if(!ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException("application not ready");
            String sha=AuditInstrumentation.sha(new File(getTargetContext().getApplicationInfo().sourceDir));
            if(!sha.equals(arguments.getString("expectedApkSha")))throw new IllegalStateException("APK mismatch");
            loader=getTargetContext().getClassLoader();Object app=getTargetContext().getApplicationContext();
            SharedPreferences prefs=(SharedPreferences)call(app,"getPreferences");String account=prefs.getString("quarkDirectAccount",null);
            if(account==null)throw new IllegalStateException("existing Token grant required");
            Object helper=call(app,"getDatabase"), connections=call(app,"getOpenConnections"), api=call(connections,"provider",account);
            SQLiteDatabase db=(SQLiteDatabase)call(helper,"getWritableDatabase");
            Set<String> oldSources=new HashSet<>(), cookies=new HashSet<>(), tokenSources=new HashSet<>();
            for(Object source:(List<?>)call(helper,"sources")) {
                String kind=String.valueOf(call(source,"getKind"));
                if(kind.equals("QUARK")) {oldSources.add((String)call(source,"getId"));cookies.add((String)call(source,"getAccountId"));}
                if(kind.equals("QUARK_OPEN") && account.equals(call(source,"getAccountId")))tokenSources.add((String)call(source,"getId"));
            }
            Object snapshot=call(helper,"restore");List<?> queue=(List<?>)call(snapshot,"getEntries");
            if(queue.isEmpty())throw new IllegalStateException("prepare Token queue before removing old web sources");
            for(Object entry:queue)if(!tokenSources.contains(call(call(entry,"getTrack"),"getSourceId")))
                throw new IllegalStateException("current queue must use Token sources only");
            Set<Long> oldRevisions=new HashSet<>();
            try(Cursor c=db.rawQuery("SELECT revision,payload FROM queue_items",null)) {
                while(c.moveToNext())if(oldSources.contains(new JSONObject(c.getString(1)).getString("sourceId")))oldRevisions.add(c.getLong(0));
            }
            int deletedTracks=0;
            db.beginTransaction();
            try {
                for(long revision:oldRevisions) {
                    db.delete("checkpoints","revision=?",new String[]{Long.toString(revision)});
                    db.delete("queue_items","revision=?",new String[]{Long.toString(revision)});
                }
                for(String source:oldSources) {
                    deletedTracks+=db.delete("tracks","source_id=?",new String[]{source});
                    db.delete("sources","id=? AND kind='QUARK'",new String[]{source});
                }
                db.execSQL("UPDATE checkpoints SET intent=0");db.setTransactionSuccessful();
            } finally {db.endTransaction();}
            String recent=prefs.getString("quarkAccount",null);if(recent!=null)cookies.add(recent);
            for(String key:getTargetContext().getSharedPreferences("encrypted-credentials",0).getAll().keySet())
                if(key.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))cookies.add(key);
            Object vault=call(app,"getVault");for(String cookie:cookies)call(vault,"remove",cookie);
            if(!prefs.edit().remove("quarkAccount").commit())throw new IllegalStateException("preference removal failed");
            CountDownLatch cleared=new CountDownLatch(1);
            runOnMainSync(()->CookieManager.getInstance().removeAllCookies(ok->{CookieManager.getInstance().flush();cleared.countDown();}));
            if(!cleared.await(10,TimeUnit.SECONDS))throw new IllegalStateException("WebView cookie cleanup timeout");
            Object restored=call(helper,"restore");List<?> after=(List<?>)call(restored,"getEntries");
            boolean sameQueue=after.size()==queue.size();
            for(int i=0;sameQueue && i<queue.size();i++)sameQueue=call(call(queue.get(i),"getTrack"),"getId").equals(call(call(after.get(i),"getTrack"),"getId"));
            report.put("queueOrderPreserved",sameQueue).put("positionPreserved",call(snapshot,"getPositionMs").equals(call(restored,"getPositionMs")))
                .put("indexPreserved",call(snapshot,"getIndex").equals(call(restored,"getIndex")));
            report.put("tokenSources",tokenSources.size()).put("removedWebSources",oldSources.size()).put("removedWebTracks",deletedTracks).put("queueSize",queue.size())
                .put("webAccountPresent",prefs.contains("quarkAccount")).put("tokenAccountPresent",prefs.contains("quarkDirectAccount")).put("success",true);
        } catch(Throwable e) {
            try {report.put("success",false).put("errorClass",e.getClass().getSimpleName());
                // Only locally generated fixed diagnostic messages, never transport bodies.
                if(e instanceof IllegalStateException)report.put("setupFailure",e.getMessage());
            }catch(Exception ignored){}
        }
        Bundle result=new Bundle();result.putString("report",report.toString());finish(-1,result);
    }
}
