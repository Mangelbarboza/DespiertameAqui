package com.angel.despiertameaqui

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlin.math.roundToInt

class LocationService : Service() {

    companion object {
        const val ACTION_PAUSE = "com.angel.despiertameaqui.PAUSE"
        const val ACTION_RESUME = "com.angel.despiertameaqui.RESUME"
        const val ACTION_STOP = "com.angel.despiertameaqui.STOP"
        const val PREFS_NAME = "trip_state"
        const val PREF_STATE = "state"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var radiusMeters: Float = 1000f
    private var isBatterySaving: Boolean = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    checkDistance(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                saveState("paused")
                updateNotification("Viaje pausado")
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                startLocationUpdates()
                saveState("running")
                updateNotification("Viaje reanudado. Calculando...")
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                saveState("idle")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        targetLat = intent?.getDoubleExtra("target_lat", 0.0) ?: 0.0
        targetLng = intent?.getDoubleExtra("target_lng", 0.0) ?: 0.0
        radiusMeters = intent?.getFloatExtra("radius", 1000f) ?: 1000f
        isBatterySaving = intent?.getBooleanExtra("battery_saving", false) ?: false
        saveState("running")

        val notification = createNotification("Buscando tu destino...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        startLocationUpdates()

        return START_NOT_STICKY
    }

    private fun startLocationUpdates() {
        val interval = if (isBatterySaving) 60000L else 10000L
        val fastestInterval = if (isBatterySaving) 30000L else 5000L

        // Dynamically choose priority based on granted permissions
        val hasFineLocation = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val priority = if (hasFineLocation) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        val locationRequest = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(fastestInterval)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (unlikely: SecurityException) {
            stopSelf()
        }
    }

    private fun checkDistance(currentLocation: Location) {
        val target = Location("").apply {
            latitude = targetLat
            longitude = targetLng
        }

        val distance = currentLocation.distanceTo(target)
        val speed = currentLocation.speed // m/s

        if (distance <= radiusMeters) {
            triggerAlarm()
            stopSelf()
        } else {
            val kmRemaining = (distance / 1000).roundToInt()
            val etaText = if (speed > 1.5) { // Más de 5 km/h aprox
                val minutes = (distance / speed / 60).roundToInt()
                "- Est. $minutes min"
            } else {
                ""
            }
            updateNotification("Faltan $kmRemaining km $etaText")
        }
    }

    private fun triggerAlarm() {
        saveState("idle")
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
        ringtone.play()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "alarm_channel"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Alarma de Destino", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("¡LLEGASTE!")
            .setContentText("Has entrado en el rango de tu destino.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2, notification)
        sendBroadcast(Intent("LOCATION_UPDATE").putExtra("trip_finished", true))
    }

    private fun saveState(state: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_STATE, state).apply()
    }

    private fun createNotification(content: String): Notification {
        val channelId = "location_service_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Seguimiento de Destino", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Despiértame Aquí")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification(content))

        // Enviar broadcast para actualizar la UI
        val intent = Intent("LOCATION_UPDATE")
        intent.putExtra("status_text", content)
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
