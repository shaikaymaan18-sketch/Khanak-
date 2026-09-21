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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platforms = loadPlatformsFromAssets()

        setContent {
            val context = LocalContext.current
            var currentScreen by remember { mutableStateOf(ScreenState.HUB) }
            var activeBrowserUrl by remember { mutableStateOf("") }

            // ---------------------------------------------------------
            // RUNTIME PERMISSION LAUNCHER (Storage & Notifications)
            // ---------------------------------------------------------
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
                    Toast.makeText(context, "Permissions active", Toast.LENGTH_SHORT).show()
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

            // ---------------------------------------------------------
            // APP SCREEN ROUTING
            // ---------------------------------------------------------
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
                        ScreenState.BROWSER -> {
                            BrowserScreen(
                                url = activeBrowserUrl,
                                onClose = { currentScreen = ScreenState.HUB },
                                onDownload = { url, userAgent, contentDisposition, mimetype ->
                                    downloadFile(url, userAgent, contentDisposition, mimetype)
                                }
                            )
                        }
                        ScreenState.DOWNLOADS -> {
                            DownloadsScreen(
                                onClose = { currentScreen = ScreenState.HUB }
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
// DASHBOARD
// -------------------------------------------------------------
@Composable
fun DashboardScreen(
    platforms: List<ConsoleSource>,
    onSelectPlatform: (String) -> Unit,
    onOpenDownloads: () -> Unit
) {
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
                    text = "VAULT HUB",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 1.5.sp,
                    color = Color(0xFFF1F5F9)
                )
                Text(
                    text = "ROM & ARCHIVE LAUNCHER",
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

        Spacer(modifier = Modifier.height(28.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(platforms) { console ->
                ConsoleCard(console = console, onClick = { onSelectPlatform(console.url) })
            }
        }

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
                    text = "ENGINE: AUTO-SNIFFER",
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

// -------------------------------------------------------------
// DOWNLOAD MANAGER VIEW (Categories: All / Downloading / Pending / Completed)
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

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("Back to Hub", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        }
    }
}

@Composable
fun DownloadCard(task: DownloadTask) {
    val (statusLabel, statusColor) = when (task.status) {
        DownloadManager.STATUS_RUNNING -> "DOWNLOADING" to Color(0xFF00E5FF)
        DownloadManager.STATUS_PENDING -> "QUEUED / PENDING" to Color(0xFFFFB300)
        DownloadManager.STATUS_PAUSED -> "PAUSED" to Color(0xFFFFB300)
        DownloadManager.STATUS_SUCCESSFUL -> "COMPLETED" to Color(0xFF10B981)
        DownloadManager.STATUS_FAILED -> "FAILED" to Color(0xFFFF3366)
        else -> "UNKNOWN" to Color(0xFF94A3B8)
    }

    val progress = if (task.totalBytes > 0) {
        (task.bytesDownloaded.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val percentText = if (task.totalBytes > 0) "${(progress * 100).toInt()}%" else "--"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0E131E))
            .border(1.dp, Color(0xFF1A2234), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = statusColor,
                trackColor = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatSize(task.bytesDownloaded)} / ${formatSize(task.totalBytes)}",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
                Text(
                    text = percentText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }
        }
    }
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
            val title = if (titleCol >= 0) c.getString(titleCol) ?: "Archive File" else "Archive File"
            val status = if (statusCol >= 0) c.getInt(statusCol) else 0
            val downloaded = if (downCol >= 0) c.getLong(downCol) else 0L
            val total = if (totalCol >= 0) c.getLong(totalCol) else 0L

            tasks.add(DownloadTask(id, title, status, downloaded, total))
        }
    }
    return tasks.reversed()
}

fun formatSize(bytes: Long): String {
    if (bytes <= 
