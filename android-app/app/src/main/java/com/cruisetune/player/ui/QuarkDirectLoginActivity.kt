package com.cruisetune.player.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.readableError
import com.cruisetune.player.data.open.DirectQuarkAuth
import com.cruisetune.player.ui.Design.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener

/** Render the live QR produced by Quark's own page; no independent QR token protocol. */
class QuarkDirectLoginActivity : CruiseActivity() {
    private val app get() = application as CruiseApplication
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var web: WebView
    private var challenge: DirectQuarkAuth.Challenge? = null
    private var job: Job? = null
    private var generation = 0
    private var exchanging = false
    private var lastPattern = ""
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Design.background); setPadding(dp(20),dp(16),dp(20),dp(16)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(20)+safe.left,dp(16)+safe.top,dp(20)+safe.right,dp(16)+safe.bottom); insets
        }
        root.addView(Design.label(this,"连接夸克网盘",28f,trueColor(),true))
        root.addView(Design.label(this,"用手机夸克网盘扫码并确认授权",20f,Design.secondary).apply { gravity=Gravity.CENTER })
        val frame=FrameLayout(this)
        web=WebView(this).apply {
            settings.javaScriptEnabled=true; settings.domStorageEnabled=true
            settings.userAgentString=com.cruisetune.player.data.QuarkApi.USER_AGENT
            settings.allowFileAccess=false; settings.allowContentAccess=false
            settings.useWideViewPort=true; settings.loadWithOverviewMode=true
            settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            alpha=0f; importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:android.webkit.WebResourceRequest):Boolean = !allowed(request.url)
                @Deprecated("Legacy Android callback") override fun shouldOverrideUrlLoading(view:WebView,url:String):Boolean = !allowed(Uri.parse(url))
            }
        }
        // The page remains attached and live so its own QR renewal and scan flow can run.
        frame.addView(web,FrameLayout.LayoutParams(dp(1280),dp(720)))
        qr=ImageView(this).apply { setBackgroundColor(Color.WHITE); contentDescription="夸克网盘授权二维码"; scaleType=ImageView.ScaleType.FIT_CENTER }
        val side=minOf(resources.configuration.screenWidthDp-48,resources.configuration.screenHeightDp-230).coerceIn(144,300)
        frame.addView(qr,FrameLayout.LayoutParams(dp(side),dp(side),Gravity.CENTER))
        root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        status=Design.label(this,"正在获取二维码…",19f,Design.accent).apply { gravity=Gravity.CENTER; maxLines=2 }
        root.addView(status,LinearLayout.LayoutParams(-1,dp(56)))
        val actions=LinearLayout(this)
        listOf("刷新" to { start() },"授权码" to { pasteCode() },"打开授权页" to {
            challenge?.let { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it.url))) }; Unit
        }).forEach { (label,callback) -> actions.addView(TouchButton(this,label).apply { textSize=18f; setOnClickListener { callback() } },LinearLayout.LayoutParams(0,dp(76),1f).apply { marginEnd=dp(6) }) }
        root.addView(actions); setContentView(root); start()
    }
    private fun trueColor() = Design.text
    private fun allowed(uri:Uri) = uri.scheme=="https" && uri.host=="pan.quark.cn"
    private fun start() {
        if(exchanging) return
        job?.cancel(); val run=++generation; challenge=null; lastPattern=""; qr.setImageDrawable(null)
        web.stopLoading(); web.loadUrl("about:blank"); status.text="正在获取二维码…"
        job=lifecycleScope.launch {
            try {
                val created=app.openConnections.direct.create(); challenge=created
                web.loadUrl(created.url)
                val until=SystemClock.elapsedRealtime()+300000
                while(isActive && SystemClock.elapsedRealtime()<until) {
                    readQr(run)
                    val code=app.openConnections.direct.poll(created)
                    if(code!=null) { complete(created,code); return@launch }
                    delay(2000)
                }
                qr.setImageDrawable(null); status.text="二维码已过期，请刷新"
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { qr.setImageDrawable(null); status.text=readableError(e) }
        }
    }
    private fun readQr(run:Int) {
        web.evaluateJavascript("""(function(){var s=Array.from(document.querySelectorAll('svg')).find(function(e){var v=e.getAttribute('viewBox');return v&&/^0 0 (\d+) \1${'$'}/.test(v)&&Number(v.split(' ')[2])>=21&&e.querySelector('path[fill="#000000"]');});if(!s)return null;return {size:Number(s.getAttribute('viewBox').split(' ')[2]),path:s.querySelector('path[fill="#000000"]').getAttribute('d')};})()""") { value ->
            if(run!=generation || exchanging || isFinishing) return@evaluateJavascript
            runCatching {
                val j=JSONTokener(value).nextValue() as? JSONObject ?: return@runCatching
                val pattern=j.getString("path")
                if(pattern==lastPattern) return@runCatching
                val bitmap=QuarkQrRaster.render(j.getInt("size"),pattern)
                qr.setImageBitmap(bitmap); lastPattern=pattern; status.text="等待扫码 · 可在手机上限定音乐目录"
            }.onFailure { status.text="二维码暂时无法显示，可打开授权页" }
        }
    }
    private suspend fun complete(created:DirectQuarkAuth.Challenge,code:String) {
        if(exchanging) return
        exchanging=true; status.text="已确认，正在保存账号…"; qr.setImageDrawable(null)
        try { app.openConnections.completeDirect(created,code); setResult(RESULT_OK); finish() }
        finally { exchanging=false }
    }
    private fun pasteCode() {
        if(challenge==null || exchanging) return
        val input=EditText(this).apply { hint="粘贴 AAC- 开头的授权码"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; textSize=20f }
        val dialog=AlertDialog.Builder(this).setTitle("输入授权码").setView(input).setNegativeButton("返回",null).setPositiveButton("连接",null).create()
        dialog.window?.setWindowAnimations(0); dialog.show(); Design.styleDialog(dialog,this); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code=input.text.toString().trim(); val current=challenge ?: return@setOnClickListener
            if(!code.startsWith("AAC-")) { input.error="请输入完整授权码"; return@setOnClickListener }
            input.text.clear(); dialog.dismiss(); job?.cancel()
            job=lifecycleScope.launch { try { complete(current,code) } catch(e:CancellationException){throw e} catch(e:Exception){status.text=readableError(e)} }
        }
    }
    override fun onDestroy(){generation++;job?.cancel();web.stopLoading();web.destroy();super.onDestroy()}
}

object QuarkQrRaster {
    fun render(size:Int,path:String):Bitmap {
        require(size in 21..177 && (size-21)%4==0 && path.length<=600000)
        val pixels=IntArray((size+8)*(size+8)){Color.WHITE}
        val matches=Regex("M\\s+(\\d+)\\s+(\\d+)\\s+l\\s+1\\s+0\\s+0\\s+1\\s+-1\\s+0\\s+Z").findAll(path).toList()
        require(matches.size in size..size*size)
        matches.forEach { val x=it.groupValues[1].toInt();val y=it.groupValues[2].toInt();require(x in 0 until size && y in 0 until size);pixels[(y+4)*(size+8)+x+4]=Color.BLACK }
        return Bitmap.createBitmap(pixels,size+8,size+8,Bitmap.Config.ARGB_8888).let { Bitmap.createScaledBitmap(it,(size+8)*4,(size+8)*4,false) }
    }
}
