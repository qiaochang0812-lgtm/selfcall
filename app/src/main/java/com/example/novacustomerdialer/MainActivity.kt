package com.example.novacustomerdialer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader

data class Customer(val name: String, val phone: String, val note: String = "", val status: String = "待联系")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CustomerDialerApp() } }
    }
}

@Composable
fun CustomerDialerApp() {
    val context = LocalContext.current
    var customers by remember { mutableStateOf(listOf<Customer>()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("请导入 CSV 客户名单。") }
    var showHelp by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取文件")
                val parsed = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                    lines.mapNotNull { line ->
                        val row = parseCsvLine(line)
                        if (row.isEmpty()) null else {
                            val first = row.firstOrNull()?.trim().orEmpty()
                            val phoneIndex = row.indexOfFirst { it.trim().matches(Regex("^\\+?[0-9][0-9() -]{5,}$")) }
                            // Accept either name,phone or phone,name; skip common header rows.
                            if (first.equals("姓名", true) || first.equals("name", true) ||
                                first.equals("phone", true) || first.equals("手机号", true)) null
                            else if (phoneIndex >= 0) {
                                val phone = row[phoneIndex].trim()
                                val name = row.indices.firstOrNull { it != phoneIndex && row[it].isNotBlank() }
                                    ?.let { row[it].trim() } ?: phone
                                val note = row.getOrNull(phoneIndex + 1).orEmpty().trim()
                                Customer(name, phone, note)
                            } else null
                        }
                    }.toList()
                }
                customers = parsed
                currentIndex = 0
                message = if (parsed.isEmpty()) "没有识别到号码。请使用“姓名,手机号”格式，每行一位客户。"
                          else "已导入 ${parsed.size} 位客户。"
            } catch (e: Exception) {
                message = "导入失败：${e.message ?: "文件格式不正确"}"
            }
        }
    }

    val current = customers.getOrNull(currentIndex)
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Nova 客户拨号助手", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("批量客户回访 · 手动确认拨号 · 数据仅在本机处理")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { importLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) }) {
                    Text("导入 CSV")
                }
                OutlinedButton(onClick = { showHelp = !showHelp }) { Text("格式说明") }
            }
            if (showHelp) {
                Card {
                    Text(
                        "CSV 示例（UTF-8 编码）：\n姓名,手机号,备注\n张三,13800138000,续约回访\n李四,+8613900000000,新客户\n\n每行一位客户。号码应包含国家/地区代码（如需要）。",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Text(message, color = MaterialTheme.colorScheme.primary)
            if (customers.isNotEmpty()) {
                Text("进度：${currentIndex + 1} / ${customers.size}")
                LinearProgressIndicator(progress = { (currentIndex + 1).toFloat() / customers.size }, modifier = Modifier.fillMaxWidth())
            }
            if (current != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(current.phone, style = MaterialTheme.typography.titleMedium)
                        if (current.note.isNotBlank()) Text("备注：${current.note}")
                        Text("状态：${current.status}")
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(current.phone)}"))
                                try { context.startActivity(intent) }
                                catch (_: Exception) { message = "未找到可用的电话应用。" }
                            }
                        ) { Text("打开电话界面并确认拨号") }
                        Text("拨号前请核对号码；本应用不会直接自动拨出电话。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("已接通", "未接听", "稍后回访", "拒绝").forEach { status ->
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                    onClick = {
                                        customers = customers.toMutableList().also {
                                            it[currentIndex] = it[currentIndex].copy(status = status)
                                        }
                                        message = "已记录：$status"
                                    }
                                ) { Text(status, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = currentIndex > 0,
                                onClick = { currentIndex-- }
                            ) { Text("上一位") }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (currentIndex < customers.lastIndex) currentIndex++
                                    else message = "已到名单末尾。"
                                }
                            ) { Text("下一位") }
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有客户名单")
                        Text("点击“导入 CSV”开始使用。")
                    }
                }
            }
            Text("客户名单 ${customers.size} 位", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f, fill = true)) {
                itemsIndexed(customers) { index, customer ->
                    OutlinedCard(onClick = { currentIndex = index }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(customer.name, fontWeight = FontWeight.SemiBold)
                                Text(customer.phone, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(customer.status, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                cell.append('"'); i++
            }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> {
                result.add(cell.toString()); cell.clear()
            }
            else -> cell.append(c)
        }
        i++
    }
    result.add(cell.toString())
    return result
}
