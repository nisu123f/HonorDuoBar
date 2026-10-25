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
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_STYLE, trioStyle())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_LENGTH, capsuleLength())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_HEIGHT, capsuleHeight())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_STROKE, capsuleStroke())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_H, capsuleMarginH())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_V, capsuleMarginV())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE, clockMode())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, btBatteryMode())
            editor.putString(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_TEMPLATE, weatherTemplate())
            editor.putInt(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_CLICK, weatherClick())
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

    /** 三合一字形样式：0=圆环，1=胶囊。 */
    fun trioStyle(): Int {
        val p = prefs ?: return FeatureCatalog.defaultTrioStyle()
        return FeatureCatalog.resolveTrioStyle(
            runCatching {
                p.getInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_STYLE, FeatureCatalog.defaultTrioStyle())
            }.getOrDefault(FeatureCatalog.defaultTrioStyle()),
        )
    }

    fun setTrioStyle(value: Int): Boolean {
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_STYLE, value)?.apply()
        val service = FrameworkState.current() ?: run {
            XLog.log(Log.WARN, TAG, "LSPosed 未连接，三合一字形样式只写入本地")
            return false
        }
        return runCatching {
            service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
                .putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_STYLE, value)
                .apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端三合一字形样式写入失败", t)
            false
        }
    }

    /** 胶囊长度：设计单位 -30–60，0 为参考长度。 */
    fun capsuleLength(): Int {
        val p = prefs ?: return FeatureCatalog.defaultCapsuleLength()
        return FeatureCatalog.resolveCapsuleLength(
            runCatching {
                p.getInt(
                    FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_LENGTH,
                    FeatureCatalog.defaultCapsuleLength(),
                )
            }.getOrDefault(FeatureCatalog.defaultCapsuleLength()),
        )
    }

    fun setCapsuleLength(value: Int): Boolean {
        val normalized = FeatureCatalog.resolveCapsuleLength(value)
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_LENGTH, normalized)?.apply()
        return pushInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_LENGTH, normalized, "胶囊长度")
    }

    /** 胶囊高度（dp，14–26）。 */
    fun capsuleHeight(): Int {
        val p = prefs ?: return FeatureCatalog.defaultCapsuleHeight()
        return FeatureCatalog.resolveCapsuleHeight(
            runCatching {
                p.getInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_HEIGHT, FeatureCatalog.defaultCapsuleHeight())
            }.getOrDefault(FeatureCatalog.defaultCapsuleHeight()),
        )
    }

    fun setCapsuleHeight(value: Int): Boolean {
        val normalized = FeatureCatalog.resolveCapsuleHeight(value)
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_HEIGHT, normalized)?.apply()
        return pushInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_HEIGHT, normalized, "胶囊高度")
    }

    /** 胶囊线宽（占轮廓高百分比，8–18）。 */
    fun capsuleStroke(): Int {
        val p = prefs ?: return FeatureCatalog.defaultCapsuleStroke()
        return FeatureCatalog.resolveCapsuleStroke(
            runCatching {
                p.getInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_STROKE, FeatureCatalog.defaultCapsuleStroke())
            }.getOrDefault(FeatureCatalog.defaultCapsuleStroke()),
        )
    }

    fun setCapsuleStroke(value: Int): Boolean {
        val normalized = FeatureCatalog.resolveCapsuleStroke(value)
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_STROKE, normalized)?.apply()
        return pushInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_STROKE, normalized, "胶囊线宽")
    }

    /** 胶囊内左右边距（占轮廓高百分比，2–20）。 */
    fun capsuleMarginH(): Int {
        val p = prefs ?: return FeatureCatalog.defaultCapsuleMarginH()
        return FeatureCatalog.resolveCapsuleMarginH(
            runCatching {
                p.getInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_H, FeatureCatalog.defaultCapsuleMarginH())
            }.getOrDefault(FeatureCatalog.defaultCapsuleMarginH()),
        )
    }

    fun setCapsuleMarginH(value: Int): Boolean {
        val normalized = FeatureCatalog.resolveCapsuleMarginH(value)
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_H, normalized)?.apply()
        return pushInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_H, normalized, "胶囊内左右边距")
    }

    /** 胶囊内上下边距（占轮廓高百分比，10–26）。 */
    fun capsuleMarginV(): Int {
        val p = prefs ?: return FeatureCatalog.defaultCapsuleMarginV()
        return FeatureCatalog.resolveCapsuleMarginV(
            runCatching {
                p.getInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_V, FeatureCatalog.defaultCapsuleMarginV())
            }.getOrDefault(FeatureCatalog.defaultCapsuleMarginV()),
        )
    }

    fun setCapsuleMarginV(value: Int): Boolean {
        val normalized = FeatureCatalog.resolveCapsuleMarginV(value)
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_V, normalized)?.apply()
        return pushInt(FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_V, normalized, "胶囊内上下边距")
    }

    /** 单个 int 配置的远端下发（本地已写入）。 */
    private fun pushInt(key: String, value: Int, label: String): Boolean {
        val service = FrameworkState.current() ?: run {
            XLog.log(Log.WARN, TAG, "LSPosed 未连接，${label}只写入本地")
            return false
        }
        return runCatching {
            service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
                .putInt(key, value)
                .apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端${label}写入失败", t)
            false
        }
    }

    /** 天气显示模板：变量占位符由 hook 侧替换。 */
    fun weatherTemplate(): String {
        val fallback = FeatureCatalog.defaultWeatherTemplate()
        val p = prefs ?: return fallback
        return runCatching {
            p.getString(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_TEMPLATE, fallback) ?: fallback
        }.getOrDefault(fallback).ifBlank { fallback }
    }

    fun setWeatherTemplate(value: String): Boolean {
        val normalized = value.trim().ifBlank { FeatureCatalog.defaultWeatherTemplate() }
        prefs?.edit()?.putString(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_TEMPLATE, normalized)?.apply()
        val service = FrameworkState.current() ?: run {
            XLog.log(Log.WARN, TAG, "LSPosed 未连接，天气模板只写入本地")
            return false
        }
        return runCatching {
            service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
                .putString(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_TEMPLATE, normalized)
                .apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端天气模板写入失败", t)
            false
        }
    }

    /** 天气行点击行为：0=无响应，1=跳转天气，2=保持原生（流量统计）。 */
    fun weatherClick(): Int {
        val p = prefs ?: return FeatureCatalog.defaultWeatherClick()
        return FeatureCatalog.resolveWeatherClick(
            runCatching {
                p.getInt(
                    FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_CLICK,
                    FeatureCatalog.defaultWeatherClick(),
                )
            }.getOrDefault(FeatureCatalog.defaultWeatherClick()),
        )
    }

    fun setWeatherClick(value: Int): Boolean {
        val normalized = FeatureCatalog.resolveWeatherClick(value)
        prefs?.edit()?.putInt(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_CLICK, normalized)?.apply()
        val service = FrameworkState.current() ?: run {
            XLog.log(Log.WARN, TAG, "LSPosed 未连接，天气行点击行为只写入本地")
            return false
        }
        return runCatching {
            service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
                .putInt(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_CLICK, normalized)
                .apply()
            true
        }.getOrElse { t ->
            XLog.log(Log.WARN, TAG, "远端天气行点击行为写入失败", t)
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
