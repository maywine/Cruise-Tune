package com.cruisetune.player.startup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.dashboard.DashboardStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** JobScheduler owns the background lifetime; idle restoration needs no media foreground service. */
@androidx.media3.common.util.UnstableApi
class StartupRestoreJobService : JobService() {
    companion object {
        internal const val JOB_ID = 0x435452
        private const val TAG = "CruiseTuneStartup"

        internal fun schedule(context: Context) {
            val settings = StartupSettings(context.getSharedPreferences("preferences", Context.MODE_PRIVATE))
            val scheduler = context.getSystemService(JobScheduler::class.java)
            try {
                // BOOT_COMPLETED and vendor quick-boot events may arrive for the same startup.
                if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
                settings.receivedBoot()
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, StartupRestoreJobService::class.java))
                    .setOverrideDeadline(5_000L)
                    .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    settings.recordStage(StartupStage.STOPPED)
                    settings.record("系统未接受后台恢复任务，请点通知打开播放器")
                }
            } catch (error: RuntimeException) {
                settings.recordStage(StartupStage.STOPPED)
                settings.record("后台恢复任务未能安排，请点通知打开播放器")
                Log.w(TAG, "Unable to schedule background restoration", error)
            }
        }

        internal fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            StartupNotice.dismiss(context)
            StartupSettings(context.getSharedPreferences("preferences", Context.MODE_PRIVATE)).apply {
                recordStage(StartupStage.STOPPED)
                record("开机恢复已关闭")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { getSharedPreferences("preferences", Context.MODE_PRIVATE) }
    private val settings by lazy { StartupSettings(preferences) }
    private var parameters: JobParameters? = null
    private var work: Job? = null
    internal var connect: (Context) -> StartupPlaybackConnection = ::StartupMediaConnection
    internal var dashboardUpdates: () -> Flow<DashboardStatus> = {
        (application as? CruiseApplication)?.dashboardStatus ?: emptyFlow()
    }
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == StartupSettings.ENABLED && !settings.enabled) {
            settings.recordStage(StartupStage.STOPPED)
            settings.record("开机恢复已关闭")
            work?.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences.registerOnSharedPreferenceChangeListener(changed)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (!settings.canRetry || parameters != null) return false
        parameters = params
        settings.beginAttempt()
        work = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            val observation = launch { dashboardUpdates().collect { settings.recordDashboard(it) } }
            try {
                StartupRestoration(connect(this@StartupRestoreJobService), { settings.enabled },
                    report = { reply -> settings.record(reply.detail) }, stage = settings::recordStage).run()
                settings.recordStage(StartupStage.FINISHED)
            } catch (error: TimeoutCancellationException) {
                retry = settings.canRetry
                val phase = settings.stage?.description ?: "后台恢复"
                settings.recordStage(if (retry) StartupStage.RETRY_WAIT else StartupStage.STOPPED)
                settings.record("${phase}超时，${if (retry) "等待重试" else "请手动打开应用"}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                retry = settings.canRetry
                val phase = settings.stage?.description ?: "后台恢复"
                settings.recordStage(if (retry) StartupStage.RETRY_WAIT else StartupStage.STOPPED)
                settings.record("${phase}未完成，${if (retry) "等待重试" else "请手动打开应用"}")
                Log.w(TAG, "Background restoration failed", error)
            } finally {
                observation.cancel()
                if (parameters === params) {
                    parameters = null
                    work = null
                    jobFinished(params, retry)
                }
            }
        }
        work?.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (parameters?.jobId != params.jobId) return false
        parameters = null
        work?.cancel(); work = null
        val retry = settings.canRetry
        settings.recordStage(if (retry) StartupStage.RETRY_WAIT else StartupStage.STOPPED)
        settings.record(if (retry) "后台恢复被系统中断，等待重试" else "后台恢复已停止")
        return retry
    }

    override fun onDestroy() {
        parameters = null
        scope.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(changed)
        super.onDestroy()
    }
}
