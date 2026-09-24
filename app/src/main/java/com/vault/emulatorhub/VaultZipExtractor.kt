package com.vault.emulatorhub

import android.webkit.CookieManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object VaultZipExtractor {

    fun extractStreamToDirectory(
        fileUrl: String,
        targetDirectory: File,
        onProgress: ((String) -> Unit)? = null,
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs()
                }

                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection
                
                // Injects active WebView cookies so the server keeps the session alive if you leave the page
                val cookies = CookieManager.getInstance().getCookie(fileUrl)
                if (!cookies.isNullOrEmpty()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connection.setRequestProperty("Referer", url.protocol + "://" + url.host + "/")
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP Error: ${connection.responseCode}")
                }

                val bufferedStream = BufferedInputStream(connection.inputStream)
                bufferedStream.mark(4)
                val header = ByteArray(2)
                val readCount = bufferedStream.read(header)
                bufferedStream.reset()

                val isZip = readCount >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()

                if (isZip) {
                    ZipInputStream(bufferedStream).use { zipInputStream ->
                        var entry = zipInputStream.nextEntry
                        while (entry != null) {
                            val newFile = File(targetDirectory, entry.name)

                            val canonicalRootDir = targetDirectory.canonicalPath
                            val canonicalFilepath = newFile.canonicalPath
                            if (!canonicalFilepath.startsWith(canonicalRootDir)) {
                                throw SecurityException("Unsafe file entry detected: ${entry.name}")
                            }

                            if (entry.isDirectory) {
                                newFile.mkdirs()
                            } else {
                                newFile.parentFile?.mkdirs()
                                FileOutputStream(newFile).use { outputStream ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (zipInputStream.read(buffer).also { len = it } > 0) {
                                        outputStream.write(buffer, 0, len)
                                    }
                                }
                                onProgress?.invoke("Extracted: ${entry.name}")
                            }
                            zipInputStream.closeEntry()
                            entry = zipInputStream.nextEntry
                        }
                    }
                } else {
                    val fileName = fileUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "rom_game.bin" }
                    val targetFile = File(targetDirectory, fileName)
                    FileOutputStream(targetFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (bufferedStream.read(buffer).also { len = it } > 0) {
                            outputStream.write(buffer, 0, len)
                        }
                    }
                }

                bufferedStream.close()
                onComplete(true, "Download & Ready to Play!")
            } catch (e: Exception) {
                onComplete(false, e.message ?: "Unknown extraction error")
            }
        }.start()
    }
}
