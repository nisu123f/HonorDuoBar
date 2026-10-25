package io.github.nisu123f.honorduobar.xposed.target

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.net.Uri
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.MountEngine
import io.github.nisu123f.honorduobar.xposed.core.MountSession
import io.github.nisu123f.honorduobar.xposed.core.XLog
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * 通知中心天气：拼到大时间下方「流量信息」行末尾（「总量 30GB·☀24℃」样式），
 * 日期行保持「日期+农历」不动，不再拥挤。
 *
 * 展示链路（jadx 实证 SystemUI 17.0.0.1）：
 * 流量开启时：`TrafficPanelManager` 刷新流量 → `NotifCenterHeaderViewModelImpl$trafficListener$1
 * .refreshDataBySlot(TrafficInfo)`（TrafficInfo.today/residue/total 为 public 可变 String）
 * → hook 处在 proceed 前把天气拼到 `total` 末尾，Compose 一次重组渲染到位。
 * 流量关闭时：`getTrafficState()` 返回 `TrafficState$Hidden`，替换为单行 NoMeal 文本态，
 * 文案由 `stringResource` hook 填入天气。
 *
 * 数据源：系统锁屏天气插件同款通道 `content://com.hihonor.android.weather/weatherInfo`
 * （authority 与天气 App 包名无关；SystemUI 特权进程可直接读，
 * 参考 odomu/HonorDuo 的 WeatherManager 实现，GPL-3.0）。本模块不发起任何网络请求。
 *
 * 刷新策略：与系统锁屏天气插件一致——监听天气 App 的数据变化广播（WEATHER_CHANGE 等）
 * 即时失效缓存并后台重查；另保留 30 分钟兜底缓存 + 息屏不查询。
 * 刷新由流量回调与日期分钟滴答双驱动，过期时后台线程异步拉取，当次仍用旧缓存，钩子线程零阻塞。
 */
object ShadeWeatherFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_INTERACTOR =
        "com.android.systemui.statusbar.notification.panelheader.NotifCenterHeaderInteractorImpl"
    private const val METHOD_GET_DATE = "getCurrentClockDate"
    private const val ID_GET_DATE = "systemui:weather.notifHeader.getCurrentClockDate"
    /** 流量信息入口：TrafficPanelManager 每刷新一次数据回调一次，TrafficInfo 字段可变。 */
    private const val CLS_TRAFFIC_LISTENER =
        "com.android.systemui.statusbar.notification.panelheader.viewmodel.NotifCenterHeaderViewModelImpl\$trafficListener\$1"
    private const val METHOD_TRAFFIC = "refreshDataBySlot"
    private const val ID_TRAFFIC = "systemui:weather.headerViewModel.refreshDataBySlot"
    /** 流量行状态入口：Compose 每次重组都读，Hidden 即「流量显示关闭」。 */
    private const val CLS_VM_IMPL =
        "com.android.systemui.statusbar.notification.panelheader.viewmodel.NotifCenterHeaderViewModelImpl"
    private const val METHOD_TRAFFIC_STATE = "getTrafficState"
    private const val ID_TRAFFIC_STATE = "systemui:weather.vm.getTrafficState"
    /** 流量行点击回调（原生跳转流量统计）。 */
    private const val METHOD_TRAFFIC_CLICK = "onClickTraffic"
    private const val ID_TRAFFIC_CLICK = "systemui:weather.vm.onClickTraffic"
    /** NoMeal 提示文案渲染入口（单文本，无分隔条）。 */
    private const val CLS_STRING_RES = "androidx.compose.ui.res.StringResources_androidKt"
    private const val METHOD_STRING_RES = "stringResource"
    private const val ID_STRING_RES = "systemui:weather.compose.stringResource"
    private const val CLS_TRAFFIC_STATE =
        "com.android.systemui.statusbar.notification.panelheader.viewmodel.TrafficState"
    private const val CLS_NO_MEAL = "$CLS_TRAFFIC_STATE\$NoMeal"
    private const val HIDDEN_CLASS_SUFFIX = "TrafficState\$Hidden"
    /** NoMeal 提示的资源 ID（流量行「本月流量已用尽」文案，仅该行使用）。 */
    private const val NO_MEAL_PROMPT = 2131956187
    private const val JUDGE_PREFIX = "SystemUiHook·Weather"

    /** 天气行点击行为：0=无响应（直接吞掉点击），1=跳转天气，2=保持原生（流量统计）。 */
    private const val WEATHER_CLICK_OPEN_WEATHER = 1
    private const val WEATHER_CLICK_NATIVE = 2
    private const val WEATHER_PACKAGE = "com.hihonor.android.totemweather"

    /**
     * 天气数据 authority：系统锁屏天气插件同款通道，
     * 与天气 App 包名（com.hihonor.android.totemweather）无关，不要混用。
     */
    private val WEATHER_URI: Uri = Uri.parse("content://com.hihonor.android.weather/weatherInfo")
    private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L

    /**
     * 锁屏天气插件同款广播（EventCenter 注册的同一组 action）：
     * 天气 App 写入新数据后主动推送，收到即失效缓存，无需等待 TTL。
     */
    private const val ACTION_WEATHER_CHANGE = "com.hihonor.android.action.WEATHER_CHANGE"
    private const val ACTION_TEMPERATURE_FORMAT_CHANGE = "com.hihonor.android.action.TEMPERATURE_FORMAT_CHANGE"
    private const val ACTION_CITYINFO_CHANGE = "com.hihonor.android.action.CITYINFO_CHANGE"
    private const val ACTION_CITYINFO_ADD = "com.hihonor.android.action.CITYINFO_ADD"
    private const val ACTION_CITYINFO_DELETE = "com.hihonor.android.action.CITYINFO_DELETE"
    private const val ACTION_WIDGET_CHANGE = "com.hihonor.android.action.WIDGET_CHANGE"
    private const val EXTRA_STATUS = "status"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HonorDuo-Weather").apply { isDaemon = true }
    }

    /** 已缓存的天气数据；无数据时为 null（拼接入判空回退原串）。 */
    private data class WeatherData(
        val condition: String,
        val tempC: Int,
        val feelsC: Int? = null,
        val city: String? = null,
        val lowC: Int? = null,
        val highC: Int? = null,
        val humidity: String? = null,
        val uvIndex: Int? = null,
        val windDir: String? = null,
        val windSpeed: Int? = null,
        val airQuality: String? = null,
        val aqi: Int? = null,
        val pm25: Int? = null,
        val pm10: Int? = null,
        val pressure: Int? = null,
        val sunrise: String? = null,
        val sunset: String? = null,
        val tomorrowCondition: String? = null,
        val tomorrowHigh: Int? = null,
        val tomorrowLow: Int? = null,
    )

    @Volatile
    private var cache: WeatherData? = null

    /** 显示模板（注入时读取，改动随重启系统界面生效）。 */
    @Volatile
    private var weatherTemplate: String = FeatureCatalog.defaultWeatherTemplate()

    /** 0=无响应，1=跳转天气，2=保持原生（流量统计）。 */
    @Volatile
    private var weatherClick: Int = FeatureCatalog.defaultWeatherClick()

    @Volatile
    private var fetchedAt = 0L

    @Volatile
    private var querying = false

    /** 是否已注册天气变化广播（与锁屏插件同款 action）。 */
    @Volatile
    private var receiverRegistered = false

    /** 当前流量行处于「仅天气」替身模式（流量显示关闭），供 stringResource 替换提示文案。 */
    @Volatile
    private var weatherOnlyRow = false

    /** TrafficState$NoMeal 构造器缓存（反射只解析一次）。 */
    @Volatile
    private var noMealCtor: java.lang.reflect.Constructor<*>? = null

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER)) {
            logDisabled(FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER, JUDGE_PREFIX)
            return
        }
        weatherTemplate = RemoteConfig.string(
            FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_TEMPLATE,
            FeatureCatalog.defaultWeatherTemplate(),
        ).trim().ifBlank { FeatureCatalog.defaultWeatherTemplate() }
        weatherClick = FeatureCatalog.resolveWeatherClick(
            RemoteConfig.int(
                FeatureCatalog.KEY_SYSTEMUI_SHADE_WEATHER_CLICK,
                FeatureCatalog.defaultWeatherClick(),
            ),
        )
        val loader = param.defaultClassLoader
        val session = MountEngine.open(
            JUDGE_PREFIX,
            HOST_PACKAGE,
            param.applicationInfo?.sourceDir,
            FeatureCatalog.baselineVersionOf(HOST_PACKAGE),
        )
        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_TRAFFIC_LISTENER,
                methodName = METHOD_TRAFFIC,
                paramCount = 1,
                returnType = "void",
                id = ID_TRAFFIC,
                description = "通知中心天气（流量信息行末段 total 拼「·☀24℃」后缀）",
            ),
            MountEngine.SimpleTask(
                className = CLS_INTERACTOR,
                methodName = METHOD_GET_DATE,
                paramCount = 1,
                returnType = "java.lang.String",
                id = ID_GET_DATE,
                description = "天气分钟滴答刷新驱动（不改日期串，仅触发缓存过期检查）",
            ),
            MountEngine.SimpleTask(
                className = CLS_VM_IMPL,
                methodName = METHOD_TRAFFIC_STATE,
                paramCount = 0,
                returnType = CLS_TRAFFIC_STATE,
                id = ID_TRAFFIC_STATE,
                description = "通知中心天气（流量显示关闭时把 Hidden 态替换为单行天气文本态）",
            ),
            MountEngine.SimpleTask(
                className = CLS_STRING_RES,
                methodName = METHOD_STRING_RES,
                paramCount = 2,
                returnType = "java.lang.String",
                id = ID_STRING_RES,
                description = "通知中心天气（把流量行 NoMeal 提示文案替换为天气文本）",
            ),
            MountEngine.SimpleTask(
                className = CLS_VM_IMPL,
                methodName = METHOD_TRAFFIC_CLICK,
                paramCount = 0,
                returnType = "void",
                id = ID_TRAFFIC_CLICK,
                description = "通知中心天气（仅天气行点击：跳转天气 App / 无响应 / 保持原生）",
            ),
        )
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
    }

    private fun mountOne(
        session: MountSession,
        task: MountEngine.SimpleTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountEngine.Result = session.mount(task, module, loader, XposedInterface.Hooker { chain ->
        when (task.id) {
            ID_TRAFFIC -> {
                // proceed 前改写 TrafficInfo：刷新一进入 ViewModel 就带天气，Compose 一次重组到位
                appendWeatherToTraffic(chain.args?.getOrNull(0))
                chain.proceed()
                null
            }
            ID_TRAFFIC_STATE -> {
                val state = chain.proceed() as? Any
                if (state != null && state.javaClass.name.endsWith(HIDDEN_CLASS_SUFFIX)) {
                    // 流量显示关闭：该行本应完全不渲染。替换为单行文本态，文案由 stringResource hook 填入天气。
                    val weather = formatWeather(currentOrRefresh())
                    val stub = if (weather.isEmpty()) null else newNoMeal(loader)
                    if (stub != null) {
                        weatherOnlyRow = true
                        stub
                    } else {
                        weatherOnlyRow = false
                        state
                    }
                } else {
                    weatherOnlyRow = false
                    state
                }
            }
            ID_STRING_RES -> {
                val result = chain.proceed() as? String
                val id = chain.args?.getOrNull(0) as? Int
                if (weatherOnlyRow && id == NO_MEAL_PROMPT) {
                    val weather = formatWeather(cache)
                    if (weather.isNotEmpty()) weather else result
                } else {
                    result
                }
            }
            ID_TRAFFIC_CLICK -> {
                // 有天气时接管点击：跳转天气 / 无响应；「保持原生」或暂无天气时走原生流量统计
                val hasWeather = weatherOnlyRow || cache != null
                XLog.diag(
                    Log.INFO,
                    MountEngine.TAG,
                    "$JUDGE_PREFIX onClickTraffic 触发: weatherOnlyRow=$weatherOnlyRow cache=${cache != null} weatherClick=$weatherClick",
                )
                if (hasWeather && weatherClick != WEATHER_CLICK_NATIVE) {
                    if (weatherClick == WEATHER_CLICK_OPEN_WEATHER) launchWeatherApp()
                    null
                } else {
                    chain.proceed()
                }
            }
            else -> {
                // 日期串原样透传，仅借分钟滴答驱动缓存过期刷新
                currentOrRefresh()
                chain.proceed()
            }
        }
    })

    /**
     * 构造 TrafficState$NoMeal 单行文本态（Compose 走 stringResource 单文本渲染，无分隔条）。
     * 每次返回新实例：让 Compose 的 changedInstance 判为变化，天气文案更新时能重新走一遍。
     */
    private fun newNoMeal(loader: ClassLoader): Any? = runCatching {
        val ctor = noMealCtor ?: Class.forName(CLS_NO_MEAL, false, loader)
            .getDeclaredConstructor(Int::class.javaPrimitiveType)
            .also {
                it.isAccessible = true
                noMealCtor = it
            }
        ctor.newInstance(NO_MEAL_PROMPT)
    }.getOrNull()

    /**
     * 点天气行时拉起系统天气 App。
     * 与原生 onClickTraffic 同一条通路：Dependency.get(StatusBar).startActivityDismissingKeyguard(...)，
     * 可收起通知面板、解锁并跨用户启动；反射失败再退回 appContext.startActivity。
     */
    private fun launchWeatherApp() {
        runCatching {
            val ctx = currentAppContext() ?: run {
                XLog.diag(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 天气行点击：取不到 Context")
                return@runCatching
            }
            val intent = weatherLaunchIntent(ctx)
            if (intent == null) {
                XLog.diag(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 天气行点击：未找到天气 App 启动入口")
                return@runCatching
            }
            val viaStatusBar = runCatching {
                val loader = ctx.classLoader
                val statusBarCls = Class.forName("com.android.systemui.statusbar.phone.StatusBar", false, loader)
                val statusBar = Class.forName("com.android.systemui.Dependency", false, loader)
                    .getMethod("get", Class::class.java)
                    .invoke(null, statusBarCls)
                if (statusBar == null) {
                    false
                } else {
                    statusBarCls.getMethod(
                        "startActivityDismissingKeyguard",
                        Intent::class.java,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).invoke(statusBar, intent, true, true, 0x20000000)
                    true
                }
            }.getOrElse { t ->
                XLog.diag(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX StatusBar 通路失败: $t")
                false
            }
            if (!viaStatusBar) {
                runCatching {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }.onFailure { t ->
                    XLog.diag(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 直接启动失败: $t")
                }
            }
            XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 天气行点击 → 拉起天气 App（StatusBar=$viaStatusBar, $intent）")
        }.onFailure {
            XLog.diag(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 天气行点击失败: $it")
        }
    }

    /** 天气 App 启动 Intent：先取桌面入口，再按 LAUNCHER 查询兜底。 */
    private fun weatherLaunchIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        runCatching { pm.getLaunchIntentForPackage(WEATHER_PACKAGE) }.getOrNull()?.let { return it }
        val probe = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(WEATHER_PACKAGE)
        val activity = runCatching { pm.queryIntentActivities(probe, 0) }.getOrNull()
            ?.firstOrNull() ?: return null
        return probe.setClassName(WEATHER_PACKAGE, activity.activityInfo.name)
    }

    /**
     * 把天气拼到 TrafficInfo.total 末尾（流量行末段「总量 xxGB」之后）。
     * TrafficInfo 字段为 public 可变 String，反射写回即可；每次刷新都是新实例，不会重复拼接。
     */
    private fun appendWeatherToTraffic(trafficInfo: Any?) {
        trafficInfo ?: return
        val weather = formatWeather(currentOrRefresh())
        if (weather.isEmpty()) return
        runCatching {
            val field = trafficInfo.javaClass.getField("total")
            val total = field.get(trafficInfo) as? String ?: return@runCatching
            if (!total.contains("℃")) field.set(trafficInfo, "$total·$weather")
        }
    }

    /**
     * 按模板格式化：变量占位符替换为实际值，变量之间保留用户输入的空格/分隔符。
     * 缺失的变量输出空串；体感温度按「与实测温差 >2℃ 才输出」的规则。
     */
    private fun formatWeather(data: WeatherData?): String {
        if (data == null) return ""
        val feels = data.feelsC?.takeIf { kotlin.math.abs(it - data.tempC) > 2 }
        val glyph = glyphFor(data.condition)
        val values = mapOf(
            "{定位}" to (data.city ?: ""),
            "{天气}" to data.condition,
            "{天气图标}" to glyph,
            "{当前温度}" to "${data.tempC}℃",
            "{最低温度}" to (data.lowC?.let { "$it℃" } ?: ""),
            "{最高温度}" to (data.highC?.let { "$it℃" } ?: ""),
            "{体感温度}" to (feels?.let { "$it℃" } ?: ""),
            "{湿度}" to (data.humidity ?: ""),
            "{紫外线}" to (data.uvIndex?.toString() ?: ""),
            "{风向}" to (data.windDir ?: ""),
            "{风速}" to (data.windSpeed?.toString() ?: ""),
            "{空气质量}" to (data.airQuality ?: ""),
            "{AQI}" to (data.aqi?.toString() ?: ""),
            "{PM25}" to (data.pm25?.toString() ?: ""),
            "{PM10}" to (data.pm10?.toString() ?: ""),
            "{气压}" to (data.pressure?.let { "$it hPa" } ?: ""),
            "{日出}" to (data.sunrise ?: ""),
            "{日落}" to (data.sunset ?: ""),
            "{明日天气}" to (data.tomorrowCondition ?: ""),
            "{明日最高}" to (data.tomorrowHigh?.let { "$it℃" } ?: ""),
            "{明日最低}" to (data.tomorrowLow?.let { "$it℃" } ?: ""),
        )
        var out = weatherTemplate
        values.forEach { (key, value) -> out = out.replace(key, value) }
        return out.trim()
    }

    /** 天气状况 → 文本符号（BMP 字符，系统字体可直接渲染，不占额外宽度渲染 emoji）。 */
    private fun glyphFor(condition: String): String = when {
        condition.contains("雷") -> "⛈"
        condition.contains("雪") -> "❄"
        condition.contains("雨") -> "☂"
        condition.contains("多云") -> "⛅"
        condition.contains("晴") -> "☀"
        else -> "☁" // 阴/云/雾/霾/沙尘等低能见度或阴天统一
    }

    /** 返回缓存；过期则触发一次后台刷新（当次仍返回旧值）。钩子线程零阻塞。 */
    private fun currentOrRefresh(): WeatherData? {
        val now = System.currentTimeMillis()
        if (now - fetchedAt >= CACHE_EXPIRY_MS && !querying) {
            querying = true
            executor.execute {
                try {
                    val ctx = currentAppContext() ?: return@execute
                    ensureWeatherReceiver(ctx)
                    // 息屏不查询，省电（与 HonorDuo 策略一致）
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm?.isInteractive == false) return@execute
                    val queried = queryWeather(ctx)
                    if (queried != null) {
                        cache = queried
                        fetchedAt = System.currentTimeMillis()
                        XLog.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 天气刷新: ${queried.condition} ${queried.tempC}℃")
                    }
                } catch (_: Throwable) {
                } finally {
                    querying = false
                }
            }
        }
        return cache
    }

    /**
     * 注册天气变化广播，与锁屏天气插件（EventCenter）完全一致：
     * WEATHER_CHANGE 仅在 status==1 时生效；地区增删改与温度格式变化同样重查。
     * 收到广播即把缓存置为过期，由下一次读取在后台重查。
     */
    private fun ensureWeatherReceiver(ctx: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(ACTION_WEATHER_CHANGE)
                addAction(ACTION_TEMPERATURE_FORMAT_CHANGE)
                addAction(ACTION_CITYINFO_CHANGE)
                addAction(ACTION_CITYINFO_ADD)
                addAction(ACTION_CITYINFO_DELETE)
                addAction(ACTION_WIDGET_CHANGE)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    if (action == ACTION_WEATHER_CHANGE && intent.getIntExtra(EXTRA_STATUS, 0) != 1) return
                    fetchedAt = 0L
                    XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 收到天气变化广播: $action，缓存已失效")
                    currentOrRefresh()
                }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    ctx.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
            }.onFailure {
                XLog.diag(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 注册天气广播失败: $it")
            }
        }
    }

    /** SystemUI 进程内取 Application Context（钩子拿不到 Context 参数）。 */
    private fun currentAppContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    /**
     * 查天气：与系统锁屏天气插件同一条 Provider 通道。
     *
     * 实测 JSON（MagicOS 11）关键字段：
     * `cityinfo.city_native_name`；`weather[day_index=1]` 的
     * `sunrise_time`/`sunset_time`、`current_temperature`、`curr_hightemp`、`curr_lowtemp`、
     * `real_feel_temp`、`weather_native_des`、`weather_icon`；
     * 夜间为同名字段的 `night_` 前缀版本。单位字段 `temp_unit`（℃/℉）。
     */
    private fun queryWeather(ctx: Context): WeatherData? {
        val extras = Bundle().apply {
            putInt("city_type", 10)
            putInt("dayAndNightWeather", 1)
        }
        val json = ctx.contentResolver.call(WEATHER_URI, "query_home_city_weather", null, extras)
            ?.getString("key_home_city_weather")
            ?.takeIf { it.isNotEmpty() } ?: return null
        val root = JSONObject(json)

        val city = root.optJSONObject("cityinfo")
            ?.optString("city_native_name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val days = root.optJSONArray("weather") ?: return null
        val dayItems = (0 until days.length()).mapNotNull { days.optJSONObject(it) }
        val today = dayItems.firstOrNull { it.optInt("day_index") == 1 } ?: return null
        val tomorrow = dayItems.firstOrNull { it.optInt("day_index") == 2 }
        // 气压不在日数据里，而是数组末尾单独一项 {"Pressure":1025}
        val pressure = dayItems.firstOrNull { it.has("Pressure") }
            ?.optInt("Pressure", -1)?.takeIf { it > 0 }

        // 与锁屏插件一致：按日出/日落判断昼夜，夜间读 night_ 前缀字段
        val now = System.currentTimeMillis()
        val sunrise = today.optLong("sunrise_time")
        val sunset = today.optLong("sunset_time")
        val isNight = sunrise > 0L && sunset > 0L && (now < sunrise || now > sunset)
        val prefix = if (isNight) "night_" else ""

        val temp = today.optDouble("${prefix}current_temperature", Double.NaN)
            .takeIf { !it.isNaN() } ?: return null
        val fahrenheit = root.optString("temp_unit").equals("F", true)
        val condition = today.optString("${prefix}weather_native_des").trim()
            .takeIf { it.isNotEmpty() }
            ?: mapIconToCondition(today.optInt("${prefix}weather_icon", -1))
        val feelsC = today.optDouble("${prefix}real_feel_temp", Double.NaN)
            .takeIf { !it.isNaN() }?.let { toCelsius(it, fahrenheit) }
        val highC = today.optDouble("${prefix}curr_hightemp", Double.NaN)
            .takeIf { !it.isNaN() }?.let { toCelsius(it, fahrenheit) }
        val lowC = today.optDouble("${prefix}curr_lowtemp", Double.NaN)
            .takeIf { !it.isNaN() }?.let { toCelsius(it, fahrenheit) }

        val humidity = today.optString("humidity").trim().takeIf { it.isNotEmpty() }
        val uvIndex = today.optInt("uv_index", -1).takeIf { it >= 0 }
        val windDir = windDirectionText(today.optInt("wind_direction", -1))
        val windSpeed = today.optInt("wind_speed", -1).takeIf { it >= 0 }
        val airQuality = today.optString("air_status_desc").trim().takeIf { it.isNotEmpty() }
        val aqi = today.optInt("air_pnum", -1).takeIf { it >= 0 }
        val pm25 = today.optInt("air_pm25", -1).takeIf { it >= 0 }
        val pm10 = today.optInt("air_pm10", -1).takeIf { it >= 0 }

        val tomorrowCondition = tomorrow?.optString("weather_native_des")?.trim()
            ?.takeIf { it.isNotEmpty() }
        val tomorrowHigh = tomorrow?.optDouble("curr_hightemp", Double.NaN)
            ?.takeIf { !it.isNaN() }?.let { toCelsius(it, fahrenheit) }
        val tomorrowLow = tomorrow?.optDouble("curr_lowtemp", Double.NaN)
            ?.takeIf { !it.isNaN() }?.let { toCelsius(it, fahrenheit) }

        return WeatherData(
            condition = condition,
            tempC = toCelsius(temp, fahrenheit),
            feelsC = feelsC,
            city = city,
            lowC = lowC,
            highC = highC,
            humidity = humidity,
            uvIndex = uvIndex,
            windDir = windDir,
            windSpeed = windSpeed,
            airQuality = airQuality,
            aqi = aqi,
            pm25 = pm25,
            pm10 = pm10,
            pressure = pressure,
            sunrise = formatTime(sunrise),
            sunset = formatTime(sunset),
            tomorrowCondition = tomorrowCondition,
            tomorrowHigh = tomorrowHigh,
            tomorrowLow = tomorrowLow,
        )
    }

    /** 风向码 → 中文（中国天气网口径：0 无持续，1 东北，2 东，3 东南，4 南，5 西南，6 西，7 西北，8 北）。 */
    private fun windDirectionText(code: Int): String? = when (code) {
        0 -> "无持续风向"
        1 -> "东北风"
        2 -> "东风"
        3 -> "东南风"
        4 -> "南风"
        5 -> "西南风"
        6 -> "西风"
        7 -> "西北风"
        8 -> "北风"
        else -> null
    }

    private fun formatTime(ms: Long): String? =
        if (ms <= 0L) null else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun toCelsius(raw: Double, fahrenheit: Boolean): Int =
        if (fahrenheit) ((raw - 32.0) * 5.0 / 9.0).roundToInt() else raw.roundToInt()

    /** weather_icon 数字码 → 中文状况（`weather_native_des` 缺失时兜底）。 */
    private fun mapIconToCondition(icon: Int): String = when (icon) {
        1, 2, 33 -> "晴"
        3, 4, 34, 47 -> "多云"
        7, 8, 35, 46 -> "云"
        11, 45 -> "雾"
        in 14..15 -> "雨夹雪"
        in 16..20, 12, 13, 75 -> "雨"
        in 21..25 -> "雪"
        in 26..29 -> "雾霾"
        in 30..32 -> "雾"
        else -> "晴"
    }
}
