package cc.starsector.server.protocol;

import cc.starsector.server.adapter.CombatEntityView;
import cc.starsector.server.adapter.ShipDurabilityView;
import cc.starsector.server.adapter.ShipMotionView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One authoritative world snapshot at a completed simulation tick: the set of
 * per-entity {@link AuthoritativeEntitySnapshot} states the server replicates to
 * clients under the snapshot-authoritative-only rule.
 *
 * <p>This replaces the aggregate-only
 * {@code cc.starsector.server.runtime.OfflineCombatStepResult} hash frame as the
 * unit a client can actually reconstruct authoritative state from: a decoded
 * snapshot yields each entity's motion and durability, not just a desync hash.
 *
 * <p>Entities are stored in ascending {@code entityId} order with no duplicates,
 * giving a stable, deterministic replication order independent of engine
 * iteration order (which is itself
 * nondeterministic).
 *
 * <p>{@code nonShipEntities} is the parallel critical-state tier
 * ("active projectiles, missiles, beams"),
 * carried since schema v2: transient {@link NonShipEntitySnapshot} entities
 * read off the live non-ship engine lists, ordered by ascending unique {@code networkId} in their own
 * id space (separate from ship {@code entityId}s). A snapshot with no non-ship
 * entities is the back-compat case the two-argument constructor produces, and is
 * exactly what the ship-only (v1) wire frames decode to.
 *
 * <p>{@code fluxStates} is the per-ship flux + shield combat-effectiveness tier,
 * carried since schema v3: one {@link ShipFluxSnapshot} per ship
 * that has a flux read, keyed by the ship's {@code entityId} and ordered ascending.
 * A snapshot with no flux states is the back-compat case the three-argument
 * constructor produces, exactly what every pre-v3 frame decodes to.
 *
 * <p>{@code weaponMounts} is the per-mount combat-readiness tier, carried
 * since schema v4: one {@link ShipWeaponMountSnapshot} per ship weapon mount
 * that has a read, keyed by the pair ({@code entityId}, {@code mountIndex}) and ordered
 * ascending by that pair. It sits one level below the ship-level flux tier — flux says
 * whether a ship is broadly combat-effective; per-mount state says which guns can fire
 * this instant. A snapshot with no weapon mounts is the back-compat case the
 * four-argument constructor produces, exactly what every pre-v4 frame
 * decodes to.
 *
 * <p>{@code profileStates} is the per-ship static physical-profile tier,
 * a deferred in-memory tier: one {@link ShipProfileSnapshot} per ship that has a
 * profile read, keyed by the ship's {@code entityId} and ordered ascending. Where the
 * flux and weapon-mount tiers describe a ship's evolving combat state, this tier
 * describes how big it is and how it occupies space (hull-size class, collision radius,
 * mass) — what a client needs to weight relevance/priority and reason about collision
 * footprint. A snapshot with no profile states is the back-compat case the five-argument
 * constructor produces, exactly what every frame from before this tier decodes to.
 *
 * <p>{@code allegianceStates} is the per-ship combat-SIDE tier (friend/foe),
 * carried since schema v5: one {@link ShipAllegianceSnapshot} per ship that has an
 * allegiance read, keyed by the ship's {@code entityId} and ordered ascending. Where
 * {@code ownerConnectionId} on the ship tier says which network connection controls a ship,
 * this says which {@code FleetSide} it fights for — the field a client needs to render friend
 * vs foe (flagged earlier as the open playability delta). It is the genuinely
 * next WIRED schema tier (v5); the {@code profileStates} tier above remains a deferred,
 * never-encoded Stage-A in-memory tier and is NOT a wire schema. A snapshot with no
 * allegiance states is the back-compat case the six-argument constructor produces, exactly what
 * every pre-v5 frame decodes to.
 *
 * <p>{@code engineStates} is the per-ship engine-disabled/flameout tier,
 * carried since schema v6: one {@link ShipEngineStateSnapshot} per ship that has an
 * engine-state read, keyed by the ship's {@code entityId} and ordered ascending. Flameout
 * is DERIVED state recomputed each advance from independently-stochastic per-engine health
 * trackers, so a predictive client can never converge on it implicitly — this tier carries
 * the server's truth so the client can apply the real per-engine mechanism locally. A
 * snapshot with no engine states is the back-compat case the seven-argument constructor
 * produces, exactly what every pre-v6 frame decodes to.
 *
 * <p>{@code maneuverCommands} is the per-ship maneuver-COMMAND tier,
 * carried since schema v7: one {@link ShipManeuverCommandSnapshot} per ship that has a
 * maneuver-command read, keyed by the ship's {@code entityId} and ordered ascending.
 * The engine's thrust-plume/glow/contrail presentation (and engine-loop audio) is
 * driven ONLY by the per-frame maneuver command queue — never by pose or velocity —
 * so a pose-snapped foe replica idles its engines unless the server's command truth
 * is carried and re-issued locally through the real {@code giveCommand} path. A
 * snapshot with no maneuver commands is the back-compat case the eight-argument
 * constructor produces, exactly what every pre-v7 frame decodes to.
 *
 * <p>{@code systemStates} is the per-ship primary ship-SYSTEM readiness tier,
 * carried since schema v8: one {@link ShipSystemStateSnapshot}
 * per ship that has a system-state read, keyed by the ship's {@code entityId} and
 * ordered ascending. The steady-state lane replicates only activation EDGES, so a
 * client that joins (or first sights an entity) mid-activation has no edge to replay —
 * this tier carries the server's phase/progress/ammo truth for a one-shot faithful
 * {@code forceState} seed. A snapshot with no system states is the back-compat case
 * the nine-argument constructor produces, exactly what every pre-v8 frame decodes to.
 *
 * <p>{@code fragmentMarkers} is the FRAGMENT_MARKER tail tier,
 * carried since schema v9: one {@link ShipFragmentMarkerSnapshot} per PARTIALLY-carried
 * ship in this frame, keyed by the ship's {@code entityId} and ordered ascending. A
 * marker declares which ship-scoped tiers (and which mount-index range) this frame
 * asserts for that entity, so one over-cap entity's record set can be split across
 * chunk frames with per-tier client merge (ABSENT ≠ EMPTY semantics, as the fragment
 * design specifies). A snapshot with no markers is the back-compat
 * case the ten-argument constructor produces, exactly what every pre-v9 frame decodes
 * to — all marker-free output stays byte-identical to v1.5 chunking.
 *
 * <p>{@code phaseCloakStates} is the per-ship PHASE-CLOAK readiness tier,
 * carried since schema v10: one {@link ShipPhaseCloakStateSnapshot} per ship
 * that has a cloak read (the separate {@code getPhaseCloak()} slot — never the primary
 * {@code getSystem()} the v8 tier carries), keyed by the ship's {@code entityId} and
 * ordered ascending. The cloak's activation edges alone cannot seed a mid-join replica,
 * so this tier carries the server's phase/progress truth for a one-shot faithful
 * {@code forceState} seed; the per-frame collision truth rides the flux tier's
 * {@code FLAG_PHASED} bit. Placed in the body STRICTLY AFTER the fragment-marker section
 * (D-M2-2: a stale-v9 reader walks pose tiers then the marker — inserting before it
 * would misparse the cloak count as marker count). A snapshot with no cloak states is
 * the back-compat case the eleven-argument constructor produces, exactly what every
 * pre-v10 frame decodes to.
 *
 * <p>{@code crStates} is the per-ship COMBAT-READINESS tier, carried since
 * schema v14: one {@link ShipCrSnapshot} per enrolled ship (every ship with a CR read —
 * the flux-fold ship set), keyed by the ship's {@code entityId} and ordered ascending.
 * A predictive client's own-ship HUD CR% and PPT countdown otherwise run on the LOCAL
 * frozen clock (the server owns deployment truth headless-side), so this tier carries
 * the authoritative {@code cr}/{@code timeDeployedSec}/{@code peakSec} for the client's
 * own-CR pin. Placed in the body STRICTLY AFTER the phase-cloak section (same
 * stale-reader ordering law as v10). A snapshot with no CR states is the back-compat
 * case the twelve-argument constructor produces, exactly what every pre-v14 frame
 * decodes to.
 *
 * <p>{@code contentDictionary} is the FRAME-GLOBAL content-dictionary tier
 * (schema v15): a small cycling window of {@link ContentDictionaryEntry} announcements
 * mapping wire {@code contentId}s to their true engine spec keys, so a client can
 * present non-ship entities (missile children especially) by their real spec instead of
 * a launcher recipe. NOT entity-scoped: entries are catalog announcements, not per-tick
 * state — the client folds them last-write-wins across frames and a lost frame's
 * entries re-announce by cycling. Placed in the body STRICTLY AFTER the CR section
 * (same stale-reader ordering law as v10/v14). No ordering law across entries (the
 * client fold is order-insensitive); duplicate {@code contentId}s are rejected. A
 * snapshot with no dictionary entries is the back-compat case the thirteen-argument
 * constructor produces, exactly what every pre-v15 frame decodes to.
 */
public record AuthoritativeWorldSnapshot(
        long simTick,
        List<AuthoritativeEntitySnapshot> entities,
        List<NonShipEntitySnapshot> nonShipEntities,
        List<ShipFluxSnapshot> fluxStates,
        List<ShipWeaponMountSnapshot> weaponMounts,
        List<ShipProfileSnapshot> profileStates,
        List<ShipAllegianceSnapshot> allegianceStates,
        List<ShipEngineStateSnapshot> engineStates,
        List<ShipManeuverCommandSnapshot> maneuverCommands,
        List<ShipSystemStateSnapshot> systemStates,
        List<ShipFragmentMarkerSnapshot> fragmentMarkers,
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates,
        List<ShipCrSnapshot> crStates,
        List<ContentDictionaryEntry> contentDictionary) {

    public AuthoritativeWorldSnapshot {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        Objects.requireNonNull(entities, "entities");
        entities = List.copyOf(entities);
        long previousId = 0L;
        for (AuthoritativeEntitySnapshot entity : entities) {
            Objects.requireNonNull(entity, "entity");
            if (entity.entityId() <= previousId) {
                throw new IllegalArgumentException(
                        "entities must be ordered by ascending unique entityId; got "
                                + entity.entityId() + " after " + previousId);
            }
            previousId = entity.entityId();
        }
        Objects.requireNonNull(nonShipEntities, "nonShipEntities");
        nonShipEntities = List.copyOf(nonShipEntities);
        long previousNonShipId = 0L;
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            Objects.requireNonNull(nonShip, "nonShipEntity");
            if (nonShip.networkId() <= previousNonShipId) {
                throw new IllegalArgumentException(
                        "non-ship entities must be ordered by ascending unique networkId; got "
                                + nonShip.networkId() + " after " + previousNonShipId);
            }
            previousNonShipId = nonShip.networkId();
        }
        Objects.requireNonNull(fluxStates, "fluxStates");
        fluxStates = List.copyOf(fluxStates);
        long previousFluxId = 0L;
        for (ShipFluxSnapshot flux : fluxStates) {
            Objects.requireNonNull(flux, "fluxState");
            if (flux.entityId() <= previousFluxId) {
                throw new IllegalArgumentException(
                        "flux states must be ordered by ascending unique entityId; got "
                                + flux.entityId() + " after " + previousFluxId);
            }
            previousFluxId = flux.entityId();
        }
        Objects.requireNonNull(weaponMounts, "weaponMounts");
        weaponMounts = List.copyOf(weaponMounts);
        long previousMountEntityId = 0L;
        int previousMountIndex = -1;
        for (ShipWeaponMountSnapshot mount : weaponMounts) {
            Objects.requireNonNull(mount, "weaponMount");
            // Ordered ascending by the composite (entityId, mountIndex): a new entityId
            // must strictly exceed the previous, and within one entityId mountIndex must
            // strictly increase. This rejects an out-of-order or duplicated mount key.
            boolean inOrder = mount.entityId() > previousMountEntityId
                    || (mount.entityId() == previousMountEntityId
                            && mount.mountIndex() > previousMountIndex);
            if (!inOrder) {
                throw new IllegalArgumentException(
                        "weapon mounts must be ordered by ascending unique (entityId, mountIndex); got ("
                                + mount.entityId() + ", " + mount.mountIndex() + ") after ("
                                + previousMountEntityId + ", " + previousMountIndex + ")");
            }
            previousMountEntityId = mount.entityId();
            previousMountIndex = mount.mountIndex();
        }
        Objects.requireNonNull(profileStates, "profileStates");
        profileStates = List.copyOf(profileStates);
        long previousProfileId = 0L;
        for (ShipProfileSnapshot profile : profileStates) {
            Objects.requireNonNull(profile, "profileState");
            if (profile.entityId() <= previousProfileId) {
                throw new IllegalArgumentException(
                        "profile states must be ordered by ascending unique entityId; got "
                                + profile.entityId() + " after " + previousProfileId);
            }
            previousProfileId = profile.entityId();
        }
        Objects.requireNonNull(allegianceStates, "allegianceStates");
        allegianceStates = List.copyOf(allegianceStates);
        long previousAllegianceId = 0L;
        for (ShipAllegianceSnapshot allegiance : allegianceStates) {
            Objects.requireNonNull(allegiance, "allegianceState");
            if (allegiance.entityId() <= previousAllegianceId) {
                throw new IllegalArgumentException(
                        "allegiance states must be ordered by ascending unique entityId; got "
                                + allegiance.entityId() + " after " + previousAllegianceId);
            }
            previousAllegianceId = allegiance.entityId();
        }
        Objects.requireNonNull(engineStates, "engineStates");
        engineStates = List.copyOf(engineStates);
        long previousEngineStateId = 0L;
        for (ShipEngineStateSnapshot engineState : engineStates) {
            Objects.requireNonNull(engineState, "engineState");
            if (engineState.entityId() <= previousEngineStateId) {
                throw new IllegalArgumentException(
                        "engine states must be ordered by ascending unique entityId; got "
                                + engineState.entityId() + " after " + previousEngineStateId);
            }
            previousEngineStateId = engineState.entityId();
        }
        Objects.requireNonNull(maneuverCommands, "maneuverCommands");
        maneuverCommands = List.copyOf(maneuverCommands);
        long previousManeuverId = 0L;
        for (ShipManeuverCommandSnapshot maneuverCommand : maneuverCommands) {
            Objects.requireNonNull(maneuverCommand, "maneuverCommand");
            if (maneuverCommand.entityId() <= previousManeuverId) {
                throw new IllegalArgumentException(
                        "maneuver commands must be ordered by ascending unique entityId; got "
                                + maneuverCommand.entityId() + " after " + previousManeuverId);
            }
            previousManeuverId = maneuverCommand.entityId();
        }
        Objects.requireNonNull(systemStates, "systemStates");
        systemStates = List.copyOf(systemStates);
        long previousSystemStateId = 0L;
        for (ShipSystemStateSnapshot systemState : systemStates) {
            Objects.requireNonNull(systemState, "systemState");
            if (systemState.entityId() <= previousSystemStateId) {
                throw new IllegalArgumentException(
                        "system states must be ordered by ascending unique entityId; got "
                                + systemState.entityId() + " after " + previousSystemStateId);
            }
            previousSystemStateId = systemState.entityId();
        }
        Objects.requireNonNull(fragmentMarkers, "fragmentMarkers");
        fragmentMarkers = List.copyOf(fragmentMarkers);
        long previousMarkerId = 0L;
        for (ShipFragmentMarkerSnapshot marker : fragmentMarkers) {
            Objects.requireNonNull(marker, "fragmentMarker");
            if (marker.entityId() <= previousMarkerId) {
                throw new IllegalArgumentException(
                        "fragment markers must be ordered by ascending unique entityId; got "
                                + marker.entityId() + " after " + previousMarkerId);
            }
            previousMarkerId = marker.entityId();
        }
        Objects.requireNonNull(phaseCloakStates, "phaseCloakStates");
        phaseCloakStates = List.copyOf(phaseCloakStates);
        long previousPhaseCloakId = 0L;
        for (ShipPhaseCloakStateSnapshot phaseCloak : phaseCloakStates) {
            Objects.requireNonNull(phaseCloak, "phaseCloakState");
            if (phaseCloak.entityId() <= previousPhaseCloakId) {
                throw new IllegalArgumentException(
                        "phase-cloak states must be ordered by ascending unique entityId; got "
                                + phaseCloak.entityId() + " after " + previousPhaseCloakId);
            }
            previousPhaseCloakId = phaseCloak.entityId();
        }
        Objects.requireNonNull(crStates, "crStates");
        crStates = List.copyOf(crStates);
        long previousCrId = 0L;
        for (ShipCrSnapshot crState : crStates) {
            Objects.requireNonNull(crState, "crState");
            if (crState.entityId() <= previousCrId) {
                throw new IllegalArgumentException(
                        "cr states must be ordered by ascending unique entityId; got "
                                + crState.entityId() + " after " + previousCrId);
            }
            previousCrId = crState.entityId();
        }
        Objects.requireNonNull(contentDictionary, "contentDictionary");
        contentDictionary = List.copyOf(contentDictionary);
        Set<Integer> seenContentIds = new HashSet<>();
        for (ContentDictionaryEntry entry : contentDictionary) {
            Objects.requireNonNull(entry, "contentDictionaryEntry");
            // No ordering law (the client fold is order-insensitive last-write-wins),
            // but one frame announcing two keys for one id is a server bug — reject.
            if (!seenContentIds.add(entry.contentId())) {
                throw new IllegalArgumentException(
                        "content dictionary must not repeat a contentId; got duplicate "
                                + entry.contentId());
            }
        }
    }

    /**
     * Back-compat constructor for a snapshot with no content-dictionary tier (ships +
     * every optional tier through CR states). Every factory and call site that predates
     * the content-dictionary tier routes through this, so the lower tiers are unchanged and
     * pre-v15 frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts,
            List<ShipProfileSnapshot> profileStates,
            List<ShipAllegianceSnapshot> allegianceStates,
            List<ShipEngineStateSnapshot> engineStates,
            List<ShipManeuverCommandSnapshot> maneuverCommands,
            List<ShipSystemStateSnapshot> systemStates,
            List<ShipFragmentMarkerSnapshot> fragmentMarkers,
            List<ShipPhaseCloakStateSnapshot> phaseCloakStates,
            List<ShipCrSnapshot> crStates) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, systemStates, fragmentMarkers,
                phaseCloakStates, crStates, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no CR tier (ships + every optional
     * tier through phase-cloak states). Every factory and call site that predates
     * the CR tier routes through this, so the lower tiers are unchanged and
     * pre-v14 frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts,
            List<ShipProfileSnapshot> profileStates,
            List<ShipAllegianceSnapshot> allegianceStates,
            List<ShipEngineStateSnapshot> engineStates,
            List<ShipManeuverCommandSnapshot> maneuverCommands,
            List<ShipSystemStateSnapshot> systemStates,
            List<ShipFragmentMarkerSnapshot> fragmentMarkers,
            List<ShipPhaseCloakStateSnapshot> phaseCloakStates) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, systemStates, fragmentMarkers,
                phaseCloakStates, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no phase-cloak tier (ships + every
     * optional tier through fragment markers). Every factory and call site that predates
     * the phase-cloak tier routes through this, so the lower tiers are unchanged and
     * pre-v10 frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts,
            List<ShipProfileSnapshot> profileStates,
            List<ShipAllegianceSnapshot> allegianceStates,
            List<ShipEngineStateSnapshot> engineStates,
            List<ShipManeuverCommandSnapshot> maneuverCommands,
            List<ShipSystemStateSnapshot> systemStates,
            List<ShipFragmentMarkerSnapshot> fragmentMarkers) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, systemStates, fragmentMarkers,
                List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no fragment-marker tier (ships +
     * every optional tier through system states). Every factory and call site that
     * predates the fragment-marker tier routes through this, so the lower tiers are unchanged
     * and pre-v9 frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts,
            List<ShipProfileSnapshot> profileStates,
            List<ShipAllegianceSnapshot> allegianceStates,
            List<ShipEngineStateSnapshot> engineStates,
            List<ShipManeuverCommandSnapshot> maneuverCommands,
            List<ShipSystemStateSnapshot> systemStates) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, systemStates, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no system-state tier (ships +
     * optional non-ship + optional flux + optional weapon mounts + optional profile +
     * optional allegiance + optional engine states + optional maneuver commands).
     * Every factory and call site that predates the system-state tier routes through
     * this, so the lower tiers are unchanged and pre-v8 frames stay byte-identical
     * in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts,
            List<ShipProfileSnapshot> profileStates,
            List<ShipAllegianceSnapshot> allegianceStates,
            List<ShipEngineStateSnapshot> engineStates,
            List<ShipManeuverCommandSnapshot> maneuverCommands) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no maneuver-command tier (ships +
     * optional non-ship + optional flux + optional weapon mounts + optional profile +
     * optional allegiance + optional engine states). Every factory and call site that
     * predates the maneuver-command tier routes through this, so the lower tiers are unchanged
     * and pre-v7 frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts,
            List<ShipProfileSnapshot> profileStates,
            List<ShipAllegianceSnapshot> allegianceStates,
            List<ShipEngineStateSnapshot> engineStates) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no engine-state tier (ships + optional
     * non-ship + optional flux + optional weapon mounts + optional profile + optional
     * allegiance). Every factory and call site that predates the engine-state tier routes
     * through this, so the lower tiers are unchanged and pre-v6 frames stay byte-identical
     * in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts,
            List<ShipProfileSnapshot> profileStates,
            List<ShipAllegianceSnapshot> allegianceStates) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no allegiance tier (ships + optional
     * non-ship + optional flux + optional weapon mounts + optional profile). Every factory
     * and call site that predates the allegiance tier routes through this, so the lower tiers
     * are unchanged and pre-allegiance frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts,
            List<ShipProfileSnapshot> profileStates) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no profile tier (ships + optional
     * non-ship + optional flux + optional weapon mounts). Every factory and call site
     * that predates the profile tier routes through this, so the ship/non-ship/flux/
     * weapon-mount tiers are unchanged and pre-profile frames stay byte-identical in
     * shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates,
            List<ShipWeaponMountSnapshot> weaponMounts) {
        this(simTick, entities, nonShipEntities, fluxStates, weaponMounts, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no weapon-mount tier (ships +
     * optional non-ship + optional flux). Every factory and call site that predates
     * the weapon-mount tier routes through this, so the ship/non-ship/flux tiers are
     * unchanged and pre-weapon-mount frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities,
            List<ShipFluxSnapshot> fluxStates) {
        this(simTick, entities, nonShipEntities, fluxStates, List.of());
    }

    /**
     * Back-compat constructor for a snapshot with no flux tier (ships + optional
     * non-ship entities). Every factory and call site that predates the flux tier
     * routes through this, so the ship/non-ship tiers are unchanged and pre-flux
     * frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(
            long simTick,
            List<AuthoritativeEntitySnapshot> entities,
            List<NonShipEntitySnapshot> nonShipEntities) {
        this(simTick, entities, nonShipEntities, List.of());
    }

    /**
     * Back-compat constructor for a ship-only snapshot (no non-ship entities). Every
     * factory and call site that predates the non-ship tier routes through this, so
     * the ship tier is unchanged and ship-only frames stay byte-identical in shape.
     */
    public AuthoritativeWorldSnapshot(long simTick, List<AuthoritativeEntitySnapshot> entities) {
        this(simTick, entities, List.of(), List.of());
    }

    /**
     * Component-named builder. Every tier-local
     * copy in this file ({@code with*}, {@code withoutProfileTier}, {@code relevantFor}) and
     * every external full-tier construction (e.g. {@code SnapshotChunker.mintChunk}) routes
     * through this instead of the full positional canonical constructor. The point is what
     * happens when the NEXT tier is added: the only raw all-components constructor call left
     * is {@link Builder#build()}, so the record-header change is compile-caught in exactly one
     * place — a positional full-args call elsewhere would instead silently rebind to the new
     * back-compat constructor and DROP the new tier (the v7-era ctor-fan-out hazard).
     * The telescoping back-compat constructors above are NOT deprecated by this: a
     * version-pinned decode path ({@code decodeBodyV5..V7}) or downgrade projection
     * ({@code without*Tier}) uses them deliberately — binding low clears every higher tier,
     * which is the intended semantics there and stays correct unedited when a tier is added.
     */
    public static final class Builder {
        private long simTick;
        private List<AuthoritativeEntitySnapshot> entities = List.of();
        private List<NonShipEntitySnapshot> nonShipEntities = List.of();
        private List<ShipFluxSnapshot> fluxStates = List.of();
        private List<ShipWeaponMountSnapshot> weaponMounts = List.of();
        private List<ShipProfileSnapshot> profileStates = List.of();
        private List<ShipAllegianceSnapshot> allegianceStates = List.of();
        private List<ShipEngineStateSnapshot> engineStates = List.of();
        private List<ShipManeuverCommandSnapshot> maneuverCommands = List.of();
        private List<ShipSystemStateSnapshot> systemStates = List.of();
        private List<ShipFragmentMarkerSnapshot> fragmentMarkers = List.of();
        private List<ShipPhaseCloakStateSnapshot> phaseCloakStates = List.of();
        private List<ShipCrSnapshot> crStates = List.of();
        private List<ContentDictionaryEntry> contentDictionary = List.of();

        public Builder simTick(long simTick) {
            this.simTick = simTick;
            return this;
        }

        public Builder entities(List<AuthoritativeEntitySnapshot> entities) {
            this.entities = Objects.requireNonNull(entities, "entities");
            return this;
        }

        public Builder nonShipEntities(List<NonShipEntitySnapshot> nonShipEntities) {
            this.nonShipEntities = Objects.requireNonNull(nonShipEntities, "nonShipEntities");
            return this;
        }

        public Builder fluxStates(List<ShipFluxSnapshot> fluxStates) {
            this.fluxStates = Objects.requireNonNull(fluxStates, "fluxStates");
            return this;
        }

        public Builder weaponMounts(List<ShipWeaponMountSnapshot> weaponMounts) {
            this.weaponMounts = Objects.requireNonNull(weaponMounts, "weaponMounts");
            return this;
        }

        public Builder profileStates(List<ShipProfileSnapshot> profileStates) {
            this.profileStates = Objects.requireNonNull(profileStates, "profileStates");
            return this;
        }

        public Builder allegianceStates(List<ShipAllegianceSnapshot> allegianceStates) {
            this.allegianceStates = Objects.requireNonNull(allegianceStates, "allegianceStates");
            return this;
        }

        public Builder engineStates(List<ShipEngineStateSnapshot> engineStates) {
            this.engineStates = Objects.requireNonNull(engineStates, "engineStates");
            return this;
        }

        public Builder maneuverCommands(List<ShipManeuverCommandSnapshot> maneuverCommands) {
            this.maneuverCommands = Objects.requireNonNull(maneuverCommands, "maneuverCommands");
            return this;
        }

        public Builder systemStates(List<ShipSystemStateSnapshot> systemStates) {
            this.systemStates = Objects.requireNonNull(systemStates, "systemStates");
            return this;
        }

        public Builder fragmentMarkers(List<ShipFragmentMarkerSnapshot> fragmentMarkers) {
            this.fragmentMarkers = Objects.requireNonNull(fragmentMarkers, "fragmentMarkers");
            return this;
        }

        public Builder phaseCloakStates(List<ShipPhaseCloakStateSnapshot> phaseCloakStates) {
            this.phaseCloakStates = Objects.requireNonNull(phaseCloakStates, "phaseCloakStates");
            return this;
        }

        public Builder crStates(List<ShipCrSnapshot> crStates) {
            this.crStates = Objects.requireNonNull(crStates, "crStates");
            return this;
        }

        public Builder contentDictionary(List<ContentDictionaryEntry> contentDictionary) {
            this.contentDictionary =
                    Objects.requireNonNull(contentDictionary, "contentDictionary");
            return this;
        }

        /** The one full-components construction site; canonical-constructor validation applies. */
        public AuthoritativeWorldSnapshot build() {
            return new AuthoritativeWorldSnapshot(
                    simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                    allegianceStates, engineStates, maneuverCommands, systemStates,
                    fragmentMarkers, phaseCloakStates, crStates, contentDictionary);
        }
    }

    /** Builder pre-loaded with every component of this snapshot (tier-local copy idiom). */
    public Builder toBuilder() {
        return new Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .profileStates(profileStates)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .phaseCloakStates(phaseCloakStates)
                .crStates(crStates)
                .contentDictionary(contentDictionary);
    }

    public int entityCount() {
        return entities.size();
    }

    public int nonShipEntityCount() {
        return nonShipEntities.size();
    }

    public int fluxStateCount() {
        return fluxStates.size();
    }

    public int weaponMountCount() {
        return weaponMounts.size();
    }

    public int profileStateCount() {
        return profileStates.size();
    }

    public int allegianceStateCount() {
        return allegianceStates.size();
    }

    public int engineStateCount() {
        return engineStates.size();
    }

    public int maneuverCommandCount() {
        return maneuverCommands.size();
    }

    public int systemStateCount() {
        return systemStates.size();
    }

    public int fragmentMarkerCount() {
        return fragmentMarkers.size();
    }

    public int phaseCloakStateCount() {
        return phaseCloakStates.size();
    }

    public int crStateCount() {
        return crStates.size();
    }

    public int contentDictionaryCount() {
        return contentDictionary.size();
    }

    /**
     * Return a copy of {@code base} with its ship tier intact and the given non-ship
     * entities folded in. This is what the live per-tick source calls after pairing
     * the ship motion/durability views into {@code base}: it reads the live
     * non-ship lists, assigns ordered network ids, and attaches them here so the same
     * frame carries ships plus projectiles/missiles/beams.
     */
    public static AuthoritativeWorldSnapshot withNonShipEntities(
            AuthoritativeWorldSnapshot base, List<NonShipEntitySnapshot> nonShipEntities) {
        Objects.requireNonNull(base, "base");
        // Tier-local since the builder promotion: every other tier of base is
        // preserved (previously higher tiers were positionally truncated — harmless in the
        // ascending live fold chain where they are still empty, but a landmine out of order).
        return base.toBuilder().nonShipEntities(nonShipEntities).build();
    }

    /**
     * Return a copy of {@code base} with its ship and non-ship tiers intact and the
     * given per-ship flux states folded in. This is what the live per-tick source
     * calls after building the ship tier and folding the non-ship tier: it reads the
     * live flux/shield views, keys each to its ship's {@code entityId}, and
     * attaches them here so the same frame carries ships + projectiles/beams + flux.
     * The flux states are ordered ascending by {@code entityId} for a deterministic
     * wire order (the constructor rejects an out-of-order or duplicated id).
     */
    public static AuthoritativeWorldSnapshot withFluxStates(
            AuthoritativeWorldSnapshot base, List<ShipFluxSnapshot> fluxStates) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(fluxStates, "fluxStates");
        List<ShipFluxSnapshot> ordered = new ArrayList<>(fluxStates);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        // Tier-local (builder promotion): every other tier of base is preserved.
        return base.toBuilder().fluxStates(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its ship, non-ship, and flux tiers intact and
     * the given per-mount weapon-readiness states folded in. This is what the live
     * per-tick source calls after building the ship tier and folding the non-ship and
     * flux tiers: it reads the live weapon-mount views, keys each to its
     * ship's {@code entityId}, interns its weapon spec id, and attaches them here so the
     * same frame carries ships + projectiles/beams + flux + per-mount readiness. The
     * mounts are ordered ascending by the composite ({@code entityId}, {@code mountIndex})
     * for a deterministic wire order (the constructor rejects an out-of-order or
     * duplicated mount key).
     */
    public static AuthoritativeWorldSnapshot withWeaponMounts(
            AuthoritativeWorldSnapshot base, List<ShipWeaponMountSnapshot> weaponMounts) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(weaponMounts, "weaponMounts");
        List<ShipWeaponMountSnapshot> ordered = new ArrayList<>(weaponMounts);
        ordered.sort((a, b) -> {
            int byEntity = Long.compare(a.entityId(), b.entityId());
            return byEntity != 0 ? byEntity : Integer.compare(a.mountIndex(), b.mountIndex());
        });
        // Tier-local (builder promotion): every other tier of base is preserved.
        return base.toBuilder().weaponMounts(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its ship, non-ship, flux, and weapon-mount tiers
     * intact and the given per-ship physical-profile states folded in. This fold has NO live
     * caller at HEAD (zero callers repo-wide; the live source folds the other tiers but never
     * this one): {@code profileStates} is a DEFERRED, never-encoded Stage-A in-memory tier,
     * NOT part of the live wire-downgrade chain — self-consistent with this file's
     * prose above ({@code profileStates} "a deferred, never-encoded Stage-A in-memory tier ...
     * NOT a wire schema"). When/if exercised it would read the profile views, key each
     * to its ship's {@code entityId}, and attach them so the frame also carries physical profile.
     * The profile states are ordered ascending by {@code entityId} for a deterministic wire order
     * (the constructor rejects an out-of-order or duplicated id).
     */
    public static AuthoritativeWorldSnapshot withProfileStates(
            AuthoritativeWorldSnapshot base, List<ShipProfileSnapshot> profileStates) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(profileStates, "profileStates");
        List<ShipProfileSnapshot> ordered = new ArrayList<>(profileStates);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return base.toBuilder().profileStates(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its lower tiers intact and the given per-ship
     * combat-side allegiance states folded in (the friend/foe tier, schema v5). This is what
     * the live per-tick source calls after building the ship tier and folding the non-ship,
     * flux, and weapon-mount tiers: it reads the bridge's {@code shipAllegiancesOrNull()}
     * views, keys each to its ship's {@code entityId}, and attaches them here so the same
     * frame carries ships + projectiles/beams + flux + per-mount readiness + combat side. The
     * states are ordered ascending by {@code entityId} for a deterministic wire order (the
     * constructor rejects an out-of-order or duplicated id). The {@code profileStates} tier (an
     * unwired Stage-A in-memory tier) is preserved unchanged.
     */
    public static AuthoritativeWorldSnapshot withAllegiances(
            AuthoritativeWorldSnapshot base, List<ShipAllegianceSnapshot> allegianceStates) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(allegianceStates, "allegianceStates");
        List<ShipAllegianceSnapshot> ordered = new ArrayList<>(allegianceStates);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return base.toBuilder().allegianceStates(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its lower tiers intact and the given per-ship
     * engine-disabled/flameout states folded in (schema v6). This is
     * what the live per-tick source calls after folding the allegiance tier: it reads the
     * bridge's {@code shipEngineStatesOrNull()} views, keys each to its ship's
     * {@code entityId}, and attaches them here so the same frame carries the per-engine
     * disabled bits a predictive client needs to apply the real flameout mechanism
     * locally. The states are ordered ascending by {@code entityId} for a deterministic
     * wire order (the constructor rejects an out-of-order or duplicated id).
     */
    public static AuthoritativeWorldSnapshot withEngineStates(
            AuthoritativeWorldSnapshot base, List<ShipEngineStateSnapshot> engineStates) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(engineStates, "engineStates");
        List<ShipEngineStateSnapshot> ordered = new ArrayList<>(engineStates);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return base.toBuilder().engineStates(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its lower tiers intact and the given per-ship
     * maneuver-command states folded in (schema v7). This is what
     * the live per-tick source calls after folding the engine-state tier: it reads the
     * bridge's {@code shipManeuverCommandsOrNull()} views, keys each to its ship's
     * {@code entityId}, and attaches them here so the same frame carries the maneuver
     * mask a predictive client needs to re-issue the foe's real commands locally (the
     * engine's own controller then drives plume/glow/contrail/audio). The states are
     * ordered ascending by {@code entityId} for a deterministic wire order (the
     * constructor rejects an out-of-order or duplicated id).
     */
    public static AuthoritativeWorldSnapshot withManeuverCommands(
            AuthoritativeWorldSnapshot base, List<ShipManeuverCommandSnapshot> maneuverCommands) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(maneuverCommands, "maneuverCommands");
        List<ShipManeuverCommandSnapshot> ordered = new ArrayList<>(maneuverCommands);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return base.toBuilder().maneuverCommands(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its lower tiers intact and the given per-ship
     * primary ship-system states folded in (schema v8). This is what
     * the live per-tick source calls after folding the maneuver-command tier: it reads
     * the bridge's {@code shipSystemStatesOrNull()} views, keys each to its ship's
     * {@code entityId}, and attaches them here so the same frame carries the system
     * phase/progress/ammo truth a predictive client needs to seed a mid-window replica
     * system through the engine's own faithful {@code forceState} path. The states are
     * ordered ascending by {@code entityId} for a deterministic wire order (the
     * constructor rejects an out-of-order or duplicated id).
     */
    public static AuthoritativeWorldSnapshot withSystemStates(
            AuthoritativeWorldSnapshot base, List<ShipSystemStateSnapshot> systemStates) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(systemStates, "systemStates");
        List<ShipSystemStateSnapshot> ordered = new ArrayList<>(systemStates);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return base.toBuilder().systemStates(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its lower tiers intact and the given fragment
     * markers folded in (the FRAGMENT_MARKER tail tier, schema v9). Unlike the
     * live per-tick tier folds above, this is called by the {@code SnapshotChunker}
     * fragment path when ONE over-cap entity's records are split across chunk frames:
     * each fragment frame declares, per marked entity, which ship-scoped tiers (and which
     * mount range) it asserts. The markers are ordered ascending by {@code entityId} for
     * a deterministic wire order (the constructor rejects an out-of-order or duplicated
     * id).
     */
    public static AuthoritativeWorldSnapshot withFragmentMarkers(
            AuthoritativeWorldSnapshot base, List<ShipFragmentMarkerSnapshot> fragmentMarkers) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(fragmentMarkers, "fragmentMarkers");
        List<ShipFragmentMarkerSnapshot> ordered = new ArrayList<>(fragmentMarkers);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return base.toBuilder().fragmentMarkers(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its lower tiers intact and the given per-ship
     * phase-cloak states folded in (schema v10). This is what the
     * live per-tick source calls after folding the system-state tier: it reads the
     * bridge's {@code shipPhaseCloakStatesOrNull()} views (the separate
     * {@code getPhaseCloak()} slot), keys each to its ship's {@code entityId}, and
     * attaches them here so the same frame carries the cloak phase/progress truth a
     * predictive client needs to seed a mid-window replica cloak through the engine's
     * own faithful {@code forceState} path. The states are ordered ascending by
     * {@code entityId} for a deterministic wire order (the constructor rejects an
     * out-of-order or duplicated id).
     */
    public static AuthoritativeWorldSnapshot withPhaseCloakStates(
            AuthoritativeWorldSnapshot base, List<ShipPhaseCloakStateSnapshot> phaseCloakStates) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(phaseCloakStates, "phaseCloakStates");
        List<ShipPhaseCloakStateSnapshot> ordered = new ArrayList<>(phaseCloakStates);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return base.toBuilder().phaseCloakStates(ordered).build();
    }

    /**
     * Return a copy of {@code base} with its lower tiers intact and the given per-ship
     * CR states folded in (schema v14). This is what the live
     * per-tick source calls after folding the phase-cloak tier: it reads the bridge's
     * {@code shipCrStatesOrNull()} views, keys each to its ship's {@code entityId}, and
     * attaches them here so the same frame carries the authoritative CR/deployed-clock
     * truth the predictive client's own-CR pin needs (headless-side the local clock is
     * frozen). The states are ordered ascending by {@code entityId} for a deterministic
     * wire order (the constructor rejects an out-of-order or duplicated id).
     */
    public static AuthoritativeWorldSnapshot withCrStates(
            AuthoritativeWorldSnapshot base, List<ShipCrSnapshot> crStates) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(crStates, "crStates");
        List<ShipCrSnapshot> ordered = new ArrayList<>(crStates);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return base.toBuilder().crStates(ordered).build();
    }

    /**
     * Build a snapshot from an unordered entity collection, sorting by
     * {@code entityId} and rejecting duplicates. This is what a per-tick
     * server publisher calls after collecting one {@link AuthoritativeEntitySnapshot}
     * per ship from the engine-backed views.
     */
    public static AuthoritativeWorldSnapshot of(
            long simTick, List<AuthoritativeEntitySnapshot> entities) {
        Objects.requireNonNull(entities, "entities");
        List<AuthoritativeEntitySnapshot> ordered = new ArrayList<>(entities);
        ordered.sort((a, b) -> Long.compare(a.entityId(), b.entityId()));
        return new AuthoritativeWorldSnapshot(simTick, ordered);
    }

    /**
     * Build a snapshot for one completed tick from the parallel per-ship
     * {@link ShipMotionView} and {@link ShipDurabilityView} lists the live engine
     * bridge exposes (e.g. {@code EngineCombatBinding.shipMotionsOrNull()} /
     * {@code shipDurabilitiesOrNull()}). This is the per-tick server publisher step
     * the snapshot-authoritative rule requires: it pairs the two read models by {@code shipId} (not list
     * position, which the engine does not guarantee), builds one
     * {@link AuthoritativeEntitySnapshot} per ship, and orders by {@code entityId}.
     *
     * <p>{@code contentId}, {@code spawnTick}, and {@code ownerConnectionId} are not
     * yet sourced from the engine views (a later bridge task);
     * they are filled with neutral defaults
     * (0 / 0 / {@link AuthoritativeEntitySnapshot#NO_OWNER_CONNECTION}) so the motion
     * and durability state — the part that actually evolves per tick — is carried
     * faithfully.
     */
    public static AuthoritativeWorldSnapshot fromViewLists(
            long simTick,
            List<? extends ShipMotionView> motions,
            List<? extends ShipDurabilityView> durabilities) {
        Objects.requireNonNull(motions, "motions");
        Objects.requireNonNull(durabilities, "durabilities");
        if (motions.size() != durabilities.size()) {
            throw new IllegalArgumentException(
                    "motion/durability view counts differ: "
                            + motions.size() + " vs " + durabilities.size());
        }
        Map<Long, ShipDurabilityView> durabilityByShipId = new HashMap<>();
        for (ShipDurabilityView durability : durabilities) {
            Objects.requireNonNull(durability, "durability");
            if (durabilityByShipId.put(durability.shipId(), durability) != null) {
                throw new IllegalArgumentException(
                        "duplicate durability shipId: " + durability.shipId());
            }
        }
        List<AuthoritativeEntitySnapshot> entities = new ArrayList<>(motions.size());
        for (ShipMotionView motion : motions) {
            Objects.requireNonNull(motion, "motion");
            ShipDurabilityView durability = durabilityByShipId.get(motion.shipId());
            if (durability == null) {
                throw new IllegalArgumentException(
                        "no durability view paired with motion shipId: " + motion.shipId());
            }
            entities.add(AuthoritativeEntitySnapshot.fromViews(
                    motion,
                    durability,
                    0,
                    0L,
                    AuthoritativeEntitySnapshot.NO_OWNER_CONNECTION));
        }
        return of(simTick, entities);
    }

    /**
     * Identity-aware variant of {@link #fromViewLists}: the same per-tick
     * motion+durability pairing, but each entity also carries its server-resolved
     * {@code contentId} (interned hull-spec id), {@code spawnTick}, and
     * {@code ownerConnectionId} instead of the neutral 0/0/{@link
     * AuthoritativeEntitySnapshot#NO_OWNER_CONNECTION} defaults.
     *
     * <p>The three identity fields are passed as already-resolved per-{@code shipId}
     * maps so this protocol type never depends on the runtime content registry or
     * the session authority assignment: the caller (the engine-backed live source)
     * reads {@link cc.starsector.server.adapter.ShipIdentityView}s off the bridge,
     * interns the content keys, and looks up ownership/spawn bookkeeping. A ship
     * absent from a map falls back to that field's neutral default, so a partial
     * identity read degrades to motion+durability rather than failing.
     */
    public static AuthoritativeWorldSnapshot fromIdentifiedViewLists(
            long simTick,
            List<? extends ShipMotionView> motions,
            List<? extends ShipDurabilityView> durabilities,
            Map<Long, Integer> contentIdByShipId,
            Map<Long, Long> spawnTickByShipId,
            Map<Long, Integer> ownerByShipId) {
        Objects.requireNonNull(motions, "motions");
        Objects.requireNonNull(durabilities, "durabilities");
        Map<Long, Integer> contentIds = contentIdByShipId == null ? Map.of() : contentIdByShipId;
        Map<Long, Long> spawnTicks = spawnTickByShipId == null ? Map.of() : spawnTickByShipId;
        Map<Long, Integer> owners = ownerByShipId == null ? Map.of() : ownerByShipId;
        if (motions.size() != durabilities.size()) {
            throw new IllegalArgumentException(
                    "motion/durability view counts differ: "
                            + motions.size() + " vs " + durabilities.size());
        }
        Map<Long, ShipDurabilityView> durabilityByShipId = new HashMap<>();
        for (ShipDurabilityView durability : durabilities) {
            Objects.requireNonNull(durability, "durability");
            if (durabilityByShipId.put(durability.shipId(), durability) != null) {
                throw new IllegalArgumentException(
                        "duplicate durability shipId: " + durability.shipId());
            }
        }
        List<AuthoritativeEntitySnapshot> entities = new ArrayList<>(motions.size());
        for (ShipMotionView motion : motions) {
            Objects.requireNonNull(motion, "motion");
            ShipDurabilityView durability = durabilityByShipId.get(motion.shipId());
            if (durability == null) {
                throw new IllegalArgumentException(
                        "no durability view paired with motion shipId: " + motion.shipId());
            }
            int contentId = contentIds.getOrDefault(motion.shipId(), 0);
            long spawnTick = spawnTicks.getOrDefault(motion.shipId(), 0L);
            int owner = owners.getOrDefault(
                    motion.shipId(), AuthoritativeEntitySnapshot.NO_OWNER_CONNECTION);
            entities.add(AuthoritativeEntitySnapshot.fromViews(
                    motion, durability, contentId, spawnTick, owner));
        }
        return of(simTick, entities);
    }

    /**
     * Return the snapshot a specific client connection should receive: the same
     * authoritative tick filtered to the entities {@code view} finds relevant.
     * This is the server-side relevance/interest step;
     * the result is itself a valid
     * snapshot ready to encode for that connection.
     */
    public AuthoritativeWorldSnapshot relevantFor(ClientRelevanceView view) {
        Objects.requireNonNull(view, "view");
        List<AuthoritativeEntitySnapshot> relevant = new ArrayList<>(entities.size());
        for (AuthoritativeEntitySnapshot entity : entities) {
            if (view.isRelevant(entity)) {
                relevant.add(entity);
            }
        }
        // The non-ship critical-state tier is filtered by its own (visibility-scoped)
        // relevance rule (WorldSnapshotMessage schema v2): a transient projectile/beam
        // a connection cannot see is dropped from that connection's frame, so an
        // out-of-range client never receives — or pays the bytes for — entities
        // outside its interest set.
        List<NonShipEntitySnapshot> relevantNonShip = new ArrayList<>(nonShipEntities.size());
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            if (view.isRelevant(nonShip)) {
                relevantNonShip.add(nonShip);
            }
        }
        // The flux tier (schema v3) and the weapon-mount tier
        // (schema v4) both belong to a ship: a record is kept only when its ship survived
        // the relevance filter. This scopes them the same way the owning ship is scoped —
        // a client that cannot see a far ship is not told that ship's flux/shield state or
        // its per-mount readiness either. (WorldSnapshotMessage's flux/weapon-mount
        // relevance rules are owner+visibility scoped to match.) The retained-id set is
        // computed once and shared between the two ship-scoped tiers.
        boolean shipsDropped = relevant.size() != entities.size();
        Set<Long> retainedIds = null;
        if (shipsDropped) {
            retainedIds = new HashSet<>();
            for (AuthoritativeEntitySnapshot entity : relevant) {
                retainedIds.add(entity.entityId());
            }
        }
        List<ShipFluxSnapshot> relevantFlux;
        if (!shipsDropped) {
            relevantFlux = fluxStates; // no ships dropped: every flux record is retained
        } else {
            relevantFlux = new ArrayList<>(fluxStates.size());
            for (ShipFluxSnapshot flux : fluxStates) {
                if (retainedIds.contains(flux.entityId())) {
                    relevantFlux.add(flux);
                }
            }
        }
        List<ShipWeaponMountSnapshot> relevantMounts;
        if (!shipsDropped) {
            relevantMounts = weaponMounts; // no ships dropped: every mount record is retained
        } else {
            relevantMounts = new ArrayList<>(weaponMounts.size());
            for (ShipWeaponMountSnapshot mount : weaponMounts) {
                if (retainedIds.contains(mount.entityId())) {
                    relevantMounts.add(mount);
                }
            }
        }
        List<ShipProfileSnapshot> relevantProfiles;
        if (!shipsDropped) {
            relevantProfiles = profileStates; // no ships dropped: every profile record is retained
        } else {
            relevantProfiles = new ArrayList<>(profileStates.size());
            for (ShipProfileSnapshot profile : profileStates) {
                if (retainedIds.contains(profile.entityId())) {
                    relevantProfiles.add(profile);
                }
            }
        }
        // The allegiance tier (schema v5) belongs to a ship and is scoped the same
        // way as the flux/weapon-mount tiers: a record is kept only when its ship survived the
        // relevance filter (a client not told a far ship's motion is not told its side either).
        List<ShipAllegianceSnapshot> relevantAllegiances;
        if (!shipsDropped) {
            relevantAllegiances = allegianceStates; // no ships dropped: every allegiance retained
        } else {
            relevantAllegiances = new ArrayList<>(allegianceStates.size());
            for (ShipAllegianceSnapshot allegiance : allegianceStates) {
                if (retainedIds.contains(allegiance.entityId())) {
                    relevantAllegiances.add(allegiance);
                }
            }
        }
        // The engine-state tier (schema v6) belongs to a ship and is scoped the
        // same way: a record is kept only when its ship survived the relevance filter.
        List<ShipEngineStateSnapshot> relevantEngineStates;
        if (!shipsDropped) {
            relevantEngineStates = engineStates; // no ships dropped: every engine state retained
        } else {
            relevantEngineStates = new ArrayList<>(engineStates.size());
            for (ShipEngineStateSnapshot engineState : engineStates) {
                if (retainedIds.contains(engineState.entityId())) {
                    relevantEngineStates.add(engineState);
                }
            }
        }
        // The maneuver-command tier (schema v7) belongs to a ship and is scoped
        // the same way: a record is kept only when its ship survived the relevance filter.
        List<ShipManeuverCommandSnapshot> relevantManeuvers;
        if (!shipsDropped) {
            relevantManeuvers = maneuverCommands; // no ships dropped: every maneuver retained
        } else {
            relevantManeuvers = new ArrayList<>(maneuverCommands.size());
            for (ShipManeuverCommandSnapshot maneuverCommand : maneuverCommands) {
                if (retainedIds.contains(maneuverCommand.entityId())) {
                    relevantManeuvers.add(maneuverCommand);
                }
            }
        }
        // The system-state tier (schema v8) belongs to a ship and is scoped
        // the same way: a record is kept only when its ship survived the relevance filter.
        List<ShipSystemStateSnapshot> relevantSystemStates;
        if (!shipsDropped) {
            relevantSystemStates = systemStates; // no ships dropped: every system state retained
        } else {
            relevantSystemStates = new ArrayList<>(systemStates.size());
            for (ShipSystemStateSnapshot systemState : systemStates) {
                if (retainedIds.contains(systemState.entityId())) {
                    relevantSystemStates.add(systemState);
                }
            }
        }
        // The fragment-marker tier (schema v9) declares a ship's partial
        // carriage, so it is ship-scoped the same way: a marker is kept only when its
        // ship survived the relevance filter (dropping the ship drops its markers with
        // its records, keeping the frame self-joining).
        List<ShipFragmentMarkerSnapshot> relevantMarkers;
        if (!shipsDropped) {
            relevantMarkers = fragmentMarkers; // no ships dropped: every marker retained
        } else {
            relevantMarkers = new ArrayList<>(fragmentMarkers.size());
            for (ShipFragmentMarkerSnapshot marker : fragmentMarkers) {
                if (retainedIds.contains(marker.entityId())) {
                    relevantMarkers.add(marker);
                }
            }
        }
        // The phase-cloak tier (schema v10) belongs to a ship and is scoped
        // the same way: a record is kept only when its ship survived the relevance filter.
        List<ShipPhaseCloakStateSnapshot> relevantPhaseCloaks;
        if (!shipsDropped) {
            relevantPhaseCloaks = phaseCloakStates; // no ships dropped: every cloak retained
        } else {
            relevantPhaseCloaks = new ArrayList<>(phaseCloakStates.size());
            for (ShipPhaseCloakStateSnapshot phaseCloak : phaseCloakStates) {
                if (retainedIds.contains(phaseCloak.entityId())) {
                    relevantPhaseCloaks.add(phaseCloak);
                }
            }
        }
        // The CR tier (schema v14) belongs to a ship and is scoped the same
        // way: a record is kept only when its ship survived the relevance filter.
        List<ShipCrSnapshot> relevantCrStates;
        if (!shipsDropped) {
            relevantCrStates = crStates; // no ships dropped: every CR state retained
        } else {
            relevantCrStates = new ArrayList<>(crStates.size());
            for (ShipCrSnapshot crState : crStates) {
                if (retainedIds.contains(crState.entityId())) {
                    relevantCrStates.add(crState);
                }
            }
        }
        if (relevant.size() == entities.size()
                && relevantNonShip.size() == nonShipEntities.size()
                && relevantFlux.size() == fluxStates.size()
                && relevantMounts.size() == weaponMounts.size()
                && relevantProfiles.size() == profileStates.size()
                && relevantAllegiances.size() == allegianceStates.size()
                && relevantEngineStates.size() == engineStates.size()
                && relevantManeuvers.size() == maneuverCommands.size()
                && relevantSystemStates.size() == systemStates.size()
                && relevantMarkers.size() == fragmentMarkers.size()
                && relevantPhaseCloaks.size() == phaseCloakStates.size()
                && relevantCrStates.size() == crStates.size()) {
            return this;
        }
        // The content-dictionary tier (schema v15) is FRAME-GLOBAL — catalog
        // announcements, not entity state — so relevance filtering passes it through whole.
        return new Builder()
                .simTick(simTick)
                .entities(relevant)
                .nonShipEntities(relevantNonShip)
                .fluxStates(relevantFlux)
                .weaponMounts(relevantMounts)
                .profileStates(relevantProfiles)
                .allegianceStates(relevantAllegiances)
                .engineStates(relevantEngineStates)
                .maneuverCommands(relevantManeuvers)
                .systemStates(relevantSystemStates)
                .fragmentMarkers(relevantMarkers)
                .phaseCloakStates(relevantPhaseCloaks)
                .crStates(relevantCrStates)
                .contentDictionary(contentDictionary)
                .build();
    }

    /**
     * Ship-tier-only projection of this snapshot: the same ships at the same tick
     * with the non-ship critical-state tier dropped (and, with it, the higher flux and
     * weapon-mount tiers, which a v1 frame cannot carry). This is what the server serves
     * to a schema-v1 client that cannot decode the v2 non-ship section — the
     * downgrade {@code NetworkMessageRegistry.negotiateServedSchema} selects when a
     * peer's maximum supported schema is v1. The result encodes as a v1 frame.
     */
    public AuthoritativeWorldSnapshot withoutNonShipTier() {
        if (nonShipEntities.isEmpty()
                && fluxStates.isEmpty()
                && weaponMounts.isEmpty()
                && profileStates.isEmpty()
                && allegianceStates.isEmpty()
                && engineStates.isEmpty()
                && maneuverCommands.isEmpty()
                && systemStates.isEmpty()
                && fragmentMarkers.isEmpty()
                && phaseCloakStates.isEmpty() && crStates.isEmpty()
                && contentDictionary.isEmpty()) {
            return this;
        }
        return new AuthoritativeWorldSnapshot(simTick, entities, List.of());
    }

    /**
     * Flux-tier-dropped projection of this snapshot: the same ships and non-ship
     * critical-state tier with the per-ship flux/shield tier (schema v3)
     * removed (and, with it, the higher weapon-mount tier, which a v2 frame cannot
     * carry). This is the downgrade
     * {@code NetworkMessageRegistry.negotiateServedSchema} selects when a peer's
     * maximum supported schema is below v3 — the result encodes as a v2 frame (or v1
     * if it also has no non-ship tier). The ship and non-ship tiers are preserved.
     */
    public AuthoritativeWorldSnapshot withoutFluxTier() {
        if (fluxStates.isEmpty()
                && weaponMounts.isEmpty()
                && profileStates.isEmpty()
                && allegianceStates.isEmpty()
                && engineStates.isEmpty()
                && maneuverCommands.isEmpty()
                && systemStates.isEmpty()
                && fragmentMarkers.isEmpty()
                && phaseCloakStates.isEmpty() && crStates.isEmpty()
                && contentDictionary.isEmpty()) {
            return this;
        }
        return new AuthoritativeWorldSnapshot(simTick, entities, nonShipEntities, List.of());
    }

    /**
     * Weapon-mount-tier-dropped projection of this snapshot: the same ships, non-ship
     * critical-state tier, and per-ship flux tier with the per-mount weapon-readiness
     * tier (schema v4) removed. This is the downgrade
     * {@code NetworkMessageRegistry.negotiateServedSchema} selects when a peer's
     * maximum supported schema is below v4 — the result encodes as a v3 frame (or v2/v1
     * if it also has no flux/non-ship tier). The ship, non-ship, and flux tiers are
     * preserved (so a v3 peer still receives flux), unlike {@link #withoutFluxTier()},
     * which a v2-or-lower peer takes instead.
     */
    public AuthoritativeWorldSnapshot withoutWeaponMountTier() {
        if (weaponMounts.isEmpty() && profileStates.isEmpty() && allegianceStates.isEmpty()
                && engineStates.isEmpty() && maneuverCommands.isEmpty()
                && systemStates.isEmpty() && fragmentMarkers.isEmpty()
                && phaseCloakStates.isEmpty() && crStates.isEmpty()
                && contentDictionary.isEmpty()) {
            return this;
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, List.of());
    }

    /**
     * Allegiance-tier-dropped projection of this snapshot: the same ships, non-ship,
     * flux, and weapon-mount tiers with the per-ship combat-side tier (schema v5)
     * removed. This is the downgrade
     * {@code NetworkMessageRegistry.negotiateServedSchema} selects when a peer's maximum
     * supported schema is below v5 — the result encodes as a v4 frame (or lower if it also
     * has no weapon/flux/non-ship tier). The ship, non-ship, flux, and weapon-mount tiers are
     * preserved (so a v4 peer still receives weapon mounts), unlike
     * {@link #withoutWeaponMountTier()}, which a v3-or-lower peer takes instead. The unwired
     * {@code profileStates} Stage-A tier is preserved (it is never encoded regardless).
     */
    public AuthoritativeWorldSnapshot withoutAllegianceTier() {
        if (allegianceStates.isEmpty() && engineStates.isEmpty() && maneuverCommands.isEmpty()
                && systemStates.isEmpty() && fragmentMarkers.isEmpty()
                && phaseCloakStates.isEmpty() && crStates.isEmpty()
                && contentDictionary.isEmpty()) {
            return this;
        }
        // Dropping to sub-v5 also drops the higher v6 engine-state, v7 maneuver-command, and
        // v8 system-state tiers, which a v4-or-lower frame cannot carry.
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates);
    }

    /**
     * Engine-state-tier-dropped projection of this snapshot: the same ships, non-ship,
     * flux, weapon-mount, and allegiance tiers with the per-ship engine-disabled/flameout
     * tier (schema v6) removed. This is the downgrade
     * {@code NetworkMessageRegistry.negotiateServedSchema} selects when a peer's maximum
     * supported schema is below v6 — the result encodes as a v5 frame (or lower if it also
     * has no allegiance/weapon/flux/non-ship tier). The allegiance tier is preserved (so a
     * v5 peer still receives combat sides), unlike {@link #withoutAllegianceTier()}, which
     * a v4-or-lower peer takes instead. Dropping to sub-v6 also drops the higher v7
     * maneuver-command tier, which a v5-or-lower frame cannot carry.
     */
    public AuthoritativeWorldSnapshot withoutEngineStateTier() {
        if (engineStates.isEmpty() && maneuverCommands.isEmpty() && systemStates.isEmpty()
                && fragmentMarkers.isEmpty() && phaseCloakStates.isEmpty()
                && crStates.isEmpty() && contentDictionary.isEmpty()) {
            return this;
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates);
    }

    /**
     * Maneuver-command-tier-dropped projection of this snapshot: the same ships, non-ship,
     * flux, weapon-mount, allegiance, and engine-state tiers with the per-ship
     * maneuver-command tier (schema v7) removed. This is the downgrade
     * {@code NetworkMessageRegistry.negotiateServedSchema} selects when a peer's maximum
     * supported schema is below v7 — the result encodes as a v6 frame (or lower if it also
     * has no engine-state/allegiance/weapon/flux/non-ship tier). The engine-state tier is
     * preserved (so a v6 peer still receives engine state), unlike
     * {@link #withoutEngineStateTier()}, which a v5-or-lower peer takes instead.
     */
    public AuthoritativeWorldSnapshot withoutManeuverCommandTier() {
        if (maneuverCommands.isEmpty() && systemStates.isEmpty() && fragmentMarkers.isEmpty()
                && phaseCloakStates.isEmpty() && crStates.isEmpty()
                && contentDictionary.isEmpty()) {
            return this;
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates);
    }

    /**
     * System-state-tier-dropped projection of this snapshot: the same ships, non-ship,
     * flux, weapon-mount, allegiance, engine-state, and maneuver-command tiers with the
     * per-ship primary ship-system tier (schema v8) removed. This is the
     * downgrade {@code NetworkMessageRegistry.negotiateServedSchema} selects when a peer's
     * maximum supported schema is below v8 — the result encodes as a v7 frame (or lower if
     * it also has no maneuver/engine-state/allegiance/weapon/flux/non-ship tier). The
     * maneuver-command tier is preserved (so a v7 peer still receives maneuver commands),
     * unlike {@link #withoutManeuverCommandTier()}, which a v6-or-lower peer takes instead.
     */
    public AuthoritativeWorldSnapshot withoutSystemStateTier() {
        if (systemStates.isEmpty() && fragmentMarkers.isEmpty() && phaseCloakStates.isEmpty()
                && crStates.isEmpty() && contentDictionary.isEmpty()) {
            return this;
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands);
    }

    /**
     * Fragment-marker-tier-dropped projection of this snapshot: the same ships and every
     * tier through system states with the FRAGMENT_MARKER tail tier (schema v9)
     * removed. This is the downgrade {@code NetworkMessageRegistry.negotiateServedSchema}
     * selects when a peer's maximum supported schema is below v9 — the result encodes as a
     * v8 frame (or lower if it also has no system-state/lower tier). The system-state tier
     * is preserved (so a v8 peer still receives system states), unlike
     * {@link #withoutSystemStateTier()}, which a v7-or-lower peer takes instead. NOTE: a
     * marker-stripped fragment frame reverts to whole-entity semantics for its partial
     * records — the serve path must never send fragments to a sub-v9 peer (the fragment design's
     * compat hazard; kit discipline guards it — UDP admission carries no maxSchema).
     */
    public AuthoritativeWorldSnapshot withoutFragmentMarkerTier() {
        if (fragmentMarkers.isEmpty() && phaseCloakStates.isEmpty() && crStates.isEmpty()
                && contentDictionary.isEmpty()) {
            return this;
        }
        // Dropping to sub-v9 also drops the higher v10 phase-cloak and v14 CR tiers,
        // which a v8-or-lower frame cannot carry.
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, systemStates);
    }

    /**
     * Phase-cloak-tier-dropped projection of this snapshot: the same ships and every tier
     * through fragment markers with the per-ship phase-cloak tier (schema v10)
     * removed. This is the downgrade {@code NetworkMessageRegistry.negotiateServedSchema}
     * selects when a peer's maximum supported schema is below v10 — the result encodes as
     * a v9 frame (or lower if it also has no marker/lower tier). The fragment-marker tier
     * is preserved (so a v9 peer still receives markers), unlike
     * {@link #withoutFragmentMarkerTier()}, which a v8-or-lower peer takes instead. NOTE:
     * the flux tier's {@code FLAG_PHASED} bit is NOT stripped here — the widened flag set
     * is a within-tier evolution old clients read bit-tolerantly, not a versioned section.
     */
    public AuthoritativeWorldSnapshot withoutPhaseCloakTier() {
        if (phaseCloakStates.isEmpty() && crStates.isEmpty()
                && contentDictionary.isEmpty()) {
            return this;
        }
        // Dropping to sub-v10 also drops the higher v14 CR tier, which a v10-or-lower
        // frame cannot carry (the 11-arg constructor clears both).
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, systemStates, fragmentMarkers);
    }

    /**
     * CR-tier-dropped projection of this snapshot: the same ships and every tier through
     * phase-cloak states with the per-ship COMBAT-READINESS tier (schema v14)
     * removed. This is the downgrade {@code NetworkMessageRegistry.negotiateServedSchema}
     * selects when a peer's maximum supported schema is below v14 — the result encodes as
     * a v13-or-lower frame (the v11/v12/v13 widenings are in-record, not tiers, so the
     * next tier boundary below is v10). The phase-cloak tier is preserved (so a v10..v13
     * peer still receives cloak states), unlike {@link #withoutPhaseCloakTier()}, which a
     * v9-or-lower peer takes instead.
     */
    public AuthoritativeWorldSnapshot withoutCrTier() {
        if (crStates.isEmpty() && contentDictionary.isEmpty()) {
            return this;
        }
        // Dropping to sub-v14 also drops the higher v15 content-dictionary tier, which a
        // v13-or-lower frame cannot carry (the 12-arg constructor clears both).
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, systemStates, fragmentMarkers,
                phaseCloakStates);
    }

    /**
     * Content-dictionary-tier-dropped projection of this snapshot: the same ships and
     * every tier through CR states with the FRAME-GLOBAL content-dictionary tail tier
     * (schema v15) removed. This is the downgrade
     * {@code NetworkMessageRegistry.negotiateServedSchema} selects when a peer's maximum
     * supported schema is v14 — the result encodes as a v14-or-lower frame. The CR tier
     * is preserved (so a v14 peer still receives CR states), unlike
     * {@link #withoutCrTier()}, which a v13-or-lower peer takes instead.
     */
    public AuthoritativeWorldSnapshot withoutContentDictionaryTier() {
        if (contentDictionary.isEmpty()) {
            return this;
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, profileStates,
                allegianceStates, engineStates, maneuverCommands, systemStates, fragmentMarkers,
                phaseCloakStates, crStates);
    }

    /**
     * Profile-tier-dropped projection of this snapshot: the per-ship physical-profile tier
     * removed, every other tier preserved. NOTE: the profile
     * tier is a deferred Stage-A, never-encoded in-memory tier — it has NO assigned wire
     * schema (the genuinely-wired tier above weapon-mount is the v5 combat-side tier, see
     * {@link #withoutAllegianceTier()}). So this is not part of the live wire-downgrade chain
     * {@code NetworkMessageRegistry.negotiateServedSchema} drives; it exists only so a future
     * profile wire-fold has a symmetric projection. The wired allegiance tier is preserved.
     */
    public AuthoritativeWorldSnapshot withoutProfileTier() {
        if (profileStates.isEmpty()) {
            return this;
        }
        // Tier-local (builder promotion): drops ONLY the out-of-band profile tier — unlike the
        // wire-downgrade without*Tier chain above, which deliberately binds a low-arity
        // constructor to clear every higher tier.
        return toBuilder().profileStates(List.of()).build();
    }

    /**
     * Shield-facing-zeroed projection of this snapshot: every flux
     * record kept, with any nonzero quantized shield-facing u8 forced to 0; every other
     * tier preserved. This is the schema-capped serve-down step for a sub-v11 peer:
     * the facing byte is IN-RECORD (not a droppable tail section), so capping a frame
     * below v11 means zeroing the value — the writeFrame stamp predicate is value-based,
     * and a facing-0 frame naturally stamps ≤v10 with the narrow flux record. Like the
     * v8..v10 projections it is capability+test-wired (reserved-dormant per the
     * encode-once law): the live fan-out serves the highest schema a peer
     * can decode from shared chunks and does not mint per-peer bytes today.
     */
    public AuthoritativeWorldSnapshot withShieldFacingZeroed() {
        boolean anyNonzero = false;
        for (ShipFluxSnapshot flux : fluxStates) {
            if (flux.shieldFacingU8() != 0) {
                anyNonzero = true;
                break;
            }
        }
        if (!anyNonzero) {
            return this;
        }
        List<ShipFluxSnapshot> zeroed = new ArrayList<>(fluxStates.size());
        for (ShipFluxSnapshot flux : fluxStates) {
            if (flux.shieldFacingU8() == 0) {
                zeroed.add(flux);
            } else {
                zeroed.add(ShipFluxSnapshot.fromWire(
                        flux.entityId(),
                        flux.currFlux(),
                        flux.maxFlux(),
                        flux.fluxLevel(),
                        flux.hardFlux(),
                        flux.statusFlags()));
            }
        }
        // Tier-local (builder promotion): rewrites ONLY the flux tier's facing values —
        // every other tier, cloaks included, is preserved.
        return toBuilder().fluxStates(zeroed).build();
    }

    /**
     * Source-cleared projection of this snapshot: every non-ship
     * record kept, with ANY resolved source binding — value-generic across kinds,
     * so post-v13 missile pairs clear alongside beam pairs — forced back to the
     * sentinel pair ({@link NonShipEntitySnapshot#NO_SOURCE},
     * {@link NonShipEntitySnapshot#NO_SOURCE_MOUNT}); every other tier preserved.
     * This is the schema-capped serve-down step for a sub-v12 peer: the source pair
     * is IN-RECORD (not a droppable tail section), so capping a frame below v12
     * means clearing the values — the writeFrame stamp predicate is value-based,
     * and a source-free frame naturally stamps ≤v11 with the narrow beam record
     * (and, when its facings are also 0, the narrow flux record — the v12-forces-v11
     * width follows the stamp back down). Like {@link #withShieldFacingZeroed()} and
     * the v8..v10 projections it is capability+test-wired (reserved-dormant per
     * the encode-once law): the live fan-out serves the highest
     * schema a peer can decode from shared chunks and does not mint per-peer bytes
     * today.
     */
    public AuthoritativeWorldSnapshot withBeamSourcesCleared() {
        boolean anyResolved = false;
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            if (nonShip.sourceEntityId() != NonShipEntitySnapshot.NO_SOURCE) {
                anyResolved = true;
                break;
            }
        }
        if (!anyResolved) {
            return this;
        }
        List<NonShipEntitySnapshot> cleared = new ArrayList<>(nonShipEntities.size());
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            if (nonShip.sourceEntityId() == NonShipEntitySnapshot.NO_SOURCE) {
                cleared.add(nonShip);
            } else {
                cleared.add(new NonShipEntitySnapshot(
                        nonShip.networkId(),
                        nonShip.kind(),
                        nonShip.owner(),
                        nonShip.posX(),
                        nonShip.posY(),
                        nonShip.contentId()));
            }
        }
        // Tier-local (builder promotion): rewrites ONLY the non-ship tier's source
        // pairs — every other tier is preserved.
        return toBuilder().nonShipEntities(cleared).build();
    }

    /**
     * Missile-source-cleared projection of this snapshot (schema v13): every
     * non-ship record kept, with any resolved kind=MISSILE source binding forced
     * back to the sentinel pair ({@link NonShipEntitySnapshot#NO_SOURCE},
     * {@link NonShipEntitySnapshot#NO_SOURCE_MOUNT}); resolved BEAM pairs (and every
     * other tier) preserved — the kind-SCOPED serve-down step for a v12 peer, which
     * still decodes beam sources. With the missile pairs cleared the value-based
     * writeFrame stamp lands back at v12 for that peer;
     * {@link #withBeamSourcesCleared()} below it stays the sub-v12 step. Like the
     * other projections it is capability+test-wired (reserved-dormant per the
     * encode-once law).
     */
    public AuthoritativeWorldSnapshot withMissileSourcesCleared() {
        boolean anyResolved = false;
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            if (nonShip.kind() == CombatEntityView.Kind.MISSILE
                    && nonShip.sourceEntityId() != NonShipEntitySnapshot.NO_SOURCE) {
                anyResolved = true;
                break;
            }
        }
        if (!anyResolved) {
            return this;
        }
        List<NonShipEntitySnapshot> cleared = new ArrayList<>(nonShipEntities.size());
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            if (nonShip.kind() != CombatEntityView.Kind.MISSILE
                    || nonShip.sourceEntityId() == NonShipEntitySnapshot.NO_SOURCE) {
                cleared.add(nonShip);
            } else {
                cleared.add(new NonShipEntitySnapshot(
                        nonShip.networkId(),
                        nonShip.kind(),
                        nonShip.owner(),
                        nonShip.posX(),
                        nonShip.posY(),
                        nonShip.contentId()));
            }
        }
        // Tier-local (builder promotion): rewrites ONLY the non-ship tier's MISSILE
        // source pairs — beams and every other tier are preserved.
        return toBuilder().nonShipEntities(cleared).build();
    }

    /**
     * Missile-pose-cleared projection of this snapshot (schema v16): every
     * non-ship record kept, with any kind=MISSILE pose pair forced back to the
     * (0, 0) sentinel; source pairs (and every other tier) preserved — the
     * kind-SCOPED serve-down step for a sub-v16 peer. The pose pair is IN-RECORD
     * (not a droppable tail section), so capping a frame below v16 means clearing
     * the values — the writeFrame stamp predicate is value-based, and a pose-free
     * frame naturally stamps ≤v15 with the v15-or-lower record widths. Like
     * {@link #withMissileSourcesCleared()} it is capability+test-wired
     * (reserved-dormant per the encode-once law): the live
     * fan-out serves the highest schema a peer can decode from shared chunks and
     * does not mint per-peer bytes today.
     */
    public AuthoritativeWorldSnapshot withMissilePosesCleared() {
        boolean anyPose = false;
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            if (nonShip.poseFlags() != 0) {
                anyPose = true;
                break;
            }
        }
        if (!anyPose) {
            return this;
        }
        List<NonShipEntitySnapshot> cleared = new ArrayList<>(nonShipEntities.size());
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            if (nonShip.poseFlags() == 0) {
                cleared.add(nonShip);
            } else {
                cleared.add(new NonShipEntitySnapshot(
                        nonShip.networkId(),
                        nonShip.kind(),
                        nonShip.owner(),
                        nonShip.posX(),
                        nonShip.posY(),
                        nonShip.contentId(),
                        nonShip.sourceEntityId(),
                        nonShip.sourceMountIndex()));
            }
        }
        // Tier-local (builder promotion): rewrites ONLY the non-ship tier's MISSILE
        // pose pairs — sources and every other tier are preserved.
        return toBuilder().nonShipEntities(cleared).build();
    }
}
