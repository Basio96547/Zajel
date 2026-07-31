package com.hisn.app.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Runs a hygiene scan in the background (periodic 24h, or on a package change) and
 * notifies only if something meaningful changed.
 */
class HygieneScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        android.util.Log.i("Hisn", "worker: scan start")
        val outcome = HygieneScanner.scanAndRecord(applicationContext, notify = true)
        android.util.Log.i("Hisn", "worker: done, change=${outcome.change}")
        Result.success()
    } catch (e: Exception) {
        android.util.Log.e("Hisn", "worker failed", e)
        Result.retry()
    }
}
