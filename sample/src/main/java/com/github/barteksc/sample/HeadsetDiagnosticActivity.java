package com.github.barteksc.sample;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HeadsetDiagnosticActivity extends Activity {
    private final StringBuilder log = new StringBuilder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView output;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private AudioTrack audioTrack;
    private Thread silentThread;
    private volatile boolean silentRunning;
    private ContentObserver volumeObserver;
    private int lastVolume = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        buildUi();
        createMediaSession();
        registerVolumeObserver();
        add("Έναρξη διάγνωσης · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE);
        add("Πάτησε «Αποκλειστική media δοκιμή» και μετά 1×, 2×, 3×, παρατεταμένο, + και −.");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Headset Diagnostic — Z3");
        title.setTextSize(22);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button start = new Button(this);
        start.setText("Αποκλειστική media δοκιμή");
        start.setOnClickListener(v -> startExclusive());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Σταμάτημα δοκιμής");
        stop.setOnClickListener(v -> stopExclusive("χειροκίνητα"));
        root.addView(stop);

        Button copy = new Button(this);
        copy.setText("Αντιγραφή log");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Headset diagnostic", log.toString()));
            add("Το log αντιγράφηκε.");
        });
        root.addView(copy);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(14);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);
        setContentView(root);
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "HeadsetDiagnostic");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(android.content.Intent intent) {
                KeyEvent event = intent.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT);
                if (event != null) logKey("MediaSession", event);
                else add("MediaSession · event χωρίς KeyEvent");
                return true;
            }
        });
    }

    private void startExclusive() {
        if (silentRunning) {
            add("Η αποκλειστική δοκιμή είναι ήδη ενεργή.");
            return;
        }
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> {
                    add("AudioFocus · change=" + change);
                    if (change == AudioManager.AUDIOFOCUS_LOSS) stopExclusive("audio focus loss");
                })
                .build();
        int focus = audioManager.requestAudioFocus(focusRequest);
        add("AudioFocus request · result=" + focus);

        int sampleRate = 44100;
        int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(min, 4096);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(buffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        PlaybackState ps = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build();
        mediaSession.setPlaybackState(ps);
        mediaSession.setActive(true);

        silentRunning = true;
        audioTrack.play();
        silentThread = new Thread(() -> {
            short[] zeros = new short[2048];
            while (silentRunning && audioTrack != null) {
                try {
                    audioTrack.write(zeros, 0, zeros.length, AudioTrack.WRITE_BLOCKING);
                } catch (Exception e) {
                    add("AudioTrack write error · " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    break;
                }
            }
        }, "HeadsetSilentAudio");
        silentThread.start();
        add("ΑΠΟΚΛΕΙΣΤΙΚΗ MEDIA ΔΟΚΙΜΗ: ΕΝΕΡΓΗ · MediaSession PLAYING + silent AudioTrack");
    }

    private void stopExclusive(String reason) {
        if (!silentRunning && (mediaSession == null || !mediaSession.isActive())) return;
        silentRunning = false;
        try {
            if (audioTrack != null) {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.stop();
                audioTrack.release();
            }
        } catch (Exception ignored) { }
        audioTrack = null;
        if (mediaSession != null) {
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
            mediaSession.setActive(false);
        }
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        add("Αποκλειστική media δοκιμή: STOP · " + reason);
    }

    private void registerVolumeObserver() {
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeObserver = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                int now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (now != lastVolume) {
                    add("AudioVolume · STREAM_MUSIC " + lastVolume + " → " + now);
                    lastVolume = now;
                }
            }
        };
        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        logKey("Activity", event);
        return super.dispatchKeyEvent(event);
    }

    private void logKey(String channel, KeyEvent e) {
        InputDevice d = e.getDevice();
        String device = d == null ? "unknown" :
                d.getName() + " · id=" + d.getId() + " · descriptor=" + d.getDescriptor() +
                        " · vendor=" + d.getVendorId() + " · product=" + d.getProductId();
        add(channel + " · " + actionName(e.getAction()) + " · " + KeyEvent.keyCodeToString(e.getKeyCode()) +
                " · repeat=" + e.getRepeatCount() + " · long=" + e.isLongPress() +
                " · scan=" + e.getScanCode() + " · source=0x" + Integer.toHexString(e.getSource()) +
                " · " + device);
    }

    private String actionName(int action) {
        if (action == KeyEvent.ACTION_DOWN) return "DOWN";
        if (action == KeyEvent.ACTION_UP) return "UP";
        if (action == KeyEvent.ACTION_MULTIPLE) return "MULTIPLE";
        return String.valueOf(action);
    }

    private void add(String text) {
        main.post(() -> {
            String line = clock.format(new Date()) + " · " + text;
            log.append(line).append('\n');
            if (output != null) output.setText(log.toString());
        });
    }

    @Override
    protected void onPause() {
        stopExclusive("Activity onPause");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopExclusive("Activity onDestroy");
        if (volumeObserver != null) getContentResolver().unregisterContentObserver(volumeObserver);
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }
}
