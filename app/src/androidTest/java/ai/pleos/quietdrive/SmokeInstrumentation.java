package ai.pleos.quietdrive;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Device tests against the real bound service; all audio scenarios explicitly use simulation. */
public final class SmokeInstrumentation extends Instrumentation {
    private AutomationService service;
    private int checks;
    private String scenario;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { service = ((AutomationService.LocalBinder)binder).service(); connected.countDown(); }
        public void onServiceDisconnected(ComponentName name) { service = null; }
    };
    @Override public void onCreate(Bundle args) { super.onCreate(args); scenario = args == null ? "policies" : args.getString("scenario", "policies"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent open = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivitySync(open);
            runOnMainSync(() -> getTargetContext().bindService(new Intent(getTargetContext(), AutomationService.class),connection,Context.BIND_AUTO_CREATE));
            if (!connected.await(10,TimeUnit.SECONDS)) throw new AssertionError("service bind timeout");
            if ("gear".equals(scenario)) {
                long deadline=SystemClock.elapsedRealtime()+10000;
                while(!snapshot().gearAvailable&&SystemClock.elapsedRealtime()<deadline)waitFor(80);
                check(snapshot().gearAvailable,"normal AAOS gear permission connects to the emulator");
                deadline=SystemClock.elapsedRealtime()+20000;
                while(!snapshot().reverse&&SystemClock.elapsedRealtime()<deadline)waitFor(80);
                check(snapshot().reverse,"Virtual Console R reaches the actual app callback");
                result.putString("reverseSource",snapshot().gearStatus);
                deadline=SystemClock.elapsedRealtime()+15000;
                while(!snapshot().parked&&SystemClock.elapsedRealtime()<deadline)waitFor(80);
                check(snapshot().parked&&!snapshot().reverse,"Virtual Console Park reaches the actual app callback");
                result.putString("parkSource",snapshot().gearStatus);
                result.putString("stream","AAOS emulator gear callback: "+checks+" checks passed\n");
                runOnMainSync(() -> getTargetContext().unbindService(connection));
                finish(Activity.RESULT_OK,result);return;
            }
            if ("microphoneDenied".equals(scenario)) {
                runOnMainSync(() -> { service.setSimulation(false); service.setConversation(true); }); waitFor(200);
                check(!snapshot().conversation,"permission denial rolls back the feature toggle");
                check(!snapshot().microphoneRunning,"denied permission cannot start capture");
                result.putString("stream","Microphone denial: "+checks+" checks passed\n");
                runOnMainSync(() -> getTargetContext().unbindService(connection));
                finish(Activity.RESULT_OK,result);return;
            }
            if ("microphone".equals(scenario)) {
                runOnMainSync(() -> { service.setSimulation(false); service.setConversation(true); }); waitFor(1000);
                result.putString("microphoneStart",snapshot().micStatus);
                boolean started = snapshot().microphoneRunning;
                check(started,"microphone actually starts; unavailable capture is not a passing result");
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getTargetContext().startActivity(home); waitFor(2200);
                result.putBoolean("microphoneStarted",started);
                result.putBoolean("microphoneSurvivedBackground",snapshot().microphoneRunning);
                result.putBoolean("nonzeroAudioInputObserved",snapshot().microphoneInputObserved);
                check(snapshot().microphoneRunning,"microphone remains active with another app in front");
                result.putString("backgroundStatus",snapshot().micStatus);
                runOnMainSync(() -> service.stopAutomation()); waitFor(300);
                check(!snapshot().microphoneRunning,"stop releases microphone");
                result.putString("stream","Microphone probe completed; started="+started+", explicit stop verified.\n");
                runOnMainSync(() -> getTargetContext().unbindService(connection));
                finish(Activity.RESULT_OK,result);return;
            }
            check(!snapshot().simulation,"initial session is actual integration, not a silent demo");
            runOnMainSync(() -> { service.setSimulation(true); service.demoReverse(true); service.setReversing(true); });
            waitFor(650);
            expectVolume(7,"enabling in already selected R ducks from baseline");
            runOnMainSync(() -> service.setPaused(true)); waitFor(2200);
            expectVolume(28,"pause restores owned volume");
            runOnMainSync(() -> service.setPaused(false)); waitFor(650);
            expectVolume(7,"resume re-seeds the current R state");
            runOnMainSync(() -> service.demoReverse(false)); waitFor(3900);
            expectVolume(28,"R exit restores baseline after hold and ramp");

            runOnMainSync(() -> { service.setConversation(true); service.demoSpeech(true); }); waitFor(650);
            expectVolume(10,"conversation applies configured reduction");
            runOnMainSync(() -> service.demoReverse(true)); waitFor(650);
            expectVolume(7,"overlap applies stronger factor once");
            eq(3,snapshot().reasons,"both reasons visible");
            runOnMainSync(() -> service.demoSpeech(false)); waitFor(3800);
            eq(7,snapshot().volume,"R retains reduction after speech ends");
            runOnMainSync(() -> service.demoReverse(false)); waitFor(3900);
            expectVolume(28,"last cause exit restores baseline");

            runOnMainSync(() -> service.demoSpeech(true)); waitFor(650);
            runOnMainSync(() -> service.demoVolume(20)); waitFor(350);
            eq(20,snapshot().volume,"manual input wins during active cause");
            check(snapshot().manualOverride,"manual override is visible");
            runOnMainSync(() -> service.demoSpeech(false)); waitFor(5600);
            eq(20,snapshot().volume,"manual input is not overwritten at episode end");

            runOnMainSync(() -> { service.setSolo(true); service.demoBalance(.4); service.settings(65,75,3,90); });
            near(.9,snapshot().fade,"focus setting updates owned axis");
            near(.4,snapshot().balance,"focus setting respects manually changed axis");
            runOnMainSync(() -> service.setPaused(true));
            near(0,snapshot().fade,"pause restores owned fade");
            near(.4,snapshot().balance,"pause keeps manual balance");
            runOnMainSync(() -> service.setPaused(false));
            near(.9,snapshot().fade,"resume reapplies owned fade");
            near(.4,snapshot().balance,"resume does not reclaim manual balance");
            runOnMainSync(() -> service.setSolo(false));
            near(0,snapshot().fade,"solo exit restores original owned axis");
            near(.4,snapshot().balance,"solo exit preserves manual axis");

            runOnMainSync(() -> { service.setPaused(true); service.setSolo(true); });
            near(0,snapshot().fade,"enabling solo while paused does not apply it yet");
            runOnMainSync(() -> service.setPaused(false));
            near(.9,snapshot().fade,"resume applies solo newly enabled during pause");
            runOnMainSync(() -> service.setSolo(false));

            runOnMainSync(() -> { service.demoSpeech(true); service.demoReverse(true); }); waitFor(650);
            runOnMainSync(() -> service.setConversation(false)); waitFor(200);
            eq(5,snapshot().volume,"disabling speech does not clear R");
            runOnMainSync(() -> { service.demoVolume(0); service.stopAutomation(); }); waitFor(2200);
            eq(0,snapshot().volume,"stop never unmutes a manual zero");
            check(!snapshot().conversation&&!snapshot().reversing&&!snapshot().solo,"stop clears every enabled feature");
            runOnMainSync(() -> service.setSimulation(false));
            result.putString("stream","Quiet Drive device scenarios: "+checks+" checks passed\n");
            result.putString("actualAudioStatus",snapshot().audioStatus);
            result.putString("gearStatus",snapshot().gearStatus);
            result.putInt("checks",checks);
            runOnMainSync(() -> getTargetContext().unbindService(connection));
            finish(Activity.RESULT_OK,result);
        } catch (Throwable error) {
            result.putString("stream", "FAILED after "+checks+" checks: "+error+"\n");
            if (service != null) {
                AutomationService.Snapshot s = snapshot();
                result.putString("diagnostics", "simulation="+s.simulation+", reversing="+s.reversing+", reverse="+s.reverse+", reasons="+s.reasons+", fault="+s.fault);
            }
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private AutomationService.Snapshot snapshot(){AtomicReference<AutomationService.Snapshot> s=new AtomicReference<>();runOnMainSync(()->s.set(service.snapshot()));return s.get();}
    private void waitFor(long time){SystemClock.sleep(time);}
    private void check(boolean truth,String message){checks++;if(!truth)throw new AssertionError(message);}
    private void eq(int expected,int actual,String message){check(expected==actual,message+" expected="+expected+" actual="+actual);}
    private void expectVolume(int expected,String message){
        long deadline=SystemClock.elapsedRealtime()+5000;
        int observed=snapshot().volume;
        while(observed!=expected&&SystemClock.elapsedRealtime()<deadline){waitFor(80);observed=snapshot().volume;}
        eq(expected,observed,message);
    }
    private void near(double expected,double actual,String message){check(Math.abs(expected-actual)<.0001,message+" expected="+expected+" actual="+actual);}
}
