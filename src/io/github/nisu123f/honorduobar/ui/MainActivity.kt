package io.github.nisu123f.honorduobar.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.nisu123f.honorduobar.FeatureCatalog

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }

        root.addView(buildStatusCard())
        root.addView(sectionTitle("状态栏"))
        root.addView(buildCard(featureRows { it.key !in notifCenterKeys && it.key !in controlCenterKeys && it.key !in capsuleKeys } + buildClockRow() + buildSizeRow() + buildBtStyleRow()))
        root.addView(sectionTitle("灵动胶囊"))
        root.addView(buildCard(featureRows { it.key in capsuleKeys }))
        root.addView(sectionTitle("通知中心"))
        root.addView(buildCard(featureRows { it.key in notifCenterKeys }))
        root.addView(sectionTitle("控制中心"))
        root.addView(buildCard(featureRows { it.key in controlCenterKeys }))
        root.addView(buildTipCard())
        root.addView(buildRestartButton())

        setContentView(ScrollView(this).apply { addView(root) })
        updateStatus()
        renderDependency()
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
                    if (feature.key == FeatureCatalog.KEY_SYSTEMUI_TRIO) {
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
        if (this::sizeRow.isInitialized) {
            sizeRow.alpha = if (trio) 1f else 0.4f
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

    private companion object {
        const val SIZE_MIN = 18
        const val SIZE_MAX = 26

        /** 「通知中心」分组的功能 key（列表顺序 = 卡内行顺序，铃铛是农历的父依赖须在前）。 */
        val notifCenterKeys = listOf(
            FeatureCatalog.KEY_SYSTEMUI_HIDE_NOTIF_BELL,
            FeatureCatalog.KEY_SYSTEMUI_SHADE_LUNAR,
        )

        /** 「控制中心」分组的功能 key。 */
        val controlCenterKeys = listOf(FeatureCatalog.KEY_SYSTEMUI_HIDE_CC_POWER)

        /** 「灵动胶囊」分组的功能 key（保留图标依赖三合一总开关，须在前）。 */
        val capsuleKeys = listOf(
            FeatureCatalog.KEY_SYSTEMUI_TRIO_KEEP_ICONS,
            FeatureCatalog.KEY_SYSTEMUI_CAPSULE_NO_SCALE,
        )
    }
}
