package gr.kingpool.tracker

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

class VolumeCommandActivity : AppCompatActivity() {
    private data class PhoneHit(val name: String, val number: String, val score: Int)
    private var pendingContactQuery = ""

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

    private fun dialContactWithPermission(query: String) {
        val wanted = ContactVoiceMatcher.phoneticKey(query)
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val hits = mutableListOf<PhoneHit>()
        runCatching {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    val number = cursor.getString(numberIndex).orEmpty().trim()
                    if (name.isBlank() || number.isBlank()) continue
                    val score = ContactVoiceMatcher.scoreKeys(wanted, ContactVoiceMatcher.phoneticKey(name))
                    if (score >= ContactVoiceMatcher.MIN_SCORE) hits += PhoneHit(name, number, score)
                }
            }
        }.onFailure {
            Toast.makeText(this, "Δεν μπόρεσα να διαβάσω τις επαφές.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val topScore = hits.maxOfOrNull { it.score }
        val topHits = hits.filter { it.score == topScore }
            .distinctBy { ContactVoiceMatcher.phoneticKey(it.name) to it.number.filter(Char::isDigit) }
        if (topScore == null || topHits.isEmpty()) {
            Toast.makeText(this, "Δεν βρέθηκε επαφή «$query».", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        if (topHits.size > 1) {
            Toast.makeText(this, "Βρέθηκαν πολλές επαφές για «$query». Πες πιο συγκεκριμένο όνομα.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val hit = topHits.single()
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", hit.number, null))) }
            .onFailure { Toast.makeText(this, "Δεν άνοιξε η κλήση για ${hit.name}.", Toast.LENGTH_SHORT).show() }
        finish()
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
