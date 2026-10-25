package io.github.nisu123f.honorduobar.xposed.data

import android.content.SharedPreferences
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.FeatureGate

/**
 * hook 侧功能开关读取器。
 *
 * 通道（与原 HonorHook 相同）：
 * - 宿主 App 经 `XposedService.getRemotePreferences(FeatureCatalog.REMOTE_GROUP)` 写开关；
 * - 本对象由 [io.github.nisu123f.honorduobar.xposed.HookEntry] 注入的 `getRemotePreferences` 只读。
 *
 * 读不到（框架未连接/异常）一律回目录默认值。
 */
object RemoteConfig : FeatureGate {

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 由 HookEntry 在 onPackageLoaded 注入（getRemotePreferences 是 XposedModule 的 protected 成员）。 */
    fun attach(prefs: SharedPreferences?) {
        this.prefs = prefs
    }

    override fun isEnabled(key: String): Boolean {
        val fallback = FeatureCatalog.defaultOf(key)
        return runCatching { prefs?.getBoolean(key, fallback) ?: fallback }.getOrDefault(fallback)
    }

    fun int(key: String, fallback: Int): Int =
        runCatching { prefs?.getInt(key, fallback) ?: fallback }.getOrDefault(fallback)

    fun string(key: String, fallback: String): String =
        runCatching { prefs?.getString(key, fallback) ?: fallback }.getOrDefault(fallback)
}
