package com.vault.emulatorhub

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

enum class ScreenState { HUB, BROWSER, DOWNLOADS }

data class PlatformConfig(val platforms: List<ConsoleSource>)

data class ConsoleSource(
    val id: String,
    val name: String,
    val subtitle: String,
    val url: String
)

data class DownloadTask(
    val id: Long,
    val title: String,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long
)

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

class AdShieldClient(
    private val onDownload: (String, String, String, String) -> Unit,
    private val defaultUserAgent: String
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val reqUrl = request?.url?.toString()?.lowercase() ?: return null
        for (adDomain in AD_BLOCK_DOMAINS) {
            if (reqUrl.contains(adDomain)) {
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val reqUrl = request?.url?.toString() ?: return false
        val lower = reqUrl.lowercase()
        if (lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar") ||
            lower.endsWith(".nsp") || lower.endsWith(".xci") || lower.endsWith(".nds") ||
            lower.endsWith(".gba") || lower.endsWith(".iso") || lower.endsWith(".exe")
        ) {
            onDownload(reqUrl, defaultUserAgent, "", "")
            return true
        }
        for (adDomain in AD_BLOCK_DOMAINS) {
            if (lower.contains(adDomain)) return true
        }
        return false
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platforms = loadPlatformsFromAssets()

        setContent {
            val context = LocalContext.current
            var currentScreen by remember { mutableStateOf(ScreenState.HUB) }
            var activeBrowserUrl by remember { mutableStateOf("") }

            val permissionsToRequest = remember {
                val list = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    list.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                list
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val allGranted = result.values.all { it }
                if (allGranted && result.isNotEmpty()) {
                    Toast.makeText(context, "Permissions enabled", Toast.LENGTH_SHORT).show()
                }
            }

            LaunchedEffect(Unit) {
                val pending = permissionsToRequest.filter { perm ->
                    ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
                }
                if (pending.isNotEmpty()) {
                    permissionLauncher.launch(pending.toTypedArray())
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF07090E))) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF07090E)
                ) {
                    when (currentScreen) {
                        ScreenState.HUB -> {
                            DashboardScreen(
                                platforms = platforms,
                                onSelectPlatform = { url ->
                                    activeBrowserUrl = url
                                    currentScreen = ScreenState.BROWSER
                                },
                                onOpenDownloads = { currentScreen = ScreenState.DOWNLOADS }
                            )
                        }
                        ScreenState.DOWNLOADS -> {
                            DownloadsScreen(onClose = { currentScreen = ScreenState.HUB })
                        }
                        ScreenState.BROWSER -> {
                            BrowserScreen(
                                url = activeBrowserUrl,
                                onClose = { currentScreen = ScreenState.HUB },
                                onDownload = { url, userAgent, contentDisposition, mimetype ->
                                    downloadFile(url, userAgent, contentDisposition, mimetype)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun loadPlatformsFromAssets(): List<ConsoleSource> {
        return try {
            assets.open("sources.json").use { stream ->
                val reader = InputStreamReader(stream)
                val config = Gson().fromJson(reader, PlatformConfig::class.java)
                config.platforms
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimetype)
            addRequestHeader("User-Agent", userAgent)
            setDescription("Vault Hub active transfer...")
            setTitle(filename)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Queued: $filename", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun DashboardScreen(
    platforms: List<ConsoleSource>,
    onSelectPlatform: (String) -> Unit,
    onOpenDownloads: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "VAULT HUB",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 1.5.sp,
                        color = Color(0xFFF1F5F9)
                    )
                    Text(
                        text = "EMULATOR REPOSITORIES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color(0xFF64748B)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF131823))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                        .clickable { onOpenDownloads() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF38BDF8))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "DOWNLOADS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF131A29), Color(0xFF0E131F))
                        )
                    )
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "CORE STATUS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${platforms.size} Repositories Connected",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "AD-SHIELD ON",
                            color = Color(0xFF10B981),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "AVAILABLE PLATFORMS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Color(0xFF475569),
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
        }

        items(platforms) { console ->
            ConsoleCard(console = console, onClick = { onSelectPlatform(console.url) })
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F141F))
                    .border(1.dp, Color(0xFF1B2333), RoundedCornerShape(16.dp))
                    .clickable { onOpenDownloads() }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ENGINE: AD-BLOCK + AUTO-SNIFFER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "VIEW QUEUE →",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
            }
        }
    }
}

@Composable
fun ConsoleCard(console: ConsoleSource, onClick: () -> Unit) {
    val id = console.id.lowercase()
    val brush = when (id) {
        "switch" -> Brush.horizontalGradient(listOf(Color(0xFF28111A), Color(0xFF140F18)))
        "pc" -> Brush.horizontalGradient(listOf(Color(0xFF0D2534), Color(0xFF0E1622)))
        "nds" -> Brush.horizontalGradient(listOf(Color(0xFF221138), Color(0xFF120E22)))
        "gba" -> Brush.horizontalGradient(listOf(Color(0xFF2A1C0B), Color(0xFF14130E)))
        else -> Brush.horizontalGradient(listOf(Color(0xFF161E2E), Color(0xFF0E131F)))
    }
    val accentColor = when (id) {
        "switch" -> Color(0xFFFF3366)
        "pc" -> Color(0xFF00E5FF)
        "nds" -> Color(0xFFBD00FF)
        "gba" -> Color(0xFFFFB300)
        else -> Color(0xFF38BDF8)
    }
    val badgeText = when (id) {
        "switch" -> "HYBRID"
        "pc" -> "X86 CORE"
        "nds" -> "DUAL SCREEN"
        "gba" -> "CLASSIC"
        else -> "CONSOLE"
    }
    val chips = when (id) {
        "switch" -> listOf("NSP", "XCI", "UPDATES")
        "pc" -> listOf("EXE", "DIRECT-PLAY", "7Z")
        "nds" -> listOf("NDS", "ZIP", "SAV")
        "gba" -> listOf("GBA", "BIN", "SAV")
        else -> listOf("ARCHIVE", "ROM")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(brush)
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = console.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFF8FAFC)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            color = accentColor,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "TARGET: ${console.subtitle}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { chip ->
                        Box(
                            modifier = Modifier
                                
