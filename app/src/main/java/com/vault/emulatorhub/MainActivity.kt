package com.vault.emulatorhub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.tonyodev.fetch2.AbstractFetchListener
import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.EnqueueAction
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
import com.tonyodev.fetch2.NetworkType
import com.tonyodev.fetch2.Priority
import com.tonyodev.fetch2.Request
import java.io.File
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platforms = loadPlatformsFromAssets()

        setContent {
            val context = LocalContext.current
            var currentScreen by remember { mutableStateOf(ScreenState.HUB) }
            var activeBrowserUrl by remember { mutableStateOf("") }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                if (result.values.any { it }) {
                    Toast.makeText(context, "Permissions updated", Toast.LENGTH_SHORT).show()
                }
            }

            LaunchedEffect(Unit) {
                val perms = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                val pending = perms.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (pending.isNotEmpty()) {
                    permissionLauncher.launch(pending.toTypedArray())
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                    }
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF07090E))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF07090E)) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            slideInHorizontally(
                                initialOffsetX = { w -> if (targetState == ScreenState.HUB) -w else w },
                                animationSpec = tween(400)
                            ) togetherWith slideOutHorizontally(
                                targetOffsetX = { w -> if (targetState == ScreenState.HUB) w else -w },
                                animationSpec = tween(400)
                            )
                        },
                        label = "screen_transition"
                    ) { screen ->
                        when (screen) {
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
                                onDownload = { url, ua, cd, mime, referer -> downloadFile(url, ua, cd, mime, referer) }
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
                Gson().fromJson(InputStreamReader(stream), PlatformConfig::class.java).platforms
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimetype: String, referer: String) {
        val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
        
        if (url.lowercase().contains(".html") || url.lowercase().contains(".php") || filename.lowercase().endsWith(".html")) {
            Toast.makeText(this, "Blocked fake HTML ad page! Tap the real download link.", Toast.LENGTH_LONG).show()
            return
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "VaultGames")
        if (!targetDir.exists()) targetDir.mkdirs()

        val targetFile = File(targetDir, filename)
        val request = Request(url, targetFile.absolutePath).apply {
            priority = Priority.HIGH
            networkType = NetworkType.ALL
            enqueueAction = EnqueueAction.REPLACE_EXISTING
            addHeader("User-Agent", userAgent)
            addHeader("Referer", referer)
            addHeader("Accept-Encoding", "identity")
            val cookies = CookieManager.getInstance().getCookie(url)
            if (cookies != null) addHeader("Cookie", cookies)
        }

        try {
            var fetch = runCatching { Fetch.Impl.getDefaultInstance() }.getOrNull()
            if (fetch == null) {
                val config = FetchConfiguration.Builder(this).setDownloadConcurrentLimit(3).build()
                Fetch.Impl.setDefaultInstanceConfiguration(config)
                fetch = Fetch.Impl.getDefaultInstance()
            }

            fetch.enqueue(
                request,
                { Toast.makeText(this, "Queued: $filename", Toast.LENGTH_SHORT).show() },
                { Toast.makeText(this, "Host rejected connection", Toast.LENGTH_LONG).show() }
            )

            fetch.addListener(object : AbstractFetchListener() {
                override fun onCompleted(download: Download) {
                    if (download.file == targetFile.absolutePath) {
                        val gameFolder = File(targetDir, filename.substringBeforeLast("."))
                        VaultZipExtractor.extractLocalZip(targetFile, gameFolder) { success, message ->
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            }
                        }
                        fetch.removeListener(this)
                    }
                }
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Crash Reason: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
