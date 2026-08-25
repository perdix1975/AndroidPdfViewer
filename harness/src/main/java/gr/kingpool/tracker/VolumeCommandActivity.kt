package gr.kingpool.tracker

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

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

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (text.isBlank()) { finish(); return@registerForActivityResult }
        executeCommand(text)
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val query = pendingContactQuery
        pendingContactQuery = ""
        if (!granted) {
            Toast.makeText(this, "Χρειάζεται άδεια επαφών για φωνητική κλήση.", Toast.LENGTH_SHORT).show()
            finish()
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
        if (savedInstanceState == null) startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "el-GR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Πες εντολή KingPool")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "Δεν βρέθηκε φωνητική αναγνώριση.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        speech.launch(intent)
    }

    private fun executeCommand(raw: String) {
        val command = normalize(raw)
        when {
            ContactVoiceMatcher.isCallCommand(raw) -> { dialContact(ContactVoiceMatcher.extractContactQuery(raw)); return }
            containsAny(command, "εργασια", "εργασιες", "εκκρεμοτητα") -> { openVoiceForm("task", raw); return }
            containsAny(command, "πληρωμη", "πληρωσα", "πληρωσε", "εδωσα", "εδωσε", "εξοδο") -> { openVoiceForm("expense", raw); return }
            containsAny(command, "ταμειο", "ταμεια") -> { openVoiceForm("collection", raw); return }
            containsAny(command, "εισπραξη", "πηρα", "πηρε", "εισεπραξα", "εισεπραξε", "μαζεψα", "μαζεψε") -> { openVoiceForm("auto", raw); return }
        }
        val path = when {
            containsAny(command, "μηχανημα", "μηχανηματα") -> "/machines"
            containsAny(command, "μαγαζι", "μαγαζια") -> "/shops"
            containsAny(command, "αναφορα", "αναφορες") -> "/reports"
            containsAny(command, "αρχικη", "kingpool", "κινγκπουλ") -> "/dashboard"
            else -> null
        }
        if (path == null) {
            Toast.makeText(this, "Δεν κατάλαβα την εντολή: «$raw»", Toast.LENGTH_LONG).show()
            finish(); return
        }
        openKingPool(path)
    }

    private fun openVoiceForm(intent: String, raw: String) {
        openKingPool("/voice-command", mapOf("intent" to intent, "q" to raw))
    }

    private fun dialContact(query: String) {
        if (query.isBlank()) {
            Toast.makeText(this, "Πες και το όνομα της επαφής.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
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
            finish(); return
        }

        val topScore = hits.maxOfOrNull { it.score }
        val topHits = hits.filter { it.score == topScore }.distinctBy { canonicalPhoneKey(it.number) }
        if (topScore == null || topHits.isEmpty()) {
            Toast.makeText(this, "Δεν βρέθηκε επαφή «$query».", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        if (topHits.size == 1) {
            dialHit(topHits.single())
            return
        }

        val hasCallLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        if (allowCallLogRanking && !hasCallLog) {
            pendingCallLogQuery = query
            callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
            return
        }

        val ordered = if (hasCallLog && allowCallLogRanking) {
            rankByRecentCalls(topHits)
        } else {
            topHits.sortedWith(compareBy<PhoneHit> { ContactVoiceMatcher.phoneticKey(it.name) }
                .thenBy { it.typeLabel }.thenBy { canonicalPhoneKey(it.number) })
        }
        showPhoneChooser(query, ordered)
    }

    private fun rankByRecentCalls(hits: List<PhoneHit>): List<PhoneHit> {
        val targetKeys = hits.map { canonicalPhoneKey(it.number) }.filter { it.isNotBlank() }.toSet()
        if (targetKeys.isEmpty()) return hits
        val lastUse = mutableMapOf<String, Long>()
        runCatching {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
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
            .setNegativeButton("Άκυρο") { _, _ -> finish() }
            .setOnCancelListener { finish() }
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
            .onFailure { Toast.makeText(this, "Δεν άνοιξε το KingPool.", Toast.LENGTH_SHORT).show() }
        finish()
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{M}+"), "").lowercase(Locale("el", "GR"))
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private fun containsAny(value: String, vararg words: String): Boolean {
        val tokens = value.split(' ')
        return words.any { word -> tokens.any { token -> token == word || token.startsWith(word) } }
    }

    companion object {
        private const val WEB = "https://kingpool-tracker.web.app"
        private const val NOTIFICATION_ID = 7301
    }
}
