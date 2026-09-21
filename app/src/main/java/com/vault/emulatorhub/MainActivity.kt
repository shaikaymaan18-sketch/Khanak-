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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.io.InputStreamReader

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
                if (result.values.all { it } && result.isNotEmpty()) {
                    Toast.makeText(context, "Permissions enabled", Toast.LENGTH_SHORT).show()
                }
            }

            LaunchedEffect(Unit) {
                val pending = permissionsToRequest.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (pending.isNotEmpty()) permissionLauncher.launch(pending.toTypedArray())
            }

            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF07090E))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF07090E)) {
                    when (currentScreen) {
                        ScreenState.HUB -> DashboardScreen(
                            platforms = platforms,
                            onSelectPlatform = { url ->
                                activeBrowserUrl = url
                                currentScreen = ScreenState.BROWSER
                            },
                            onOpenDownloads = { currentScreen = ScreenState.DOWNLOADS }
                        )
                        ScreenState.DOWNLOADS -> DownloadsScreen(onClose = { currentScreen = ScreenState.HUB })
                        ScreenState.BROWSER -> BrowserScreen(
                            url = activeBrowserUrl,
                            onClose = { currentScreen = ScreenState.HUB },
                            onDownload = { url, ua, cd, mime -> downloadFile(url, ua, cd, mime) }
                        )
                    }
                }
            }
        }
    }

    private fun loadPlatformsFromAssets(): List<ConsoleSource> {
        return try {
            assets.open("sources.json").use { stream ->
                Gson().fromJson(InputStreamReader(stream), PlatformConfig::class.java).platforms
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

@Composable
fun DashboardScreen(platforms: List<ConsoleSource>, onSelectPlatform: (String) -> Unit, onOpenDownloads: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("VAULT HUB", fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, letterSpacing = 1.5.sp, color = Color(0xFFF1F5F9))
                    Text("EMULATOR REPOSITORIES", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF64748B))
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF131823)).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp)).clickable { onOpenDownloads() }.padding(horizontal = 14.dp, vertical = 8.dp)) {
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
        item {
            Text("AVAILABLE PLATFORMS", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Color(0xFF475569), modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
        }
        items(platforms) { console -> ConsoleCard(console = console, onClick = { onSelectPlatform(console.url) }) }
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0F141F)).border(1.dp, Color(0xFF1B2333), RoundedCornerShape(16.dp)).clickable { onOpenDownloads() }.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("ENGINE: AD-BLOCK + AUTO-SNIFFER", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF94A3B8), letterSpacing = 0.5.sp)
                    Text("VIEW QUEUE →", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                }
            }
        }
    }
}

@Composable
fun ConsoleCard(console: ConsoleSource, onClick: () -> Unit) {
    val theme = remember(console.id) { getConsoleTheme(console.id) }
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(theme.brush).border(1.dp, theme.accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp)).clickable { onClick() }.padding(16.dp)) {
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

