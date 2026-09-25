package com.github.gbandszxc.obt.data

import android.content.Context
import com.github.gbandszxc.obt.playback.PlaybackController

/**
 * 手动依赖注入容器（Application 级单例，不用 Hilt）。
 * UI/Service 层通过 `(application as BurnInApplication).appContainer` 获取仓库与播放控制器。
 */
class AppContainer(appContext: Context) {

    val database: BurnInDatabase by lazy { BurnInDatabase.create(appContext) }

    /** 应用设置仓库：主题模式/调色盘/动态取色等持久化设置（后续播放类设置同样收敛于此）。 */
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val burnInRepository: BurnInRepository by lazy { BurnInRepository(database.burnInSessionDao()) }

    /** 本地音轨仓库：本地音乐导入/查询/删除（context 传入用于私有目录与 ContentResolver）。 */
    val trackRepository: TrackRepository by lazy {
        TrackRepository(appContext.applicationContext, database.localTrackDao())
    }

    /** 播放控制器：控制逻辑与状态流中枢，全应用唯一实例。 */
    val playbackController: PlaybackController by lazy {
        PlaybackController(appContext.applicationContext, burnInRepository, trackRepository)
    }
}
