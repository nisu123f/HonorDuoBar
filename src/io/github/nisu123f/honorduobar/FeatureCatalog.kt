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
    /** 三合一电量环按百分比变色（≤10% 红、11–20% 橙）。 */
    const val KEY_SYSTEMUI_TRIO_LEVEL_COLOR = "systemui.trio_level_color"
    /** 三合一防烧屏（周期性微移字形）。 */
    const val KEY_SYSTEMUI_TRIO_ANTI_BURNIN = "systemui.trio_anti_burnin"
    /** 灵动胶囊展开时保留蓝牙等状态图标（不让原生按胶囊边界把图标淡出）。 */
    const val KEY_SYSTEMUI_TRIO_KEEP_ICONS = "systemui.trio_keep_icons"
    /** 三合一图标尺寸（dp，18–26）。 */
    const val KEY_SYSTEMUI_TRIO_SIZE = "systemui.trio_size"

    /**
     * 三合一蓝牙耳机电量（int 三态）：0=关闭，1=圆弧，2=数字。
     * 旧版同键 boolean 会被 getInt 读到 ClassCastException → runCatching 回默认 0（关闭），自然迁移。
     */
    const val KEY_SYSTEMUI_TRIO_BT_BATTERY = "systemui.trio_bt_battery"

    /**
     * 旧版「隐藏状态栏时钟」布尔键（v1.0.8 及以前）。
     * 保留仅用于迁移：远端/本地只有此键时，true 折算为 clock_mode=1（隐藏）。
     */
    const val KEY_SYSTEMUI_HIDE_CLOCK = "systemui.hide_clock"

    /** 状态栏时钟模式（int）：0=不修改，1=隐藏，2=显秒。 */
    const val KEY_SYSTEMUI_CLOCK_MODE = "systemui.clock_mode"

    /** 隐藏控制中心关机按钮（右上角电源图标）。 */
    const val KEY_SYSTEMUI_HIDE_CC_POWER = "systemui.hide_cc_power"

    /** 灵动胶囊消息上岛常驻时禁用状态栏缩放（上岛缩小动画播完后触发系统原生回弹恢复，展开/关闭动画不动）。 */
    const val KEY_SYSTEMUI_CAPSULE_NO_SCALE = "systemui.capsule_no_scale"

    /** 通知中心顶部日期行追加农历（「·九月初四」后缀）。 */
    const val KEY_SYSTEMUI_SHADE_LUNAR = "systemui.shade_lunar"

    /** 隐藏通知中心大时间右侧的铃铛按钮（通知管理入口）。 */
    const val KEY_SYSTEMUI_HIDE_NOTIF_BELL = "systemui.hide_notif_bell"

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
            KEY_SYSTEMUI_TRIO_LEVEL_COLOR,
            "三合一：按电量变色",
            "电量环与环心数字随百分比变色：≤10% 红、11–20% 橙、>20% 跟随反色（与原生电池图标行为一致）；充电时统一显示绿色。",
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
        Feature(
            KEY_SYSTEMUI_TRIO_KEEP_ICONS,
            "灵动胶囊：展开时保留图标",
            "灵动胶囊展开时，原生会按胶囊边界把蓝牙等状态图标淡出；三合一已腾出空间，开启后保持这些图标可见。",
            defaultEnabled = true,
            dependsOn = KEY_SYSTEMUI_TRIO,
        ),
        Feature(
            KEY_SYSTEMUI_CAPSULE_NO_SCALE,
            "灵动胶囊：状态栏不缩放",
            "消息上岛常驻时，原生会把状态栏图标微缩（约 88%）给胶囊腾位置。开启后等上岛缩小动画播完，立即触发系统原生回弹动画恢复原始大小；点击展开/关闭胶囊的动画保持原生不变。改动需重启系统界面生效。",
            defaultEnabled = false,
        ),
        Feature(
            KEY_SYSTEMUI_HIDE_NOTIF_BELL,
            "隐藏通知中心铃铛按钮",
            "隐藏通知中心大时间右侧的铃铛图标（通知管理入口，仍可从系统设置进入）；隐藏后日期行可获得更多显示宽度。",
            defaultEnabled = false,
        ),
        Feature(
            KEY_SYSTEMUI_SHADE_LUNAR,
            "通知中心农历",
            "在通知中心（状态栏左侧下滑面板）顶部原日期后直接显示农历：「10月24日周六九月初四」。需先开启「隐藏通知中心铃铛按钮」腾出宽度。",
            defaultEnabled = false,
            dependsOn = KEY_SYSTEMUI_HIDE_NOTIF_BELL,
        ),
        Feature(
            KEY_SYSTEMUI_HIDE_CC_POWER,
            "隐藏控制中心关机按钮",
            "把控制中心（状态栏右侧下滑面板）右上角的电源按钮设为 GONE；关机仍可通过长按电源键触发。",
            defaultEnabled = false,
        ),
    )

    private val defaults: Map<String, Boolean> by lazy { features.associate { it.key to it.defaultEnabled } }

    fun defaultOf(key: String): Boolean = defaults[key] ?: false

    /** 三合一尺寸默认值（dp，可直接在 18–26 之间调节）。 */
    fun defaultTrioSize(): Int = 20

    /** 时钟模式默认值：不修改。 */
    fun defaultClockMode(): Int = 0

    /** 蓝牙耳机电量默认值：关闭。 */
    fun defaultBtBatteryMode(): Int = 0

    /** 尺寸值归一：0/1/2 是旧的三档索引，新值直接就是 dp。 */
    fun resolveTrioSize(raw: Int): Int = when (raw) {
        in 18..26 -> raw
        0 -> 18
        1 -> 22
        2 -> 26
        else -> defaultTrioSize()
    }
}
