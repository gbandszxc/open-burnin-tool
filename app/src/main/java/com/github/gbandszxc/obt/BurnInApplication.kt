package com.github.gbandszxc.obt

import android.app.Application
import com.github.gbandszxc.obt.data.AppContainer

/** 应用入口：持有手动注入容器，保证数据库/仓库全局单例。 */
class BurnInApplication : Application() {

    val appContainer: AppContainer by lazy { AppContainer(this) }
}
