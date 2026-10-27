package cc.starsector.client;

import cc.starsector.client.net.ServerBeamState;
import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The DEM slaved-drone PRESENTER (the beam-presentation lane's
 * DEM-family extension; NEW-LANE LAW: collaborator, not in-plugin — and deliberately NOT
 * inside {@link MissilePoseLane}, whose growth watch-item names exactly this second concern).
 * Rides the SAME knob as its parent lane: {@code cc.predict.beamPresentation} =
 * {@code off|meter|apply} ({@code meter} counts matches, mints nothing).
 *
 * <p>WHAT IT DOES: vanilla DEM missiles (hydra/gorgon/dragonfire family) fire their beam
 * from a cosmetic fx drone hard-SLAVED to the missile every frame (mint, slave and cleanup
 * all in DEMScript). Headless the engine still fires that
 * beam, and the wire carries it SOURCELESS (v12 sentinel pair — the fx drone is not a
 * roster ship; census-measured). This presenter re-mints the vanilla presentation
 * client-side at ZERO wire bytes: a sourceless beam record is matched to an adopted DEM
 * child puppet, the child gets the vanilla cosmetic drone ({@code createFXDrone}, weapon =
 * the beam's dictionary-resolved content key — generic across the family, no hardcoded
 * weapon list), the drone's pose is slaved to the child's puppet pose exactly as vanilla
 * does, and FIRE is relayed per render frame while the beam persists (the FLAG_FIRING
 * relay pattern: the local engine's OWN beam cycle renders/terminates the beam).
 *
 * <p>MATCHING (census-proven): the wire beam point is the engine's {@code getTo()}
 * FAR END, NOT the muzzle. The wire carries NO missile facing —
 * the client's derived puppet facing (atan2 of wire pos deltas) is the flight TANGENT,
 * p50 68° off the true beam bearing during DEM standoff/circle — so facing is never a gate
 * here (the facing-cone matcher's sibling mis-binds were the DOMINANT regime).
 * Per sourceless beam: candidates = puppet twins of the beam's OWN owner whose spec
 * {@code behaviorJSON} names the resolved key as {@code payloadWeaponId} /
 * {@code targetingLaserId} (the exact recipe vanilla's DEMScript ctor reads — the family
 * gate); gate = far-point distance ≤ weapon-spec range (+ targeting-laser bonus when
 * applicable) + {@link #RANGE_SLACK}; NEAREST UNCLAIMED wins — per-fresh-pass exclusivity,
 * one beam per child (vanilla is structurally 1:1; far points cluster on the
 * target hull, so unexclusive nearest COLLAPSES a salvo onto the target-closest siblings —
 * one recorded reel: 28/60 children starved to zero presented beams, 88.5% of multi-beam ticks
 * collided, up to 5 beams on one child = a player's "never fired" percept. A permuted
 * pairing only moves the beam ORIGIN; the far end stays wire truth).
 * One drone per child (vanilla shape); a match refreshes the child's track, absence from
 * {@link #ABSENCE_FRESH_MISSES} consecutive FRESH frames (or child death) retires the
 * drone ({@code removeEntity}, the vanilla cleanup). Unmatched sourceless beams count +
 * capped-log, never present (a decide-later case).
 *
 * <p>AIM (far-point assist — the faithful lane): the far point IS the server's own
 * beam end, so presenting child→far reproduces the server's beam LINE exactly. Per render
 * frame the weapon aims {@code setFacing(bearing child→far)} + {@code updateBeamFromPoints},
 * FIRE leads at the far point itself (not the vanilla facing-lead — the derived facing is
 * a reconstruction artifact), and the presented length clamps ≈ |child→far| via
 * {@code BeamWeaponRangeBonus} so lawful misses don't overshoot to weapon max range.
 * The drone BODY still wears the child's puppet pose verbatim, as vanilla does.
 * Since the v16 missile pose the child wears the server-minted wire facing
 * directly (which the beam-bearing law approximated: vanilla fires the beam
 * along the drone facing) — the bind is purely the beam presentation's own origin/aim seam.
 *
 * <p>SHOOTS-ONCE (the two strokes that killed the double-shot percept). <b>S1 twin
 * collapse:</b> the ENGINE's own {@code getBeams()} carries EVERY sourceless DEM payload beam
 * as TWO co-located records — bit-identical far points, consecutive per-frame ordinals,
 * 3179/3179 = 100% on a recorded reel while every ship-beam family is 1.00; our
 * binding is a straight one-view-per-beam pass, so the duplication is upstream of us. Left
 * raw, the per-pass exclusivity below forcibly pushes the TWIN onto a different, NON-FIRING
 * sibling — 16/60 children showed ≥2 beam windows instead of the true 4/60. So
 * co-located sourceless records (same owner + content + exact far point) collapse to ONE
 * logical beam BEFORE the exclusivity pass. <b>S2 fire cap + latch:</b> vanilla's FIRE state
 * accumulates {@code amount} and at {@code elapsedFiring >= firingTime} goes DONE — the fx
 * drone is removed and the missile fizzles. Without that cap
 * the presented beam runs the LOCAL weapon's own burst cycle (hydra_payload = 1.70 s vs the
 * spec's 1.25 s FIRE window) and RE-IGNITES with no new wire beam onset, so the relay caps at
 * the child spec's own {@code firingTime} and retires there, and a fired-latch keyed on the
 * missile lane's WIRE id (not the local body identity — a puppet re-adopt mints a NEW body)
 * keeps a burnt child from ever re-minting. Both strokes reproduce vanilla's own law, so
 * neither is an engine-unfaithful shortcut; both are presentation-lane, zero wire bytes, no new knob.
 *
 * <p>KNOWN-BENIGN: OWN-ship DEM children are never candidates (own missiles fly locally
 * under the real DEMScript — drone+beam already native; no puppet twin exists), though in
 * a multi-seat match an own-side sourceless echo may bind a same-side REMOTE puppet
 * sibling near the same target. Under far-point aim ANY sibling mis-bind moves only the
 * beam ORIGIN — the far end stays wire truth. Client-local beam damage = the
 * presented-then-reconciled class of the parent lane (no new faithfulness question);
 * the RAW rows stay untouched.
 *
 * <p>Render-thread only, all-public-API (loader-B safe), fail-soft throughout.
 */
final class DemDronePresenter {

    /** Knob statics — the parent beam-presentation lane's mode, re-parsed here (the
     *  MissilePoseLane collaborator idiom; THROWS at class load on an invalid value). */
    static final String DEM_MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.beamPresentation",
            System.getProperty("cc.predict.beamPresentation", "apply"));
    static final boolean DEM_METER = !"off".equals(DEM_MODE);
    static final boolean DEM_APPLY = "apply".equals(DEM_MODE);
    /** Per-event log cap (the beam-lane discipline; counters continue past it). */
    private static final int DEM_LOG_MAX = 40;
    /** Range-gate slack (su) past the weapon-spec max range (the census reach law
     *  proved 3220/3220 at spec+150; 200 is its superset). */
    private static final float RANGE_SLACK = 200f;
    /** Range gate when the weapon spec is unreadable (fail-open bound, counted normally). */
    private static final float FALLBACK_RANGE = 1500f;
    /** Track absence tolerance in consecutive FRESH frames (the missile-lane G). */
    private static final int ABSENCE_FRESH_MISSES = 6;
    /** Presented-length clamp floor (su) — a far point atop the child never drives the
     *  beam range to zero. */
    private static final float MIN_BEAM_LEN = 10f;

    /** Vanilla's own absent-key default for {@code firingTime}. */
    private static final float DEFAULT_FIRING_TIME = 1.25f;

    /** One child's DEM-recipe read (per projectile-spec id, cached): the behaviorJSON
     *  fields vanilla's DEMScript ctor reads. {@code null} entries mark non-DEM specs. */
    private static final class Recipe {
        final String payloadId;
        final String targetingId;
        final float targetingRange;
        /** The FIRE-phase duration — vanilla installs it as the missile's own
         *  {@code maxFlightTime} at SIGNAL→FIRE and ends FIRE at it. Spec range across the
         *  vanilla DEM family: 1.0 (dragon) … 10.5 (gazer); never a rolled range. */
        final float firingTime;

        Recipe(String payloadId, String targetingId, float targetingRange, float firingTime) {
            this.payloadId = payloadId;
            this.targetingId = targetingId;
            this.targetingRange = targetingRange;
            this.firingTime = firingTime;
        }
    }

    /** One live per-child presentation: the minted drone + its single beam weapon. */
    private static final class Entry {
        final MissileAPI child;
        final ShipAPI drone;
        final WeaponAPI weapon;
        final String key;
        /** The child's WIRE id at mint (the fired-latch key; -1 = unlinked puppet). */
        final long wireId;
        /** FIRE-relay cap in seconds — the spec's own {@code firingTime} on a PAYLOAD entry,
         *  0 on a targeting-laser entry (vanilla caps THAT phase by {@code targetingTime},
         *  a different field, and it is not the double-shot class). */
        final float fireCap;
        /** Last matched wire far point (world) — the aim + length-clamp anchor. */
        float farX;
        float farY;
        int missedFresh;
        boolean matched;
        /** Seconds of FIRE relay accumulated (vanilla's {@code elapsedFiring}). */
        float firing;

        Entry(MissileAPI child, ShipAPI drone, WeaponAPI weapon, String key,
                long wireId, float fireCap) {
            this.child = child;
            this.drone = drone;
            this.weapon = weapon;
            this.key = key;
            this.wireId = wireId;
            this.fireCap = fireCap;
        }
    }

    private final PredictReconcileCombatPlugin plugin;
    private final MissilePoseLane missileLane;
    private final PredictReconcileCombatPlugin.PredictLane lane =
            new PredictReconcileCombatPlugin.PredictLane(DEM_LOG_MAX);

    // === Presenter state (render-thread only; per-round, cleared at resetForMatchReset) ===
    private final IdentityHashMap<MissileAPI, Entry> entries =
            new IdentityHashMap<MissileAPI, Entry>();
    /** Per-spec DEM recipe cache (content-scoped — survives rounds; null = not DEM). */
    private final Map<String, Recipe> recipes = new HashMap<String, Recipe>();
    /** Per-weapon-key spec range cache (content-scoped). */
    private final Map<String, Float> ranges = new HashMap<String, Float>();
    /** fired-latch: WIRE ids whose payload burn already ran its vanilla
     *  {@code firingTime} — they never present a DEM beam again this round. Keyed on the
     *  wire id (not the body) so it survives a puppet re-adopt, which mints a NEW body. */
    private final Set<Long> firedWireIds = new HashSet<Long>();

    // === Cumulative counters (survive rounds — the missile-lane discipline) ===
    private int beamsSeen;      // predict_dem_beams_seen (sourceless records considered)
    private int specUnresolved; // predict_dem_spec_unresolved (contentId not in catalog yet)
    private int matchedRecords; // predict_dem_matched (beam records that found their child)
    private int unmatched;      // predict_dem_unmatched (count+skip)
    private int dronesMinted;   // predict_dem_drones_minted
    private int mintFailed;     // predict_dem_mint_failed
    private int rekeyed;        // predict_dem_rekeyed (weapon key changed mid-life -> re-mint)
    private int exclRebinds;    // predict_dem_excl_rebinds (beams a collision would
                                //   have stolen — the exclusivity meter)
    private int fireFrames;     // predict_dem_fire_frames (render frames relaying FIRE)
    private int trackEnded;     // predict_dem_track_ended (beam absence retires)
    private int childGone;      // predict_dem_child_gone (child body left play)
    private int twinsCollapsed; // predict_dem_twins_collapsed (engine-side twin
                                //   records dropped — a future engine change shows up HERE)
    private int fireCapped;     // predict_dem_fire_capped (burns ended at firingTime)
    private int fireLatched;    // predict_dem_fire_latched (beams refused to a burnt wire id)
    private int capUnkeyed;     // predict_dem_cap_unkeyed (capped with no wire id -> no latch)
    private int nativeBound;    // predict_dem_native_bound (bound by the server's own
                                //   child attribution — no guessing; the target state)
    private int nativeUnlinked; // predict_dem_native_unlinked (emitter id present but no live
                                //   linked puppet -> fell back to the nearest-child matcher)
    private int nativeOffFamily;  // predict_dem_native_offfamily (named child's spec has no
                                  //   matching DEM recipe — would mint fireCap=0; refused)
    private int nativeNotInPlay;  // predict_dem_native_notinplay (named child's body gone)
    private int nativeOutOfReach; // predict_dem_native_outofreach (far point beyond the
                                  //   child's own weapon reach — puppet desync; refused)
    private int nativeSecondRay;  // predict_dem_native_secondray (a second DISTINCT ray from
                                  //   an already-claimed child; 1:1 presentation holds)
    private int sourceless;     // predict_dem_sourceless (record carried NO source at all —
                                //   knob-off server, mod DEMScript subclass, or a drone the
                                //   server could not resolve; the shape before server attribution)

    DemDronePresenter(PredictReconcileCombatPlugin plugin, MissilePoseLane missileLane) {
        this.plugin = plugin;
        this.missileLane = missileLane;
    }

    /**
     * The per-frame drive: FRESH frames run the match pass (beam records only change on
     * fresh frames); every render frame runs the slave+fire pass (beam sustain requires
     * per-frame re-assert — the parent lane's law).
     */
    void advance(ServerSnapshotFrame snapshot, boolean freshSnapshot) {
        if (!DEM_METER) {
            return;
        }
        try {
            if (freshSnapshot && snapshot != null) {
                matchPass(snapshot);
            }
            if (DEM_APPLY) {
                presentPass();
            }
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /** FRESH-frame match pass: prune dead children, bind sourceless beams to DEM children,
     *  tick absence on unrefreshed tracks. */
    private void matchPass(ServerSnapshotFrame snapshot) {
        for (Iterator<Map.Entry<MissileAPI, Entry>> it = entries.entrySet().iterator();
                it.hasNext();) {
            Entry e = it.next().getValue();
            e.matched = false;
            if (plugin.engine == null || !plugin.engine.isEntityInPlay(e.child)) {
                retire(e);
                it.remove();
                childGone++;
            }
        }
        List<ServerBeamState> beams = snapshot.beams();
        if (beams != null) {
            // per-pass exclusivity: a child claimed by one beam this fresh frame is
            // no candidate for the next (identity set — the entries-map keying).
            Set<MissileAPI> claimed = java.util.Collections.newSetFromMap(
                    new IdentityHashMap<MissileAPI, Boolean>());
            // The ENGINE's getBeams() holds TWO co-located records per sourceless
            // DEM payload beam (100%, ratio 2.00, DEM-only). Collapse them to ONE
            // logical beam HERE — before the exclusivity pass — or the twin is forcibly
            // pushed onto a non-firing sibling and reads as a second shot. Key = the wire
            // record's identity as the census measured it (owner + content + exact far-point
            // bits); owner/content are in the key so a multi-family scene can never merge
            // two different weapons that happen to share an impact point.
            Set<String> distinctBeams = new HashSet<String>();
            for (ServerBeamState beam : beams) {
                if (beam.hasShipSource()) {
                    continue; // roster beams are the parent lane's (relay + aim halves)
                }
                beamsSeen++;
                if (!distinctBeams.add(twinKey(beam))) {
                    twinsCollapsed++;
                    continue;
                }
                String key = beam.contentId() >= 1
                        ? plugin.contentCatalog.get(beam.contentId()) : null;
                if (key == null || key.isEmpty()) {
                    specUnresolved++; // dictionary race — the record retries next frame
                    continue;
                }
                matchBeam(beam, key, claimed);
            }
        }
        for (Iterator<Map.Entry<MissileAPI, Entry>> it = entries.entrySet().iterator();
                it.hasNext();) {
            Entry e = it.next().getValue();
            if (e.matched) {
                e.missedFresh = 0;
            } else if (++e.missedFresh >= ABSENCE_FRESH_MISSES) {
                trackEnded++;
                lane.log("predict dem-track END: key=" + FallbackAnnounce.sanitize(e.key, 64) + " gone " + e.missedFresh
                        + " fresh frames -> remove drone");
                retire(e);
                it.remove();
            }
        }
    }

    /** Twin key: exact-bit far point + owner + content — the same identity the
     *  offline census collapsed on (`HydraBeamWindows.demBeams`), so the deployed matcher
     *  and the verifier answer the same question. Raw bits, never an epsilon: the measured
     *  twins are BIT-identical, and two genuinely distinct beams that agreed to the last
     *  float bit would be indistinguishable on the wire anyway. */
    private static String twinKey(ServerBeamState beam) {
        // When the server names the emitter, scope the SAME far-point rule to that
        // (child, drone-mount) instead of (owner, content) — exactly as conservative as
        // before, and immune to two different children whose far points happen to agree.
        String scope = beam.hasDemChildSource()
                ? "m" + beam.sourceEntityId() + ":" + beam.sourceMountIndex()
                : beam.owner() + ":" + beam.contentId();
        return scope
                + ":" + Float.floatToIntBits(beam.toX())
                + ":" + Float.floatToIntBits(beam.toY());
    }

    /** Bind one sourceless beam record to the census-proven child (same owner +
     *  in-family + far point within reach; NEAREST UNCLAIMED wins — the claimed set
     *  makes the pass 1:1 like vanilla, and a claimed-but-closer candidate counts a rebind;
     *  the derived puppet facing is a flight tangent and never gates); a bound child with a
     *  live entry refreshes it and re-anchors its far point. */
    private void matchBeam(ServerBeamState beam, String key, Set<MissileAPI> claimed) {
        // AUTHORITATIVE BINDING FIRST. The server now resolves a DEM
        // payload beam's own fx drone back to the child missile that minted it (engine
        // truth: the child's DEMScript holds the drone) and carries that child's stable
        // v13 wire id in the beam's source slot at ZERO extra bytes. When it is there,
        // the whole nearest-child guess below is skipped: no distance gate, no
        // exclusivity, no per-pass amnesia — which is what made a warhead's beam snap to
        // another aim point mid-burn and back (15.6% of bound child-tick
        // pairs swapped, 66% of children showed an A->B->A return, by census).
        // The heuristic survives ONLY as the labelled fallback below, for a beam whose
        // child has no live linked puppet yet and for a knob-off / mod-subclass server
        // that emits the sentinel pair (a known caveat).
        if (beam.hasDemChildSource()) {
            MissileAPI owner = missileLane.bodyForWireId(beam.sourceEntityId());
            if (owner != null) {
                // The server NAMED this child. Once a named child exists we never fall
                // back to guessing: a gate refusal here means "present nothing", because
                // the alternative — handing the record to the nearest-child pass — would
                // deliberately bind it to a child the server says is NOT the emitter.
                nativeResolve(beam, key, owner, claimed);
                return;
            }
            nativeUnlinked++; // id names a child with no live linked puppet -> fall through
            // Fallback X5: the server NAMED this child — the guess below may bind the beam to
            // a child the server says is NOT the emitter (the retired heuristic re-engages).
            FallbackAnnounce.announceOnce("x5-dem-child-unlinked#" + beam.sourceEntityId(),
                    "named child has no live puppet -> nearest-child guess");
        } else {
            sourceless++;
        }
        MissileAPI best = null;
        float bestDist = Float.MAX_VALUE;
        float minClaimedDist = Float.MAX_VALUE;
        for (MissileAPI body : missileLane.puppetBodies()) {
            Recipe r = recipeOf(body);
            if (r == null || !(key.equals(r.payloadId) || key.equals(r.targetingId))) {
                continue;
            }
            if (body.getOwner() != beam.owner()) {
                continue;
            }
            Vector2f loc = body.getLocation();
            if (loc == null || plugin.engine == null || !plugin.engine.isEntityInPlay(body)) {
                continue;
            }
            float dx = beam.toX() - loc.x;
            float dy = beam.toY() - loc.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float reach = rangeOf(key)
                    + (key.equals(r.targetingId) ? r.targetingRange : 0f) + RANGE_SLACK;
            if (dist > reach) {
                continue;
            }
            if (claimed.contains(body)) {
                minClaimedDist = Math.min(minClaimedDist, dist);
                continue;
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = body;
            }
        }
        if (best != null && minClaimedDist < bestDist) {
            exclRebinds++; // exclusivity engaged: no-exclusivity would have collided here
        }
        if (best == null) {
            unmatched++;
            lane.log("predict dem-beam UNMATCHED: key=" + FallbackAnnounce.sanitize(key, 64) + " to=(" + beam.toX() + ","
                    + beam.toY() + ") (no same-owner in-family child in reach — skipped)");
            return;
        }
        bind(beam, key, best, claimed);
    }

    /**
     * Authoritative path: the server named the emitting child and its puppet is live.
     * The nearest-child SEARCH is gone, but every SANITY gate the search carried is kept —
     * they were never about picking a child, they bound what a bind is allowed to mean:
     * <ul>
     * <li><b>family/recipe</b> — {@link #mint} derives the fire cap from the
     *     child's own {@code payloadWeaponId}; a child whose spec has no DEM recipe (a
     *     puppet spawned from the LAUNCHER recipe during a dictionary race, or a cross-spec
     *     mislink) would mint with {@code fireCap = 0}, i.e. UNCAPPED, and the local
     *     weapon's longer burst cycle would re-ignite the presented beam — the double-shot
     *     percept S2 exists to kill.</li>
     * <li><b>reach</b> — {@code presentPass} grants the drone a beam-range bonus of
     *     {@code |child→far| − specRange} with a floor and no ceiling, so a mislinked or
     *     badly-desynced puppet would stretch a payload beam across the map.</li>
     * <li><b>already claimed</b> — one presented beam per child per pass (the vanilla 1:1
     *     shape). A drone CAN emit two genuinely distinct rays (a dragonfire targeting
     *     laser sweeps its two barrels to different angles), so this is a real case, not
     *     just the collapsed twins; the second ray presents nothing rather than being
     *     pushed onto an innocent sibling.</li>
     * </ul>
     * Each refusal is metered separately so a census can tell them apart.
     */
    private void nativeResolve(ServerBeamState beam, String key, MissileAPI owner,
            Set<MissileAPI> claimed) {
        Recipe r = recipeOf(owner);
        if (r == null || !(key.equals(r.payloadId) || key.equals(r.targetingId))) {
            nativeOffFamily++;
            lane.log("predict dem-native OFF-FAMILY: key=" + FallbackAnnounce.sanitize(key, 64) + " child="
                    + beam.sourceEntityId() + " spec=" + specIdOf(owner) + " (no presentation)");
            return;
        }
        Vector2f loc = owner.getLocation();
        if (loc == null || plugin.engine == null || !plugin.engine.isEntityInPlay(owner)) {
            nativeNotInPlay++;
            return;
        }
        float dx = beam.toX() - loc.x;
        float dy = beam.toY() - loc.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float reach = rangeOf(key)
                + (key.equals(r.targetingId) ? r.targetingRange : 0f) + RANGE_SLACK;
        if (dist > reach) {
            nativeOutOfReach++;
            lane.log("predict dem-native OUT-OF-REACH: key=" + FallbackAnnounce.sanitize(key, 64) + " child="
                    + beam.sourceEntityId() + " dist=" + dist + " > " + reach
                    + " (puppet desync — no presentation)");
            return;
        }
        if (claimed.contains(owner)) {
            nativeSecondRay++;
            return;
        }
        nativeBound++;
        bind(beam, key, owner, claimed);
    }

    /** The shared bind tail: claim the child, honour the fired-latch, mint or
     *  refresh its drone entry, and re-anchor the far point. Reached from the
     *  authoritative path and from the nearest-child fallback alike, so the meters and the
     *  latch behave identically whichever named the child. */
    private void bind(ServerBeamState beam, String key, MissileAPI best, Set<MissileAPI> claimed) {
        matchedRecords++;
        claimed.add(best); // One beam per child per pass (meter mode included)
        // Latch: a child whose payload burn already ran its vanilla firingTime is
        // DONE — vanilla removed its drone and fizzled the missile, so no later record may
        // re-ignite it. Claimed above first, so the attribution meters stay honest and the
        // refused beam never spills onto a sibling.
        if (firedWireIds.contains(missileLane.wireIdFor(best))) {
            fireLatched++;
            return;
        }
        Entry e = entries.get(best);
        if (e != null && !e.key.equals(key)) {
            rekeyed++; // targeting -> payload phase change: re-mint with the new weapon
            retire(e);
            entries.remove(best);
            e = null;
        }
        if (e == null) {
            if (!DEM_APPLY) {
                lane.log("predict dem-match: key=" + FallbackAnnounce.sanitize(key, 64) + " child="
                        + missileLane.wireIdFor(best) + " (meter)");
                return;
            }
            e = mint(best, key);
            if (e == null) {
                return;
            }
            entries.put(best, e);
        }
        e.matched = true;
        e.farX = beam.toX();
        e.farY = beam.toY();
    }

    /** The vanilla cosmetic-drone mint (cosmetic subset: no mothership
     *  AI flag / source-ship damage mults — the source ship is unknown by construction). */
    private Entry mint(MissileAPI child, String key) {
        try {
            ShipHullSpecAPI hull = Global.getSettings().getHullSpec("dem_drone");
            ShipVariantAPI v = Global.getSettings().createEmptyVariant("dem_drone", hull);
            v.addWeapon("WS 000", key);
            WeaponGroupSpec g = new WeaponGroupSpec(WeaponGroupType.LINKED);
            g.addSlot("WS 000");
            v.addWeaponGroup(g);
            ShipAPI drone = plugin.engine.createFXDrone(v);
            drone.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
            drone.setOwner(child.getOwner());
            // Beam range rides the per-frame length clamp (presentPass) — the
            // mint sets no range mod; targeting-laser reach lives in the match gate only.
            drone.getMutableStats().getHullDamageTakenMult().modifyMult("cc_dem", 0f);
            drone.setDrone(true);
            drone.setCollisionClass(CollisionClass.NONE);
            drone.setShipAI(new InertShipAI());
            drone.giveCommand(ShipCommand.SELECT_GROUP, null, 0);
            plugin.engine.addEntity(drone);
            WeaponAPI weapon = drone.getWeaponGroupsCopy().get(0).getWeaponsCopy().get(0);
            weapon.setKeepBeamTargetWhileChargingDown(true);
            weapon.setScaleBeamGlowBasedOnDamageEffectiveness(false);
            dronesMinted++;
            // The FIRE cap is the child spec's OWN firingTime and applies to the
            // PAYLOAD phase only — vanilla enters FIRE (group 1) at the SIGNAL edge, and the
            // first payload beam record IS that edge as the wire shows it. A targeting-laser
            // entry is the earlier SIGNAL phase (capped by targetingTime) and stays uncapped
            // here; it retires on beam absence exactly as before.
            Recipe r = recipeOf(child);
            float cap = r != null && key.equals(r.payloadId) ? r.firingTime : 0f;
            lane.log("predict dem-mint: key=" + FallbackAnnounce.sanitize(key, 64) + " spec=" + specIdOf(child)
                    + " fireCap=" + cap + "s");
            return new Entry(child, drone, weapon, key, missileLane.wireIdFor(child), cap);
        } catch (Throwable t) {
            mintFailed++;
            lane.log("predict dem-mint FAILED: key=" + FallbackAnnounce.sanitize(key, 64) + " (" + t + ")");
            return null;
        }
    }

    /** Per-RENDER-frame slave + FIRE relay: the drone BODY wears the child's puppet pose
     *  verbatim, the WEAPON aims child→far-point (the wire
     *  far point is the engine's own beam end; the derived child facing is a flight
     *  tangent, never the aim), the presented length clamps ≈ |child→far|, and the FIRE
     *  command re-asserts at the far point into the local engine's own beam cycle. */
    private void presentPass() {
        // Vanilla's FIRE accumulator is the plugin's own per-frame `amount`; the enclosing
        // plugin advance() early-returns on isPaused() before this lane runs, so the engine's
        // frame delta is the same quantity (and never ticks while paused).
        float elapsed = 0f;
        try {
            elapsed = plugin.engine == null ? 0f : plugin.engine.getElapsedInLastFrame();
        } catch (Throwable ignore) {
            // unreadable delta: no cap this frame (fail-soft — never break the watch)
        }
        for (Iterator<Map.Entry<MissileAPI, Entry>> it = entries.entrySet().iterator();
                it.hasNext();) {
            Entry e = it.next().getValue();
            try {
                if (plugin.engine == null || !plugin.engine.isEntityInPlay(e.child)) {
                    retire(e);
                    it.remove();
                    childGone++;
                    continue;
                }
                MissileAPI child = e.child;
                ShipAPI drone = e.drone;
                drone.setOwner(child.getOwner());
                Vector2f loc = child.getLocation();
                if (loc == null || drone.getLocation() == null) {
                    continue; // poseless frame: hold fire, retry next frame (fail-soft)
                }
                drone.getLocation().set(loc);
                drone.setFacing(child.getFacing());
                if (child.getVelocity() != null && drone.getVelocity() != null) {
                    drone.getVelocity().set(child.getVelocity());
                }
                drone.setAngularVelocity(child.getAngularVelocity());
                float dx = e.farX - loc.x;
                float dy = e.farY - loc.y;
                float len = Math.max((float) Math.sqrt(dx * dx + dy * dy), MIN_BEAM_LEN);
                float bearing = (float) Math.toDegrees(Math.atan2(dy, dx));
                drone.getMutableStats().getBeamWeaponRangeBonus()
                        .modifyFlat("cc_dem", len - rangeOf(e.key));
                Vector2f far = new Vector2f(e.farX, e.farY);
                if (drone.getMouseTarget() != null) {
                    drone.getMouseTarget().set(far);
                }
                e.weapon.setFacing(bearing);
                e.weapon.updateBeamFromPoints();
                drone.giveCommand(ShipCommand.FIRE, far, 0);
                fireFrames++;
                // Vanilla issues FIRE and THEN tests `elapsedFiring >= firingTime`
                // in its own script, so the capping frame still fires — same order here.
                // At the cap vanilla goes DONE and the fx drone is gone the next frame;
                // we retire it now. Uncapped, the presented beam would run the
                // LOCAL weapon's longer burst cycle and re-ignite with no new wire onset.
                if (e.fireCap > 0f && (e.firing += elapsed) >= e.fireCap) {
                    fireCapped++;
                    // The mint-time id can be stale — a child minted before
                    // its puppet linked carries -1 forever, and a re-adopted puppet can carry
                    // a NEW wire id. Re-read at cap time; the mint-time value is the fallback
                    // (a child whose puppet has since unlinked still latches on what it had).
                    long latchId = missileLane.wireIdFor(e.child);
                    if (latchId < 0L) {
                        latchId = e.wireId;
                    }
                    if (latchId >= 0L) {
                        firedWireIds.add(latchId);
                    } else {
                        capUnkeyed++; // unlinked puppet: cap holds, but no re-mint latch
                    }
                    lane.log("predict dem-fire CAP: key=" + FallbackAnnounce.sanitize(e.key, 64) + " wire=" + latchId
                            + " fired=" + e.firing + "s >= " + e.fireCap + "s -> remove drone");
                    retire(e);
                    it.remove();
                }
            } catch (Throwable ignore) {
                // presentation lane; a broken entry retires next match pass
            }
        }
    }

    /** The vanilla cleanup: the fx drone is removed outright. */
    private void retire(Entry e) {
        try {
            if (e.drone != null && plugin.engine != null) {
                plugin.engine.removeEntity(e.drone);
            }
        } catch (Throwable ignore) {
        }
    }

    /** A no-op {@link ShipAIPlugin} so the local engine never flies/fights the cosmetic
     *  drone — pose is slaved and FIRE relayed per render frame (the replica inert-install
     *  idiom; mirrors {@link PredictReconcileCombatPlugin}'s {@code InertShipAI}). */
    private static final class InertShipAI implements ShipAIPlugin {
        private final ShipwideAIFlags flags = new ShipwideAIFlags();
        private final ShipAIConfig config = new ShipAIConfig();

        @Override public void advance(float amount) { /* inert: issue no fire/maneuver commands */ }
        @Override public void setDoNotFireDelay(float amount) { /* no-op */ }
        @Override public void forceCircumstanceEvaluation() { /* no-op */ }
        @Override public boolean needsRefit() { return false; }
        @Override public ShipwideAIFlags getAIFlags() { return flags; }
        @Override public void cancelCurrentManeuver() { /* no-op */ }
        @Override public ShipAIConfig getConfig() { return config; }
    }

    /** The child's DEM recipe off its OWN spec behaviorJSON (the DEMScript ctor source),
     *  cached per spec id; null = not a DEM missile (the family gate). */
    private Recipe recipeOf(MissileAPI body) {
        String specId = specIdOf(body);
        if (specId == null) {
            return null;
        }
        if (recipes.containsKey(specId)) {
            return recipes.get(specId);
        }
        Recipe r = null;
        try {
            JSONObject json = body.getSpec().getBehaviorJSON();
            if (json != null) {
                String payload = emptyToNull(json.optString("payloadWeaponId", null));
                String targeting = emptyToNull(json.optString("targetingLaserId", null));
                if (payload != null || targeting != null) {
                    // Vanilla ctor default; array-valued fields take their max
                    // (a presentation bound, not a roll — vanilla randomizes in-range).
                    float tlRange = json.has("targetingLaserRange")
                            ? (float) json.optDouble("targetingLaserRange", 600.0)
                            : Math.max(maxOf(json, "triggerDistance", 500f),
                                    maxOf(json, "preferredMinFireDistance", 0f)) + 200f;
                    // firingTime is a plain optDouble in the vanilla ctor — it does
                    // NOT go through the array-capable getValue helper the rolled fields use,
                    // so it never reads as a [min,max] range and never rolls per missile.
                    // Read it the same way (deliberately NOT maxOf) with vanilla's default.
                    float fireTime = (float) json.optDouble("firingTime", DEFAULT_FIRING_TIME);
                    r = new Recipe(payload, targeting, tlRange, fireTime);
                }
            }
            recipes.put(specId, r); // clean read only ((83) CL-10; null = genuinely not-DEM)
        } catch (Throwable ignore) {
            // 1290 (81) X6 + (83) CL-10: unreadable spec = not DEM THIS lookup, UNCACHED — the
            // next sighting retries (the announce dedupes per spec, so the retry loop stays quiet).
            FallbackAnnounce.announceOnce("x6-dem-recipe-unreadable#" + specId, "spec read threw "
                    + ignore.getClass().getSimpleName() + " -> not-DEM this lookup (uncached)");
        }
        return r;
    }

    /** The weapon-spec max range for the range gate (clean reads cached; a throw guesses the
     *  fail-open bound THIS lookup, uncached — (83) CL-10, next lookup retries). */
    private float rangeOf(String key) {
        Float cached = ranges.get(key);
        if (cached != null) {
            return cached;
        }
        float range = FALLBACK_RANGE;
        try {
            range = Global.getSettings().getWeaponSpec(key).getMaxRange();
            ranges.put(key, range); // clean read only ((83) CL-10)
        } catch (Throwable ignore) {
            // 1290 (81) X7 + (83) CL-10: announce once; uncached so the next lookup retries.
            FallbackAnnounce.announceOnce("x7-dem-range-unreadable#" + FallbackAnnounce.sanitize(key, 64),
                    "weapon-spec range read threw -> fallback range this lookup (uncached)");
        }
        return range;
    }

    /** Vanilla getValue's array form ({@code [min,max]}) read at its MAX; scalar/absent =
     *  {@code optDouble} with the given default. */
    private static float maxOf(JSONObject json, String key, float dflt) {
        try {
            JSONArray arr = json.optJSONArray(key);
            if (arr != null) {
                return (float) Math.max(arr.optDouble(0), arr.optDouble(1));
            }
            return (float) json.optDouble(key, dflt);
        } catch (Throwable ignore) {
            return dflt;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String specIdOf(MissileAPI missile) {
        try {
            return missile.getProjectileSpecId();
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** Round reset: totals line (the missile-lane discipline), drones removed, per-round
     *  state cleared — counters cumulative, recipe/range caches content-scoped (kept). */
    void resetForMatchReset() {
        PilotWatchLog.info("predict dem-lane match-reset totals: entries=" + entries.size()
                + " matched=" + matchedRecords + " unmatched=" + unmatched
                + " minted=" + dronesMinted + " ended=" + trackEnded);
        for (Entry e : entries.values()) {
            retire(e);
        }
        entries.clear();
        firedWireIds.clear(); // wire ids are per-round; a new round re-arms every child
    }

    /** The lane's greppable meter/census line (the shared meter-line discipline); silent at off. */
    void logMeter(String where) {
        if (!DEM_METER) {
            return;
        }
        PilotWatchLog.info("predict dem-meter [" + where + "]:"
                + " mode=" + DEM_MODE
                + " predict_dem_beams_seen=" + beamsSeen
                + " predict_dem_spec_unresolved=" + specUnresolved
                + " predict_dem_matched=" + matchedRecords
                + " predict_dem_unmatched=" + unmatched
                + " predict_dem_drones_minted=" + dronesMinted
                + " predict_dem_mint_failed=" + mintFailed
                + " predict_dem_rekeyed=" + rekeyed
                + " predict_dem_native_bound=" + nativeBound
                + " predict_dem_native_unlinked=" + nativeUnlinked
                + " predict_dem_native_offfamily=" + nativeOffFamily
                + " predict_dem_native_notinplay=" + nativeNotInPlay
                + " predict_dem_native_outofreach=" + nativeOutOfReach
                + " predict_dem_native_secondray=" + nativeSecondRay
                + " predict_dem_sourceless=" + sourceless
                + " predict_dem_excl_rebinds=" + exclRebinds
                + " predict_dem_twins_collapsed=" + twinsCollapsed
                + " predict_dem_fire_frames=" + fireFrames
                + " predict_dem_fire_capped=" + fireCapped
                + " predict_dem_fire_latched=" + fireLatched
                + " predict_dem_cap_unkeyed=" + capUnkeyed
                + " predict_dem_track_ended=" + trackEnded
                + " predict_dem_child_gone=" + childGone
                + " entries=" + entries.size());
    }
}
