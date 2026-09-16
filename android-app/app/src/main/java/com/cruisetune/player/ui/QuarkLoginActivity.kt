package com.cruisetune.player.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.readableError
import com.cruisetune.player.data.QuarkApi
import com.cruisetune.player.data.QuarkQrAuth
import com.cruisetune.player.data.QrLoginState
import com.cruisetune.player.ui.Design.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import java.util.UUID

class QuarkLoginActivity : CruiseActivity() {
    companion object {
        const val RECONNECT_ACCOUNT = "reconnectAccount"
        const val RECONNECTED = "reconnected"
        const val RETRY_TRACK = "retryTrack"
    }
    private val app get() = application as CruiseApplication
    private var web: WebView? = null
    private lateinit var root: LinearLayout
    private lateinit var qrImage: ImageView
    private lateinit var qrStatus: TextView
    private var qrJob: Job? = null
    private var connecting = false
    private var browserMode = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Design.background); setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        BottomClearance.applyTo(root)
        setContentView(root)
        showQrScreen()
    }
    private fun showQrScreen() {
        browserMode = false; web?.destroy(); web = null; root.removeAllViews()
        root.addView(Design.label(this, "连接夸克网盘", 28f, bold = true))
        root.addView(Design.label(this, if (intent.hasExtra(RECONNECT_ACCOUNT)) "用原夸克账号扫码，恢复已有音乐目录" else "用手机夸克 App 扫码，并确认登录", 21f, Design.secondary), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        val frame = FrameLayout(this)
        qrImage = ImageView(this).apply { setBackgroundColor(Color.WHITE); contentDescription = "夸克登录二维码"; scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val size = minOf(resources.configuration.screenWidthDp - 60, BottomClearance.contentHeightDp(this) - 230).coerceIn(144, 280)
        frame.addView(qrImage, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        qrStatus = Design.label(this, "正在获取二维码…", 20f, Design.accent).apply { gravity = Gravity.CENTER; maxLines = 2 }
        root.addView(qrStatus, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val actions = LinearLayout(this)
        actions.addView(TouchButton(this, "刷新").apply { textSize = 20f; setOnClickListener { startQr() } }, LinearLayout.LayoutParams(0, dp(76), 1f))
        actions.addView(TouchButton(this, "网页登录").apply { textSize = 18f; setOnClickListener { showWebLogin() } }, LinearLayout.LayoutParams(0, dp(76), 1.5f).apply { marginStart = dp(8) })
        actions.addView(TouchButton(this, "其他方式").apply { textSize = 18f; setOnClickListener { importSession() } }, LinearLayout.LayoutParams(0, dp(76), 1.5f).apply { marginStart = dp(8) })
        root.addView(actions)
    }
    override fun onStart() { super.onStart(); if (!browserMode && !connecting) startQr() }
    override fun onStop() { qrJob?.cancel(); qrJob = null; super.onStop() }
    private fun startQr() {
        if (connecting) return
        qrJob?.cancel(); qrImage.setImageDrawable(null); qrStatus.text = "正在获取二维码…"
        qrJob = lifecycleScope.launch {
            try {
                val auth = QuarkQrAuth(app.http)
                val challenge = auth.create()
                val bitmap = withContext(Dispatchers.Default) {
                    val matrix = MultiFormatWriter().encode(challenge.url, BarcodeFormat.QR_CODE, 600, 600, mapOf(EncodeHintType.MARGIN to 4))
                    val pixels = IntArray(600 * 600) { n -> if (matrix[n % 600, n / 600]) Color.BLACK else Color.WHITE }
                    Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.RGB_565)
                }
                qrImage.setImageBitmap(bitmap)
                val expires = SystemClock.elapsedRealtime() + 300000
                while (isActive && SystemClock.elapsedRealtime() < expires) {
                    val seconds = ((expires - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                    qrStatus.text = "等待扫码 · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                    delay(2000)
                    when (val state = auth.poll(challenge)) {
                        QrLoginState.Waiting -> Unit
                        QrLoginState.Expired -> { qrImage.setImageDrawable(null); qrStatus.text = "二维码已失效，请刷新"; return@launch }
                        is QrLoginState.Confirmed -> {
                            qrImage.setImageDrawable(null); qrStatus.text = "已确认，正在连接音乐目录…"
                            connect(auth.exchange(state.ticket)); return@launch
                        }
                    }
                }
                qrImage.setImageDrawable(null); qrStatus.text = "二维码已过期，请刷新"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { qrImage.setImageDrawable(null); qrStatus.text = readableError(e) }
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebLogin() {
        qrJob?.cancel(); browserMode = true; root.removeAllViews()
        root.addView(Design.label(this, "夸克网页登录", 26f, bold = true))
        root.addView(Design.label(this, "登录后点击“登录完成”。请在停车时操作。", 18f, Design.secondary))
        val browser = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.userAgentString = QuarkApi.USER_AGENT
            settings.useWideViewPort = true; settings.loadWithOverviewMode = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = WebViewClient()
        }
        web = browser
        CookieManager.getInstance().setAcceptCookie(true)
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this)
        actions.addView(TouchButton(this, "二维码").apply { setOnClickListener { showQrScreen(); startQr() } }, LinearLayout.LayoutParams(0, dp(76), 1f))
        actions.addView(TouchButton(this, "登录完成", true).apply { setOnClickListener {
            val value = CookieManager.getInstance().getCookie("https://pan.quark.cn/").orEmpty()
            if (QuarkApi.validCookie(value)) connect(value) else toast("尚未检测到登录状态，请先完成登录")
        } }, LinearLayout.LayoutParams(0, dp(76), 2f).apply { marginStart = dp(12) })
        root.addView(actions); browser.loadUrl("https://pan.quark.cn/")
    }
    private fun importSession() {
        val input = EditText(this).apply {
            hint = "本人夸克网页的 Cookie"; textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val dialog = AlertDialog.Builder(this).setTitle("使用已有网页登录状态")
            .setMessage("可粘贴本人其他设备上的夸克网页 Cookie。此信息仅加密保存在当前设备。")
            .setView(input).setNegativeButton("返回", null).setPositiveButton("连接", null).create()
        dialog.window?.setWindowAnimations(0); dialog.show(); Design.styleDialog(dialog,this)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            minHeight = dp(76)
            setOnClickListener {
                val value = input.text.toString().trim()
                if (!QuarkApi.validCookie(value)) input.error = "未检测到有效夸克网页登录状态"
                else { input.text.clear(); dialog.dismiss(); qrJob?.cancel(); connect(value) }
            }
        }
    }
    private fun connect(value: String) {
        if (connecting) return
        connecting = true
        lifecycleScope.launch {
            try {
                val reconnect = intent.getStringExtra(RECONNECT_ACCOUNT)
                val id = reconnect ?: UUID.randomUUID().toString()
                app.library.connectWebSession(value, reconnect, id)
                app.preferences.edit().putString("quarkAccount", id).apply()
                web?.stopLoading(); CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
                setResult(RESULT_OK, Intent().putExtra(RECONNECTED, reconnect != null)
                    .putExtra(RETRY_TRACK, intent.getStringExtra(RETRY_TRACK)))
                finish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!browserMode) qrStatus.text = readableError(e)
                toast(readableError(e))
            } finally { connecting = false }
        }
    }
    private fun toast(message: String) { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { web?.destroy(); super.onDestroy() }
}
