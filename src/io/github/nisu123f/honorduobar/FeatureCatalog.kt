package io.github.nisu123f.honorduobar

/**
 * 功能目录（功能 key 的唯一事实源）。
 *
 * 本模块只保留状态栏三合一：Wi-Fi + 移动信号 + 电量合并为一个自绘字形。
 */
object FeatureCatalog {

    /** 远端 prefs group 名（宿主 App 经 XposedService 写，hook 侧经 getRemotePreferences 只读）。 */
    const val REMOTE_GROUP = "honorduobar_config"

    const val KEY_SYSTEMUI_TRIO = "systemui.trio"
    const val KEY_SYSTEMUI_TRIO_WIFI = "systemui.trio_wifi"
    const val KEY_SYSTEMUI_TRIO_DOTS = "systemui.trio_dots"
    const val KEY_SYSTEMUI_TRIO_PERCENT = "systemui.trio_percent"
    const val KEY_SYSTEMUI_TRIO_TYPE = "systemui.trio_type"
    const val KEY_SYSTEMUI_TRIO_ACCENT = "systemui.trio_accent"
    /** 三合一防烧屏（周期性微移字形）。 */
    const val KEY_SYSTEMUI_TRIO_ANTI_BURNIN = "systemui.trio_anti_burnin"
    /** 三合一图标尺寸（dp，18–26）。 */
    const val KEY_SYSTEMUI_TRIO_SIZE = "systemui.trio_size"

    /** 宿主包名（本模块唯一作用域）。 */
    const val HOST_PACKAGE = "com.android.systemui"

    /** 宿主版本基线（荣耀 SystemUI 17.0.0.1，jadx 实证；仅用于日志自检，不做门控）。 */
    const val BASELINE_SYSTEMUI = "17.0.0.1"

    fun baselineVersionOf(packageName: String): String? =
        if (packageName == HOST_PACKAGE) BASELINE_SYSTEMUI else null

    /**
     * 一条功能开关。
     *
     * @param dependsOn 依赖的上级开关 key（上级关闭时 UI 置灰；hook 侧由各 Feature 自行判）
     */
    data class Feature(
        val key: String,
        val title: String,
        val desc: String,
        val defaultEnabled: Boolean,
        val dependsOn: String? = null,
    )

    /** 设置页展示顺序 = 此列表顺序。 */
    val features: List<Feature> = listOf(
        Feature(
            KEY_SYSTEMUI_TRIO,
            "状态栏三合一",
            "把 Wi-Fi、移动信号、电量合并成一个 Duo 风格字形：外圈电量环、环内 Wi-Fi 弧与电量数字、底部信号点；原生三图标隐藏。",
            defaultEnabled = true,
        ),
        Feature(
            KEY_SYSTEMUI_TRIO_WIFI,
            "三合一：显示 Wi-Fi 弧",
            "环内上部绘制 Wi-Fi 弧线，弧数跟随真实信号强度；关闭后该位置留空。",
            defaultEnabled = true,
            dependsOn = KEY_SYSTEMUI_TRIO,
        ),
        Feature(
            KEY_SYSTEMUI_TRIO_DOTS,
            "三合一：显示移动信号点",
            "环内底部绘制 4 个信号点（单卡，取默认数据卡）；关闭后不画信号点。",
            defaultEnabled = true,
            dependsOn = KEY_SYSTEMUI_TRIO,
        ),
        Feature(
            KEY_SYSTEMUI_TRIO_PERCENT,
            "三合一：显示电量数字",
            "环心显示电量百分比；充电时固定显示闪电，此开关不影响充电字形。",
            defaultEnabled = true,
            dependsOn = KEY_SYSTEMUI_TRIO,
        ),
        Feature(
            KEY_SYSTEMUI_TRIO_TYPE,
            "三合一：无 Wi-Fi 时显示 5G/4G",
            "没有 Wi-Fi 时环内上部改显示当前网络制式文本。",
            defaultEnabled = true,
            dependsOn = KEY_SYSTEMUI_TRIO,
        ),
        Feature(
            KEY_SYSTEMUI_TRIO_ACCENT,
            "三合一：彩色强调",
            "充电绿色、快充琥珀、低电量红色；关闭后所有元素跟随状态栏反色。",
            defaultEnabled = true,
            dependsOn = KEY_SYSTEMUI_TRIO,
        ),
        Feature(
            KEY_SYSTEMUI_TRIO_ANTI_BURNIN,
            "三合一：防烧屏微移",
            "每 30 秒把字形沿 3×3 网格微移 ±1dp，避免 OLED 长时间点亮同一组像素；肉眼几乎不可察觉。",
            defaultEnabled = false,
            dependsOn = KEY_SYSTEMUI_TRIO,
        ),
    )

    private val defaults: Map<String, Boolean> by lazy { features.associate { it.key to it.defaultEnabled } }

    fun defaultOf(key: String): Boolean = defaults[key] ?: false

    /** 三合一尺寸默认值（dp，可直接在 18–26 之间调节）。 */
    fun defaultTrioSize(): Int = 20

    /** 尺寸值归一：0/1/2 是旧的三档索引，新值直接就是 dp。 */
    fun resolveTrioSize(raw: Int): Int = when (raw) {
        in 18..26 -> raw
        0 -> 18
        1 -> 22
        2 -> 26
        else -> defaultTrioSize()
    }
}
