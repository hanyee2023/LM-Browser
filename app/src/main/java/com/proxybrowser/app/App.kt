package com.proxybrowser.app

import android.app.Application
import com.proxybrowser.app.core.V2RayManager

/** 应用入口：提前初始化 xray-core 运行环境 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        V2RayManager.init(this)
    }
}
