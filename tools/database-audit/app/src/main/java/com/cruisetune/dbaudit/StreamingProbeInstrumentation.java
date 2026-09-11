package com.cruisetune.dbaudit;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static com.cruisetune.dbaudit.NetworkAuditInstrumentation.call;

/** Tests a known streaming protocol with the app's own authorized identity. No credential export. */
public final class StreamingProbeInstrumentation extends Instrumentation {
    private final CountDownLatch ready=new CountDownLatch(1);
    private Bundle arguments;
    private ClassLoader loader;
    @Override public void onCreate(Bundle args){arguments=args;start();}
    @Override public void callApplicationOnCreate(Application app){ready.countDown();}
    private Object suspendCall(Object receiver,String name,Object...args)throws Throwable {
        Class<?> fn=loader.loadClass("kotlin.jvm.functions.Function2");
        Object block=Proxy.newProxyInstance(loader,new Class<?>[]{fn},(p,m,a)->{
            if(!m.getName().equals("invoke"))return "StreamingProbe";
            Object[] full=Arrays.copyOf(args,args.length+1);full[args.length]=a[1];return call(receiver,name,full);
        });
        Object empty=loader.loadClass("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
        try{return loader.loadClass("kotlinx.coroutines.BuildersKt").getMethod("runBlocking",loader.loadClass("kotlin.coroutines.CoroutineContext"),fn).invoke(null,empty,block);}
        catch(InvocationTargetException e){throw e.getCause();}
    }
    @Override public void onStart(){
        JSONObject report=new JSONObject();Object response=null;
        try{
            if(!ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException("app not ready");
            String sha=AuditInstrumentation.sha(new File(getTargetContext().getApplicationInfo().sourceDir));
            if(!sha.equals(arguments.getString("expectedApkSha")))throw new IllegalStateException("APK mismatch");
            loader=getTargetContext().getClassLoader();Object app=getTargetContext().getApplicationContext();
            Object db=call(app,"getDatabase"),snapshot=call(db,"restore");
            int index=(Integer)call(snapshot,"getIndex");List<?> entries=(List<?>)call(snapshot,"getEntries");
            Object track=call(entries.get(index),"getTrack"),source=null;
            for(Object s:(List<?>)call(db,"sources"))if(call(s,"getId").equals(call(track,"getSourceId")))source=s;
            if(source==null || !"QUARK_OPEN".equals(String.valueOf(call(source,"getKind"))))throw new IllegalStateException("Token source required");
            Object connections=call(app,"getOpenConnections"),sessions=call(connections,"getSessions");
            Object session=suspendCall(sessions,"fresh",call(source,"getAccountId"));
            Map<?,?> headers=(Map<?,?>)suspendCall(call(connections,"getBroker"),"sign",session,"GET","/file");
            String method=arguments.getString("method","streaming");
            if(!Set.of("streaming","download","list").contains(method))throw new IllegalArgumentException("unsupported probe");
            Object ub=loader.loadClass("okhttp3.HttpUrl$Builder").getConstructor().newInstance();
            call(ub,"scheme","https");call(ub,"host","open-api-drive.quark.cn");call(ub,"encodedPath","/file");
            Map<String,String> params=new LinkedHashMap<>();
            params.put("method",method);
            if(method.equals("list")) {
                params.put("parent_fid","0");params.put("order_by","3");params.put("desc","1");
                params.put("category","");params.put("source","");params.put("ex_source","");params.put("list_all","0");
                params.put("page_size","100");params.put("page_index","0");
            } else {
                params.put("group_by","source");params.put("fid",(String)call(track,"getFileId"));
                params.put("resolution","low,normal,high,super,2k,4k");params.put("support","dolby_vision");
            }
            params.put("access_token",(String)call(session,"getAccessToken"));params.put("device_id",(String)call(session,"getDeviceId"));
            params.put("req_id",UUID.randomUUID().toString());params.put("platform","tv");
            for(Map.Entry<String,String> e:params.entrySet())call(ub,"addQueryParameter",e.getKey(),e.getValue());
            Object rb=loader.loadClass("okhttp3.Request$Builder").getConstructor().newInstance();call(rb,"url",call(ub,"build"));
            for(Map.Entry<?,?> e:headers.entrySet())call(rb,"header",e.getKey(),e.getValue());
            call(rb,"header","Accept","application/json, text/plain, */*");
            call(rb,"header","User-Agent","Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1");
            Object cb=call(call(app,"getHttp"),"newBuilder");call(cb,"followRedirects",false);call(cb,"followSslRedirects",false);
            response=call(call(call(cb,"build"),"newCall",call(rb,"build")),"execute");
            report.put("endpoint","/file").put("method",method).put("usesExistingAgentToken",true).put("fileSizeBytes",call(track,"getSize"));
            int http=(Integer)call(response,"code");report.put("httpStatus",http);
            Object peek=call(response,"peekBody",65536L);JSONObject json;
            try{json=new JSONObject((String)call(peek,"string"));}finally{call(peek,"close");}
            for(String key:new String[]{"status","code","errno"})if(json.opt(key) instanceof Number)report.put(key,json.get(key));
            String message=json.optString("error_info",json.optString("message",""));
            if(message.matches("[a-zA-Z_ ,.:()\\u4e00-\\u9fff，。]{1,160}") && !message.matches(".*[a-zA-Z_]{24,}.*"))report.put("serviceMessage",message);
            JSONObject data=json.optJSONObject("data");
            report.put("hasAudioInfo",data!=null && data.has("audio_info")).put("hasVideoInfo",data!=null && data.has("video_info"));
            report.put("responseAccepted",http>=200 && http<300 && Set.of(0,200).contains(json.optInt("status",-1)) && json.optInt("errno",0)==0);
            report.put("fileCount",data!=null && data.optJSONArray("files")!=null ? data.getJSONArray("files").length() : 0);
            report.put("audioPlaybackVerified",false);
        }catch(Throwable e){try{report.put("probeErrorClass",e.getClass().getSimpleName());}catch(Exception ignored){}}
        finally{if(response!=null)try{call(response,"close");}catch(Throwable ignored){}}
        Bundle b=new Bundle();b.putString("report",report.toString());finish(-1,b);
    }
}
