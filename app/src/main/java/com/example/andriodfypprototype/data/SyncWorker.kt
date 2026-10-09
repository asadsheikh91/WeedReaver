package com.example.andriodfypprototype.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.andriodfypprototype.data.net.ApiException
import com.example.andriodfypprototype.data.net.OfflineException
import java.util.concurrent.TimeUnit

/**
 * Runs [Store.sync] in the background whenever the phone has a network: shortly after each
 * write (debounced, leaving room for an undo), and every 15 minutes to pick up what the
 * dashboard published. Without signal WorkManager simply waits; nothing is lost.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        if (!Store.hasSession) return Result.success()
        if (applicationContext.getSharedPreferences("wr_prefs", Context.MODE_PRIVATE).getBoolean("workOffline", false)) {
            return Result.success()
        }
        return try {
            val r = Store.sync()
            Log.i("WeedReaverSync", "Background sync: ${r.applied} applied, ${r.duplicates} duplicate, ${r.rejected} rejected, ${r.photos} photos")
            Result.success()
        } catch (e: OfflineException) {
            Result.retry()
        } catch (e: ApiException) {
            if (e.endsSession || e.status == 401 || e.status == 403) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Log.w("WeedReaverSync", "Background sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val NOW = "wr-sync"
        private const val PERIODIC = "wr-sync-periodic"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun scheduleNow(context: Context, delaySeconds: Long) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Replacing is safe: a push that is cut off is resent and the station answers "duplicate".
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
