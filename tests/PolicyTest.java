package ai.pleos.quietdrive;

public final class PolicyTest {
    private static int assertions;

    public static void main(String[] args) {
        conversationRestartsSilenceTimer();
        overlappingConditionsUseLowestFactorOnce();
        reverseChatterDoesNotPumpVolume();
        rampsAreTimeBasedAndDoNotForceUnmute();
        manualChangeWinsForTheWholeEpisode();
        featureTogglesAndPauseReleaseCorrectly();
        positionRestoresOnlyAxesStillOwned();
        delayedVehicleObservationLatchesRealBaseline();
        positionSuspendResumePreservesOwnership();
        positionLeaveWhileSuspendedEndsSession();
        System.out.println("PolicyTest: " + assertions + " assertions passed");
    }

    private static AudioPolicy fastPolicy() {
        AudioPolicy.Config config = new AudioPolicy.Config();
        config.conversationRemaining = 0.5;
        config.reverseRemaining = 0.25;
        config.silenceMs = 1_000;
        config.reverseHoldMs = 500;
        config.restoreDelayMs = 200;
        config.attackMs = 100;
        config.releaseMs = 400;
        AudioPolicy policy = new AudioPolicy(config);
        policy.setConversationEnabled(true);
        policy.setReverseEnabled(true);
        return policy;
    }

    private static void conversationRestartsSilenceTimer() {
        AudioPolicy policy = fastPolicy();
        policy.speech(true, 0);
        eq(100, policy.tick(0, 100, 100).volume, "attack begins at the observed baseline");
        eq(50, policy.tick(100, 50, 100).volume, "conversation reaches its target");

        policy.speech(false, 200);
        AudioPolicy.Output held = policy.tick(1_100, 50, 100);
        eq(AudioPolicy.REASON_CONVERSATION, held.reasons, "short silence remains a conversation reason");
        yes(held.holding, "short silence reports a hold");

        policy.speech(true, 1_150);
        policy.speech(false, 1_250);
        eq(50, policy.tick(2_200, 50, 100).volume, "new speech restarts the silence timer");
        AudioPolicy.Output waiting = policy.tick(2_250, 50, 100);
        eq(0, waiting.reasons, "conversation reason clears after silence timeout");
        yes(waiting.holding, "restore delay holds the ducked level");
        eq(50, policy.tick(2_449, 50, 100).volume, "restore delay does not start the release early");
        eq(50, policy.tick(2_450, 50, 100).volume, "release begins continuously");
        eq(75, policy.tick(2_650, 75, 100).volume, "release ramps from the held level");
        AudioPolicy.Output restored = policy.tick(2_850, 100, 100);
        eq(100, restored.volume, "release restores the latched baseline");
        no(restored.ownsVolume, "ownership ends after restoration");
    }

    private static void overlappingConditionsUseLowestFactorOnce() {
        AudioPolicy policy = fastPolicy();
        policy.speech(true, 0);
        policy.tick(0, 80, 100);
        eq(40, policy.tick(100, 40, 100).volume, "conversation ducks from the baseline");

        policy.reverse(true, 110);
        eq(20, policy.tick(210, 20, 100).volume, "overlap uses min factor rather than multiplying factors");
        eq(AudioPolicy.REASON_CONVERSATION | AudioPolicy.REASON_REVERSE,
                policy.tick(220, 20, 100).reasons, "both causes remain visible");

        policy.speech(false, 230);
        eq(20, policy.tick(1_230, 20, 100).volume, "R keeps the lower target after conversation ends");
        eq(AudioPolicy.REASON_REVERSE, policy.tick(1_231, 20, 100).reasons, "only R remains after speech hold");

        policy.reverse(false, 1_240);
        eq(20, policy.tick(1_700, 20, 100).volume, "R exit hold prevents pumping");
        yes(policy.tick(1_700, 20, 100).holding, "R exit is reported as held");
    }

    private static void reverseChatterDoesNotPumpVolume() {
        AudioPolicy policy = fastPolicy();
        policy.reverse(true, 0);
        policy.tick(0, 60, 100);
        eq(15, policy.tick(100, 15, 100).volume, "R reaches its target");
        policy.reverse(false, 120);
        policy.tick(400, 15, 100);
        policy.reverse(true, 450);
        eq(15, policy.tick(700, 15, 100).volume, "R re-entry cancels pending release");
        policy.reverse(false, 710);
        eq(15, policy.tick(1_209, 15, 100).volume, "latest R exit owns the full hold interval");
    }

    private static void rampsAreTimeBasedAndDoNotForceUnmute() {
        AudioPolicy oneTick = fastPolicy();
        oneTick.speech(true, 0);
        oneTick.tick(0, 100, 100);
        eq(75, oneTick.tick(50, 75, 100).volume, "attack midpoint is deterministic");

        AudioPolicy manyTicks = fastPolicy();
        manyTicks.speech(true, 0);
        manyTicks.tick(0, 100, 100);
        manyTicks.tick(10, 95, 100);
        manyTicks.tick(20, 90, 100);
        manyTicks.tick(30, 85, 100);
        eq(75, manyTicks.tick(50, 75, 100).volume, "tick frequency does not change interpolation");

        AudioPolicy muted = fastPolicy();
        muted.speech(true, 0);
        eq(0, muted.tick(0, 0, 100).volume, "a zero baseline remains muted");
        eq(0, muted.tick(100, 0, 100).volume, "ducking never forces unmute");

        AudioPolicy clamped = fastPolicy();
        clamped.speech(true, 0);
        clamped.tick(0, 90, 100);
        eq(40, clamped.tick(100, 40, 40).volume, "result clamps when maximum volume shrinks");
    }

    private static void manualChangeWinsForTheWholeEpisode() {
        AudioPolicy policy = fastPolicy();
        policy.speech(true, 0);
        policy.tick(0, 80, 100);
        eq(60, policy.tick(50, 60, 100).volume, "automatic ramp owns the initial change");

        policy.manualVolume(70, 60);
        AudioPolicy.Output manual = policy.tick(100, 70, 100);
        eq(70, manual.volume, "manual value is preserved");
        yes(manual.manualOverride, "manual override is exposed");
        no(manual.ownsVolume, "automation releases ownership after a manual change");

        policy.speech(false, 110);
        eq(70, policy.tick(1_310, 70, 100).volume, "manual value survives reason and restore holds");
        eq(70, policy.tick(1_311, 70, 100).volume, "old baseline is never restored after manual input");
        no(policy.tick(1_311, 70, 100).manualOverride, "override clears when the episode is fully over");

        policy.speech(true, 1_500);
        policy.tick(1_500, 70, 100);
        eq(35, policy.tick(1_600, 35, 100).volume, "next episode latches the new manual baseline");
    }

    private static void featureTogglesAndPauseReleaseCorrectly() {
        AudioPolicy policy = fastPolicy();
        policy.speech(true, 0);
        policy.reverse(true, 0);
        policy.tick(0, 100, 100);
        policy.tick(100, 25, 100);
        policy.setConversationEnabled(false);
        eq(AudioPolicy.REASON_REVERSE, policy.tick(110, 25, 100).reasons,
                "disabling conversation does not clear reverse");
        policy.setReverseEnabled(false);
        eq(0, policy.tick(120, 25, 100).reasons, "disabled causes stop contributing immediately");

        AudioPolicy paused = fastPolicy();
        paused.speech(true, 0);
        paused.tick(0, 100, 100);
        paused.tick(100, 50, 100);
        paused.setPaused(true);
        AudioPolicy.Output releaseStart = paused.tick(110, 50, 100);
        eq(0, releaseStart.reasons, "pause suppresses all reasons");
        yes(releaseStart.ownsVolume, "pause releases an owned reduction smoothly");
        eq(75, paused.tick(310, 75, 100).volume, "pause uses the configured release ramp");
        no(paused.tick(510, 100, 100).ownsVolume, "pause release eventually relinquishes ownership");
    }

    private static void positionRestoresOnlyAxesStillOwned() {
        PositionPolicy policy = new PositionPolicy(0.2, 0.1);
        policy.enter(0.75);
        near(0.75, policy.fade(), "focus moves forward");
        near(-0.75, policy.balance(), "left-hand driver focus moves left");
        yes(policy.active(), "position mode is active");

        policy.manualBalance(0.4);
        policy.leave();
        near(0.2, policy.fade(), "unchanged owned fade restores its original value");
        near(0.4, policy.balance(), "manually changed balance is preserved");
        no(policy.active(), "position mode exits");

        PositionPolicy clamped = new PositionPolicy(-2.0, 2.0);
        near(-1.0, clamped.fade(), "initial fade clamps to valid range");
        near(1.0, clamped.balance(), "initial balance clamps to valid range");
        clamped.enter(2.0);
        near(1.0, clamped.fade(), "focus clamps at full front");
        near(-1.0, clamped.balance(), "focus clamps at full left");
        clamped.manualFade(-0.3);
        clamped.leave();
        near(-0.3, clamped.fade(), "manual fade remains on exit");
        near(1.0, clamped.balance(), "still-owned balance restores separately");
    }

    private static void positionSuspendResumePreservesOwnership() {
        PositionPolicy policy = new PositionPolicy(0.2, 0.1);
        policy.enter(0.7);
        policy.manualBalance(0.4);
        policy.suspend();
        yes(policy.suspended(), "global pause marks position mode suspended");
        near(0.2, policy.fade(), "suspend temporarily restores an owned fade axis");
        near(0.4, policy.balance(), "suspend leaves an unowned manual axis unchanged");

        policy.manualFade(-0.5);
        policy.enter(0.9);
        near(-0.5, policy.fade(), "focus changes stay unapplied while suspended");
        near(0.4, policy.balance(), "suspended focus changes do not touch manual balance");
        policy.resume();
        no(policy.suspended(), "resume clears suspended state");
        near(-0.5, policy.fade(), "manual fade during suspend releases fade ownership");
        near(0.4, policy.balance(), "resume preserves a previously released balance axis");

        PositionPolicy owned = new PositionPolicy(-0.1, 0.25);
        owned.enter(0.6);
        owned.suspend();
        owned.enter(0.85);
        near(-0.1, owned.fade(), "new focus is remembered without moving suspended fade");
        near(0.25, owned.balance(), "new focus is remembered without moving suspended balance");
        owned.resume();
        near(0.85, owned.fade(), "resume reapplies remembered focus to owned fade");
        near(-0.85, owned.balance(), "resume reapplies remembered focus to owned balance");
        owned.leave();
        near(-0.1, owned.fade(), "leave still restores the pre-solo fade after suspension");
        near(0.25, owned.balance(), "leave still restores the pre-solo balance after suspension");
    }

    private static void positionLeaveWhileSuspendedEndsSession() {
        PositionPolicy policy = new PositionPolicy(0.15, -0.2);
        policy.enter(0.8);
        policy.suspend();
        policy.manualBalance(0.35);
        policy.leave();
        no(policy.active(), "leave while suspended ends the solo session");
        no(policy.suspended(), "leave while suspended clears suspension state");
        near(0.15, policy.fade(), "owned fade remains at its restored original on leave");
        near(0.35, policy.balance(), "manual balance during suspension survives leave");
        policy.resume();
        near(0.15, policy.fade(), "resume after leave is a no-op for fade");
        near(0.35, policy.balance(), "resume after leave is a no-op for balance");
    }

    private static void delayedVehicleObservationLatchesRealBaseline() {
        AudioPolicy policy = fastPolicy();
        policy.speech(true, 0);
        yes(policy.tickIfAvailable(false, 0, 28, 40) == null, "unknown audio cannot create an episode from defaults");
        yes(policy.tickIfAvailable(false, 1000, 28, 40) == null, "probing keeps the policy awaiting real observations");
        eq(16, policy.tickIfAvailable(true, 2000, 16, 20).volume, "availability latches actual volume and starts continuously");
        eq(8, policy.tickIfAvailable(true, 2100, 8, 20).volume, "duck target comes from actual baseline");
        policy.speech(false, 2200);
        policy.tickIfAvailable(true, 3400, 8, 20);
        eq(16, policy.tickIfAvailable(true, 3800, 16, 20).volume, "restore never uses a fabricated baseline");
        yes(policy.tickIfAvailable(true, 3900, -1, 20) == null, "invalid read cannot advance policy");
        eq(6, policy.tickIfAvailable(true, 4000, 6, 20).volume, "fresh observation after loss does not restore stale ownership");
    }

    private static void eq(int expected, int actual, String message) {
        assertions++;
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void near(double expected, double actual, String message) {
        assertions++;
        if (Math.abs(expected - actual) > 0.000_001) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void yes(boolean actual, String message) {
        assertions++;
        if (!actual) throw new AssertionError(message + ": expected true");
    }

    private static void no(boolean actual, String message) {
        assertions++;
        if (actual) throw new AssertionError(message + ": expected false");
    }
}
