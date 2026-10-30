package com.example.novacustomerdialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

private val callStatuses = listOf("待联系", "已接通", "未接听", "稍后回访", "拒绝", "已成交")
private val palette = listOf(0xFF6686B8, 0xFF6B9A8A, 0xFFC49A62, 0xFFB87D91, 0xFF9586B7, 0xFF7D8794, 0xFF659AA3, 0xFFB87972)

data class Customer(
    val id: String = UUID.randomUUID().toString(), val name: String, val phone: String,
    val company: String = "", val note: String = "", val status: String = "待联系",
    val followUp: String = "", val tags: List<String> = emptyList(), val lastContactAt: String = ""
)
data class CustomerTag(val name: String, val color: Long = palette[0])
private enum class Page { HOME, CUSTOMERS, DIALER, SETTINGS, STATS, TAGS }

private data class SimCallOption(
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String,
    val account: PhoneAccountHandle?,
    val matchMethod: String
) {
    val title: String get() = "SIM${slotIndex + 1}"
    val network: String get() = displayName.ifBlank { carrierName }.ifBlank { "已识别 SIM" }
}

private data class PreparedCustomerCall(
    val intent: Intent,
    val account: PhoneAccountHandle?,
    val simSlotIndex: Int?,
    val simLabel: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs = getSharedPreferences("nova_settings", MODE_PRIVATE)
            var themeColor by remember {
                mutableIntStateOf(prefs.getInt("themeColor", 0xFF3568F2.toInt()))
            }
            var fontScale by remember {
                mutableFloatStateOf(prefs.getFloat("fontScale", 1.0f))
            }
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    systemDensity.density,
                    systemDensity.fontScale * fontScale
                )
            ) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Color(themeColor),
                        secondary = Color(0xFF2E8E78),
                        background = Color(0xFFF4F7FC),
                        surface = Color.White,
                        surfaceVariant = Color(0xFFEAF0FA)
                    )
                ) {
                    NovaApp(
                        themeColor = themeColor,
                        fontScale = fontScale,
                        onThemeColorChange = { color ->
                            themeColor = color
                            prefs.edit().putInt("themeColor", color).apply()
                        },
                        onFontScaleChange = { scale ->
                            fontScale = scale
                            prefs.edit().putFloat("fontScale", scale).apply()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NovaApp(
    themeColor: Int,
    fontScale: Float,
    onThemeColorChange: (Int) -> Unit,
    onFontScaleChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var customers by remember { mutableStateOf(loadCustomers(context)) }
    var tags by remember { mutableStateOf(loadTags(context)) }
    var page by remember { mutableStateOf(Page.HOME) }
    var currentId by remember { mutableStateOf(customers.firstOrNull()?.id) }
    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("全部状态") }
    var dialerStatusFilter by remember { mutableStateOf("全部状态") }
    var dialerQuickFilter by remember { mutableStateOf("全部名单") }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<Customer?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showOutcome by remember { mutableStateOf(false) }
    var pendingCall by remember { mutableStateOf<String?>(null) }
    var callLaunched by remember { mutableStateOf(false) }
    var showBulkStatusMenu by remember { mutableStateOf(false) }
    var callLogPermissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) }
    var callLogRefresh by remember { mutableIntStateOf(0) }
    var phoneStateGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) }
    var callPermissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) }
    var showBulkTagMenu by remember { mutableStateOf(false) }
    val settingsPrefs = remember { context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE) }
    var simMode by remember { mutableStateOf(settingsPrefs.getString("simMode", "system") ?: "system") }
    var rotationSlot by remember { mutableIntStateOf(settingsPrefs.getInt("simRotationSlot", settingsPrefs.getInt("simRotationIndex", 0)).coerceIn(0, 1)) }
    var rotationUsed by remember { mutableIntStateOf(settingsPrefs.getInt("simRotationUsed", 0).coerceAtLeast(0)) }
    var rotationCallsPerSim by remember { mutableIntStateOf(settingsPrefs.getInt("simRotationCallsPerSim", 1).coerceIn(1, 99)) }
    var simRefresh by remember { mutableIntStateOf(0) }
    var permissionsRefresh by remember { mutableIntStateOf(0) }
    var pendingCallRequest by remember { mutableStateOf<PreparedCustomerCall?>(null) }
    var outcome by remember { mutableStateOf("已接通") }
    var outcomeNote by remember { mutableStateOf("") }
    var outcomeDate by remember { mutableStateOf("") }
    var outcomeTags by remember { mutableStateOf(setOf<String>()) }
    var outcomeTagMenu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<CustomerTag?>(null) }
    var showCustomerStatusMenu by remember { mutableStateOf(false) }
    var showDialerStatusMenu by remember { mutableStateOf(false) }
    var statsPeriod by remember { mutableStateOf("当天统计") }
    val availableSims = remember(phoneStateGranted, simRefresh, permissionsRefresh) {
        if (phoneStateGranted) activeSimCallOptions(context) else emptyList()
    }

    fun saveCustomers(value: List<Customer>) { customers = value; persistCustomers(context, value); if (value.none { it.id == currentId }) currentId = value.firstOrNull()?.id; selected = selected.intersect(value.map { it.id }.toSet()) }
    fun saveTags(value: List<CustomerTag>) { tags = value; persistTags(context, value) }

    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        phoneStateGranted = granted
        simRefresh++
        if (!granted) {
            simMode = "system"
            settingsPrefs.edit().putString("simMode", "system").apply()
            message = "未授权读取电话账户，已切回系统默认拨号方式。"
        } else {
            message = "电话权限已授权，正在读取 SIM 卡和通话账户。"
        }
    }
    fun chooseRotationSlot(): Int? {
        val sims = activeSimCallOptions(context).filter { it.account != null }
        if (sims.isEmpty()) return null
        return sims.firstOrNull { it.slotIndex == rotationSlot }?.slotIndex
            ?: sims.firstOrNull()?.slotIndex
    }
    fun prepareCustomerCall(customer: Customer): PreparedCustomerCall? {
        val uri = Uri.parse("tel:${Uri.encode(customer.phone)}")
        if (simMode == "system") {
            return PreparedCustomerCall(Intent(Intent.ACTION_CALL, uri), null, null, "系统默认卡")
        }
        if (!phoneStateGranted) {
            message = "指定 SIM 拨号需要“电话状态”权限，请先完成下方权限授权。"
            return null
        }
        val requestedSlot = when (simMode) {
            "sim1" -> 0
            "sim2" -> 1
            "rotate" -> chooseRotationSlot()
            else -> null
        }
        if (requestedSlot == null) {
            message = "当前没有可用的语音 SIM，请检查 SIM 是否启用语音服务。"
            return null
        }
        val sims = activeSimCallOptions(context)
        val sim = sims.firstOrNull { it.slotIndex == requestedSlot }
        if (sim == null) {
            message = "未检测到 SIM${requestedSlot + 1}。请确认卡槽已插卡并在系统中启用。"
            return null
        }
        val account = sim.account
        if (account == null) {
            val detected = sims.joinToString("、") { "${it.title} ${it.network}" }.ifBlank { "无" }
            message = "SIM${requestedSlot + 1} 已识别为 ${sim.network}，但系统未返回对应通话账户。已检测：$detected。请确认该卡已启用语音通话。"
            Log.w("NovaDialer", "No Telecom account for slot=$requestedSlot; detected=$detected")
            return null
        }
        val intent = Intent(Intent.ACTION_CALL, uri).putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
        return PreparedCustomerCall(intent, account, requestedSlot, "${sim.title} · ${sim.network}")
    }
    fun commitRotationAfterCall(prepared: PreparedCustomerCall) {
        val actualSlot = prepared.simSlotIndex ?: return
        if (simMode != "rotate") return
        var nextUsed = rotationUsed + 1
        var nextSlot = rotationSlot
        if (nextUsed >= rotationCallsPerSim) {
            nextUsed = 0
            nextSlot = 1 - actualSlot.coerceIn(0, 1)
        } else {
            nextSlot = actualSlot
        }
        rotationUsed = nextUsed
        rotationSlot = nextSlot
        settingsPrefs.edit()
            .putInt("simRotationUsed", nextUsed)
            .putInt("simRotationSlot", nextSlot)
            .putInt("simRotationIndex", nextSlot)
            .apply()
    }
    fun launchPreparedCall(prepared: PreparedCustomerCall) {
        val account = prepared.account
        if (account != null) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account) }
            telecom.placeCall(prepared.intent.data ?: error("缺少电话号码"), extras)
        } else {
            context.startActivity(prepared.intent)
        }
        callLaunched = true
        commitRotationAfterCall(prepared)
        message = "正在通过 ${prepared.simLabel} 拨出。"
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        callPermissionGranted = granted
        val prepared = pendingCallRequest
        if (prepared != null) try {
            if (granted) launchPreparedCall(prepared)
            else {
                pendingCall = null
                message = "未授予“直接拨打电话”权限，本次未拨出。可在下方权限栏目中授权后重试。"
            }
        } catch (e: Exception) {
            pendingCall = null; callLaunched = false
            message = "直接拨号失败：${e.message ?: "请检查 SIM 状态及系统电话服务"}"
            Log.e("NovaDialer", "Direct call failed for ${prepared.simLabel}", e)
        } finally { pendingCallRequest = null }
    }
    val callLogPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        callLogPermissionGranted = granted
        if (granted) callLogRefresh++ else message = "未授权读取通话记录，统计页将不显示系统通话数据。"
    }
    val allPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        callPermissionGranted = result[Manifest.permission.CALL_PHONE]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
        phoneStateGranted = result[Manifest.permission.READ_PHONE_STATE]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
        callLogPermissionGranted = result[Manifest.permission.READ_CALL_LOG]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
        permissionsRefresh++
        simRefresh++
        if (callPermissionGranted && phoneStateGranted) {
            message = "拨号与 SIM 权限已就绪。通话记录权限可选，用于统计。"
        } else {
            message = "权限尚未全部授权；指定 SIM 拨号至少需要“电话”和“电话状态”权限。"
        }
    }
    fun startCustomerCall(customer: Customer) {
        pendingCall = customer.id
        val prepared = prepareCustomerCall(customer)
        if (prepared == null) {
            pendingCall = null
            pendingCallRequest = null
            return
        }
        pendingCallRequest = prepared
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try { launchPreparedCall(prepared); pendingCallRequest = null }
            catch (e: Exception) {
                pendingCall = null; callLaunched = false; pendingCallRequest = null
                message = "直接拨号失败：${e.message ?: "请检查电话权限及 SIM 状态"}"
                Log.e("NovaDialer", "Direct call failed for ${prepared.simLabel}", e)
            }
        } else callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
    }

    DisposableEffect(lifecycle, pendingCall, callLaunched) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                callPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                phoneStateGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                callLogPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
                permissionsRefresh++
                simRefresh++
                if (pendingCall != null && callLaunched) {
                    callLaunched = false; callLogRefresh++
                    val c = customers.firstOrNull { it.id == pendingCall }
                    if (c != null) { outcome = c.status.takeIf { it != "待联系" } ?: "已接通"; outcomeNote = ""; outcomeDate = c.followUp; outcomeTags = c.tags.toSet(); showOutcome = true }
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val name = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
            val rows = if (name.endsWith(".xlsx") || mime.contains("spreadsheetml")) readXlsx(context, uri) else {
                val stream = context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readLines().map { line -> csvRow(line.removePrefix("\uFEFF")).map(String::trim) } }
            }
            val parsed = rowsToCustomers(rows)
            val seen = customers.map { digits(it.phone) }.toMutableSet()
            val fresh = parsed.filter { digits(it.phone).isNotBlank() && seen.add(digits(it.phone)) }
            val importedTagNames = fresh.flatMap { it.tags }.distinct().filter { name -> tags.none { it.name == name } }
            if (importedTagNames.isNotEmpty()) {
                saveTags(tags + importedTagNames.mapIndexed { index, name -> CustomerTag(name, palette[(tags.size + index) % palette.size]) })
            }
            saveCustomers(customers + fresh); message = "识别 ${parsed.size} 条，新增 ${fresh.size} 条，重复号码已跳过。"
        } catch (e: Exception) { message = "导入失败：${e.message ?: "请检查文件格式"}" }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) try {
            val exportList = if (selected.isNotEmpty()) customers.filter { it.id in selected } else customers
            val csv = buildString {
                append("姓名,手机号,单位名称,状态,备注情况,下次回访日期,标签,最后沟通\r\n")
                exportList.forEach { c -> append(listOf(c.name,c.phone,c.company,c.status,c.note,c.followUp,c.tags.joinToString("|"),c.lastContactAt).joinToString(",") { csvEscape(it) }); append("\r\n") }
            }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write("\uFEFF$csv") }
            message = "已导出 ${exportList.size} 位客户。"
        } catch (e: Exception) { message = "导出失败：${e.message}" }
    }

    val todayForDialer = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    val current = customers.firstOrNull { it.id == currentId }
    val dialerCustomers = customers.filter { c ->
        (dialerStatusFilter == "全部状态" || c.status == dialerStatusFilter) && when (dialerQuickFilter) {
            "到期回访" -> c.followUp.trim().take(10).let { it.isNotBlank() && it <= todayForDialer }
            "稍后回访" -> c.status == "稍后回访"
            else -> true
        }
    }
    val visible = customers.filter { c ->
        (search.isBlank() || c.name.contains(search, true) || c.phone.contains(search, true) || c.company.contains(search, true)) &&
        (statusFilter == "全部状态" || c.status == statusFilter) && (selectedTags.isEmpty() || selectedTags.all { it in c.tags })
    }
    val customerCallLogs = remember(callLogPermissionGranted, callLogRefresh, customers) {
        if (callLogPermissionGranted) queryCustomerCallLogs(context) else emptyMap()
    }
    fun saveOutcome(goNext: Boolean) {
        val c = current ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val appended = if (outcomeNote.isBlank()) c.note else listOf(c.note, "$stamp：${outcomeNote.trim()}").filter { it.isNotBlank() }.joinToString("\n")
        saveCustomers(customers.map { if (it.id == c.id) it.copy(status = outcome, note = appended, followUp = outcomeDate.trim(), tags = outcomeTags.toList(), lastContactAt = stamp) else it })
        val idx = dialerCustomers.indexOfFirst { it.id == c.id }
        val next = if (goNext && idx >= 0) dialerCustomers.drop(idx + 1).firstOrNull { it.id != c.id } else null
        showOutcome = false
        pendingCall = null
        message = if (goNext && next != null) "记录已保存，正在拨打下一位。" else if (goNext) "记录已保存，当前筛选名单已到最后一位。" else "沟通记录已保存。"
        if (next != null) {
            currentId = next.id
            startCustomerCall(next)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message.isNotBlank()) snackbarHostState.showSnackbar(message)
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 1.dp, color = Color.White) {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.verticalGradient(listOf(Color.White, MaterialTheme.colorScheme.primary.copy(alpha = 0.045f)))
                    )
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page != Page.HOME) {
                            IconButton(onClick = { page = Page.HOME }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "返回首页")
                            }
                        }
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    when (page) {
                                        Page.HOME -> Icons.Filled.Home
                                        Page.CUSTOMERS, Page.TAGS -> Icons.Filled.People
                                        Page.DIALER -> Icons.Filled.Call
                                        Page.STATS -> Icons.Filled.BarChart
                                        Page.SETTINGS -> Icons.Filled.Settings
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(when(page) { Page.HOME -> "沟通无限"; Page.CUSTOMERS -> "客户管理"; Page.DIALER -> "连续拨号"; Page.SETTINGS -> "设置"; Page.STATS -> "统计报表"; Page.TAGS -> "标签管理" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(when(page) { Page.HOME -> "高效沟通 · 客户无限"; Page.CUSTOMERS -> "搜索 · 分类 · 批量管理"; Page.DIALER -> "拨打后记录结果，继续下一位"; Page.SETTINGS -> "双卡拨号、权限与数据管理"; Page.STATS -> "当天与当月跟进情况"; Page.TAGS -> "按业务需要定义客户分类" }, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = page == Page.HOME, onClick = { page = Page.HOME }, icon = { Icon(Icons.Filled.Home, contentDescription = "首页") }, label = { Text("首页") })
                NavigationBarItem(selected = page == Page.CUSTOMERS, onClick = { page = Page.CUSTOMERS }, icon = { Icon(Icons.Filled.People, contentDescription = "客户") }, label = { Text("客户") })
                NavigationBarItem(selected = page == Page.DIALER, onClick = { page = Page.DIALER }, icon = { Icon(Icons.Filled.Call, contentDescription = "拨号") }, label = { Text("拨号") })
                NavigationBarItem(selected = page == Page.STATS, onClick = { page = Page.STATS }, icon = { Icon(Icons.Filled.BarChart, contentDescription = "统计") }, label = { Text("统计") })
                NavigationBarItem(selected = page == Page.SETTINGS || page == Page.TAGS, onClick = { page = Page.SETTINGS }, icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") }, label = { Text("设置") })
            }
        },
        floatingActionButton = { if (page == Page.CUSTOMERS) FloatingActionButton(onClick = { editing = null; showEditor = true }) { Text("+", style = MaterialTheme.typography.headlineMedium) } }
    ) { insets ->
        when(page) {
            Page.HOME -> HomeScreen(
                Modifier.padding(insets), customers, current,
                dialModeDescription(simMode, availableSims, rotationSlot, rotationUsed, rotationCallsPerSim),
                onCallCurrent = { current?.let(::startCustomerCall) },
                onCallCustomer = ::startCustomerCall,
                onDial = { dialerQuickFilter = "全部名单"; dialerStatusFilter = "全部状态"; page = Page.DIALER },
                onCustomers = { page = Page.CUSTOMERS },
                onSelectCustomer = { customer ->
                    currentId = customer.id
                    page = Page.DIALER
                },
                onDueFollowUps = {
                    dialerQuickFilter = "到期回访"; dialerStatusFilter = "全部状态";
                    currentId = customers.firstOrNull { it.followUp.trim().take(10).let { d -> d.isNotBlank() && d <= todayForDialer } }?.id ?: currentId
                    page = Page.DIALER
                },
                onLaterFollowUps = {
                    dialerQuickFilter = "稍后回访"; dialerStatusFilter = "全部状态";
                    currentId = customers.firstOrNull { it.status == "稍后回访" }?.id ?: currentId
                    page = Page.DIALER
                }
            )
            Page.CUSTOMERS -> Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    search,
                    { search = it },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索姓名、电话、单位") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                if (!callLogPermissionGranted) {
                    TextButton(onClick = { callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG) }) {
                        Text("授权后匹配客户的系统通话记录")
                    }
                }
                Text("客户标签（可多选组合筛选）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = selectedTags.isEmpty(), onClick = { selectedTags = emptySet() }, label = { Text("全部") }) }
                    items(tags, key = { it.name }) { t -> FilterChip(selected = t.name in selectedTags, onClick = { selectedTags = if (t.name in selectedTags) selectedTags - t.name else selectedTags + t.name }, label = { Text(t.name) }, leadingIcon = { Box(Modifier.size(9.dp).background(Color(t.color), RoundedCornerShape(50))) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box { OutlinedButton(onClick = { showCustomerStatusMenu = true }) { Text(statusFilter) }; DropdownMenu(expanded = showCustomerStatusMenu, onDismissRequest = { showCustomerStatusMenu = false }) { (listOf("全部状态") + callStatuses).forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { statusFilter = s; showCustomerStatusMenu = false }) } } }
                    Text("  ${visible.size} 位客户", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B), modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = visible.map { it.id }.toSet() }) { Text("全选") }
                    TextButton(onClick = { selected = emptySet() }) { Text("清除") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Text("导入") }
                    OutlinedButton(onClick = { exportLauncher.launch("沟通无限-客户名单.csv") }, modifier = Modifier.weight(1f)) { Text("导出") }
                }
                if (selected.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("已选 ${selected.size} 位", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            Box {
                                TextButton(onClick = { showBulkStatusMenu = true }) { Text("改状态") }
                                DropdownMenu(expanded = showBulkStatusMenu, onDismissRequest = { showBulkStatusMenu = false }) {
                                    callStatuses.forEach { status -> DropdownMenuItem(text = { Text(status) }, onClick = {
                                        val ids = selected
                                        saveCustomers(customers.map { if (it.id in ids) it.copy(status = status) else it })
                                        selected = emptySet(); showBulkStatusMenu = false; message = "已批量修改为「$status」。"
                                    }) }
                                }
                            }
                            TextButton(onClick = { showDeleteConfirm = true }) { Text("批量删除") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box {
                                OutlinedButton(onClick = { showBulkTagMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) { Text("批量标签") }
                                DropdownMenu(expanded = showBulkTagMenu, onDismissRequest = { showBulkTagMenu = false }) {
                                    tags.forEach { tag ->
                                        DropdownMenuItem(text = { Text("添加：${tag.name}") }, onClick = {
                                            val ids = selected
                                            saveCustomers(customers.map { c -> if (c.id in ids) c.copy(tags = (c.tags + tag.name).distinct()) else c })
                                            showBulkTagMenu = false; message = "已为所选客户添加标签「${tag.name}」。"
                                        })
                                        DropdownMenuItem(text = { Text("移除：${tag.name}") }, onClick = {
                                            val ids = selected
                                            saveCustomers(customers.map { c -> if (c.id in ids) c.copy(tags = c.tags - tag.name) else c })
                                            showBulkTagMenu = false; message = "已从所选客户移除标签「${tag.name}」。"
                                        })
                                    }
                                }
                            }
                            OutlinedButton(onClick = {
                                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                                DatePickerDialog(context, { _, y, m, d ->
                                    val date = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d)
                                    val ids = selected
                                    saveCustomers(customers.map { c -> if (c.id in ids) c.copy(followUp = date) else c })
                                    message = "已为所选客户设置回访日期：$date。"
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) { Text("设回访日期") }
                            TextButton(onClick = {
                                val ids = selected
                                saveCustomers(customers.map { c -> if (c.id in ids) c.copy(followUp = "") else c })
                                message = "已清除所选客户的回访日期。"
                            }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) { Text("清除日期") }
                        }
                    }
                }
                if (visible.isEmpty()) EmptyCard("没有匹配客户", "点击右下角 + 新增，或导入 Excel/CSV 文件。")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                    items(visible, key = { it.id }) { c -> CustomerCard(c, tags, c.id in selected, customerCallLogs[phoneMatchKey(c.phone)]?.let(::formatCustomerCall), callLogPermissionGranted, onSelect = { yes -> selected = if (yes) selected + c.id else selected - c.id }, onOpen = { currentId = c.id; page = Page.DIALER }, onEdit = { editing = c; showEditor = true }) }
                }
            }
            Page.DIALER -> Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${dialerCustomers.size} 位待拨客户", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("选中名单后，使用下方按钮一键直拨", style = MaterialTheme.typography.bodySmall, color = Color(0xFF737D8B))
                    }
                    Box {
                        OutlinedButton(onClick = { showDialerStatusMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) { Text(dialerStatusFilter, maxLines = 1, softWrap = false) }
                        DropdownMenu(expanded = showDialerStatusMenu, onDismissRequest = { showDialerStatusMenu = false }) {
                            (listOf("全部状态") + callStatuses).forEach { status -> DropdownMenuItem(text = { Text(status) }, onClick = { dialerStatusFilter = status; dialerQuickFilter = "全部名单"; showDialerStatusMenu = false; val filtered = customers.filter { status == "全部状态" || it.status == status }; if (filtered.none { it.id == currentId }) currentId = filtered.firstOrNull()?.id }) }
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                            Text("当前拨号线路", style = MaterialTheme.typography.labelSmall, color = Color(0xFF667287))
                            Text(
                                dialModeDescription(simMode, availableSims, rotationSlot, rotationUsed, rotationCallsPerSim),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                        TextButton(onClick = { page = Page.SETTINGS }) { Text("切换线路") }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    if (current != null && dialerCustomers.any { it.id == current.id }) item {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                                        Text("当前客户", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Surface(shape = RoundedCornerShape(50), color = Color(0xFFF0F2F5)) {
                                        Text(current.status, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF4F5C6D))
                                    }
                                }
                                Text(current.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                                Text(current.phone, style = MaterialTheme.typography.titleLarge, color = Color(0xFF34445B), maxLines = 1, softWrap = false)
                                if (current.company.isNotBlank() && current.company != current.name) Text(current.company, color = Color(0xFF737D8B), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (current.tags.isNotEmpty()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        current.tags.take(4).forEach { tag ->
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(tag, maxLines = 1) },
                                                leadingIcon = { Box(Modifier.size(7.dp).background(Color(tags.firstOrNull { it.name == tag }?.color ?: palette[0]), RoundedCornerShape(50))) }
                                            )
                                        }
                                    }
                                }
                                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF5F7FA)) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color(0xFF6F7D90), modifier = Modifier.size(16.dp))
                                        Text("  ${lastCommunication(current)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B), maxLines = 1)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { val i = dialerCustomers.indexOfFirst { it.id == current.id }; if (i > 0) currentId = dialerCustomers[i - 1].id }, enabled = dialerCustomers.indexOfFirst { it.id == current.id } > 0, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("上一位", maxLines = 1, softWrap = false) }
                                    TextButton(onClick = { outcome = current.status.takeIf { it != "待联系" } ?: "已接通"; outcomeNote = ""; outcomeDate = current.followUp; outcomeTags = current.tags.toSet(); showOutcome = true }, modifier = Modifier.weight(1.15f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("记录结果", maxLines = 1, softWrap = false) }
                                    OutlinedButton(onClick = { val i = dialerCustomers.indexOfFirst { it.id == current.id }; dialerCustomers.getOrNull(i + 1)?.let { currentId = it.id } ?: run { message = "当前名单已到末尾。" } }, enabled = dialerCustomers.indexOfFirst { it.id == current.id } >= 0 && dialerCustomers.indexOfFirst { it.id == current.id } < dialerCustomers.lastIndex, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("下一位", maxLines = 1, softWrap = false) }
                                }
                            }
                        }
                    } else if (dialerCustomers.isEmpty()) item { EmptyCard("没有符合条件的客户", "请更换状态筛选或先导入客户。") }
                    item { Text("选择名单中的客户", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                    items(dialerCustomers, key = { it.id }) { c ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { currentId = c.id }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = if (c.id == currentId) Color(0xFFF0F3F8) else Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(c.name, fontWeight = FontWeight.Medium, maxLines = 2)
                                    Text(c.phone, style = MaterialTheme.typography.bodySmall, color = Color(0xFF526174), maxLines = 1)
                                    Text("${c.status} · ${lastCommunication(c)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF818A96), maxLines = 1)
                                }
                                if (c.id == currentId) Icon(Icons.Filled.CheckCircle, contentDescription = "当前客户", tint = Color(0xFF6B9487), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (current != null && dialerCustomers.any { it.id == current.id }) {
                    Surface(color = Color.White, shadowElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                            Button(onClick = { startCustomerCall(current) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Filled.PhoneInTalk, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (simMode == "system") "拨打当前客户" else "通过 ${dialModeDescription(simMode, availableSims, rotationSlot, rotationUsed, rotationCallsPerSim)} 拨打",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text("按下后直接拨出，不需要再次选择电话卡", style = MaterialTheme.typography.labelSmall, color = Color(0xFF737D8B), modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 3.dp))
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFF737D8B), maxLines = 2)
            }
            Page.STATS -> {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val month = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                val periodPrefix = if (statsPeriod == "当天统计") today else month
                val touched = customers.filter { c -> c.note.lines().any { line -> line.trim().startsWith(periodPrefix) } }
                val statusCount = callStatuses.filter { it != "待联系" }.associateWith { status -> touched.count { it.status == status } }
                val periodStart = try { SimpleDateFormat(if (statsPeriod == "当天统计") "yyyy-MM-dd" else "yyyy-MM", Locale.getDefault()).parse(periodPrefix)?.time ?: 0L } catch (_: Exception) { 0L }
                val systemCalls = remember(statsPeriod, callLogPermissionGranted, callLogRefresh, customers.size) { if (callLogPermissionGranted) queryOutgoingCalls(context, periodStart) else emptyList() }
                Column(Modifier.fillMaxSize().padding(insets).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = statsPeriod == "当天统计", onClick = { statsPeriod = "当天统计" }, label = { Text("当天统计") }, modifier = Modifier.weight(1f))
                        FilterChip(selected = statsPeriod == "当月统计", onClick = { statsPeriod = "当月统计" }, label = { Text("当月统计") }, modifier = Modifier.weight(1f))
                    }
                    Text(if (statsPeriod == "当天统计") today else month, color = Color(0xFF737D8B))
                    if (!callLogPermissionGranted) {
                        OutlinedButton(onClick = { callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG) }, modifier = Modifier.fillMaxWidth()) { Text("授权读取系统通话记录") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCard("系统拨出次数", systemCalls.size.toString(), Modifier.weight(1f))
                            MetricCard("已接通次数", systemCalls.count { it.durationSeconds > 0 }.toString(), Modifier.weight(1f))
                        }
                        val totalSeconds = systemCalls.sumOf { it.durationSeconds }
                        Text("系统通话时长：${totalSeconds / 60} 分 ${totalSeconds % 60} 秒", style = MaterialTheme.typography.bodySmall, color = Color(0xFF737D8B))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("有沟通记录客户", touched.size.toString(), Modifier.weight(1f))
                        MetricCard("已接通", (statusCount["已接通"] ?: 0).toString(), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("未接听", (statusCount["未接听"] ?: 0).toString(), Modifier.weight(1f))
                        MetricCard("稍后回访", (statusCount["稍后回访"] ?: 0).toString(), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("拒绝", (statusCount["拒绝"] ?: 0).toString(), Modifier.weight(1f))
                        MetricCard("已成交", (statusCount["已成交"] ?: 0).toString(), Modifier.weight(1f))
                    }
                    Text("系统拨出次数和时长来自 Android 通话记录；接通以系统记录的通话时长大于 0 估算。客户沟通状态统计仍来自应用内记录。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF737D8B))
                }
            }
            Page.SETTINGS -> {
                fun applySimMode(mode: String) {
                    simMode = mode
                    rotationSlot = 0
                    rotationUsed = 0
                    settingsPrefs.edit()
                        .putString("simMode", mode)
                        .putInt("simRotationSlot", 0)
                        .putInt("simRotationIndex", 0)
                        .putInt("simRotationUsed", 0)
                        .apply()
                    simRefresh++
                    if (mode != "system" && !phoneStateGranted) {
                        phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                    }
                }
                fun openAppPermissionSettings() {
                    try {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    } catch (_: Exception) {
                        message = "无法打开系统应用设置，请从系统设置的应用管理中调整权限。"
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize().padding(insets),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary) {
                                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White)
                                    }
                                }
                                Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("拨号偏好", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("选择线路后，点“一键拨打”立即呼叫当前客户。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF5F6C7E))
                                }
                            }
                        }
                    }

                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("双卡直拨", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("通过 Android Telecom 指定账户，不弹出选卡窗口", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                                    }
                                    StatusPill(
                                        text = if (phoneStateGranted) "${availableSims.count { it.account != null }}/${availableSims.size} 卡可拨" else "待授权",
                                        active = phoneStateGranted && availableSims.any { it.account != null }
                                    )
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val sim1 = availableSims.firstOrNull { it.slotIndex == 0 }
                                    val sim2 = availableSims.firstOrNull { it.slotIndex == 1 }
                                    FilterChip(selected = simMode == "system", onClick = { applySimMode("system") }, label = { Text("系统默认") })
                                    FilterChip(selected = simMode == "sim1", onClick = { applySimMode("sim1") }, label = { Text("SIM1${sim1?.network?.let { " · $it" }.orEmpty()}") })
                                    FilterChip(selected = simMode == "sim2", onClick = { applySimMode("sim2") }, label = { Text("SIM2${sim2?.network?.let { " · $it" }.orEmpty()}") })
                                    FilterChip(selected = simMode == "rotate", onClick = { applySimMode("rotate") }, label = { Text("按拨号轮换") })
                                }
                                if (phoneStateGranted && availableSims.isEmpty()) {
                                    InlineNotice("系统未返回活动 SIM 信息。请确认两张卡都已启用，再点“重新检测”。")
                                }
                                if (availableSims.any { it.account == null }) {
                                    InlineNotice("部分 SIM 未返回通话账户。请确认该卡已启用“语音通话”，必要时重试检测。")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (simMode == "system") "当前：使用系统默认拨号卡" else "当前：${dialModeDescription(simMode, availableSims, rotationSlot, rotationUsed, rotationCallsPerSim)}",
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    TextButton(onClick = {
                                        phoneStateGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                                        simRefresh++
                                        message = if (phoneStateGranted) "已重新检测 SIM 卡。" else "请先授权“电话状态”权限。"
                                    }) { Text("重新检测") }
                                }
                            }
                        }
                    }

                    if (simMode == "rotate") {
                        item {
                            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC))) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("轮换规则", fontWeight = FontWeight.Bold)
                                    Text("同一张卡连续拨出指定次数后，自动切换另一张卡。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(1, 2, 3, 5, 10).forEach { count ->
                                            FilterChip(
                                                selected = rotationCallsPerSim == count,
                                                onClick = {
                                                    rotationCallsPerSim = count
                                                    rotationSlot = 0
                                                    rotationUsed = 0
                                                    settingsPrefs.edit()
                                                        .putInt("simRotationCallsPerSim", count)
                                                        .putInt("simRotationSlot", 0)
                                                        .putInt("simRotationUsed", 0)
                                                        .apply()
                                                },
                                                label = { Text("每卡 $count 次") }
                                            )
                                        }
                                    }
                                    Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                                        Text(
                                            "下一次：SIM${rotationSlot + 1} · 本卡已拨 $rotationUsed/$rotationCallsPerSim 次",
                                            Modifier.fillMaxWidth().padding(12.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("外观设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("字体大小", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0.8f to "小", 1.0f to "标准", 1.2f to "大").forEach { (scale, label) ->
                                        FilterChip(selected = fontScale == scale, onClick = { onFontScaleChange(scale) }, label = { Text(label) })
                                    }
                                }
                                Text("主题颜色", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    listOf(
                                        0xFF5879A8.toInt(), 0xFF39856B.toInt(), 0xFF8064A8.toInt(),
                                        0xFFC47A36.toInt(), 0xFF318C9A.toInt()
                                    ).forEach { color ->
                                        Box(
                                            Modifier.size(34.dp)
                                                .background(Color(color), RoundedCornerShape(50))
                                                .clickable { onThemeColorChange(color) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (themeColor == color) Icon(Icons.Filled.CheckCircle, contentDescription = "当前主题", tint = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("快捷入口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SettingsLink("客户标签", "创建、编辑标签及颜色", Icons.Filled.People) { page = Page.TAGS }
                            SettingsLink("沟通统计", "当天与当月拨出记录和跟进情况", Icons.Filled.BarChart) { page = Page.STATS }
                        }
                    }

                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("本机客户数据", fontWeight = FontWeight.Bold)
                                    Text("当前客户数量：${customers.size}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                                    Text("数据保存在本机，请定期从客户管理页导出备份。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                                }
                                Text("${customers.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("系统权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("以下为应用会调用的 Android 权限。只有前两项是 SIM 一键直拨的必需权限。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text("权限状态", fontWeight = FontWeight.Bold)
                                        Text("可随时在系统设置中撤销", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                                    }
                                    OutlinedButton(onClick = {
                                        allPermissionLauncher.launch(arrayOf(
                                            Manifest.permission.CALL_PHONE,
                                            Manifest.permission.READ_PHONE_STATE,
                                            Manifest.permission.READ_CALL_LOG
                                        ))
                                    }) { Text("检查授权") }
                                }
                                HorizontalDivider(color = Color(0xFFE8ECF2))
                                PermissionSettingRow(
                                    title = "直接拨打电话",
                                    permission = "android.permission.CALL_PHONE",
                                    purpose = "按下拨号按钮后直接呼叫；必需",
                                    granted = callPermissionGranted,
                                    onRequest = { callPermissionLauncher.launch(Manifest.permission.CALL_PHONE) },
                                    onManage = ::openAppPermissionSettings
                                )
                                HorizontalDivider(color = Color(0xFFE8ECF2))
                                PermissionSettingRow(
                                    title = "读取电话状态与 SIM 账户",
                                    permission = "android.permission.READ_PHONE_STATE",
                                    purpose = "识别 SIM1/SIM2 并指定 Telecom 通话账户；必需",
                                    granted = phoneStateGranted,
                                    onRequest = { phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
                                    onManage = ::openAppPermissionSettings
                                )
                                HorizontalDivider(color = Color(0xFFE8ECF2))
                                PermissionSettingRow(
                                    title = "读取系统通话记录",
                                    permission = "android.permission.READ_CALL_LOG",
                                    purpose = "匹配客户最近通话并生成拨出统计；可选",
                                    granted = callLogPermissionGranted,
                                    onRequest = { callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG) },
                                    onManage = ::openAppPermissionSettings
                                )
                                Text("应用不申请通讯录、短信、定位或网络权限。客户名单由你选择的本地文件导入。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                            }
                        }
                    }
                }
            }

            Page.TAGS -> Column(Modifier.fillMaxSize().padding(insets).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自定义标签", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("标签用于业务分类，可为客户添加多个标签，并在客户列表组合筛选。", color = Color(0xFF64748B))
                Button(onClick = { editingTag = null; showTagDialog = true }, Modifier.fillMaxWidth()) { Text("+ 新建标签") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tags, key = { it.name }) { tag -> Card(shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(Color(tag.color), RoundedCornerShape(50)))
                        Text(tag.name, Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold)
                        Text("${customers.count { tag.name in it.tags }} 位", color = Color(0xFF64748B))
                        TextButton(onClick = { editingTag = tag; showTagDialog = true }) { Text("编辑") }
                        TextButton(onClick = { saveTags(tags.filterNot { it.name == tag.name }); saveCustomers(customers.map { it.copy(tags = it.tags - tag.name) }); selectedTags = selectedTags - tag.name; message = "标签已删除，客户资料已更新。" }) { Text("删除") }
                    } } }
                }
            }
        }
    }

    if (showEditor) CustomerEditor(editing, tags, onDismiss = { showEditor = false }, onSave = { c ->
        val exists = customers.any { it.id == c.id }; saveCustomers(if (exists) customers.map { if (it.id == c.id) c else it } else listOf(c) + customers); currentId = c.id; showEditor = false; message = if (exists) "客户资料已保存。" else "客户已新增。"
    }, onDelete = { c -> saveCustomers(customers.filterNot { it.id == c.id }); showEditor = false; message = "客户已删除。" }, onCreateTag = { name -> if (tags.none { it.name.equals(name,true) }) saveTags(tags + CustomerTag(name, palette[tags.size % palette.size])) })

    if (showOutcome && current != null) AlertDialog(
        onDismissRequest = { showOutcome = false; pendingCall = null },
        title = { Text("快速记录 · ${current.name}") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                callStatuses.filter { it != "待联系" }.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { st -> FilterChip(selected = outcome == st, onClick = { outcome = st }, label = { Text(st, maxLines = 1) }, modifier = Modifier.weight(1f)) }
                    }
                }
                OutlinedTextField(outcomeNote, { outcomeNote = it }, label = { Text("本次沟通情况") }, placeholder = { Text("例如：有意向，下周报价") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedButton(onClick = {
                    val cal = Calendar.getInstance()
                    val parsed = try { if (outcomeDate.isNotBlank()) SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(outcomeDate) else null } catch (_: Exception) { null }
                    if (parsed != null) cal.time = parsed else cal.add(Calendar.DAY_OF_YEAR, 1)
                    DatePickerDialog(context, { _, y, m, d -> outcomeDate = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d) }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (outcomeDate.isBlank()) "下次回访日期：不设置（点击选择）" else "下次回访：$outcomeDate（点击修改）")
                }
                Box {
                    OutlinedButton(onClick = { outcomeTagMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (outcomeTags.isEmpty()) "客户标签：未选择（点击选择）" else "客户标签：${outcomeTags.joinToString("、")}")
                    }
                    DropdownMenu(expanded = outcomeTagMenu, onDismissRequest = { outcomeTagMenu = false }) {
                        if (tags.isEmpty()) DropdownMenuItem(text = { Text("请先在设置中创建标签") }, onClick = { outcomeTagMenu = false })
                        tags.forEach { tag ->
                            DropdownMenuItem(text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).background(Color(tag.color), RoundedCornerShape(50)))
                                    Spacer(Modifier.width(8.dp)); Text(tag.name); Spacer(Modifier.weight(1f))
                                    if (tag.name in outcomeTags) Icon(Icons.Filled.CheckCircle, contentDescription = "已选择")
                                }
                            }, onClick = { outcomeTags = if (tag.name in outcomeTags) outcomeTags - tag.name else outcomeTags + tag.name })
                        }
                    }
                }
            }
        },
        confirmButton = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { saveOutcome(false) }, modifier = Modifier.fillMaxWidth()) { Text("保存记录") }
            Button(onClick = { saveOutcome(true) }, modifier = Modifier.fillMaxWidth()) { Text("保存并拨下一位") }
        } },
        dismissButton = { TextButton(onClick = { showOutcome = false; pendingCall = null }) { Text("稍后记录") } }
    )

    if (showDeleteConfirm) AlertDialog(onDismissRequest = { showDeleteConfirm = false }, title = { Text("批量删除客户") }, text = { Text("确定删除已选的 ${selected.size} 位客户？此操作不可撤销。") }, confirmButton = { Button(onClick = { val n = selected.size; saveCustomers(customers.filterNot { it.id in selected }); selected = emptySet(); showDeleteConfirm = false; message = "已删除 $n 位客户。" }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } })

    if (showTagDialog) TagEditor(editingTag, onDismiss = { showTagDialog = false }, onSave = { tag ->
        if (tags.none { it.name.equals(tag.name, true) && it.name != editingTag?.name }) {
            if (editingTag == null) saveTags(tags + tag) else {
                val old = editingTag!!.name; saveTags(tags.map { if (it.name == old) tag else it }); saveCustomers(customers.map { c -> c.copy(tags = c.tags.map { if (it == old) tag.name else it }.distinct()) }); selectedTags = selectedTags.map { if (it == old) tag.name else it }.toSet()
            }; message = "标签已保存。"
        } else message = "标签名称已存在。"
        showTagDialog = false
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    modifier: Modifier,
    customers: List<Customer>,
    currentCustomer: Customer?,
    lineSummary: String,
    onCallCurrent: () -> Unit,
    onCallCustomer: (Customer) -> Unit,
    onDial: () -> Unit,
    onCustomers: () -> Unit,
    onSelectCustomer: (Customer) -> Unit,
    onDueFollowUps: () -> Unit,
    onLaterFollowUps: () -> Unit
) {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    val dueFollowUps = customers.count { c -> c.followUp.trim().take(10).let { it.isNotBlank() && it <= today } }
    val laterFollowUps = customers.count { it.status == "稍后回访" }
    val waiting = customers.count { it.status == "待联系" }
    val priorityCustomers = (
        customers.filter { c -> c.followUp.trim().take(10).let { it.isNotBlank() && it <= today } } +
            customers.filter { it.status == "稍后回访" } +
            customers.filter { it.status == "待联系" }
        ).distinctBy { it.id }.filter { it.id != currentCustomer?.id }.take(3)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("今日工作台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("优先处理到期回访和稍后回访客户", style = MaterialTheme.typography.bodySmall, color = Color(0xFF667287))
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                    Text(
                        lineSummary,
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.78f))
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("下一位客户", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        if (currentCustomer != null) {
                            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.16f)) {
                                Text(
                                    currentCustomer.status,
                                    Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    if (currentCustomer != null) {
                        Text(currentCustomer.name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(currentCustomer.phone, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                        if (currentCustomer.company.isNotBlank() && currentCustomer.company != currentCustomer.name) {
                            Text(currentCustomer.company, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        if (currentCustomer.tags.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                currentCustomer.tags.take(3).forEach { tag ->
                                    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.13f)) {
                                        Text(tag, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = onCallCurrent,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Filled.PhoneInTalk, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("一键拨打当前客户", fontWeight = FontWeight.Bold)
                        }
                        Text("上次沟通：${lastCommunication(currentCustomer)}", color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    } else {
                        Text("还没有可拨打的客户", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("导入 Excel 或 CSV 后，就能从这里直接开始。", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = onCustomers,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary)
                        ) { Text("导入客户") }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeMetricCard("$waiting", "待联系", Modifier.weight(1f))
                HomeMetricCard("$dueFollowUps", "到期回访", Modifier.weight(1f), onClick = onDueFollowUps)
                HomeMetricCard("$laterFollowUps", "稍后回访", Modifier.weight(1f), onClick = onLaterFollowUps)
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("继续处理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("按到期、稍后回访、待联系排序", style = MaterialTheme.typography.bodySmall, color = Color(0xFF737D8B))
                }
                TextButton(onClick = onDial) { Text("查看全部") }
            }
        }

        if (priorityCustomers.isEmpty()) {
            item { EmptyCard("当前没有待处理客户", "可以进入客户管理新增或导入名单。") }
        } else {
            items(priorityCustomers, key = { it.id }) { customer ->
                HomePriorityRow(customer, onClick = { onSelectCustomer(customer) }, onCall = { onCallCustomer(customer) })
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).clickable(onClick = onCustomers).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.People, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                    Text("管理全部 ${customers.size} 位客户", fontWeight = FontWeight.Medium)
                    Text("搜索、导入、导出、批量修改和回访筛选", style = MaterialTheme.typography.bodySmall, color = Color(0xFF737D8B))
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color(0xFF8290A3))
            }
        }
    }
}

@Composable
private fun HomeMetricCard(value: String, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val clickModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Column(
        clickModifier
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF737D8B))
    }
}

@Composable
private fun HomePriorityRow(customer: Customer, onClick: () -> Unit, onCall: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(customer.name.trim().take(1).ifBlank { "客" }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(customer.name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(customer.phone, style = MaterialTheme.typography.bodySmall, color = Color(0xFF526174), maxLines = 1)
            Text(
                if (customer.followUp.isNotBlank()) "${customer.status} · 回访 ${customer.followUp.take(10)}" else "${customer.status} · ${lastCommunication(customer)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF818A96),
                maxLines = 1
            )
        }
        Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onCall)) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Call, contentDescription = "拨打 ${customer.name}", tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable private fun HomeAction(title: String, subtitle: String, icon: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Color(0xFFEAF0FF), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Text(icon, color = Color(0xFF245BDB), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color(0xFF64748B))
        }
    }
}

@Composable private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier, shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF245BDB)); Text(label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B)) } } }
@Composable private fun CustomerCard(c: Customer, tags: List<CustomerTag>, selected: Boolean, callSummary: String?, callLogAccess: Boolean, onSelect: (Boolean) -> Unit, onOpen: () -> Unit, onEdit: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEAF0FF) else Color.White)) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = onSelect)
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) { Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(c.phone, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF245BDB)); if (c.company.isNotBlank()) Text(c.company, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B)) }
            AssistChip(onClick = onOpen, label = { Text(c.status) })
        }
        if (c.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { c.tags.forEach { name -> val tag = tags.firstOrNull { it.name == name }; AssistChip(onClick = {}, label = { Text(name) }, leadingIcon = { Box(Modifier.size(8.dp).background(Color(tag?.color ?: palette[0]), RoundedCornerShape(50))) }) } }
        if (c.note.isNotBlank()) Text("备注：${c.note.takeLast(90)}", style = MaterialTheme.typography.bodySmall, maxLines = 2, color = Color(0xFF475569))
        Text(
            if (!callLogAccess) "系统通话：授权后匹配" else callSummary ?: "系统通话：暂无匹配记录",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B),
            maxLines = 1
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onOpen) { Text("拨号 / 详情") }; TextButton(onClick = onEdit) { Text("编辑") } }
    } }
}
@Composable private fun EmptyCard(title: String, subtitle: String) { Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = Color(0xFF64748B)) } } }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CustomerEditor(initial: Customer?, tags: List<CustomerTag>, onDismiss: () -> Unit, onSave: (Customer) -> Unit, onDelete: (Customer) -> Unit, onCreateTag: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }; var phone by remember(initial?.id) { mutableStateOf(initial?.phone.orEmpty()) }; var company by remember(initial?.id) { mutableStateOf(initial?.company.orEmpty()) }; var note by remember(initial?.id) { mutableStateOf(initial?.note.orEmpty()) }; var status by remember(initial?.id) { mutableStateOf(initial?.status ?: "待联系") }; var followUp by remember(initial?.id) { mutableStateOf(initial?.followUp.orEmpty()) }; var chosen by remember(initial?.id) { mutableStateOf(initial?.tags?.toSet() ?: emptySet()) }; var error by remember { mutableStateOf("") }; var statusMenu by remember { mutableStateOf(false) }; var newTag by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "新增客户" else "编辑客户") }, text = { Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("姓名 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(phone, { phone = it }, label = { Text("电话 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(company, { company = it }, label = { Text("单位名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Box { OutlinedButton(onClick = { statusMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("沟通状态：$status") }; DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) { callStatuses.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { status = s; statusMenu = false }) } } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = followUp,
                onValueChange = {},
                readOnly = true,
                label = { Text("下次回访日期") },
                placeholder = { Text("选择回访日期") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedButton(onClick = {
                val calendar = Calendar.getInstance()
                val parsed = try { if (followUp.isNotBlank()) SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(followUp.take(10)) else null } catch (_: Exception) { null }
                if (parsed != null) calendar.time = parsed else calendar.add(Calendar.DAY_OF_YEAR, 1)
                DatePickerDialog(
                    context,
                    { _, year, month, day -> followUp = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day) },
                    calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
                ).show()
            }) { Text("选日期") }
        }
        Text("打开日期选择器时，默认选中明天；也可以改选其他日期。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
        OutlinedTextField(note, { note = it }, label = { Text("备注 / 沟通情况") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Text("客户标签（可多选）", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { t -> FilterChip(selected = t.name in chosen, onClick = { chosen = if (t.name in chosen) chosen - t.name else chosen + t.name }, label = { Text(t.name) }, leadingIcon = { Box(Modifier.size(9.dp).background(Color(t.color), RoundedCornerShape(50))) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(newTag, { newTag = it }, modifier = Modifier.weight(1f), label = { Text("新建标签") }, singleLine = true)
            TextButton(onClick = { if (newTag.isNotBlank()) { onCreateTag(newTag.trim()); chosen = chosen + newTag.trim(); newTag = "" } }, modifier = Modifier.widthIn(min = 64.dp)) { Text("添加", maxLines = 1) }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = { if (name.isBlank() || phone.isBlank()) error = "姓名和电话不能为空。" else onSave((initial ?: Customer(name = name.trim(), phone = phone.trim())).copy(name = name.trim(), phone = phone.trim(), company = company.trim(), note = note.trim(), status = status, followUp = followUp.trim(), tags = chosen.toList())) }) { Text("保存") } }, dismissButton = { Row { if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("删除") }; TextButton(onClick = onDismiss) { Text("取消") } } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TagEditor(initial: CustomerTag?, onDismiss: () -> Unit, onSave: (CustomerTag) -> Unit) {
    var name by remember(initial?.name) { mutableStateOf(initial?.name.orEmpty()) }; var color by remember(initial?.name) { mutableLongStateOf(initial?.color ?: palette[0]) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "新建标签" else "编辑标签") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("标签名称") }, singleLine = true)
        Text("选择标签颜色")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            palette.forEach { p ->
                Box(Modifier.size(34.dp).background(Color(p), RoundedCornerShape(50)).clickable { color = p }, contentAlignment = Alignment.Center) {
                    if (color == p) Text("✓", color = Color.White)
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = { if (name.isBlank()) error = "请输入标签名称。" else onSave(CustomerTag(name.trim(), color)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    val background = if (active) Color(0xFFE6F4EE) else Color(0xFFFFF1E4)
    val foreground = if (active) Color(0xFF247257) else Color(0xFF9A5B20)
    Surface(shape = RoundedCornerShape(50), color = background) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = foreground, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InlineNotice(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFF5E8)) {
        Text(text, Modifier.fillMaxWidth().padding(11.dp), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A5A21))
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color(0xFF8290A3))
        }
    }
}

@Composable
private fun PermissionSettingRow(
    title: String,
    permission: String,
    purpose: String,
    granted: Boolean,
    onRequest: () -> Unit,
    onManage: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).background(
                if (granted) Color(0xFF3A9B72) else Color(0xFFE09A45),
                RoundedCornerShape(50)
            )
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(permission, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8190A2))
            Text(purpose, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
        }
        TextButton(onClick = if (granted) onManage else onRequest) {
            Text(if (granted) "管理" else "授权", color = if (granted) Color(0xFF3A7F65) else MaterialTheme.colorScheme.primary)
        }
    }
}

private fun dialModeDescription(
    simMode: String,
    sims: List<SimCallOption>,
    rotationSlot: Int,
    rotationUsed: Int,
    rotationCallsPerSim: Int
): String {
    if (simMode == "system") return "系统默认卡"
    if (simMode == "rotate") {
        val sim = sims.firstOrNull { it.slotIndex == rotationSlot }
        return "SIM${rotationSlot + 1}${sim?.network?.let { " · $it" }.orEmpty()}（$rotationUsed/$rotationCallsPerSim）"
    }
    val slot = when (simMode) {
        "sim1" -> 0
        "sim2" -> 1
        else -> return "未设置"
    }
    val sim = sims.firstOrNull { it.slotIndex == slot }
    return when {
        sim == null -> "SIM${slot + 1}（未检测到）"
        sim.account == null -> "SIM${slot + 1} · ${sim.network}（账户未就绪）"
        else -> "SIM${slot + 1} · ${sim.network}"
    }
}

private data class SystemCallRecord(val number: String, val dateMillis: Long, val durationSeconds: Long, val type: Int = CallLog.Calls.OUTGOING_TYPE)

private fun activeSimCallOptions(context: Context): List<SimCallOption> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return try {
        val subscriptions = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager ?: return emptyList()
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return emptyList()
        val activeSubscriptions = subscriptions.activeSubscriptionInfoList.orEmpty()
            .filter { it.simSlotIndex >= 0 }
            .sortedBy { it.simSlotIndex }
        if (activeSubscriptions.isEmpty()) return emptyList()

        val callCapable = telecom.callCapablePhoneAccounts
        // getPhoneAccount() can require READ_PHONE_NUMBERS on Huawei. The connection-service
        // component identifies SIM accounts without reading or exposing the subscriber number.
        val simAccounts = callCapable.filter { handle ->
            val component = handle.componentName
            component.packageName == "com.android.phone" ||
                component.className.contains("TelephonyConnectionService", ignoreCase = true)
        }
        if (simAccounts.isEmpty()) {
            Log.w("NovaDialer", "Telecom returned no phone service accounts; callCapable=${callCapable.size}")
            return emptyList()
        }
        val usableAccounts = simAccounts

        val assigned = mutableSetOf<PhoneAccountHandle>()
        val resolved = activeSubscriptions.mapIndexed { index, subscription ->
            val subId = subscription.subscriptionId
            val exact = usableAccounts.firstOrNull { handle ->
                handle !in assigned && handle.id == subId.toString()
            }
            val tokenMatch = usableAccounts.firstOrNull { handle ->
                if (handle in assigned) return@firstOrNull false
                val id = handle.id.orEmpty()
                id.matches(Regex("(^|\\D)${Regex.escape(subId.toString())}(\\D|$)"))
            }
            // 华为等定制系统可能使用槽位号或内部 ID；只有双卡账户数量一致时才按系统排序兜底。
            val ordinalFallback = if (usableAccounts.size == activeSubscriptions.size) {
                usableAccounts.getOrNull(index)?.takeIf { it !in assigned }
            } else null
            val picked = exact ?: tokenMatch ?: ordinalFallback
            if (picked != null) assigned.add(picked)
            val method = when {
                exact != null -> "subscription-id"
                tokenMatch != null -> "id-contains-subscription"
                ordinalFallback != null -> "system-order"
                else -> "unmatched"
            }
            SimCallOption(
                slotIndex = subscription.simSlotIndex,
                subscriptionId = subId,
                displayName = subscription.displayName?.toString().orEmpty(),
                carrierName = subscription.carrierName?.toString().orEmpty(),
                account = picked,
                matchMethod = method
            )
        }
        Log.i(
            "NovaDialer",
            resolved.joinToString("; ") { sim ->
                "${sim.title} ${sim.network}: account=${sim.account != null}, match=${sim.matchMethod}"
            }
        )
        resolved
    } catch (e: SecurityException) {
        Log.w("NovaDialer", "Unable to read SIM accounts", e)
        emptyList()
    } catch (e: Exception) {
        Log.w("NovaDialer", "Unable to resolve SIM accounts", e)
        emptyList()
    }
}

private fun phoneMatchKey(number: String): String {
    val d = digits(number)
    return when {
        d.startsWith("0086") && d.length > 11 -> d.drop(4)
        d.startsWith("86") && d.length > 11 -> d.drop(2)
        else -> d
    }
}

private fun queryCustomerCallLogs(context: Context): Map<String, SystemCallRecord> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyMap()
    val result = linkedMapOf<String, SystemCallRecord>()
    val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE)
    try {
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex).orEmpty()
                val key = phoneMatchKey(number)
                if (key.isNotBlank() && key !in result) {
                    result[key] = SystemCallRecord(number, cursor.getLong(dateIndex), cursor.getLong(durationIndex), cursor.getInt(typeIndex))
                }
            }
        }
    } catch (_: SecurityException) { return emptyMap() } catch (_: Exception) { return emptyMap() }
    return result
}

private fun formatCustomerCall(record: SystemCallRecord): String {
    val type = when (record.type) {
        CallLog.Calls.INCOMING_TYPE -> "呼入"
        CallLog.Calls.OUTGOING_TYPE -> "呼出"
        CallLog.Calls.MISSED_TYPE -> "未接"
        CallLog.Calls.REJECTED_TYPE -> "拒接"
        CallLog.Calls.BLOCKED_TYPE -> "拦截"
        CallLog.Calls.VOICEMAIL_TYPE -> "语音留言"
        else -> "通话"
    }
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(record.dateMillis))
    val duration = if (record.durationSeconds > 0) " · ${record.durationSeconds}秒" else ""
    return "系统通话：$type $time$duration"
}

private fun queryOutgoingCalls(context: Context, periodStartMillis: Long): List<SystemCallRecord> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val result = mutableListOf<SystemCallRecord>()
    val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE)
    val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?"
    try {
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), periodStartMillis.toString()), "${CallLog.Calls.DATE} DESC")?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            while (cursor.moveToNext()) result += SystemCallRecord(
                cursor.getString(numberIndex).orEmpty(),
                cursor.getLong(dateIndex),
                cursor.getLong(durationIndex),
                if (typeIndex >= 0) cursor.getInt(typeIndex) else CallLog.Calls.OUTGOING_TYPE
            )
        }
    } catch (_: SecurityException) { return emptyList() } catch (_: Exception) { return emptyList() }
    return result
}

private fun loadCustomers(context: Context): List<Customer> = try {
    val a = JSONArray(context.getSharedPreferences("nova_customers", Context.MODE_PRIVATE).getString("data", "[]") ?: "[]")
    (0 until a.length()).map { i -> val o = a.getJSONObject(i); Customer(o.optString("id", UUID.randomUUID().toString()), o.optString("name"), o.optString("phone"), o.optString("company"), o.optString("note"), o.optString("status", "待联系"), o.optString("followUp"), o.optJSONArray("tags")?.let { ar -> (0 until ar.length()).map { ar.optString(it) } } ?: emptyList(), o.optString("lastContactAt")) }.filter { it.name.isNotBlank() && it.phone.isNotBlank() }
} catch (_: Exception) { emptyList() }
private fun persistCustomers(context: Context, list: List<Customer>) { val a = JSONArray(); list.forEach { c -> a.put(JSONObject().apply { put("id", c.id); put("name", c.name); put("phone", c.phone); put("company", c.company); put("note", c.note); put("status", c.status); put("followUp", c.followUp); put("tags", JSONArray(c.tags)); put("lastContactAt", c.lastContactAt) }) }; context.getSharedPreferences("nova_customers", Context.MODE_PRIVATE).edit().putString("data", a.toString()).apply() }
private fun loadTags(context: Context): List<CustomerTag> = try { val a = JSONArray(context.getSharedPreferences("nova_tags", Context.MODE_PRIVATE).getString("data", "[]") ?: "[]"); (0 until a.length()).map { val o = a.getJSONObject(it); CustomerTag(o.optString("name"), o.optLong("color", palette[0])) } } catch (_: Exception) { emptyList() }
private fun persistTags(context: Context, list: List<CustomerTag>) { val a = JSONArray(); list.forEach { a.put(JSONObject().apply { put("name", it.name); put("color", it.color) }) }; context.getSharedPreferences("nova_tags", Context.MODE_PRIVATE).edit().putString("data", a.toString()).apply() }
private fun lastCommunication(c: Customer): String {
    if (c.lastContactAt.isNotBlank()) return c.lastContactAt
    val pattern = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")
    return c.note.lines().asReversed().firstNotNullOfOrNull { line -> pattern.find(line.trim())?.value } ?: "暂无记录"
}
private fun digits(s: String) = s.filter { it.isDigit() }
private fun csvEscape(s: String) = "\"" + s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""

private fun rowsToCustomers(rows: List<List<String>>): List<Customer> {
    val clean = rows.filter { it.any(String::isNotBlank) }
    if (clean.isEmpty()) return emptyList()

    fun norm(s: String) = s.trim().lowercase(Locale.ROOT)
        .replace(" ", "").replace("_", "").replace("-", "").replace("（", "(").replace("）", ")")

    val nameKeys = setOf("姓名", "客户姓名", "name", "联系人", "联系人姓名")
    val companyKeys = setOf("单位", "单位名称", "公司", "公司名称", "企业名称", "company", "organization", "机构")
    val phoneKeys = setOf(
        "电话", "手机号", "手机号码", "联系电话", "电话/手机", "phone", "mobile", "tel",
        "联系电话号码", "电话号码", "工商年报电话", "更多联系电话", "固定电话", "座机", "联系手机",
        "手机1", "手机2", "电话1", "电话2", "联系电话1", "联系电话2"
    )
    fun isPhoneHeader(value: String): Boolean {
        val h = norm(value)
        return h in phoneKeys || h.startsWith("更多联系电话") ||
            h.startsWith("工商年报电话") || h.startsWith("联系电话") ||
            h.matches(Regex("^(手机|电话|手机号|mobile|phone|tel)\\d+$"))
    }

    // 支持常规客户表，也支持企业查询导出表（企业名称 + 多个联系电话字段）。
    val headerIndex = clean.indexOfFirst { row ->
        row.any { norm(it) in nameKeys || norm(it) in companyKeys } && row.any { isPhoneHeader(it) }
    }
    val header = if (headerIndex >= 0) clean[headerIndex].map(::norm) else emptyList()
    fun col(keys: Set<String>) = header.indexOfFirst { it in keys }
    val nc = col(nameKeys)
    val cc = col(companyKeys)
    val mc = col(setOf("备注", "备注情况", "沟通备注", "跟进情况", "客户情况", "说明", "note", "remark", "comments"))
    val legalRepCol = col(setOf("法定代表人", "法人代表", "法人", "legalrepresentative"))
    val addressCol = col(setOf("地址", "注册地址", "住所", "address"))
    val industryCol = col(setOf("行业", "所属行业", "industry"))
    val statusCol = col(setOf("经营状态", "企业状态", "状态", "status"))
    val followUpCol = col(setOf("下次回访日期", "回访日期", "下次联系日期", "followup"))
    val tagsCol = col(setOf("标签", "客户标签", "tags"))
    val lastContactCol = col(setOf("最后沟通", "最后联系", "lastcontact", "lastcontactat"))
    val phoneCols = if (headerIndex >= 0) header.indices.filter { isPhoneHeader(header[it]) } else emptyList()

    fun phoneNumbers(raw: String): List<String> {
        val value = raw.trim()
        if (value.isBlank()) return emptyList()
        val result = linkedSetOf<String>()
        // 常见企业导出使用分号；同时兼容中文逗号、顿号、斜线和换行分隔。
        val chunks = value.split(Regex("[;；,，、/|\\n\\r]+"))
        for (chunk in chunks) {
            val s = chunk.trim()
            if (s.isBlank()) continue
            val digitsOnly = s.filter(Char::isDigit)
            if (digitsOnly.length in 7..15 && s.none { it.isLetter() }) {
                result.add(digitsOnly)
                continue
            }
            // 有“电话：”等文字或括号、短横线时，提取其中的号码。
            val beforeCount = result.size
            Regex("(?<!\\d)\\+?\\d[\\d()\\-]{5,}\\d(?!\\d)").findAll(s).forEach { match ->
                val d = match.value.filter(Char::isDigit)
                if (d.length in 7..15) result.add(d)
            }
            // 有些表格用空格隔开多个完整号码。
            if (result.size == beforeCount) {
                s.split(Regex("\\s+")).forEach { token ->
                    val d = token.filter(Char::isDigit)
                    if (d.length in 7..15) result.add(d)
                }
            }
        }
        return result.toList()
    }

    val allSeenPhones = mutableSetOf<String>()
    return clean.mapIndexedNotNull { i, row ->
        if (i == headerIndex) return@mapIndexedNotNull null

        val enterpriseMode = headerIndex >= 0 && cc >= 0 && phoneCols.isNotEmpty() &&
            (nc < 0 || phoneCols.size > 1 || header.any { it == "工商年报电话" || it == "更多联系电话" })
        if (enterpriseMode) {
            val company = row.getOrNull(cc).orEmpty().trim()
            if (company.isBlank()) return@mapIndexedNotNull null
            val numbers = phoneCols.flatMap { phoneNumbers(row.getOrNull(it).orEmpty()) }
                .filter { allSeenPhones.add(it) }
            if (numbers.isEmpty()) return@mapIndexedNotNull null

            val details = mutableListOf<String>()
            row.getOrNull(legalRepCol).orEmpty().trim().takeIf { it.isNotBlank() }?.let { details.add("法定代表人：$it") }
            row.getOrNull(addressCol).orEmpty().trim().takeIf { it.isNotBlank() }?.let { details.add("地址：$it") }
            row.getOrNull(industryCol).orEmpty().trim().takeIf { it.isNotBlank() }?.let { details.add("行业：$it") }
            row.getOrNull(statusCol).orEmpty().trim().takeIf { it.isNotBlank() }?.let { details.add("经营状态：$it") }
            row.getOrNull(mc).orEmpty().trim().takeIf { it.isNotBlank() }?.let { details.add(it) }
            val note = details.joinToString("；")
            val importedStatus = row.getOrNull(statusCol).orEmpty().trim().takeIf { it in callStatuses } ?: "待联系"
            val importedFollowUp = row.getOrNull(followUpCol).orEmpty().trim()
            val importedTags = row.getOrNull(tagsCol).orEmpty().split("|", ";", "；")
                .map(String::trim).filter(String::isNotBlank).distinct()
            val importedLastContact = row.getOrNull(lastContactCol).orEmpty().trim()

            // 每个电话号码生成一条客户记录；同一单位的记录保持相邻，方便连续拨号。
            return@mapIndexedNotNull numbers.map { number ->
                Customer(
                    name = company,
                    phone = number,
                    company = company,
                    note = note,
                    status = importedStatus,
                    followUp = importedFollowUp,
                    tags = importedTags,
                    lastContactAt = importedLastContact
                )
            }
        }

        // 常规姓名/电话/单位格式兼容逻辑。
        val p = if (headerIndex >= 0) {
            header.indexOfFirst { isPhoneHeader(it) }.takeIf { it >= 0 }
        } else null
        val phoneIndex = p ?: row.indexOfFirst { it.trim().matches(Regex("^\\+?[0-9][0-9() \\-]{5,}$")) }
        if (phoneIndex < 0 || row.getOrNull(phoneIndex).isNullOrBlank()) return@mapIndexedNotNull null

        val numbers = phoneNumbers(row[phoneIndex]).filter { allSeenPhones.add(it) }
        if (numbers.isEmpty()) return@mapIndexedNotNull null
        val name = if (nc >= 0) row.getOrNull(nc).orEmpty().trim()
            else row.indices.firstOrNull { it != phoneIndex && row[it].isNotBlank() }?.let { row[it].trim() }.orEmpty()
        val company = if (cc >= 0) row.getOrNull(cc).orEmpty().trim() else row.getOrNull(2).orEmpty().trim()
        val note = if (mc >= 0) row.getOrNull(mc).orEmpty().trim() else row.getOrNull(3).orEmpty().trim()
        val importedStatus = row.getOrNull(statusCol).orEmpty().trim().takeIf { it in callStatuses } ?: "待联系"
        val importedFollowUp = row.getOrNull(followUpCol).orEmpty().trim()
        val importedTags = row.getOrNull(tagsCol).orEmpty().split("|", ";", "；")
            .map(String::trim).filter(String::isNotBlank).distinct()
        val importedLastContact = row.getOrNull(lastContactCol).orEmpty().trim()
        numbers.map { phone ->
            Customer(
                name = name.ifBlank { company.ifBlank { phone } },
                phone = phone,
                company = company,
                note = note,
                status = importedStatus,
                followUp = importedFollowUp,
                tags = importedTags,
                lastContactAt = importedLastContact
            )
        }
    }.flatten()
}
private fun readXlsx(context: Context, uri: Uri): List<List<String>> {
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(context.contentResolver.openInputStream(uri) ?: error("无法打开 Excel 文件")).use { z -> var e = z.nextEntry; while (e != null) { if (!e.isDirectory && (e.name == "xl/sharedStrings.xml" || e.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")))) entries[e.name] = z.readBytes(); e = z.nextEntry } }
    val shared = mutableListOf<String>(); entries["xl/sharedStrings.xml"]?.let { bytes -> val p = XmlPullParserFactory.newInstance().newPullParser(); p.setInput(bytes.inputStream(), "UTF-8"); var inSi = false; val b = StringBuilder(); var ev = p.eventType; while (ev != XmlPullParser.END_DOCUMENT) { when(ev) { XmlPullParser.START_TAG -> when(p.name) { "si" -> { inSi = true; b.clear() }; "t" -> if (inSi) b.append(p.nextText()) }; XmlPullParser.END_TAG -> if (p.name == "si") { shared.add(b.toString()); inSi = false } }; ev = p.eventType; if (ev != XmlPullParser.END_DOCUMENT) ev = p.next() } }
    val sheet = entries.keys.filter { it.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }.sorted().firstOrNull() ?: error("Excel 中没有可读取的工作表")
    val p = XmlPullParserFactory.newInstance().newPullParser(); p.setInput(entries.getValue(sheet).inputStream(), "UTF-8"); val rows = mutableListOf<List<String>>(); var row = mutableMapOf<Int,String>(); var col = -1; var type = ""; var value = ""; var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) { when(ev) {
        XmlPullParser.START_TAG -> when(p.name) { "row" -> row = mutableMapOf(); "c" -> { val ref = p.getAttributeValue(null,"r").orEmpty(); col = ref.takeWhile { it.isLetter() }.fold(0) { a,ch -> a*26 + (ch.uppercaseChar()-'A'+1) }-1; type = p.getAttributeValue(null,"t").orEmpty(); value = "" }; "v", "t" -> value = p.nextText() }
        XmlPullParser.END_TAG -> when(p.name) { "c" -> if (col >= 0) row[col] = if (type == "s") shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else value; "row" -> if (row.isNotEmpty()) rows.add((0..(row.keys.maxOrNull() ?: -1)).map { row[it].orEmpty() }) }
    }; ev = p.eventType; if (ev != XmlPullParser.END_DOCUMENT) ev = p.next() }
    return rows
}
private fun csvRow(line: String): List<String> { val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0; while (i < line.length) { val c = line[i]; when { c == '"' && quoted && i+1 < line.length && line[i+1] == '"' -> { cell.append('"'); i++ }; c == '"' -> quoted = !quoted; c == ',' && !quoted -> { out.add(cell.toString()); cell.clear() }; else -> cell.append(c) }; i++ }; out.add(cell.toString()); return out }
