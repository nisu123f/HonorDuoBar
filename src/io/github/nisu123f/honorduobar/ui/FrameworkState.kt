package io.github.nisu123f.honorduobar.ui

import io.github.libxposed.service.XposedService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LSPosed 服务连接状态（宿主 App 侧）。
 *
 * 只有收到 LSPosed 的 XposedService Binder 才算「已激活」——本地开关写远端 prefs
 * 依赖这个服务，没有服务时改动只落本地，hook 侧读不到。
 */
object FrameworkState {

    @Volatile
    private var service: XposedService? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun current(): XposedService? = service

    fun isConnected(): Boolean = service != null

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun onBind(s: XposedService) {
        service = s
        notifyChanged()
    }

    fun onDied(s: XposedService) {
        if (service === s) service = null
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }
}
