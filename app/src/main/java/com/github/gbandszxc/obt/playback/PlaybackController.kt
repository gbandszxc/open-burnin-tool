package com.github.gbandszxc.obt.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.github.gbandszxc.obt.MainActivity
import com.github.gbandszxc.obt.R
import com.github.gbandszxc.obt.data.BurnInRepository
import com.github.gbandszxc.obt.data.LocalTrack
import com.github.gbandszxc.obt.data.SessionStatus
import com.github.gbandszxc.obt.data.TrackRepository
import com.github.gbandszxc.obt.domain.logic.BurnProgressEngine
import com.github.gbandszxc.obt.domain.logic.BurnSequencer
import com.github.gbandszxc.obt.domain.logic.EngineStatus
import com.github.gbandszxc.obt.domain.logic.PhasePosition
import com.github.gbandszxc.obt.domain.model.BurnPlan
import com.github.gbandszxc.obt.domain.model.SoundSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 触发暂停的原因；决定是否保留音频焦点与是否自动恢复。 */
private enum class PauseReason {

    /** 用户主动（通知按钮 / UI）。 */
    USER,

    /** 音频焦点短暂丢失（电话、导航播报等）：保留焦点， regained 后自动恢复。 */
    FOCUS_TRANSIENT,

    /** 音频焦点永久丢失（其他应用长期占用）：不自动恢复。 */
    FOCUS_LOSS,

    /** 拔耳机（ACTION_AUDIO_BECOMING_NOISY）：不自动恢复。 */
    BECOMING_NOISY,
}

/**
 * 播放控制器：煲机播放的控制逻辑与状态流中枢（Application 级单例，见
 * [com.github.gbandszxc.obt.data.AppContainer]）。
 *
 * 职责划分（对应任务约定）：
 * - 本类承载全部控制逻辑：按 [BurnSequencer] 定位音源并以统一的 [BurnSoundPlayer]
 *   抽象驱动（合成音源走 AudioTrack 流式写入，
 *   本地音乐走 MediaPlayer 循环私有目录文件）、每秒 tick 检测播放身份（音源/本地音轨）
 *   变化并无缝切换音源与增益（旧 player 释放、新 player 起播）、
 *   单调时钟锚点计时（[BurnProgressEngine.advanceTo]）、音频焦点、WakeLock、
 *   Room 进度落库（每 60 秒 + 暂停/继续/结束/完成时）；
 * - 本地音乐音源（[BurnPhase.localTrackId] 非空）：切阶段时经 [TrackRepository] 查库一次
 *   解析文件与展示名，[PlaybackState.soundSourceName] 显示曲目展示名；
 * - 方案续播：[start] 支持续播起点，进度引擎与音源定位均从该秒数起步，
 *   会话行初始 completedSeconds 即为该起点（可再次续播）；
 * - [BurnInService] 只负责前台保活与通知渲染（含煲机完成系统通知的渠道创建）；
 * - [BurnInViewModel] 把状态暴露给 UI。
 *
 * 增益策略：不移植原版「按阶段覆盖系统媒体流音量」的坏行为，
 * 改为播放器级增益（MediaPlayer.setVolume / AudioTrack.setVolume）。
 *
 * 线程模型：所有播放/焦点/WakeLock 操作固定在主线程（协程 scope 为 Main.immediate），
 * 音频焦点回调与广播回调默认也投递到主线程，无并发竞争；Room 写入走挂起函数内部 IO 线程。
 *
 * @param context Application Context（仅用于系统服务、注册广播与启动服务）。
 * @param repository 会话持久化入口。
 * @param trackRepository 本地音轨仓库：本地音乐音源阶段按音轨 id 解析文件路径与展示名。
 */
class PlaybackController(
    private val context: Context,
    private val repository: BurnInRepository,
    private val trackRepository: TrackRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _state = MutableStateFlow(PlaybackState.IDLE)

    /** 当前播放状态（已煲/剩余/暂停等），每秒刷新。 */
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // ---- 会话运行态（非空表示有活跃会话；仅主线程读写） ----
    private var engine: BurnProgressEngine? = null
    private var sequencer: BurnSequencer? = null
    private var currentPlan: BurnPlan? = null
    private var sessionId: Long = 0L
    private var tickCount: Long = 0L

    private var player: BurnSoundPlayer? = null

    /** 当前播放器对应的音源（null 表示无播放器）。本地音乐阶段恒为 [SoundSource.LOCAL_TRACK]。 */
    private var activeSource: SoundSource? = null

    /**
     * 当前播放器对应的本地音轨 id（null 表示非本地音乐音源）。
     * 与 [activeSource] 共同构成「播放身份」：本地音源枚举固定为 [SoundSource.LOCAL_TRACK]，
     * 两条不同本地音轨之间枚举相同但身份不同，仍需切换播放器。
     */
    private var activeLocalTrackId: Long? = null

    /** 当前本地音轨的展示名；[PlaybackState.soundSourceName] 对本地音乐显示它。 */
    private var activeLocalTrackName: String? = null

    /**
     * 本会话内已判定不可用的本地音轨 id（记录不存在/文件损坏等）。
     * 缓存失败结果避免切阶段失败后每秒 tick 重复查库重试；新会话时清空。
     */
    private val unavailableLocalTrackIds = mutableSetOf<Long>()

    private var activeGain: Double = Double.NaN
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyReceiverRegistered = false

    /** 音频焦点变化回调（AudioFocusRequest 未指定 Handler，默认主线程投递）。 */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onAudioFocusChange(change)
    }

    /** 拔耳机广播：仅播放中注册，收到即暂停。 */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "检测到音频输出切换（拔耳机），暂停播放")
                pause(PauseReason.BECOMING_NOISY)
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外控制入口（UI / 通知按钮 / 服务调用，均在主线程）
    // ------------------------------------------------------------------

    /**
     * 开始一次新会话：落库 → 抢焦点 → 起播 → 前台服务。已有会话时忽略。
     *
     * 方案续播支持：[resumeFromSeconds] 非零时以该已完成秒数起步（UI 先经
     * BurnInRepository.latestResumableSession 查到可续播会话，再把其 completedSeconds 传入）：
     * - 进度引擎以 completedSeconds = [resumeFromSeconds] 起步；
     * - 音源按 sequencer.positionAt([resumeFromSeconds]) 的位置选（startInternal 按当前位置
     *   选音源，因此天然支持从任意阶段切入）；
     * - 会话落库时把 [resumeFromSeconds] 写入初始 completedSeconds（见
     *   BurnInRepository.startSession），使新会话自身即刻可续播。
     * 越界输入收敛到 [0, plan.totalSeconds]，不抛异常。
     */
    fun start(plan: BurnPlan, resumeFromSeconds: Long = 0L) {
        if (_state.value.status != PlaybackStatus.IDLE) {
            Log.w(TAG, "已有会话进行中，忽略重复 start")
            return
        }
        scope.launch { startInternal(plan, resumeFromSeconds) }
    }

    /** 暂停（用户触发）：释放焦点与 WakeLock，进度落库，状态置 PAUSED。 */
    fun pause() = pause(PauseReason.USER)

    /** 继续播放：重新抢焦点（被占用则保持暂停），重启计时与 WakeLock。 */
    fun resume() {
        val eng = engine ?: return
        if (eng.status != EngineStatus.PAUSED) return
        if (!requestAudioFocus()) {
            Log.w(TAG, "恢复播放被拒：音频焦点被占用，保持暂停")
            return
        }
        resumeOnFocusGain = false
        if (!eng.resume()) return
        safePlayback("resume") { player?.resume() }
        acquireWakeLock()
        registerBecomingNoisy()
        restartTicker()
        scope.launch {
            persistProgress()
            updateSessionStatus(SessionStatus.RUNNING)
        }
        publishState()
    }

    /** 结束会话（用户放弃）：停止音源、进度与状态落库（ABANDONED）、回到空闲。 */
    fun stop() {
        scope.launch { stopInternal() }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private suspend fun startInternal(plan: BurnPlan, resumeFromSeconds: Long = 0L) {
        val startedAtMillis = System.currentTimeMillis()
        // 续播起点收敛到合法区间；负值/超计划值按边界起步，不因脏输入中断
        val startAtSeconds = resumeFromSeconds.coerceIn(0L, plan.totalSeconds)
        if (startAtSeconds != resumeFromSeconds) {
            Log.w(TAG, "续播起点越界已收敛：$resumeFromSeconds → $startAtSeconds（计划 ${plan.totalSeconds}s）")
        }
        // 插入新会话前先把同方案旧的可续检查点全部置为 ABANDONED（失败仅记日志，不阻断起播）：
        // 无论「继续」（旧检查点被新会话取代）还是「全新开始」（旧检查点作废），同一方案
        // 至多保留一个可续检查点，避免「已暂停」检查点行无限累积、方案卡长期显示过期「上次进度」
        repository.abandonResumableSessions(plan, startedAtMillis)
        val newSessionId = try {
            // 初始 completedSeconds 写入续播起点：新会话行自身即刻成为「可续播」记录
            repository.startSession(plan, startedAtMillis, initialCompletedSeconds = startAtSeconds)
        } catch (t: Throwable) {
            Log.e(TAG, "会话落库失败，放弃开始", t)
            _state.value = PlaybackState.IDLE
            return
        }

        engine = BurnProgressEngine(plannedSeconds = plan.totalSeconds, initialCompletedSeconds = startAtSeconds)
        sequencer = BurnSequencer(plan)
        currentPlan = plan
        sessionId = newSessionId
        tickCount = 0L

        if (!requestAudioFocus()) {
            Log.e(TAG, "音频焦点获取失败，会话标记放弃")
            abandonAudioFocus()
            runCatching { repository.updateStatus(newSessionId, SessionStatus.ABANDONED, System.currentTimeMillis()) }
            resetToIdle()
            return
        }

        // 音源按续播位置选：从第 startAtSeconds 秒起播时直接定位到所处阶段/轮换段
        val position = sequencer!!.positionAt(startAtSeconds)
        val created = try {
            createPlayerFor(position)?.apply {
                player.setGain(position.volumeRatio)
                player.start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "音源初始化失败，会话标记放弃", t)
            null
        }
        if (created == null) {
            // 本地音轨不可用（已删除/文件损坏等）视同音源初始化失败
            Log.e(TAG, "音源不可用（本地音轨缺失，position=$position），会话标记放弃")
            abandonAudioFocus()
            runCatching { repository.updateStatus(newSessionId, SessionStatus.ABANDONED, System.currentTimeMillis()) }
            releasePlayer()
            resetToIdle()
            return
        }
        player = created.player
        activeSource = created.source
        activeLocalTrackId = created.localTrackId
        activeLocalTrackName = created.localTrackName
        activeGain = position.volumeRatio

        acquireWakeLock()
        registerBecomingNoisy()
        publishState()
        restartTicker()

        // 状态就绪后再拉起前台服务，保证服务首帧通知即为播放中
        val serviceIntent = Intent(context, BurnInService::class.java)
            .setAction(BurnInService.ACTION_START)
        ContextCompat.startForegroundService(context, serviceIntent)
        Log.i(
            TAG,
            "煲机开始：session=$newSessionId plan=${plan.id} 总时长=${plan.totalSeconds}s " +
                "起点=${startAtSeconds}s",
        )
    }

    private fun pause(reason: PauseReason) {
        val eng = engine ?: return
        if (!eng.pause()) return // 已暂停则幂等返回

        tickerJob?.cancel()
        tickerJob = null
        safePlayback("pause") { player?.pause() }
        unregisterBecomingNoisy()
        releaseWakeLock()
        // 焦点短暂丢失时保留焦点监听，待 GAIN 自动恢复；其余原因释放焦点让给他人
        if (reason == PauseReason.FOCUS_TRANSIENT) {
            resumeOnFocusGain = true
        } else {
            abandonAudioFocus()
        }

        scope.launch {
            persistProgress()
            updateSessionStatus(SessionStatus.PAUSED)
        }
        publishState()
        Log.i(TAG, "暂停（原因=$reason，已煲=${eng.completedSeconds}s）")
    }

    private suspend fun stopInternal() {
        val eng = engine ?: return
        if (_state.value.status == PlaybackStatus.IDLE) return

        tickerJob?.cancel()
        tickerJob = null
        releasePlayer()
        unregisterBecomingNoisy()
        releaseWakeLock()
        abandonAudioFocus()

        persistProgress()
        updateSessionStatus(SessionStatus.ABANDONED)
        Log.i(TAG, "会话结束（放弃）：已煲=${eng.completedSeconds}s")
        resetToIdle()
    }

    /** 每秒 tick：单调时钟锚点推进（无累积漂移）→ 对齐音源/增益 → 发状态 → 定期落库 → 完成判定。 */
    private fun restartTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            val eng = engine ?: return@launch
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                if (eng.status != EngineStatus.RUNNING) break
                val status = eng.advanceTo(SystemClock.elapsedRealtime())
                tickCount++
                syncPlayback()
                publishState()
                if (tickCount % PERSIST_EVERY_TICKS == 0L) {
                    persistProgress()
                }
                if (status == EngineStatus.COMPLETED) {
                    finishCompleted()
                    return@launch
                }
            }
        }
    }

    /**
     * 每秒对齐播放链路与时序器定位：播放身份变化（阶段切换、打擂 30 分钟轮换，
     * 或本地音轨与内置音源/另一条本地音轨之间切换）→ 创建新播放器并起播后再释放旧播放器
     * （主线程顺序执行，缝隙最小化）；身份未变但阶段增益变化 → 仅调整增益。
     *
     * 本地音乐查库纪律：仅在「播放身份变化、确需切换」时经 [createPlayerFor] 查库一次，
     * 正常播放中身份不变的 tick 完全不查库（查库失败也缓存结果，见
     * [unavailableLocalTrackIds]，避免每秒重试）。
     */
    private suspend fun syncPlayback() {
        val seq = sequencer ?: return
        val eng = engine ?: return
        val current = player ?: return
        val position = seq.positionAt(eng.completedSeconds)
        // 本地音轨阶段：音源枚举固定为 LOCAL_TRACK（volumeRatio/循环由播放器与增益承担），
        // 但播放身份以音轨 id 区分——不同音轨之间同样需要切换播放器
        val targetLocalTrackId = position.phase.localTrackId
        val targetSource = if (targetLocalTrackId != null) SoundSource.LOCAL_TRACK else position.soundSource
        val targetChanged = targetSource != activeSource || targetLocalTrackId != activeLocalTrackId
        if (targetChanged) {
            Log.i(
                TAG,
                "音源切换：${describeActiveSource()} → ${describeTarget(targetSource, targetLocalTrackId)}" +
                    "（阶段=${position.phase.name}，增益=${position.volumeRatio}）",
            )
            val created = try {
                createPlayerFor(position)?.apply {
                    player.setGain(position.volumeRatio)
                    player.start()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "音源切换失败，保留当前播放器", t)
                null
            }
            if (created == null) {
                Log.w(TAG, "目标音源不可用（本地音轨 id=$targetLocalTrackId），保留当前播放器")
                return
            }
            safePlayback("switch-release") { current.release() }
            player = created.player
            activeSource = created.source
            activeLocalTrackId = created.localTrackId
            activeLocalTrackName = created.localTrackName
            activeGain = position.volumeRatio
            return
        }
        if (position.volumeRatio != activeGain) {
            safePlayback("gain") { current.setGain(position.volumeRatio) }
            activeGain = position.volumeRatio
        }
    }

    /** 到达计划时长：停音源、写满进度、标记 COMPLETED、发出系统完成通知。 */
    private suspend fun finishCompleted() {
        val eng = engine ?: return
        releasePlayer()
        unregisterBecomingNoisy()
        releaseWakeLock()
        abandonAudioFocus()

        persistProgress()
        updateSessionStatus(SessionStatus.COMPLETED)
        notifyBurnComplete(eng.completedSeconds)
        Log.i(TAG, "煲机完成：session=$sessionId 计划=${eng.plannedSeconds}s")
        resetToIdle()
    }

    // ------------------------------------------------------------------
    // 状态发布
    // ------------------------------------------------------------------

    private fun publishState() {
        val eng = engine ?: return
        val seq = sequencer ?: return
        val plan = currentPlan ?: return
        val position = seq.positionAt(eng.completedSeconds)
        val status = when (eng.status) {
            EngineStatus.RUNNING -> PlaybackStatus.PLAYING
            EngineStatus.PAUSED -> PlaybackStatus.PAUSED
            // 完成态只是 finishCompleted() 过渡，随后立即回 IDLE，通知层无需渲染
            EngineStatus.COMPLETED -> PlaybackStatus.PLAYING
        }
        // 本地音乐阶段显示曲目展示名（切阶段时已随 createPlayerFor 解析并缓存）；
        // 极端时序（如解析尚未完成即发布）回退「本地音乐」占位，不显示内置音源文案
        val soundSourceName = if (position.phase.localTrackId != null) {
            activeLocalTrackName ?: "本地音乐"
        } else {
            position.soundSource.displayName
        }
        _state.value = PlaybackState(
            status = status,
            sessionId = sessionId,
            planId = plan.id,
            planName = plan.name,
            plannedSeconds = eng.plannedSeconds,
            completedSeconds = eng.completedSeconds,
            phaseName = position.phase.name,
            soundSourceName = soundSourceName,
        )
    }

    private fun resetToIdle() {
        engine = null
        sequencer = null
        currentPlan = null
        sessionId = 0L
        tickCount = 0L
        activeSource = null
        activeLocalTrackId = null
        activeLocalTrackName = null
        unavailableLocalTrackIds.clear()
        activeGain = Double.NaN
        _state.value = PlaybackState.IDLE
    }

    // ------------------------------------------------------------------
    // 音频焦点
    // ------------------------------------------------------------------

    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause(PauseReason.FOCUS_LOSS)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> pause(PauseReason.FOCUS_TRANSIENT)
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    Log.i(TAG, "音频焦点回归，自动恢复播放")
                    resume()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (focusRequest == null) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        }
        val request = focusRequest ?: return false
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        resumeOnFocusGain = false
    }

    // ------------------------------------------------------------------
    // WakeLock / 拔耳机广播
    // ------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        }
        wakeLock?.takeIf { !it.isHeld }?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun registerBecomingNoisy() {
        if (noisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterBecomingNoisy() {
        if (!noisyReceiverRegistered) return
        runCatching { context.unregisterReceiver(becomingNoisyReceiver) }
        noisyReceiverRegistered = false
    }

    // ------------------------------------------------------------------
    // 播放器创建（内置音源 + 本地音乐音源）与落库
    // ------------------------------------------------------------------

    /**
     * 一次播放器创建的结果：播放器本体 + 需要登记的播放身份（音源枚举 / 本地音轨 id 与展示名）。
     * startInternal 与 syncPlayback 共用，保证两处的身份登记口径一致。
     */
    private class CreatedPlayer(
        val player: BurnSoundPlayer,
        val source: SoundSource,
        val localTrackId: Long?,
        val localTrackName: String?,
    )

    /**
     * 按时序位置 [PhasePosition] 创建播放器：
     * - 阶段含 localTrackId（本地音乐音源）：经 [TrackRepository.getById] 解析音轨
     *   （**切阶段时仅查库这一次**），MediaPlayer(文件路径, isLooping=true) 无缝循环，
     *   增益沿用 [BurnSoundPlayer.setGain]（MediaPlayer.setVolume）路径；
     * - 否则按内置合成音源走 [BurnSoundPlayers]（AudioTrack 流式写入）。
     *
     * 本地音轨不可用（不存在/查库失败/文件打不开）返回 null 并把 id 记入
     * [unavailableLocalTrackIds]，本会话内不再重复查库重试；调用方按「切换失败保留当前
     * 播放器」（tick 中）或「音源初始化失败放弃会话」（startInternal 中）处理。
     */
    private suspend fun createPlayerFor(position: PhasePosition): CreatedPlayer? {
        val localTrackId = position.phase.localTrackId ?: return CreatedPlayer(
            player = BurnSoundPlayers.create(position.soundSource),
            source = position.soundSource,
            localTrackId = null,
            localTrackName = null,
        )
        if (localTrackId in unavailableLocalTrackIds) return null
        return try {
            val track: LocalTrack = trackRepository.getById(localTrackId) ?: run {
                Log.w(TAG, "本地音轨不存在（id=$localTrackId，可能已被移除）")
                unavailableLocalTrackIds += localTrackId
                return null
            }
            val player = createLocalFilePlayer(trackRepository.playbackPath(track))
            CreatedPlayer(
                player = player,
                source = SoundSource.LOCAL_TRACK,
                localTrackId = track.id,
                localTrackName = track.displayName,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "本地音源创建失败（id=$localTrackId），本会话内不再重试", t)
            unavailableLocalTrackIds += localTrackId
            null
        }
    }

    /**
     * 本地音乐文件播放器：MediaPlayer(path, isLooping=true) 循环播放用户私有目录音轨，
     * 按 [BurnSoundPlayer] 统一抽象包装（合成音源在 SynthPlayer.kt 走 AudioTrack，
     * 本文件承载本地文件路径的 MediaPlayer 版本）。
     */
    private fun createLocalFilePlayer(path: String): BurnSoundPlayer {
        val mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            isLooping = true
            prepare()
        }
        return object : BurnSoundPlayer {
            override fun start() {
                mediaPlayer.start()
            }

            override fun pause() {
                mediaPlayer.pause()
            }

            override fun resume() {
                mediaPlayer.start()
            }

            override fun setGain(ratio: Double) {
                val clamped = ratio.coerceIn(0.0, 1.0).toFloat()
                mediaPlayer.setVolume(clamped, clamped)
            }

            override fun release() {
                runCatching { mediaPlayer.release() }
            }
        }
    }

    /** 当前播放身份的可读描述（日志用）。 */
    private fun describeActiveSource(): String = when (val trackId = activeLocalTrackId) {
        null -> activeSource?.displayName ?: "无"
        else -> "本地音轨#$trackId"
    }

    /** 目标播放身份的可读描述（日志用）。 */
    private fun describeTarget(source: SoundSource, localTrackId: Long?): String =
        if (localTrackId != null) "本地音轨#$localTrackId" else source.displayName

    /**
     * 煲机完成系统通知：标题「煲机完成」，内容「本次煲机已达到计划时长，共 X」
     * （X 为定格的 [completedSeconds] 经 [formatBurnDuration] 格式化），点击打开
     * [MainActivity]，[android.app.Notification.FLAG_AUTO_CANCEL] 语义由 setAutoCancel(true) 提供。
     *
     * 渠道 burn_complete（默认重要级）随前台服务通知在 [BurnInService] 一处创建；
     * 无通知权限（33+ 未授予或用户关闭应用通知）时静默跳过，发送异常仅记日志，不影响会话完成。
     */
    private fun notifyBurnComplete(completedSeconds: Long) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.i(TAG, "未授予通知权限，跳过完成通知")
            return
        }
        runCatching {
            val notification = NotificationCompat.Builder(context, BurnInService.COMPLETE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_complete_title))
                .setContentText(context.getString(R.string.notif_complete_text, formatBurnDuration(completedSeconds)))
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        COMPLETE_NOTIFICATION_REQUEST_CODE,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
            manager.notify(COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { t ->
            Log.w(TAG, "完成通知发送失败（不影响会话完成）", t)
        }
    }

    private fun releasePlayer() {
        safePlayback("release") { player?.release() }
        player = null
        activeSource = null
        activeLocalTrackId = null
        activeLocalTrackName = null
        activeGain = Double.NaN
    }

    private suspend fun persistProgress() {
        val eng = engine ?: return
        try {
            repository.recordProgress(sessionId, eng.completedSeconds, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "进度落库失败（不影响播放）", t)
        }
    }

    private suspend fun updateSessionStatus(status: SessionStatus) {
        try {
            repository.updateStatus(sessionId, status, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "状态落库失败（status=$status）", t)
        }
    }

    private inline fun safePlayback(scene: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "播放器操作异常（scene=$scene）", t)
        }
    }

    private companion object {
        const val TAG = "PlaybackController"
        const val WAKE_LOCK_TAG = "burnin:playback"

        /** tick 周期：1 秒；实际秒数由 elapsedRealtime 锚点差推算，delay 误差不累积。 */
        const val TICK_INTERVAL_MILLIS = 1_000L

        /** 进度落库周期：每 60 个 tick（60 秒）持久化一次。 */
        const val PERSIST_EVERY_TICKS = 60L

        /** 煲机完成通知 id（与前台服务通知 1001 互不覆盖）。 */
        const val COMPLETE_NOTIFICATION_ID = 1002

        private const val COMPLETE_NOTIFICATION_REQUEST_CODE = 3
    }
}
