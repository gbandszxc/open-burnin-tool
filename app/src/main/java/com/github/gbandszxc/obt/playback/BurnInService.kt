package com.github.gbandszxc.obt.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.github.gbandszxc.obt.BurnInApplication
import com.github.gbandszxc.obt.MainActivity
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.locale.AppLocale
import com.github.gbandszxc.obt.ui.phaseDisplayName
import com.github.gbandszxc.obt.ui.planDisplayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 煲机前台服务：只负责前台保活与通知渲染，全部控制逻辑在 [PlaybackController]
 * （Application 单例，服务被系统回收后控制器状态仍在）。
 *
 * 通知设计：
 * - 常驻 ongoing + [setOnlyAlertOnce]（后续更新不响铃不振动）；
 * - 播放中 [setUsesChronometer] + [setChronometerCountDown] 自动倒计时显示剩余时间，
 *   通知本体只在「状态离散变化」（播放↔暂停、阶段切换、换会话）时重建，不每秒刷新；
 * - 动作按钮：暂停/继续（随状态切换）与结束，PendingIntent 一律 [PendingIntent.FLAG_IMMUTABLE]。
 */
class BurnInService : Service() {

    private val controller by lazy {
        (application as BurnInApplication).appContainer.playbackController
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectJob: Job? = null
    private var languageCollectJob: Job? = null

    /** 上次渲染进通知的「渲染键」快照：仅离散变化才重建通知。 */
    private var lastRendered: PlaybackState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** 语言切换（含冷启动）：把进程级应用语言应用到服务资源，通知字符串据此解析。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 控制器已空闲（START_STICKY 进程重启后 intent 为 null，或极罕见的启动即结束竞态）：
        // 仍须先 startForeground 满足 startForegroundService 的 5 秒约束，再立即撤下退场
        val current = controller.state.value
        if (current.status == PlaybackStatus.IDLE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildEmptyNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_STOP -> controller.stop()
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(controller.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        if (stateCollectJob == null) {
            stateCollectJob = serviceScope.launch {
                controller.state.collect { newState ->
                    when {
                        // 会话结束/完成/放弃：控制器回 IDLE，服务撤下通知并退场
                        newState.status == PlaybackStatus.IDLE -> {
                            lastRendered = null
                            ServiceCompat.stopForeground(this@BurnInService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        shouldRerender(newState) -> {
                            lastRendered = newState
                            notifySafely(newState)
                        }
                    }
                }
            }
        }
        if (languageCollectJob == null) {
            // 应用内切换语言时按最后一次状态立即重渲染通知；与 Activity recreate 独立，
            // 保证后台播放中设置页改语言，通知栏同样即时换语言
            languageCollectJob = serviceScope.launch {
                (application as BurnInApplication).appContainer.settingsRepository.language.collect {
                    lastRendered?.let { notifySafely(it) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateCollectJob?.cancel()
        stateCollectJob = null
        languageCollectJob?.cancel()
        languageCollectJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 通知重建判定：状态档位、会话、方案、阶段序号任一变化才重建；
     * 每秒 tick 引起的已煲/剩余变化交给 chronometer 自走，避免每秒整条重建。
     */
    private fun shouldRerender(newState: PlaybackState): Boolean {
        val last = lastRendered ?: return true
        return last.status != newState.status ||
            last.sessionId != newState.sessionId ||
            last.planId != newState.planId ||
            last.phaseIndex != newState.phaseIndex
    }

    private fun notifySafely(state: PlaybackState) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (securityException: SecurityException) {
            // POST_NOTIFICATIONS 被拒：前台服务类型保证服务本身仍存活，仅通知不可见
            Log.w(TAG, "通知发布被拒绝（未授予通知权限）", securityException)
        }
    }

    /**
     * 通知构建上下文：服务 attach 后语言可能被应用内切换，base 上下文停留在旧语言；
     * 每次构建按进程级当前语言现包装一次（轻量），保证通知即时跟随切换。
     */
    private fun localizedContext(): Context = AppLocale.wrap(baseContext)

    private fun buildNotification(state: PlaybackState): Notification {
        val context = localizedContext()
        val paused = state.isPaused
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(
                    if (paused) R.string.notif_title_paused else R.string.notif_title_playing,
                    planDisplayName(state, context),
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 31+ 立即展示前台通知，不做延迟
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent())
            .addAction(
                /* icon = */ 0,
                context.getString(if (paused) R.string.notif_action_resume else R.string.notif_action_pause),
                servicePendingIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, REQUEST_CODE_TOGGLE),
            )
            .addAction(
                /* icon = */ 0,
                context.getString(R.string.notif_action_stop),
                servicePendingIntent(ACTION_STOP, REQUEST_CODE_STOP),
            )
        if (paused) {
            builder.setContentText(
                context.getString(
                    R.string.notif_text_paused,
                    formatBurnDuration(state.remainingSeconds, context.getString(R.string.duration_cross_day_fmt)),
                ),
            )
        } else {
            builder.setContentText(
                context.getString(
                    R.string.notif_text_playing,
                    phaseDisplayName(state, context),
                    formatBurnDuration(state.completedSeconds, context.getString(R.string.duration_cross_day_fmt)),
                ),
            )
            // 倒计时终点 = 当前时刻 + 剩余毫秒，系统 chronometer 自动逐秒递减
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + state.remainingSeconds * 1_000L)
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, BurnInService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /** 空闲占位通知：仅供「启动即退场」路径满足系统前台约束，瞬时存在。 */
    private fun buildEmptyNotification(): Notification {
        val context = localizedContext()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setOngoing(true)
            .build()
    }

    /**
     * 通知渠道统一创建：burn_playback（前台常驻，低重要级不打扰）与 burn_complete
     * （煲机完成提示，默认重要级），一处管理避免渠道创建逻辑分散。
     * 每次创建为幂等操作（系统对已存在渠道按新配置更新）。
     */
    private fun createNotificationChannel() {
        val context = localizedContext()
        val manager = getSystemService(NotificationManager::class.java)
        val playbackChannel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_description)
            setShowBadge(false)
        }
        val completeChannel = NotificationChannel(
            COMPLETE_CHANNEL_ID,
            context.getString(R.string.notif_complete_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_complete_channel_description)
        }
        manager.createNotificationChannel(playbackChannel)
        manager.createNotificationChannel(completeChannel)
    }

    companion object {
        private const val TAG = "BurnInService"

        /** 服务动作：拉起前台并就位（不携带控制语义）。 */
        const val ACTION_START = "com.github.gbandszxc.obt.action.START"

        /** 服务动作：暂停播放。 */
        const val ACTION_PAUSE = "com.github.gbandszxc.obt.action.PAUSE"

        /** 服务动作：继续播放。 */
        const val ACTION_RESUME = "com.github.gbandszxc.obt.action.RESUME"

        /** 服务动作：结束会话（用户放弃）。 */
        const val ACTION_STOP = "com.github.gbandszxc.obt.action.STOP"

        /** 前台通知固定 id（整条生命周期只此一条，原地更新）。 */
        const val NOTIFICATION_ID = 1001

        /** 煲机完成通知渠道 id（默认重要级，随前台服务渠道一处创建，见 [createNotificationChannel]）。 */
        const val COMPLETE_CHANNEL_ID = "burn_complete"

        private const val CHANNEL_ID = "burn_playback"
        private const val REQUEST_CODE_TOGGLE = 1
        private const val REQUEST_CODE_STOP = 2
        private const val REQUEST_CODE_CONTENT = 0
    }
}
