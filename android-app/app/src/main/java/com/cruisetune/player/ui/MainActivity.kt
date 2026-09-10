package com.cruisetune.player.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.R
import android.content.res.Configuration
import android.os.Parcelable
import com.cruisetune.player.core.*
import com.cruisetune.player.data.LibraryState
import com.cruisetune.player.data.JsonCodec
import com.cruisetune.player.playback.PlaybackService
import com.cruisetune.player.ui.Design.dp
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.util.ArrayDeque

@UnstableApi
class MainActivity : CruiseActivity() {
    private val app get() = application as CruiseApplication
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var library = LibraryState()
    private var selectedSource: String? = null
    private var showingQueue = false
    private lateinit var adapter: TrackAdapter
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var format: TextView
    private lateinit var banner: TextView
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var cover: CoverView
    private lateinit var seek: SeekBar
    private lateinit var play: TouchButton
    private lateinit var previous: TouchButton
    private lateinit var next: TouchButton
    private lateinit var recycler: RecyclerView
    private var pendingListPosition: Parcelable? = null
    private lateinit var shuffle: TouchButton
    private lateinit var repeat: TouchButton
    private lateinit var offline: TouchButton
    private lateinit var queueTab: TouchButton
    private lateinit var libraryTab: TouchButton
    private lateinit var listTitle: TextView
    private lateinit var empty: TextView
    private var seeking = false
    private var lastMessage: String? = null
    private var renderedTrack: String? = null
    private var renderedArtworkHash: Int? = null
    private var renderedArtworkBytes: ByteArray? = null
    private var trackPayload: String? = null
    private var trackModel: Track? = null
    private var artworkJob: Job? = null
    private data class PanelViews(val heading: TextView, val scroll: ScrollView, val content: LinearLayout)
    private val panels = java.util.WeakHashMap<AlertDialog, PanelViews>()
    private var settingsDialog: AlertDialog? = null
    private var refreshingAppearance = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val localDirectory = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { toast("目录权限未保留，请重新选择"); return@registerForActivityResult }
        app.scope.launch {
            val name = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(this@MainActivity, uri)?.name } ?: "本地音乐"
            val source = MusicSource(stableHash("local", uri.toString()), SourceKind.LOCAL, name, uri.toString())
            selectedSource = source.id; showingQueue = false
            app.library.addAndScan(source)
        }
    }
    private val quarkLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) { toast("夸克已连接，请选择音乐目录"); showQuarkFolders() }
    }
    private val directLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) { toast("夸克已授权，请选择音乐目录"); showQuarkFolders(SourceKind.QUARK_OPEN) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Design.configure(app.preferences.getBoolean("dayMode", false), app.preferences.getBoolean("highContrast", false), app.preferences.getBoolean("reduceTransparency", false))
        Design.applySystemBars(window)
        selectedSource = savedInstanceState?.getString("source")
        showingQueue = savedInstanceState?.getBoolean("queue") ?: false
        buildScreen()
        if (savedInstanceState?.getBoolean("settingsOpen") == true) window.decorView.post {
            showSettings(savedInstanceState.getInt("settingsScroll"))
        }
        controllerFuture = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync().also { future ->
            future.addListener({
                if (isDestroyed) return@addListener
                runCatching { future.get() }.onSuccess { c ->
                    controller = c
                    c.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            renderPlayer()
                            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) renderList()
                        }
                    })
                    if (savedInstanceState == null) command(PlaybackService.RESUME_ON_OPEN)
                    renderPlayer(); renderList()
                }.onFailure { toast("播放器暂时无法启动，请重新打开应用") }
            }, ContextCompat.getMainExecutor(this))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.library.state.collect { state ->
                        library = state
                        renderList()
                        if (state.message != null && state.message != lastMessage) { lastMessage = state.message; toast(state.message) }
                        renderPlayer()
                    }
                }
                launch { while (isActive) { renderPlayer(); delay(500) } }
            }
        }
    }
    private fun buildScreen() {
        val config = resources.configuration
        val spec = PlayerLayoutSpec.forWindow(config.screenWidthDp, config.screenHeightDp, config.fontScale)
        val compact = spec.compact
        val pad = if (compact) 12 else 20
        val headerHeight = if (compact) 56 else 80
        val root = LinearLayout(this).apply {
            id = R.id.player_root; orientation = LinearLayout.VERTICAL; setBackgroundColor(Design.background)
            setPadding(dp(pad), dp(6), dp(pad), dp(8))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(pad)+safe.left, dp(6)+safe.top, dp(pad)+safe.right, dp(8)+safe.bottom); insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(Design.label(this,"Cruise Tune",if (compact) 22f else 28f,bold=true).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END })
        if (!compact) brand.addView(Design.label(this,"此刻，听你喜欢",17f,Design.secondary).apply { setPadding(0,dp(4),0,0) })
        header.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        fun headerButton(label:String, action:()->Unit) = TouchButton(this,label).apply {
            textSize=if(compact)18f else 22f; minHeight=dp(headerHeight); setPadding(dp(8),0,dp(8),0); maxLines=1; setOnClickListener { action() }
        }
        header.addView(headerButton("来源",::showSources),LinearLayout.LayoutParams(dp(84),dp(headerHeight)))
        header.addView(headerButton("设置",{ showSettings() }),LinearLayout.LayoutParams(dp(84),dp(headerHeight)).apply { marginStart=dp(8) })
        root.addView(header,LinearLayout.LayoutParams(-1,dp(headerHeight)))

        val body=LinearLayout(this).apply { orientation=if(spec.landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val now=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(if(compact)12 else 20),dp(if(compact)8 else 20),dp(if(compact)12 else 20),dp(if(compact)8 else 16)); background=Design.surface(Design.panel,dp(24).toFloat()) }
        banner=Design.label(this,"继续你的旅程",18f,Design.accent).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END }
        if(!compact)now.addView(banner,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        val info=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        cover=CoverView(this).apply{visibility=if(spec.showCover)View.VISIBLE else View.GONE}
        info.addView(cover,LinearLayout.LayoutParams(dp(if(compact)56 else if(spec.landscape)160 else 80),dp(if(compact)56 else if(spec.landscape)160 else 80)))
        val titles=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(if(spec.showCover)dp(if(compact)8 else 16)else 0,0,0,0)}
        title=Design.label(this,"让旅途有音乐",if(compact)22f else 28f,bold=true).apply{id=R.id.player_title;maxLines=if(compact)1 else 2;ellipsize=TextUtils.TruncateAt.END;letterSpacing=-.015f}
        artist=Design.label(this,"从音乐目录开始",20f,Design.secondary).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        format=Design.label(this,"",if(compact)15f else 18f,Design.accent).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        titles.addView(title)
        if(!compact)titles.addView(artist,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        if(!compact || (spec.landscape && config.fontScale<=1.3f))titles.addView(format,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(if(compact)4 else 8)})
        info.addView(titles,LinearLayout.LayoutParams(0,-2,1f));now.addView(info)
        seek=SeekBar(this).apply{
            id=R.id.player_seek;max=10000;minimumHeight=dp(if(compact)48 else 64);contentDescription="播放进度"
            progressTintList=ColorStateList.valueOf(Design.accent);thumbTintList=ColorStateList.valueOf(Design.accent);setPadding(dp(6),0,dp(6),0)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(seekBar:SeekBar){seeking=true}
                override fun onStopTrackingTouch(seekBar:SeekBar){val total=knownDuration();if(total>0)controller?.seekTo(total*seekBar.progress/10000);seeking=false}
                override fun onProgressChanged(seekBar:SeekBar,progress:Int,fromUser:Boolean){if(fromUser)elapsed.text=time(knownDuration()*progress/10000)}
            })
        }
        now.addView(seek,LinearLayout.LayoutParams(-1,dp(if(compact)48 else 64)))
        val times=LinearLayout(this).apply{id=R.id.player_times}
        elapsed=Design.label(this,"0:00",if(compact)16f else 18f,Design.secondary)
        duration=Design.label(this,"—",if(compact)16f else 18f,Design.secondary).apply{gravity=Gravity.END}
        times.addView(elapsed,LinearLayout.LayoutParams(0,-2,1f));times.addView(duration,LinearLayout.LayoutParams(0,-2,1f));now.addView(times)
        offline=TouchButton(this,"保留离线").apply{setOnClickListener{keepCurrentOffline()}}
        if(!compact && config.screenHeightDp>=700)now.addView(offline,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        if(spec.landscape)body.addView(now,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(16)})
        else body.addView(now,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val lists=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val tabs=LinearLayout(this).apply{id=R.id.player_tabs}
        val tabHeight=if(compact)56 else 64
        queueTab=TouchButton(this,"队列").apply{minHeight=dp(tabHeight);textSize=20f;setPadding(dp(8),0,dp(8),0);maxLines=1;setOnClickListener{showingQueue=true;renderList()}}
        libraryTab=TouchButton(this,"曲库").apply{minHeight=dp(tabHeight);textSize=20f;setPadding(dp(8),0,dp(8),0);maxLines=1;setOnClickListener{showingQueue=false;renderList()}}
        tabs.addView(queueTab,LinearLayout.LayoutParams(0,dp(tabHeight),1f));tabs.addView(libraryTab,LinearLayout.LayoutParams(0,dp(tabHeight),1f).apply{marginStart=dp(8)});lists.addView(tabs)
        listTitle=Design.label(this,"还没有音乐",if(compact)16f else 19f,Design.secondary).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(dp(4),dp(if(compact)6 else 12),0,dp(if(compact)4 else 8))};lists.addView(listTitle)
        val frame=FrameLayout(this)
        adapter=TrackAdapter(compact){track ->
            if(showingQueue){val c=controller?:return@TrackAdapter;val index=(0 until c.mediaItemCount).firstOrNull{c.getMediaItemAt(it).mediaId==track.id};if(index!=null){c.seekTo(index,0);c.prepare();c.play()}}
            else{command(PlaybackService.PLAY_TRACK,Bundle().apply{putString("trackId",track.id);selectedSource?.let{putString("sourceId",it)}});showingQueue=true}
            askNotificationPermission()
        }
        recycler=RecyclerView(this).apply{id=R.id.player_list;layoutManager=LinearLayoutManager(this@MainActivity);adapter=this@MainActivity.adapter;itemAnimator=null;clipToPadding=true}
        frame.addView(recycler,FrameLayout.LayoutParams(-1,-1))
        empty=Design.label(this,"添加一个音乐目录\n喜欢的音乐，就在路上",if(compact)18f else 22f,Design.secondary).apply{gravity=Gravity.CENTER;setLineSpacing(dp(6).toFloat(),1f);setOnClickListener{showSources()};isFocusable=true;contentDescription="尚无音乐，点击添加来源"}
        frame.addView(empty,FrameLayout.LayoutParams(-1,-1));lists.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        body.addView(lists,if(spec.landscape)LinearLayout.LayoutParams(0,-1,1.15f)else LinearLayout.LayoutParams(-1,0,1f))
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(8)})

        val footer=FrameLayout(this).apply{id=R.id.player_footer;setPadding(0,dp(8),0,0)}
        val row=BoundedControlRow(this,if(spec.showModes)720 else 480).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        fun addFooter(button:TouchButton,weight:Float){button.minHeight=dp(76);button.setPadding(dp(8),0,dp(8),0);row.addView(button,LinearLayout.LayoutParams(0,dp(76),weight).apply{if(row.childCount>0)marginStart=dp(8)})}
        shuffle=TouchButton(this,"随机").apply{setOnClickListener{command(PlaybackService.SHUFFLE)}}
        repeat=TouchButton(this,"顺序").apply{setOnClickListener{controller?.let{it.repeatMode=(it.repeatMode+1)%3}}}
        previous=TouchButton(this,"上一首").apply{id=R.id.player_previous;textSize=if(compact)18f else 22f;setOnClickListener{controller?.let{if(it.hasPreviousMediaItem())it.seekToPreviousMediaItem()else it.seekTo(0)}}}
        next=TouchButton(this,"下一首").apply{id=R.id.player_next;textSize=if(compact)18f else 22f;setOnClickListener{controller?.seekToNextMediaItem()}}
        play=TouchButton(this,if(library.tracks.isEmpty())"添加" else "播放",true).apply{id=R.id.player_play;textSize=24f;setOnClickListener{
            val c=controller
            if(c==null){if(library.tracks.isEmpty())showSources();return@setOnClickListener}
            if(c.isPlaying||c.playWhenReady)c.pause()
            else if(c.mediaItemCount>0){if(c.playbackState==Player.STATE_ENDED)c.seekTo(0);c.prepare();c.play();askNotificationPermission()}
            else if(library.tracks.isEmpty())showSources()
            else library.tracks.firstOrNull()?.let{command(PlaybackService.PLAY_TRACK,Bundle().apply{putString("trackId",it.id)});askNotificationPermission()}
        }}
        if(spec.showModes)addFooter(shuffle,1f)
        addFooter(previous,1f);addFooter(play,1.3f);addFooter(next,1f)
        if(spec.showModes)addFooter(repeat,1f)
        footer.addView(row,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));root.addView(footer,LinearLayout.LayoutParams(-1,-2));setContentView(root)
    }
    private fun renderList() {
        if (!::adapter.isInitialized) return
        queueTab.selectedState(showingQueue); libraryTab.selectedState(!showingQueue)
        val c = controller
        val tracks = if (showingQueue && c != null) (0 until c.mediaItemCount).mapNotNull { index ->
            val item = c.getMediaItemAt(index)
            item.mediaMetadata.extras?.getString("track")?.let { runCatching { JsonCodec.decode(it) }.getOrNull() }
        } else library.tracks.filter { selectedSource == null || it.sourceId == selectedSource }
        if (adapter.currentList != tracks) adapter.submitList(tracks) { pendingListPosition?.let { recycler.layoutManager?.onRestoreInstanceState(it) }; pendingListPosition = null }
        adapter.updatePlayback(c?.currentMediaItem?.mediaId, playbackLabel(c))
        empty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (showingQueue) "队列还是空的\n从我的曲库选一首音乐" else "添加一个音乐目录\n喜欢的音乐，就在路上"
        listTitle.text = if (library.scanning != null) "正在读取目录 · ${library.scannedCount} 首" else "${if (showingQueue) "接下来播放" else library.sources.find { it.id == selectedSource }?.title ?: "全部音乐"} · ${tracks.size} 首"
    }
    private fun renderPlayer() {
        if (!::title.isInitialized) return
        val c = controller ?: return
        val item = c.currentMediaItem
        val id = item?.mediaId
        val track = currentTrack()
        title.updateText(item?.mediaMetadata?.title ?: "让旅途有音乐")
        artist.updateText(item?.mediaMetadata?.artist?.takeIf { it.isNotBlank() } ?: "连接夸克网盘，选择音乐目录")
        val status = track?.let { runCatching { app.media.status(it) }.getOrDefault("在线") }.orEmpty()
        format.updateText(if (track == null) "" else "${track.relativePath.substringAfterLast('.', "音频").uppercase()} · $status")
        if (renderedTrack != id) {
            renderedTrack = id; cover.seed = id?.hashCode() ?: 0; cover.artwork(null); renderedArtworkHash = null; renderedArtworkBytes = null
        }
        c.mediaMetadata.artworkData?.takeIf { it.size < 12 * 1024 * 1024 && it !== renderedArtworkBytes }?.let { bytes ->
            renderedArtworkBytes = bytes
            val hash = bytes.contentHashCode()
            if (renderedArtworkHash != hash) {
                renderedArtworkHash = hash; artworkJob?.cancel()
                artworkJob = lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1) }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                    if (renderedTrack == id) cover.artwork(bitmap)
                }
            }
        }
        val total = knownDuration()
        seek.isEnabled = total > 0
        seek.alpha = if (total > 0) 1f else .35f
        seek.contentDescription = if (total > 0) "播放进度" else "播放进度，等待加载时长，已恢复至 ${time(c.currentPosition)}"
        if (!seeking) { seek.progress = if (total > 0) (c.currentPosition * 10000 / total).toInt().coerceIn(0, 10000) else 0; elapsed.updateText(time(c.currentPosition)) }
        duration.updateText(if (total > 0) time(total) else "—")
        play.updateText(if (c.playWhenReady) "暂停" else if (c.mediaItemCount == 0 && library.tracks.isEmpty()) "添加" else if (c.currentPosition > 0) "继续" else "播放")
        play.contentDescription = if (c.playWhenReady) "暂停播放" else if (c.mediaItemCount == 0 && library.tracks.isEmpty()) "添加音乐目录" else "继续播放"
        previous.isEnabled = c.mediaItemCount > 0
        next.isEnabled = c.hasNextMediaItem()
        shuffle.selectedState(c.sessionExtras.getBoolean("shuffled"))
        repeat.updateText(when (c.repeatMode) { Player.REPEAT_MODE_ONE -> "单曲"; Player.REPEAT_MODE_ALL -> "循环"; else -> "顺序" })
        repeat.contentDescription = "播放顺序"
        ViewCompat.setStateDescription(repeat, when(c.repeatMode) { Player.REPEAT_MODE_ONE -> "单曲循环"; Player.REPEAT_MODE_ALL -> "列表循环"; else -> "顺序播放" })
        shuffle.contentDescription = "随机播放"
        ViewCompat.setStateDescription(shuffle, if(c.sessionExtras.getBoolean("shuffled")) "已开启" else "已关闭")
        banner.updateText(c.sessionExtras.getString("error") ?: when {
            c.playerError != null -> readableError(c.playerError?.cause ?: c.playerError!!)
            c.playWhenReady && c.playbackState == Player.STATE_BUFFERING -> "正在缓冲，网络恢复后继续"
            c.isPlaying -> "正在播放"
            item != null -> "继续上次播放 · ${time(c.currentPosition)}"
            else -> "继续你的旅程"
        })
        offline.isEnabled = track != null && track.localUri.isBlank()
        offline.updateText(if (status == "可离线播放") "已保留离线" else "保留离线")
        adapter.updatePlayback(id, playbackLabel(c))
    }
    private fun knownDuration(): Long = controller?.duration?.takeIf { it > 0 && it != C.TIME_UNSET }
        ?: currentTrack()?.durationMs?.takeIf { it > 0 } ?: 0
    private fun playbackLabel(c: Player?): String = when {
        c?.isPlaying == true -> "正在播放"
        c?.playWhenReady == true && c.playbackState == Player.STATE_BUFFERING -> "正在缓冲"
        else -> "已暂停"
    }
    private fun command(action: String, args: Bundle = Bundle.EMPTY) { controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
    private fun currentTrack(): Track? {
        val value = controller?.currentMediaItem?.mediaMetadata?.extras?.getString("track")
        if (value != trackPayload) { trackPayload = value; trackModel = value?.let { runCatching { JsonCodec.decode(it) }.getOrNull() } }
        return trackModel
    }
    private fun TextView.updateText(value: CharSequence) { if (!TextUtils.equals(text, value)) text = value }
    private fun keepCurrentOffline() {
        val track = currentTrack() ?: return
        try { app.media.keepOffline(track); askNotificationPermission(); toast("已加入离线下载") }
        catch (e: Exception) { toast(readableError(e)) }
    }
    private fun showSources() {
        val (dialog, content) = panel("音乐来源")
        if (library.sources.isNotEmpty()) {
            section(content, "我的音乐目录")
            library.sources.forEach { source ->
                val row = LinearLayout(this)
                val displayTitle = if(library.sources.count { it.title == source.title } > 1) source.title + when(source.kind) { SourceKind.LOCAL -> " · 本地"; SourceKind.QUARK -> " · 网页"; SourceKind.QUARK_OPEN -> " · 夸克" } else source.title
                row.addView(TouchButton(this, displayTitle).apply { maxLines=1; setOnClickListener { selectedSource=source.id;showingQueue=false;renderList();dialog.dismiss() } },LinearLayout.LayoutParams(0,-2,1f))
                row.addView(TouchButton(this,"刷新").apply { textSize=18f;setPadding(dp(8),0,dp(8),0);setOnClickListener { app.scope.launch { app.library.scan(source) };dialog.dismiss() } },LinearLayout.LayoutParams(dp(80),dp(76)).apply { marginStart=dp(8) })
                content.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
            }
            action(content,"查看全部音乐") { selectedSource=null;showingQueue=false;renderList();dialog.dismiss() }
        }
        action(content,"添加音乐目录") { dialog.dismiss();showAddSource() }
        action(content,"账号管理") { dialog.dismiss();showAccountManagement() }
        if (packageName.endsWith(".authcheck") && app.preferences.contains("quarkDirectAccount")) {
            section(content,"验证工具")
            action(content,"检查新授权") { dialog.dismiss();checkDirectAuthorization() }
            action(content,"检查后3首缓存") { dialog.dismiss();checkLookAheadCache() }
        }
        showPanel(dialog)
    }
    private fun showAddSource() {
        val (dialog, content)=panel("添加音乐目录")
        action(content,if(app.preferences.contains("quarkDirectAccount")) "夸克网盘目录" else "连接夸克网盘") {
            dialog.dismiss()
            if(app.preferences.contains("quarkDirectAccount"))showQuarkFolders(SourceKind.QUARK_OPEN)
            else directLogin.launch(Intent(this,QuarkDirectLoginActivity::class.java))
        }
        action(content,"本地音乐目录") { dialog.dismiss();localDirectory.launch(null) }
        showPanel(dialog)
    }
    private fun showAccountManagement() {
        val (dialog,content)=panel("账号管理")
        paragraph(content,if(app.preferences.contains("quarkDirectAccount")) "夸克网盘已连接，选择音乐目录时会复用当前授权。" else "连接夸克网盘后，可以选择授权范围内的音乐目录。")
        action(content,if(app.preferences.contains("quarkDirectAccount")) "切换夸克账号" else "扫码连接夸克") { dialog.dismiss();directLogin.launch(Intent(this,QuarkDirectLoginActivity::class.java)) }
        section(content,"备用网页登录")
        if(app.preferences.contains("quarkAccount"))action(content,"原网页登录的音乐目录") { dialog.dismiss();showQuarkFolders() }
        action(content,if(app.preferences.contains("quarkAccount")) "重新网页登录" else "使用网页登录") { dialog.dismiss();quarkLogin.launch(Intent(this,QuarkLoginActivity::class.java)) }
        showPanel(dialog)
    }
    private fun showQuarkFolders(kind: SourceKind = SourceKind.QUARK) {
        val account = app.preferences.getString(if (kind == SourceKind.QUARK_OPEN) "quarkDirectAccount" else "quarkAccount", null) ?: return
        val (dialog, content) = panel("选择夸克目录")
        val path = Design.label(this, "全部文件", 21f, Design.accent)
        content.addView(path)
        val note = Design.label(this, "正在读取目录…", 19f, Design.secondary)
        content.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val folders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(folders)
        val recursive = CalmSwitch(this).apply { text = "包含子目录中的音乐"; textSize = 21f; setTextColor(Design.text); isChecked = true; minHeight = dp(76) }
        content.addView(recursive)
        var current = RemoteEntry("0", "夸克音乐", true, 0, "")
        val stack = ArrayDeque<RemoteEntry>()
        var job: Job? = null
        val choose = TouchButton(this, "使用这个目录", true).apply { isEnabled = false }
        lateinit var load: (RemoteEntry) -> Unit
        load = { entry ->
            current = entry; choose.isEnabled = false; path.text = (stack.map { it.name } + entry.name).joinToString(" / ")
            note.text = "正在读取目录…"; folders.removeAllViews(); job?.cancel()
            job = lifecycleScope.launch {
                try {
                    val files = app.library.provider(kind, account).listChildren(entry.id)
                    if (!isActive) return@launch
                    note.text = "${files.count { AudioFiles.mime(it.name) != null && !it.isDirectory }} 首音频 · ${files.count { it.isDirectory }} 个子目录"
                    if (stack.isNotEmpty()) action(folders, "‹ 返回上级") { load(stack.removeLast()) }
                    files.filter { it.isDirectory }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }.forEach { folder ->
                        action(folders, "${folder.name}  ›") { stack.addLast(current); load(folder) }
                    }
                    choose.isEnabled = true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    note.text = readableError(e)
                    if (e is UserError && e.needsLogin) {
                        action(folders, "重新登录夸克") {
                            dialog.dismiss()
                            if (kind == SourceKind.QUARK_OPEN) directLogin.launch(Intent(this@MainActivity, QuarkDirectLoginActivity::class.java))
                            else quarkLogin.launch(Intent(this@MainActivity, QuarkLoginActivity::class.java))
                        }
                    } else {
                        action(folders, "重新读取") { load(current) }
                    }
                }
            }
        }
        choose.setOnClickListener {
            val rootIdentity = if (kind == SourceKind.QUARK_OPEN) com.cruisetune.player.data.open.openFileIdentity(current.id) else current.id
            val source = MusicSource(stableHash(if (kind == SourceKind.QUARK_OPEN) "quark-open" else "quark", account, rootIdentity), kind, current.name, current.id, account, recursive.isChecked)
            selectedSource = source.id; showingQueue = false
            app.scope.launch { app.library.addAndScan(source) }
            dialog.dismiss()
        }
        content.addView(choose, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(16) })
        dialog.setOnDismissListener { job?.cancel() }
        showPanel(dialog); load(current)
    }
    private fun checkLookAheadCache() {
        val (dialog, content) = panel("后 3 首缓存验证")
        val result = Design.label(this, "正在读取缓存状态…", 20f)
        content.addView(result); showPanel(dialog)
        val work = lifecycleScope.launch {
            while (isActive) {
                val c = controller ?: break
                val saved = withContext(Dispatchers.IO) { app.database.restore() }
                val future = com.cruisetune.player.playback.LookAheadPlan.select(saved.entries.map { it.track }, c.currentMediaItemIndex, c.repeatMode,
                    nextIndex = { c.currentTimeline.getNextWindowIndex(it, c.repeatMode, c.shuffleModeEnabled) })
                result.text = future.mapIndexed { index, track ->
                    val bytes = app.media.stream.getCachedBytes(track.cacheKey, 0, -1)
                    "${index+1}. ${track.title}\n${app.media.status(track)} · ${bytes / 1024} KiB / ${app.media.contentLength(track).coerceAtLeast(0) / 1024} KiB"
                }.joinToString("\n\n") + "\n\n" + c.sessionExtras.getString("prefetchStatus", "缓存正常") + "\n\n" + c.sessionExtras.getString("prefetchGate", "未收到调度状态")
                delay(1000)
            }
        }
        dialog.setOnDismissListener { work.cancel() }
    }
    private fun checkDirectAuthorization() {
        val (dialog, content) = panel("独立授权验证")
        val result = Design.label(this, "正在检查保存的账号、刷新令牌和网络读取…", 21f)
        content.addView(result); showPanel(dialog)
        lifecycleScope.launch {
            val lines = mutableListOf<String>()
            try {
                val report = withContext(Dispatchers.IO) {
                    check(packageName.endsWith(".authcheck"))
                    check(!app.preferences.contains("quarkAccount")) { "验证版已有网页登录，无法排除旧授权" }
                    lines += "旧网页登录：无"
                    val account = app.preferences.getString("quarkDirectAccount", null) ?: error("请先扫码授权")
                    val before = app.openConnections.sessions.fresh(account)
                    check(before.profileId == com.cruisetune.player.data.open.DirectQuarkAuth.PROFILE)
                    lines += "新授权：已从加密存储读取"
                    val after = app.openConnections.sessions.refreshAfterFailure(account, before.generation)
                    check(after.accountId == before.accountId && after.generation > before.generation)
                    lines += "令牌刷新：成功"
                    val provider = app.openConnections.provider(account)
                    provider.listChildren("0")
                    lines += "授权目录网络读取：成功"
                    val source = app.database.sources().firstOrNull { it.kind == SourceKind.QUARK_OPEN && it.accountId == account }
                        ?: error("请先选择新授权的音乐目录")
                    val track = app.database.tracks(source.id).firstOrNull() ?: error("音乐目录为空")
                    val request = provider.resolve(track.fileId)
                    app.streamHttp.newCall(okhttp3.Request.Builder().url(request.url).header("Range", "bytes=0-65535").apply {
                        request.headers.forEach { (k, v) -> header(k, v) }
                    }.build()).execute().use { response ->
                        check(response.code == 206) { "媒体分段读取返回 HTTP ${response.code}" }
                        val input = response.body?.byteStream() ?: error("媒体未返回数据")
                        var count = 0; val buffer = ByteArray(4096)
                        input.use { while (count < 65536) { val n = it.read(buffer, 0, minOf(buffer.size, 65536-count)); if (n < 0) break; count += n } }
                        check(count == 65536) { "媒体数据未读完整" }
                    }
                    lines += "媒体网络读取：成功（64 KiB）"
                    lines.joinToString("\n\n")
                }
                result.text = report
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { result.text = (lines + readableError(e)).joinToString("\n\n") }
        }
    }
    private fun showSettings(scrollY: Int = 0) {
        settingsDialog?.takeIf { it.isShowing }?.let { return }
        val (dialog,content)=panel("设置")
        settingsDialog=dialog
        populateSettings(dialog,content)
        dialog.setOnDismissListener { if(settingsDialog===dialog)settingsDialog=null }
        showPanel(dialog)
        panels[dialog]?.scroll?.apply { id=R.id.settings_scroll;post { scrollTo(0,scrollY) } }
    }
    private fun populateSettings(dialog: AlertDialog, content: LinearLayout) {
        section(content,"播放")
        toggle(content, "打开应用时继续播放", "resumeOnOpen", false)
        paragraph(content, "熄屏时自动暂停并保存进度，亮屏后点击继续播放。")
        action(content, "${if (controller?.sessionExtras?.getBoolean("shuffled") == true) "关闭" else "开启"}随机播放") { command(PlaybackService.SHUFFLE); dialog.dismiss() }
        action(content, "切换顺序／单曲／列表循环") { controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 }; dialog.dismiss() }
        section(content,"缓存与离线")
        toggle(content, "自动缓存后 3 首", "prefetchNextTracks", true)
        paragraph(content, "按播放顺序缓存后 3 首完整歌曲，所有网络均可使用；网络中断后持续重试。")
        controller?.sessionExtras?.getString("prefetchStatus")?.let { paragraph(content, it) }
        action(content, "保留当前歌曲离线") { keepCurrentOffline() }
        action(content, "移除当前歌曲的离线下载") { currentTrack()?.let { app.media.removeOffline(it); toast("已移除离线保留，播放位置继续保存") } }
        action(content, "重试当前歌曲") { command(PlaybackService.RETRY); dialog.dismiss() }
        action(content, "调整播放进度") {
            val total = controller?.duration?.takeIf { it > 0 } ?: return@action
            val (progressDialog, progressContent) = panel("播放进度")
            val label = Design.label(this, time(controller?.currentPosition ?: 0), 24f)
            progressContent.addView(label)
            val slider = SeekBar(this).apply {
                max = 10000; minimumHeight = dp(76); progress = (((controller?.currentPosition ?: 0) * 10000) / total).toInt().coerceIn(0, 10000)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) { controller?.seekTo(total * seekBar.progress / 10000) }
                    override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) { label.text = time(total * value / 10000) }
                })
            }
            progressContent.addView(slider); showPanel(progressDialog)
        }
        paragraph(content, "流式缓存按存储空间自动设限，最多 2 GiB。已保留的离线音乐单独存放。")
        action(content, "清理流式缓存") { lifecycleScope.launch { withContext(Dispatchers.IO) { app.media.clearStreaming() }; toast("流式缓存已清理，离线音乐和进度已保留") } }
        section(content,"显示与动效")
        toggle(content, "减少动态效果", "reduceMotion", false)
        toggle(content, "日间模式", "dayMode", false)
        toggle(content, "提高对比度", "highContrast", false)
        toggle(content, "减少透明效果", "reduceTransparency", false)
        section(content,"关于")
        action(content, "设备信息") { showDeviceInfo() }
        action(content, "开源许可") {
            val (licenses, body) = panel("开源许可")
            paragraph(body, assets.open("third_party_notices.txt").bufferedReader().use { it.readText() })
            showPanel(licenses)
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::adapter.isInitialized && !refreshingAppearance) refreshAppearance(false)
    }
    private fun refreshAppearance(syncNightMode: Boolean = true) {
        if(refreshingAppearance)return
        refreshingAppearance=true
        try {
            val currentSettings=settingsDialog?.takeIf { it.isShowing }
            val settingsPosition=currentSettings?.let { panels[it]?.scroll?.scrollY } ?: 0
            pendingListPosition=if(::recycler.isInitialized)recycler.layoutManager?.onSaveInstanceState()else null
            Design.configure(app.preferences.getBoolean("dayMode",false),app.preferences.getBoolean("highContrast",false),app.preferences.getBoolean("reduceTransparency",false))
            if(syncNightMode)androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(if(Design.light)androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            Design.applySystemBars(window)
            artworkJob?.cancel();renderedTrack=null;renderedArtworkHash=null;renderedArtworkBytes=null
            buildScreen();renderList();renderPlayer()
            currentSettings?.let { dialog -> panels[dialog]?.let { views ->
                views.heading.setTextColor(Design.text)
                views.content.removeAllViews();populateSettings(dialog,views.content)
                Design.styleDialog(dialog,this)
                views.scroll.post { views.scroll.scrollTo(0,settingsPosition) }
            } }
        } finally {refreshingAppearance=false}
    }
    private fun showDeviceInfo() {
        val (dialog, content) = panel("设备信息")
        paragraph(content, "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n设备：${Build.MANUFACTURER} ${Build.MODEL}\n支持架构：${Build.SUPPORTED_ABIS.joinToString()}\n64 位架构：${Build.SUPPORTED_64_BIT_ABIS.joinToString().ifBlank { "未提供" }}\n可用窗口：${resources.configuration.screenWidthDp} × ${resources.configuration.screenHeightDp} dp\n字体缩放：${resources.configuration.fontScale}\nCruise Tune ${packageManager.getPackageInfo(packageName, 0).versionName}")
        showPanel(dialog)
    }
    private fun panel(title: String): Pair<AlertDialog, LinearLayout> {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val scroll = ScrollView(this).apply { addView(content) }
        val heading = Design.label(this, title, 28f, bold = true).apply { setPadding(dp(24), dp(24), dp(24), dp(8)) }
        val dialog = object : AlertDialog(this) {
            override fun dismiss() { super.dismiss(); panels.remove(this) }
        }.apply {
            setCustomTitle(heading); setView(scroll)
            setButton(AlertDialog.BUTTON_NEGATIVE,"完成") { _, _ -> dismiss() }
        }
        panels[dialog] = PanelViews(heading,scroll,content)
        return dialog to content
    }
    private fun showPanel(dialog: AlertDialog) {
        dialog.window?.setWindowAnimations(0)
        dialog.show()
        Design.styleDialog(dialog,this)
        dialog.window?.setLayout(minOf(dp(640), resources.displayMetrics.widthPixels - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { minHeight = dp(76); textSize = 21f; setTextColor(Design.accent) }
    }
    private fun section(parent: LinearLayout, text: String) {
        parent.addView(Design.label(this,text,20f,Design.accent,true),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(20);bottomMargin=dp(4) })
    }
    private fun paragraph(parent: LinearLayout, text: String) {
        parent.addView(Design.label(this, text, 20f, Design.secondary).apply { setLineSpacing(dp(5).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
    }
    private fun action(parent: LinearLayout, text: String, click: () -> Unit) {
        parent.addView(TouchButton(this, text).apply { maxLines = 2; setOnClickListener { click() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun toggle(parent: LinearLayout, text: String, key: String, default: Boolean) {
        parent.addView(CalmSwitch(this).apply {
            this.text = text; textSize = 21f; setTextColor(Design.text); minHeight = dp(84)
            isChecked = app.preferences.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                app.preferences.edit().putBoolean(key, checked).apply()
                if (key in listOf("dayMode","highContrast","reduceTransparency")) refreshAppearance()
                else if(key=="reduceMotion") {
                    panels.keys.toList().filter { it.isShowing }.forEach { Design.styleDialog(it,this@MainActivity) }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !app.preferences.getBoolean("notificationAsked", false)) {
            app.preferences.edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun time(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source",selectedSource);outState.putBoolean("queue",showingQueue)
        settingsDialog?.takeIf { it.isShowing }?.let { outState.putBoolean("settingsOpen",true);outState.putInt("settingsScroll",panels[it]?.scroll?.scrollY ?: 0) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { panels.keys.toList().forEach { it.dismiss() }; controllerFuture?.let(MediaController::releaseFuture); artworkJob?.cancel(); super.onDestroy() }
}
