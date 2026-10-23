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
    private val switches = HashMap<String, Switch>()
    private val listeners = ArrayList<() -> Unit>()

    private val serviceListener: () -> Unit = {
        runOnUiThread { updateStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "Honor Duo Bar"
            setTextColor(colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "状态栏三合一 · LSPosed 模块"
            setTextColor(colorSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(buildStatusCard())
        root.addView(sectionTitle("状态栏"))
        root.addView(buildCard(featureRows() + buildClockRow() + buildSizeRow()))
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

    /** 生成开关行。 */
    private fun featureRows(): List<View> =
        FeatureCatalog.features.mapNotNull { feature ->
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
        renderDependency()
    }

    /**
     * 三合一是整段 system_icons 的接管者：总开关关闭时其子开关与尺寸行置灰。
     */
    private fun renderDependency() {
        val trio = switches[FeatureCatalog.KEY_SYSTEMUI_TRIO]?.isChecked ?: false
        FeatureCatalog.features.filter { it.dependsOn == FeatureCatalog.KEY_SYSTEMUI_TRIO }
            .forEach { feature ->
                switches[feature.key]?.let { toggle ->
                    toggle.isEnabled = trio
                    toggle.alpha = if (trio) 1f else 0.4f
                }
            }
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
    }
}
