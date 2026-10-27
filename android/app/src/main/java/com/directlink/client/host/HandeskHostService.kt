package com.directlink.client.host

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.directlink.client.MainActivity
import com.directlink.client.R

class HandeskHostService : Service() {

    companion object {
        const val ACTION_START = "com.directlink.client.host.START"
        const val ACTION_STOP = "com.directlink.client.host.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        private const val CHANNEL_ID = "handesk_host_service_channel"
        private const val NOTIFICATION_ID = 21118

        @Volatile
        var isHostRunning: Boolean = false
            private set

        fun getDeviceIps(): Pair<String?, String?> {
            var tsIp: String? = null
            var lanIp: String? = null
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("100.")) {
                                tsIp = ip
                            } else if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                if (lanIp == null) lanIp = ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return Pair(tsIp, lanIp)
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, HandeskHostService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, HandeskHostService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var screenEncoder: ScreenEncoder? = null
    private var udpHostServer: UdpHostServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_STOP) {
            stopHost()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }

            if (resultCode != Activity.RESULT_OK || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            startForegroundWithNotification()
            startScreenCaptureAndServer(resultCode, data)
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Handesk Host Active")
            .setContentText("Screen sharing and remote control active on port 21118")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenCaptureAndServer(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            stopSelf()
            return
        }

        try {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopHost()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Exception) {}

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        val encWidth = (width / 16) * 16
        val encHeight = (height / 16) * 16

        screenEncoder = ScreenEncoder(mediaProjection!!, encWidth, encHeight, dpi) { nalu ->
            udpHostServer?.broadcastVideoFrame(nalu)
        }
        screenEncoder?.start()

        val isHevc = screenEncoder?.isHevc ?: true
        udpHostServer = UdpHostServer(encWidth, encHeight, isHevc) {
            screenEncoder?.requestSyncFrame()
        }
        udpHostServer?.start()

        isHostRunning = true
    }

    private fun stopHost() {
        isHostRunning = false
        try {
            screenEncoder?.stop()
        } catch (_: Exception) {}
        screenEncoder = null

        try {
            udpHostServer?.stop()
        } catch (_: Exception) {}
        udpHostServer = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }

    override fun onDestroy() {
        stopHost()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Handesk Host Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Handesk screen sharing active in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
