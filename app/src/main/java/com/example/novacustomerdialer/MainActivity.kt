package com.example.novacustomerdialer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

private val statuses = listOf("待联系", "已接通", "未接听", "稍后回访", "拒绝", "已成交")

data class Customer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val company: String = "",
    val note: String = "",
    val status: String = "待联系",
    val followUp: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF315EFB),
                secondary = androidx.compose.ui.graphics.Color(0xFF00897B)
            )) { CustomerApp() }
        }
    }
}

@Composable
private fun CustomerApp() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var customers by remember { mutableStateOf(readCustomers(context)) }
    var currentId by remember { mutableStateOf(customers.firstOrNull()?.id) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var message by remember { mutableStateOf("客户数据自动保存在本机。") }
    var editTarget by remember { mutableStateOf<Customer?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showOutcome by remember { mutableStateOf(false) }
    var pendingCall by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf("已接通") }
    var outcomeNote by remember { mutableStateOf("") }
    var outcomeDate by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var inCustomerModule by remember { mutableStateOf(false) }

    fun update(list: List<Customer>) {
        customers = list
        writeCustomers(context, list)
        if (list.none { it.id == currentId }) currentId = list.firstOrNull()?.id
        selected = selected.intersect(list.map { it.id }.toSet())
    }
    LaunchedEffect(customers) { writeCustomers(context, customers) }

    DisposableEffect(lifecycle, pendingCall) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingCall != null) {
                val c = customers.firstOrNull { it.id == pendingCall }
                if (c != null) {
                    outcome = c.status.takeIf { it != "待联系" } ?: "已接通"
                    outcomeNote = ""
                    outcomeDate = c.followUp
                    showOutcome = true
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val name = (uri.lastPathSegment ?: "").lowercase(Locale.ROOT)
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
            val rows: List<List<String>> = if (name.endsWith(".xlsx") || mime.contains("spreadsheetml")) {
                readXlsx(context, uri)
            } else {
                val stream = context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readLines().map { csvRow(it.removePrefix("\\uFEFF")).map(String::trim) }
                }
            }
            val imported = rowsToCustomers(rows)
            val existing = customers.map { digits(it.phone) }.toMutableSet()
            val fresh = imported.filter { digits(it.phone) !in existing && existing.add(digits(it.phone)) }
            update(customers + fresh)
            message = "识别 ${imported.size} 条有效客户，新增 ${fresh.size} 条；重复号码已跳过。"
        } catch (e: Exception) { message = "导入失败：${e.message ?: "文件格式不支持"}" }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) try {
            val csv = buildString {
                append("姓名,手机号,公司,状态,备注,下次回访日期\n")
                customers.forEach { c ->
                    append(listOf(c.name,c.phone,c.company,c.status,c.note,c.followUp).joinToString(",") { escapeCsv(it) })
                    append('\n')
                }
            }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
            message = "已导出 ${customers.size} 位客户。"
        } catch (e: Exception) { message = "导出失败：${e.message}" }
    }

    val current = customers.firstOrNull { it.id == currentId }
    val visible = customers.filter {
        (search.isBlank() || it.name.contains(search, true) || it.phone.contains(search, true) || it.company.contains(search, true)) &&
        (filter == "全部" || it.status == filter)
    }
    fun saveOutcome(next: Boolean) {
        val c = current ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val newNote = if (outcomeNote.isBlank()) c.note else listOf(c.note, "$stamp：${outcomeNote.trim()}").filter { it.isNotBlank() }.joinToString("\n")
        val idx = customers.indexOfFirst { it.id == c.id }
        update(customers.map { if (it.id == c.id) it.copy(status = outcome, note = newNote, followUp = outcomeDate.trim()) else it })
        currentId = if (next) customers.getOrNull(idx + 1)?.id ?: c.id else c.id
        showOutcome = false
        pendingCall = null
        message = if (next && idx < customers.lastIndex) "记录已保存，已切换到下一位。" else "沟通记录已保存。"
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (inCustomerModule) TextButton(onClick = { inCustomerModule = false }) { Text("‹ 首页") }
                    Column(Modifier.weight(1f)) {
                        Text(if (inCustomerModule) "客户管理系统" else "NovaCustomerDialer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (inCustomerModule) "客户档案 · 回访记录 · 快速拨号" else "客户拨号与跟进工作台", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        floatingActionButton = {
            if (inCustomerModule) FloatingActionButton(onClick = { editTarget = null; showEditor = true }) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        }
    ) { padding ->
        if (!inCustomerModule) {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("客户工作台", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("统一管理客户资料、沟通备注、回访状态，并从客户档案快速发起拨号。")
                        Button(onClick = { inCustomerModule = true }, modifier = Modifier.fillMaxWidth()) { Text("进入客户管理系统  ›") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("客户总数", customers.size.toString(), Modifier.weight(1f))
                    Stat("待联系", customers.count { it.status == "待联系" }.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("已跟进", customers.count { it.status != "待联系" }.toString(), Modifier.weight(1f))
                    Stat("已成交", customers.count { it.status == "已成交" }.toString(), Modifier.weight(1f))
                }
                Text("常用功能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { inCustomerModule = true }, modifier = Modifier.fillMaxWidth()) { Text("客户资料 / Excel 导入 / 数据导出") }
            }
        } else {
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("客户", customers.size.toString(), Modifier.weight(1f))
                Stat("已跟进", customers.count { it.status != "待联系" }.toString(), Modifier.weight(1f))
                Stat("待联系", customers.count { it.status == "待联系" }.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { importFile.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Text("导入 CSV / Excel") }
                OutlinedButton(onClick = { exportCsv.launch("Nova客户名单.csv") }, modifier = Modifier.weight(1f)) { Text("导出 CSV") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { selected = visible.map { it.id }.toSet() }, modifier = Modifier.weight(1f)) { Text("全选") }
                OutlinedButton(onClick = { selected = emptySet() }, modifier = Modifier.weight(1f)) { Text("取消选择") }
                Button(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("删除 ${selected.size}") }
            }
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索姓名、手机号、公司") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("筛选：")
                Box {
                    OutlinedButton(onClick = { filterMenu = true }) { Text(filter) }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        (listOf("全部") + statuses).forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { filter = s; filterMenu = false }) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("显示 ${visible.size} 位", style = MaterialTheme.typography.bodySmall)
            }
            if (current != null) {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text("当前客户", style = MaterialTheme.typography.labelMedium)
                                Text(current.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(current.phone, style = MaterialTheme.typography.titleMedium)
                                if (current.company.isNotBlank()) Text(current.company)
                            }
                            AssistChip(onClick = { editTarget = current; showEditor = true }, label = { Text("编辑") })
                        }
                        Text("状态：${current.status}${if (current.followUp.isNotBlank()) " · 回访 ${current.followUp}" else ""}", fontWeight = FontWeight.SemiBold)
                        if (current.note.isNotBlank()) Text("备注：${current.note}", maxLines = 3)
                        Button(onClick = {
                            pendingCall = current.id
                            try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(current.phone)}"))) }
                            catch (_: Exception) { pendingCall = null; message = "未找到电话应用。" }
                        }, modifier = Modifier.fillMaxWidth()) { Text("拨打当前客户") }
                        Text("通话后返回本应用，将弹出快速记录窗口。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(enabled = customers.indexOfFirst { it.id == current.id } > 0, onClick = {
                                val i = customers.indexOfFirst { it.id == current.id }; currentId = customers[i - 1].id
                            }, modifier = Modifier.weight(1f)) { Text("上一位") }
                            Button(onClick = { outcome = current.status.takeIf { it != "待联系" } ?: "已接通"; outcomeNote = ""; outcomeDate = current.followUp; showOutcome = true }, modifier = Modifier.weight(1f)) { Text("记录沟通") }
                            Button(onClick = {
                                val i = customers.indexOfFirst { it.id == current.id }
                                if (i < customers.lastIndex) currentId = customers[i + 1].id else message = "已到名单末尾。"
                            }, modifier = Modifier.weight(1f)) { Text("下一位") }
                        }
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (customers.isEmpty()) "还没有客户" else "没有匹配客户", style = MaterialTheme.typography.titleMedium)
                        Text(if (customers.isEmpty()) "点击右下角 + 新增客户，或批量导入 CSV。" else "修改搜索条件或筛选状态。")
                    }
                }
            }
            Text("客户列表 · 勾选后可批量删除", fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 84.dp)) {
                items(visible, key = { it.id }) { c ->
                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = if (c.id == currentId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.id in selected, onCheckedChange = { yes -> selected = if (yes) selected + c.id else selected - c.id })
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = FontWeight.SemiBold)
                                Text(c.phone, style = MaterialTheme.typography.bodySmall)
                                Text(c.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { currentId = c.id }) { Text("选择") }
                            TextButton(onClick = { editTarget = c; showEditor = true }) { Text("编辑") }
                        }
                    }
                }
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        }
    }

    if (showEditor) EditDialog(editTarget, onDismiss = { showEditor = false }, onSave = { c ->
        val exists = customers.any { it.id == c.id }
        update(if (exists) customers.map { if (it.id == c.id) c else it } else listOf(c) + customers)
        currentId = c.id
        showEditor = false
        message = if (exists) "客户资料已更新。" else "已新增客户。"
    }, onDelete = { c -> update(customers.filterNot { it.id == c.id }); showEditor = false; message = "客户已删除。" })

    if (showOutcome && current != null) {
        AlertDialog(
            onDismissRequest = { showOutcome = false; pendingCall = null },
            title = { Text("沟通记录 · ${current.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    statuses.filter { it != "待联系" }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { s -> FilterChip(selected = outcome == s, onClick = { outcome = s }, label = { Text(s) }, modifier = Modifier.weight(1f)) }
                        }
                    }
                    OutlinedTextField(outcomeNote, { outcomeNote = it }, label = { Text("本次沟通备注") }, placeholder = { Text("例如：有意向，周五再联系") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(outcomeDate, { outcomeDate = it }, label = { Text("下次回访日期（可选）") }, placeholder = { Text("例如：2026-11-01") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Column {
                    Button(onClick = { saveOutcome(false) }, modifier = Modifier.fillMaxWidth()) { Text("保存记录") }
                    Button(onClick = { saveOutcome(true) }, modifier = Modifier.fillMaxWidth()) { Text("保存并切换下一位") }
                }
            },
            dismissButton = { TextButton(onClick = { showOutcome = false; pendingCall = null }) { Text("取消") } }
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("批量删除") },
        text = { Text("确定删除已选择的 ${selected.size} 位客户？此操作不可撤销。") },
        confirmButton = { Button(onClick = { val n = selected.size; update(customers.filterNot { it.id in selected }); selected = emptySet(); confirmDelete = false; message = "已删除 $n 位客户。" }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EditDialog(initial: Customer?, onDismiss: () -> Unit, onSave: (Customer) -> Unit, onDelete: (Customer) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember(initial?.id) { mutableStateOf(initial?.phone.orEmpty()) }
    var company by remember(initial?.id) { mutableStateOf(initial?.company.orEmpty()) }
    var note by remember(initial?.id) { mutableStateOf(initial?.note.orEmpty()) }
    var status by remember(initial?.id) { mutableStateOf(initial?.status ?: "待联系") }
    var followUp by remember(initial?.id) { mutableStateOf(initial?.followUp.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增客户" else "编辑客户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("姓名 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("手机号 *") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(company, { company = it }, label = { Text("公司 / 来源") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text("状态：$status") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        statuses.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { status = s; menu = false }) }
                    }
                }
                OutlinedTextField(followUp, { followUp = it }, label = { Text("下次回访日期") }, placeholder = { Text("例如：2026-11-01") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("备注 / 沟通历史") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = {
            if (name.isBlank() || phone.isBlank()) error = "姓名和手机号不能为空。"
            else onSave((initial ?: Customer(name = name.trim(), phone = phone.trim())).copy(name = name.trim(), phone = phone.trim(), company = company.trim(), note = note.trim(), status = status, followUp = followUp.trim()))
        }) { Text("保存") } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

private fun readCustomers(context: Context): List<Customer> = try {
    val a = JSONArray(context.getSharedPreferences("nova_customers", Context.MODE_PRIVATE).getString("data", "[]") ?: "[]")
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Customer(o.optString("id", UUID.randomUUID().toString()), o.optString("name"), o.optString("phone"), o.optString("company"), o.optString("note"), o.optString("status", "待联系"), o.optString("followUp"))
    }.filter { it.name.isNotBlank() && it.phone.isNotBlank() }
} catch (_: Exception) { emptyList() }

private fun writeCustomers(context: Context, list: List<Customer>) {
    val a = JSONArray()
    list.forEach { c -> a.put(JSONObject().apply {
        put("id", c.id); put("name", c.name); put("phone", c.phone); put("company", c.company)
        put("note", c.note); put("status", c.status); put("followUp", c.followUp)
    }) }
    context.getSharedPreferences("nova_customers", Context.MODE_PRIVATE).edit().putString("data", a.toString()).apply()
}
private fun digits(s: String) = s.filter { it.isDigit() }
private fun escapeCsv(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""

private fun rowsToCustomers(rows: List<List<String>>): List<Customer> {
    val clean = rows.filter { row -> row.any { it.isNotBlank() } }
    if (clean.isEmpty()) return emptyList()
    fun norm(v: String) = v.trim().lowercase(Locale.ROOT).replace(" ", "").replace("_", "")
    val headerIndex = clean.indexOfFirst { row ->
        row.any { norm(it) in setOf("姓名", "客户姓名", "name", "联系人", "联系人姓名") } &&
        row.any { norm(it) in setOf("电话", "手机号", "手机号码", "联系电话", "电话/手机", "phone", "mobile", "tel") }
    }.let { if (it < 0) -1 else it }
    val header = if (headerIndex >= 0) clean[headerIndex].map(::norm) else emptyList()
    fun findColumn(keys: Set<String>): Int = header.indexOfFirst { it in keys }
    val nameCol = findColumn(setOf("姓名", "客户姓名", "name", "联系人", "联系人姓名"))
    val phoneCol = findColumn(setOf("电话", "手机号", "手机号码", "联系电话", "电话/手机", "phone", "mobile", "tel"))
    val companyCol = findColumn(setOf("单位", "单位名称", "公司", "公司名称", "企业名称", "company", "organization", "机构"))
    val noteCol = findColumn(setOf("备注", "备注情况", "沟通备注", "跟进情况", "客户情况", "说明", "note", "remark", "comments"))
    return clean.mapIndexedNotNull { idx, row ->
        if (idx == headerIndex) return@mapIndexedNotNull null
        val phoneIdx = if (phoneCol >= 0) phoneCol else row.indexOfFirst { it.trim().matches(Regex("^\\+?[0-9][0-9() \\-]{5,}$")) }
        if (phoneIdx < 0 || row.getOrNull(phoneIdx).isNullOrBlank()) return@mapIndexedNotNull null
        val phone = row[phoneIdx].trim()
        val name = if (nameCol >= 0) row.getOrNull(nameCol).orEmpty().trim() else
            row.indices.firstOrNull { it != phoneIdx && row[it].isNotBlank() }?.let { row[it].trim() }.orEmpty()
        val company = if (companyCol >= 0) row.getOrNull(companyCol).orEmpty().trim() else row.getOrNull(2).orEmpty().trim()
        val note = if (noteCol >= 0) row.getOrNull(noteCol).orEmpty().trim() else row.getOrNull(3).orEmpty().trim()
        Customer(name = name.ifBlank { phone }, phone = phone, company = company, note = note)
    }
}

private fun readXlsx(context: Context, uri: Uri): List<List<String>> {
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(context.contentResolver.openInputStream(uri) ?: error("无法打开 Excel 文件")).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && (entry.name == "xl/sharedStrings.xml" || entry.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")))) {
                entries[entry.name] = zip.readBytes()
            }
            entry = zip.nextEntry
        }
    }
    val strings = mutableListOf<String>()
    entries["xl/sharedStrings.xml"]?.let { bytes ->
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        var inSi = false
        val value = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inSi = true; value.clear() }
                    "t" -> if (inSi) value.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") { strings.add(value.toString()); inSi = false }
            }
            event = parser.eventType
            if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
        }
    }
    val sheetName = entries.keys.filter { it.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }.sorted().firstOrNull()
        ?: error("Excel 中没有可读取的工作表")
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(entries.getValue(sheetName).inputStream(), "UTF-8")
    val rows = mutableListOf<List<String>>()
    var row = mutableMapOf<Int, String>()
    var col = 0
    var type = ""
    var cellValue = ""
    var inValue = false
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> row = mutableMapOf()
                "c" -> {
                    val ref = parser.getAttributeValue(null, "r").orEmpty()
                    col = ref.takeWhile { it.isLetter() }.fold(0) { acc, ch -> acc * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
                    type = parser.getAttributeValue(null, "t").orEmpty()
                    cellValue = ""
                }
                "v", "t" -> { inValue = true; cellValue = parser.nextText(); inValue = false }
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "c" -> {
                    val value = if (type == "s") strings.getOrNull(cellValue.toIntOrNull() ?: -1).orEmpty() else cellValue
                    if (col >= 0) row[col] = value
                }
                "row" -> if (row.isNotEmpty()) {
                    val max = row.keys.maxOrNull() ?: -1
                    rows.add((0..max).map { row[it].orEmpty() })
                }
            }
        }
        event = parser.eventType
        if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
    }
    return rows
}

private fun csvRow(line: String): List<String> {
    val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out.add(cell.toString()); cell.clear() }
            else -> cell.append(c)
        }
        i++
    }
    out.add(cell.toString())
    return out
}
