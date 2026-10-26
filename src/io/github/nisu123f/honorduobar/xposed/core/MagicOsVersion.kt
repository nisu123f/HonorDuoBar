package io.github.nisu123f.honorduobar.xposed.core

import android.os.Build

/**
 * MagicOS 主版本号检测（10 / 11 / …）。
 *
 * 荣耀设备上 MagicOS 版本的标准来源是系统属性 `ro.build.version.magic`
 * （如 "11.0"、"10.1"）；个别版本用 `ro.magicos.version`；最后兜底解析
 * `Build.VERSION.INCREMENTAL`（部分机型含 "MagicOS 11" 字样）。
 *
 * 所有读取均走反射调用 `android.os.SystemProperties.get`（隐藏 API），
 * 失败一律回 null → 调用方按「未知」处理（默认走 MagicOS 11 的 HonorDuo 方案）。
 */
object MagicOsVersion {

    /** 主版本号（10/11/...），检测失败返回 null。 */
    val major: Int? by lazy { detect() }

    /** 是否 MagicOS 11（含无法识别时按 11 处理，因为本模块主目标是 MagicOS 11）。 */
    val isMagicOs11OrUnknown: Boolean
        get() = major?.let { it >= 11 } ?: true

    /** 是否 MagicOS 10（不含 11 及以上；检测失败返回 false）。 */
    val isMagicOs10: Boolean
        get() = major == 10

    private fun detect(): Int? {
        readSystemProperty("ro.build.version.magic")?.let { return parseMajor(it) }
        readSystemProperty("ro.magicos.version")?.let { return parseMajor(it) }
        parseMajor(Build.VERSION.INCREMENTAL)?.let { return it }
        return null
    }

    /** 从版本字符串中提取首个数字段作为主版本号。 */
    private fun parseMajor(raw: String): Int? {
        val match = Regex("(\\d+)").find(raw) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun readSystemProperty(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java)
        (method.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
