package ai.pleos.quietdrive;

/**
 * Pure state machine for media-volume automation.
 *
 * <p>The class deliberately has no Android dependencies. Platform code supplies monotonic time,
 * observed media-group volume, and explicit manual-volume callbacks.</p>
 */
public final class AudioPolicy {
    public static final int REASON_CONVERSATION = 1;
    public static final int REASON_REVERSE = 1 << 1;

    public static final class Config {
        public double conversationRemaining = 0.35;
        public double reverseRemaining = 0.25;
        public long silenceMs = 3_000;
        public long reverseHoldMs = 1_200;
        public long restoreDelayMs = 500;
        public long attackMs = 400;
        public long releaseMs = 1_800;
    }

    public static final class Output {
        public final int volume;
        public final int reasons;
        public final boolean holding;
        public final boolean manualOverride;
        public final boolean changing;
        public final boolean ownsVolume;

        private Output(
                int volume,
                int reasons,
                boolean holding,
                boolean manualOverride,
                boolean changing,
                boolean ownsVolume) {
            this.volume = volume;
            this.reasons = reasons;
            this.holding = holding;
            this.manualOverride = manualOverride;
            this.changing = changing;
            this.ownsVolume = ownsVolume;
        }
    }

    private static final long NO_DEADLINE = Long.MIN_VALUE;

    private final Config config;
    private boolean conversationEnabled;
    private boolean reverseEnabled;
    private boolean paused;
    private boolean speechActive;
    private boolean reverseActive;
    private long speechHoldUntil = NO_DEADLINE;
    private long reverseHoldUntil = NO_DEADLINE;

    private boolean episodeActive;
    private boolean manualOverride;
    private boolean ownsVolume;
    private double baseline;
    private double current;
    private double rampFrom;
    private double rampTo;
    private long rampStartedAt;
    private long rampDuration;
    private boolean ramping;
    private long restoreReadyAt = NO_DEADLINE;
    private int previousReasons;
    private long pendingCauseChangeAt = NO_DEADLINE;
    private boolean waitingForObservation = true;

    public AudioPolicy(Config config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
    }

    public Config config() {
        return config;
    }

    public void setConversationEnabled(boolean enabled) {
        conversationEnabled = enabled;
        if (!enabled) {
            speechActive = false;
            speechHoldUntil = NO_DEADLINE;
        }
    }

    public void setReverseEnabled(boolean enabled) {
        reverseEnabled = enabled;
        if (!enabled) {
            reverseActive = false;
            reverseHoldUntil = NO_DEADLINE;
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            speechActive = false;
            reverseActive = false;
            speechHoldUntil = NO_DEADLINE;
            reverseHoldUntil = NO_DEADLINE;
        }
    }

    public void speech(boolean active, long now) {
        if (!conversationEnabled || paused) return;
        speechActive = active;
        pendingCauseChangeAt = now;
        if (active) {
            speechHoldUntil = NO_DEADLINE;
        } else {
            speechHoldUntil = safeAdd(now, nonNegative(config.silenceMs));
        }
    }

    public void reverse(boolean active, long now) {
        if (!reverseEnabled || paused) return;
        reverseActive = active;
        pendingCauseChangeAt = now;
        if (active) {
            reverseHoldUntil = NO_DEADLINE;
        } else {
            reverseHoldUntil = safeAdd(now, nonNegative(config.reverseHoldMs));
        }
    }

    /** Gives the current automation episode to the user until all causes and waits have ended. */
    public void manualVolume(int volume, long now) {
        int safeVolume = Math.max(0, volume);
        baseline = safeVolume;
        current = safeVolume;
        ramping = false;
        ownsVolume = false;
        if (episodeActive) {
            manualOverride = true;
        }
    }

    /** No automatic episode or restoration may be based on an unavailable vehicle reading. */
    public Output tickIfAvailable(boolean available, long now, int observedVolume, int maxVolume) {
        if (!available || observedVolume < 0 || maxVolume < observedVolume) {
            waitingForObservation = true;
            return null;
        }
        if (waitingForObservation) {
            finishEpisode(observedVolume);
            previousReasons = 0;
            pendingCauseChangeAt = now;
            waitingForObservation = false;
        }
        return tick(now, observedVolume, maxVolume);
    }

    public Output tick(long now, int observedVolume, int maxVolume) {
        int safeMax = Math.max(0, maxVolume);
        int observed = clamp(observedVolume, 0, safeMax);
        int reasons = activeReasons(now);
        boolean causeHolding = causeHolding(now, reasons);

        if (reasons != 0) {
            restoreReadyAt = NO_DEADLINE;
            if (!episodeActive) {
                episodeActive = true;
                manualOverride = false;
                ownsVolume = true;
                baseline = observed;
                current = observed;
                ramping = false;
            }

            if (manualOverride) {
                current = observed;
            } else {
                long targetChangedAt = pendingCauseChangeAt != NO_DEADLINE
                        ? Math.min(now, pendingCauseChangeAt)
                        : now;
                advanceRamp(targetChangedAt);
                double factor = remainingFactor(reasons);
                double target = clampDouble(baseline * factor, 0, safeMax);
                retarget(targetChangedAt, target, nonNegative(config.attackMs));
                advanceRamp(now);
                ownsVolume = true;
            }
            pendingCauseChangeAt = NO_DEADLINE;
        } else if (episodeActive) {
            if (restoreReadyAt == NO_DEADLINE) {
                restoreReadyAt = paused
                        ? now
                        : safeAdd(reasonInactiveAt(now), nonNegative(config.restoreDelayMs));
            }

            if (manualOverride) {
                current = observed;
                if (now >= restoreReadyAt) finishEpisode(observed);
            } else if (now >= restoreReadyAt) {
                advanceRamp(now);
                retarget(now, clampDouble(baseline, 0, safeMax), nonNegative(config.releaseMs));
                advanceRamp(now);
                ownsVolume = ramping;
                if (!ramping) finishEpisode((int) Math.round(current));
            }
        } else {
            current = observed;
            ownsVolume = false;
            ramping = false;
        }

        boolean restoreHolding = episodeActive && reasons == 0 && now < restoreReadyAt;
        boolean holding = causeHolding || restoreHolding;
        int outputVolume = clamp((int) Math.round(manualOverride ? observed : current), 0, safeMax);
        boolean changing = ownsVolume && ramping;
        previousReasons = reasons;
        return new Output(
                outputVolume,
                reasons,
                holding,
                manualOverride,
                changing,
                ownsVolume);
    }

    /** Stops accepting causes and begins the configured smooth release immediately. */
    public Output forceRelease(long now, int observedVolume, int maxVolume) {
        setPaused(true);
        restoreReadyAt = now;
        return tick(now, observedVolume, maxVolume);
    }

    private int activeReasons(long now) {
        if (paused) return 0;
        int result = 0;
        if (conversationEnabled && (speechActive || before(now, speechHoldUntil))) {
            result |= REASON_CONVERSATION;
        }
        if (reverseEnabled && (reverseActive || before(now, reverseHoldUntil))) {
            result |= REASON_REVERSE;
        }
        return result;
    }

    private boolean causeHolding(long now, int reasons) {
        boolean conversationHold = (reasons & REASON_CONVERSATION) != 0
                && !speechActive && before(now, speechHoldUntil);
        boolean reverseHold = (reasons & REASON_REVERSE) != 0
                && !reverseActive && before(now, reverseHoldUntil);
        return conversationHold || reverseHold;
    }

    private long reasonInactiveAt(long now) {
        if (previousReasons == 0) return now;
        long inactiveAt = NO_DEADLINE;
        if ((previousReasons & REASON_CONVERSATION) != 0 && !speechActive) {
            inactiveAt = Math.max(inactiveAt, speechHoldUntil);
        }
        if ((previousReasons & REASON_REVERSE) != 0 && !reverseActive) {
            inactiveAt = Math.max(inactiveAt, reverseHoldUntil);
        }
        return inactiveAt == NO_DEADLINE ? now : Math.min(now, inactiveAt);
    }

    private double remainingFactor(int reasons) {
        double factor = 1.0;
        if ((reasons & REASON_CONVERSATION) != 0) {
            factor = Math.min(factor, clampDouble(config.conversationRemaining, 0, 1));
        }
        if ((reasons & REASON_REVERSE) != 0) {
            factor = Math.min(factor, clampDouble(config.reverseRemaining, 0, 1));
        }
        return factor;
    }

    private void retarget(long now, double target, long duration) {
        if (ramping && nearlyEqual(rampTo, target)) return;
        if (!ramping && nearlyEqual(current, target)) return;
        rampFrom = current;
        rampTo = target;
        rampStartedAt = now;
        rampDuration = duration;
        ramping = duration > 0 && !nearlyEqual(rampFrom, rampTo);
        if (!ramping) current = target;
    }

    private void advanceRamp(long now) {
        if (!ramping) return;
        long elapsed = Math.max(0, now - rampStartedAt);
        if (elapsed >= rampDuration) {
            current = rampTo;
            ramping = false;
            return;
        }
        double fraction = (double) elapsed / (double) rampDuration;
        current = rampFrom + ((rampTo - rampFrom) * fraction);
    }

    private void finishEpisode(int currentVolume) {
        episodeActive = false;
        manualOverride = false;
        ownsVolume = false;
        ramping = false;
        restoreReadyAt = NO_DEADLINE;
        baseline = currentVolume;
        current = currentVolume;
    }

    private static boolean before(long now, long deadline) {
        return deadline != NO_DEADLINE && now < deadline;
    }

    private static long safeAdd(long value, long addition) {
        if (addition > 0 && value > Long.MAX_VALUE - addition) return Long.MAX_VALUE;
        return value + addition;
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clampDouble(double value, double minimum, double maximum) {
        if (Double.isNaN(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) < 0.000_001;
    }
}
