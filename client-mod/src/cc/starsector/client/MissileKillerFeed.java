package cc.starsector.client;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;

/**
 * The {@code dmg-shot-out} KILLER feed —
 * who damaged a presented (or own) missile body on THIS client, read from the engine's OWN report
 * seam, never a poll. WHY: a test flight had the operator's window kill 36 presented Hammers the server
 * still flew (2 ghost hits) and no client row named the killer ({@code dmg-shot-out} carried no
 * {@code hp=}/{@code fading=}, and there was no beam-vs-missile damage row) — this feed fills the
 * {@code killer= ksrc= khits= kdmg= ktick=} tokens {@link DamageWitness#retireShot} prints on MISSILE
 * rows.
 *
 * <p><b>Seam</b>: ONE engine-global {@link DamageListener} (registered beside the own-missile-hit
 * feed in {@code PredictReconcileCombatPlugin}, served windows only). The engine's
 * {@code Ship.applyDamage} report hands {@code (source, target, result)} with {@code source} = the
 * FIRING SHIP, not the beam/projectile; a missile victim
 * reports through the same global seam. Only a BEAM kill
 * resolves by name — ONE bounded public-API scan, {@code engine.getBeams()} ({@link #SCAN_MAX_BEAMS})
 * for {@code getSource()==source && getDamageTarget()==target} → {@code beam:<weaponId>}, which works
 * because the engine sets a beam's damage target BEFORE its damage response.
 * A projectile / missile / flak kill prints {@code other} (= unresolved): the engine sets a
 * projectile's {@code damageTarget} only AFTER its damage report (applyDamage → report →
 * notifyDealtDamage), so the
 * former projectile scan could never find the shot that hit and was DELETED (up to 512 × 2 interface
 * calls on the render thread per report, for a mis-named token at best); the projectile join stays a
 * queued fold in the deferred-refactor list. A beam scan that hit its cap
 * without a match → {@code other}, counted in {@code scanCapped} (NOT the {@code other} counter); a clean
 * beam scan with no match → {@code other}, counted in {@code other}. The token grammar mirrors the
 * server's {@code HullSourceLedger.describeParam} so one grader reads both sides; weapon ids and
 * source SimpleNames pass through {@link #token} (the server's {@code HullSourceLedger.sanitize}
 * charset) so a raw id never splits the grader's whitespace-delimited {@code killer=} group. B-deny
 * law: public API only — the server's param-about-to-apply read is reflection and never
 * rides the mod jar, so the source→beam join is a scan, not a param read. Weapon/spec ids are DATA
 * ids (bounded tokens), never player text.
 *
 * <p><b>Cost </b>: this listener fires for EVERY damage report in the engine (every hull,
 * armor, shield and beam tick on every ship). A non-missile target returns at the first
 * {@code instanceof} (no map probe, no scan); an untracked missile costs one identity-hash probe; a
 * report inside the client's own death-puff presenter ({@link #presenting}) stops at the fed count;
 * only a TRACKED missile pays the bounded beam scan (a PD beam, the common named killer, matches early).
 *
 * <p><b>Lifetime</b>: records are keyed by the live missile object (identity, the witness's
 * {@code tracked} key) and removed by {@link #take} at the witness's retire, or {@link #clear} at the
 * round reset; {@link #FEED_MAX} bounds the map (a new missile past it counts {@code overflow}
 * and gets no record). Threading: the engine's listener dispatch, the witness retire and the
 * presenter's flag set/clear all run on the engine advance (render) thread — plain fields. The
 * callback NEVER throws into the engine: every {@code catch} counts {@code caught} and the
 * first one logs once. The counters are unprinted; {@link #counterForTest} reads them.
 */
final class MissileKillerFeed implements DamageListener {

    static final int SCAN_MAX_BEAMS = 256;
    static final int FEED_MAX = 4096;
    /** Killer-token cap = the server's {@code HullSourceLedger.TOKEN_MAX} (40), so one id prints the same
     *  token on both sides of the grader join. */
    static final int TOKEN_MAX = 40;

    /** Per-missile killer record; {@code killer}/{@code src} are the LAST report's, hits/dmg summed. */
    static final class Killer {
        String killer;
        String src;
        int hits;
        float dmg;
        long lastTick;
    }

    private final Predicate<Object> tracked;
    private final LongSupplier tick;
    private final Supplier<CombatEngineAPI> engine;
    private final Supplier<ShipAPI> ownShip;
    private final IdentityHashMap<Object, Killer> byMissile = new IdentityHashMap<Object, Killer>();

    private long fed;
    private long untracked;
    private long beam;
    private long other;
    private long scanCapped;
    private long overflow;
    private long nullResult;
    private long caught;
    private long self;
    private boolean caughtLogged;
    /** TRUE only while the client presents its OWN
     *  DESTROYED puff — {@code SourcedMissileEndLane.presentDeathPuff}'s lethal null-source
     *  {@code applyDamage} on a still-tracked missile reports through this same global seam and would
     *  otherwise overwrite a real local PD killer with {@code killer=other ksrc=none} and inflate
     *  {@code kdmg} by the puff's dose; such a report counts {@code self} only. */
    private boolean presenting;

    MissileKillerFeed(Predicate<Object> tracked, LongSupplier tick, Supplier<CombatEngineAPI> engine,
            Supplier<ShipAPI> ownShip) {
        this.tracked = tracked;
        this.tick = tick;
        this.engine = engine;
        this.ownShip = ownShip;
    }

    /** The death-puff presenter's gate ({@link #presenting}); set/cleared around ONE synchronous
     *  {@code applyDamage}, the clear in a {@code finally}. */
    void presenting(boolean on) {
        presenting = on;
    }

    @Override
    public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
        if (!(target instanceof MissileAPI)) {
            return;
        }
        try {
            if (!tracked.test(target)) {
                untracked++;
                return;
            }
            fed++;
            // The own death puff never touches the record
            if (presenting) {
                self++;
                return;
            }
            Killer k = byMissile.get(target);
            if (k == null) {
                if (byMissile.size() >= FEED_MAX) {
                    overflow++;
                    return;
                }
                k = new Killer();
                byMissile.put(target, k);
            }
            k.killer = resolveKiller(source, target);
            k.src = sourceToken(source);
            k.hits++;
            if (result == null) {
                nullResult++;
            } else {
                k.dmg += nz(result.getDamageToHull()) + nz(result.getTotalDamageToArmor());
            }
            k.lastTick = tick.getAsLong();
        } catch (Throwable t) {
            countCaught(t);
        }
    }

    /** The ONE bounded beam scan (the projectile scan is
     *  DELETED — see the class javadoc). A capped scan lands {@code other} counted in
     *  {@code scanCapped}; a scan fault lands {@code other} counted in {@code caught};
     *  NEITHER bumps the {@code other} counter, which stays "the beam scan ran clean and nothing matched". */
    private String resolveKiller(Object source, CombatEntityAPI target) {
        try {
            CombatEngineAPI eng = engine.get();
            List<BeamAPI> beams = eng != null ? eng.getBeams() : null;
            if (beams != null) {
                int n = 0;
                for (BeamAPI b : beams) {
                    if (++n > SCAN_MAX_BEAMS) {
                        scanCapped++;
                        return "other";
                    }
                    if (b != null && b.getSource() == source && b.getDamageTarget() == target) {
                        WeaponAPI w = b.getWeapon();
                        String id = w != null ? w.getId() : null;
                        beam++;
                        return "beam:" + (id != null ? token(id) : "?");
                    }
                }
            }
            other++;
            return "other";
        } catch (Throwable t) {
            countCaught(t);
            return "other";
        }
    }

    private String sourceToken(Object source) {
        if (source == null) {
            return "none";
        }
        ShipAPI own = ownShip.get();
        if (own != null && source == own) {
            return "own";
        }
        if (source instanceof ShipAPI) {
            return "ship";
        }
        return "obj:" + token(source.getClass().getSimpleName());
    }

    /** One whitespace-free killer token — the SAME charset as
     *  the server's {@code HullSourceLedger.sanitize} ({@code [A-Za-z0-9_.:?-]} kept, the rest {@code _},
     *  capped at {@link #TOKEN_MAX}, never empty → {@code -}); the client cannot import server code. A raw
     *  weapon id with whitespace would split the grader's {@code killer=} / {@code ksrc=} groups. */
    private static String token(String s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        int n = Math.min(s.length(), TOKEN_MAX);
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == ':' || c == '-' || c == '?';
            b.append(ok ? c : '_');
        }
        return b.toString();
    }

    private static float nz(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? 0f : v;
    }

    private void countCaught(Throwable t) {
        caught++;
        if (!caughtLogged) {
            caughtLogged = true;
            PilotWatchLog.error("predict missile-killer feed: callback threw (counted; later throws count"
                    + " silently, this row prints once per feed)", t);
        }
    }

    /** Remove-and-return the missile's record (the witness retire); null = no tracked report landed. */
    Killer take(Object missile) {
        return byMissile.remove(missile);
    }

    /** Round reset: dead-round records get no retire row, never a stale one. */
    void clear() {
        byMissile.clear();
    }

    /** Smoke seam: one counter by FIELD name. The counters are no longer printed — their {@code killer_*}
     *  tokens on the {@code predict dmg-witness summary} line were deleted as unread (three flight
     *  grades read none) — but the smoke's K rows still pin every count. An unknown name throws.
     * */
    long counterForTest(String field) {
        switch (field) {
            case "fed": return fed;
            case "untracked": return untracked;
            case "beam": return beam;
            case "other": return other;
            case "scanCapped": return scanCapped;
            case "overflow": return overflow;
            case "nullResult": return nullResult;
            case "caught": return caught;
            case "self": return self;
            default: throw new IllegalArgumentException("no killer-feed counter " + field);
        }
    }
}
