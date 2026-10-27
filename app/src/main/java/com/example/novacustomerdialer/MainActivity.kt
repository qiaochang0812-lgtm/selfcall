package com.example.novacustomerdialer

import android.content.Context
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
private val palette = listOf(0xFF2563EB, 0xFF0F9D78, 0xFFF59E0B, 0xFFDB2777, 0xFF7C3AED, 0xFF64748B, 0xFF0891B2, 0xFFDC2626)

data class Customer(
    val id: String = UUID.randomUUID().toString(), val name: String, val phone: String,
    val company: String = "", val note: String = "", val status: String = "待联系",
    val followUp: String = "", val tags: List<String> = emptyList(), val lastContactAt: String = ""
)
data class CustomerTag(val name: String, val color: Long = palette[0])
private enum class Page { HOME, CUSTOMERS, DIALER, SETTINGS, STATS, TAGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF245BDB), secondary = Color(0xFF0F9D78), background = Color(0xFFF5F7FB), surface = Color.White)) { NovaApp() } }
    }
}

@Composable
private fun NovaApp() {
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
    var outcome by remember { mutableStateOf("已接通") }
    var outcomeNote by remember { mutableStateOf("") }
    var outcomeDate by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("客户资料仅保存在本机。") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<CustomerTag?>(null) }
    var showStatusMenu by remember { mutableStateOf(false) }
    var statsPeriod by remember { mutableStateOf("当天统计") }

    fun saveCustomers(value: List<Customer>) { customers = value; persistCustomers(context, value); if (value.none { it.id == currentId }) currentId = value.firstOrNull()?.id; selected = selected.intersect(value.map { it.id }.toSet()) }
    fun saveTags(value: List<CustomerTag>) { tags = value; persistTags(context, value) }

    DisposableEffect(lifecycle, pendingCall) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingCall != null) {
                val c = customers.firstOrNull { it.id == pendingCall }
                if (c != null) { outcome = c.status.takeIf { it != "待联系" } ?: "已接通"; outcomeNote = ""; outcomeDate = c.followUp; showOutcome = true }
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
            saveCustomers(customers + fresh); message = "识别 ${parsed.size} 条，新增 ${fresh.size} 条，重复号码已跳过。"
        } catch (e: Exception) { message = "导入失败：${e.message ?: "请检查文件格式"}" }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) try {
            val exportList = if (selected.isNotEmpty()) customers.filter { it.id in selected } else customers
            val csv = buildString {
                append("姓名,手机号,单位名称,状态,备注情况,下次回访日期,标签\r\n")
                exportList.forEach { c -> append(listOf(c.name,c.phone,c.company,c.status,c.note,c.followUp,c.tags.joinToString("|" )).joinToString(",") { csvEscape(it) }); append("\r\n") }
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
    fun saveOutcome(goNext: Boolean) {
        val c = current ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val appended = if (outcomeNote.isBlank()) c.note else listOf(c.note, "$stamp：${outcomeNote.trim()}").filter { it.isNotBlank() }.joinToString("\n")
        saveCustomers(customers.map { if (it.id == c.id) it.copy(status = outcome, note = appended, followUp = outcomeDate.trim(), lastContactAt = stamp) else it })
        val idx = customers.indexOfFirst { it.id == c.id }
        if (goNext) { val next = customers.drop(idx + 1).firstOrNull { it.id != c.id }; if (next != null) currentId = next.id else message = "记录已保存，当前已是最后一位。" }
        showOutcome = false; pendingCall = null; message = if (goNext && idx < customers.lastIndex) "记录已保存，已切换下一位。" else "沟通记录已保存。"
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp, color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page != Page.HOME) TextButton(onClick = { page = Page.HOME }) { Text("‹") }
                    Column(Modifier.weight(1f)) {
                        Text(when(page) { Page.HOME -> "沟通无限"; Page.CUSTOMERS -> "客户管理"; Page.DIALER -> "连续拨号"; Page.SETTINGS -> "设置"; Page.STATS -> "统计报表"; Page.TAGS -> "标签管理" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(when(page) { Page.HOME -> "高效沟通 · 客户无限"; Page.CUSTOMERS -> "搜索 · 分类 · 批量管理"; Page.DIALER -> "拨打后记录结果，继续下一位"; Page.SETTINGS -> "标签、统计与数据管理"; Page.STATS -> "当天与当月跟进情况"; Page.TAGS -> "按业务需要定义客户分类" }, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                    }
                }
            }
        },
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
                Modifier.padding(insets), customers,
                onDial = { dialerQuickFilter = "全部名单"; dialerStatusFilter = "全部状态"; page = Page.DIALER },
                onCustomers = { page = Page.CUSTOMERS },
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
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜索姓名、电话、单位") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                Text("客户标签（可多选组合筛选）", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = selectedTags.isEmpty(), onClick = { selectedTags = emptySet() }, label = { Text("全部") }) }
                    items(tags, key = { it.name }) { t -> FilterChip(selected = t.name in selectedTags, onClick = { selectedTags = if (t.name in selectedTags) selectedTags - t.name else selectedTags + t.name }, label = { Text(t.name) }, leadingIcon = { Box(Modifier.size(9.dp).background(Color(t.color), RoundedCornerShape(50))) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box { OutlinedButton(onClick = { showStatusMenu = true }) { Text(statusFilter) }; DropdownMenu(expanded = showStatusMenu, onDismissRequest = { showStatusMenu = false }) { (listOf("全部状态") + callStatuses).forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { statusFilter = s; showStatusMenu = false }) } } }
                    Text("  ${visible.size} 位客户", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B), modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = visible.map { it.id }.toSet() }) { Text("全选") }
                    TextButton(onClick = { selected = emptySet() }) { Text("清除") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Text("导入") }
                    OutlinedButton(onClick = { exportLauncher.launch("沟通无限-客户名单.csv") }, modifier = Modifier.weight(1f)) { Text("导出") }
                }
                if (selected.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 位", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { showDeleteConfirm = true }) { Text("批量删除") }
                }
                if (visible.isEmpty()) EmptyCard("没有匹配客户", "点击右下角 + 新增，或导入 Excel/CSV 文件。")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                    items(visible, key = { it.id }) { c -> CustomerCard(c, tags, c.id in selected, onSelect = { yes -> selected = if (yes) selected + c.id else selected - c.id }, onOpen = { currentId = c.id; page = Page.DIALER }, onEdit = { editing = c; showEditor = true }) }
                }
            }
            Page.DIALER -> Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("拨号名单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { showStatusMenu = true }) { Text(dialerStatusFilter) }
                        DropdownMenu(expanded = showStatusMenu, onDismissRequest = { showStatusMenu = false }) {
                            (listOf("全部状态") + callStatuses).forEach { status -> DropdownMenuItem(text = { Text(status) }, onClick = { dialerStatusFilter = status; dialerQuickFilter = "全部名单"; showStatusMenu = false; val first = customers.firstOrNull { status == "全部状态" || it.status == status }; if (first != null && customers.filter { status == "全部状态" || it.status == status }.none { it.id == currentId }) currentId = first.id }) }
                        }
                    }
                }
                Text("${if (dialerQuickFilter == "全部名单") "按沟通状态筛选拨号名单" else dialerQuickFilter + "名单"} · ${dialerCustomers.size} 位客户", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                if (current != null && dialerCustomers.any { it.id == current.id }) {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF0FF))) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("当前客户", color = Color(0xFF245BDB), style = MaterialTheme.typography.labelLarge)
                            Text(current.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(current.phone, style = MaterialTheme.typography.titleMedium)
                            if (current.company.isNotBlank()) Text(current.company, color = Color(0xFF475569))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                SuggestionChip(onClick = {}, label = { Text("状态：${current.status}") })
                                Text("最后沟通：${lastCommunication(current)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
                            }
                            Button(onClick = { pendingCall = current.id; try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(current.phone)}"))) } catch (_: Exception) { pendingCall = null; message = "无法打开系统拨号界面。" } }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) { Text("拨打当前客户") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { val i = dialerCustomers.indexOfFirst { it.id == current.id }; if (i > 0) currentId = dialerCustomers[i - 1].id }, enabled = dialerCustomers.indexOfFirst { it.id == current.id } > 0, modifier = Modifier.weight(1f)) { Text("上一位") }
                                Button(onClick = { outcome = current.status.takeIf { it != "待联系" } ?: "已接通"; outcomeNote = ""; outcomeDate = current.followUp; showOutcome = true }, modifier = Modifier.weight(1.2f)) { Text("记录结果") }
                                OutlinedButton(onClick = { val i = dialerCustomers.indexOfFirst { it.id == current.id }; dialerCustomers.getOrNull(i + 1)?.let { currentId = it.id } ?: run { message = "当前筛选名单已到末尾。" } }, enabled = dialerCustomers.indexOfFirst { it.id == current.id } >= 0 && dialerCustomers.indexOfFirst { it.id == current.id } < dialerCustomers.lastIndex, modifier = Modifier.weight(1f)) { Text("下一位") }
                            }
                        }
                    }
                } else EmptyCard("没有符合状态的客户", "请选择其他沟通状态，或先到客户管理中添加客户。")
                Text("选择名单中的客户", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(dialerCustomers, key = { it.id }) { c ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { currentId = c.id }, shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = if (c.id == currentId) Color(0xFFEAF0FF) else Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(c.name, fontWeight = FontWeight.SemiBold)
                                    Text("${c.phone}${if (c.company.isNotBlank()) " · ${c.company}" else ""}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569), maxLines = 1)
                                    Text("最后沟通：${lastCommunication(c)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                                }
                                SuggestionChip(onClick = { currentId = c.id }, label = { Text(c.status) })
                            }
                        }
                    }
                }
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Page.SETTINGS -> Column(Modifier.fillMaxSize().padding(insets).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF0FF))) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("沟通无限", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF245BDB))
                        Text("客户跟进与高效拨号工具", color = Color(0xFF475569))
                    }
                }
                HomeAction("统计报表", "查看当天统计与当月统计", "▥", { page = Page.STATS })
                HomeAction("标签管理", "创建自定义标签并设置颜色", "◆", { page = Page.TAGS })
                HomeAction("导入客户", "导入 Excel (.xlsx) 或 CSV", "↓", { importLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) })
                HomeAction("导出客户", "将全部客户导出为 CSV（Excel 可打开）", "↑", { exportLauncher.launch("沟通无限-客户名单.csv") })
                Text("客户数据保存在本机。建议定期导出备份。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
            Page.STATS -> {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val month = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                val periodPrefix = if (statsPeriod == "当天统计") today else month
                val touched = customers.filter { c -> c.note.lines().any { line -> line.trim().startsWith(periodPrefix) } }
                val statusCount = callStatuses.filter { it != "待联系" }.associateWith { status -> touched.count { it.status == status } }
                Column(Modifier.fillMaxSize().padding(insets).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = statsPeriod == "当天统计", onClick = { statsPeriod = "当天统计" }, label = { Text("当天统计") }, modifier = Modifier.weight(1f))
                        FilterChip(selected = statsPeriod == "当月统计", onClick = { statsPeriod = "当月统计" }, label = { Text("当月统计") }, modifier = Modifier.weight(1f))
                    }
                    Text(if (statsPeriod == "当天统计") today else month, color = Color(0xFF64748B))
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
                    Text("统计说明：根据客户备注中自动追加的日期时间记录筛选；状态按客户当前状态统计，不等同于完整历史通话次数。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
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

    if (showOutcome && current != null) AlertDialog(onDismissRequest = { showOutcome = false; pendingCall = null }, title = { Text("快速记录 · ${current.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            callStatuses.filter { it != "待联系" }.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { row.forEach { s -> FilterChip(selected = outcome == s, onClick = { outcome = s }, label = { Text(s) }, modifier = Modifier.weight(1f)) } } }
            OutlinedTextField(outcomeNote, { outcomeNote = it }, label = { Text("本次沟通情况") }, placeholder = { Text("例如：有意向，下周报价") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(outcomeDate, { outcomeDate = it }, label = { Text("下次回访日期（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("可在客户管理中继续维护标签。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Column { Button(onClick = { saveOutcome(false) }, Modifier.fillMaxWidth()) { Text("保存记录") }; Button(onClick = { saveOutcome(true) }, Modifier.fillMaxWidth()) { Text("保存并拨下一位") } } }, dismissButton = { TextButton(onClick = { showOutcome = false; pendingCall = null }) { Text("稍后记录") } })

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

@Composable
private fun HomeScreen(
    modifier: Modifier,
    customers: List<Customer>,
    onDial: () -> Unit,
    onCustomers: () -> Unit,
    onDueFollowUps: () -> Unit,
    onLaterFollowUps: () -> Unit
) {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    val dueFollowUps = customers.count { c -> c.followUp.trim().take(10).let { it.isNotBlank() && it <= today } }
    val laterFollowUps = customers.count { it.status == "稍后回访" }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF245BDB))) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(44.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Column {
                            Text("沟通无限", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("高效跟进，让每次沟通都有价值", color = Color(0xFFE0E9FF), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("今日待办", color = Color(0xFFE0E9FF), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f).background(Color.White.copy(alpha = 0.13f), RoundedCornerShape(16.dp)).clickable(onClick = onDueFollowUps).padding(14.dp)) {
                            Text("到期回访", color = Color(0xFFE0E9FF), style = MaterialTheme.typography.labelLarge)
                            Text("$dueFollowUps", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text("位客户", color = Color(0xFFE0E9FF), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(Modifier.weight(1f).background(Color.White.copy(alpha = 0.13f), RoundedCornerShape(16.dp)).clickable(onClick = onLaterFollowUps).padding(14.dp)) {
                            Text("稍后回访", color = Color(0xFFE0E9FF), style = MaterialTheme.typography.labelLarge)
                            Text("$laterFollowUps", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text("位客户", color = Color(0xFFE0E9FF), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDial, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.Call, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("开始拨号")
                }
                OutlinedButton(onClick = onCustomers, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.People, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("客户管理")
                }
            }
        }
        item {
            Text("只显示待办数量，不展示具体客户信息。进入拨号页面后，可按沟通状态筛选名单。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
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
@Composable private fun CustomerCard(c: Customer, tags: List<CustomerTag>, selected: Boolean, onSelect: (Boolean) -> Unit, onOpen: () -> Unit, onEdit: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEAF0FF) else Color.White)) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = onSelect)
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) { Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(c.phone, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF245BDB)); if (c.company.isNotBlank()) Text(c.company, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B)) }
            AssistChip(onClick = onOpen, label = { Text(c.status) })
        }
        if (c.tags.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { c.tags.forEach { name -> val tag = tags.firstOrNull { it.name == name }; AssistChip(onClick = {}, label = { Text(name) }, leadingIcon = { Box(Modifier.size(8.dp).background(Color(tag?.color ?: palette[0]), RoundedCornerShape(50))) }) } }
        if (c.note.isNotBlank()) Text("备注：${c.note.takeLast(90)}", style = MaterialTheme.typography.bodySmall, maxLines = 2, color = Color(0xFF475569))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onOpen) { Text("拨号 / 详情") }; TextButton(onClick = onEdit) { Text("编辑") } }
    } }
}
@Composable private fun EmptyCard(title: String, subtitle: String) { Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = Color(0xFF64748B)) } } }

@Composable private fun CustomerEditor(initial: Customer?, tags: List<CustomerTag>, onDismiss: () -> Unit, onSave: (Customer) -> Unit, onDelete: (Customer) -> Unit, onCreateTag: (String) -> Unit) {
    val context = LocalContext.current
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }; var phone by remember(initial?.id) { mutableStateOf(initial?.phone.orEmpty()) }; var company by remember(initial?.id) { mutableStateOf(initial?.company.orEmpty()) }; var note by remember(initial?.id) { mutableStateOf(initial?.note.orEmpty()) }; var status by remember(initial?.id) { mutableStateOf(initial?.status ?: "待联系") }; var followUp by remember(initial?.id) { mutableStateOf(initial?.followUp.orEmpty()) }; var chosen by remember(initial?.id) { mutableStateOf(initial?.tags?.toSet() ?: emptySet()) }; var error by remember { mutableStateOf("") }; var statusMenu by remember { mutableStateOf(false) }; var newTag by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "新增客户" else "编辑客户") }, text = { Column(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
        tags.forEach { t -> FilterChip(selected = t.name in chosen, onClick = { chosen = if (t.name in chosen) chosen - t.name else chosen + t.name }, label = { Text(t.name) }, leadingIcon = { Box(Modifier.size(9.dp).background(Color(t.color), RoundedCornerShape(50))) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(newTag, { newTag = it }, modifier = Modifier.weight(1f), label = { Text("新建标签") }, singleLine = true); TextButton(onClick = { if (newTag.isNotBlank()) { onCreateTag(newTag.trim()); chosen = chosen + newTag.trim(); newTag = "" } }) { Text("添加") } }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = { if (name.isBlank() || phone.isBlank()) error = "姓名和电话不能为空。" else onSave((initial ?: Customer(name = name.trim(), phone = phone.trim())).copy(name = name.trim(), phone = phone.trim(), company = company.trim(), note = note.trim(), status = status, followUp = followUp.trim(), tags = chosen.toList())) }) { Text("保存") } }, dismissButton = { Row { if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("删除") }; TextButton(onClick = onDismiss) { Text("取消") } } })
}

@Composable private fun TagEditor(initial: CustomerTag?, onDismiss: () -> Unit, onSave: (CustomerTag) -> Unit) {
    var name by remember(initial?.name) { mutableStateOf(initial?.name.orEmpty()) }; var color by remember(initial?.name) { mutableLongStateOf(initial?.color ?: palette[0]) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "新建标签" else "编辑标签") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("标签名称") }, singleLine = true)
        Text("选择标签颜色")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { palette.forEach { p -> Box(Modifier.size(30.dp).background(Color(p), RoundedCornerShape(50)).clickable { color = p }, contentAlignment = Alignment.Center) { if (color == p) Text("✓", color = Color.White) } } }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = { if (name.isBlank()) error = "请输入标签名称。" else onSave(CustomerTag(name.trim(), color)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
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

            // 每个电话号码生成一条客户记录；同一单位的记录保持相邻，方便连续拨号。
            return@mapIndexedNotNull numbers.map { number ->
                Customer(
                    name = company,
                    phone = number,
                    company = company,
                    note = note
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
        numbers.map { phone -> Customer(name = name.ifBlank { company.ifBlank { phone } }, phone = phone, company = company, note = note) }
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
