package com.vault.emulatorhub

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import java.io.InputStreamReader

data class PlatformConfig(val platforms: List<ConsoleSource>)

data class ConsoleSource(
    val id: String,
    val name: String,
    val subtitle: String,
    val url: String
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platforms = loadPlatformsFromAssets()

        setContent {
            var selectedUrl by remember { mutableStateOf<String?>(null) }

            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF07090E))) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF07090E)
                ) {
                    AnimatedVisibility(
                        visible = selectedUrl == null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        DashboardScreen(
                            platforms = platforms,
                            onSelect = { selectedUrl = it }
                        )
                    }

                    if (selectedUrl != null) {
                        BrowserScreen(
                            url = selectedUrl!!,
                            onClose = { selectedUrl = null },
                            onDownload = { url, userAgent, contentDisposition, mimetype ->
                                downloadFile(url, userAgent, contentDisposition, mimetype)
                            }
                        )
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
            setDescription("Downloading ROM via Vault Hub sniffer...")
            setTitle(filename)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Snagged file: $filename", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun DashboardScreen(platforms: List<ConsoleSource>, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // --- Top Bar & Branding ---
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
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF131823))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ONLINE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF10B981),
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // --- Grid of Cards ---
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(platforms) { console ->
                ConsoleCard(console = console, onClick = { onSelect(console.url) })
            }
        }

        // --- Status Footer ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0F141F))
                .border(1.dp, Color(0xFF1B2333), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ENGINE: 1DM AUTO-INTERCEPTOR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8),
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "ACTIVE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )
            }
        }
    }
}

@Composable
fun ConsoleCard(console: ConsoleSource, onClick: () -> Unit) {
    val (cardBrush, accentColor, tagText) = when (console.id.lowercase()) {
        "switch" -> Triple(
            Brush.verticalGradient(listOf(Color(0xFF2B121A), Color(0xFF161019))),
            Color(0xFFFF3366),
            "HYBRID"
        )
        "pc" -> Triple(
            Brush.verticalGradient(listOf(Color(0xFF0D2534), Color(0xFF0D1722))),
            Color(0xFF00E5FF),
            "X86 CORE"
        )
        "nds" -> Triple(
            Brush.verticalGradient(listOf(Color(0xFF1F1235), Color(0xFF120E22))),
            Color(0xFFBD00FF),
            "DUAL SCREEN"
        )
        "gba" -> Triple(
            Brush.verticalGradient(listOf(Color(0xFF281E0B), Color(0xFF16140E))),
            Color(0xFFFFB300),
            "CLASSIC"
        )
        else -> Triple(
            Brush.verticalGradient(listOf(Color(0xFF181F2C), Color(0xFF0F141F))),
            Color(0xFF38BDF8),
            "CONSOLE"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(145.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(cardBrush)
            .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top row: Tag Pill & Accent Glow Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = tagText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor,
                        letterSpacing = 0.5.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }

            // Bottom Column: Name & Subtitle
            Column {
                Text(
                    text = console.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFF8FAFC)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = console.subtitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
fun BrowserScreen(
    url: String,
    onClose: () -> Unit,
    onDownload: (String, String, String, String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D121D))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    "← Back to Hub",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Sniffer Engaged",
                    color = Color(0xFF10B981),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
                        onDownload(dlUrl, userAgent, contentDisposition, mimetype)
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
