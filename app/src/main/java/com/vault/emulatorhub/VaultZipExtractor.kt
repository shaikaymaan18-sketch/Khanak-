package com.vault.emulatorhub

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
                // Set browser User-Agent to prevent server blocks/403 errors
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP Error: ${connection.responseCode}")
                }

                val bufferedStream = BufferedInputStream(connection.inputStream)
                bufferedStream.mark(4)
                val header = ByteArray(2)
                val readCount = bufferedStream.read(header)
                bufferedStream.reset()

                // Check if file is a ZIP archive by inspecting magic bytes ("PK" -> 0x50, 0x4B)
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
                    // Not a zip file (e.g. direct .gba, .nds), save it directly to the folder
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
