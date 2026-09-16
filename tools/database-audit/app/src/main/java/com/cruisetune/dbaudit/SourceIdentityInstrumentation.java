package com.cruisetune.dbaudit;
import android.app.*;
import android.os.Bundle;
import org.json.*;
import java.util.*;
import java.io.File;
import java.util.concurrent.*;
import static com.cruisetune.dbaudit.NetworkAuditInstrumentation.call;
public final class SourceIdentityInstrumentation extends Instrumentation {
    private final CountDownLatch ready=new CountDownLatch(1);private Bundle args;
    public void onCreate(Bundle args){this.args=args;start();}
    public void callApplicationOnCreate(Application app){ready.countDown();}
    public void onStart(){JSONObject report=new JSONObject();Object response=null;
        try {
            if(!ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException();
            if(!AuditInstrumentation.sha(new File(getTargetContext().getApplicationInfo().sourceDir)).equals(args.getString("expectedApkSha")))throw new IllegalStateException();
            Object app=getTargetContext().getApplicationContext(),db=call(app,"getDatabase"),vault=call(app,"getVault");
            Object web=null,token=null;
            for(Object s:(List<?>)call(db,"sources")){
                if("QUARK".equals(String.valueOf(call(s,"getKind"))))web=s;
                if("QUARK_OPEN".equals(String.valueOf(call(s,"getKind"))))token=s;
            }
            if(web==null || token==null)throw new IllegalStateException();
            String wr=(String)call(web,"getRootId"),tr=(String)call(token,"getRootId");
            report.put("sameRawRoot",wr.equals(tr)).put("tokenRootLength",tr.length()).put("webRootLength",wr.length());
            report.put("tokenRootHasPipe",tr.contains("|")).put("tokenRootContainsWebRoot",tr.contains(wr));
            String leaf=tr.substring(tr.lastIndexOf('|')+1);report.put("sameRootLeaf",wr.equals(leaf));
            JSONArray parts=new JSONArray();boolean encodedMatch=false;
            for(String part:tr.split("\\|")){
                JSONObject shape=new JSONObject().put("length",part.length()).put("hex",part.matches("[0-9a-fA-F]+"));parts.put(shape);
                try{byte[] decoded=Base64.getUrlDecoder().decode(part);String asText=new String(decoded,java.nio.charset.StandardCharsets.UTF_8);if(asText.contains(wr))encodedMatch=true;
                    StringBuilder hex=new StringBuilder();for(byte b:decoded)hex.append(String.format("%02x",b));if(hex.toString().contains(wr))encodedMatch=true;
                }catch(Exception ignored){}
            }
            report.put("tokenRootParts",parts).put("encodedRootMatches",encodedMatch);
            try{String decoded=new String(Base64.getUrlDecoder().decode(tr));report.put("decodedRootContainsWebRoot",decoded.contains(wr));}catch(Exception ignored){}
            List<?> webTracks=(List<?>)call(db,"tracks",call(web,"getId")),tokenTracks=(List<?>)call(db,"tracks",call(token,"getId"));
            int paired=0,sameId=0,sameLeaf=0,sameVersion=0;Set<Integer> tokenLengths=new HashSet<>();
            for(Object w:webTracks)for(Object t:tokenTracks)if(call(w,"getRelativePath").equals(call(t,"getRelativePath")) && call(w,"getSize").equals(call(t,"getSize"))){
                paired++;String wi=(String)call(w,"getFileId"),ti=(String)call(t,"getFileId");tokenLengths.add(ti.length());
                if(wi.equals(ti))sameId++;if(wi.equals(ti.substring(ti.lastIndexOf('|')+1)))sameLeaf++;
                if(call(w,"getVersion").equals(call(t,"getVersion")))sameVersion++;
            }
            report.put("matchedMetadataPairs",paired).put("sameFileId",sameId).put("sameFileLeaf",sameLeaf).put("sameVersion",sameVersion).put("tokenFileIdLengths",new JSONArray(tokenLengths));
            String stored=(String)call(vault,"get","open:account:"+call(token,"getAccountId"));
            String user=new JSONObject(stored).getString("userId");
            String cookie=(String)call(vault,"get",call(web,"getAccountId"));
            boolean uidMatch=false;JSONArray cookieNames=new JSONArray();
            for(String part:cookie.split(";")){String[] p=part.trim().split("=",2);cookieNames.put(p[0]);if(p.length==2 && p[0].equals("__uid") && p[1].equals(user))uidMatch=true;}
            report.put("cookieUidMatchesToken",uidMatch).put("cookieNames",cookieNames);
            ClassLoader cl=getTargetContext().getClassLoader();Object rb=cl.loadClass("okhttp3.Request$Builder").getConstructor().newInstance();
            call(rb,"url","https://drive-pc.quark.cn/1/clouddrive/config?pr=ucpro&fr=pc");call(rb,"header","Cookie",call(vault,"get",call(web,"getAccountId")));
            call(rb,"header","User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/143.0.0.0 Safari/537.36");
            Object cb=call(call(app,"getHttp"),"newBuilder");call(cb,"followRedirects",false);
            response=call(call(call(cb,"build"),"newCall",call(rb,"build")),"execute");
            report.put("webIdentityHttpStatus",call(response,"code"));
            JSONObject info=new JSONObject((String)call(call(response,"body"),"string"));
            report.put("status",info.optInt("status")).put("code",info.optInt("code"));
            JSONArray fields=new JSONArray(),matches=new JSONArray();walk(info,"",user,fields,matches);
            report.put("identityFields",fields).put("matchingUserIdPaths",matches);
        }catch(Throwable e){try{report.put("errorClass",e.getClass().getSimpleName());}catch(Exception ignored){}}
        finally{if(response!=null)try{call(response,"close");}catch(Throwable ignored){}}
        Bundle b=new Bundle();b.putString("report",report.toString());finish(-1,b);
    }
    private void walk(JSONObject o,String path,String user,JSONArray fields,JSONArray matches)throws JSONException{
        for(Iterator<String> it=o.keys();it.hasNext();){String key=it.next();
            Object value=o.get(key);String p=path.isEmpty()?key:path+"."+key;
            if(value instanceof JSONObject){walk((JSONObject)value,p,user,fields,matches);continue;}
            if(key.matches("(?i).*(uid|user.?id|account.?id).*"))fields.put(p+":"+value.getClass().getSimpleName());
            if(user.equals(String.valueOf(value)))matches.put(p);
        }
    }
}
