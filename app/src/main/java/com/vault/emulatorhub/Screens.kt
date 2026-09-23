package com.vault.emulatorhub

import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.Status
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

enum class ScreenState { HUB, BROWSER, DOWNLOADS }
data class PlatformConfig(val platforms: List<ConsoleSource>)
data class ConsoleSource(val id: String, val name: String, val subtitle: String, val url: String)
data class ConsoleTheme(val brush: Brush, val accent: Color, val badge: String, val chips: List<String>)

val AD_BLOCK_DOMAINS = listOf(
    "doubleclick.net", "googleads", "adservice.google", "popads.net",
    "propellerads.com", "exoclick.com", "adsterra.com", "monetag.com",
    "adnxs.com", "syndication.exoclick.com", "trafficjunky", "onclickmega.com",
    "clksite.com", "zeroredirect", "popunder"
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

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 KB/s"
    val kb = bytesPerSec.toDouble() / 1024.0
    val mb = kb / 1024.0
    if (mb >= 1.0) return String.format("%.1f MB/s", mb)
    return String.format("%.0f KB/s", kb)
}

fun getConsoleTheme(id: String): ConsoleTheme {
    return when (id.lowercase()) {
        "switch" -> ConsoleTheme(Brush.horizontalGradient(listOf(Color(0xFF28111A), Color(0xFF140F18))), Color(0xFFFF3366), "HYBRID", listOf("NSP", "XCI", "UPDATES"))
        "pc" -> ConsoleTheme(Brush.horizontalGradient(listOf(Color(0xFF0D2534), Color(0xFF0E1622))), Color(0xFF00E5FF), "X86 CORE", listOf("EXE", "DIRECT-PLAY", "7Z"))
        "nds" -> ConsoleTheme(Brush.horizontalGradient(listOf(Color(0xFF221138), Color(0xFF120E22))), Color(0xFFBD00FF), "DUAL SCREEN", listOf("NDS", "ZIP", "SAV"))
        "gba" -> ConsoleTheme(Brush.horizontalGradient(listOf(Color(0xFF2A1C0B), Color(0xFF14130E))), Color(0xFFFFB300), "CLASSIC", listOf("GBA", "BIN", "SAV"))
        else -> ConsoleTheme(Brush.horizontalGradient(listOf(Color(0xFF161E2E), Color(0xFF0E131F))), Color(0xFF38BDF8), "CONSOLE", listOf("ARCHIVE", "ROM"))
    }
}

@Composable
fun BrowserScreen(url: String, onClose: () -> Unit, onDownload: (String, String, String, String, String) -> Unit) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onClose()
        }
    }

    if (pendingRedirectUrl != null) {
        val targetUri = Uri.parse(pendingRedirectUrl)
        val targetHost = targetUri.host ?: "external destination"

        AlertDialog(
            onDismissRequest = { pendingRedirectUrl = null },
            containerColor = Color(0xFF0F141F),
            title = { Text("Redirect Alert", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    Text("Attempting to redirect outside to:", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(targetHost, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val destination = pendingRedirectUrl
                        pendingRedirectUrl = null
                        destination?.let { webViewRef?.loadUrl(it) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) { Text("Proceed", color = Color(0xFF07090E), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingRedirectUrl = null },
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.horizontalGradient(listOf(Color(0xFF334155), Color(0xFF334155))))
                ) { Text("Stay Here", color = Color(0xFFE2E8F0)) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0D121D)).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E293B)).border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("← Back to Hub", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = true
                    }

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
                            val reqUrl = request?.url?.toString() ?: return false
                            val lower = reqUrl.lowercase()
                            if (AD_BLOCK_DOMAINS.any { lower.contains(it) }) return true

                            if (lower.contains(".html") || lower.contains(".php") || lower.contains(".aspx")) {
                                return false
                            }

                            if (lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar") ||
                                lower.endsWith(".nsp") || lower.endsWith(".xci") || lower.endsWith(".nds") ||
                                lower.endsWith(".gba") || lower.endsWith(".iso") || lower.endsWith(".exe")
                            ) {
                                onDownload(reqUrl, ua, "", "", view?.url ?: url)
                                return true
                            }

                            val currentHost = view?.url?.let { Uri.parse(it).host?.lowercase() }
                            val targetHost = request.url?.host?.lowercase()
                            if (currentHost != null && targetHost != null && currentHost != targetHost) {
                                pendingRedirectUrl = reqUrl
                                return true
                            }
                            return false
                        }
                    }

                    setDownloadListener { dl, u, cd, m, _ ->
                        if (!dl.isNullOrEmpty() && 
                            !dl.contains(".html", ignoreCase = true) && 
                            !dl.contains(".php", ignoreCase = true) &&
                            !dl.contains(".aspx", ignoreCase = true)
                        ) {
                            onDownload(dl, u ?: ua, cd ?: "", m ?: "", webViewRef?.url ?: url)
                        }
                    }
                    loadUrl(url)
                    webViewRef = this
                }
            }
        )
    }
}

@Composable
fun DashboardScreen(platforms: List<ConsoleSource>, onSelectPlatform: (String) -> Unit, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("VAULT HUB", fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, letterSpacing = 1.5.sp, color = Color(0xFFF1F5F9))
                    Text("EMULATOR REPOSITORIES", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF64748B))
                }
                val interactionSource = remember { MutableInteractionSource() }
                val isDownloadsPressed by interactionSource.collectIsPressedAsState()
                val dScale by animateFloatAsState(if (isDownloadsPressed) 0.90f else 1f, label = "bounce")

                Box(modifier = Modifier.scale(dScale).clip(RoundedCornerShape(16.dp)).background(Color(0xFF131823)).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp)).clickable(interactionSource = interactionSource, indication = null, onClick = onOpenDownloads).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("DOWNLOADS", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF38BDF8), letterSpacing = 1.sp)
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF131A29), Color(0xFF0E131F)))).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp)).padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("CORE STATUS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), letterSpacing = 1.5.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("${platforms.size} Repositories Connected", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF10B981).copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("AD-SHIELD ON", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    }
                }
            }
        }

        item { Text("AVAILABLE PLATFORMS", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color(0xFF475569), modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) }

        items(platforms) { console ->
            ConsoleCard(
                console = console,
                onClick = {
                    if (console.id.lowercase() == "switch") {
                        Toast.makeText(context, "Nintendo Switch support is under development!", Toast.LENGTH_SHORT).show()
                    } else {
                        onSelectPlatform(console.url)
                    }
                }
            )
        }
    }
}

@Composable
fun ConsoleCard(console: ConsoleSource, onClick: () -> Unit) {
    val theme = remember(console.id) { getConsoleTheme(console.id) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "bounce")

    Box(
        modifier = Modifier.fillMaxWidth().scale(scale).clip(RoundedCornerShape(20.dp)).background(theme.brush).border(1.dp, theme.accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick).padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(4.dp).height(58.dp).clip(RoundedCornerShape(2.dp)).background(theme.accent))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(console.name, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFF8FAFC))
                    Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(theme.accent.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(theme.badge, fontSize = 8.sp, fontWeight = FontWeight.Black, color = theme.accent, letterSpacing = 0.5.sp)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text("TARGET: ${console.subtitle}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF94A3B8))
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    theme.chips.forEach { chip ->
                        Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF090D14)).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                            Text(chip, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                        }
                    }
                }
            }
            Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF0F141F)).border(1.dp, theme.accent.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
                Text("→", color = theme.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DownloadsScreen(onClose: () -> Unit) {
    var downloadList by remember { mutableStateOf<List<Download>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf("ALL") }
    val fetch = remember { runCatching { Fetch.Impl.getDefaultInstance() }.getOrNull() }

    LaunchedEffect(fetch) {
        while (fetch != null) {
            fetch.getDownloads { downloads ->
                downloadList = downloads.sortedByDescending { it.id }
            }
            delay(1000)
        }
    }

    val filteredList = remember(downloadList, selectedFilter) {
        when (selectedFilter) {
            "ACTIVE" -> downloadList.filter { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }
            "PAUSED" -> downloadList.filter { it.status == Status.PAUSED || it.status == Status.FAILED }
            "COMPLETED" -> downloadList.filter { it.status == Status.COMPLETED }
            else -> downloadList
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("DOWNLOADS", fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = Color(0xFFF1F5F9))
                Text("MULTI-THREADED ENGINE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color(0xFF64748B))
            }
            Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E293B)).border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp)).clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Back to Hub", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL", "ACTIVE", "PAUSED", "COMPLETED").forEach { tag ->
                val isSelected = selectedFilter == tag
                Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF131823)).border(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerS
                                val isSelected = selectedFilter == tag
                Box(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF131823)).border(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerShape(10.dp)).clickable { selectedFilter = tag }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(tag, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = if (isSelected) Color(0xFF07090E) else Color(0xFF94A3B8))
                }
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        if (filteredList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No files in this queue.", color = Color(0xFF475569), fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filteredList) { item -> DownloadCard(task = item, fetch = fetch) }
            }
        }
    }
}

@Composable
fun DownloadCard(task: Download, fetch: Fetch?) {
    val statusLabel = when (task.status) {
        Status.DOWNLOADING -> "DOWNLOADING"
        Status.PAUSED -> "PAUSED"
        Status.QUEUED -> "QUEUED"
        Status.COMPLETED -> "COMPLETED"
        Status.FAILED -> "FAILED"
        Status.CANCELLED -> "CANCELLED"
        else -> "UNKNOWN"
    }
    val statusColor = when (task.status) {
        Status.DOWNLOADING -> Color(0xFF00E5FF)
        Status.QUEUED, Status.PAUSED -> Color(0xFFFFB300)
        Status.COMPLETED -> Color(0xFF10B981)
        Status.FAILED, Status.CANCELLED -> Color(0xFFFF3366)
        else -> Color(0xFF94A3B8)
    }
    
    val progress = if (task.total > 0L) (task.downloaded.toFloat() / task.total.toFloat()).coerceIn(0f, 1f) else 0f

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0E131E)).border(1.dp, Color(0xFF1A2234), RoundedCornerShape(16.dp)).padding(14.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(task.file.substringAfterLast("/"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (task.status == Status.DOWNLOADING || task.status == Status.QUEUED) {
                        Icon(Icons.Rounded.Pause, "Pause", modifier = Modifier.size(20.dp).clickable { fetch?.pause(task.id) }, tint = Color.White)
                    } else if (task.status == Status.PAUSED || task.status == Status.FAILED) {
                        Icon(Icons.Rounded.PlayArrow, "Resume", modifier = Modifier.size(20.dp).clickable { fetch?.resume(task.id) }, tint = Color.White)
                    }
                    if (task.status != Status.COMPLETED) {
                        Icon(Icons.Rounded.Close, "Cancel", modifier = Modifier.size(20.dp).clickable { fetch?.delete(task.id) }, tint = Color(0xFFFF3366))
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = statusColor, trackColor = Color(0xFF1E293B))
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatSize(task.downloaded)} / ${formatSize(task.total)}", fontSize = 11.sp, color = Color(0xFF64748B))
                if (task.status == Status.DOWNLOADING) {
                    Text(formatSpeed(task.downloadedBytesPerSecond), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                } else {
                    Text(statusLabel, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = statusColor)
                }
            }
        }
    }
}
