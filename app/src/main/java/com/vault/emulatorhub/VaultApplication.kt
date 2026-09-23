package com.vault.emulatorhub

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration
import com.tonyodev.fetch2.AbstractFetchListener
import com.tonyodev.fetch2.Download
import java.io.File

class VaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val fetchConfiguration = FetchConfiguration.Builder(this)
            .setDownloadConcurrentLimit(3)
            .setAutoRetryMaxAttempts(10)
            .enableAutoStart(true)
            .enableLogging(false)
            .build()
            
        val fetch = Fetch.Impl.getInstance(fetchConfiguration)
        
        // Automatically purge fake HTML/ad redirect downloads (< 5MB)
        fetch.addListener(object : AbstractFetchListener() {
            override fun onCompleted(download: Download) {
                val file = File(download.file)
                if (file.exists() && file.length() < 5 * 1024 * 1024) {
                    file.delete()
                    fetch.delete(download.id)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "Blocked fake HTML ad redirect! Try another mirror.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
}
