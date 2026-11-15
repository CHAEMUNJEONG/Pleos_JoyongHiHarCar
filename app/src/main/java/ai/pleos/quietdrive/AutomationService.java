package ai.pleos.quietdrive;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.function.Consumer;

/** Serializes all decisions on the main looper. Simulation never writes to a vehicle. */
public final class AutomationService extends Service {
    private static final int NOTIFICATION = 41;
    private final Handler handler = new Handler(Looper.getMainLooper());
    public final class LocalBinder extends Binder { public AutomationService service() { return AutomationService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private AudioPolicy policy;
    private AudioPolicy.Config config;
    private PositionPolicy position = new PositionPolicy(0, 0);
    private VehicleBridge vehicle;
    private VoiceMonitor voice;
    private Consumer<Snapshot> observer;
    private boolean simulation, paused, conversation, reversing, solo, speech, reverse, foreground;
    private int demoVolume = 28, volume = 28, maximum = 40, lastObserved = -1;
    private Integer pendingWrite;
    private int pendingPrevious;
    private long pendingSince;
    private boolean audioFault;
    private int mediaMinimum;
    private boolean vehicleReverse, vehicleParked;
    private double focus = .7;
    private String fault = "", micStatus = "마이크 꺼짐";
    private AudioPolicy.Output output;
    private boolean stopping;
    private long stopDeadline;

    public static final class Snapshot {
        public boolean simulation, paused, conversation, reversing, solo, speech, reverse, audioAvailable, gearAvailable, parked, manualOverride, changing, holding, microphoneRunning;
        public boolean microphoneInputObserved;
        public int volume, maximum, reasons;
        public double fade, balance, focus;
        public long silenceMs;
        public int conversationReduction, reverseReduction;
        public String audioStatus, gearStatus, micStatus, fault;
    }

    @Override public void onCreate() {
        super.onCreate();
        config = new AudioPolicy.Config();
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        config.conversationRemaining = 1 - p.getInt("conversationReduction", 65) / 100.0;
        config.reverseRemaining = 1 - p.getInt("reverseReduction", 75) / 100.0;
        config.silenceMs = p.getLong("silenceMs", 3000);
        focus = p.getFloat("focus", .7f);
        policy = new AudioPolicy(config);
        vehicle = new VehicleBridge(this, new VehicleBridge.Listener() {
            public void onGear(boolean r, boolean parked) {
                vehicleReverse = r; vehicleParked = parked;
                if (!simulation) { AutomationService.this.parked = parked; reverse = r; policy.reverse(r, now()); }
            }
            public void onFault(String message) {
                fault = message + " · 차량에서 음량을 직접 확인하세요.";
                // A lost signal is unknown, never a fabricated R exit.
            }
        });
        voice = new VoiceMonitor(this, new VoiceMonitor.Listener() {
            public void onSpeech(boolean active) {
                if (!simulation && conversation && !paused) { speech = active; policy.speech(active, now()); }
            }
            public void onStatus(String status) { micStatus = status; }
            public void onStopped(String status) {
                if (!simulation) {
                    conversation = false; speech = false; policy.setConversationEnabled(false);
                    micStatus = status + " · 감지 중단, 다시 켜기 필요";
                    if (foreground) ensureForeground(false);
                    settleIdle(); publish();
                }
            }
        });
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("automation", "오디오 자동 조절 상태", NotificationManager.IMPORTANCE_LOW));
        vehicle.connect();
        handler.post(loop);
    }

    private long now() { return SystemClock.elapsedRealtime(); }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "STOP".equals(intent.getAction())) stopAutomation();
        return START_NOT_STICKY;
    }

    private final Runnable loop = new Runnable() {
        public void run() {
            long time = now();
            int observed = simulation ? demoVolume : readVolume();
            boolean usable = simulation || (vehicle.canControlMedia() && !audioFault);
            if (usable) {
                maximum = simulation ? 40 : vehicle.maxVolume();
                mediaMinimum = simulation ? 0 : vehicle.minVolume();
                if (mediaMinimum < 0 || maximum < mediaMinimum || observed < mediaMinimum || observed > maximum) {
                    failAudio("차량 음량 범위를 확인할 수 없습니다. 차량에서 음량을 직접 확인하세요.");
                    publish(); handler.postDelayed(this, 80); return;
                }
                if (!simulation) detectManualChange(time, observed);
                output = policy.tickIfAvailable(true, time, observed - mediaMinimum, maximum - mediaMinimum);
                volume = observed;
                int physicalTarget = output.volume + mediaMinimum;
                if (physicalTarget != observed && (simulation || (!audioFault && pendingWrite == null))) {
                    if (simulation) { demoVolume = output.volume; volume = demoVolume; }
                    else {
                        try {
                            pendingPrevious = observed;
                            pendingWrite = physicalTarget;
                            pendingSince = time;
                            vehicle.setMediaVolume(physicalTarget);
                        } catch (RuntimeException e) { failAudio("미디어 제어가 중단되었습니다. 권한과 차량 연결을 확인하세요."); }
                    }
                }
            } else {
                output = policy.tickIfAvailable(false, time, -1, -1);
            }
            if (stopping && (time >= stopDeadline || (output != null && !output.changing && !output.ownsVolume))) {
                stopping = false;
                if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
                stopSelf();
            }
            publish();
            handler.postDelayed(this, 80);
        }
    };

    private int readVolume() {
        if (!vehicle.canControlMedia() || audioFault) return volume;
        try { int value = vehicle.volume(); if (value < 0) failAudio("차량 오디오 연결을 읽을 수 없습니다. 차량에서 음량을 직접 확인하세요."); return value < 0 ? volume : value; }
        catch (RuntimeException e) { failAudio("차량 오디오 연결을 읽을 수 없습니다."); return volume; }
    }

    private void detectManualChange(long time, int observed) {
        if (pendingWrite != null) {
            if (observed == pendingWrite) pendingWrite = null;
            else if (observed != pendingPrevious) { pendingWrite = null; policy.manualVolume(observed - mediaMinimum, time); }
            else if (time - pendingSince > 1000) failAudio("차량이 음량 변경을 확인하지 않았습니다. 자동 제어를 멈췄습니다.");
        } else if (lastObserved >= 0 && observed != lastObserved) policy.manualVolume(observed - mediaMinimum, time);
        lastObserved = observed;
    }

    private void failAudio(String message) { audioFault = true; pendingWrite = null; fault = message + " 차량에서 음량을 직접 확인하세요."; }

    public void observe(Consumer<Snapshot> listener) { observer = listener; publish(); }
    public void detach() { observer = null; }

    public void setSimulation(boolean enabled) {
        if (simulation == enabled) return;
        // Do not abandon a vehicle volume owned by the old controller during a mode switch.
        if (!simulation && output != null && output.ownsVolume) {
            fault = "먼저 자동 조절을 일시 중지하고 음량 복원이 끝난 뒤 시연으로 전환하세요.";
            publish(); return;
        }
        voice.stop();
        simulation = enabled;
        conversation = reversing = solo = speech = reverse = paused = false;
        if (!enabled) { reverse = vehicleReverse; parked = vehicleParked; }
        policy = new AudioPolicy(config);
        position = new PositionPolicy(0, 0);
        demoVolume = 28; volume = 28; maximum = 40;
        pendingWrite = null; lastObserved = -1; audioFault = false; mediaMinimum = 0; fault = "";
        output = null;
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false;
        stopping = false; stopSelf();
        micStatus = enabled ? "시연 입력 · 실제 마이크 꺼짐" : "마이크 꺼짐";
        publish();
    }

    public void setConversation(boolean enabled) {
        if (enabled && !simulation && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fault = "대화 감지에 마이크 권한이 필요합니다."; publish(); return;
        }
        conversation = enabled;
        policy.setConversationEnabled(enabled);
        if (enabled && !paused) {
            if (!ensureForeground(!simulation) || (!simulation && !voice.start())) {
                conversation = false; policy.setConversationEnabled(false);
                fault = "대화 감지를 시작하지 못했습니다. 마이크 상태를 확인하고 화면에서 다시 켜 주세요.";
                if (foreground) ensureForeground(false);
                settleIdle();
            }
        } else { voice.stop(); speech = false; policy.speech(false, now()); }
        if (!enabled) settleIdle();
        publish();
    }

    public void setReversing(boolean enabled) {
        if (enabled && !simulation && (!vehicle.canControlMedia() || !vehicle.canReadGear())) return;
        if (enabled && !ensureForeground(false)) { publish(); return; }
        reversing = enabled; policy.setReverseEnabled(enabled);
        if (enabled) policy.reverse(reverse, now());
        if (!enabled) settleIdle();
        publish();
    }

    public void setSolo(boolean enabled) {
        if (!simulation) { fault = "소리 위치 읽기·복원 API가 승인되지 않아 실제 적용을 사용할 수 없습니다."; publish(); return; }
        if (enabled && !ensureForeground(false)) { publish(); return; }
        solo = enabled;
        if (enabled && !paused) position.enter(focus); else position.leave();
        if (!enabled) settleIdle();
        publish();
    }

    public void setPaused(boolean value) {
        if (!value && (conversation || reversing || solo)) {
            if (!ensureForeground(conversation && !simulation)) { publish(); return; }
            if (conversation && !simulation && !voice.start()) {
                conversation = false; policy.setConversationEnabled(false);
                fault = "대화 감지를 다시 시작하지 못했습니다. 마이크 상태를 확인하세요.";
                if (foreground) ensureForeground(false);
                settleIdle();
            }
        }
        paused = value; policy.setPaused(value);
        if (value) { voice.stop(); speech = false; policy.speech(false, now()); position.suspend(); if (foreground) ensureForeground(false); }
        else {
            if (solo) { position.enter(focus); position.resume(); }
            if (reversing) policy.reverse(reverse, now());
            if (conversation && simulation) policy.speech(speech, now());
        }
        publish();
    }

    public void demoSpeech(boolean value) { if (simulation) { speech = value; policy.speech(value, now()); publish(); } }
    public void demoReverse(boolean value) { if (simulation) { reverse = value; policy.reverse(value, now()); publish(); } }
    public void demoVolume(int value) {
        if (simulation) { demoVolume = Math.max(0, Math.min(40, value)); policy.manualVolume(demoVolume, now()); publish(); }
    }
    public void demoPosition(double fade, double balance) {
        if (simulation) { position.manualFade(fade); position.manualBalance(balance); publish(); }
    }
    public void demoFade(double fade) { if (simulation) { position.manualFade(fade); publish(); } }
    public void demoBalance(double balance) { if (simulation) { position.manualBalance(balance); publish(); } }

    public void settings(int conversationReduction, int reverseReduction, int silenceSeconds, int focusPercent) {
        if (!(simulation ? !reverse : vehicle.canReadParking() && isParked())) return;
        config.conversationRemaining = 1 - Math.max(10, Math.min(90, conversationReduction)) / 100.0;
        config.reverseRemaining = 1 - Math.max(10, Math.min(90, reverseReduction)) / 100.0;
        config.silenceMs = Math.max(1, Math.min(10, silenceSeconds)) * 1000L;
        focus = Math.max(.1, Math.min(.9, focusPercent / 100.0));
        if (solo && !paused) position.enter(focus);
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putInt("conversationReduction", conversationReduction).putInt("reverseReduction", reverseReduction)
            .putLong("silenceMs", config.silenceMs).putFloat("focus", (float) focus).apply();
        publish();
    }

    private boolean isParked() { return parked; }
    private boolean parked;
    private void settleIdle() {
        if (!conversation && !reversing && !solo) {
            stopping = true; stopDeadline = now() + config.releaseMs + config.restoreDelayMs + 1500;
        } else if (foreground) ensureForeground(conversation && !simulation && !paused);
    }
    private boolean ensureForeground(boolean microphone) {
        try {
            // Keep the explicitly enabled session alive after the activity unbinds.
            startService(new Intent(this, AutomationService.class));
            microphone = microphone || (conversation && !simulation && !paused);
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Intent stop = new Intent(this, AutomationService.class).setAction("STOP");
            PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this, "automation")
                .setSmallIcon(R.drawable.ic_quiet_drive).setContentTitle(simulation ? "Quiet Drive · 화면 시연" : "Quiet Drive · 자동 조절")
                .setContentText(microphone ? "실험용 대화 감지 중 · 음성은 저장하지 않습니다" : "자동 조절 상태를 확인하려면 누르세요")
                .setContentIntent(content).setOngoing(true).addAction(new Notification.Action.Builder(null, "모두 중지", stopIntent).build()).build();
            int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
            if (microphone && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, type);
            else startForeground(NOTIFICATION, notification);
            foreground = true; stopping = false;
            return true;
        } catch (RuntimeException e) {
            fault = "화면에서 기능을 다시 켜 주세요. 백그라운드 시작이 허용되지 않았습니다.";
            if (!foreground) stopSelf();
            return false;
        }
    }

    public void stopAutomation() {
        conversation = reversing = solo = speech = false;
        paused = true;
        voice.stop(); policy.setConversationEnabled(false); policy.setReverseEnabled(false); policy.setPaused(true);
        position.leave(); stopping = true; stopDeadline = now() + config.releaseMs + config.restoreDelayMs + 1500;
        publish();
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.simulation = simulation; s.paused = paused; s.conversation = conversation; s.reversing = reversing;
        s.solo = solo; s.speech = speech; s.reverse = reverse; s.volume = simulation ? demoVolume : volume; s.maximum = maximum;
        s.audioAvailable = simulation || (vehicle.canControlMedia() && !audioFault);
        s.gearAvailable = simulation || vehicle.canReadGear();
        s.parked = simulation ? !reverse : vehicle.canReadParking() && parked;
        s.audioStatus = simulation ? "화면 시연 · 차량 음량 변경 없음" : vehicle.audioStatus();
        s.gearStatus = simulation ? (reverse ? "가상 기어 R" : "가상 기어 P") : vehicle.gearStatus();
        s.micStatus = simulation ? "시연 입력 · 실제 마이크 꺼짐" : micStatus;
        s.microphoneRunning = voice.isRunning();
        s.microphoneInputObserved = voice.hasAudioInput();
        s.fault = fault; s.focus = focus; s.fade = position.fade(); s.balance = position.balance();
        s.silenceMs = config.silenceMs;
        s.conversationReduction = (int) Math.round((1 - config.conversationRemaining) * 100);
        s.reverseReduction = (int) Math.round((1 - config.reverseRemaining) * 100);
        if (output != null) { s.reasons = output.reasons; s.holding = output.holding; s.changing = output.changing; s.manualOverride = output.manualOverride; }
        return s;
    }

    private void publish() { if (observer != null) observer.accept(snapshot()); }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        voice.close();
        if (!simulation && output != null && output.ownsVolume && vehicle.canControlMedia() && !audioFault) {
            int observed = vehicle.volume();
            // A controlled shutdown can restore only while readback still matches our write.
            if (observed >= mediaMinimum && (observed == volume || (pendingWrite != null && observed == pendingWrite))) {
                long time = now();
                policy.forceRelease(time, observed - mediaMinimum, maximum - mediaMinimum);
                AudioPolicy.Output restored = policy.tick(time + config.releaseMs + 1, observed - mediaMinimum, maximum - mediaMinimum);
                vehicle.setMediaVolume(restored.volume + mediaMinimum);
            }
        }
        vehicle.close();
        super.onDestroy();
    }
}
