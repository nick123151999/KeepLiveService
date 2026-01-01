/**
 * ============================================================================
 * FwForegroundService.kt - 核心前台服务
 * ============================================================================
 *
 * 功能简介：
 *   保活框架的核心前台服务，具备以下特性：
 *   - 持久通知栏显示
 *   - MediaSession 媒体会话（让系统认为是媒体应用）
 *   - WakeLock 唤醒锁
 *   - START_STICKY 自动重启
 *   - 被销毁时触发自救机制
 *
 * @author Pangu-Immortal
 * @github https://github.com/Pangu-Immortal/KeepLiveService
 * @since 2.1.0
 */
package com.service.framework.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import android.support.v4.media.session.MediaSessionCompat
import com.service.framework.Fw
import com.service.framework.R
import com.service.framework.util.FwLog
import com.service.framework.util.ServiceStarter

@SuppressLint("WakelockTimeout")
class FwForegroundService : LifecycleService() {

    companion object {
        const val EXTRA_START_REASON = "start_reason"
        private const val NOTIFICATION_ID = 10001
        private const val ALIVE_NOTIFICATION_ID = 10002
        private const val CHECK_INTERVAL = 5000L // 5秒
    }

    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 存活检测相关
    private val aliveHandler = Handler(Looper.getMainLooper())
    private var aliveCheckRunnable: Runnable? = null
    private var checkCount = 0
    private var startTime = 0L
    private var totalSeconds = 0

    override fun onCreate() {
        super.onCreate()
        FwLog.d("FwForegroundService initializing...")

        startForegroundWithNotification()

        if (Fw.config.enableMediaSession) {
            mediaSession = createMediaSession()
            mediaSession?.isActive = true
        }
        wakeLock = createWakeLock()
        acquireWakeLock()
        
        // 启动存活检测
        startAliveCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val reason = intent?.getStringExtra(EXTRA_START_REASON) ?: "未知原因"
        FwLog.d("Service started or restarted. Reason: $reason")
        startForegroundWithNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        FwLog.w("FwForegroundService is being destroyed.")

        stopAliveCheck()
        releaseMediaSession()
        releaseWakeLock()

        ServiceStarter.startForegroundService(this, "服务被杀后自救")
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            FwLog.d("Service promoted to foreground successfully.")
        } catch (e: Exception) {
            FwLog.e("Failed to start foreground service", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Fw.config.notificationChannelId,
                Fw.config.notificationChannelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持后台服务运行"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val config = Fw.config

        val pendingIntent = config.notificationActivityClass?.let {
            val intent = Intent(this, it).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(this, 0, intent, flags)
        }

        return NotificationCompat.Builder(this, config.notificationChannelId)
            .setContentTitle(config.notificationTitle)
            .setContentText(config.notificationContent)
            .setSmallIcon(config.notificationIconResId)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createMediaSession(): MediaSessionCompat {
        FwLog.d("Creating MediaSession...")
        return MediaSessionCompat(this, "FwMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
    }

    private fun releaseMediaSession() {
        mediaSession?.let {
            it.release()
            FwLog.d("MediaSession released.")
        }
        mediaSession = null
    }

    private fun createWakeLock(): PowerManager.WakeLock {
        FwLog.d("Creating WakeLock...")
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fw::WakeLock").apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireWakeLock() {
        try {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(10 * 60 * 1000L) // 持有 10 分钟超时
                    FwLog.d("WakeLock acquired.")
                }
            }
        } catch (e: Exception) {
            FwLog.e("Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    FwLog.d("WakeLock released.")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            FwLog.e("Failed to release WakeLock", e)
        }
    }

    /**
     * 启动存活检测任务
     */
    private fun startAliveCheck() {
        startTime = System.currentTimeMillis()
        totalSeconds = 0
        checkCount = 0
        createAliveNotificationChannel()
        
        aliveCheckRunnable = object : Runnable {
            override fun run() {
                checkCount++
                totalSeconds += 5
                
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                
                // 显示Toast
                showAliveToast(timeStr)
                
                // 发送通知
                sendAliveNotification(timeStr)
                
                // 记录日志
                FwLog.d("存活检测 #$checkCount - 运行时长: $timeStr")
                
                // 5秒后再次执行
                aliveHandler.postDelayed(this, CHECK_INTERVAL)
            }
        }
        
        // 立即执行第一次
        aliveHandler.post(aliveCheckRunnable!!)
        FwLog.i("存活检测已启动，每${CHECK_INTERVAL / 1000}秒检测一次")
    }

    /**
     * 停止存活检测任务
     */
    private fun stopAliveCheck() {
        aliveCheckRunnable?.let {
            aliveHandler.removeCallbacks(it)
            FwLog.i("存活检测已停止，总运行时长: ${formatTime(totalSeconds)}")
        }
        aliveCheckRunnable = null
    }

    /**
     * 创建存活通知渠道
     */
    private fun createAliveNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "fw_alive_channel",
                "服务存活通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "显示服务存活状态和运行时长"
                setSound(null, null)
                enableLights(true)
                enableVibration(false)
                setShowBadge(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 显示存活Toast
     */
    private fun showAliveToast(timeStr: String) {
        try {
            val message = "✅ 服务存活 #$checkCount | 运行时长: $timeStr"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FwLog.e("Failed to show alive toast", e)
        }
    }

    /**
     * 发送存活通知
     */
    private fun sendAliveNotification(timeStr: String) {
        try {
            val config = Fw.config
            
            val pendingIntent = config.notificationActivityClass?.let {
                val intent = Intent(this, it).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                PendingIntent.getActivity(this, 0, intent, flags)
            }

            val notification = NotificationCompat.Builder(this, "fw_alive_channel")
                .setContentTitle("🟢 服务存活通知 #$checkCount")
                .setContentText("已运行: $timeStr")
                .setSmallIcon(config.notificationIconResId)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ALIVE_NOTIFICATION_ID, notification)
            
        } catch (e: Exception) {
            FwLog.e("Failed to send alive notification", e)
        }
    }

    /**
     * 格式化时间
     */
    private fun formatTime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
}
