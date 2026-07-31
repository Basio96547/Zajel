package com.securemessenger.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * MessageCleanupService - Background service for cleaning up expired messages.
 * Runs periodically to delete self-destructing messages.
 */
class MessageCleanupService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Schedule periodic cleanup
        schedulePeriodicCleanup(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            try {
                val repository = (applicationContext as? com.securemessenger.app.SecureMessengerApp)?.repository
                repository?.deleteExpiredMessages()
            } catch (e: Exception) {
                // Log error silently
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val CLEANUP_INTERVAL_HOURS = 1L

        /**
         * Schedule periodic message cleanup using WorkManager.
         */
        fun schedulePeriodicCleanup(context: Context) {
            // NOTE: WorkManager forbids backoff criteria on an idle-mode job
            // ("Cannot set backoff criteria on an idle mode job"), so we set only
            // the constraints here. (Backoff applies to retries, which periodic
            // work doesn't use anyway.)
            val cleanupRequest = PeriodicWorkRequestBuilder<MessageCleanupWorker>(
                CLEANUP_INTERVAL_HOURS,
                TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder().apply {
                    setRequiresBatteryNotLow(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setRequiresDeviceIdle(true)
                    }
                }.build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "message_cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                cleanupRequest
            )
        }

    }
}

/**
 * WorkManager worker for message cleanup.
 */
class MessageCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val repository = (applicationContext as? com.securemessenger.app.SecureMessengerApp)?.repository
                val deletedCount = repository?.deleteExpiredMessages() ?: 0
                Result.success()
            } catch (e: Exception) {
                if (runAttemptCount >= 3) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }
}
