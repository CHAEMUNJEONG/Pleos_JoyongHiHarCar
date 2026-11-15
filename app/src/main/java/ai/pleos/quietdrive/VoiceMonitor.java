package ai.pleos.quietdrive;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;

/** A local, non-transcribing experimental speech-activity monitor. */
public final class VoiceMonitor implements AutoCloseable {
    public interface Listener {
        void onSpeech(boolean speech);
        void onStatus(String status);
        default void onStopped(String status) { }
    }

    private static final int SAMPLE_RATE = 16_000;
    private static final int SILENT_READ_LIMIT = 100;
    private static final long HANGOVER_MS = 600L;

    private final Object lock = new Object();
    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean inputObserved;
    private AudioRecord recorder;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private Thread worker;
    private String status = "마이크 감지 꺼짐";
    private boolean speech;
    private long captureGeneration;

    public VoiceMonitor(Context context, Listener listener) {
        if (context == null || listener == null) {
            throw new IllegalArgumentException("context and listener are required");
        }
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * Starts capture. The caller must already be a visible microphone foreground service.
     */
    public boolean start() {
        synchronized (lock) {
            if (running) return true;
            final long session = ++captureGeneration;
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                updateStatusLocked("마이크 권한 필요", session);
                return false;
            }
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null && audio.isMicrophoneMute()) {
                updateStatusLocked("시스템에서 마이크가 음소거됨", session);
                return false;
            }

            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                updateStatusLocked("마이크 버퍼를 만들 수 없음", session);
                return false;
            }
            int bufferBytes = Math.max(minimum, SAMPLE_RATE / 5 * 2);
            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();
                recorder = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferBytes)
                        .build();
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord not initialized");
                }
                String effectStatus = enableEffectsLocked(recorder.getAudioSessionId());
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("recording did not start");
                }
                running = true;
                inputObserved = false;
                speech = false;
                status = "실험용 마이크 감지 · " + effectStatus
                        + " · 음성 분리 정확도 보장 안 됨";
                postStatus(status, session);
                int samples = Math.max(320, bufferBytes / 2);
                AudioRecord sessionRecorder = recorder;
                worker = new Thread(() -> captureLoop(samples, session, sessionRecorder),
                        "QuietDrive-VoiceMonitor");
                worker.start();
                return true;
            } catch (RuntimeException error) {
                releaseLocked();
                updateStatusLocked("마이크 시작 실패 · " + error.getClass().getSimpleName(),
                        session);
                return false;
            }
        }
    }

    public void stop() {
        Thread joinTarget;
        long stopGeneration;
        synchronized (lock) {
            stopGeneration = ++captureGeneration;
            running = false;
            joinTarget = worker;
            worker = null;
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (IllegalStateException ignored) {
                    // A revoked or dead recorder can already be stopped.
                }
            }
            releaseLocked();
            setSpeechLocked(false, stopGeneration, true);
            updateStatusLocked("마이크 감지 꺼짐", stopGeneration);
        }
        if (joinTarget != null && joinTarget != Thread.currentThread()) {
            try {
                joinTarget.join(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean hasAudioInput() { return inputObserved; }

    public String status() {
        synchronized (lock) {
            return status;
        }
    }

    private void captureLoop(int sampleCount, long session, AudioRecord sessionRecorder) {
        short[] pcm = new short[sampleCount];
        double noiseFloor = 280.0;
        int onsetFrames = 0;
        int silentReads = 0;
        long lastVoiceAt = 0L;
        String terminalStatus = null;
        try {
            while (isSessionRunning(session)) {
                int count = sessionRecorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (!isSessionRunning(session)) break;
                if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                    terminalStatus = "마이크 장치 연결이 끊김";
                    break;
                }
                if (count < 0) {
                    terminalStatus = "마이크 읽기 실패 · " + count;
                    break;
                }
                if (count == 0) continue;

                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    terminalStatus = "마이크 권한이 해제됨";
                    break;
                }
                AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audio != null && audio.isMicrophoneMute()) {
                    terminalStatus = "시스템에서 마이크가 음소거됨";
                    break;
                }

                double sum = 0.0;
                int crossings = 0;
                int nonZero = 0;
                short previous = pcm[0];
                for (int i = 0; i < count; i++) {
                    short value = pcm[i];
                    double sample = value;
                    sum += sample * sample;
                    if (value != 0) nonZero++;
                    if (i > 0 && ((value >= 0) != (previous >= 0))) crossings++;
                    previous = value;
                }
                double rms = Math.sqrt(sum / count);
                double zeroCrossingRate = crossings / (double) Math.max(1, count - 1);
                if (nonZero == 0) {
                    silentReads++;
                    if (silentReads >= SILENT_READ_LIMIT) {
                        terminalStatus = "마이크에서 오디오가 수신되지 않음";
                        break;
                    }
                } else {
                    silentReads = 0;
                    synchronized (lock) { if (session == captureGeneration) inputObserved = true; }
                }

                double threshold = Math.max(650.0, noiseFloor * 2.8);
                boolean candidate = rms > threshold
                        && zeroCrossingRate >= 0.003 && zeroCrossingRate <= 0.55;
                long now = android.os.SystemClock.elapsedRealtime();
                if (candidate) {
                    onsetFrames++;
                    lastVoiceAt = now;
                } else {
                    onsetFrames = 0;
                    if (!speech) {
                        double bounded = Math.max(120.0, Math.min(3_000.0, rms));
                        noiseFloor = noiseFloor * 0.97 + bounded * 0.03;
                    }
                }
                synchronized (lock) {
                    if (session != captureGeneration) break;
                    if (onsetFrames >= 2) setSpeechLocked(true, session, false);
                    if (speech && now - lastVoiceAt >= HANGOVER_MS) {
                        setSpeechLocked(false, session, false);
                    }
                }
            }
        } catch (RuntimeException error) {
            if (isSessionRunning(session)) {
                terminalStatus = "마이크 처리 실패 · " + error.getClass().getSimpleName();
            }
        } finally {
            Arrays.fill(pcm, (short) 0);
            synchronized (lock) {
                if (session != captureGeneration) return;
                running = false;
                worker = null;
                releaseLocked();
                setSpeechLocked(false, session, true);
                if (terminalStatus != null) {
                    updateStatusLocked(terminalStatus, session);
                    postStopped(terminalStatus, session);
                }
            }
        }
    }

    private String enableEffectsLocked(int sessionId) {
        boolean aec = false;
        boolean ns = false;
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                aec = echoCanceler != null && echoCanceler.setEnabled(true) == 0
                        && echoCanceler.getEnabled();
            }
        } catch (RuntimeException ignored) {
            if (echoCanceler != null) echoCanceler.release();
            echoCanceler = null;
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                ns = noiseSuppressor != null && noiseSuppressor.setEnabled(true) == 0
                        && noiseSuppressor.getEnabled();
            }
        } catch (RuntimeException ignored) {
            if (noiseSuppressor != null) noiseSuppressor.release();
            noiseSuppressor = null;
        }
        return "AEC " + (aec ? "켜짐" : "없음") + " · NS " + (ns ? "켜짐" : "없음");
    }

    private void releaseLocked() {
        if (echoCanceler != null) {
            echoCanceler.release();
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }

    private void setSpeechLocked(boolean active, long session, boolean forceFinal) {
        if (speech == active && !forceFinal) return;
        speech = active;
        mainHandler.post(() -> {
            synchronized (lock) {
                if (captureGeneration != session) return;
            }
            listener.onSpeech(active);
        });
    }

    private void updateStatusLocked(String newStatus, long session) {
        status = newStatus;
        postStatus(newStatus, session);
    }

    private void postStatus(String newStatus, long session) {
        mainHandler.post(() -> {
            synchronized (lock) {
                if (captureGeneration != session) return;
            }
            listener.onStatus(newStatus);
        });
    }

    private void postStopped(String terminalStatus, long session) {
        mainHandler.post(() -> {
            synchronized (lock) {
                if (captureGeneration != session || running) return;
            }
            listener.onStopped(terminalStatus);
        });
    }

    private boolean isSessionRunning(long session) {
        synchronized (lock) {
            return running && captureGeneration == session;
        }
    }
}
