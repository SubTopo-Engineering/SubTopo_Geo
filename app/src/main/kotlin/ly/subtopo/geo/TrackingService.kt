package ly.subtopo.geo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File

/**
 * Mémoire tampon des positions GPS, enregistrée sur disque.
 * Elle survit à l'extinction de l'écran et à la fermeture de l'application.
 * L'application web relit les positions manquantes à la réouverture (fixesSince) puis les acquitte (ack).
 */
object FixStore {
    @Volatile
    var listener: ((String) -> Unit)? = null
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "fixes.jsonl")

    fun append(ctx: Context, loc: Location): String {
        synchronized(lock) {
            val prefs = ctx.getSharedPreferences("fixstore", Context.MODE_PRIVATE)
            val seq = prefs.getLong("seq", 0L) + 1L
            prefs.edit().putLong("seq", seq).apply()
            val o = JSONObject()
            o.put("seq", seq)
            o.put("lat", loc.latitude)
            o.put("lon", loc.longitude)
            if (loc.hasAltitude()) o.put("alt", loc.altitude) else o.put("alt", JSONObject.NULL)
            o.put("acc", if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0)
            o.put("spd", if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
            if (loc.hasBearing()) o.put("hd", loc.bearing.toDouble()) else o.put("hd", JSONObject.NULL)
            o.put("t", loc.time)
            val line = o.toString()
            file(ctx).appendText(line + "\n")
            return line
        }
    }

    /** Retourne un tableau JSON des positions dont le numéro de séquence est supérieur à [since]. */
    fun since(ctx: Context, since: Long): String {
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) return "[]"
            val sb = StringBuilder("[")
            var first = true
            f.forEachLine { line ->
                if (line.isNotBlank()) {
                    val seq = try {
                        JSONObject(line).getLong("seq")
                    } catch (e: Exception) {
                        -1L
                    }
                    if (seq > since) {
                        if (!first) sb.append(',')
                        sb.append(line)
                        first = false
                    }
                }
            }
            sb.append(']')
            return sb.toString()
        }
    }

    /** Supprime les positions déjà reprises par l'application (numéro de séquence <= [upTo]). */
    fun ack(ctx: Context, upTo: Long) {
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) return
            val keep = f.readLines().filter { line ->
                line.isNotBlank() && (try {
                    JSONObject(line).getLong("seq")
                } catch (e: Exception) {
                    0L
                }) > upTo
            }
            if (keep.isEmpty()) f.delete() else f.writeText(keep.joinToString("\n") + "\n")
        }
    }
}

/**
 * Service au premier plan : garde le GPS actif, même écran éteint ou application fermée,
 * et enregistre chaque position dans [FixStore]. Une notification permanente est affichée.
 */
class TrackingService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubTopoGeo:tracking")
        wakeLock?.acquire(12L * 60L * 60L * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        recording = intent?.getBooleanExtra(EXTRA_REC, recording) ?: recording
        val notification = buildNotification(recording)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        try {
            lm.removeUpdates(this)
            var started = false
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                started = true
            }
            if (!started && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun buildNotification(rec: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, TrackingService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gps)
            .setContentTitle(getString(if (rec) R.string.notif_rec else R.string.notif_gps))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onLocationChanged(location: Location) {
        val line = FixStore.append(this, location)
        FixStore.listener?.invoke(line)
    }

    @Deprecated("Requis sur les anciennes versions d'Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {
    }

    override fun onProviderDisabled(provider: String) {
    }

    override fun onDestroy() {
        try {
            lm.removeUpdates(this)
        } catch (e: Exception) {
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "ly.subtopo.geo.STOP"
        const val EXTRA_REC = "rec"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 42

        @Volatile
        var running = false

        @Volatile
        var recording = false
    }
}
