package cc.starsector.client;

/**
 * Own Missile-Autoloader RELOAD-HOLD edge law (pure; no engine/API imports).
 *
 *
 * <p>WHY: a reported "Hammer bounce". The vanilla {@code missile_autoloader}
 * hullmod refills an empty small missile mount ({@code getAmmo() <= 0})
 * on its own random {@code IntervalUtil(0.2,0.4)} clock, then PINS the mount's cooldown at full for
 * 5 s (S-mod 10 s) through its {@code TimeoutTracker}. Server and client run that clock independently;
 * the client's ownlock ammo lane mirrored the server's refill before the local interval elapsed, so the
 * local hullmod never reloaded, never pinned, and the local mount went free while the server's stayed
 * pinned — the owner's press fired locally and the server retired it ({@code timeout-futile}).
 * {@link OwnReloadHoldLane} mirrors the server's reload INTO the local hullmod's own tracker; this law
 * decides WHEN.
 *
 * <p>TWO-SIGNAL edge (the amendment: an ammo rise ALONE is wrong on this hull): the Gryphon's own
 * system {@code forgevats} (Missile Autoforge) is an engine {@code AMMO_RELOAD} system = an ammo RISE
 * with NO hold; a rise-only mirror would lock a free server mount for 5 s. The autoloader's
 * signature (= server F1's) is ammo 0→k on an affected mount, THEN the cooldown pinned at
 * EXACTLY full from the next tick (5719 ammo 0→2 at cd 0.82, 5720 cd 1.00). A rise from
 * 0 ARMS; the confirming frame (same or next carried frame) must read the pin; a pending arm that is
 * NOT pinned at the next carried frame is UNCONFIRMED (an Autoforge/other refill — no hold, metered).
 *
 * <p>{@link #PIN_EPS} = the server F1 detector's own epsilon (a fraction 0.997
 * admitted a natural next-tick read on AWSS raw floats for cooldowns ≥ 11.1 s — the gazer's 15 s reads
 * 14.967 = 0.99778). AWSD decodes {@code cdFrac/255 × cooldown} ({@code AwsdSnapshotReader}),
 * so the pin = frac 255 = {@code cooldown} bit-exact and one step below = {@code cooldown − cooldown/255},
 * outside the epsilon for every affected small missile (cooldown ≥ 1 s); a natural shot's next-tick read
 * is full − 2·dt (0.033 s at 60 Hz). AWSS carries raw floats: the pin = {@code getCooldown()} exactly.
 *
 * <p>RISE = any wire ammo increase: when the hullmod's
 * interval elapses on the emptying tick the weapon's own advance spends the last round BEFORE the reload,
 * so the post-step wire reads 1→2 and never 0 (~1/18 of reloads) — a rise-from-0 rule missed that hold.
 * A rise INSIDE a hold this lane itself mirrored ({@code mirroredLive}) is a refill (the Autoforge 2→6
 * mid-hold) and must never re-base or extend the lock — the server's own hullmod does nothing there either.
 *
 * <p>VALUE-DOMAIN: ammo ints ≥ 0 ({@code -1} = the unseen sentinel, {@code prevAuthAmmo} only);
 * cooldown floats in seconds ≥ 0. Caller: {@link OwnReloadHoldLane#onCarriedMount}; smoke:
 * {@code OwnReloadHoldLawSmoke}.
 */
final class OwnReloadHoldLaw {

    /** Pin discriminator: {@code authCdRem >= authCd − PIN_EPS} reads "held at full" (class doc; = the server's ε). */
    static final float PIN_EPS = 1e-3f;

    /** Not an autoloader mount (not {@code isAffected}, or the hull lacks the hullmod) — the caller clears pending. */
    static final int SKIP = 0;
    /** First carried frame for this mount on this body ({@code prevAuthAmmo < 0}) — record only. */
    static final int SEED = 1;
    /** Ammo rose from 0 but the cooldown is not pinned yet — wait one carried frame for the pin. */
    static final int ARM = 2;
    /** Reload confirmed (rise or pending + pinned), local tracker does not hold it — hold AND debit. */
    static final int HOLD_DEBIT = 3;
    /** Reload confirmed and the local hullmod already reloaded it (tracker holds it) — re-base, no debit. */
    static final int HOLD_REBASE = 4;
    /** A pending arm NOT pinned at the next carried frame — a non-autoloader refill; no hold, metered. */
    static final int UNCONFIRMED = 5;
    /** Nothing to do this frame. */
    static final int NONE = 6;
    /**
     * The wire reads UNPINNED on a mount whose live tracker entry THIS lane applied = the server's own
     * hold ended — drop ours. WHY: the mirrored hold is a
     * fixed {@code BASIC|SMOD_COOLDOWN} from the frame that SAW the pin, so it outlasts the server's by
     * the pin-to-sight lag (≥ 1 tick + coalesced frames) and by the whole unseen part of a hold entered
     * mid-way (SEED / same-body stall-resume: up to ~5 s); in that tail a class-S press has no own fire,
     * so no declaration is sent and the tap is DROPPED. The wire's first unpinned read caps the
     * tail at one-way age + one frame; the local cooldown then decays from full exactly as the server's.
     * A hold this lane did NOT apply (the client's own interval) is never wire-released.
     */
    static final int RELEASE = 7;

    private OwnReloadHoldLaw() {
    }

    /**
     * The per-carried-mount verdict (class doc = the edge). {@code prevAuthAmmo} = the last carried
     * frame's wire ammo for this mount on this body ({@code -1} unseen); {@code pending} = the last
     * verdict was {@link #ARM}; {@code alreadyHeld} = the local hullmod's tracker already holds the weapon;
     * {@code mirroredLive} = that tracker entry is one THIS lane applied and it is still live (a rise inside
     * it is a refill, never a reload).
     */
    static int decide(boolean affected, boolean hasMod, int prevAuthAmmo, int authAmmo, boolean pending,
            float authCdRem, float authCd, boolean alreadyHeld, boolean mirroredLive) {
        if (!affected || !hasMod) {
            return SKIP;
        }
        if (prevAuthAmmo < 0) {
            return SEED;
        }
        boolean pinned = authCd > 0f && authCdRem >= authCd - PIN_EPS;
        if (mirroredLive) {
            return pinned ? NONE : RELEASE;
        }
        boolean rise = authAmmo > prevAuthAmmo;
        if ((rise || pending) && pinned) {
            return alreadyHeld ? HOLD_REBASE : HOLD_DEBIT;
        }
        if (rise) {
            return ARM;
        }
        if (pending) {
            return UNCONFIRMED;
        }
        return NONE;
    }
}
