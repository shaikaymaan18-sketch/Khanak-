package com.vault.emulatorhub

import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

object VaultZipExtractor {

    /**
     * Streams a remote ZIP archive and extracts its contents directly to the target directory
     * on a background thread. The .zip file is never saved to internal storage.
     */
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
                val connection = url.openConnection()
                connection.connect()

                connection.getInputStream().use { inputStream ->
                    ZipInputStream(inputStream).use { zipInputStream ->
                        var entry = zipInputStream.nextEntry
                        while (entry != null) {
                            val newFile = File(targetDirectory, entry.name)

                            // Security check to prevent path traversal vulnerability (Zip Slip)
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
                }

                onComplete(true, "Extraction finished successfully. Zero zip footprint.")
            } catch (e: Exception) {
                onComplete(false, e.message ?: "Unknown extraction error occurred.")
            }
        }.start()
    }
}
