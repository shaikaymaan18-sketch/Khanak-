package com.vault.emulatorhub

import android.app.Application
import com.tonyodev.fetch2.Fetch
import com.tonyodev.fetch2.FetchConfiguration

class VaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val fetchConfiguration = FetchConfiguration.Builder(this)
            .setDownloadConcurrentLimit(3)
            .setAutoRetryMaxAttempts(10)
            .enableAutoStart(true)
            .enableLogging(false)
            .build()
            
        Fetch.Impl.setDefaultInstanceConfiguration(fetchConfiguration)
    }
}

