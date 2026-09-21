package com.vault.emulatorhub

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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

data class ConsoleCardStyle(
    val brush: Brush,
    val accentColor: Color,
    val formatChips: List<String>,
    val badgeText: String
)

val AD_BLOCK_DOMAINS = setOf(
    "doubleclick.net", "googleads", "adservice.google", "popads.net",
    "propellerads.com", "exoclick.com", "adsterra.com", "monetag.com",
    "adnxs.com", "syndication.exoclick.com", "trafficjunky", "onclickmega.com",
    "ad-delivery", "advertising", "clksite.com", "zeroredirect", "popunder",
    "revenuehits", "infolinks", "onclickalgo", "yadro.ru", "deloton.com"
)

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
                val pendingPermissions = permissionsToRequest.filter { perm ->
                    ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
                }
                if (pendingPermissions.isNotEmpty()) {
                    permissionLauncher.launch(pendingPermissions.toTypedArray())
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
                            DownloadsScreen(
                                onClose = { currentScreen = ScreenState.HUB }
                            )
                        }
                        ScreenState.BROWSER -> {
                            BrowserScreen(
                                url = activeBrowserUrl,
                                onClose = { currentScreen = ScreenState.HUB },
                                onDownload = { url: String, userAgent: String, contentDisposition: String, mimetype: String ->
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
            setDescription("Vault Hub active download...")
            setTitle(filename)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Queued: $filename", Toast.LENGTH_SHORT).show()
    }
}

// -------------------------------------------------------------
// STANDALONE AD & REDIRECT SHIELD
// -------------------------------------------------------------
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
            if (lower.contains(adDomain)) {
                return true
            }
        }
        return false
    }
}

// -------------------------------------------------------------
// DASHBOARD SCREEN
// -------------------------------------------------------------
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

// -------------------------------------------------------------
// DOWNLOADS MANAGEMENT SCREEN
// -------------------------------------------------------------
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
            "PENDING" -> downloadList.filter {
                it.status == DownloadManager.STATUS_PENDING || it.status == DownloadManager.STATUS_PAUSED
            }
            "COMPLETED" -> downloadList.filter { it.status == DownloadManager.STATUS_SUCCESSFUL }
            else -> downloadList
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DOWNLOADS",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color(0xFFF1F5F9)
                )
                Text(
                    text = "LOCAL TRANSFERS & ARCHIVES",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = Color(0xFF64748B)
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Back to Hub",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL", "DOWNLOADING", "PENDING", "COMPLETED").forEach { tag ->
                val isSelected = selectedFilter == tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF131823))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { selectedFilter = tag }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = tag,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isSelected) Color(0xFF07090E) else Color(0xFF94A3B8)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No files found in this category.",
                    color = Color(0xFF475569),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredList) { item ->
                    DownloadCard(task = item)
                }
            }
      
