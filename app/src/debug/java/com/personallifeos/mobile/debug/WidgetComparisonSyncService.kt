package com.personallifeos.mobile.debug

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

/** Deliberately no-op: the Activity owns all synthetic CalendarProvider rows. */
private class WidgetComparisonSyncAdapter(context: android.content.Context) :
    AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) = Unit
}

class WidgetComparisonSyncService : Service() {
    private lateinit var adapter: WidgetComparisonSyncAdapter

    override fun onCreate() {
        super.onCreate()
        adapter = WidgetComparisonSyncAdapter(this)
    }

    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}
