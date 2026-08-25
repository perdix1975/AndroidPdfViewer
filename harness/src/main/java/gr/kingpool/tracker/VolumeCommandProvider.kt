package gr.kingpool.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class VolumeCommandProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { appContext ->
            VolumeCommandGesture.install(appContext)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private object VolumeCommandGesture {
    private const val CHANNEL_ID = "volume_command"
    private const val NOTIFICATION_ID = 7301
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    @Volatile private var installed = false
    private lateinit var context: Context
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = VolumeGestureRecognizer()
    private var lastVolume = -1
    private var ignoreRestoredVolume: Int? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            handleVolumeChange()
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (stream != -1 && stream != AudioManager.STREAM_MUSIC) return
            handleVolumeChange()
        }
    }

    @Synchronized
    fun install(appContext: Context) {
        if (installed) return
        context = appContext.applicationContext
        audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        ContextCompat.registerReceiver(
            context,
            volumeReceiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        ensureNotificationChannel()
        installed = true
    }

    private fun handleVolumeChange() {
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (lastVolume < 0) { lastVolume = current; return }
        if (current == lastVolume) return
        ignoreRestoredVolume?.let { expected ->
            if (current == expected) {
                ignoreRestoredVolume = null
                lastVolume = current
                recognizer.resetPending()
                return
            }
            ignoreRestoredVolume = null
        }
        val before = lastVolume
        lastVolume = current
        val trigger = recognizer.onVolumeChange(before, current, SystemClock.elapsedRealtime()) ?: return
        val originalVolume = trigger.originalVolume
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != originalVolume) {
            ignoreRestoredVolume = originalVolume
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) }
                .onFailure { ignoreRestoredVolume = null }
            lastVolume = originalVolume
        }
        launchCommandMode()
    }

    private fun launchCommandMode() {
        val commandIntent = Intent(context, VolumeCommandActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            commandIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kingpool_crown)
            .setContentTitle("KingPool — πες εντολή")
            .setContentText("Πάτησε για φωνητική εντολή αν δεν άνοιξε αυτόματα.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        runCatching { context.startActivity(commandIntent) }
    }

    private fun ensureNotificationChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Φωνητικές εντολές KingPool",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }
}
