package com.hisn.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hisn.app.notify.HisnNotifications
import com.hisn.app.scan.HygieneScanWorker
import java.util.concurrent.TimeUnit

class HisnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HisnNotifications.ensureChannel(this)

        // Scan roughly once a day in the background; KEEP so re-launches don't reset
        // the schedule.
        val periodic = PeriodicWorkRequestBuilder<HygieneScanWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "hygiene_scan", ExistingPeriodicWorkPolicy.KEEP, periodic
        )
    }
}
