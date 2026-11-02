package app.hushfeed.verification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/** Real external focus and playback, isolated from TikTok's process and UID. No network or UI. */
public final class FocusFixtureService extends Service {
    static final String TAG = "HushfeedFocusFixture";
    private static final String CHANNEL = "hushfeed_focus_fixture";
    private static final int NOTIFICATION = 47;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener listener = this::focusChanged;
    private AudioManager audio;
    private AudioFocusRequest request;
    private AudioTrack track;
    private boolean running, granted, requested;
    private long startedAt;
    private String run = "focus_check";

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notices = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Audio focus check",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            notices.createNotificationChannel(channel);
        }
        Notification.Builder notice = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = notice.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Audio focus check")
                .setContentText("Muted local fixture. Stops automatically.")
                .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW).setDefaults(0).setSound(null).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else startForeground(NOTIFICATION, notification);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        event("created uid=" + Process.myUid() + " pid=" + Process.myPid()
                + " targetSdk=" + getApplicationInfo().targetSdkVersion);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String op = intent == null ? "stop" : intent.getStringExtra("op");
        if (op == null) op = "start";
        try {
            if ("stop".equals(op)) finish("shell_stop");
            else if ("status".equals(op)) {
                status("status");
                if (!running) finish("idle_status");
            } else if ("start".equals(op)) {
                if (running) event("rejected already_running=true");
                else {
                    String label = intent.getStringExtra("run");
                    if (label != null) {
                        if (!label.matches("[A-Za-z0-9_]{1,40}")) throw new IllegalArgumentException("invalid run label");
                        run = label;
                    }
                    startFixture(PcmFocusFixture.duration(intent.getIntExtra("seconds", 25)));
                }
            } else throw new IllegalArgumentException("expected start, status or stop");
        } catch (RuntimeException failure) {
            event("failed kind=" + failure.getClass().getSimpleName());
            finish("failure");
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startFixture(int seconds) {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        byte[] pcm = PcmFocusFixture.oneSecond();
        track = new AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PcmFocusFixture.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length).setTransferMode(AudioTrack.MODE_STATIC).build();
        if (track.getState() != AudioTrack.STATE_NO_STATIC_DATA
                || track.setVolume(0f) != AudioTrack.SUCCESS
                || track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING) != pcm.length
                || track.setLoopPoints(0, PcmFocusFixture.SAMPLE_RATE, seconds - 1) != AudioTrack.SUCCESS) {
            throw new IllegalStateException("fixture track could not be prepared and muted");
        }
        startedAt = SystemClock.elapsedRealtime();
        running = true;
        // The finite PCM loop is a second bound. The watchdog also releases suspended focus.
        main.postDelayed(() -> finish("timeout"), seconds * 1000L);
        requested = true;
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(listener, main).build();
            result = audio.requestAudioFocus(request);
        } else result = audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        event("request result=" + result + " gain=transient usage=media muted=true seconds=" + seconds);
        if (!granted) { finish("focus_refused"); return; }
        track.play();
        status("started");
        main.postDelayed(heartbeat, 1000);
    }

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            status("tick");
            main.postDelayed(this, 1000);
        }
    };

    private void focusChanged(int change) {
        event("callback change=" + change);
        if (!running) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            granted = true;
            if (track != null) track.play();
        } else {
            granted = false;
            if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
            if (change == AudioManager.AUDIOFOCUS_LOSS) { finish("permanent_loss"); return; }
        }
        status("focus_changed");
    }

    private void status(String event) {
        event(event + " running=" + running + " granted=" + granted
                + " playState=" + (track == null ? -1 : track.getPlayState())
                + " positionFrames=" + (track == null ? 0 : Integer.toUnsignedLong(track.getPlaybackHeadPosition()))
                + " elapsedMs=" + (startedAt == 0 ? 0 : SystemClock.elapsedRealtime() - startedAt));
    }

    @SuppressWarnings("deprecation")
    private void finish(String reason) {
        main.removeCallbacksAndMessages(null);
        status("ending reason=" + reason);
        running = false;
        granted = false;
        if (track != null) {
            try { track.stop(); }
            catch (IllegalStateException ignored) { event("track_already_stopped=true"); }
            track.release();
            track = null;
        }
        if (requested && audio != null) {
            requested = false;
            int result = Build.VERSION.SDK_INT >= 26 && request != null
                    ? audio.abandonAudioFocusRequest(request) : audio.abandonAudioFocus(listener);
            request = null;
            event("released result=" + result + " reason=" + reason);
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void event(String value) { Log.i(TAG, "run=" + run + " " + value); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { finish("destroyed"); super.onDestroy(); }
}
