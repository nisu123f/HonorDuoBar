package io.github.nisu123f.honorduobar.xposed.core

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig

/**
 * 统一日志门面：全模块（hook 侧与 App 进程侧）唯一日志入口。
 *
 * - hook 侧：[HookEntry] 在注入时 [attach]，此后日志写入 LSPosed 模块日志（管理器可见）；
 * - App 进程侧（设置页等）或 attach 之前：自动退化 logcat（android.util.Log）。
 *
 * 业务代码一律调本类，不再直接持有 XposedModule 写日志，也不再直接调 android.util.Log。
 */
object XLog {

    @Volatile private var module: XposedModule? = null

    /** 注入侧入口登记 module 引用（每进程一次，幂等）。 */
    fun attach(module: XposedModule) {
        this.module = module
    }

    /** 常开日志：挂载判据、状态翻转等低频关键事件。 */
    fun log(priority: Int, tag: String, msg: String, tr: Throwable? = null) {
        val m = module
        if (m != null) {
            if (tr != null) m.log(priority, tag, msg, tr) else m.log(priority, tag, msg)
        } else {
            Log.println(priority, tag, if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)
        }
    }

    /** 诊断日志：受「诊断日志」全局开关门控，翻转即时生效；仅状态翻转级事件使用，禁止逐帧调用。 */
    fun diag(priority: Int, tag: String, msg: String) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_DEBUG_LOG)) return
        log(priority, tag, msg)
    }
}
