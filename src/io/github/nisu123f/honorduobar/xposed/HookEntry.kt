package io.github.nisu123f.honorduobar.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.XLog
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig
import io.github.nisu123f.honorduobar.xposed.target.SystemUiHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed 102 模块入口（注册于 META-INF/xposed/java_init.list）。
 *
 * 本独立版只有 SystemUI 一个目标：注入 `com.android.systemui` 进程，读一次远端开关后
 * 按开关挂载各功能点。任何异常只记日志，绝不炸宿主进程。
 */
class HookEntry : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != FeatureCatalog.HOST_PACKAGE) return
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            // 统一日志门面登记（此后全模块日志写 LSPosed 模块日志，见 XLog）
            XLog.attach(this)
            // 远端 prefs 缓存注入（一次 Binder 读取；读失败回 null = 按目录默认值）
            RemoteConfig.attach(runCatching { getRemotePreferences(FeatureCatalog.REMOTE_GROUP) }.getOrNull())
            SystemUiHook.install(this, param)
            XLog.log(Log.INFO, TAG, "target installed: ${param.packageName}/SystemUiHook")
        }.onFailure { error ->
            XLog.log(Log.ERROR, TAG, "target install failed for ${param.packageName}", error)
        }
    }

    private companion object {
        const val TAG = "HonorSystemUI"
        val installed = AtomicBoolean(false)
    }
}
