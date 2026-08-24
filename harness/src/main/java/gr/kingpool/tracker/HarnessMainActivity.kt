package gr.kingpool.tracker

import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Μικρή οθόνη αποκλειστικά για device test του volume trigger.
 * Δεν έχει Firebase, KingPool δεδομένα ή media session.
 */
class HarnessMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audio = getSystemService(AudioManager::class.java)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "KingPool Volume Trigger — δοκιμή"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "\n1. Άφησε το Spotify να παίζει.\n" +
                "2. Κάνε γρήγορα + − + − ή − + − +.\n" +
                "3. Η ένταση πρέπει να επιστρέψει εκεί που ήταν.\n" +
                "4. Πρέπει να ανοίξει το μικρόφωνο: πες π.χ. «κάλεσε την Klairi».\n\n" +
                "Τα Play/Pause/Next/Previous δεν χρησιμοποιούνται από αυτή την εφαρμογή."
            textSize = 17f
        })
        root.addView(TextView(this).apply {
            text = "\nΤρέχουσα ένταση media: ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}"
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "Άμεση δοκιμή μικροφώνου"
            setOnClickListener {
                startActivity(android.content.Intent(this@HarnessMainActivity, VolumeCommandActivity::class.java))
            }
        })
        root.addView(TextView(this).apply {
            text = "\nΠροσωρινό diagnostic build — δεν περιέχει ιδιωτικά δεδομένα KingPool."
            textSize = 13f
        })

        setContentView(root)
    }
}
