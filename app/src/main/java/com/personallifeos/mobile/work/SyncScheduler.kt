package com.personallifeos.mobile.work

import android.content.Context
import androidx.work.*
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.widgets.WidgetUpdates
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "life-os-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        )
    }
    fun periodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "life-os-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online).build()
        )
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = LifeRepository.get(applicationContext)
        if (!repository.snapshot().connected) return Result.success()
        return try {
            repository.sync()
            WidgetUpdates.update(applicationContext)
            val snapshot = repository.snapshot()
            if (snapshot.error != null && snapshot.connected && !snapshot.blocked) Result.retry() else Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
