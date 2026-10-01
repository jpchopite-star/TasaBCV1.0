package com.tasabcv.widget

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RateUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            runCatching { MarketRepository.save(applicationContext, MarketRepository.fetch()) }
            RateRepository.save(applicationContext, RateRepository.fetch())
            RateWidgetProvider.refreshAll(applicationContext, error = false)
            Result.success()
        } catch (e: Exception) {
            RateWidgetProvider.refreshAll(applicationContext, error = true)
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "bcv_periodic"
        private const val NOW = "bcv_now"

        private val online = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Checks every 3 hours. BCV publishes the next day's rate in the afternoon, so this
         * picks up each new rate the same day, survives reboots, and costs almost no battery.
         */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RateUpdateWorker>(3, TimeUnit.HOURS)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RateUpdateWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
