package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Typed adapter over the vanilla command-transfer manager
 * ({@code com.fs.starfarer.combat.B}, one per {@code CombatFleetManager}). House idiom =
 * {@link MatchOverLane}: API imports at top, engine types inline-FQN in method bodies, every
 * public surface try/catch(Throwable) fail-soft with a {@code dead} latch — presentation/
 * trigger only, never break the frame. ZERO reflection (b-deny law: reflection stays
 * server-side; typed virtual calls on engine classes are plain compile-time links).
 *
 * <p><b>Lever provenance:</b>
 * the state getter; the TO getter (NEVER the API
 * {@code getShipPlayerIsTransferringCommandTo} — its engine bug returns FROM); the FROM
 * getter; last-ship set = the one-ship overload; trigger/retarget
 * = the three-arg overload (non-IDLE else-branch = in-flight retarget, re-pins
 * the landing key {@code do$do} — the ABORT-return lever; the IDLE branch is the
 * spawn branch, {@link #driveSpawn}); CR-flag set
 * = the boolean setter; state enum (IDLE /
 * IN_FLIGHT / LANDING). Engine identifiers ride as raw unicode
 * (file is UTF-8; the jdk-23 javac defaults to UTF-8 source encoding — JEP 400). TRAP LIST
 * (keyword-mangled, source-UNREACHABLE — never write): {@code null.while()} CR get, {@code float.while()}
 * can-transfer, {@code class.while()} spec init; replicate can-transfer by comparing the
 * state getter yourself.
 */
final class TransferManagerAdapter {

    static final int STATE_UNKNOWN = -1;
    static final int STATE_IDLE = 0;
    static final int STATE_IN_FLIGHT = 1;
    static final int STATE_LANDING = 2;

    /** MOL dead-latch: any engine-seam throw retires the adapter for the run (fail-soft). */
    private boolean dead;

    boolean usable() {
        return !dead;
    }

    /** The {@link MatchOverLane#ensureDestroyedRoster} cast chain — API engine -> API getFleetManager(int) -> engine downcast -> B. */
    private com.fs.starfarer.combat.B manager(int ownSide) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return null;
        }
        com.fs.starfarer.combat.CombatFleetManager cfm =
                (com.fs.starfarer.combat.CombatFleetManager) engine.getFleetManager(ownSide);
        return cfm != null ? cfm.getTransferManager() : null;
    }

    int state(int ownSide) {
        if (dead) {
            return STATE_UNKNOWN;
        }
        try {
            com.fs.starfarer.combat.B tm = manager(ownSide);
            if (tm == null) {
                return STATE_UNKNOWN;
            }
            com.fs.starfarer.combat.B.o s = tm.ÖØO000(); // state get
            if (s == com.fs.starfarer.combat.B.o.Ó00000) {   // IDLE
                return STATE_IDLE;
            }
            if (s == com.fs.starfarer.combat.B.o.String) {        // IN_FLIGHT
                return STATE_IN_FLIGHT;
            }
            if (s == com.fs.starfarer.combat.B.o.Ò00000) {   // LANDING
                return STATE_LANDING;
            }
            return STATE_UNKNOWN;
        } catch (Throwable t) {
            die("state", t);
            return STATE_UNKNOWN;
        }
    }

    /** The transfer TARGET — the correct TO read (the engine's Ship implements ShipAPI). */
    ShipAPI transferTarget(int ownSide) {
        if (dead) {
            return null;
        }
        try {
            com.fs.starfarer.combat.B tm = manager(ownSide);
            return tm != null ? (ShipAPI) tm.ØØO000() : null;
        } catch (Throwable t) {
            die("transferTarget", t);
            return null;
        }
    }

    /** The vanilla CR-deduct flag — {@link CommandTransferLane} sets it false: the server owns CR. */
    void setCrDeductEnabled(int ownSide, boolean enabled) {
        if (dead) {
            return;
        }
        try {
            com.fs.starfarer.combat.B tm = manager(ownSide);
            if (tm != null) {
                tm.ÖO0000(enabled);
            }
        } catch (Throwable t) {
            die("setCrDeductEnabled", t);
        }
    }

    /** Maintain the vanilla last/current player-ship pointer across the COMMIT swap. */
    void setLastShip(int ownSide, ShipAPI ship) {
        if (dead) {
            return;
        }
        try {
            com.fs.starfarer.combat.B tm = manager(ownSide);
            if (tm != null) {
                tm.o00000(ship);
            }
        } catch (Throwable t) {
            die("setLastShip", t);
        }
    }

    /**
     * Typed retarget-back: the trigger/retarget call {@code (from, from, null)} — the non-IDLE else-branch
     * re-pins the landing key {@code do$do} to the SOURCE and retargets the in-flight pod's
     * {@code TransferCommandAI}, so a late landing swaps onto the source (a no-op restore),
     * never hijacks onto a server-denied target. Guarded IN_FLIGHT-only (the
     * {@link #retargetTo} mirror): at LANDING the else-branch's unconditional
     * {@code state=IN_FLIGHT} with {@code pod.isLanding} stuck true makes the sole swap site
     * in the manager unreachable forever — the strand; at IDLE it would mint a rogue pod.
     * Returns "attempted": false at non-IN_FLIGHT or with no FROM hull — every CTL caller
     * arms the pendingCollapse latch on false.
     */
    boolean retargetToSource(int ownSide) {
        if (dead) {
            return false;
        }
        try {
            com.fs.starfarer.combat.B tm = manager(ownSide);
            if (tm == null || tm.ÖØO000() != com.fs.starfarer.combat.B.o.String) { // IN_FLIGHT only
                return false;
            }
            com.fs.starfarer.combat.entities.Ship from = tm.öØO000(); // FROM get
            if (from == null) {
                return false;
            }
            tm.o00000(from, from, null);
            return true;
        } catch (Throwable t) {
            die("retargetToSource", t);
            return false;
        }
    }

    /**
     * Typed mid-flight retarget of the LOCAL vanilla machine
     * onto a GRANTED target's replica body: the {@code (from, to, null)} trigger/retarget call — its non-IDLE
     * else-branch rewrites FROM and TO and re-points the pod's
     * {@code TransferCommandAI} (its {@code setTo}) — same pod, state stays IN_FLIGHT.
     * Guarded IN_FLIGHT-only IN HERE so no caller can misuse the lever: at IDLE the call would
     * mint a rogue second pod, at LANDING it would flip the machine back to a flying tier and
     * strand it (LANDING is the point of no return). Returns "attempted" — the
     * vanilla call is void and silently no-ops when the body is not in-play.
     */
    boolean retargetTo(int ownSide, ShipAPI target) {
        if (dead || target == null) {
            return false;
        }
        try {
            com.fs.starfarer.combat.B tm = manager(ownSide);
            if (tm == null || tm.ÖØO000() != com.fs.starfarer.combat.B.o.String) { // IN_FLIGHT only
                return false;
            }
            com.fs.starfarer.combat.entities.Ship from = tm.öØO000(); // FROM get
            if (from == null) {
                return false;
            }
            tm.o00000(from, (com.fs.starfarer.combat.entities.Ship) target, null);
            return true;
        } catch (Throwable t) {
            die("retargetTo", t);
            return false;
        }
    }

    /**
     * Drive the vanilla spawn branch for a typed
     * {@code /transfer}: the {@code (from, to, null)} trigger call's IDLE branch launches a real
     * shuttle pod and makes it the player ship SYNCHRONOUSLY. FROM is passed
     * explicitly (B's stored FROM is stale at IDLE); the null launch vector is safe with
     * FROM != null (the sole deref sits in the FROM==null else — the warroom itself passes
     * null); a replica TO is guard-safe (the guard ladder reads no TO fleet member; any
     * guard fail = SILENT no-op, which the caller's search budget converts to observer mode).
     * The synth skips the warroom's CR-deduct set so the trigger stays
     * CR-neutral. Guarded IDLE-only IN HERE (strict ==IDLE — a bricked R7 machine or a live
     * episode must never be driven). Returns "attempted" (the vanilla call is void).
     */
    boolean driveSpawn(int ownSide, ShipAPI from, ShipAPI to) {
        if (dead || from == null || to == null) {
            return false;
        }
        try {
            com.fs.starfarer.combat.B tm = manager(ownSide);
            if (tm == null || tm.ÖØO000() != com.fs.starfarer.combat.B.o.Ó00000) { // IDLE only
                return false;
            }
            // The trigger's FIRST gate is
            // `to != lastLanded || player.isShuttlePod()` — vanilla silently refuses a transfer to
            // the ship the pod LAST LANDED ON (in vanilla you still sit in it). Our seats LEAVE a
            // landed body (return-home, dismount, death) and may target it again (the citadel
            // module, re-homed 27 -> 70) => drove=true, no pod, OBSERVER, "teleport". lastLanded
            // also REPLACES `from` when it differs (the pod would launch from the
            // last-landed module, not the wreck). Clear it through the public last-ship setter
            // (the one-ship overload); its only other seams are the landing write
            // that re-arms it every flight, the getter, the gate + override.
            tm.o00000((ShipAPI) null);
            tm.o00000((com.fs.starfarer.combat.entities.Ship) from,
                    (com.fs.starfarer.combat.entities.Ship) to, null);
            return true;
        } catch (Throwable t) {
            die("driveSpawn", t);
            return false;
        }
    }

    private void die(String seam, Throwable t) {
        dead = true;
        PilotWatchLog.info("cc-transfer-adapter DEAD at " + seam + ": " + t
                + " (trigger/retarget lane retired for the run; server truth unaffected)");
    }
}
