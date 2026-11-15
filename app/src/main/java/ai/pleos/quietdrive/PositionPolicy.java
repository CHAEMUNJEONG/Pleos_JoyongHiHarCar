package ai.pleos.quietdrive;

/** Tracks per-axis ownership for the driver-focused fade and balance mode. */
public final class PositionPolicy {
    private double fade;
    private double balance;
    private double originalFade;
    private double originalBalance;
    private double focus;
    private boolean active;
    private boolean suspended;
    private boolean ownsFade;
    private boolean ownsBalance;

    public PositionPolicy(double initialFade, double initialBalance) {
        fade = clamp(initialFade);
        balance = clamp(initialBalance);
        originalFade = fade;
        originalBalance = balance;
    }

    public void enter(double focus) {
        double amount = clampFocus(focus);
        if (!active) {
            originalFade = fade;
            originalBalance = balance;
            ownsFade = true;
            ownsBalance = true;
            active = true;
        }
        this.focus = amount;
        if (!suspended) {
            if (ownsFade) fade = amount;
            if (ownsBalance) balance = -amount;
        }
    }

    /** Temporarily restores owned axes while retaining the current solo session. */
    public void suspend() {
        if (!active || suspended) return;
        if (ownsFade) fade = originalFade;
        if (ownsBalance) balance = originalBalance;
        suspended = true;
    }

    /** Reapplies focus only to axes that are still owned by this policy. */
    public void resume() {
        if (!active || !suspended) return;
        suspended = false;
        if (ownsFade) fade = focus;
        if (ownsBalance) balance = -focus;
    }

    public void manualFade(double value) {
        fade = clamp(value);
        if (active) ownsFade = false;
    }

    public void manualBalance(double value) {
        balance = clamp(value);
        if (active) ownsBalance = false;
    }

    public void leave() {
        if (!active) return;
        if (ownsFade) fade = originalFade;
        if (ownsBalance) balance = originalBalance;
        active = false;
        suspended = false;
        ownsFade = false;
        ownsBalance = false;
    }

    public double fade() {
        return fade;
    }

    public double balance() {
        return balance;
    }

    public boolean active() {
        return active;
    }

    public boolean suspended() {
        return suspended;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0;
        return Math.max(-1, Math.min(1, value));
    }

    private static double clampFocus(double value) {
        if (Double.isNaN(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }
}
