package gr.kingpool.tracker

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class VolumeCommandActivity : AppCompatActivity() {
    private data class PhoneHit(
        val name: String,
        val number: String,
        val typeLabel: String,
        val score: Int,
        val lastUsedAt: Long = 0L,
    )

    private var pendingContactQuery = ""
    private var pendingCallLogQuery = ""
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retryButton: Button

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else setIdleState("Χρειάζεται άδεια μικροφώνου για φωνητικές εντολές.")
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val query = pendingContactQuery
        pendingContactQuery = ""
        if (!granted) {
            Toast.makeText(this, "Χρειάζεται άδεια επαφών για φωνητική κλήση.", Toast.LENGTH_SHORT).show()
            setIdleState("Δεν δόθηκε άδεια επαφών.")
            return@registerForActivityResult
        }
        dialContactWithPermission(query)
    }

    private val callLogPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val query = pendingCallLogQuery
        pendingCallLogQuery = ""
        dialContactWithPermission(query, allowCallLogRanking = granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        buildVoiceUi()
        val deepLink = intent?.data
        if (deepLink?.scheme == "kingpool" && deepLink.host == "voice-call") {
            val query = deepLink.getQueryParameter("q").orEmpty().trim()
            if (query.isBlank()) setIdleState("Δεν δόθηκε όνομα επαφής.") else dialContact(query)
            return
        }
        if (savedInstanceState == null) ensureMicrophoneAndStart()
    }

    override fun onDestroy() {
        stopRecognizer()
        super.onDestroy()
    }

    private fun buildVoiceUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        val title = TextView(this).apply {
            text = "🎙 KingPool — φωνητική εντολή"
            textSize = 21f
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            text = "Ετοιμάζω το μικρόφωνο…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(8))
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        transcriptView = TextView(this).apply {
            text = ""
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), dp(18))
            minHeight = dp(84)
        }
        retryButton = Button(this).apply {
            text = "🎙 Άκου ξανά"
            setOnClickListener { ensureMicrophoneAndStart() }
        }
        val commandsButton = Button(this).apply {
            text = "📋 Όλες οι εντολές"
            setOnClickListener {
                stopRecognizer()
                openKingPool("/voice-command", mapOf("mode" to "commands"))
            }
        }
        val cancelButton = Button(this).apply {
            text = "Άκυρο"
            setOnClickListener { stopRecognizer(); finish() }
        }
        val hint = TextView(this).apply {
            text = "Οι φράσεις μπορούν να αλλάζουν από τη λίστα χωρίς νέο APK."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(title, matchWrap())
        root.addView(statusView, matchWrap())
        root.addView(progress, wrapWrap())
        root.addView(transcriptView, matchWrap())
        root.addView(retryButton, matchWrap(dp(6)))
        root.addView(commandsButton, matchWrap(dp(6)))
        root.addView(cancelButton, matchWrap(dp(6)))
        root.addView(hint, matchWrap())
        setContentView(root)
    }

    private fun ensureMicrophoneAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setIdleState("Δεν βρέθηκε υπηρεσία φωνητικής αναγνώρισης.")
            return
        }
        stopRecognizer()
        transcriptView.text = ""
        statusView.text = "Ακούω… μίλα τώρα"
        progress.visibility = View.VISIBLE
        retryButton.isEnabled = false
        listening = true
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { statusView.text = "Ακούω… μίλα τώρα" }
            override fun onBeginningOfSpeech() { statusView.text = "Σε ακούω…" }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { statusView.text = "Καταλαβαίνω την εντολή…" }
            override fun onError(error: Int) {
                listening = false
                recognizer?.destroy()
                recognizer = null
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Δεν κατάλαβα. Πάτησε «Άκου ξανά»."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Δεν άκουσα ομιλία. Πάτησε «Άκου ξανά»."
                    SpeechRecognizer.ERROR_AUDIO -> "Πρόβλημα μικροφώνου."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Πρόβλημα φωνητικής υπηρεσίας/δικτύου."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Δεν υπάρχει άδεια μικροφώνου."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Το μικρόφωνο είναι απασχολημένο. Δοκίμασε ξανά."
                    else -> "Δεν ολοκληρώθηκε η αναγνώριση. Πάτησε «Άκου ξανά»."
                }
                setIdleState(message)
            }
            override fun onResults(results: Bundle?) {
                listening = false
                progress.visibility = View.GONE
                retryButton.isEnabled = true
                val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                val text = alternatives.firstOrNull { ContactVoiceMatcher.isCallCommand(it) }
                    ?: alternatives.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    setIdleState("Δεν κατάλαβα. Πάτησε «Άκου ξανά».")
                    return
                }
                transcriptView.text = "«$text»"
                statusView.text = "Εκτέλεση…"
                executeCommand(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isNotBlank()) transcriptView.text = "«$text»"
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "el-GR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        runCatching { speech.startListening(request) }.onFailure {
            listening = false
            speech.destroy()
            recognizer = null
            setIdleState("Δεν ξεκίνησε το μικρόφωνο. Πάτησε «Άκου ξανά».")
        }
    }

    private fun stopRecognizer() {
        listening = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (::progress.isInitialized) progress.visibility = View.GONE
        if (::retryButton.isInitialized) retryButton.isEnabled = true
    }

    private fun setIdleState(message: String) {
        progress.visibility = View.GONE
        retryButton.isEnabled = true
        statusView.text = message
    }

    private fun executeCommand(raw: String) {
        stopRecognizer()
        if (ContactVoiceMatcher.isCallCommand(raw)) {
            dialContact(ContactVoiceMatcher.extractContactQuery(raw))
            return
        }
        openKingPool("/voice-command", mapOf("q" to raw))
    }

    private fun dialContact(query: String) {
        if (query.isBlank()) { setIdleState("Πες και το όνομα της επαφής."); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingContactQuery = query
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        dialContactWithPermission(query)
    }

    private fun dialContactWithPermission(query: String, allowCallLogRanking: Boolean = true) {
        val wanted = ContactVoiceMatcher.phoneticKey(query)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val hits = mutableListOf<PhoneHit>()
        runCatching {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    val number = cursor.getString(numberIndex).orEmpty().trim()
                    if (name.isBlank() || number.isBlank()) continue
                    val score = ContactVoiceMatcher.scoreKeys(wanted, ContactVoiceMatcher.phoneticKey(name))
                    if (score < ContactVoiceMatcher.MIN_SCORE) continue
                    val type = cursor.getInt(typeIndex)
                    val customLabel = cursor.getString(labelIndex)
                    val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, type, customLabel)
                        .toString().ifBlank { "Τηλέφωνο" }
                    hits += PhoneHit(name, number, typeLabel, score)
                }
            }
        }.onFailure {
            Toast.makeText(this, "Δεν μπόρεσα να διαβάσω τις επαφές.", Toast.LENGTH_SHORT).show()
            setIdleState("Δεν μπόρεσα να διαβάσω τις επαφές.")
            return
        }
        val topScore = hits.maxOfOrNull { it.score }
        val topHits = hits.filter { it.score == topScore }.distinctBy { canonicalPhoneKey(it.number) }
        if (topScore == null || topHits.isEmpty()) { setIdleState("Δεν βρέθηκε επαφή «$query»."); return }
        if (topHits.size == 1) { dialHit(topHits.single()); return }

        val hasCallLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        if (allowCallLogRanking && !hasCallLog) {
            pendingCallLogQuery = query
            callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
            return
        }
        val ordered = if (hasCallLog && allowCallLogRanking) rankByRecentCalls(topHits) else
            topHits.sortedWith(compareBy<PhoneHit> { ContactVoiceMatcher.phoneticKey(it.name) }
                .thenBy { it.typeLabel }.thenBy { canonicalPhoneKey(it.number) })
        showPhoneChooser(query, ordered)
    }

    private fun rankByRecentCalls(hits: List<PhoneHit>): List<PhoneHit> {
        val targetKeys = hits.map { canonicalPhoneKey(it.number) }.filter { it.isNotBlank() }.toSet()
        if (targetKeys.isEmpty()) return hits
        val lastUse = mutableMapOf<String, Long>()
        runCatching {
            contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                while (cursor.moveToNext() && lastUse.size < targetKeys.size) {
                    val key = canonicalPhoneKey(cursor.getString(numberIndex).orEmpty())
                    if (key in targetKeys && key !in lastUse) lastUse[key] = cursor.getLong(dateIndex)
                }
            }
        }
        return hits.map { it.copy(lastUsedAt = lastUse[canonicalPhoneKey(it.number)] ?: 0L) }
            .sortedWith(compareByDescending<PhoneHit> { it.lastUsedAt }
                .thenBy { ContactVoiceMatcher.phoneticKey(it.name) }
                .thenBy { it.typeLabel }
                .thenBy { canonicalPhoneKey(it.number) })
    }

    private fun showPhoneChooser(query: String, hits: List<PhoneHit>) {
        val labels = hits.mapIndexed { index, hit ->
            val recent = if (index == 0 && hit.lastUsedAt > 0L) "Πιο πρόσφατη κλήση • " else ""
            "$recent${hit.typeLabel}\n${hit.name} — ${hit.number}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Ποιον αριθμό για «$query»;")
            .setItems(labels) { _, which -> dialHit(hits[which]) }
            .setNegativeButton("Άκυρο") { _, _ -> setIdleState("Η κλήση ακυρώθηκε.") }
            .setOnCancelListener { setIdleState("Η κλήση ακυρώθηκε.") }
            .show()
    }

    private fun dialHit(hit: PhoneHit) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", hit.number, null))) }
            .onFailure { Toast.makeText(this, "Δεν άνοιξε η κλήση για ${hit.name}.", Toast.LENGTH_SHORT).show() }
        finish()
    }

    private fun canonicalPhoneKey(value: String): String {
        val digits = value.filter(Char::isDigit)
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    private fun openKingPool(path: String, params: Map<String, String> = emptyMap()) {
        val uri = Uri.parse("$WEB$path").buildUpon().apply { params.forEach { (key, value) -> appendQueryParameter(key, value) } }.build()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) }) }
            .onFailure { Toast.makeText(this, "Δεν άνοιξε το KingPool.", Toast.LENGTH_SHORT).show(); setIdleState("Δεν άνοιξε το KingPool."); return }
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { this.topMargin = topMargin }
    private fun wrapWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    companion object {
        private const val WEB = "https://kingpool-tracker.web.app"
        private const val NOTIFICATION_ID = 7301
    }
}
