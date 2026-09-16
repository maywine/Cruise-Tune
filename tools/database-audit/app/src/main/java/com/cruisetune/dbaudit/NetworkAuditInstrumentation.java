package com.cruisetune.dbaudit;

import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the installed release's uncached DataSource. Reports no credentials or signed URLs. */
public final class NetworkAuditInstrumentation extends Instrumentation {
    private final CountDownLatch ready = new CountDownLatch(1);
    private final JSONArray events = new JSONArray();
    private Bundle arguments;
    private ClassLoader loader;
    private boolean pcHost;
    private boolean desktopUa;
    private boolean openListShape;
    @Override public void onCreate(Bundle args) { arguments=args; start(); }
    @Override public void callApplicationOnCreate(Application app) { ready.countDown(); }
    @Override public void onStart() {
        JSONObject report=new JSONObject(); Object source=null;
        try {
            if(!ready.await(10, TimeUnit.SECONDS))throw new IllegalStateException("application not ready");
            String hash=AuditInstrumentation.sha(new File(getTargetContext().getApplicationInfo().sourceDir));
            if(!hash.equals(arguments.getString("expectedApkSha")))throw new IllegalStateException("APK hash mismatch");
            report.put("apkSha256",hash);
            loader=getTargetContext().getClassLoader();pcHost="true".equals(arguments.getString("pcHost"));
            desktopUa="true".equals(arguments.getString("desktopUa"));
            report.put("desktopUserAgentOverride",desktopUa);
            openListShape="true".equals(arguments.getString("openListShape"));
            report.put("openListRequestShape",openListShape);
            Object app=getTargetContext().getApplicationContext();
            Object database=call(app,"getDatabase"), snapshot=call(database,"restore");
            List<?> entries=(List<?>)call(snapshot,"getEntries"); int index=(Integer)call(snapshot,"getIndex");
            Object track=call(entries.get(index),"getTrack");
            String hint=arguments.getString("trackTitleHint","");
            if(!hint.isEmpty() && !((String)call(track,"getTitle")).contains(hint))throw new IllegalStateException("selected track differs from requested case");
            String sourceId=(String)call(track,"getSourceId");Object kind=null; String account=null;
            for(Object item:(List<?>)call(database,"sources"))if(sourceId.equals(call(item,"getId"))){kind=call(item,"getKind");account=(String)call(item,"getAccountId");}
            report.put("sourceKind",String.valueOf(kind)).put("selectedIndex",index).put("pcHostOverride",pcHost).put("fileSizeBytes",call(track,"getSize"));
            android.content.SharedPreferences prefs=(android.content.SharedPreferences)call(app,"getPreferences");
            report.put("sourceUsesCurrentWebAccount",Objects.equals(account,prefs.getString("quarkAccount",null)));
            report.put("hasDirectAccount",prefs.contains("quarkDirectAccount"));
            Object library=call(app,"getLibrary");
            set(library,"client",trace(field(library,"client")));
            Object connections=call(app,"getOpenConnections");
            set(connections,"apiTransport",trace(field(connections,"apiTransport")));
            if("QUARK_OPEN".equals(String.valueOf(kind))) {
                Object direct=call(connections,"getDirect");set(direct,"transport",trace(field(direct,"transport")));
            }
            Object streaming=trace(call(app,"getStreamHttp"));
            Method lazy=loader.loadClass("kotlin.LazyKt").getMethod("lazyOf",Object.class);lazy.setAccessible(true);
            set(app,"streamHttp$delegate",lazy.invoke(null,streaming));
            source=loader.loadClass("com.cruisetune.player.playback.ResolvingTrackSource")
                    .getConstructor(loader.loadClass("com.cruisetune.player.CruiseApplication")).newInstance(app);
            Object builder=loader.loadClass("androidx.media3.datasource.DataSpec$Builder").getConstructor().newInstance();
            call(builder,"setUri","cruisetune://track/"+call(track,"getId"));
            call(builder,"setKey",call(track,"getCacheKey"));call(builder,"setLength",65536L);
            call(source,"open",call(builder,"build"));
            byte[] buffer=new byte[4096];int total=0;
            while(total<65536){int n=(Integer)call(source,"read",buffer,0,Math.min(buffer.length,65536-total));if(n<0)break;total+=n;}
            report.put("bytesRead",total).put("success",total==65536);
        } catch(Throwable error) {
            error=unwrap(error);
            try {
                report.put("success",false).put("errorClass",error.getClass().getSimpleName());
                if(loader!=null){Object policy=loader.loadClass("com.cruisetune.player.playback.NetworkRetry").getField("INSTANCE").get(null);report.put("classifiedTransient",call(policy,"isTransient",error));}
                try { report.put("needsLogin",call(error,"getNeedsLogin")).put("retryable",call(error,"getRetryable")); } catch(Throwable ignored){}
            } catch(Throwable ignored){}
        } finally {
            if(source!=null)try{call(source,"close");}catch(Throwable ignored){}
            try{report.put("httpEvents",events);}catch(Exception ignored){}
            Bundle b=new Bundle();b.putString("report",report.toString());finish(-1,b);
        }
    }
    private Object trace(Object client) throws Throwable {
        Class<?> interceptor=loader.loadClass("okhttp3.Interceptor");Object builder=call(client,"newBuilder");
        if(openListShape)call(builder,"addInterceptor",Proxy.newProxyInstance(loader,new Class<?>[]{interceptor},(proxy,method,args)->{
            if(!method.getName().equals("intercept"))return objectMethod(proxy,method,args);
            Object chain=args[0],request=call(chain,"request"),url=call(request,"url");
            if("open-api-drive.quark.cn".equals(call(url,"host")) && "/open/v1/file/get_download_url".equals(call(url,"encodedPath"))){
                Object ub=call(url,"newBuilder");call(ub,"removeAllQueryParameters","platform");call(ub,"removeAllQueryParameters","device_id");
                Object rb=call(request,"newBuilder");call(rb,"url",call(ub,"build"));
                call(rb,"header","User-Agent","go-resty/3.0.0-beta.1 (https://resty.dev)");
                call(rb,"header","Accept","application/json, text/plain, */*");
                request=call(rb,"build");
            }
            return call(chain,"proceed",request);
        }));
        if(desktopUa)call(builder,"addInterceptor",Proxy.newProxyInstance(loader,new Class<?>[]{interceptor},(proxy,method,args)->{
            if(!method.getName().equals("intercept"))return objectMethod(proxy,method,args);
            Object chain=args[0],request=call(chain,"request"),url=call(request,"url");
            if("open-api-drive.quark.cn".equals(call(url,"host")) && "/open/v1/file/get_download_url".equals(call(url,"encodedPath"))){
                Object rb=call(request,"newBuilder");
                call(rb,"header","User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.56 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch");
                request=call(rb,"build");
            }
            return call(chain,"proceed",request);
        }));
        if(pcHost)call(builder,"addInterceptor",Proxy.newProxyInstance(loader,new Class<?>[]{interceptor},(proxy,method,args)->{
            if(!method.getName().equals("intercept"))return objectMethod(proxy,method,args);
            Object chain=args[0],request=call(chain,"request"),url=call(request,"url");
            if("drive.quark.cn".equals(call(url,"host")) && ((String)call(url,"encodedPath")).startsWith("/1/clouddrive/")){
                Object ub=call(url,"newBuilder");call(ub,"host","drive-pc.quark.cn");
                Object rb=call(request,"newBuilder");call(rb,"url",call(ub,"build"));request=call(rb,"build");
            }
            return call(chain,"proceed",request);
        }));
        call(builder,"addNetworkInterceptor",Proxy.newProxyInstance(loader,new Class<?>[]{interceptor},(proxy,method,args)->{
            if(!method.getName().equals("intercept"))return objectMethod(proxy,method,args);
            Object chain=args[0],request=call(chain,"request"),url=call(request,"url");
            String path=(String)call(url,"encodedPath"),host=(String)call(url,"host");
            Set<String> paths=Set.of("/1/clouddrive/file/download","/1/clouddrive/file/sort","/open/v1/file/get_download_url","/agent/v1/oauth/access_token/rotate");
            boolean api=paths.contains(path);JSONObject event=new JSONObject();
            event.put("operation",api?path:"media_range");
            event.put("host",Set.of("drive.quark.cn","drive-pc.quark.cn","open-api-drive.quark.cn").contains(host)?host:"media_host");
            synchronized(events){events.put(event);}
            try {
                Object response=call(chain,"proceed",request);int code=(Integer)call(response,"code");event.put("httpStatus",code);
                if(api){
                    Object peek=call(response,"peekBody",8192L);
                    try{
                        JSONObject json=new JSONObject((String)call(peek,"string"));
                        for(String key:new String[]{"status","code","errno"})if(json.opt(key) instanceof Number)event.put(key,json.get(key));
                        for(String key:new String[]{"message","errmsg","msg","error_msg","error","error_info"}) {
                            String value=json.optString(key,"");
                            if(json.optInt("errno")==23018 && value.matches("download file size limit\\[\\d+\\]"))
                                event.put("serviceMessage",value);
                        }
                    }catch(Exception ignored){}finally{call(peek,"close");}
                }
                Bundle progress=new Bundle();progress.putString("operation",api?path:"media_range");progress.putInt("httpStatus",code);sendStatus(1,progress);
                return response;
            } catch(Throwable error){event.put("networkErrorClass",unwrap(error).getClass().getSimpleName());throw error;}
        }));
        return call(builder,"build");
    }
    private static Object objectMethod(Object proxy,Method method,Object[] args){
        if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
        if(method.getName().equals("equals"))return proxy==args[0];
        return "NetworkAuditInterceptor";
    }
    static Object call(Object receiver,String name,Object...args) throws Throwable {
        for(Method method:receiver.getClass().getMethods()) {
            if(!method.getName().equals(name) || method.getParameterCount()!=args.length)continue;
            Class<?>[] types=method.getParameterTypes();boolean matches=true;
            for(int i=0;i<args.length;i++){
                Class<?> t=types[i];if(t==int.class)t=Integer.class;else if(t==long.class)t=Long.class;else if(t==boolean.class)t=Boolean.class;
                if(args[i]!=null && !t.isInstance(args[i])){matches=false;break;}
            }
            if(matches){method.setAccessible(true);try{return method.invoke(receiver,args);}catch(InvocationTargetException e){throw e.getCause();}}
        }
        throw new NoSuchMethodException(name);
    }
    private static Field member(Object receiver,String name) throws Exception {
        for(Class<?> type=receiver.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private static Object field(Object receiver,String name) throws Exception{return member(receiver,name).get(receiver);}
    private static void set(Object receiver,String name,Object value)throws Exception{member(receiver,name).set(receiver,value);}
    private static Throwable unwrap(Throwable e){while(e.getCause()!=null && (e instanceof InvocationTargetException || e instanceof UndeclaredThrowableException))e=e.getCause();return e;}
}
