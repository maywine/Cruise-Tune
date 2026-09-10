package com.cruisetune.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.UserError
import com.cruisetune.player.core.readableError
import com.cruisetune.player.ui.Design.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class QuarkOpenLoginActivity : CruiseActivity() {
    private val app get() = application as CruiseApplication
    private lateinit var status: TextView
    private var working = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Design.background); setPadding(dp(24), dp(24), dp(24), dp(24)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(24) + safe.left, dp(24) + safe.top, dp(24) + safe.right, dp(24) + safe.bottom); insets
        }
        root.addView(Design.label(this, "夸克开放平台授权", 28f, bold = true))
        status = Design.label(this, "正在准备授权页面…", 22f, Design.secondary).apply { gravity = Gravity.CENTER }
        root.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TouchButton(this, "重新开始授权", true).apply { setOnClickListener { begin() } }, LinearLayout.LayoutParams(-1, dp(76)))
        root.addView(TouchButton(this, "返回播放器").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(12) })
        setContentView(root)
        if (intent.data != null) complete(intent.data!!) else if (savedInstanceState == null) begin() else status.text = "请在夸克授权页面完成确认"
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); intent.data?.let(::complete) }
    private fun begin() {
        if (working) return
        working = true; status.text = "正在准备授权页面…"
        lifecycleScope.launch {
            try {
                val url = app.openConnections.begin()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                status.text = "请在浏览器中的夸克页面确认授权，完成后将返回播放器"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = readableError(e) }
            finally { working = false }
        }
    }
    private fun complete(uri: Uri) {
        if (working) return
        working = true; status.text = "正在完成账号连接…"
        lifecycleScope.launch {
            try {
                if (!uri.isHierarchical || uri.scheme != "cruisetune" || uri.host != "quark-open" || uri.path != "/callback" || uri.getQueryParameters("code").size != 1 || uri.getQueryParameters("state").size != 1) throw UserError("授权回调不完整，请重新开始")
                app.openConnections.complete(uri.getQueryParameter("code").orEmpty(), uri.getQueryParameter("state").orEmpty())
                setResult(RESULT_OK); finish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = readableError(e) }
            finally { working = false }
        }
    }
}
