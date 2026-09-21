package com.vault.emulatorhub

import android.app.DownloadManager
import android.content.Context
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

enum class ScreenState { HUB, BROWSER, DOWNLOADS }
data class PlatformConfig(val platforms: List<ConsoleSource>)
data class ConsoleSource(val id: String, val name: String, val subtitle: String, val url: String)
data class DownloadTask(val id: Long, val title: String, val status: Int, val bytesDownloaded: Long, val totalBytes: Long)
data class ConsoleTheme(val brush: Brush, val accent: Color, val badge: String, val chips: List<String>)

val AD_BLOCK_DOMAINS = listOf(
    "doubleclick.net", "googleads", "adservice.google", "popads.net",
    "propellerads.com", "exoclick.com", "adsterra.com", "monetag.com",
    "adnxs.com", "syndication.exoclick.com", "trafficjunky", "onclickmega.com",
    "ad-delivery", "advertising", "clksite.com", "zeroredirect", "popunder",
    "revenuehits", "infolinks", "onclickalgo", "yadro.ru", "deloton.com"
)

fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val kb = bytes.toDouble() / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    if (gb >= 1.0) return String.format("%.2f GB", gb)
    if (mb >= 1.0) return String.format("%.1f MB", mb)
    return String.format("%.0f KB", kb)
}

fun fetchDownloads(context: Context): List<DownloadTask> {
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val cursor = dm.query(DownloadManager.Query()) ?: return emptyList()
    val tasks = mutableListOf<DownloadTask>()
    cursor.use { c ->
        val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
        val titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val downCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        while (c.moveToNext()) {
            val id = if (idCol >= 0) c.getLong(idCol) else 0L
            val title = if (titleCol >= 0) c.getString(titleCol) ?: "Archive" else "Archive"
            val status = if (statusCol >= 0) c.getInt(statusCol) else 0
            val down = if (downCol >= 0) c.getLong(downCol) else 0L
            val total = if (totalCol >= 0) c.getLong(totalCol) else 0L
            tasks.add(DownloadTask(id, title, status, down, total))
        }
    }
    return tasks.reversed()
}

fun getConsoleTheme(id: String): ConsoleTheme {
    return when (id.lowercase()) {
        "switch" -> ConsoleTheme(
            Brush.horizontalGradient(listOf(Color(0xFF28111A), Color(0xFF140F18))),
            Color(0xFFFF3366), "HYBRID", listOf("NSP", "XCI", "UPDATES")
        )
        "pc" -> ConsoleTheme(
            Brush.horizontalGradient(listOf(Color(0xFF0D2534), Color(0xFF0E1622))),
            Color(0xFF00E5FF), "X86 CORE", listOf("EXE", "DIRECT-PLAY", "7Z")
        )
        "nds" -> ConsoleTheme(
            Brush.horizontalGradient(listOf(Color(0xFF221138), Color(0xFF120E22))),
            Color(0xFFBD00FF), "DUAL SCREEN", listOf("NDS", "ZIP", "SAV")
        )
        "gba" -> ConsoleTheme(
            Brush.horizontalGradient(listOf(Color(0xFF2A1C0B), Color(0xFF14130E))),
            Color(0xFFFFB300), "CLASSIC", listOf("GBA", "BIN", "SAV")
        )
        else -> ConsoleTheme(
            Brush.horizontalGradient(listOf(Color(0xFF161E2E), Color(0xFF0E131F))),
            Color(0xFF38BDF8), "CONSOLE", listOf("ARCHIVE", "ROM")
        )
    }
}

@Composable
fun BrowserScreen(url: String, onClose: () -> Unit, onDownload: (String, String, String, String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0D121D)).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E293B)).border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Back to Hub", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF10B981)))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Shield Active", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    val ua = settings.userAgentString
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val req = request?.url?.toString()?.lowercase() ?: return null
                            if (AD_BLOCK_DOMAINS.any { req.contains(it) }) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val req = request?.url?.toString() ?: return false
                            val l = req.lowercase()
                            if (l.endsWith(".zip") || l.endsWith(".7z") || l.endsWith(".rar") || l.endsWith(".nsp") || l.endsWith(".xci") || l.endsWith(".nds") || l.endsWith(".gba") || l.endsWith(".iso") || l.endsWith(".exe")) {
                                onDownload(req, ua, "", "")
                                return true
                            }
                            return AD_BLOCK_DOMAINS.any { l.contains(it) }
                        }
                    }
                    setDownloadListener { dl, u, cd, m, _ -> onDownload(dl ?: "", u ?: ua, cd ?: "", m ?: "") }
                    loadUrl(url)
                }
            }
        )
    }
}

@Composable
fun DownloadsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var downloadList by remember { mutableStateOf<List<DownloadTask>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf("ALL") }

    LaunchedEffect(Unit) {
        while (true) {
            downloadList = fetchDownloads(context)
            delay(1500)
        }
    }

    val filteredList = remember(downloadList, selectedFilter) {
        when (selectedFilter) {
            "DOWNLOADING" -> downloadList.filter { it.status == DownloadManager.STATUS_RUNNING }
            "PENDING" -> downloadList.filter { it.status == DownloadManager.STATUS_PENDING || it.status == DownloadManager.STATUS_PAUSED }
            "COMPLETED" -> downloadList.filter { it.status == DownloadManager.STATUS_SUCCESSFUL }
            else -> downloadList
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("DOWNLOADS", fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = Color(0xFFF1F5F9))
                Text("LOCAL TRANSFERS & ARCHIVES", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color(0xFF64748B))
            }
            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E293B)).border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Back to Hub", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL", "DOWNLOADING", "PENDING", "COMPLETED").forEach { tag ->
                val isSelected = selectedFilter == tag
                Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF131823)).border(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerShape(10.dp)).clickable { selectedFilter = tag }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(tag, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = if (isSelected) Color(0xFF07090E) else Color(0xFF94A3B8))
                }
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        if (filteredList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No files found in this category.", color = Color(0xFF475569), fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filteredList) { item -> DownloadCard(task = item) }
            }
        }
    }
}

@Composable
fun DownloadCard(task: DownloadTask) {
    val statusLabel = when (task.status) {
        DownloadManager.STATUS_RUNNING -> "DOWNLOADING"
        DownloadManager.STATUS_PENDING -> "PENDING"
        DownloadManager.STATUS_PAUSED -> "PAUSED"
        DownloadManager.STATUS_SUCCESSFUL -> "COMPLETED"
        DownloadManager.STATUS_FAILED -> "FAILED"
        else -> "UNKNOWN"
    }
    val statusColor = when (task.status) {
        DownloadManager.STATUS_RUNNING -> Color(0xFF00E5FF)
        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> Color(0xFFFFB300)
        DownloadManager.STATUS_SUCCESSFUL -> Color(0xFF10B981)
        DownloadManager.STATUS_FAILED -> Color(0xFFFF3366)
        else -> Color(0xFF94A3B8)
    }
    val progress = if (task.totalBytes > 0L) (task.bytesDownloaded.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val percentText = if (task.totalBytes > 0L) "${(progress * 100).toInt()}%" else "--"

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0E131E)).border(1.dp, Color(0xFF1A2234), RoundedCornerShape(16.dp)).padding(14.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(statusColor.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(statusLabel, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = statusColor, trackColor = Color(0xFF1E293B))
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatSize(task.bytesDownloaded)} / ${formatSize(task.totalBytes)}", fontSize = 11.sp, color = Color(0xFF64748B))
                Text(percentText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor)
            }
        }
    }
}

