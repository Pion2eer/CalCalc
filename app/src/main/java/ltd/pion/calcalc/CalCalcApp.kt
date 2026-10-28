package ltd.pion.calcalc

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ltd.pion.calcalc.sync.SyncWorker
import ltd.pion.calcalc.update.UpdateWorker
import java.util.concurrent.TimeUnit

class CalCalcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniquePeriodicWork(
            "update-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).setConstraints(online).build(),
        )
        wm.enqueueUniquePeriodicWork(
            "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
        SyncWorker.enqueue(this)
    }
}
