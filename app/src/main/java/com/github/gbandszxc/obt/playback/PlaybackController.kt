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
import com.github.gbandszxc.obt.data.sessionSoundInfo
import com.github.gbandszxc.obt.domain.logic.BurnProgressEngine
import com.github.gbandszxc.obt.domain.logic.BurnSequencer
import com.github.gbandszxc.obt.domain.logic.EngineStatus
import com.github.gbandszxc.obt.domain.logic.PhasePosition
import com.github.gbandszxc.obt.domain.model.BurnPlan
import com.github.gbandszxc.obt.domain.model.SoundSource
import com.github.gbandszxc.obt.domain.model.nextPlayableIndex
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
 *   抽象驱动（合成音源走 AudioTrack 流式写入，本地音乐走 MediaPlayer 播放私有目录文件、
 *   歌单循环由完成回调推进）、每秒 tick 检测播放身份（音源/歌单/序号）
 *   变化并无缝切换音源与响度（旧 player 释放、新 player 起播）、
 *   单调时钟锚点计时（[BurnProgressEngine.advanceTo]）、音频焦点、WakeLock、
 *   Room 进度落库（每 60 秒 + 暂停/继续/结束/完成时）；
 * - 本地音乐音源（[BurnPhase.localTrackIds] 非空，本阶段按序循环该歌单）：切阶段时经
 *   [TrackRepository] 查库一次解析文件与展示名，[PlaybackState.localTrackName] 显示当前曲目
 *   展示名；歌单内一曲播完经 MediaPlayer 完成回调推进到下一可用曲目（见
 *   [onPlaylistTrackCompleted]）；
 * - 方案续播：[start] 支持续播起点，进度引擎与音源定位均从该秒数起步，
 *   会话行初始 completedSeconds 即为该起点（可再次续播）；
 * - [BurnInService] 只负责前台保活与通知渲染（含煲机完成系统通知的渠道创建）；
 * - [BurnInViewModel] 把状态暴露给 UI。
 *
 * 响度策略：方案煲机（[BurnPlan.loudnessViaSystemVolume] = true，classic/custom 工厂产出）
 * 的阶段响度经系统媒体音量（AudioManager STREAM_MUSIC）表达——进入会话先记录原始档位，
 * 按阶段 volumeRatio 换算档位设置（见 [streamVolumeIndexFor]），播放器增益恒 1.0；
 * 系统音量设置失败（勿扰模式等受限场景）→ 会话级降级回播放器增益（增益 = 比例），
 * 本会话不再尝试系统音量、不做自动恢复。暂停 / 结束 / 完成恢复进入会话前的原始档位。
 * 自由煲机（quick，标记 false）维持播放器级增益（MediaPlayer.setVolume / AudioTrack.setVolume）。
 * 已知局限：进程被杀后无法恢复原始音量，续播按新会话重新记录原始档位；系统音量模式下
 * 用户手动改动系统音量，会在下次阶段切换 / 响度变化时被拉回阶段档位（预期行为，不做音量监听）。
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
     * 当前播放器对应的本地歌单（null 表示非本地音乐音源；列表取自阶段，实例不可变）。
     * 与 [activeSource]、[activePlaylistIndex] 共同构成「播放身份」三元组：本地音源枚举固定为
     * [SoundSource.LOCAL_TRACK]，不同歌单（或同歌单的不同曲目序号）之间枚举相同但身份不同，
     * 仍需切换播放器。
     */
    private var activeLocalTrackIds: List<Long>? = null

    /**
     * 当前播放器在 [activeLocalTrackIds] 中的曲目序号；非歌单身份时恒为
     * [NO_LOCAL_PLAYLIST]。歌单内推进由完成回调（[onPlaylistTrackCompleted]）专属管辖，
     * tick 的身份对齐不按时间推此序号（曲目时长可能未知，时间制不可靠）。
     */
    private var activePlaylistIndex: Int = NO_LOCAL_PLAYLIST

    /** 当前曲目的展示名（随 [activePlaylistIndex] 切歌更新）；[PlaybackState.localTrackName] 对本地音乐显示它。 */
    private var activeLocalTrackName: String? = null

    /**
     * 本会话内已判定不可用的本地音轨 id（记录不存在/文件损坏等）。
     * 缓存失败结果避免切阶段失败后每秒 tick 重复查库重试；新会话时清空。
     */
    private val unavailableLocalTrackIds = mutableSetOf<Long>()

    private var activeGain: Double = Double.NaN

    /**
     * 进入会话时的系统媒体音量原始档位（STREAM_MUSIC，null 表示非系统音量会话或未记录）。
     * 仅方案煲机（[BurnPlan.loudnessViaSystemVolume] = true）在 startInternal 起播前记录，
     * 暂停 / 结束 / 完成时恢复，[resetToIdle] 统一清空。
     */
    private var originalStreamVolume: Int? = null

    /**
     * 本会话系统音量模式是否可用：方案煲机起播时置 true；任一次 setStreamVolume 失败
     * （勿扰模式等）即置 false，此后本会话整体降级为播放器增益模式、不再尝试恢复。
     * 自由煲机会话恒为 false（响度走播放器增益，见类头「响度策略」）。
     */
    private var systemVolumeAvailable: Boolean = false

    /**
     * 当前已落到系统音量的响度比例（NaN 表示尚未落位）：用于 tick / 阶段切换时与目标比例
     * 比较，避免每秒重复写系统设置；暂停恢复原始档位、降级后作废（置 NaN），下次响度
     * 落位时强制重设。
     */
    private var appliedLoudnessRatio: Double = Double.NaN
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
     *
     * 暂停起步：[startPaused] = true 时（历史记录页续播进行中/已暂停会话）完成会话创建、
     * 进度引擎与音源定位后**不进入播放**，直接呈现与手动 [pause] 一致的暂停态
     * （引擎暂停、进度落库、释放焦点、不持 WakeLock/拔耳机监听），前台服务照常拉起，
     * 通知按暂停样式渲染；恢复走既有 [resume] 路径。
     */
    fun start(plan: BurnPlan, resumeFromSeconds: Long = 0L, startPaused: Boolean = false) {
        if (_state.value.status != PlaybackStatus.IDLE) {
            Log.w(TAG, "已有会话进行中，忽略重复 start")
            return
        }
        scope.launch { startInternal(plan, resumeFromSeconds, startPaused) }
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
        // 系统音量模式：暂停期间已还原为用户档位，重新按当前阶段比例落位（降级模式空转）
        sequencer?.let { seq -> syncLoudness(seq.positionAt(eng.completedSeconds).volumeRatio) }
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

    private suspend fun startInternal(plan: BurnPlan, resumeFromSeconds: Long = 0L, startPaused: Boolean = false) {
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
        // 音效快照（自由煲机历史回显）：仅 quick 方案非 null（方案煲机两列保持 null）；
        // 本地音乐音源在此解析曲目展示名快照——曲目之后可能被删除，必须存名字而非只存 id。
        // 本次额外一次一次性查库只发生在会话开始，与 createPlayerFor 起播时的查库互不影响
        //（后者带 unavailable 缓存与失败转试语义，此处解析失败仅 label 置空，不阻断起播）
        val soundInfo = sessionSoundInfo(plan)
        var soundSourceId: Int? = null
        var soundLabel: String? = null
        if (soundInfo != null) {
            soundSourceId = soundInfo.sourceId
            val trackId = soundInfo.localTrackId
            if (trackId != null) {
                soundLabel = try {
                    trackRepository.getById(trackId)?.displayName
                } catch (t: Throwable) {
                    Log.w(TAG, "会话音效展示名解析失败（trackId=$trackId），label 置空", t)
                    null
                }
            }
        }
        val newSessionId = try {
            // 初始 completedSeconds 写入续播起点：新会话行自身即刻成为「可续播」记录
            repository.startSession(
                plan,
                startedAtMillis,
                initialCompletedSeconds = startAtSeconds,
                soundSourceId = soundSourceId,
                soundLabel = soundLabel,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "会话落库失败，放弃开始", t)
            _state.value = PlaybackState.IDLE
            return
        }

        // 暂停起步时引擎直接以 PAUSED 态创建（锚点为空、暂停期间流逝时间不计入进度），
        // 与「播放一秒后手动 pause()」的引擎状态一致；起点已达计划值由 init 强制 COMPLETED，
        // 交由下方暂停分支的完成兜底处理
        engine = BurnProgressEngine(
            plannedSeconds = plan.totalSeconds,
            initialCompletedSeconds = startAtSeconds,
            initialStatus = if (startPaused) EngineStatus.PAUSED else EngineStatus.RUNNING,
        )
        sequencer = BurnSequencer(plan)
        currentPlan = plan
        sessionId = newSessionId
        tickCount = 0L

        // 系统音量会话登记：改音量前先记录原始媒体音量档位（暂停/结束/完成时恢复）；
        // 方案煲机响度改由系统音量承担、播放器增益恒 1.0，自由煲机维持增益模式
        if (plan.loudnessViaSystemVolume) {
            originalStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            systemVolumeAvailable = true
        } else {
            originalStreamVolume = null
            systemVolumeAvailable = false
        }

        if (!requestAudioFocus()) {
            Log.e(TAG, "音频焦点获取失败，会话标记放弃")
            abandonAudioFocus()
            runCatching { repository.updateStatus(newSessionId, SessionStatus.ABANDONED, System.currentTimeMillis()) }
            resetToIdle()
            return
        }

        // 音源按续播位置选：从第 startAtSeconds 秒起播时直接定位到所处阶段/轮换段
        val position = sequencer!!.positionAt(startAtSeconds)
        // 暂停起步的极端边界：续播起点已达计划值（正常数据不会发生，防御历史脏数据），
        // 引擎已被构造强制为 COMPLETED、无法呈现暂停态，直接按完成收尾
        if (startPaused && engine!!.isCompleted) {
            Log.w(TAG, "暂停起步但续播起点已达计划值（起点=$startAtSeconds），按完成收尾")
            finishCompleted()
            return
        }
        val created = try {
            createPlayerFor(position)?.apply {
                player.setGain(playerGainFor(position.volumeRatio))
                // 暂停起步不 start：播放器 prepared 待命（resume 走 start()，状态机等价）
                if (!startPaused) player.start()
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
        activeLocalTrackIds = created.localTrackIds
        activePlaylistIndex = created.playlistIndex
        activeLocalTrackName = created.localTrackName
        activeGain = playerGainFor(position.volumeRatio)
        if (startPaused) {
            // 暂停起步（历史记录续播）：不落位阶段响度（系统音量维持用户档位、已应用比例作废，
            // 恢复播放时强制重设）、释放焦点、不持 WakeLock/拔耳机监听、不启动 ticker——
            // 运行态与手动 pause() 后完全一致；进度与 PAUSED 状态即刻落库
            restoreOriginalStreamVolume()
            abandonAudioFocus()
            scope.launch {
                persistProgress()
                updateSessionStatus(SessionStatus.PAUSED)
            }
            publishState()
        } else {
            // 响度落位：系统音量模式按当前位置比例设系统音量（失败在此处即降级），增益模式已就位
            syncLoudness(position.volumeRatio)

            acquireWakeLock()
            registerBecomingNoisy()
            publishState()
            restartTicker()
        }

        // 状态就绪后再拉起前台服务，保证服务首帧通知即为播放中（暂停起步时即为暂停样式）
        val serviceIntent = Intent(context, BurnInService::class.java)
            .setAction(BurnInService.ACTION_START)
        ContextCompat.startForegroundService(context, serviceIntent)
        Log.i(
            TAG,
            "煲机开始${if (startPaused) "（暂停起步）" else ""}：session=$newSessionId plan=${plan.id} " +
                "总时长=${plan.totalSeconds}s " +
                "起点=${startAtSeconds}s",
        )
    }

    private fun pause(reason: PauseReason) {
        val eng = engine ?: return
        if (!eng.pause()) return // 已暂停则幂等返回

        tickerJob?.cancel()
        tickerJob = null
        safePlayback("pause") { player?.pause() }
        // 系统音量模式：暂停期间把媒体音量还给用户（恢复进入会话前的档位，失败仅日志）；
        // 已应用比例作废，resume 时强制按阶段比例重设
        restoreOriginalStreamVolume()
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
        // 会话放弃：恢复进入会话前的系统媒体音量，把音量控制权还给用户
        restoreOriginalStreamVolume()

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
     * 每秒对齐播放链路与时序器定位：播放身份变化（阶段切换、轮换 30 分钟音源切换、
     * 或合成音源与本地歌单/另一份歌单之间切换）→ 创建新播放器并起播后再释放旧播放器
     * （主线程顺序执行，缝隙最小化）；身份未变但阶段响度变化 → 仅调整响度
     * （系统音量模式设系统媒体音量档位，增益模式调播放器增益，见 [syncLoudness]）。
     *
     * 播放身份为三元组（音源枚举, 歌单, 曲目序号）。时序器只感知阶段与音源，不感知歌单内
     * 曲目进度，故 tick 计算的目标序号在「歌单未变」时直接沿用当前序号——歌单内推进由
     * 完成回调（[onPlaylistTrackCompleted]）专属管辖，tick 既不会因推进误判身份变化，
     * 也绝不按阶段内已播时间反推曲目序号（曲目 durationMs 可能为 null，时间制不可靠）；
     * 歌单变化（进入/切出/换歌单）时目标序号为 0（从歌单头起播）。因此 tick 层面的判定
     * 实际落在「音源枚举变 || 歌单列表变」两项，序号维度的变化由 [switchToPlaylistIndex]
     * 主动切换身份。
     *
     * 歌单相等性用 [List.equals]（内容相等即同一身份）：两个阶段携带内容相同的歌单时
     * 跨阶段无缝续播当前曲目，不重启播放器。
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
        // 本地歌单阶段：音源枚举固定为 LOCAL_TRACK（volumeRatio/循环由播放器与增益承担），
        // 但播放身份以歌单与序号区分——不同歌单/曲目之间同样需要切换播放器
        val targetPlaylist = position.phase.localTrackIds.ifEmpty { null }
        val targetSource = if (targetPlaylist != null) SoundSource.LOCAL_TRACK else position.soundSource
        val playlistUnchanged = targetPlaylist == activeLocalTrackIds
        val targetIndex = if (playlistUnchanged) {
            activePlaylistIndex
        } else if (targetPlaylist != null) {
            0
        } else {
            NO_LOCAL_PLAYLIST
        }
        val targetChanged = targetSource != activeSource || !playlistUnchanged
        if (targetChanged) {
            Log.i(
                TAG,
                "音源切换：${describeActiveSource()} → ${describeTarget(targetSource, targetPlaylist)}" +
                    "（阶段=${position.phase.name}，增益=${position.volumeRatio}）",
            )
            val created = try {
                createPlayerFor(position)?.apply {
                    player.setGain(playerGainFor(position.volumeRatio))
                    player.start()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "音源切换失败，保留当前播放器", t)
                null
            }
            if (created == null) {
                Log.w(TAG, "目标音源不可用（本地歌单=$targetPlaylist），保留当前播放器")
                return
            }
            safePlayback("switch-release") { current.release() }
            player = created.player
            activeSource = created.source
            activeLocalTrackIds = created.localTrackIds
            activePlaylistIndex = created.playlistIndex
            activeLocalTrackName = created.localTrackName
            activeGain = playerGainFor(position.volumeRatio)
            // 新身份的响度立即落位（阶段切换常伴随比例变化或需降级补偿），不等下一秒 tick
            syncLoudness(position.volumeRatio)
            return
        }
        syncLoudness(position.volumeRatio)
    }

    // ------------------------------------------------------------------
    // 响度落位（系统音量模式 + 失败降级）
    // ------------------------------------------------------------------

    /**
     * 当前会话的播放器增益口径：系统音量模式恒 1.0（响度由系统媒体音量承担，播放器满增益，
     * 避免二次衰减），增益模式（自由煲机 / 降级会话）为阶段比例本身。
     */
    private fun playerGainFor(ratio: Double): Double = if (systemVolumeAvailable) 1.0 else ratio

    /**
     * 把阶段响度比例落位到当前模式：系统音量模式下比例有变化才换算档位并 setStreamVolume
     * （比例未变跳过，避免每秒 tick 重复写系统设置；用户手动改过的音量会在下一次比例变化时
     * 被拉回阶段档位，预期行为）；增益模式维持既有 setGain 路径（比例变化才调）。
     *
     * 系统音量设置失败 → 记日志、本会话置 [systemVolumeAvailable] = false 整体降级为
     * 播放器增益（增益 = 比例），并补一次 setGain 补齐响度缺口；本会话不再尝试系统音量，
     * 不做恢复流程。
     */
    private fun syncLoudness(ratio: Double) {
        if (systemVolumeAvailable) {
            if (ratio != appliedLoudnessRatio) {
                applySystemVolume(ratio)
            }
            return
        }
        val current = player ?: return
        if (ratio != activeGain) {
            safePlayback("gain") { current.setGain(ratio) }
            activeGain = ratio
        }
    }

    /**
     * 系统音量档位落位：按 [streamVolumeIndexFor] 换算 STREAM_MUSIC 档位并设置
     * （flags = 0，不拉起系统音量 UI）。成功记 [appliedLoudnessRatio]；失败（勿扰模式等
     * 系统受限场景可能抛异常）→ 会话级降级：置 [systemVolumeAvailable] = false、作废
     * 已应用比例，并补一次 setGain(ratio)（此时播放器可能仍处于满增益 1.0），此后本会话
     * 响度整体退回播放器增益模式。
     */
    private fun applySystemVolume(ratio: Double) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = streamVolumeIndexFor(ratio, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "系统媒体音量设置失败（target=$target/$max），本会话降级为播放器增益", t)
            systemVolumeAvailable = false
            appliedLoudnessRatio = Double.NaN
            safePlayback("degrade-gain") { player?.setGain(ratio) }
            activeGain = ratio
            return
        }
        appliedLoudnessRatio = ratio
        Log.i(TAG, "系统媒体音量已设为 $target/$max（响度比例=$ratio）")
    }

    /**
     * 恢复进入会话前的系统媒体音量原始档位（[originalStreamVolume]）：暂停 / 结束 / 完成
     * 时把音量控制权还给用户，失败仅记日志不抛出；同时作废已应用比例，保证恢复播放时
     * 强制按阶段比例重设。幂等（可重复调用，未记录档位时空操作），运行态由
     * [resetToIdle] 统一清理。
     */
    private fun restoreOriginalStreamVolume() {
        val original = originalStreamVolume ?: return
        appliedLoudnessRatio = Double.NaN
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "系统媒体音量恢复失败（original=$original）", t)
        }
    }

    /**
     * 歌单内一曲播完的推进入口（MediaPlayer 完成回调，见 [createLocalFilePlayer]）：
     * 仅当回调仍属当前播放身份（歌单内容相同且曲目序号相同）时有效——播放器被替换/释放后
     * 残留的滞后回调直接忽略，避免对新身份二次推进；有效则按 [nextPlayableIndex] 计算
     * 下一可用序号（跳过失效曲目、必要回绕），经主线程 scope 投递一次轻量切换
     * （[switchToPlaylistIndex]）。全部曲目不可用属兜底路径（UI 层会在配置时修剪失效
     * 曲目），保留当前播放器并记日志。
     *
     * 线程模型：MediaPlayer 在主线程创建，完成回调默认投递创建线程的 Looper（主线程），
     * 与「全部播放操作在主线程」的既有模型一致，无并发竞争。
     */
    private fun onPlaylistTrackCompleted(playlist: List<Long>, index: Int) {
        if (playlist != activeLocalTrackIds || index != activePlaylistIndex) return
        val next = nextPlayableIndex(playlist, index, unavailableLocalTrackIds) ?: run {
            Log.w(TAG, "歌单全部音轨不可用，无法推进（保留当前播放器）：$playlist")
            return
        }
        switchToPlaylistIndex(next)
    }

    /**
     * 按歌单序号切换播放身份（歌单推进专用，经主线程 scope 投递）：与 tick 的身份切换
     * 同路径——先起新播放器并起播，成功后再释放旧播放器并登记新序号；目标曲目起不了
     * 且无下一条可用曲目（[createPlayerFor] 返回 null，兜底见 [onPlaylistTrackCompleted]）
     * 或回调排队期间阶段已切换（歌单不再匹配，交给 tick 按新阶段身份对齐）时放弃本次切换。
     */
    private fun switchToPlaylistIndex(index: Int) {
        scope.launch {
            val eng = engine ?: return@launch
            val seq = sequencer ?: return@launch
            val playlist = activeLocalTrackIds ?: return@launch
            val position = seq.positionAt(eng.completedSeconds)
            // 回调排队期间阶段已切换且歌单不再匹配：本次推进作废，交给 tick 按新阶段身份对齐
            if (position.phase.localTrackIds != playlist) return@launch
            val created = try {
                createPlayerFor(position, playlistStartIndex = index)?.apply {
                    player.setGain(playerGainFor(position.volumeRatio))
                    player.start()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "歌单切歌失败，保留当前播放器", t)
                null
            }
            if (created == null) {
                Log.w(TAG, "歌单切歌无可用音轨（目标序号=$index），保留当前播放器")
                return@launch
            }
            safePlayback("switch-release") { player?.release() }
            player = created.player
            activeSource = created.source
            activeLocalTrackIds = created.localTrackIds
            activePlaylistIndex = created.playlistIndex
            activeLocalTrackName = created.localTrackName
            activeGain = playerGainFor(position.volumeRatio)
            // 歌单推进阶段比例通常不变（响度落位空转），防御阶段已切换的极端时序
            syncLoudness(position.volumeRatio)
            publishState()
        }
    }

    /** 到达计划时长：停音源、写满进度、标记 COMPLETED、发出系统完成通知。 */
    private suspend fun finishCompleted() {
        val eng = engine ?: return
        releasePlayer()
        unregisterBecomingNoisy()
        releaseWakeLock()
        abandonAudioFocus()
        // 会话完成：恢复进入会话前的系统媒体音量，把音量控制权还给用户
        restoreOriginalStreamVolume()

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
        // 本地音乐阶段（歌单非空）携带当前曲目展示名（切歌/切阶段时已随 createPlayerFor 解析
        // 并缓存），极端时序（如解析尚未完成即发布）为 null，展示层回退「本地音乐」占位
        val isLocalPlaylist = position.phase.localTrackIds.isNotEmpty()
        _state.value = PlaybackState(
            status = status,
            sessionId = sessionId,
            planId = plan.id,
            plannedSeconds = eng.plannedSeconds,
            completedSeconds = eng.completedSeconds,
            phaseIndex = position.phase.index,
            // 阶段固定身份随状态透出：展示层按它解析阶段名（语言资源），不随播放顺序改变
            stageId = position.phase.stageId,
            soundSource = if (isLocalPlaylist) SoundSource.LOCAL_TRACK else position.soundSource,
            localTrackName = if (isLocalPlaylist) activeLocalTrackName else null,
        )
    }

    private fun resetToIdle() {
        // 兜底恢复：异常放弃路径（如起播失败）可能已改过系统音量，先还原再清运行态（幂等）
        restoreOriginalStreamVolume()
        engine = null
        sequencer = null
        currentPlan = null
        sessionId = 0L
        tickCount = 0L
        activeSource = null
        activeLocalTrackIds = null
        activePlaylistIndex = NO_LOCAL_PLAYLIST
        activeLocalTrackName = null
        unavailableLocalTrackIds.clear()
        activeGain = Double.NaN
        originalStreamVolume = null
        systemVolumeAvailable = false
        appliedLoudnessRatio = Double.NaN
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
     * 一次播放器创建的结果：播放器本体 + 需要登记的播放身份（音源枚举 / 歌单与曲目序号 /
     * 曲目展示名）。startInternal、syncPlayback 与 switchToPlaylistIndex 共用，
     * 保证各处的身份登记口径一致。
     */
    private class CreatedPlayer(
        val player: BurnSoundPlayer,
        val source: SoundSource,

        /** 本地歌单（合成音源为 null）。 */
        val localTrackIds: List<Long>?,
        val playlistIndex: Int,
        val localTrackName: String?,
    )

    /**
     * 按时序位置 [PhasePosition] 创建播放器：
     * - 阶段携带歌单（[BurnPhase.localTrackIds] 非空，本地音乐音源）：经 [TrackRepository.getById]
     *   解析音轨（**切阶段时仅查库这一次**），MediaPlayer 播放私有目录文件，增益沿用
     *   [BurnSoundPlayer.setGain]（MediaPlayer.setVolume）路径。起播序号取
     *   [playlistStartIndex]（阶段切换默认 0；歌单推进传完成回调算好的下一序号），
     *   **不按阶段内已播时间推歌单位置**——曲目 durationMs 可能为 null，时间制不可靠；
     *   若该起点的音轨不可用，则从下一位起循环找歌单中下一条可用曲目（见
     *   [nextPlayableIndex]）；
     * - 否则按内置合成音源走 [BurnSoundPlayers]（AudioTrack 流式写入）。
     *
     * 单条音轨不可用（不存在/查库失败/文件打不开）把 id 记入 [unavailableLocalTrackIds]
     * 并转试下一条可用曲目；歌单全部不可用返回 null，调用方按「切换失败保留当前播放器」
     * （tick / 歌单推进中）或「音源初始化失败放弃会话」（startInternal 中）处理——UI 层会在
     * 配置时修剪失效曲目，此处为兜底。本会话内已判定不可用的 id 不再重复查库。
     */
    private suspend fun createPlayerFor(
        position: PhasePosition,
        playlistStartIndex: Int = 0,
    ): CreatedPlayer? {
        val playlist = position.phase.localTrackIds
        if (playlist.isEmpty()) {
            return CreatedPlayer(
                player = BurnSoundPlayers.create(position.soundSource),
                source = position.soundSource,
                localTrackIds = null,
                playlistIndex = NO_LOCAL_PLAYLIST,
                localTrackName = null,
            )
        }
        var index = playlistStartIndex.coerceIn(playlist.indices)
        while (true) {
            val trackId = playlist[index]
            if (trackId !in unavailableLocalTrackIds) {
                val track: LocalTrack? = try {
                    trackRepository.getById(trackId)
                } catch (t: Throwable) {
                    Log.e(TAG, "本地音轨查询失败（id=$trackId）", t)
                    null
                }
                if (track != null) {
                    try {
                        val player = createLocalFilePlayer(trackRepository.playbackPath(track), playlist, index)
                        return CreatedPlayer(
                            player = player,
                            source = SoundSource.LOCAL_TRACK,
                            localTrackIds = playlist,
                            playlistIndex = index,
                            localTrackName = track.displayName,
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "本地音源创建失败（id=$trackId），转试歌单下一条可用曲目", t)
                    }
                } else {
                    Log.w(TAG, "本地音轨不存在（id=$trackId，可能已被移除），转试歌单下一条可用曲目")
                }
                unavailableLocalTrackIds += trackId
            }
            // 当前候选不可用：从下一位起循环找下一条可用曲目；绕整圈仍无 → 歌单全不可用
            index = nextPlayableIndex(playlist, index, unavailableLocalTrackIds) ?: return null
        }
    }

    /**
     * 本地音乐文件播放器：MediaPlayer(path) 播放用户私有目录音轨，按 [BurnSoundPlayer]
     * 统一抽象包装（合成音源在 SynthPlayer.kt 走 AudioTrack，本文件承载本地文件路径的
     * MediaPlayer 版本）。
     *
     * 歌单循环不走 MediaPlayer 的 isLooping（置 false）：单曲循环语义升级为「播完 →
     * 推进到歌单下一可用曲目 → 切换播放器」，而 isLooping=true 时完成回调永不触发；
     * 非循环重启带来的毫秒级换曲间隙对煲机场景无损（单曲歌单同样走此路径，保持语义统一）。
     *
     * 完成回调携带创建时固化的（[playlist]、[playlistIndex]）：回调经 [onPlaylistTrackCompleted]
     * 校验「仍是当前播放身份」后才推进，避免被释放的旧播放器的滞后回调对新身份二次推进。
     */
    private fun createLocalFilePlayer(path: String, playlist: List<Long>, playlistIndex: Int): BurnSoundPlayer {
        val mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            isLooping = false
            prepare()
            setOnCompletionListener {
                onPlaylistTrackCompleted(playlist, playlistIndex)
            }
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

    /** 当前播放身份的可读描述（日志用，内部标识非用户文案）。 */
    private fun describeActiveSource(): String {
        val playlist = activeLocalTrackIds ?: return activeSource?.name ?: "无"
        return "本地歌单#$playlist@${activePlaylistIndex}"
    }

    /** 目标播放身份的可读描述（日志用，内部标识非用户文案）。 */
    private fun describeTarget(source: SoundSource, playlist: List<Long>?): String =
        playlist?.let { "本地歌单#$it" } ?: source.name

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
                .setContentText(
                    context.getString(
                        R.string.notif_complete_text,
                        formatBurnDuration(completedSeconds, context.getString(R.string.duration_cross_day_fmt)),
                    ),
                )
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
        activeLocalTrackIds = null
        activePlaylistIndex = NO_LOCAL_PLAYLIST
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

        /**
         * 播放身份非歌单时 [activePlaylistIndex] 的占位序号（永不与合法歌单下标冲突，
         * 仅用于身份三元组的相等性比较）。
         */
        const val NO_LOCAL_PLAYLIST = -1

        /** tick 周期：1 秒；实际秒数由 elapsedRealtime 锚点差推算，delay 误差不累积。 */
        const val TICK_INTERVAL_MILLIS = 1_000L

        /** 进度落库周期：每 60 个 tick（60 秒）持久化一次。 */
        const val PERSIST_EVERY_TICKS = 60L

        /** 煲机完成通知 id（与前台服务通知 1001 互不覆盖）。 */
        const val COMPLETE_NOTIFICATION_ID = 1002

        private const val COMPLETE_NOTIFICATION_REQUEST_CODE = 3
    }
}
