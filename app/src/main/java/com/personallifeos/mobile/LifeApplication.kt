package com.personallifeos.mobile

import android.app.Application
import com.personallifeos.mobile.work.SyncScheduler

class LifeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.periodic(this)
    }
}
