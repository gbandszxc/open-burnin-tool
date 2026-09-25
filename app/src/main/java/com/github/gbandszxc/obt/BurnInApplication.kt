package com.github.gbandszxc.obt

import android.app.Application
import com.github.gbandszxc.obt.data.AppContainer
import com.github.gbandszxc.obt.locale.AppLocale
import kotlinx.coroutines.runBlocking

/** 应用入口：持有手动注入容器，保证数据库/仓库全局单例。 */
class BurnInApplication : Application() {

    val appContainer: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // 冷启动同步初始化进程级应用语言（本地文件毫秒级读取）：首个 Activity/Service 的
        // attachBaseContext 早于任何异步流，必须在此前就绪，否则首帧语言错误。
        runBlocking {
            AppLocale.update(appContainer.settingsRepository.languageOnce())
        }
    }
}
