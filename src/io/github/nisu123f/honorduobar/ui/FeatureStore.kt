package io.github.nisu123f.honorduobar.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.XLog

/**
 * 功能开关存储（宿主侧）——本地镜像 + 远端下发双写。
 *
 * - 本地 SharedPreferences：UI 即时读取显示；
 * - 远端 prefs（[FeatureCatalog.REMOTE_GROUP]）：hook 侧在 SystemUI 进程只读此通道。
 *
 * 开关改动需 SystemUI 进程重启（下次注入读取）才生效。
 */
object FeatureStore {

    private const val PREFS = "features"
    private const val TAG = "HonorSystemUI"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isEnabled(key: String): Boolean {
        val fallback = FeatureCatalog.defaultOf(key)
        return runCatching { prefs?.getBoolean(key, fallback) ?: fallback }.getOrDefault(fallback)
    }

    /**
     * 写开关：本地立即生效 + 远端下发。
     *
     * @return 远端下发是否成功；false 表示框架未连接（hook 侧读不到本次改动）
     */
    fun setEnabled(key: String, enabled: Boolean): Boolean {
        prefs?.edit()?.putBoolean(key, enabled)?.apply()
        val service = FrameworkState.current() ?: run {
            XLog.log(Log.WARN, TAG, "LSPosed 未连接，开关只写入本地: $key")
            return false
        }
        return runCatching {
            val editor = service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
            editor.putBoolean(key, enabled)
            editor.apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端 prefs 下发失败: $key", t)
            false
        }
    }

    /** 全量同步本地 → 远端（服务连接时调用，保证 UI 与 hook 侧一致）。 */
    fun pushAll(): Boolean {
        val service = FrameworkState.current() ?: return false
        return runCatching {
            val editor = service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
            FeatureCatalog.features.forEach { editor.putBoolean(it.key, isEnabled(it.key)) }
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_SIZE, trioSize())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE, clockMode())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, btBatteryMode())
            editor.apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端 prefs 批量下发失败", t)
            false
        }
    }

    fun trioSize(): Int =
        runCatching {
            prefs?.getInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_SIZE, FeatureCatalog.defaultTrioSize())
                ?: FeatureCatalog.defaultTrioSize()
        }.getOrDefault(FeatureCatalog.defaultTrioSize())

    fun setTrioSize(value: Int): Boolean {
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_SIZE, value)?.apply()
        val service = FrameworkState.current() ?: run {
            XLog.log(Log.WARN, TAG, "LSPosed 未连接，尺寸只写入本地")
            return false
        }
        return runCatching {
            val editor = service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_SIZE, value)
            editor.apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端尺寸下发失败", t)
            false
        }
    }

    /** 时钟模式：0=不修改，1=隐藏，2=显秒。 */
    fun clockMode(): Int {
        val p = prefs ?: return FeatureCatalog.defaultClockMode()
        return runCatching {
            p.getInt(FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE, FeatureCatalog.defaultClockMode())
        }.getOrDefault(FeatureCatalog.defaultClockMode())
    }

    fun setClockMode(value: Int): Boolean {
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE, value)?.apply()
        val service = FrameworkState.current() ?: run {
            XLog.log(Log.WARN, TAG, "LSPosed 未连接，时钟模式只写入本地")
            return false
        }
        return runCatching {
            val editor = service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE, value)
            editor.apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端时钟模式写入失败", t)
            false
        }
    }

    /** 蓝牙耳机电量模式：0=关闭，1=圆弧，2=数字。 */
    fun btBatteryMode(): Int {
        val p = prefs ?: return FeatureCatalog.defaultBtBatteryMode()
        return runCatching {
            p.getInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, FeatureCatalog.defaultBtBatteryMode())
        }.getOrDefault(FeatureCatalog.defaultBtBatteryMode()).coerceIn(0, 2)
    }

    fun setBtBatteryMode(value: Int): Boolean {
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, value)?.apply()
        val service = FrameworkState.current() ?: run {
            XLog.log(Log.WARN, TAG, "LSPosed 未连接，蓝牙电量模式只写入本地")
            return false
        }
        return runCatching {
            service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
                .putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, value)
                .apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端蓝牙电量模式写入失败", t)
            false
        }
    }
}
