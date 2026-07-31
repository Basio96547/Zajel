package com.hisn.app.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Triggers an immediate hygiene scan when an app is installed/removed/replaced —
 * one of the "clear changes" worth checking right away (a new app might have just
 * gained a dangerous permission). The scan itself decides whether to notify.
 */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("Hisn", "PackageChangeReceiver: ${intent.action} ${intent.data} -> enqueue scan")
        WorkManager.getInstance(context.applicationContext)
            .enqueue(OneTimeWorkRequestBuilder<HygieneScanWorker>().build())
    }
}
