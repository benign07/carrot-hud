package com.carrot.hud

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object ArchiveJobs {
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context) {
        if (!ArchiveConfig.enabled(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("carrot-archive-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ArchiveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun request(context: Context) {
        if (!ArchiveConfig.enabled(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork("carrot-archive-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ArchiveWorker>().setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("carrot-archive-now")
        WorkManager.getInstance(context).cancelUniqueWork("carrot-archive-periodic")
    }
}

class ArchiveWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (!ArchiveConfig.enabled(applicationContext)) return Result.success()
        // The OP wait must never prevent uploading already archived records.
        val uploaded = PcArchive.sync(applicationContext) { isStopped || !ArchiveConfig.enabled(applicationContext) }
        if (!isStopped && ArchiveConfig.enabled(applicationContext)) {
            DriveArchive.syncOnce(applicationContext) { isStopped || !ArchiveConfig.enabled(applicationContext) }
        }
        return if (uploaded) Result.success() else Result.retry()
    }
}
