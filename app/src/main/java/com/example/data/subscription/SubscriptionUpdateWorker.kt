package com.example.data.subscription

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.MaxNetDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.VpnRepository
import java.util.concurrent.TimeUnit

/**
 * WorkManager background worker that refreshes enabled subscriptions
 * while preserving manually added profiles.
 */
class SubscriptionUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val db = MaxNetDatabase.getInstance(applicationContext)
            val dataStore = SettingsDataStore(applicationContext)
            val repository = VpnRepository(applicationContext, db.maxNetDao(), dataStore)
            val subs = db.maxNetDao().getAllSubscriptionsSnapshot()
                .map { it.toDomain() }
                .filter { it.enabledAutoUpdate }

            for (sub in subs) {
                repository.refreshSubscription(sub)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "maxnet5g_subscription_auto_update"

        fun schedulePeriodicUpdates(context: Context, intervalHours: Long = 12L) {
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
                    intervalHours.coerceAtLeast(1L),
                    TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }
        }
    }
}
