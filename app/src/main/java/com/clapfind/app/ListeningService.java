package com.clapfind.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.util.Log;

public class ListeningService extends Service {
    private static final String TAG = "ClapFind";
    volatile boolean listening = false;
    Thread worker;
    AudioRecord rec;
    ToneGenerator tone;
    CameraManager cam;
    String camId;
    Handler h = new Handler(Looper.getMainLooper());
    volatile boolean alarm = false;
    long lastRing = 0;
    int clapCount = 0;
    long lastClapAt = 0;
    long lastClapDetectedAt = 0;
    boolean whistleTriggered = false;
    double noiseRms = 0.004;

    final Runnable finishClapBurst = () -> {
        int count;
        synchronized (this) {
            count = clapCount;
            clapCount = 0;
            lastClapAt = 0;
        }
        updateNotification("Listening for claps or a whistle");
        if (count == 3) ring();
    };

    public void onCreate() {
        super.onCreate();
        try { tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100); } catch (Exception e) { Log.e(TAG, "Tone init", e); }
        cam = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for (String id : cam.getCameraIdList()) {
                Boolean flash = cam.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) { camId = id; break; }
            }
        } catch (Exception e) { Log.e(TAG, "Camera init", e); }
        if (Build.VERSION.SDK_INT >= 26) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel("cf", "ClapFind", NotificationManager.IMPORTANCE_LOW));
        }
    }

    public int onStartCommand(Intent in, int flags, int id) {
        String action = in == null ? "START" : in.getAction();
        if ("SILENCE".equals(action)) { silence(); return START_STICKY; }
        if ("STOP".equals(action)) {
            silence();
            listening = false;
            try { if (rec != null) rec.stop(); } catch (Exception ignored) {}
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = buildNotification("Listening for claps or a whistle");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Microphone-only foreground type: the camera/flash is optional.
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else startForeground(1, n);
        } catch (Exception e) {
            Log.e(TAG, "Could not start foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!listening) {
            listening = true;
            worker = new Thread(this::listen, "ClapFindAudio");
            worker.start();
        }
        return START_STICKY;
    }

    Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, "cf") : new Notification.Builder(this);
        return b.setContentTitle("ClapFind is ON")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build();
    }

    void updateNotification(String text) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, buildNotification(text)); }
        catch (Exception e) { Log.e(TAG, "Notification update", e); }
    }

    void listen() {
        int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { Log.e(TAG, "Unsupported AudioRecord format: " + min); updateNotification("Microphone format error — reopen app"); return; }
        try {
            // MIC is more widely supported across Android devices than VOICE_RECOGNITION.
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                updateNotification("Microphone failed to start — check permission");
                listening = false;
                return;
            }
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord is not recording");
                updateNotification("Microphone is not recording — check permission");
                listening = false;
                return;
            }
            updateNotification("Microphone active — clap 3 times or whistle");
            short[] buffer = new short[512]; // 32 ms at 16 kHz, catches short clap transients
            int whistleFrames = 0;
            while (listening) {
                int n = rec.read(buffer, 0, buffer.length);
                if (n < 1) { if (n < 0) Log.e(TAG, "AudioRecord read error: " + n); continue; }
                double sum = 0;
                int crossings = 0;
                int peak = 0;
                short prev = buffer[0];
                for (int i = 0; i < n; i++) {
                    int sample = buffer[i];
                    int abs = Math.abs(sample);
                    if (abs > peak) peak = abs;
                    double x = sample / 32768.0;
                    sum += x * x;
                    if ((sample >= 0 && prev < 0) || (sample < 0 && prev >= 0)) crossings++;
                    prev = buffer[i];
                }
                double rms = Math.sqrt(sum / n);
                double zcr = (double) crossings / n;
                long now = System.currentTimeMillis();

                // Detect a short, loud transient; no dependence on the previous frame's volume.
                boolean clap = peak >= 2800 && rms >= Math.max(0.009, noiseRms * 1.9)
                    && now - lastClapDetectedAt >= 230;
                if (clap) {
                    lastClapDetectedAt = now;
                    recordClap();
                } else if (rms < noiseRms * 3.0) {
                    noiseRms = noiseRms * 0.97 + rms * 0.03;
                    noiseRms = Math.max(0.002, Math.min(noiseRms, 0.04));
                }

                // Whistles usually have sustained energy and a relatively narrow frequency band.
                boolean whistleTone = rms >= Math.max(0.012, noiseRms * 2.4) && zcr > 0.035 && zcr < 0.28;
                whistleFrames = whistleTone ? whistleFrames + 1 : 0;
                if (whistleFrames >= 5 && !whistleTriggered) {
                    whistleTriggered = true;
                    ring();
                }
                if (!whistleTone) whistleTriggered = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Microphone listener stopped", e);
            updateNotification("Microphone error — reopen ClapFind");
            listening = false;
        } finally {
            try { if (rec != null) rec.release(); } catch (Exception ignored) {}
            rec = null;
        }
    }

    synchronized void recordClap() {
        long now = System.currentTimeMillis();
        if (lastClapAt == 0 || now - lastClapAt > 1600) clapCount = 0;
        clapCount++;
        lastClapAt = now;
        int shown = clapCount;
        updateNotification("Detected clap " + shown + "/3");
        h.removeCallbacks(finishClapBurst);
        h.postDelayed(finishClapBurst, 1600);
    }

    synchronized void ring() {
        if (alarm || System.currentTimeMillis() - lastRing < 4000) return;
        lastRing = System.currentTimeMillis();
        alarm = true;
        sendBroadcast(new Intent("com.clapfind.ALERT").setPackage(getPackageName()));
        h.post(() -> {
            try { if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 5000); }
            catch (Exception e) { Log.e(TAG, "Alarm sound", e); }
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(new long[]{0, 300, 150, 300, 150, 600}, -1);
            } catch (Exception e) { Log.e(TAG, "Vibration", e); }
            if (camId != null && checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                for (int j = 0; j < 8; j++) {
                    final boolean on = j % 2 == 0;
                    h.postDelayed(() -> { try { if (alarm) cam.setTorchMode(camId, on); } catch (Exception ignored) {} }, j * 250L);
                }
            }
        });
    }

    void silence() {
        alarm = false;
        try { if (tone != null) tone.stopTone(); } catch (Exception ignored) {}
        try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.cancel(); } catch (Exception ignored) {}
        try { if (camId != null) cam.setTorchMode(camId, false); } catch (Exception ignored) {}
        sendBroadcast(new Intent("com.clapfind.SILENCED").setPackage(getPackageName()));
    }

    public void onDestroy() {
        listening = false;
        h.removeCallbacks(finishClapBurst);
        silence();
        try { if (rec != null) rec.stop(); } catch (Exception ignored) {}
        try { if (tone != null) tone.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    public IBinder onBind(Intent i) { return null; }
}
