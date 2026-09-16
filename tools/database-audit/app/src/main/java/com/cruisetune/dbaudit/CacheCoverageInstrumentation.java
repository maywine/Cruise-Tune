package com.cruisetune.dbaudit;

import android.app.*;
import android.os.Bundle;
import org.json.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static com.cruisetune.dbaudit.NetworkAuditInstrumentation.call;

/** Reads completed cache spans, never opens an upstream data source or exports track identifiers. */
public final class CacheCoverageInstrumentation extends Instrumentation {
    private final CountDownLatch ready=new CountDownLatch(1);private Bundle args;private ClassLoader loader;
    public void onCreate(Bundle args){this.args=args;start();}
    public void callApplicationOnCreate(Application app){ready.countDown();}
    public void onStart(){JSONObject report=new JSONObject();Object stream=null,offline=null;
        try {
            if(!ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException("not ready");
            String sha=AuditInstrumentation.sha(new File(getTargetContext().getApplicationInfo().sourceDir));
            if(!sha.equals(args.getString("expectedApkSha")))throw new IllegalStateException("APK mismatch");
            loader=getTargetContext().getClassLoader();Object app=getTargetContext().getApplicationContext();
            Object db=call(app,"getDatabase"),snapshot=call(db,"restore");List<?> entries=(List<?>)call(snapshot,"getEntries");
            int current=(Integer)call(snapshot,"getIndex");report.put("currentIndex",current);
            // Open caches directly: do not construct MediaCache's DownloadManager.
            Class<?> cacheClass=loader.loadClass("androidx.media3.datasource.cache.SimpleCache");
            Class<?> evictorClass=loader.loadClass("androidx.media3.datasource.cache.CacheEvictor");
            Class<?> providerClass=loader.loadClass("androidx.media3.database.DatabaseProvider");
            Object provider=call(app,"getMediaDatabase");
            Object evictor=loader.loadClass("androidx.media3.datasource.cache.NoOpCacheEvictor").getConstructor().newInstance();
            stream=cacheClass.getConstructor(File.class,evictorClass,providerClass).newInstance(new File(getTargetContext().getFilesDir(),"stream-cache"),evictor,provider);
            offline=cacheClass.getConstructor(File.class,evictorClass,providerClass).newInstance(new File(getTargetContext().getFilesDir(),"offline-cache"),evictor,provider);
            JSONArray tracks=new JSONArray();String hint=args.getString("titleHint","");
            List<String> matchingKeys=new ArrayList<>();
            for(int i=0;i<entries.size();i++) {
                Object track=call(entries.get(i),"getTrack");boolean named=!hint.isEmpty() && ((String)call(track,"getTitle")).contains(hint);
                if(!named && Math.abs(i-current)>1)continue;
                String key=(String)call(track,"getCacheKey");long size=(Long)call(track,"getSize");
                if(named)matchingKeys.add(key);
                JSONObject row=new JSONObject().put("queueIndex",i).put("matchesHint",named).put("declaredSize",size);
                row.put("stream",coverage(stream,key,size)).put("offline",coverage(offline,key,size));tracks.put(row);
            }
            report.put("tracks",tracks);
            if("true".equals(args.getString("clearHintStreamingCache"))) {
                if(hint.isEmpty() || matchingKeys.size()!=1)throw new IllegalStateException("one explicit target required");
                String key=matchingKeys.get(0);
                if((Long)call(offline,"getCachedBytes",key,0L,-1L)>0)throw new IllegalStateException("offline cache must remain untouched");
                call(stream,"removeResource",key);
                report.put("clearedTargetStreamingBytesRemaining",call(stream,"getCachedBytes",key,0L,-1L));
            }
            report.put("success",true);
        }catch(Throwable e){try{report.put("success",false).put("errorClass",e.getClass().getSimpleName());}catch(Exception ignored){}}
        finally {for(Object cache:new Object[]{stream,offline})if(cache!=null)try{call(cache,"release");}catch(Throwable ignored){}}
        Bundle b=new Bundle();b.putString("report",report.toString());finish(-1,b);
    }
    private JSONObject coverage(Object cache,String key,long expected)throws Throwable {
        Object metadata=call(cache,"getContentMetadata",key);
        long length=(Long)call(metadata,"get","exo_len",-1L);
        JSONObject result=new JSONObject().put("metadataLength",length).put("cachedBytes",call(cache,"getCachedBytes",key,0L,-1L));
        List<long[]> spans=new ArrayList<>();
        for(Object span:(Set<?>)call(cache,"getCachedSpans",key))spans.add(new long[]{span.getClass().getField("position").getLong(span),span.getClass().getField("length").getLong(span)});
        spans.sort(Comparator.comparingLong(x->x[0]));long cursor=0;JSONArray holes=new JSONArray();
        for(long[] span:spans){if(span[0]>cursor)holes.put(new JSONObject().put("start",cursor).put("length",span[0]-cursor));cursor=Math.max(cursor,span[0]+span[1]);}
        if(expected>cursor)holes.put(new JSONObject().put("start",cursor).put("length",expected-cursor));
        return result.put("spanCount",spans.size()).put("complete",expected>0 && (Boolean)call(cache,"isCached",key,0L,expected)).put("holes",holes);
    }
}
