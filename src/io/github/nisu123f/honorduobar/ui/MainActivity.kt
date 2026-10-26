package io.github.nisu123f.honorduobar.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.MagicOsVersion

/** 设置页：纯代码 UI（无资源依赖，便于独立构建）。 */
class MainActivity : Activity() {

    private val colorBg = 0xFFF4F5F7.toInt()
    private val colorCard = Color.WHITE
    private val colorText = 0xFF17181A.toInt()
    private val colorSub = 0xFF6B7280.toInt()
    private val colorAccent = 0xFF2563EB.toInt()
    private val colorOk = 0xFF16A34A.toInt()
    private val colorWarn = 0xFFB45309.toInt()
    private val colorDivider = 0xFFE7E8EB.toInt()

    private lateinit var statusText: TextView
    private lateinit var sizeValue: TextView
    private lateinit var sizeRow: LinearLayout
    private lateinit var clockValue: TextView
    private lateinit var btStyleValue: TextView
    private lateinit var weatherTemplateRow: LinearLayout
    private lateinit var weatherTemplateValue: TextView
    private lateinit var weatherClickRow: LinearLayout
    private lateinit var weatherClickValue: TextView
    private lateinit var styleRow: LinearLayout
    private lateinit var styleValue: TextView
    private lateinit var capsuleSettingsRow: LinearLayout
    private lateinit var capsuleSettingsValue: TextView
    private val switches = HashMap<String, Switch>()
    private val listeners = ArrayList<() -> Unit>()

    private val serviceListener: () -> Unit = {
        runOnUiThread { updateStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 去掉系统标题栏（应用名常驻占屏幕空间，无实际作用）
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        actionBar?.hide()
        applyLightSystemBars()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }

        root.addView(buildStatusCard())
        root.addView(sectionTitle("状态栏"))
        root.addView(buildCard(featureRows {
            it.key !in notifCenterKeys && it.key !in controlCenterKeys &&
                it.key !in capsuleKeys && it.key !in advancedKeys
        } + buildClockRow() + buildStyleRow() + buildCapsuleSettingsRow() + buildSizeRow() + buildBtStyleRow()))
        root.addView(sectionTitle("灵动胶囊"))
        root.addView(buildCard(featureRows { it.key in capsuleKeys }))
        root.addView(sectionTitle("通知中心"))
        root.addView(buildCard(featureRows { it.key in notifCenterKeys } + buildWeatherTemplateRow() + buildWeatherClickRow()))
        root.addView(sectionTitle("控制中心"))
        root.addView(buildCard(featureRows { it.key in controlCenterKeys }))
        root.addView(sectionTitle("高级"))
        root.addView(buildCard(featureRows { it.key in advancedKeys }))
        root.addView(buildTipCard())
        root.addView(buildRestartButton())

        setContentView(ScrollView(this).apply { addView(root) })
        updateStatus()
        renderDependency()
    }

    /**
     * 设置页是浅色背景（#F4F5F7 底 + 白色卡片），系统默认的白色状态栏图标会看不清。
     * 这里把状态栏/导航栏底色改成页面背景色，并声明「浅色系统栏」让图标转深色。
     */
    private fun applyLightSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = colorBg
        window.navigationBarColor = colorBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    override fun onStart() {
        super.onStart()
        FrameworkState.addListener(serviceListener)
        updateStatus()
        refreshSwitchStates()
    }

    override fun onStop() {
        FrameworkState.removeListener(serviceListener)
        super.onStop()
    }

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(colorCard, 14f, colorDivider)
        }
        statusText = TextView(this).apply {
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        card.addView(statusText)
        card.addView(TextView(this).apply {
            text = "作用域：com.android.systemui（需在 LSPosed 中勾选并启用本模块）"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            val major = MagicOsVersion.major
            text = "系统：MagicOS ${major ?: "未知"}" +
                if (major == 10) "（反色兼容开关已启用）" else ""
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, 0)
        })
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
        return card
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(colorSub)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(18), 0, dp(8))
    }

    private fun buildCard(rows: List<View>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colorCard, 16f, 0)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        rows.forEachIndexed { index, row ->
            card.addView(row)
            if (index != rows.lastIndex) {
                card.addView(View(this).apply {
                    setBackgroundColor(colorDivider)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1),
                    )
                })
            }
        }
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return card
    }

    /** 生成开关行（[belongs] 决定哪些功能进本组卡片）。 */
    private fun featureRows(belongs: (FeatureCatalog.Feature) -> Boolean): List<View> =
        FeatureCatalog.features.filter(belongs).mapNotNull { feature ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = feature.title
                setTextColor(colorText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            texts.addView(TextView(this).apply {
                text = feature.desc
                setTextColor(colorSub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(3), dp(12), 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            row.addView(texts)

            val toggle = Switch(this).apply {
                isChecked = FeatureStore.isEnabled(feature.key)
                showText = false
                setOnCheckedChangeListener { _, checked ->
                    if (feature.dependsOn != null && !FeatureStore.isEnabled(feature.dependsOn)) {
                        isChecked = false
                        return@setOnCheckedChangeListener
                    }
                    val pushed = FeatureStore.setEnabled(feature.key, checked)
                    if (!pushed) {
                        toast("已保存到本地，但 LSPosed 未连接，本次改动不会下发")
                    }
                    if (feature.key == FeatureCatalog.KEY_SYSTEMUI_TRIO ||
                        feature.key == FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER
                    ) {
                        renderDependency()
                    }
                    if (FeatureCatalog.features.any { it.dependsOn == feature.key }) {
                        renderDependency()
                    }
                }
            }
            switches[feature.key] = toggle
            row.addView(toggle)
            row
        }

    private fun buildClockRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showClockModeDialog() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "状态栏时间"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = "不修改：保持系统默认；隐藏：时钟整个不显示；显秒：时间精确到秒（与三合一独立）。"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), dp(12), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        row.addView(texts)
        clockValue = TextView(this).apply {
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        row.addView(clockValue)
        updateClockText()
        return row
    }

    private fun clockModeLabel(mode: Int): String = when (mode) {
        1 -> "隐藏"
        2 -> "显秒"
        else -> "不修改"
    }

    private fun updateClockText() {
        clockValue.text = "${clockModeLabel(FeatureStore.clockMode())} ›"
    }

    private fun showClockModeDialog() {
        val labels = arrayOf("不修改", "隐藏", "显秒")
        val current = FeatureStore.clockMode().coerceIn(0, labels.size - 1)
        AlertDialog.Builder(this)
            .setTitle("状态栏时间")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val pushed = FeatureStore.setClockMode(which)
                updateClockText()
                if (!pushed) toast("已保存到本地，但 LSPosed 未连接，本次改动不会下发")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildBtStyleRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showBtStyleDialog() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "蓝牙耳机电量"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = "三合一底部信号点在蓝牙耳机连接时的替换样式：关闭保持原信号点；圆弧沿信号点轨迹画一段弧线；数字直接显示百分比。同时隐藏原生蓝牙图标避免重复。需先开启「状态栏三合一」，改动后需重启系统界面并重连蓝牙耳机生效。"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), dp(12), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        row.addView(texts)
        btStyleValue = TextView(this).apply {
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        row.addView(btStyleValue)
        updateBtStyleText()
        return row
    }

    private fun buildWeatherTemplateRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showWeatherTemplateDialog() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "天气显示模板"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = "用变量自由拼接，变量之间可加空格、「·」「/」等任意字符。可用：{定位} {天气} {天气图标} {当前温度} {最低温度} {最高温度} {体感温度} {湿度} {紫外线} {风向} {风速} {空气质量} {AQI} {PM25} {PM10} {气压} {日出} {日落} {明日天气} {明日最高} {明日最低}。体感仅在温差>2℃时输出；改动需重启系统界面生效。"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), dp(12), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        row.addView(texts)
        weatherTemplateValue = TextView(this).apply {
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        row.addView(weatherTemplateValue)
        weatherTemplateRow = row
        updateWeatherTemplateText()
        return row
    }

    private fun updateWeatherTemplateText() {
        val tpl = FeatureStore.weatherTemplate()
        weatherTemplateValue.text = "${if (tpl.length > 16) tpl.take(15) + "…" else tpl} ›"
    }

    private fun showWeatherTemplateDialog() {
        if (switches[FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER]?.isChecked != true) {
            toast("请先开启「通知中心天气」")
            return
        }
        val input = EditText(this).apply {
            setText(FeatureStore.weatherTemplate())
            setSingleLine(true)
            setSelection(text.length)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("天气显示模板")
            .setMessage("可用变量：{定位} {天气} {天气图标} {当前温度} {最低温度} {最高温度} {体感温度} {湿度} {紫外线} {风向} {风速} {空气质量} {AQI} {PM25} {PM10} {气压} {日出} {日落} {明日天气} {明日最高} {明日最低}")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val pushed = FeatureStore.setWeatherTemplate(input.text.toString())
                updateWeatherTemplateText()
                if (!pushed) toast("已保存到本地，但 LSPosed 未连接，本次改动不会下发")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildWeatherClickRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showWeatherClickDialog() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "天气行点击行为"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = "仅在「流量显示关闭、该行只显示天气」时生效：点天气可跳转系统天气 App、不响应，或保持原生跳转流量统计。改动需重启系统界面生效。"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), dp(12), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        row.addView(texts)
        weatherClickValue = TextView(this).apply {
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        row.addView(weatherClickValue)
        weatherClickRow = row
        updateWeatherClickText()
        return row
    }

    private fun weatherClickLabel(mode: Int): String = when (mode) {
        0 -> "无响应"
        2 -> "流量统计"
        else -> "跳转天气"
    }

    private fun updateWeatherClickText() {
        weatherClickValue.text = "${weatherClickLabel(FeatureStore.weatherClick())} ›"
    }

    private fun showWeatherClickDialog() {
        if (switches[FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER]?.isChecked != true) {
            toast("请先开启「通知中心天气」")
            return
        }
        val labels = arrayOf("跳转天气 App", "无响应", "保持原生（流量统计）")
        // 设置值 0/1/2 与选项顺序不同：跳转天气=1、无响应=0、流量统计=2
        val modes = intArrayOf(1, 0, 2)
        val current = modes.indexOf(FeatureStore.weatherClick()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("天气行点击行为")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val pushed = FeatureStore.setWeatherClick(modes[which])
                updateWeatherClickText()
                if (!pushed) toast("已保存到本地，但 LSPosed 未连接，本次改动不会下发")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun btStyleLabel(mode: Int): String = when (mode) {
        1 -> "圆弧"
        2 -> "数字"
        else -> "关闭"
    }
    private fun updateBtStyleText() {
        btStyleValue.text = "${btStyleLabel(FeatureStore.btBatteryMode())} ›"
    }

    private fun showBtStyleDialog() {
        if (switches[FeatureCatalog.KEY_SYSTEMUI_TRIO]?.isChecked != true) {
            toast("请先开启「状态栏三合一」")
            return
        }
        val labels = arrayOf("关闭", "圆弧", "数字")
        val current = FeatureStore.btBatteryMode().coerceIn(0, labels.size - 1)
        AlertDialog.Builder(this)
            .setTitle("蓝牙耳机电量")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val pushed = FeatureStore.setBtBatteryMode(which)
                updateBtStyleText()
                if (!pushed) toast("已保存到本地，但 LSPosed 未连接，本次改动不会下发")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildStyleRow(): View {
        styleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showStyleDialog() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "三合一图标样式"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = "圆环：圆形电量环 + 环内 Wi-Fi/制式 + 底部信号点。胶囊：胶囊轮廓即电量，左格 Wi-Fi/制式、右格电量数字，上/下边缘为主卡/副卡信号点。需先开启「状态栏三合一」，改动后需重启系统界面生效。"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), dp(12), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        styleRow.addView(texts)
        styleValue = TextView(this).apply {
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        styleRow.addView(styleValue)
        updateStyleText()
        return styleRow
    }

    private fun styleLabel(style: Int): String = if (style == STYLE_CAPSULE) "胶囊" else "圆环"

    private fun updateStyleText() {
        styleValue.text = "${styleLabel(FeatureStore.trioStyle())} ›"
    }

    private fun showStyleDialog() {
        if (switches[FeatureCatalog.KEY_SYSTEMUI_TRIO]?.isChecked != true) {
            toast("请先开启「状态栏三合一」")
            return
        }
        val labels = arrayOf("圆环", "胶囊")
        val current = FeatureStore.trioStyle().coerceIn(0, labels.size - 1)
        AlertDialog.Builder(this)
            .setTitle("三合一图标样式")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val pushed = FeatureStore.setTrioStyle(which)
                updateStyleText()
                renderDependency()
                if (!pushed) toast("已保存到本地，但 LSPosed 未连接，本次改动不会下发")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「胶囊样式设置」入口行：胶囊详细参数（预览 + 滑块）折叠在此行点开的设置弹窗内。 */
    private fun buildCapsuleSettingsRow(): View {
        capsuleSettingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showCapsuleSettingsDialog() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "胶囊样式设置"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = "胶囊预览与长度/高度/线宽/内边距调节；仅胶囊样式生效，改动需重启系统界面后在状态栏生效。"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), dp(12), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        capsuleSettingsRow.addView(texts)
        capsuleSettingsValue = TextView(this).apply {
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        capsuleSettingsRow.addView(capsuleSettingsValue)
        updateCapsuleSettingsText()
        return capsuleSettingsRow
    }

    /** 当前胶囊高度 + 长度 + 线宽对应的实际宽度（dp）。 */
    private fun capsuleWidthDp(length: Int): Int {
        val h = FeatureCatalog.resolveCapsuleHeight(FeatureStore.capsuleHeight())
        return (h * FeatureCatalog.capsuleAspect(length, FeatureStore.capsuleStroke()) + 0.5f).toInt()
    }

    private fun updateCapsuleSettingsText() {
        val height = FeatureCatalog.resolveCapsuleHeight(FeatureStore.capsuleHeight())
        capsuleSettingsValue.text = "${capsuleWidthDp(FeatureStore.capsuleLength())}×${height}dp ›"
    }

    /**
     * 胶囊样式设置弹窗（折叠的详细设置页）：顶部实时预览，
     * 下方长度/高度/线宽/左右边距/上下边距滑块，拖动即保存并刷新预览。
     */
    private fun showCapsuleSettingsDialog() {
        if (switches[FeatureCatalog.KEY_SYSTEMUI_TRIO]?.isChecked != true) {
            toast("请先开启「状态栏三合一」")
            return
        }
        if (FeatureStore.trioStyle() != STYLE_CAPSULE) {
            toast("请先把「三合一图标样式」设为胶囊")
            return
        }
        var warned = false
        val saveParam: ((Int) -> Boolean, Int) -> Unit = { setter, v ->
            if (!setter(v) && !warned) {
                warned = true
                toast("已保存到本地，但 LSPosed 未连接，本次改动不会下发")
            }
            updateCapsuleSettingsText()
        }
        val preview = CapsulePreviewView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(16))
            addView(preview)
        })

        /** 弹窗内滑块行：标题 + 当前值一行、下面 SeekBar；拖动即保存并刷新预览。 */
        fun slider(
            label: String,
            min: Int,
            max: Int,
            getter: () -> Int,
            format: (Int) -> String,
            setter: (Int) -> Boolean,
        ) {
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(TextView(this).apply {
                text = label
                setTextColor(colorText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val valueText = TextView(this).apply {
                setTextColor(colorAccent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                text = format(getter())
            }
            head.addView(valueText)
            item.addView(head)
            item.addView(SeekBar(this).apply {
                this.max = max - min
                progress = (getter() - min).coerceIn(0, this.max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = min + progress
                        valueText.text = format(v)
                        saveParam(setter, v)
                        preview.refresh()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            })
            box.addView(item)
        }

        slider(
            "长度（设计单位）", CAPSULE_LEN_MIN, CAPSULE_LEN_MAX,
            { FeatureStore.capsuleLength() }, { v -> "${capsuleWidthDp(v)}dp 宽" },
        ) { v -> FeatureStore.setCapsuleLength(v) }
        slider(
            "胶囊高度", CAP_HEIGHT_MIN, CAP_HEIGHT_MAX,
            { FeatureStore.capsuleHeight() }, { v -> "$v dp" },
        ) { v -> FeatureStore.setCapsuleHeight(v) }
        slider(
            "胶囊线宽", CAP_STROKE_MIN, CAP_STROKE_MAX,
            { FeatureStore.capsuleStroke() }, { v -> "$v%" },
        ) { v -> FeatureStore.setCapsuleStroke(v) }
        slider(
            "内部左右边距", CAP_MARGIN_H_MIN, CAP_MARGIN_H_MAX,
            { FeatureStore.capsuleMarginH() }, { v -> "$v%" },
        ) { v -> FeatureStore.setCapsuleMarginH(v) }
        slider(
            "内部上下边距", CAP_MARGIN_V_MIN, CAP_MARGIN_V_MAX,
            { FeatureStore.capsuleMarginV() }, { v -> "$v%" },
        ) { v -> FeatureStore.setCapsuleMarginV(v) }

        AlertDialog.Builder(this)
            .setTitle("胶囊样式设置")
            .setView(box)
            .setPositiveButton("完成") { _, _ -> updateCapsuleSettingsText() }
            .show()
    }

    private fun buildSizeRow(): View {
        sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showSizeDialog() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = "三合一图标大小"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(this).apply {
            text = "可直接在 18–26 dp 之间调节，固定尺寸，横屏与锁屏不放大。"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), dp(12), 0)
        })
        sizeRow.addView(texts)
        sizeValue = TextView(this).apply {
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        sizeRow.addView(sizeValue)
        updateSizeText()
        return sizeRow
    }

    private fun updateSizeText() {
        sizeValue.text = "${FeatureCatalog.resolveTrioSize(FeatureStore.trioSize())} dp ›"
    }

    private fun showSizeDialog() {
        if (switches[FeatureCatalog.KEY_SYSTEMUI_TRIO]?.isChecked != true) {
            toast("请先开启「状态栏三合一」")
            return
        }
        val current = FeatureCatalog.resolveTrioSize(FeatureStore.trioSize())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
        }
        val valueText = TextView(this).apply {
            text = "$current dp"
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        box.addView(valueText)
        val bar = SeekBar(this).apply {
            max = SIZE_MAX - SIZE_MIN
            progress = (current - SIZE_MIN).coerceIn(0, SIZE_MAX - SIZE_MIN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueText.text = "${SIZE_MIN + progress} dp"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        box.addView(bar)
        AlertDialog.Builder(this)
            .setTitle("三合一图标大小")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val pushed = FeatureStore.setTrioSize(SIZE_MIN + bar.progress)
                updateSizeText()
                if (!pushed) toast("已保存到本地，但 LSPosed 未连接，本次改动不会下发")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildTipCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(0xFFEFF4FF.toInt(), 14f, 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(18) }
        addView(TextView(this@MainActivity).apply {
            text = "生效说明"
            setTextColor(colorAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = "开关改动在 SystemUI 进程重启后生效。改完点下方按钮重启系统界面，" +
                "或手动重启手机。若状态卡显示未连接，请到 LSPosed 中启用本模块、" +
                "作用域勾选「系统界面 / SystemUI」，然后重启系统界面。"
            setTextColor(0xFF334155.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
    }

    private fun buildRestartButton(): Button = Button(this).apply {
        text = "重启系统界面（需 Root）"
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(colorAccent, 14f, 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(16) }
        setOnClickListener { confirmRestart() }
    }

    private fun confirmRestart() {
        AlertDialog.Builder(this)
            .setTitle("重启系统界面")
            .setMessage("将通过 su 结束 com.android.systemui 进程，状态栏会在几秒内自动重载。继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("重启") { _, _ -> restartSystemUi() }
            .show()
    }

    private fun restartSystemUi() {
        Thread {
            val ok = runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "killall com.android.systemui"))
                process.waitFor() == 0
            }.getOrDefault(false)
            runOnUiThread {
                toast(if (ok) "已发送重启指令，请稍候" else "重启失败：未获得 Root 权限")
            }
        }.start()
    }

    private fun updateStatus() {
        val connected = FrameworkState.isConnected()
        statusText.text = if (connected) {
            "LSPosed：已连接 · 开关可下发"
        } else {
            "LSPosed：未连接 · 开关只保存在本地"
        }
        statusText.setTextColor(if (connected) colorOk else colorWarn)
    }

    private fun refreshSwitchStates() {
        FeatureCatalog.features.forEach { feature ->
            switches[feature.key]?.isChecked = FeatureStore.isEnabled(feature.key)
        }
        if (this::clockValue.isInitialized) updateClockText()
        if (this::btStyleValue.isInitialized) updateBtStyleText()
        if (this::weatherTemplateValue.isInitialized) updateWeatherTemplateText()
        if (this::weatherClickValue.isInitialized) updateWeatherClickText()
        if (this::styleValue.isInitialized) updateStyleText()
        if (this::capsuleSettingsValue.isInitialized) updateCapsuleSettingsText()
        renderDependency()
    }

    /**
     * 依赖置灰：父开关关闭时其子开关置灰（三合一子开关、农历依赖铃铛隐藏等，按 dependsOn 通用处理）。
     */
    private fun renderDependency() {
        FeatureCatalog.features.filter { it.dependsOn != null }.forEach { feature ->
            val parentOn = switches[feature.dependsOn]?.isChecked ?: false
            switches[feature.key]?.let { toggle ->
                toggle.isEnabled = parentOn
                toggle.alpha = if (parentOn) 1f else 0.4f
            }
        }
        val trio = switches[FeatureCatalog.KEY_SYSTEMUI_TRIO]?.isChecked ?: false
        val capsule = trio && FeatureStore.trioStyle() == STYLE_CAPSULE
        if (this::sizeRow.isInitialized) {
            // 尺寸滑块只管圆环；胶囊用独立的「胶囊高度」
            sizeRow.alpha = if (trio && !capsule) 1f else 0.4f
        }
        if (this::styleRow.isInitialized) {
            styleRow.alpha = if (trio) 1f else 0.4f
        }
        // 胶囊详细设置折叠为「胶囊样式设置」入口行：仅胶囊样式下显示
        if (this::capsuleSettingsRow.isInitialized) {
            capsuleSettingsRow.visibility = if (capsule) View.VISIBLE else View.GONE
        }
        // 天气模板/点击行为行跟随「通知中心天气」开关联动置灰
        if (this::weatherTemplateRow.isInitialized) {
            val weather = switches[FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER]?.isChecked ?: false
            weatherTemplateRow.alpha = if (weather) 1f else 0.4f
            if (this::weatherClickRow.isInitialized) {
                weatherClickRow.alpha = if (weather) 1f else 0.4f
            }
        }
        // MagicOS 10 反色兼容开关：仅 MagicOS 10 可操作，其余版本（含 11）显示但禁用。
        switches[FeatureCatalog.KEY_SYSTEMUI_TRIO_LEGACY_TINT]?.let { toggle ->
            if (!MagicOsVersion.isMagicOs10) {
                toggle.isEnabled = false
                toggle.alpha = 0.4f
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** 圆角背景（纯色 + 可选描边）。 */
    private fun rounded(fill: Int, radiusDp: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    /**
     * 胶囊预览：按当前设置（长度/高度/线宽/内部边距）以真实 dp 尺寸绘制样例，
     * 与 hook 侧 TrioView 的胶囊几何同公式；固定样例状态：电量 100、5G、
     * 单卡 3 格（顶部闭合、下开口信号点）、未充电。
     */
    private inner class CapsulePreviewView(context: android.content.Context) : View(context) {

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val path = Path()
        private val arcRect = RectF()
        private val textBounds = Rect()

        fun refresh() {
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val heightPx = dp(FeatureCatalog.resolveCapsuleHeight(FeatureStore.capsuleHeight()))
            val aspect = FeatureCatalog.capsuleAspect(
                FeatureStore.capsuleLength(),
                FeatureStore.capsuleStroke(),
            )
            setMeasuredDimension((heightPx * aspect + 0.5f).toInt(), heightPx)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fg = colorText
            val track = (fg and 0x00FFFFFF) or (56 shl 24)

            // 与 TrioView 胶囊几何同公式（设计空间轮廓 138×59）
            val stroke = 59f * FeatureStore.capsuleStroke() / 100f
            val dotR = stroke * 0.5f
            val dotReach = PITCH * 1.5f + dotR + CLEARANCE + stroke * 0.5f
            val inkLeft = LEFT - stroke * 0.5f
            val inkTop = TOP - stroke * 0.5f
            val inkH = 59f + stroke
            val inset = 59f * FeatureStore.capsuleMarginH() / 100f
            val interiorLeft = LEFT + stroke * 0.5f
            val interiorTop = TOP + stroke * 0.5f + dotR
            val interiorBottom = BOTTOM - stroke * 0.5f - dotR
            val interiorCy = (interiorTop + interiorBottom) * 0.5f
            // 元素字高 = 内腔高 − 上下边距，与 TrioView 同公式
            val textCap = (
                (interiorBottom - interiorTop) -
                    2f * 59f * FeatureStore.capsuleMarginV() / 100f
                ).coerceAtLeast(8f)
            val right = RIGHT_REF + FeatureCatalog.resolveCapsuleLength(FeatureStore.capsuleLength())
            val cx = (LEFT + right) * 0.5f
            val inkW = right - LEFT + stroke
            val reach = minOf(dotReach, cx - LEFT - RADIUS)

            val scale = height / inkH
            if (scale <= 0f) return
            val save = canvas.save()
            canvas.translate((width - inkW * scale) * 0.5f, (height - inkH * scale) * 0.5f)
            canvas.scale(scale, scale)
            canvas.translate(-inkLeft, -inkTop)

            // 轮廓轨道（单卡样例：顶部闭合、底部开口）
            val k = 0.55228475f * RADIUS
            val cy = (TOP + BOTTOM) * 0.5f
            path.reset()
            path.moveTo(cx - reach, BOTTOM)
            path.lineTo(LEFT + RADIUS, BOTTOM)
            path.cubicTo(LEFT + RADIUS - k, BOTTOM, LEFT, cy + k, LEFT, cy)
            path.cubicTo(LEFT, cy - k, LEFT + RADIUS - k, TOP, LEFT + RADIUS, TOP)
            path.lineTo(right - RADIUS, TOP)
            path.cubicTo(right - RADIUS + k, TOP, right, cy - k, right, cy)
            path.cubicTo(right, cy + k, right - RADIUS + k, BOTTOM, right - RADIUS, BOTTOM)
            path.lineTo(cx + reach, BOTTOM)
            strokePaint.strokeWidth = stroke
            strokePaint.color = track
            canvas.drawPath(path, strokePaint)

            // 电量填充 100%：整条轮廓点亮
            strokePaint.color = fg
            canvas.drawPath(path, strokePaint)

            // 信号点：单卡样例 = 下开口 3 格（顶部闭合不画）
            val firstX = cx - PITCH * 1.5f
            for (i in 0 until 4) {
                fillPaint.color = if (i < 3) fg else track
                canvas.drawCircle(firstX + i * PITCH, BOTTOM, dotR, fillPaint)
            }

            // 左格 5G 文字（与 TrioView drawCapsuleLeft 同锚点同字体，制式文字 0.82 档）
            val textSize = textCap / 0.71f
            textPaint.color = fg
            textPaint.textSize = textSize * 0.82f
            textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.getTextBounds("5G", 0, 2, textBounds)
            canvas.drawText(
                "5G",
                interiorLeft + inset,
                interiorCy - (textBounds.top + textBounds.bottom) / 2f,
                textPaint,
            )

            // 右格电量数字
            val text = "100"
            textPaint.textSize = textSize
            textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            canvas.drawText(
                text,
                right - stroke * 0.5f - inset,
                interiorCy - (textBounds.top + textBounds.bottom) / 2f,
                textPaint,
            )

            canvas.restoreToCount(save)
        }
    }

    private companion object {
        const val SIZE_MIN = 18
        const val SIZE_MAX = 26

        /** 胶囊样式（与 FeatureCatalog.resolveTrioStyle 的 0/1 对应）。 */
        const val STYLE_CAPSULE = 1

        /** 胶囊长度滑块范围（设计单位）。 */
        const val CAPSULE_LEN_MIN = -30
        const val CAPSULE_LEN_MAX = 60

        /** 胶囊参数滑块范围：高度 dp、线宽 %、左右边距 %、上下边距 %（与 FeatureCatalog 归一范围一致）。 */
        const val CAP_HEIGHT_MIN = 14
        const val CAP_HEIGHT_MAX = 26
        const val CAP_STROKE_MIN = 8
        const val CAP_STROKE_MAX = 18
        const val CAP_MARGIN_H_MIN = 2
        const val CAP_MARGIN_H_MAX = 20
        const val CAP_MARGIN_V_MIN = 10
        const val CAP_MARGIN_V_MAX = 26

        /** 胶囊预览几何（与 TrioView 胶囊常量一致，设计空间轮廓 138×59）。 */
        const val LEFT = 1f
        const val RIGHT_REF = 139f
        const val TOP = 1f
        const val BOTTOM = 60f
        const val RADIUS = 29.5f
        const val PITCH = 10.9f
        const val CLEARANCE = 5.31f
        const val PI = 3.1415927f
        const val RAD_TO_DEG = 57.29578f

        /** 「通知中心」分组的功能 key（列表顺序 = 卡内行顺序，铃铛是农历的父依赖须在前）。 */
        val notifCenterKeys = listOf(
            FeatureCatalog.KEY_SYSTEMUI_HIDE_NOTIF_BELL,
            FeatureCatalog.KEY_SYSTEMUI_SHADE_LUNAR,
            FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER,
        )

        /** 「控制中心」分组的功能 key。 */
        val controlCenterKeys = listOf(FeatureCatalog.KEY_SYSTEMUI_HIDE_CC_POWER)

        /** 「灵动胶囊」分组的功能 key（保留图标依赖三合一总开关，须在前）。 */
        val capsuleKeys = listOf(
            FeatureCatalog.KEY_SYSTEMUI_TRIO_KEEP_ICONS,
            FeatureCatalog.KEY_SYSTEMUI_CAPSULE_NO_SCALE,
        )

        /** 「高级」分组的功能 key（兼容性开关 + 诊断日志）。 */
        val advancedKeys = listOf(
            FeatureCatalog.KEY_SYSTEMUI_TRIO_LEGACY_TINT,
            FeatureCatalog.KEY_DEBUG_LOG,
        )
    }
}
