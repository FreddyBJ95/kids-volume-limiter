package dk.kidsvolume.limiter.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dk.kidsvolume.limiter.settings.SettingsRepository
import dk.kidsvolume.limiter.volume.VolumeLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository.get(applicationContext)
        if (!settings.current.enabled) return Result.success()
        settings.recordWatchdog()
        withContext(Dispatchers.Main) {
            VolumeLimiter.get(applicationContext).enforce("vagthund")
            if (!VolumeLimiterService.isRunning) {
                ServiceController.start(applicationContext, "genstartet af vagthund")
            }
        }
        return Result.success()
    }
}
