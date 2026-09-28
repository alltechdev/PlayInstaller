// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 alltechdev

package com.atd.vending

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the process alive while an install runs; the work itself stays in InstallerApplication. */
class InstallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Installation progress", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Installing ${intent?.getStringExtra(EXTRA_NAME) ?: "app"}")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "install"
        private const val ID = 1
        private const val EXTRA_NAME = "name"

        fun start(context: Context, appName: String) =
            context.startForegroundService(Intent(context, InstallService::class.java).putExtra(EXTRA_NAME, appName))

        fun stop(context: Context) = context.stopService(Intent(context, InstallService::class.java))
    }
}
