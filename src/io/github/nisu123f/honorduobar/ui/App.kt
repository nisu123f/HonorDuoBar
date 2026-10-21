package io.github.nisu123f.honorduobar.ui

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** 宿主 App 入口：初始化开关存储并注册 LSPosed 服务监听。 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        FeatureStore.init(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        FrameworkState.onBind(service)
        // 服务连上后把本地开关全量下发，避免 UI 显示与 hook 侧读到的值不一致
        FeatureStore.pushAll()
    }

    override fun onServiceDied(service: XposedService) {
        FrameworkState.onDied(service)
    }
}
