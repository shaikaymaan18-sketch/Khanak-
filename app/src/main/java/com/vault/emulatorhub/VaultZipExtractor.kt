package com.vault.emulatorhub

import com.github.junrar.Junrar
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

object VaultZipExtractor {

    fun extractLocalZip(
        zipFile: File,
        targetDirectory: File,
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs()
                }

                if (zipFile.exists() && zipFile.length() > 4) {
                    val header = ByteArray(4)
                    FileInputStream(zipFile).use { fis ->
                        fis.read(header)
                    }

                    // Check magic bytes for ZIP ("PK" -> 0x50, 0x4B)
                    val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                    
                    // Check magic bytes for RAR ("Rar!" -> 0x52, 0x61, 0x72, 0x21)
                    val isRar = header[0] == 0x52.toByte() && 
                                header[1] == 0x61.toByte() && 
                                header[2] == 0x72.toByte() && 
                                header[3] == 0x21.toByte()

                    when {
                        isZip -> {
                            ZipInputStream(FileInputStream(zipFile)).use { zipInputStream ->
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
                                        newFile.outputStream().use { outputStream ->
                                            zipInputStream.copyTo(outputStream)
                                        }
                                    }
                                    zipInputStream.closeEntry()
                                    entry = zipInputStream.nextEntry
                                }
                            }
                            zipFile.delete()
                            onComplete(true, "Extracted Zip & Ready to Play!")
                        }
                        isRar -> {
                            // Extract RAR using Junrar
                            Junrar.extract(zipFile, targetDirectory)
                            zipFile.delete()
                            onComplete(true, "Extracted Rar & Ready to Play!")
                        }
                        else -> {
                            // Already a raw file (.gba, .nds, etc.)
                            onComplete(true, "Download Complete!")
                        }
                    }
                } else {
                    onComplete(false, "Downloaded file is empty")
                }
            } catch (e: Exception) {
                onComplete(false, e.message ?: "Extraction error")
            }
        }.start()
    }
}
