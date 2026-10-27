package cc.starsector.server.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Non-held-lane engine-reflection collaborator of {@link EngineCombatBinding} (extracted
 * from it). Owns the reflection families that are NOT part
 * of the held pilot lane: the remnant (hulk/piece) sighting walk, the ALTERNATING active-tube
 * mirror, and the fog-of-war tracker re-mint. Bodies moved verbatim from
 * {@code EngineCombatBinding}; shared handles ({@code shipGetId}/{@code shipIsAlive}/
 * {@code shipClass}/weapon-group reads) stay declared in the binding and are passed in as refs,
 * while the tube-mirror group handles and the lazy fog handles live here exclusively. All
 * failure semantics are unchanged: fail-soft families stay fail-soft, and anything that funnels
 * through {@link EngineCombatBinding#invoke} keeps its fail-closed
 * {@link EngineCombatBinding.EngineBindingException} contract.
 */
final class EngineReflectionBinding {

    private final EngineCombatBinding ecb;
    private final Object engine;
    private final Class<?> shipClass;
    private final Method shipGetId; // nullable: lifecycle (stable id) view unavailable
    private final Method shipIsAlive; // nullable: lifecycle (liveness) view unavailable
    private final Method shipGetWeaponGroupsCopy; // nullable: group/autofire mirror unavailable
    private final Method shipGetSelectedGroupAPI; // nullable: group mirror unavailable
    private final Method groupGetType; // nullable: tube mirror unavailable
    private final Method groupGetActiveWeapon; // nullable: tube mirror unavailable
    private final Method groupGetWeaponsCopy; // nullable: tube mirror unavailable
    private final Method groupSetCurrentActive; // nullable: tube pointer write unavailable
    private final Object weaponGroupTypeAlternating; // nullable: ALTERNATING constant unavailable

    EngineReflectionBinding(EngineCombatBinding ecb, Object engine, Class<?> shipClass,
            Method shipGetId, Method shipIsAlive,
            Method shipGetWeaponGroupsCopy, Method shipGetSelectedGroupAPI,
            Method groupGetType, Method groupGetActiveWeapon, Method groupGetWeaponsCopy,
            Method groupSetCurrentActive, Object weaponGroupTypeAlternating) {
        this.ecb = ecb;
        this.engine = engine;
        this.shipClass = shipClass;
        this.shipGetId = shipGetId;
        this.shipIsAlive = shipIsAlive;
        this.shipGetWeaponGroupsCopy = shipGetWeaponGroupsCopy;
        this.shipGetSelectedGroupAPI = shipGetSelectedGroupAPI;
        this.groupGetType = groupGetType;
        this.groupGetActiveWeapon = groupGetActiveWeapon;
        this.groupGetWeaponsCopy = groupGetWeaponsCopy;
        this.groupSetCurrentActive = groupSetCurrentActive;
        this.weaponGroupTypeAlternating = weaponGroupTypeAlternating;
    }

    // ---- Remnant sighting walk. ----

    /**
     * Per-tick remnant sightings for the hulk-fracture detector —
     * one entry per {@code !alive} repository ship (hulks + pieces), carrying the stable engine
     * id (pieces inherit the parent hull's — the corpse-family key), the engine object
     * reference (piece identity across ticks; the positional shipId renumbers), and the bbox
     * area over {@code getExactBounds()} (the engine's own H cut-largest metric, per-piece
     * fail-soft to collisionRadius²), and the six-float corpse pose (per-piece
     * fail-soft to all-NaN: membership and pose are separate products; a pose read failure
     * must never null the walk). Whole-read fail-soft null on ANY reflective failure —
     * a partial remnant walk would read as spurious PIECE_REMOVED edges downstream, so the
     * detector must see all remnants or none.
     */
    List<cc.starsector.server.runtime.HulkFractureDetector.RemnantSighting>
            remnantSightingsOrNull() {
        if (shipGetId == null || shipIsAlive == null) {
            return null;
        }
        try {
            // Roster enumeration — a blank-id fx mint dying into a
            // !alive repo entry must not void the fracture detector's whole read.
            List<?> ships = ecb.rosterShipList();
            List<cc.starsector.server.runtime.HulkFractureDetector.RemnantSighting> sightings =
                    new ArrayList<>();
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object aliveValue = shipIsAlive.invoke(ship);
                if (!(aliveValue instanceof Boolean)) {
                    return null;
                }
                if ((Boolean) aliveValue) {
                    continue;
                }
                Object idValue = shipGetId.invoke(ship);
                if (!(idValue instanceof String)) {
                    return null;
                }
                String key = ((String) idValue).trim();
                if (key.isEmpty()) {
                    return null;
                }
                float[] pose = remnantPoseOrNull(ship);
                float[] renderOff = remnantRenderOffsetOrNull(ship);
                float roX = renderOff == null ? Float.NaN : renderOff[0];
                float roY = renderOff == null ? Float.NaN : renderOff[1];
                boolean moduleDetached = remnantModuleDetached(ship);
                sightings.add(pose == null
                        ? new cc.starsector.server.runtime.HulkFractureDetector.RemnantSighting(
                                key, ship, remnantBoundsArea(ship),
                                Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                                roX, roY, moduleDetached)
                        : new cc.starsector.server.runtime.HulkFractureDetector.RemnantSighting(
                                key, ship, remnantBoundsArea(ship),
                                pose[0], pose[1], pose[2], pose[3], pose[4], pose[5],
                                roX, roY, moduleDetached));
            }
            return sightings;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // ---- Corpse-pose read handles (lazy like the fog handles). ----
    private Method shipGetLocation;
    private Method shipGetVelocity;
    private Method shipGetFacing;
    private Method shipGetAngularVelocity;

    /**
     * Six-float corpse pose {@code {locX, locY, facing, velX, velY, angVel}}, or {@code null}
     * when any component is unreadable or non-finite — per-piece fail-soft (the detector
     * skips the piece's keyframe with a counter; the sighting itself still counts for
     * membership). Setters on the client side are corpse-safe and side-effect-free (engine
     * ground truth); these reads are the plain public getters — {@code getLocation()}/
     * {@code getVelocity()} live {@code Vector2f} refs read via their public getX/getY,
     * {@code getFacing()}/{@code getAngularVelocity()} plain floats.
     */
    private float[] remnantPoseOrNull(Object ship) {
        try {
            if (shipGetAngularVelocity == null) {
                Method loc = shipClass.getMethod("getLocation");
                Method vel = shipClass.getMethod("getVelocity");
                Method facing = shipClass.getMethod("getFacing");
                Method angVel = shipClass.getMethod("getAngularVelocity");
                shipGetLocation = loc;
                shipGetVelocity = vel;
                shipGetFacing = facing;
                shipGetAngularVelocity = angVel; // assigned LAST: the lazy-resolved sentinel
            }
            Object loc = shipGetLocation.invoke(ship);
            Object vel = shipGetVelocity.invoke(ship);
            Object locX = loc == null ? null : publicRead(loc, "getX");
            Object locY = loc == null ? null : publicRead(loc, "getY");
            Object velX = vel == null ? null : publicRead(vel, "getX");
            Object velY = vel == null ? null : publicRead(vel, "getY");
            Object facing = shipGetFacing.invoke(ship);
            Object angVel = shipGetAngularVelocity.invoke(ship);
            if (!(locX instanceof Number) || !(locY instanceof Number)
                    || !(velX instanceof Number) || !(velY instanceof Number)
                    || !(facing instanceof Number) || !(angVel instanceof Number)) {
                return null;
            }
            float[] pose = {
                    ((Number) locX).floatValue(), ((Number) locY).floatValue(),
                    ((Number) facing).floatValue(),
                    ((Number) velX).floatValue(), ((Number) velY).floatValue(),
                    ((Number) angVel).floatValue()};
            for (float component : pose) {
                if (!Float.isFinite(component)) {
                    return null;
                }
            }
            return pose;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // ---- Module-detach read (lazy like the pose handles). ----
    private Method shipGetStationSlot;
    private Method shipGetParentStation;

    /**
     * true iff this dead remnant reads MODULE-DETACHED — {@code
     * getParentStation()} present (only station modules ever carry one, and it SURVIVES the
     * detach: the death path reads it right after its own {@code setStationSlot(null)})
     * while {@code getStationSlot()} reads null (the
     * authoritative detach roll, or a late overkill detach).
     * Ordinary corpses carry no parent station ⇒ false. Per-piece fail-soft false on ANY
     * reflective failure — no detach edge is today's behavior, and the per-tick walk retries
     * naturally; the read must never null membership.
     */
    private boolean remnantModuleDetached(Object ship) {
        try {
            if (shipGetParentStation == null) {
                shipGetStationSlot = shipClass.getMethod("getStationSlot");
                shipGetParentStation = shipClass.getMethod("getParentStation"); // sentinel LAST
            }
            return shipGetParentStation.invoke(ship) != null
                    && shipGetStationSlot.invoke(ship) == null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ---- Remnant renderOffset read (lazy like the pose handles). ----
    private Method shipGetRenderOffset;

    /**
     * Two-float remnant renderOffset {@code {x, y}}, or {@code null} when unreadable or
     * non-finite — per-piece fail-soft (a NaN pair disables the cut-geometry capture for
     * the sighting, never membership). The plain public getter, a
     * live {@code Vector2f} — STATIC between splits, so prev-tick vs current differencing
     * recovers the carve's grid-snapped piece centers exactly.
     */
    private float[] remnantRenderOffsetOrNull(Object ship) {
        try {
            if (shipGetRenderOffset == null) {
                shipGetRenderOffset = shipClass.getMethod("getRenderOffset");
            }
            Object offset = shipGetRenderOffset.invoke(ship);
            Object x = offset == null ? null : publicRead(offset, "getX");
            Object y = offset == null ? null : publicRead(offset, "getY");
            if (!(x instanceof Number) || !(y instanceof Number)) {
                return null;
            }
            float offX = ((Number) x).floatValue();
            float offY = ((Number) y).floatValue();
            if (!Float.isFinite(offX) || !Float.isFinite(offY)) {
                return null;
            }
            return new float[] {offX, offY};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Round-arm warm of the corpse-read paths on ONE live roster
     * ship — pre-pays the first-corpse one-time bill (engine-internal bounds/segment/point classloads via
     * the exact-bounds ring walk, plus the lazy pose/module/renderOffset handle resolution
     * below) so the first real death does not stall the serve frame. Read-only by the
     * static-RE verdict: {@code BaseEntity.getExactBounds}/{@code Ship.getVisualBounds}
     * return plain fields (no lazy build-and-cache), the bounds class's {@code getOrigSegments} copies into
     * a fresh list, {@code getP1/getP2} are plain fields — zero persistent mutation anywhere
     * on the chain. {@code visualBounds} is null on an alive ship, so {@link #hulkRingsOrNull}
     * fail-softs AFTER the exact-bounds walk (the dominant classload term still runs);
     * {@code remnantSightingsOrNull} skips alive ships, hence this direct helper. Fail-soft:
     * returns a compact receipt string, never throws into the serve.
     */
    String warmRemnantReadPaths() {
        try {
            for (Object ship : ecb.rosterShipList()) {
                if (ship == null) {
                    continue;
                }
                float[] pose = remnantPoseOrNull(ship);
                float[] renderOff = remnantRenderOffsetOrNull(ship);
                boolean detached = remnantModuleDetached(ship);
                float area = remnantBoundsArea(ship);
                float[][] rings = hulkRingsOrNull(ship);
                return "pose=" + (pose != null) + " ro=" + (renderOff != null)
                        + " detached=" + detached + " area=" + (area > 0f)
                        + " rings=" + (rings != null);
            }
            return "no_roster_ship";
        } catch (RuntimeException e) {
            return "warm_failed err=" + e;
        }
    }

    /**
     * One remnant's {exact, visual} bound rings as flat x,y pairs — the
     * {@code Tesselator} walk shape (seed p1, then every p2, in segment-list order;
     * CLOSED when the engine's segment loop closes — the detector's {@code openRing} strips
     * the duplicate). Whole-read fail-soft {@code null} on ANY unreadable/non-finite step or
     * either bounds missing (the capture skips with a counter; a partial ring pair must
     * never reach the wire).
     */
    float[][] hulkRingsOrNull(Object ship) {
        float[] exact = ringWalkOrNull(ship, "getExactBounds");
        float[] visual = ringWalkOrNull(ship, "getVisualBounds");
        if (exact == null || visual == null) {
            return null;
        }
        return new float[][] {exact, visual};
    }

    private float[] ringWalkOrNull(Object ship, String boundsGetter) {
        try {
            Object bounds = shipClass.getMethod(boundsGetter).invoke(ship);
            Object segments = bounds == null ? null : publicRead(bounds, "getOrigSegments");
            if (!(segments instanceof List<?> segs) || segs.isEmpty()) {
                return null;
            }
            float[] ring = new float[(segs.size() + 1) * 2];
            int out = 0;
            for (int i = 0; i < segs.size(); i++) {
                Object seg = segs.get(i);
                for (String end : i == 0 ? new String[] {"getP1", "getP2"}
                        : new String[] {"getP2"}) {
                    Object point = seg == null ? null : publicRead(seg, end);
                    Object x = point == null ? null : publicRead(point, "getX");
                    Object y = point == null ? null : publicRead(point, "getY");
                    if (!(x instanceof Number) || !(y instanceof Number)) {
                        return null;
                    }
                    float px = ((Number) x).floatValue();
                    float py = ((Number) y).floatValue();
                    if (!Float.isFinite(px) || !Float.isFinite(py)) {
                        return null;
                    }
                    ring[out++] = px;
                    ring[out++] = py;
                }
            }
            return ring;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Bbox area over the ship's {@code getExactBounds()} original segments (min/max over the
     * segment endpoints — rotation-independent, so the rank input is stable across drift).
     * Per-piece fail-soft: any unreadable step falls back to collisionRadius² (rank input
     * only — a coarse area mis-ranks at worst, it never nulls the sighting walk).
     */
    private float remnantBoundsArea(Object ship) {
        try {
            Object bounds = shipClass.getMethod("getExactBounds").invoke(ship);
            Object segments = bounds == null ? null : publicRead(bounds, "getOrigSegments");
            if (segments instanceof List<?> segs && !segs.isEmpty()) {
                float minX = Float.POSITIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY;
                float maxY = Float.NEGATIVE_INFINITY;
                for (Object seg : segs) {
                    for (String end : new String[] {"getP1", "getP2"}) {
                        Object point = seg == null ? null : publicRead(seg, end);
                        Object x = point == null ? null : publicRead(point, "getX");
                        Object y = point == null ? null : publicRead(point, "getY");
                        if (!(x instanceof Number) || !(y instanceof Number)) {
                            throw new IllegalStateException("segment point unreadable");
                        }
                        float px = ((Number) x).floatValue();
                        float py = ((Number) y).floatValue();
                        minX = Math.min(minX, px);
                        minY = Math.min(minY, py);
                        maxX = Math.max(maxX, px);
                        maxY = Math.max(maxY, py);
                    }
                }
                float area = (maxX - minX) * (maxY - minY);
                if (Float.isFinite(area) && area > 0f) {
                    return area;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException fallThrough) {
            // fail-soft: the collision-radius fallback below
        }
        try {
            Object radius = shipClass.getMethod("getCollisionRadius").invoke(ship);
            if (radius instanceof Number n && Float.isFinite(n.floatValue())) {
                return n.floatValue() * n.floatValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fail-soft: rank input only
        }
        return 0f;
    }

    /**
     * Read a no-arg public method off an engine object whose concrete class may be
     * non-public (bounds/segment/point internals): {@code setAccessible} is legal here —
     * both JVMs' classpath code shares the unnamed module, so no opens are needed.
     */
    private static Object publicRead(Object target, String method)
            throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(method);
        if (!m.canAccess(target)) {
            m.setAccessible(true);
        }
        return m.invoke(target);
    }

    // ---- ALTERNATING active-tube mirror. ----

    /**
     * Conform the selected ALTERNATING group's active-tube pointer to the
     * wire-carried {@code activeTubeOrdinal} — the server half of the tube-pointer fix. The
     * engine advances an alternating group's pointer on select-command RISING EDGES
     * in the weapon group, so a re-select of the already-selected group is a
     * wire-invisible client-only +1 that {@code EngineCombatBinding#mirrorSelectedGroup}
     * (selection TRANSITIONS only) can never see; carrying the pointer as per-tick STATE and
     * read-compare-applying it heals the whole divergence class (missed edges, one-side
     * refusals, ammo-skip forks) at the next quiescent tick. Apply verb = the engine AI's own
     * conform idiom (read-compare
     * {@code getWeaponsCopy().indexOf(getActiveWeapon()) != target} →
     * {@code WeaponGroup.setCurrentActive(int)});
     * {@code giveCommand(SELECT_GROUP)} cycling REJECTED (select-flash/tracker side effects, cannot
     * target a tube). Void STATEMENT, deliberately NOT a {@code PilotApplyResult} widening (the
     * shield-hold precedent) — the observable is {@code state.tubeOrdinalConformed}.
     *
     * <p>Gates, ALL else silent no-op: seams bound (every fake without them keeps its byte-identical
     * contract); wire ordinal ≥ 0 (−1 = ABSENT: legacy producer, or the client's selection was
     * non-ALTERNATING/absent at sample); not inside a declared-lane serve window (the mirror
     * belt — the serve window owns selection state); selection SETTLED (hull ordinal ==
     * {@code frame.selectedWeaponGroup()} ≥ 0 — on a transition tick the queued SELECT_GROUP's own
     * +1 lands during the world advance, so conforming now would double-advance; an out-of-bounds
     * wire group can never equal a hull ordinal, so it skips here too); group ALTERNATING (a LINKED
     * pointer is not a tube pointer); FIRE-QUIESCENT ({@code !fireHeld() && !fireEdgeLatchArmed()}
     * — a state mirror inside an edge window = the fork class); wire ordinal past the hull's
     * tube count = V4 capped warn + skip.
     */
    void mirrorActiveTubeOrdinal(Object ship,
            cc.starsector.server.runtime.PilotIntentFrame frame,
            PilotApplyState state) {
        int wireOrd = frame.activeTubeOrdinal();
        if (wireOrd < 0 || groupGetType == null || groupGetActiveWeapon == null
                || groupGetWeaponsCopy == null || groupSetCurrentActive == null
                || weaponGroupTypeAlternating == null || shipGetWeaponGroupsCopy == null
                || shipGetSelectedGroupAPI == null) {
            return;
        }
        if (state.declaredFireLane() && state.pendingDeclarationCount() > 0) {
            return;
        }
        int wireGroup = frame.selectedWeaponGroup();
        if (wireGroup < 0) {
            return;
        }
        List<?> groups = ecb.weaponGroupsCopyOrNull(ship);
        if (groups == null) {
            return;
        }
        int hullSelected = groups.indexOf(
                ecb.invoke(shipGetSelectedGroupAPI, ship, "ShipAPI.getSelectedGroupAPI"));
        if (hullSelected != wireGroup) {
            return;
        }
        Object group = groups.get(hullSelected);
        if (!weaponGroupTypeAlternating.equals(
                ecb.invoke(groupGetType, group, "WeaponGroupAPI.getType"))) {
            return;
        }
        if (frame.fireHeld() || state.fireEdgeLatchArmed()) {
            return;
        }
        Object rawTubes = ecb.invoke(groupGetWeaponsCopy, group, "WeaponGroupAPI.getWeaponsCopy");
        if (!(rawTubes instanceof List<?>)) {
            return;
        }
        List<?> tubes = (List<?>) rawTubes;
        if (wireOrd >= tubes.size()) {
            ecb.warnGroupParity("active-tube", wireOrd, tubes.size());
            return;
        }
        if (tubes.indexOf(ecb.invoke(groupGetActiveWeapon, group, "WeaponGroupAPI.getActiveWeapon"))
                == wireOrd) {
            return;
        }
        ecb.invoke(groupSetCurrentActive, group, "WeaponGroup.setCurrentActive", wireOrd);
        state.tubeOrdinalConformed++;
    }

    // ---- ALTERNATING held-dwell advance. ----
    /**
     * Expose the EXISTING ALTERNATING gate ({@code groupGetType} vs
     * {@code weaponGroupTypeAlternating}, the {@link #advanceAlternatingHeldDwell} read) for one
     * group object: TRUE / FALSE, or null when either handle is unbound (fake bindings) — the
     * caller treats null as unreadable. A read throw propagates (the caller's catch counts it).
     *
     */
    Boolean groupIsAlternatingOrNull(Object group) {
        if (group == null || groupGetType == null || weaponGroupTypeAlternating == null) {
            return null;
        }
        return weaponGroupTypeAlternating.equals(ecb.invoke(groupGetType, group, "WeaponGroupAPI.getType"));
    }

    // Lazy reflective dwell-spec family (weapon isBeam/getSpec/getAmmo/getType; spec burst/
    // refire/charge reads; ship getMutableStats→RoFMult→getModifiedValue): the home law
    // places NEW non-held-lane reflection families here at birth. Cached by concrete-class#method
    // (beam and non-beam specs are different classes).
    private final java.util.HashMap<String, Method> dwellMethodCache = new java.util.HashMap<>();
    private int dwellAdvanceLogs;

    /**
     * the ALT-FIRE fix verb: while the HELD lane serves FIRE on a
     * server-driven hull whose SELECTED group is ALTERNATING, accumulate held time in the
     * state's dwell machine ({@code noteAlternatingHeldTick}) and advance the active tube on the
     * ENGINE'S OWN dwell law (vanilla's mid-hold advance is player-ship-only,
     * so dead headless), via the same read-compare
     * {@code setCurrentActive} conform idiom as {@link #mirrorActiveTubeOrdinal} (whose
     * FIRE-QUIESCENT gate keeps the two verbs disjoint by construction — no new interplay).
     * Next-tube choice mirrors {@code WeaponGroup.findNextWeaponFrom}: scan
     * cur+1..cur+size skipping {@code getAmmo() <= 0}, fallback ordinal 0; a scan landing back
     * on cur is a read-compare no-op (vanilla's own possible outcome). Gates, ALL else silent
     * no-op: seams bound, selected group resolvable + ALTERNATING, ≥2 tubes, current ordinal
     * readable. Unreadable dwell-spec seams read threshold −1 = never advance (fail-soft: worst
     * case that weapon keeps the unfixed no-advance behavior; never a wrong write). Capped-5 stdout
     * {@code pilot-alt-dwell advance} lines; the durable meter is the state's
     * {@code alt_dwell_advances=} round field. {@code fireLocked}
     * = the hull's fire-lock read, asked only PAST the group gates (≈ 6 reflective invokes —
     * never paid on a LINKED group's held tick): locked ⇒ this tick is skipped, which
     * resets the dwell accumulator exactly as a non-held tick does.
     */
    void advanceAlternatingHeldDwell(Object ship,
            PilotApplyState state, long simTick, java.util.function.Predicate<Object> fireLocked) {
        if (groupGetType == null || groupGetActiveWeapon == null || groupGetWeaponsCopy == null
                || groupSetCurrentActive == null || weaponGroupTypeAlternating == null
                || shipGetWeaponGroupsCopy == null || shipGetSelectedGroupAPI == null) {
            return;
        }
        List<?> groups = ecb.weaponGroupsCopyOrNull(ship);
        if (groups == null) {
            return;
        }
        int groupOrdinal = groups.indexOf(
                ecb.invoke(shipGetSelectedGroupAPI, ship, "ShipAPI.getSelectedGroupAPI"));
        if (groupOrdinal < 0) {
            return;
        }
        Object group = groups.get(groupOrdinal);
        if (!weaponGroupTypeAlternating.equals(
                ecb.invoke(groupGetType, group, "WeaponGroupAPI.getType"))) {
            return;
        }
        Object rawTubes = ecb.invoke(groupGetWeaponsCopy, group, "WeaponGroupAPI.getWeaponsCopy");
        if (!(rawTubes instanceof List<?>) || ((List<?>) rawTubes).size() < 2) {
            return;
        }
        List<?> tubes = (List<?>) rawTubes;
        int cur = tubes.indexOf(
                ecb.invoke(groupGetActiveWeapon, group, "WeaponGroupAPI.getActiveWeapon"));
        if (cur < 0) {
            return;
        }
        if (fireLocked.test(ship)) {
            return; // the engine refuses this press — no dwell on a refused tick
        }
        float thresholdSeconds = dwellThresholdSecondsOrNever(ship, tubes.get(cur), tubes.size());
        if (!state.noteAlternatingHeldTick(simTick, groupOrdinal, thresholdSeconds)) {
            return;
        }
        int next;
        try {
            next = nextTubeOrdinalWithAmmo(tubes, cur + 1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return; // unreadable ammo seam — skip this advance; the machine re-armed (fail-soft)
        }
        if (next == cur) {
            return; // the ammo scan landed back on cur — vanilla's own no-op outcome
        }
        ecb.invoke(groupSetCurrentActive, group, "WeaponGroup.setCurrentActive", next);
        if (dwellAdvanceLogs < 5) {
            dwellAdvanceLogs++;
            System.out.println("pilot-alt-dwell advance group=" + groupOrdinal
                    + " tube=" + cur + "->" + next + " tick=" + simTick
                    + " threshold_s=" + thresholdSeconds
                    + (dwellAdvanceLogs == 5 ? " (log capped; alt_dwell_advances= continues)" : ""));
        }
    }

    /** The active weapon's dwell threshold in seconds, or −1 = never (fail-soft). */
    private float dwellThresholdSecondsOrNever(Object ship, Object weapon, int groupSize) {
        try {
            boolean isBeam = Boolean.TRUE.equals(dwellRead(weapon, "isBeam"));
            Object spec = dwellRead(weapon, "getSpec");
            if (spec == null) {
                return -1f;
            }
            float rofMult = dwellRofMult(ship, weapon);
            if (isBeam) {
                if (!Boolean.TRUE.equals(dwellRead(spec, "isBurstBeam"))) {
                    return -1f; // non-burst beams never dwell-advance
                }
                return PilotApplyState.alternatingDwellThresholdSeconds(
                        true, true,
                        dwellFloat(spec, "getBurstDuration"), dwellFloat(spec, "getBurstCooldown"),
                        dwellFloat(spec, "getChargeupTime"), dwellFloat(spec, "getChargedownTime"),
                        0f, 0f, 0f, 0f, groupSize, rofMult);
            }
            return PilotApplyState.alternatingDwellThresholdSeconds(
                    false, false, 0f, 0f, 0f, 0f,
                    dwellFloat(spec, "getBurstSize"), dwellFloat(spec, "getBurstDelay"),
                    dwellFloat(spec, "getRefireDelay"), dwellFloat(spec, "getChargeTime"),
                    groupSize, rofMult);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1f;
        }
    }

    /** Vanilla getRoFMult — type-keyed ship stat mult; any unreadable seam reads 1.0. */
    private float dwellRofMult(Object ship, Object weapon) {
        try {
            Object type = dwellRead(weapon, "getType");
            if (!(type instanceof Enum<?>)) {
                return 1.0f;
            }
            String getter;
            switch (((Enum<?>) type).name()) {
                case "BALLISTIC":
                    getter = "getBallisticRoFMult";
                    break;
                case "ENERGY":
                    getter = "getEnergyRoFMult";
                    break;
                case "MISSILE":
                    getter = "getMissileRoFMult";
                    break;
                default:
                    return 1.0f;
            }
            Object stats = dwellRead(ship, "getMutableStats");
            Object stat = stats == null ? null : dwellRead(stats, getter);
            Object value = stat == null ? null : dwellRead(stat, "getModifiedValue");
            return value instanceof Number n && Float.isFinite(n.floatValue())
                    && n.floatValue() > 0f ? n.floatValue() : 1.0f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 1.0f;
        }
    }

    /** The {@code WeaponGroup.findNextWeaponFrom} mirror — ammo scan, fallback 0. */
    private int nextTubeOrdinalWithAmmo(List<?> tubes, int from)
            throws ReflectiveOperationException {
        for (int i = 0; i < tubes.size(); i++) {
            int ordinal = (from + i) % tubes.size();
            Object ammo = dwellRead(tubes.get(ordinal), "getAmmo");
            if (ammo instanceof Number n && n.intValue() > 0) {
                return ordinal;
            }
        }
        return 0;
    }

    /**
     * Cached no-arg read keyed concrete-class#method (the {@link #publicRead} idiom
     * plus a Method cache — this family runs per held tick on the sim thread, so the lookup cost
     * is paid once per concrete class).
     */
    private Object dwellRead(Object target, String method) throws ReflectiveOperationException {
        String key = target.getClass().getName() + '#' + method;
        Method m = dwellMethodCache.get(key);
        if (m == null) {
            m = target.getClass().getMethod(method);
            if (!m.canAccess(target)) {
                m.setAccessible(true);
            }
            dwellMethodCache.put(key, m);
        }
        return m.invoke(target);
    }

    private float dwellFloat(Object spec, String method) throws ReflectiveOperationException {
        Object v = dwellRead(spec, method);
        return v instanceof Number n ? n.floatValue() : Float.NaN;
    }

    // ---- Fog tracker re-mint handles (resolution idiom = RealEngineRoundHeapDrain). ----
    private Constructor<?> fogTrackerCtor;
    private Field fogTrackerMapField;
    private Field fogTrackerTilesField;

    /**
     * Re-mint the two per-side fog-of-war trackers from the (just-resized) combat map — the
     * engine's own {@code setCombatMap} fog pair, surgical scope. Fail-soft: returns a one-line
     * outcome for the {@code [cc-map-bounds]} log and never throws; a miss leaves the stale
     * trackers in place (the pre-fix behavior) rather than failing the round boot. Runs at
     * round boot pre-tick-0, so discarding accumulated tile/fader state is clean.
     */
    String remintFogTrackers(Object combatMap) {
        try {
            Method getFog = engine.getClass().getMethod("getFogOfWar", int.class);
            Object fog0 = getFog.invoke(engine, 0);
            if (fog0 == null) {
                return "skip: getFogOfWar(0) null (trackers not minted)";
            }
            if (fogTrackerCtor == null || fogTrackerMapField == null) {
                Constructor<?> ctor =
                        fog0.getClass().getDeclaredConstructor(combatMap.getClass(), int.class);
                ctor.setAccessible(true);
                Field mapField = null;
                for (Field f : engine.getClass().getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object val = f.get(engine);
                    if (val instanceof Map && ((Map<?, ?>) val).get(Integer.valueOf(0)) == fog0) {
                        mapField = f;
                        break;
                    }
                }
                if (mapField == null) {
                    return "skip: no engine Map field maps 0 -> getFogOfWar(0)";
                }
                for (Field f : fog0.getClass().getDeclaredFields()) {
                    if (f.getType() == boolean[][].class) {
                        f.setAccessible(true);
                        fogTrackerTilesField = f;
                        break;
                    }
                }
                fogTrackerCtor = ctor;
                fogTrackerMapField = mapField;
            }
            String gridBefore = fogGridDims(fog0);
            @SuppressWarnings("unchecked")
            Map<Object, Object> trackers = (Map<Object, Object>) fogTrackerMapField.get(engine);
            Object newFog0 = fogTrackerCtor.newInstance(combatMap, Integer.valueOf(0));
            Object newFog1 = fogTrackerCtor.newInstance(combatMap, Integer.valueOf(1));
            trackers.put(Integer.valueOf(0), newFog0);
            trackers.put(Integer.valueOf(1), newFog1);
            return "applied grid=" + gridBefore + "->" + fogGridDims(newFog0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "FAILED " + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private String fogGridDims(Object fog) {
        try {
            if (fogTrackerTilesField == null) {
                return "?";
            }
            boolean[][] tiles = (boolean[][]) fogTrackerTilesField.get(fog);
            return tiles.length + "x" + (tiles.length > 0 ? tiles[0].length : 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    // ---- Piloted-cloak wall-clock tracker family. ----
    // The phase system impl (combat.systems.F, what getPhaseCloak() returns) holds its state
    // tracker in the UNOBFUSCATED protected field `chargeTracker`; the tracker class
    // (combat.systems.super) keeps ONE shared elapsed accumulator in `private float class`
    // and the SystemState in a `private SystemState` field — both
    // private, reachable ONLY via getDeclaredField+setAccessible. Fail-soft null
    // here; the caller (PilotedCloakWallClock) treats a null after a non-null cloak as a resolve
    // failure and disables itself fail-closed.
    private Field cloakChargeTrackerField;
    private Field cloakTrackerStateField;
    private Field cloakTrackerElapsedField;

    /** The cloak impl's charge tracker (`F#chargeTracker`), or null on any reflective miss. */
    Object cloakChargeTrackerOrNull(Object cloak) {
        try {
            if (cloakChargeTrackerField == null) {
                cloakChargeTrackerField = findFieldInHierarchy(cloak.getClass(), "chargeTracker");
            }
            return cloakChargeTrackerField == null ? null : cloakChargeTrackerField.get(cloak);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The tracker's SystemState enum value, or null on any reflective miss. */
    Object cloakTrackerStateOrNull(Object tracker) {
        try {
            if (cloakTrackerStateField == null) {
                cloakTrackerStateField = findFieldInHierarchy(tracker.getClass(), "o00000");
            }
            return cloakTrackerStateField == null ? null : cloakTrackerStateField.get(tracker);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The tracker's shared elapsed accumulator (`super#class`), or null on any reflective miss. */
    Float cloakTrackerElapsedOrNull(Object tracker) {
        try {
            if (cloakTrackerElapsedField == null) {
                cloakTrackerElapsedField = findFieldInHierarchy(tracker.getClass(), "class");
            }
            Object value = cloakTrackerElapsedField == null ? null : cloakTrackerElapsedField.get(tracker);
            return value instanceof Float ? (Float) value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Raw write of the tracker's elapsed accumulator; false on any reflective miss. */
    boolean cloakTrackerElapsedWrite(Object tracker, float value) {
        try {
            if (cloakTrackerElapsedField == null) {
                cloakTrackerElapsedField = findFieldInHierarchy(tracker.getClass(), "class");
            }
            if (cloakTrackerElapsedField == null) {
                return false;
            }
            cloakTrackerElapsedField.setFloat(tracker, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** getDeclaredField up the class hierarchy (the runtime class may subclass the declarer). */
    private static Field findFieldInHierarchy(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // keep walking
            }
        }
        return null;
    }
}
