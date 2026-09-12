package com.personallifeos.mobile.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder

class WidgetComparisonAuthenticatorService : Service() {
    private lateinit var authenticator: WidgetComparisonAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = WidgetComparisonAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
