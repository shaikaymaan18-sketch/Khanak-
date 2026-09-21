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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0E1116))) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (selectedUrl == null) {
                        DashboardScreen(platforms = platforms, onSelect = { selectedUrl = it })
                    } else {
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
            setDescription("Downloading game file...")
            setTitle(filename)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Download started: $filename", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun DashboardScreen(platforms: List<ConsoleSource>, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text("Vault Hub", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Select a console platform", fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(20.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(platforms) { console ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF181C24)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clickable { onSelect(console.url) }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(console.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Text(console.subtitle, color = Color(0xFFA8C7FA), fontSize = 12.sp)
                    }
                }
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
                .background(Color(0xFF181C24))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF004A77))
            ) {
                Text("Back to Hub")
            }
            Text(
                "Link Sniffer Active",
                color = Color(0xFF7AE582),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
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
