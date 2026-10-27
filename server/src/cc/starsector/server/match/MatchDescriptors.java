package cc.starsector.server.match;

import cc.starsector.server.protocol.SeatRole;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The server-only descriptor MINT: the demo shapes — {@code 1v1},
 * {@code bots:N}, {@code seats:K}, {@code teams:S:N}, {@code gauntlet:N} — authored from today's certified constants. Deliberately NOT
 * bundled into the client mod jar: minting is the server's job (the descriptor file/payload is
 * what crosses to clients), and only the mint touches {@link SecureRandom} for seat tokens —
 * created inside the mint methods, never in static init, so merely loading match classes stays
 * trivial under restricted contexts.
 *
 * <p>Defaults are the CERTIFIED 1v1 values (the four-site census):
 * the dmod-free {@code mudskipper2_cc_Hellbore} variant,
 * ±9000 map, deploy CR 0.7, ISS/HSS sides with the certified taglines. The 1v1 mint reproduces
 * the certified mission EXACTLY (verified by parity row V5) so descriptor-ON differs from the
 * certified lane by zero authored values.
 */
public final class MatchDescriptors {

    /** The certified dmod-free duel variant. */
    public static final String CERTIFIED_VARIANT_ID = "mudskipper2_cc_Hellbore";

    /**
     * The D4 gauntlet owner hull: the XIV Onslaught — a skin of base
     * {@code onslaught}, deterministic built-ins, no RNG d-mods, so no {@code _cc}
     * hull strip is needed. Since a mod cleanup (the
     * {@code onslaught_xiv_cc_Elite} variant was removed from the mod) the loadout is the
     * hand-exported {@code onslaught_Hephaestus} ({@code data/variants/onslaught_Hephaestus.variant}
     * — same loadout-pinning + mod-dir discipline as {@link #CERTIFIED_VARIANT_ID}); an operator may
     * override the loadout by editing that file, never by re-pointing at a stock id.
     */
    public static final String GAUNTLET_OWNER_VARIANT_ID = "onslaught_Hephaestus";

    /**
     * The seat-table ceiling of ONE match: seat ids ride the voice wire as an unsigned byte and
     * seat 0 is the observer / "no seat" value, so 1..255 are the only seat ids that exist on the
     * wire. Server-side only (the kit's seat slots already span 256) — raising it means widening
     * the voice seat id, a kit roll.
     */
    public static final int MAX_SEATS = MatchDocumentLaws.SEATS_MAX;

    /** Gauntlet joiner-seat cap: the gauntlet mints N + 1 seats (the owner's hull is seat 1), so N stays under {@link #MAX_SEATS}. */
    public static final int GAUNTLET_MAX_JOINERS = MAX_SEATS - 1;

    /**
     * The Players ceiling a host can ask for: one player holds one seat, so it IS
     * {@link #MAX_SEATS} (an alias the players mint and its refusal texts read).
     *
     *
     */
    public static final int MAX_PLAYERS = MAX_SEATS;

    /**
     * The BASTION D5 default Grendel.
     * Since a requested default swap
     * this is the hand-exported HVD loadout
     * {@code grendel_Hypervelocity} — the phase_anchor-capable hull, deliberately the SAME id
     * as {@link #ANCHOR_GRENDEL_VARIANT_ID} (which stays as the probe lanes' name). The id is
     * case-sensitive and NOT {@code _cc_}-renamed: it is the hand export verbatim,
     * mod-dir-pinned at {@code data/variants/grendel_Hypervelocity.variant} (the reviewed
     * ground truth). The pre-swap HMG pin ({@code grendel_cc_hmg}) was removed from the mod;
     * the seat officer row
     * ({@code data/cc/officers.txt}) is keyed to THIS id.
     */
    public static final String BASTION_GRENDEL_VARIANT_ID = "grendel_Hypervelocity";

    /**
     * The hand-exported Anubis loadout ({@code anubis_Paladin}, 3× guardian beams +
     * the {@code temporalshell} system; ADMITTED + ROLLED) —
     * the {@code bastion:A:B:anubis} seat hull, so the headless temporal-shell parity probe
     * ({@code RealEngineTemporalShellProbeMain}) flies the seat LANE on this system. Hand export
     * verbatim, mod-dir-pinned at {@code data/variants/anubis_Paladin.variant} (same pin law as
     * {@link #BASTION_GRENDEL_VARIANT_ID}).
     */
    public static final String BASTION_ANUBIS_VARIANT_ID = "anubis_Paladin";

    /**
     * The hand-exported HVD Grendel ({@code grendel_Hypervelocity}) —
     * the repo's {@code phase_anchor} carrier, whose emergency-dive intercept is the (66)
     * defect lane. Named separately for the anchor/dive probe lanes
     * ({@code bastion:anchor}); since the swap the field default
     * {@link #BASTION_GRENDEL_VARIANT_ID} is the SAME hull (the pre-swap seat hull's
     * {@code adaptive_coils} was PhaseAnchor-incompatible — the original reason these two
     * constants diverged).
     */
    public static final String ANCHOR_GRENDEL_VARIANT_ID = "grendel_Hypervelocity";

    /**
     * The BASTION D5 leg-2 hull. Since a mod cleanup
     * (the {@code aurora_cc_hvb2} variant was removed from the mod) this
     * repoints to the hand-exported {@code conquest_Broadside}
     * ({@code data/variants/conquest_Broadside.variant}); the constant name is kept for the ~14
     * seat mints it drives. The retired Aurora HVB loadout (SO + 3 s-mods incl. frontemitter, a
     * FRONT-360 served shield riding the edge-toggle law) no longer ships.
     */
    public static final String BASTION_AURORA_VARIANT_ID = "conquest_Broadside";

    /**
     * The W2a wishlist pod:
     * the stock {@code shuttlepod} hull, cc_client-pinned as
     * {@code data/variants/shuttlepod_cc_pod.variant} (zero weapons/OP, shield NONE). The
     * variantId must NEVER be {@code shuttlepod_xfer}: the engine's {@code isShuttlePod()}
     * tests FRIGATE size + THAT exact variant id and turns the hull into the invulnerable,
     * AI-untargetable command-shuttle prop — any other id on this hull is a normal damageable
     * frigate.
     */
    public static final String BASTION_SHUTTLEPOD_VARIANT_ID = "shuttlepod_cc_pod";

    /**
     * SEAT variants declared genuinely COSTLESS — vanilla ship_data carries a
     * BLANK supplies/rec for the pod's hull, so the engine reads DP 0.0 (FAITHFUL,
     * measured). The fortress boot fold ({@code FortressServeBootChecks.foldSeatDp}) accepts a
     * declared row WITHOUT folding it into the curve basis (a pod must never inflate wave
     * difficulty) and fail-closes BOTH ways: a declared id reading positive DP throws (stale
     * declaration — a balance patch priced the hull), and an UNDECLARED non-positive read keeps
     * throwing (the blind-spot guard). Exemption is an explicit reviewed declaration here,
     * never an inference from a 0 read.
     */
    public static final Set<String> ZERO_DP_SEAT_EXEMPT_VARIANT_IDS =
            Set.of(BASTION_SHUTTLEPOD_VARIANT_ID);

    /**
     * cc-ORIGINAL variants that carry the {@code _cc_} id marker but are NOT
     * officer twins — the pod is a new hull mint, not a copy-of-vanilla row carrier, and its
     * seat deliberately fields UNOFFICERED (isolates the first live test).
     * {@code FortressServeBootChecks.requireTwinRows} exempts these from the twin-row
     * requirement and fail-closes the reverse: a declared id that DOES get an officers.txt row
     * throws (stale declaration — the attach would silently un-isolate the seat).
     */
    public static final Set<String> NON_TWIN_CC_VARIANT_IDS =
            Set.of(BASTION_SHUTTLEPOD_VARIANT_ID);

    /**
     * The beam-commission Aurora fixture. The burst-beam loadout
     * (WS007/008 → gravitonbeam, WS009 → phasebeam) is RETIRED: a mod cleanup
     * removed {@code aurora_cc_beam} from the mod, so this repoints to the surviving root Aurora
     * {@code aurora_Heavyblaster} on the SAME hull — the {@code bastion:beam[:rig]} rig shape (a
     * now-dormant RE rig) keeps compiling, it just no longer fields the beam loadout. Fielded by
     * the {@code bastion:beam[:rig]} shapes; every other shape STAYS
     * {@link #BASTION_AURORA_VARIANT_ID} (commission record).
     */
    public static final String BASTION_AURORA_BEAM_VARIANT_ID = "aurora_Heavyblaster";

    /**
     * The Hyperion own-teleport fixture: the
     * Hyperion with the owner's Overdriven loadout (2× heavyblaster + jackhammer; SO + 3
     * s-mods incl. missleracks), cc_client-pinned as
     * {@code data/variants/hyperion_cc_Overdriven.variant} (owner file
     * {@code hyperion_Overdriven.variant}; the onslaught-precedent loadout-pinning + mod-dir
     * discipline). Its hull system is the LONG-RANGE {@code teleporter} — the D5 own-patience
     * leg's instrument ("properly test the longer range teleports"). Fielded ONLY by the
     * {@code bastion:hyperion[:rig]} shapes: the seat-2 hull on every other shape STAYS
     * {@link #BASTION_AURORA_VARIANT_ID}.
     */
    public static final String BASTION_HYPERION_VARIANT_ID = "hyperion_cc_Overdriven";

    /**
     * The BASTION D5 leg-3 wave-1 Remnant hull: the stock beam-free
     * {@code glimmer_Assault} variant (ionpulser + 4× irpulse; system {@code highenergyfocus} =
     * the proven STAT_MOD generic-system lane shape). Deliberately a VANILLA id, NOT a
     * {@code _cc} copy: vanilla variant ids resolve on BOTH ends via the engine's own SpecStore
     * dir-scan ({@code mudskipper2_Hellbore} stock-id precedent) — loadout-pinning
     * ({@code _cc} copies) is a HUMAN-seat discipline, and AI foes fly vanilla. Beam-free WAS
     * load-bearing pre-v12 (continuous beams invisible on the wire);
     * a later change lifted that (v12 beam-source tier + client beam presentation), so the wave foe
     * row is now a 1×Assault + 1×{@link #REMNANT_GLIMMER_SUPPORT_VARIANT_ID} mix.
     */
    public static final String REMNANT_GLIMMER_VARIANT_ID = "glimmer_Assault";

    /**
     * The beam-commission wave foe: the stock ALL-BEAM
     * non-PD {@code glimmer_Support} variant (gravitonbeam + 4× taclaser). Minted 1× alongside
     * 1× {@link #REMNANT_GLIMMER_VARIANT_ID} — the mix keeps a ballistic foe in the
     * wave. Same vanilla-id rationale as the Assault row; its beams ride the v12 beam-source
     * wire tier + the client beam driver.
     */
    public static final String REMNANT_GLIMMER_SUPPORT_VARIANT_ID = "glimmer_Support";

    /**
     * The BASTION D5 leg-3 wave-2 Remnant hull: the stock beam-free
     * {@code lumen_Standard} variant (atropos + swarmer + irpulse + ioncannon; system
     * {@code displacer} = the engine-native 300u teleport under watch attention).
     * Same vanilla-id / beam-free rationale as {@link #REMNANT_GLIMMER_VARIANT_ID}.
     */
    public static final String REMNANT_LUMEN_VARIANT_ID = "lumen_Standard";

    /**
     * The fortress-chain DESTROYER foe: the stock {@code fulgent_Assault} variant
     * (DP 10). Same vanilla-id rationale as {@link #REMNANT_GLIMMER_VARIANT_ID} — AI foes fly
     * vanilla, both ends resolve via SpecStore, and a bare vanilla id carries NO officers.txt
     * row, so it fields UNOFFICERED (the fork-2 "SOME hulls" law).
     */
    public static final String REMNANT_FULGENT_VARIANT_ID = "fulgent_Assault";

    /**
     * The re-author's second fulgent loadout (mixed through w4–w6): the stock
     * {@code fulgent_Support} variant (DP 10; typhoon + 4× {@code pdlaser} beams) — real Ordos
     * mix Assault and Support hulls, and the field-the-beams ruling retired the beam-avoidant
     * single-loadout pick. Vanilla id ⇒ unofficered, same law as
     * {@link #REMNANT_FULGENT_VARIANT_ID}.
     */
    public static final String REMNANT_FULGENT_SUPPORT_VARIANT_ID = "fulgent_Support";

    /**
     * The fortress-chain CRUISER foe (w6 debut): the stock
     * {@code brilliant_Standard} variant (DP 25). Vanilla id ⇒ unofficered, same law as
     * {@link #REMNANT_FULGENT_VARIANT_ID}.
     */
    public static final String REMNANT_BRILLIANT_VARIANT_ID = "brilliant_Standard";

    /**
     * The fortress-chain CAPITAL finale foe (w8): the stock
     * {@code radiant_Standard} variant (DP 60 — NOT its fleet-point cost 30, the trap).
     * Vanilla id ⇒ unofficered, same law as {@link #REMNANT_FULGENT_VARIANT_ID}.
     */
    public static final String REMNANT_RADIANT_VARIANT_ID = "radiant_Standard";

    /**
     * The fortress-chain CARRIER foe (w5 debut; Remnant carriers RATIFIED):
     * the stock {@code scintilla_Strike} variant (DESTROYER carrier, DP 12, wings =
     * {@code lux_wing} + {@code flash_wing} — the flash bombers exercise the B2 bomb lane).
     * The earlier "spark drones stay unfielded" beam caveat is RETIRED
     * (beam lane B1–B4 proven): the Support loadout fields too, via
     * {@link #REMNANT_SCINTILLA_SUPPORT_VARIANT_ID}. Vanilla id ⇒ unofficered, same law as
     * {@link #REMNANT_FULGENT_VARIANT_ID}.
     */
    public static final String REMNANT_SCINTILLA_VARIANT_ID = "scintilla_Strike";

    /**
     * The re-author's second scintilla loadout (w6/w7): the stock
     * {@code scintilla_Support} variant (DP 12, wings = 2× {@code spark_wing} — 5-drone
     * interceptor wings firing {@code pdburst} BEAMS, the fighter-beam surface the field-the-beams
     * ruling ordered fielded). Vanilla id ⇒ unofficered, same law as
     * {@link #REMNANT_FULGENT_VARIANT_ID}.
     */
    public static final String REMNANT_SCINTILLA_SUPPORT_VARIANT_ID = "scintilla_Support";

    /**
     * The fortress-chain SECOND capital (w8 finale beside the radiant): the
     * stock {@code nova_Standard} variant (CAPITAL, DP 40; 4× phasebeam mains + 4× pdburst —
     * the re-author's pick after the field-the-beams ruling retired the beam-light {@code nova_Attack}
     * choice; capital main-gun beams close out the escalation). The old apex-rejection note is
     * SUPERSEDED: apex's system is {@code drone_strike} (STAT_MOD, aiType
     * DRONE_STRIKE), not the engine-internal DRONE_LAUNCHER path, and BOTH aiTypes are already
     * replica-gate-suppressed client-side — the residue is closed by the gate,
     * so apex fields at w7 ({@link #REMNANT_APEX_VARIANT_ID}). Vanilla id ⇒ unofficered, same
     * law as {@link #REMNANT_FULGENT_VARIANT_ID}.
     */
    public static final String REMNANT_NOVA_VARIANT_ID = "nova_Standard";

    /**
     * The fighter-ordnance hull (fielded w7): the stock
     * {@code apex_Overdriven} variant (CRUISER, DP 30 — {@code getSuppliesToRecover}; the
     * "DP 18" was the fleet-points column). The hull carries 2× built-in {@code terminator_wing}
     * (2 fighters each — the pdlaser fighter-BEAM fixture for the arc watch) and the
     * {@code drone_strike} system: converts one bay drone into a {@code terminator_missile}
     * ride-along (engine-side {@code DroneStrikeStats.DroneMissileScript} — the drone body
     * FOLLOWS the missile, so poses + the v13 missile track both cross on existing lanes).
     * The {@code apex_Overdriven} pick was the beam-LIGHT law's; the re-author
     * flips to the neutral {@code apex_Standard} (2× gravitonbeam mains + pdlaser PD,
     * targetingunit — no safetyoverrides aggression); an authored pick.
     * Vanilla id ⇒ unofficered.
     */
    public static final String REMNANT_APEX_VARIANT_ID = "apex_Standard";

    /**
     * The AI-core officer TWINS. Same hull + loadout as their vanilla
     * base ({@code *_cc_*} copies pinned in the cc_client mod data dir), minted ONLY because one
     * hull appears at more than one officer state: the officer lane is keyed by variantId alone
     * (one row per variant, codec law), so tier is expressed by variant twins, never by a new
     * keying axis and never by a wire field (stats never ride the wire). Each twin has exactly
     * one {@code data/cc/officers.txt} row carrying the INTEGRATED AI-core set (the
     * {@code AICoreOfficerPluginImpl} tier table plus the {@code RemnantOfficerGeneratorPlugin}
     * campaign integration, baked at authoring — zero attach-lane code change).
     */
    public static final String REMNANT_GLIMMER_GAMMA_VARIANT_ID = "glimmer_cc_gamma";

    /** {@link #REMNANT_FULGENT_VARIANT_ID} twin, gamma core (see {@link #REMNANT_GLIMMER_GAMMA_VARIANT_ID}). */
    public static final String REMNANT_FULGENT_GAMMA_VARIANT_ID = "fulgent_cc_gamma";

    /** {@link #REMNANT_FULGENT_VARIANT_ID} twin, beta core (see {@link #REMNANT_GLIMMER_GAMMA_VARIANT_ID}). */
    public static final String REMNANT_FULGENT_BETA_VARIANT_ID = "fulgent_cc_beta";

    /** {@link #REMNANT_BRILLIANT_VARIANT_ID} twin, beta core (see {@link #REMNANT_GLIMMER_GAMMA_VARIANT_ID}). */
    public static final String REMNANT_BRILLIANT_BETA_VARIANT_ID = "brilliant_cc_beta";

    /** {@link #REMNANT_BRILLIANT_VARIANT_ID} twin, alpha core (see {@link #REMNANT_GLIMMER_GAMMA_VARIANT_ID}). */
    public static final String REMNANT_BRILLIANT_ALPHA_VARIANT_ID = "brilliant_cc_alpha";

    /** {@link #REMNANT_RADIANT_VARIANT_ID} twin, alpha core (see {@link #REMNANT_GLIMMER_GAMMA_VARIANT_ID}). */
    public static final String REMNANT_RADIANT_ALPHA_VARIANT_ID = "radiant_cc_alpha";

    /**
     * The carrier foe (carriers arc, carriers before
     * fortress): the stock {@code condor_Attack} variant — DESTROYER,
     * DP 10, wings = 2× {@code broadsword_wing} (GUN fighters: no bomber/missile load while
     * slice 3 is unbuilt, and no DRONE_LAUNCHER system — the un-lock-gated drone residue
     * stays untriggered). Same vanilla-id rationale as {@link #REMNANT_GLIMMER_VARIANT_ID}
     * (AI foes fly vanilla; both ends resolve via SpecStore). Fielded ONLY by the
     * {@code bastion:carrier[:rig]} shapes — the first fighter-bay hull any shape fields: the
     * standard {@code bastion:wave} roster+schedule has ZERO bays, so the engine-born-growth
     * tier ({@code cc.server.engineBornGrowth}) is structurally unobservable without it.
     */
    public static final String CARRIER_CONDOR_VARIANT_ID = "condor_Attack";

    /**
     * The strike foe (slice-3 wire-ordnance shape): the stock
     * {@code drover_Strike} goalVariant — DESTROYER, DP 11, 4× {@code harpoon} on an
     * ALTERNATING group (guided MISSILE bursts), wings = {@code broadsword_wing} (GUN-fighter
     * regression) + {@code dagger_wing} (3× BOMBER, atropos torpedoes + per-salvo re-dock
     * traffic — the dock-gap subject), hull system {@code flarelauncher_active} (the
     * first fielded warship flare subject; ship_data.csv/variant verified first-hand
     * 2026-07-22). No DRONE_LAUNCHER anywhere in its roster — the un-lock-gated drone
     * residue stays untriggered. Same vanilla-id rationale as
     * {@link #REMNANT_GLIMMER_VARIANT_ID} (AI foes fly vanilla; both ends resolve via
     * SpecStore). Fielded ONLY by the {@code bastion:strike[:rig]} shapes.
     */
    public static final String STRIKE_DROVER_VARIANT_ID = "drover_Strike";

    /**
     * The missile-split acceptance MIRV vehicles ({@code bastion:mirv} shape): stock
     * {@code gryphon_Standard} (cruiser, {@code hurricane} MIRV in WS 001) +
     * {@code champion_DEMEscort} (cruiser, {@code hydra} MDEM in WS 009) — with the strike
     * roster's condor ({@code pilum}) the foe side fields ALL THREE missile-split families
     * (variant weapon rows verified first-hand 2026-07-23; no vanilla Remnant hull carries
     * any of them, and non-Remnant stand-ins are fine for testing).
     * Fielded ONLY by {@code bastion:mirv}.
     */
    public static final String MIRV_GRYPHON_VARIANT_ID = "gryphon_Standard";

    /** The hydra vehicle — see {@link #MIRV_GRYPHON_VARIANT_ID}. */
    public static final String MIRV_CHAMPION_VARIANT_ID = "champion_DEMEscort";

    /**
     * The Bastion leg-4 objective: the DEFAULT
     * STAR FORTRESS variant — one BASE hull plus its engine-minted module cluster (the base is
     * the objective; module deaths are attrition). Same vanilla-id rationale as
     * {@link #REMNANT_GLIMMER_VARIANT_ID} (AI foes fly vanilla; both ends resolve via the
     * engine's own SpecStore dir-scan) — a {@code _cc} copy would buy nothing here: the
     * cluster is hull-spec data, not a hand-tuned loadout.
     *
     * <p>Repointed
     * {@code station3_Standard} → {@code station3_midline_Standard} (constant name kept — the
     * {@link #BASTION_AURORA_VARIANT_ID} conquest precedent). The station is a DESCRIPTOR-lane
     * choice, never a runtime knob: the client mints the station hull from ITS kit
     * {@code match.txt}, and the three families field DIFFERENT module clusters (24/23/12) —
     * a server-side-only knob would field different hulls on the two sides = structural
     * desync. Family selection = {@link #stationVariantForFamily} + the ops
     * {@code applyStationVariant} descriptor re-author.
     *
     * <p>{@link #isStationVariantId} is the GAME-FREE predicate the serve loop keys its
     * station lane on (module bound term, engine-born-growth requirement, lose-seam wiring),
     * now a 3-id family membership. The ENGINE-side detection seam is
     * {@code ShipVariantAPI.isStation()}, read at the deploy lanes; the two agree by
     * construction because this mint only ever authors real station variant ids and the deploy
     * lanes fail closed when one does not read {@code isStation()}.
     */
    public static final String BASTION_STATION_VARIANT_ID = "station3_midline_Standard";

    /**
     * The three vanilla star-fortress FAMILIES
     * — the {@code applyStationVariant} vocabulary and the ops-GUI combo set, in
     * display order. Family → variant id is {@link #stationVariantForFamily}; the inverse is
     * {@link #familyForStationVariant}. Unknown values THROW both ways (reject-never-clamp).
     */
    public static final List<String> STATION_FAMILIES = List.of("lowtech", "midline", "hightech");

    private static final String STATION_LOWTECH_VARIANT_ID = "station3_Standard";
    private static final String STATION_MIDLINE_VARIANT_ID = "station3_midline_Standard";
    private static final String STATION_HIGHTECH_VARIANT_ID = "station3_hightech_Standard";

    /** Family token → station variant id; unknown family THROWS (fail-closed
     *  vocabulary, the {@link #mint(String)} unknown-shape discipline). */
    public static String stationVariantForFamily(String family) {
        return switch (family == null ? "" : family) {
            case "lowtech" -> STATION_LOWTECH_VARIANT_ID;
            case "midline" -> STATION_MIDLINE_VARIANT_ID;
            case "hightech" -> STATION_HIGHTECH_VARIANT_ID;
            default -> throw new IllegalArgumentException("unknown station family '" + family
                    + "' (expected one of " + STATION_FAMILIES + ")");
        };
    }

    /** Station variant id → family token (the {@link #stationVariantForFamily}
     *  inverse); a non-station id THROWS. */
    public static String familyForStationVariant(String variantId) {
        return switch (variantId == null ? "" : variantId) {
            case STATION_LOWTECH_VARIANT_ID -> "lowtech";
            case STATION_MIDLINE_VARIANT_ID -> "midline";
            case STATION_HIGHTECH_VARIANT_ID -> "hightech";
            default -> throw new IllegalArgumentException("variant id '" + variantId
                    + "' is not a station variant (expected one of the "
                    + STATION_FAMILIES + " family ids)");
        };
    }

    /**
     * The DEFEND STAR FORTRESS shape string, named ONCE here so the mint, the
     * {@code WaveSchedule} escalating chain, its victory latch and the serve-boot DP/officer
     * checks can never key on drifting literals. The {@code :rig} twin is deliberately NOT this
     * shape: it proves the station deploy/lose machinery, never the wave fight.
     */
    public static final String FORTRESS_SHAPE = "bastion:fortress";

    /**
     * The A/B ping-discriminator fortress twin: the
     * {@link #bastionFortressAb} shape string — same fortress behavior everywhere, roster's
     * grendel seat swapped for a second aurora so the owner flies the SAME hull at ~0ms and
     * ~250ms. Named once here for the same no-drifting-literals reason as
     * {@link #FORTRESS_SHAPE}.
     */
    public static final String FORTRESS_AB_SHAPE = "bastion:fortress_ab";

    /**
     * The PvP fortress shape: two player teams, South (side 0) and North (side 1), each from its
     * own map edge, on the fortress wave chain with NO station and no win latch. Its own shape
     * string (never a config row) so the mode rides {@code match.shape}, survives every re-mint
     * and needs no kit change. 20 chars: its minted id is 37 = {@link MatchDocumentLaws#MATCH_ID_MAX}.
     *
     */
    public static final String PVP_SHAPE = "bastion:fortress:pvp";

    /**
     * The ONE fortress-behavior predicate — {@link #FORTRESS_SHAPE}, its
     * A/B twin, or the PvP shape {@link #PVP_SHAPE}. Every serve-side fortress special-case
     * (wave chain compile, boot DP/officer checks, fly-in default, wave DP modes, serve-log
     * cosmetics) keys HERE, so a fortress twin can never silently lose a lane to a missed
     * equals-site; the one PvP carve-out is the WIN latch, which keys on
     * {@code WaveSchedule.latchesRoundEndOnWaveClear} (fortress and NOT PvP). Null is not a
     * fortress; the {@code :rig} twin stays deliberately outside (it proves the station
     * deploy/lose machinery, never the wave fight).
     */
    public static boolean isFortressShape(String shape) {
        return FORTRESS_SHAPE.equals(shape) || FORTRESS_AB_SHAPE.equals(shape)
                || PVP_SHAPE.equals(shape);
    }

    /**
     * Is {@code shape} the PvP shape? Exact match, null false: every other shape (1v1, the
     * gauntlets, {@code bastion:fortress} itself) keeps its behaviour byte-identical.
     *
     */
    public static boolean isPvpShape(String shape) {
        return PVP_SHAPE.equals(shape);
    }

    /**
     * Does an all-gone field HOLD the serve open on {@code shape} (true = PvP only)? The fortress
     * station is the live hull that keeps a fortress serve up between players; PvP fields none,
     * so without the hold an empty field latches the roster-0 match end and the serve exits clean
     * (never relaunched), and the next joiner finds no server. The serve arms its roster-0 hold
     * supplier on this while respawn is on; a host's {@code respawnPolicy=disabled} keeps the
     * all-dead end.
     */
    public static boolean holdsEmptyField(String shape) {
        return isPvpShape(shape);
    }

    /**
     * Is {@code variantId} one of the mint's STATION rows? Game-free (no
     * engine read) so the serve loop can require the station hooks + the module bound term at
     * serve entry, exactly the way {@code WaveSchedule.forShape} keys the wave lane off the
     * shape string. Widened to the 3-family membership — the semantics stay
     * "is this row THE station", family-safe (exactly-one-station is asserted at the deploy
     * census, {@code RealEngineDemoRoster}). Null/blank is simply not a station.
     */
    public static boolean isStationVariantId(String variantId) {
        return STATION_LOWTECH_VARIANT_ID.equals(variantId)
                || STATION_MIDLINE_VARIANT_ID.equals(variantId)
                || STATION_HIGHTECH_VARIANT_ID.equals(variantId);
    }

    /**
     * W2b module seats: one claimable fortress
     * WEAPON-module seat — the {@code /module} census row. {@code token} is the server-authored
     * chat token (arbiter allowlist, never wire-derived); {@code slotId} is the engine slot id
     * VERBATIM from the variant file (interior space included — {@code "WS 009"}, never
     * {@code WS009}); {@code moduleVariantId} is the FULL module variant id the engine's
     * {@code getStationModules()} map carries ({@code _Standard} suffix included).
     */
    public record ModuleSeat(String token, String slotId, String moduleVariantId) {
    }

    /**
     * The claimable-module census for {@code stationVariantId}, slot-id-sorted (= the
     * {@code /module} listing order and the {@code stationModuleVariantIds} read order).
     * GAME-FREE authored knowledge, per STATION FAMILY —
     * ground-truth-verified against the vanilla
     * {@code data/variants/stations/station3[_midline|_hightech]_Standard.variant} files
     * (each family read first-hand). A module is
     * claimable iff its OWN variant file carries non-empty {@code weaponGroups} (the
     * "weapon modules" rule): armour/decor/blocker/strut modules carry
     * zero weapons in every family, the HIGHTECH shield modules (WS 000/001) are zero-weapon
     * too and stay unclaimable, and the vanilla MIDLINE citadel rows are COMMENTED OUT (never
     * fielded — 23 active modules; hightech fields 12, lowtech 24). Identity is by SLOT,
     * never by variant id (lowtech WS 012/014 and hightech WS 003/004 each share one module
     * variant), which is why the census keys mounts on {@code slotId}. Callers thread the
     * DEPLOYED station variant id (the descriptor's station roster row); an unknown id THROWS
     * (reject-never-clamp).
     */
    public static List<ModuleSeat> moduleSeatCensus(String stationVariantId) {
        return switch (stationVariantId == null ? "" : stationVariantId) {
            case STATION_LOWTECH_VARIANT_ID -> LOWTECH_MODULE_SEAT_CENSUS;
            case STATION_MIDLINE_VARIANT_ID -> MIDLINE_MODULE_SEAT_CENSUS;
            case STATION_HIGHTECH_VARIANT_ID -> HIGHTECH_MODULE_SEAT_CENSUS;
            default -> throw new IllegalArgumentException("no module-seat census for variant"
                    + " id '" + stationVariantId + "' (expected a station family id — "
                    + STATION_FAMILIES + ")");
        };
    }

    private static final List<ModuleSeat> LOWTECH_MODULE_SEAT_CENSUS = List.of(
            new ModuleSeat("large1", "WS 009", "module_large1_lowtech_Standard"),
            new ModuleSeat("large2a", "WS 010", "module_large2_lowtech_Standard"),
            new ModuleSeat("large2b", "WS 011", "module_large2_lowtech_Standard"),
            new ModuleSeat("bastion1", "WS 012", "module_bastion1_lowtech_Standard"),
            new ModuleSeat("citadel", "WS 013", "module_citadel1_lowtech_Standard"),
            new ModuleSeat("bastion2", "WS 014", "module_bastion1_lowtech_Standard"));

    /** The 4 midline WEAPON modules of its 23 fielded. */
    private static final List<ModuleSeat> MIDLINE_MODULE_SEAT_CENSUS = List.of(
            new ModuleSeat("support", "WS 002", "module_midline_support_Standard"),
            new ModuleSeat("bastion1", "WS 008", "module_midline_bastion_Standard1"),
            new ModuleSeat("bastion2", "WS 010", "module_midline_bastion_Standard2"),
            new ModuleSeat("broadside", "WS 023", "module_midline_broadside_Standard"));

    /** The 4 hightech WEAPON modules of its 12 fielded. */
    private static final List<ModuleSeat> HIGHTECH_MODULE_SEAT_CENSUS = List.of(
            new ModuleSeat("hangar", "WS 002", "module_hightech_hangar_Standard"),
            new ModuleSeat("mine1", "WS 003", "module_hightech_attack_minelayer_Standard"),
            new ModuleSeat("mine2", "WS 004", "module_hightech_attack_minelayer_Standard"),
            new ModuleSeat("citadel", "WS 007", "module_hightech_citadel_Standard"));

    /** The census tokens in listing order — the {@code SeatRebindArbiter} ctor census. Same
     *  per-family law + unknown-id THROW as {@link #moduleSeatCensus(String)}. */
    public static List<String> moduleSeatTokens(String stationVariantId) {
        List<ModuleSeat> census = moduleSeatCensus(stationVariantId);
        List<String> tokens = new ArrayList<>(census.size());
        for (ModuleSeat seat : census) {
            tokens.add(seat.token());
        }
        return List.copyOf(tokens);
    }

    /**
     * The certified DEFAULT arena half-extent (both axes) — the resolve fallback for the
     * {@code map.half} knob and the value contract mints use, so tests stay env-independent
     * (the knob row in {@code cc-server.properties} is the AUTHORING source;
     * the ops map-size apply rewrites knob + live descriptor set together, and
     * {@code MatchDescriptorAuthor} default-resolves the row — the SERVE then derives its
     * bounds from the descriptor, {@link #serveMapHalf}). This constant only
     * defines what "unconfigured" means. History: 6000→12000, then a retune to 9000.
     *
     */
    public static final float CERTIFIED_MAP_HALF = 9000f;

    /**
     * The certified DEFAULT deploy CR (the vanilla-mission default the scout gate mirrors) —
     * the resolve fallback for the {@code match.deployCr} knob and the value contract mints
     * use, so tests stay env-independent. The LIVE value is the knob row in
     * {@code cc-server.properties} (1.0 for ALL ships;
     * kept a knob on purpose — never hardcode the preference), which
     * {@code MatchDescriptorAuthor} default-resolves at mint; the serve then reads
     * {@code rules.deployCr} from the decoded descriptor. This constant only defines what
     * "unconfigured" means.
     */
    public static final float CERTIFIED_DEPLOY_CR = 0.7f;

    /**
     * The map-size SINGLE SOURCE at serve (absorbs the
     * lockstep guard): the descriptor is the CLIENT's initMap truth
     * ({@code MatchMissionBuilder.build} ends in {@code initMap(±halfWidth, ±halfHeight)}), so
     * the serve loop derives its engine bounds FROM the decoded descriptor instead of a second
     * {@code map.half} knob read — the split-brain class (clients build a ±descriptor map,
     * ships fly at ±knob bounds; the spent watch) is structurally dead, and the arm-time
     * {@code CC_PREDICT_MAP_HALF} env ferry dies with it. The {@code map.half} props row stays
     * the AUTHORING source ({@code MatchDescriptorAuthor} / the ops map-size apply, which
     * rewrites row + live descriptors together). Non-square refuses fail-closed: the engine
     * widen ({@code applyCombatMapBounds}) takes ONE half-extent.
     */
    public static float serveMapHalf(MatchDescriptor descriptor) {
        return serveMapHalf(descriptor.halfWidth(), descriptor.halfHeight());
    }

    /** The {@link #serveMapHalf(MatchDescriptor)} law on raw halves (contract-testable — the
     *  validating constructor already refuses non-finite/non-positive, but not non-square). */
    public static float serveMapHalf(float halfWidth, float halfHeight) {
        if (halfWidth != halfHeight) {
            throw new IllegalArgumentException("match descriptor map.halfWidth/halfHeight = "
                    + halfWidth + "/" + halfHeight + " — non-square arenas are unsupported: the"
                    + " serve derives ONE engine half-extent from the descriptor (the"
                    + " single source); re-author the descriptor file (MatchDescriptorAuthor /"
                    + " the ops map-size apply)");
        }
        return halfWidth;
    }

    static final String PLAYER_SIDE_NAME = "ISS";
    static final String PLAYER_SIDE_TAGLINE = "Multiplayer test - Bastion Mode";
    static final String ENEMY_SIDE_NAME = "HSS";
    static final String ENEMY_SIDE_TAGLINE = "Sparring partner";
    /** The PvP sides' taglines: the team a side fields, by side index (0 = South, 1 = North). */
    public static final String PVP_SOUTH_TAGLINE = "South team";
    public static final String PVP_NORTH_TAGLINE = "North team";

    private MatchDescriptors() {
    }

    /**
     * Mint from a shape spec string — the {@code cc.match.shape} knob value: {@code 1v1},
     * {@code bots:N} / {@code bots:A:B}, {@code seats:K}, {@code teams:S:N},
     * {@code gauntlet:N} / {@code gauntlet:N:skipper}, {@code bastion:A:B[:phase|:aurora]},
     * {@code bastion:duel[:aurora]}, {@code bastion:wave[:rig]}, {@code bastion:beam[:rig]},
     * {@code bastion:hyperion[:rig]}, {@code bastion:carrier[:rig]},
     * {@code bastion:strike[:rig]}, {@code bastion:fortress[:rig]}, or {@code bastion:ram}.
     * Unknown specs THROW (fail-closed knob discipline).
     */
    public static MatchDescriptor mint(String shapeSpec) {
        if (shapeSpec == null || shapeSpec.trim().isEmpty()) {
            throw new IllegalArgumentException("shape spec must not be blank");
        }
        String spec = shapeSpec.trim();
        if ("1v1".equals(spec)) {
            return oneVsOne();
        }
        if (spec.startsWith("bots:")) {
            String[] parts = spec.substring("bots:".length()).split(":", -1);
            if (parts.length == 1) {
                return bots(parseIntArg(spec, parts[0]));
            }
            if (parts.length == 2) {
                return bots(parseIntArg(spec, parts[0]), parseIntArg(spec, parts[1]));
            }
            throw new IllegalArgumentException(
                    "shape spec '" + spec + "' has too many args (expected bots:N or bots:A:B)");
        }
        if (spec.startsWith("seats:")) {
            return seats(parseIntArg(spec, spec.substring("seats:".length())));
        }
        if (spec.startsWith("teams:")) {
            String[] parts = spec.substring("teams:".length()).split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "shape spec '" + spec + "' needs exactly two args (expected teams:S:N)");
            }
            return teams(parseIntArg(spec, parts[0]), parseIntArg(spec, parts[1]));
        }
        if (spec.startsWith("gauntlet:")) {
            String[] parts = spec.substring("gauntlet:".length()).split(":", -1);
            if (parts.length == 1) {
                return gauntlet(parseIntArg(spec, parts[0]));
            }
            if (parts.length == 2 && "skipper".equals(parts[1])) {
                return gauntletSkipper(parseIntArg(spec, parts[0]));
            }
            throw new IllegalArgumentException("shape spec '" + spec
                    + "' has an unknown gauntlet arg (expected gauntlet:N or gauntlet:N:skipper)");
        }
        if (spec.startsWith("bastion:")) {
            String[] parts = spec.substring("bastion:".length()).split(":", -1);
            if (parts.length == 1 && "duel".equals(parts[0])) {
                return bastionDuel();
            }
            if (parts.length >= 1 && "wave".equals(parts[0])) {
                // Explicit wave branches, own tokens, fail-closed on anything
                // else (the DD3-1265 never-token-soup law).
                if (parts.length == 1) {
                    return bastionWave();
                }
                if (parts.length == 2 && "rig".equals(parts[1])) {
                    return bastionWaveRig();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown wave arg (expected bastion:wave or bastion:wave:rig)");
            }
            if (parts.length >= 1 && "beam".equals(parts[0])) {
                // Explicit beam branches, own tokens, fail-closed on anything
                // else (the DD3-1265 never-token-soup law; wave-branch precedent).
                if (parts.length == 1) {
                    return bastionBeam();
                }
                if (parts.length == 2 && "rig".equals(parts[1])) {
                    return bastionBeamRig();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown beam arg (expected bastion:beam or bastion:beam:rig)");
            }
            if (parts.length >= 1 && "hyperion".equals(parts[0])) {
                // Explicit hyperion branches, own tokens, fail-closed on
                // anything else (the DD3-1265 never-token-soup law; beam-branch precedent).
                if (parts.length == 1) {
                    return bastionHyperion();
                }
                if (parts.length == 2 && "rig".equals(parts[1])) {
                    return bastionHyperionRig();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown hyperion arg (expected bastion:hyperion or bastion:hyperion:rig)");
            }
            if (parts.length >= 1 && "carrier".equals(parts[0])) {
                // Explicit carrier branches, own tokens, fail-closed on
                // anything else (the DD3-1265 never-token-soup law; hyperion-branch precedent).
                if (parts.length == 1) {
                    return bastionCarrier();
                }
                if (parts.length == 2 && "rig".equals(parts[1])) {
                    return bastionCarrierRig();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown carrier arg (expected bastion:carrier or bastion:carrier:rig)");
            }
            if (parts.length >= 1 && "strike".equals(parts[0])) {
                // Explicit strike branches, own tokens, fail-closed on
                // anything else (the DD3-1265 never-token-soup law; carrier-branch precedent).
                if (parts.length == 1) {
                    return bastionStrike();
                }
                if (parts.length == 2 && "rig".equals(parts[1])) {
                    return bastionStrikeRig();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown strike arg (expected bastion:strike or bastion:strike:rig)");
            }
            if (parts.length >= 1 && "fortress".equals(parts[0])) {
                // Explicit fortress branches, own tokens, fail-closed on
                // anything else (the DD3-1265 never-token-soup law; carrier-branch precedent).
                if (parts.length == 1) {
                    return bastionFortress();
                }
                if (parts.length == 2 && "rig".equals(parts[1])) {
                    return bastionFortressRig();
                }
                if (parts.length == 2 && "pvp".equals(parts[1])) {
                    return bastionFortressPvp();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown fortress arg (expected bastion:fortress,"
                        + " bastion:fortress:rig or bastion:fortress:pvp)");
            }
            if (parts.length >= 1 && "fortress_ab".equals(parts[0])) {
                // Explicit fortress_ab branch, own tokens, fail-closed on
                // anything else (mirv-branch precedent). No rig twin: the AB shape exists for
                // the same-ship ping A/B watch by a human player; unattended controls ride the fortress rig.
                if (parts.length == 1) {
                    return bastionFortressAb();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown fortress_ab arg (expected bastion:fortress_ab;"
                        + " no rig twin)");
            }
            if (parts.length >= 1 && "ram".equals(parts[0])) {
                // Explicit ram branch, own tokens, fail-closed on anything
                // else (anchor-branch precedent). No rig twin: the shape exists for the
                // offline vanilla ram-collision baseline (RealEngineRamProbeMain); nothing
                // player-facing rides it.
                if (parts.length == 1) {
                    return bastionRam();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown ram arg (expected bastion:ram; no rig twin)");
            }
            if (parts.length >= 1 && "anchor".equals(parts[0])) {
                // Explicit anchor branch, own tokens, fail-closed on
                // anything else (mirv-branch precedent). No rig twin: the shape exists for the
                // offline emergency-dive stall repro (RealEngineAnchorDiveProbeMain); nothing
                // player-facing rides it.
                if (parts.length == 1) {
                    return bastionAnchor();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown anchor arg (expected bastion:anchor; no rig twin)");
            }
            if (parts.length >= 1 && "mirv".equals(parts[0])) {
                // Explicit mirv branch, own tokens, fail-closed on anything
                // else (strike-branch precedent). No rig twin: the shape exists for an
                // eyeball acceptance; unwatched baselines ride the strike rigs.
                if (parts.length == 1) {
                    return bastionMirv();
                }
                throw new IllegalArgumentException("shape spec '" + spec
                        + "' has an unknown mirv arg (expected bastion:mirv; no rig twin)");
            }
            if (parts.length == 2 && "duel".equals(parts[0]) && "aurora".equals(parts[1])) {
                return bastionDuelAurora();
            }
            if (parts.length == 2) {
                return bastion(parseIntArg(spec, parts[0]), parseIntArg(spec, parts[1]));
            }
            if (parts.length == 3 && "phase".equals(parts[2])) {
                return bastionPhase(parseIntArg(spec, parts[0]), parseIntArg(spec, parts[1]));
            }
            if (parts.length == 3 && "aurora".equals(parts[2])) {
                // Grammar note: parts[2] overloads the suffix slot (:phase
                // precedent); any other token stays fail-closed, and a future phase+aurora
                // combo mints its own 4-part branch — never a token soup here.
                return bastionAurora(parseIntArg(spec, parts[0]), parseIntArg(spec, parts[1]));
            }
            if (parts.length == 3 && "anubis".equals(parts[2])) {
                return bastionAnubis(parseIntArg(spec, parts[0]), parseIntArg(spec, parts[1]));
            }
            throw new IllegalArgumentException("shape spec '" + spec
                    + "' has an unknown bastion arg (expected bastion:A:B, bastion:A:B:phase,"
                    + " bastion:A:B:aurora, bastion:A:B:anubis, bastion:duel, bastion:duel:aurora, bastion:wave[:rig], bastion:beam[:rig], bastion:hyperion[:rig], bastion:carrier[:rig], bastion:strike[:rig], bastion:fortress[:rig|:pvp], bastion:fortress_ab, bastion:ram, bastion:anchor or bastion:mirv)");
        }
        throw new IllegalArgumentException(
                "unknown shape spec '" + spec
                        + "' (expected 1v1 | bots:N | bots:A:B | seats:K | teams:S:N | gauntlet:N[:skipper] | bastion:A:B[:phase|:aurora|:anubis] | bastion:duel[:aurora] | bastion:wave[:rig] | bastion:beam[:rig] | bastion:hyperion[:rig] | bastion:carrier[:rig] | bastion:strike[:rig] | bastion:fortress[:rig|:pvp] | bastion:fortress_ab | bastion:ram | bastion:anchor | bastion:mirv)");
    }

    /**
     * {@link #mint(String)} at an explicit arena half-extent — the authoring
     * path for a configured {@code map.half} ({@code MatchDescriptorAuthor} default-resolve +
     * the ops map-size apply). Re-halves the freshly minted descriptor through the validating
     * constructor (finite &gt; 0 enforced there); the per-shape factories stay on
     * {@link #CERTIFIED_MAP_HALF} so contract mints are env-independent. Fresh identity per
     * call (match.id + seat tokens), exactly like {@link #mint(String)}.
     */
    public static MatchDescriptor mint(String shapeSpec, float mapHalf) {
        return mint(shapeSpec, mapHalf, CERTIFIED_DEPLOY_CR);
    }

    /**
     * The deploy-CR knob leg, same law as the map-half overload above:
     * {@link #mint(String, float)} at an explicit deploy CR — the authoring path for a
     * configured {@code match.deployCr} ({@code MatchDescriptorAuthor} default-resolve; the
     * ops map-size re-author threads each descriptor's CURRENT value through here so a
     * map-size apply never silently resets CR to certified). Range (0,1] enforced by the
     * validating constructor (reject-never-clamp); the per-shape factories stay on
     * {@link #CERTIFIED_DEPLOY_CR} so contract mints are env-independent. Fresh identity per
     * call (match.id + seat tokens), exactly like {@link #mint(String)}.
     */
    public static MatchDescriptor mint(String shapeSpec, float mapHalf, float deployCr) {
        return atHalfAndCr(mint(shapeSpec), mapHalf, deployCr);
    }

    /** {@code base} re-halved and re-CR'd through the validating constructor, identity kept (shared
     *  by the spec mint and the players mint). */
    private static MatchDescriptor atHalfAndCr(MatchDescriptor base, float mapHalf, float deployCr) {
        return new MatchDescriptor(MatchDescriptor.VERSION, base.matchId(), base.shape(),
                mapHalf, mapHalf, deployCr, base.endCondition(),
                base.sides(), base.roster(), base.seats());
    }

    /**
     * The deploy-CR thread law, third leg:
     * {@link #mint(String, float, float)} at an explicit STATION variant — the authoring path
     * for a family-configured fortress descriptor (the ops {@code applyStationVariant}; the
     * map-size apply threads each descriptor's CURRENT station id through here so a map-size
     * apply never silently resets the family to default). Replaces the station roster row's
     * variant id IN PLACE — position preserved, so the station stays LAST by the id-contract
     * law. Fail-closed both ways: a non-station {@code stationVariantId} throws
     * (reject-never-clamp), and a shape that does not field exactly ONE station row throws
     * (the station family is meaningless there). Fresh identity per call (match.id + seat
     * tokens), exactly like {@link #mint(String)}.
     */
    public static MatchDescriptor mint(String shapeSpec, float mapHalf, float deployCr,
            String stationVariantId) {
        requireStationVariantId(stationVariantId);
        return withStationRow(shapeSpec, mint(shapeSpec, mapHalf, deployCr), stationVariantId);
    }

    private static void requireStationVariantId(String stationVariantId) {
        if (!isStationVariantId(stationVariantId)) {
            throw new IllegalArgumentException("'" + stationVariantId + "' is not a station"
                    + " variant id (families: " + STATION_FAMILIES + ")");
        }
    }

    /** The station leg on an already-minted {@code base}: the ONE station row's variant swapped
     *  in place (position kept, so it stays LAST); a shape without exactly one station row throws.
     * */
    private static MatchDescriptor withStationRow(String shapeSpec, MatchDescriptor base,
            String stationVariantId) {
        List<MatchDescriptor.RosterRow> roster = new ArrayList<>(base.roster().size());
        int stationRows = 0;
        for (MatchDescriptor.RosterRow row : base.roster()) {
            if (isStationVariantId(row.variantId())) {
                stationRows++;
                roster.add(new MatchDescriptor.RosterRow(
                        row.side(), stationVariantId, row.count(), row.pilot()));
            } else {
                roster.add(row);
            }
        }
        if (stationRows != 1) {
            throw new IllegalArgumentException("shape spec '" + shapeSpec + "' fields "
                    + stationRows + " station roster rows - a station variant applies only to"
                    + " a shape with exactly ONE station row");
        }
        return new MatchDescriptor(MatchDescriptor.VERSION, base.matchId(), base.shape(),
                base.halfWidth(), base.halfHeight(), base.deployCr(), base.endCondition(),
                base.sides(), roster, base.seats());
    }

    /**
     * The player-count range {@code {min, max}} a shape spec accepts at {@link #mintPlayers}. WHY
     * per family: {@code seats:K}, {@code 1v1}, {@code gauntlet:N[:skipper]} and the two-sided
     * fixed duels need a player on each side (or the owner hull plus one joiner), so 2; a
     * {@code teams:S:N} mint is valid down to ONE claimable seat (AI fills the rest), so 1; a
     * fixed one-sided multi-seat {@code bastion:*} regrows from 1; every shape whose stock mint
     * fields exactly one seat ({@code bots:*}, the single-seat {@code bastion:*} arms and rigs)
     * takes exactly 1. The ceiling is {@link #MAX_PLAYERS}. Validates the spec by minting it once
     * (an unknown spec THROWS, the {@link #mint(String)} fail-closed law).
     *
     *
     */
    public static int[] playersRange(String shapeSpec) {
        String spec = requireSpec(shapeSpec);
        return playersRange(spec, mint(spec));
    }

    private static int[] playersRange(String spec, MatchDescriptor stock) {
        if (spec.startsWith("teams:")) {
            return new int[] {1, MAX_PLAYERS};
        }
        if ("1v1".equals(spec) || spec.startsWith("seats:") || spec.startsWith("gauntlet:")) {
            return new int[] {2, MAX_PLAYERS};
        }
        if (stock.seats().size() == 1) {
            return new int[] {1, 1};
        }
        if (spec.startsWith("bastion:")) {
            return new int[] {seatRowSides(stock.roster()) == 2 ? 2 : 1, MAX_PLAYERS};
        }
        throw new IllegalArgumentException("shape spec '" + spec + "' has no players rule");
    }

    /**
     * Mint {@code shapeSpec} for {@code players} seats, at the certified map half and deploy CR.
     * The count rides the SPEC for the counted families: {@code seats:*} = {@code seats(P)};
     * {@code teams:S:N} = {@code teams(P, max(N, ceil(P/2)))} (N is a FLOOR of hulls per side,
     * players replace AI); {@code gauntlet:N[:skipper]} = P - 1 joiners; {@code 1v1} at P == 2 is
     * {@link #oneVsOne()}, else {@code seats(P)} (the same certified hull, a relabel). A fixed
     * multi-seat {@code bastion:*} keeps its shape STRING untouched (the serve keys fortress and
     * wave behaviour on the exact string) and carries the count as its SEAT LIST, the stock SEAT
     * rows regrown by the hull rule (see {@link #regrowSeatRows}). A one-seat shape takes P == 1
     * only. P outside {@link #playersRange} THROWS IllegalArgumentException. Every rewritten spec
     * stays within 20 chars (the skipper gauntlet at 254 joiners is the longest, exactly 20), the
     * match-id budget the fingerprint composite's 128-char cap leaves.
     *
     *
     */
    public static MatchDescriptor mintPlayers(String shapeSpec, int players) {
        String spec = requireSpec(shapeSpec);
        MatchDescriptor stock = mint(spec);
        int[] range = playersRange(spec, stock);
        if (players < range[0] || players > range[1]) {
            throw new IllegalArgumentException("shape spec '" + spec + "' takes " + range[0]
                    + " to " + range[1] + " players, got: " + players);
        }
        if (spec.startsWith("seats:")) {
            return seats(players);
        }
        if ("1v1".equals(spec)) {
            return players == 2 ? stock : seats(players);
        }
        if (spec.startsWith("teams:")) {
            int hullsFloor = parseIntArg(spec, spec.substring("teams:".length()).split(":", -1)[1]);
            return teams(players, Math.max(hullsFloor, (players + 1) / 2));
        }
        if (spec.startsWith("gauntlet:")) {
            return spec.endsWith(":skipper") ? gauntletSkipper(players - 1) : gauntlet(players - 1);
        }
        if (stock.seats().size() == 1) {
            return stock;
        }
        return regrowSeatRows(stock, players);
    }

    /** {@link #mintPlayers(String, int)} at an explicit map half and deploy CR (the
     *  {@link #mint(String, float, float)} leg). */
    public static MatchDescriptor mintPlayers(String shapeSpec, int players, float mapHalf,
            float deployCr) {
        return atHalfAndCr(mintPlayers(shapeSpec, players), mapHalf, deployCr);
    }

    /** {@link #mintPlayers(String, int, float, float)} with the station leg (the
     *  {@link #mint(String, float, float, String)} law: a non-station id or a shape without
     *  exactly one station row throws). */
    public static MatchDescriptor mintPlayers(String shapeSpec, int players, float mapHalf,
            float deployCr, String stationVariantId) {
        requireStationVariantId(stationVariantId);
        return withStationRow(shapeSpec, mintPlayers(shapeSpec, players, mapHalf, deployCr),
                stationVariantId);
    }

    /**
     * THE re-mint: a fresh identity (new match.id + seat tokens) of {@code decoded}'s shape with
     * every authored leg threaded so no re-mint silently resets one: map half, deploy CR, the
     * station variant ({@code null} = the shape has no station row), the backdrop word, and the
     * PLAYER COUNT = {@code decoded}'s own seat count (identity on every stock file; a file
     * written at 40 players re-mints at 40, never back to the spec's stock count).
     *
     *
     */
    public static MatchDescriptor remint(MatchDescriptor decoded, float mapHalf, float deployCr,
            String stationVariantIdOrNull, String terrain) {
        if (decoded == null) {
            throw new IllegalArgumentException("decoded descriptor must not be null");
        }
        return remint(decoded, decoded.seats().size(), mapHalf, deployCr, stationVariantIdOrNull,
                terrain);
    }

    /** {@link #remint(MatchDescriptor, float, float, String, String)} at a NEW player count (the
     *  New-shape leg: a base re-minted at the host's Players value). */
    public static MatchDescriptor remint(MatchDescriptor decoded, int players, float mapHalf,
            float deployCr, String stationVariantIdOrNull, String terrain) {
        if (decoded == null) {
            throw new IllegalArgumentException("decoded descriptor must not be null");
        }
        return withTerrain(stationVariantIdOrNull == null
                ? mintPlayers(decoded.shape(), players, mapHalf, deployCr)
                : mintPlayers(decoded.shape(), players, mapHalf, deployCr, stationVariantIdOrNull),
                terrain);
    }

    /**
     * The SHAPE-SWITCH re-mint, the {@link #remint} twin for the PvP toggle: a fresh identity (new
     * match.id + seat tokens) of {@code targetSpec} at the given players, map half, deploy CR and
     * backdrop word. {@code remint} always keeps {@code decoded}'s own shape, so it can never turn a
     * fortress into PvP or back; this one can, between exactly {@link #FORTRESS_SHAPE} and
     * {@link #PVP_SHAPE} (either way, or the same shape), and THROWS for any other source or target
     * shape (the console names those before it calls here). Fortress to PvP drops the station
     * (PvP fields none: a non-null {@code stationVariantIdOrNull} THROWS); PvP to the fortress
     * takes {@code stationVariantIdOrNull}, null = the stock (default) station. Every number comes
     * from the arguments, never from {@code decoded}: the caller reads the file's values and
     * passes them on.
     */
    public static MatchDescriptor remintAs(MatchDescriptor decoded, String targetSpec, int players,
            float mapHalf, float deployCr, String stationVariantIdOrNull, String terrain) {
        if (decoded == null) {
            throw new IllegalArgumentException("decoded descriptor must not be null");
        }
        String target = requireSpec(targetSpec);
        if (!isFortressOrPvp(decoded.shape()) || !isFortressOrPvp(target)) {
            throw new IllegalArgumentException("remintAs switches between " + FORTRESS_SHAPE
                    + " and " + PVP_SHAPE + " only, got '" + decoded.shape() + "' -> '" + target
                    + "'");
        }
        if (isPvpShape(target) && stationVariantIdOrNull != null) {
            throw new IllegalArgumentException("PvP fields no station: " + PVP_SHAPE
                    + " takes no station variant");
        }
        return withTerrain(stationVariantIdOrNull == null
                ? mintPlayers(target, players, mapHalf, deployCr)
                : mintPlayers(target, players, mapHalf, deployCr, stationVariantIdOrNull),
                terrain);
    }

    private static boolean isFortressOrPvp(String shape) {
        return FORTRESS_SHAPE.equals(shape) || PVP_SHAPE.equals(shape);
    }

    /**
     * THE HULL RULE for a fixed multi-seat {@code bastion:*} at {@code players} seats: the stock
     * roster with its SEAT rows' counts regrown, AI rows verbatim (so the station stays LAST),
     * seat ids 1..P ascending with ship id == seat id. Seats split ceil/floor across the sides
     * when the stock seats both, else all on the seated side. Within a side the shuttlepod rows
     * keep their stock count and are TRIMMED FIRST when P shrinks; the other SEAT rows grow
     * round-robin in declaration order (fortress@P, P &gt;= 3: 1 + ceil((P-3)/2) Grendels,
     * 1 + floor((P-3)/2) Auroras, 1 pod; P = 2 = Grendel + Aurora; P = 1 = the Grendel). A row
     * regrown to 0 is dropped; rows stay GROUPED (one row per hull with a count, which keeps the
     * share text small). WHY ship id == seat id is LOAD-BEARING: the client interns wire ids in
     * flattened roster-declaration order and the deploy layer position-asserts every seat's hull
     * there, so every SEAT row must precede every AI row (a stock roster breaking that THROWS).
     *
     *
     */
    private static MatchDescriptor regrowSeatRows(MatchDescriptor stock, int players) {
        List<MatchDescriptor.RosterRow> rows = stock.roster();
        boolean seenAi = false;
        for (MatchDescriptor.RosterRow row : rows) {
            if (row.pilot() != MatchDescriptor.PilotKind.SEAT) {
                seenAi = true;
            } else if (seenAi) {
                throw new IllegalArgumentException("shape '" + stock.shape() + "' declares a SEAT"
                        + " row after an AI row - its seat hulls would not hold ship id == seat id");
            }
        }
        int[] perSide = new int[2];
        if (seatRowSides(rows) == 2) {
            perSide[0] = (players + 1) / 2;
            perSide[1] = players - perSide[0];
        } else {
            perSide[firstSeatRowSide(rows)] = players;
        }
        int[] counts = new int[rows.size()];
        for (int side = 0; side < 2; side++) {
            List<Integer> hulls = new ArrayList<>();
            List<Integer> pods = new ArrayList<>();
            int stockPods = 0;
            for (int i = 0; i < rows.size(); i++) {
                MatchDescriptor.RosterRow row = rows.get(i);
                if (row.pilot() != MatchDescriptor.PilotKind.SEAT || row.side() != side) {
                    continue;
                }
                if (BASTION_SHUTTLEPOD_VARIANT_ID.equals(row.variantId())) {
                    pods.add(i);
                    stockPods += row.count();
                } else {
                    hulls.add(i);
                }
            }
            int want = perSide[side];
            if (hulls.isEmpty() && want > stockPods) {
                throw new IllegalArgumentException("shape '" + stock.shape() + "' side " + side
                        + " seats only shuttlepods (" + stockPods + ") - it cannot grow to "
                        + want + " seats");
            }
            int podSeats = hulls.isEmpty() ? want : Math.min(stockPods, Math.max(0, want - hulls.size()));
            int rest = want - podSeats;
            for (int k = 0; k < hulls.size(); k++) {
                counts[hulls.get(k)] = rest / hulls.size() + (k < rest % hulls.size() ? 1 : 0);
            }
            for (int i : pods) {
                counts[i] = Math.min(rows.get(i).count(), podSeats);
                podSeats -= counts[i];
            }
        }
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<>(rows.size());
        List<MatchDescriptor.Seat> seats = new ArrayList<>(players);
        int ship = 0;
        for (int i = 0; i < rows.size(); i++) {
            MatchDescriptor.RosterRow row = rows.get(i);
            if (row.pilot() != MatchDescriptor.PilotKind.SEAT) {
                roster.add(row);
                continue;
            }
            if (counts[i] == 0) {
                continue;
            }
            roster.add(new MatchDescriptor.RosterRow(row.side(), row.variantId(), counts[i],
                    MatchDescriptor.PilotKind.SEAT));
            for (int c = 0; c < counts[i]; c++) {
                ship++;
                seats.add(new MatchDescriptor.Seat(ship, SeatRole.PILOT, row.side(), ship,
                        newToken(rng)));
            }
        }
        return descriptor(stock.shape(), rng, roster, seats);
    }

    /** How many sides (0, 1 or 2) carry a SEAT roster row. */
    private static int seatRowSides(List<MatchDescriptor.RosterRow> rows) {
        boolean[] seated = new boolean[2];
        for (MatchDescriptor.RosterRow row : rows) {
            if (row.pilot() == MatchDescriptor.PilotKind.SEAT) {
                seated[row.side()] = true;
            }
        }
        return (seated[0] ? 1 : 0) + (seated[1] ? 1 : 0);
    }

    private static int firstSeatRowSide(List<MatchDescriptor.RosterRow> rows) {
        for (MatchDescriptor.RosterRow row : rows) {
            if (row.pilot() == MatchDescriptor.PilotKind.SEAT) {
                return row.side();
            }
        }
        throw new IllegalArgumentException("a roster without a SEAT row has no seats to regrow");
    }

    /**
     * THE CLAIM ORDER of {@code d}'s seats (server-only: the password ledger's first-free pick
     * walks it). Sides in the order their first SEAT row is declared; within a side, round-robin
     * across that side's SEAT roster rows (hull groups) in declaration order, each group's seats
     * in flattened-id order. WHY: a regrown fortress groups its hulls (Grendels 1..g, Auroras,
     * then the pod), and plain ascending ids would hand the first joiners every Grendel before
     * any Aurora; round-robin fills Grendel, Aurora, pod, Grendel, Aurora, ... as the stock
     * three-seat fortress always did. IDENTICAL to ascending seat id on every stock shape (one
     * SEAT row per side, or rows of count 1). A descriptor whose seats do not sit one-per-slot in
     * SEAT rows of their own side (a hand-made file) falls back to ascending seat id.
     *
     *
     */
    public static List<MatchDescriptor.Seat> claimOrder(MatchDescriptor d) {
        List<MatchDescriptor.Seat> ascending = new ArrayList<>(d.seats());
        ascending.sort((a, b) -> Integer.compare(a.seatId(), b.seatId()));
        List<MatchDescriptor.RosterRow> rows = d.roster();
        long[] firstShip = new long[rows.size()];
        long next = 1L;
        for (int i = 0; i < rows.size(); i++) {
            firstShip[i] = next;
            next += rows.get(i).count();
        }
        List<List<MatchDescriptor.Seat>> groups = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            groups.add(new ArrayList<>());
        }
        List<MatchDescriptor.Seat> byShip = new ArrayList<>(d.seats());
        byShip.sort((a, b) -> Long.compare(a.shipId(), b.shipId()));
        for (MatchDescriptor.Seat seat : byShip) {
            int row = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (seat.shipId() >= firstShip[i] && seat.shipId() < firstShip[i] + rows.get(i).count()) {
                    row = i;
                    break;
                }
            }
            if (row < 0 || rows.get(row).pilot() != MatchDescriptor.PilotKind.SEAT
                    || rows.get(row).side() != seat.side()) {
                return ascending;
            }
            groups.get(row).add(seat);
        }
        List<Integer> sideOrder = new ArrayList<>(2);
        for (MatchDescriptor.RosterRow row : rows) {
            if (row.pilot() == MatchDescriptor.PilotKind.SEAT && !sideOrder.contains(row.side())) {
                sideOrder.add(row.side());
            }
        }
        List<MatchDescriptor.Seat> order = new ArrayList<>(ascending.size());
        for (int side : sideOrder) {
            boolean took = true;
            for (int pass = 0; took; pass++) {
                took = false;
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).side() == side && pass < groups.get(i).size()) {
                        order.add(groups.get(i).get(pass));
                        took = true;
                    }
                }
            }
        }
        return order;
    }

    /** The trimmed non-blank spec, or IllegalArgumentException (the {@link #mint(String)} text). */
    private static String requireSpec(String shapeSpec) {
        if (shapeSpec == null || shapeSpec.trim().isEmpty()) {
            throw new IllegalArgumentException("shape spec must not be blank");
        }
        return shapeSpec.trim();
    }

    /**
     * The thread law, fourth leg: {@code base} with the
     * build-time backdrop word set — IDENTITY PRESERVED (same match.id + seat tokens; the
     * caller decides whether a fresh identity is due: the ops apply re-mints first, then
     * threads the word, so ALL prior kits die there exactly as a map-size apply kills them).
     * Every other row copies through unchanged (shape, halves, deploy CR, station row, seats).
     * The word is validated by the descriptor constructor (exact {@link MatchDescriptor#TERRAIN_WORDS}
     * membership, reject-never-clamp). {@code none} = the pre-slice-D bytes.
     */
    public static MatchDescriptor withTerrain(MatchDescriptor base, String terrain) {
        if (base == null) {
            throw new IllegalArgumentException("base descriptor must not be null");
        }
        return new MatchDescriptor(base.version(), base.matchId(), base.shape(),
                base.halfWidth(), base.halfHeight(), base.deployCr(), base.endCondition(),
                base.sides(), base.roster(), base.seats(), terrain);
    }

    /**
     * The match.txt REDACTION law: the same
     * descriptor with every seat token replaced by its {@code REDACTED-seat<seatId>} placeholder
     * ({@link MatchDescriptor#REDACTED_TOKEN_PREFIX}); everything else (match.id, map, rules,
     * sides, roster, seat id/role/side/ship, backdrop word) copies through, so the fingerprint
     * composite (which reads only {@code match.id}) is identical for a redacted and a
     * keep-tokens copy. Placeholders keep the codec shape (single-line, non-blank, distinct per
     * seat), so redaction is idempotent. The ONE home since the release-parity package Apply
     * re-redacts a re-minted shape; the
     * packer CLI {@code tools.MatchTokenRedact} delegates here.
     *
     *
     */
    public static MatchDescriptor redact(MatchDescriptor descriptor) {
        List<MatchDescriptor.Seat> seats = new ArrayList<>();
        for (MatchDescriptor.Seat seat : descriptor.seats()) {
            seats.add(new MatchDescriptor.Seat(seat.seatId(), seat.role(), seat.side(),
                    seat.shipId(),
                    MatchDescriptor.REDACTED_TOKEN_PREFIX + "seat" + seat.seatId()));
        }
        return new MatchDescriptor(descriptor.version(), descriptor.matchId(),
                descriptor.shape(), descriptor.halfWidth(), descriptor.halfHeight(),
                descriptor.deployCr(), descriptor.endCondition(), descriptor.sides(),
                descriptor.roster(), seats,
                descriptor.terrain()); // A redacted kit keeps its backdrop word
    }

    /**
     * The release gate's STRICT redaction test over a descriptor file's lines: true iff at least
     * one line starts {@code seat.<digits>.token=} AND every such line's value, trimmed, starts
     * with {@link MatchDescriptor#REDACTED_TOKEN_PREFIX} (case-sensitive). WHY strict: a package
     * folder or a kit ships only the PUBLIC copy of a match, so a single live seat token beside
     * placeholders is a leaked credential; zero token rows is not a redacted file either.
     * The ONE home for the package-folder writer
     * and the kit maker; its pwsh twins are the kit-match parity library's redaction test
     * - {@code Test-CcKitMatchRedacted}: the same all-rows shape,
     * case-sensitive {@code -cnotmatch} over the value with its leading blanks allowed,
     * the twin row {@code T1-redaction-trim-twins} pins it - and the serve preflight verifier's two
     * redaction checks - case-sensitive
     * {@code -cmatch} over the trimmed line, but an ANY-row test. {@code ServeLauncher}'s
     * {@code descriptorIsRedacted} is deliberately a different question: ANY placeholder row tells
     * the launcher which mode the door runs in (a redacted shape needs the host's password), it is
     * not a gate on what may ship. A null list or null line reads as no row.
     *
     *
     */
    public static boolean isStrictlyRedacted(List<String> descriptorLines) {
        if (descriptorLines == null) {
            return false;
        }
        int tokenRows = 0;
        int redacted = 0;
        for (String line : descriptorLines) {
            int valueStart = line == null ? -1 : seatTokenValueStart(line);
            if (valueStart < 0) {
                continue;
            }
            tokenRows++;
            if (MatchDescriptor.isRedactedToken(line.substring(valueStart).trim())) {
                redacted++;
            }
        }
        return tokenRows > 0 && redacted == tokenRows;
    }

    /** The index after {@code seat.<one or more ASCII digits>.token=} at column 0, or -1. */
    private static int seatTokenValueStart(String line) {
        final String head = "seat.";
        final String tail = ".token=";
        if (!line.startsWith(head)) {
            return -1;
        }
        int i = head.length();
        while (i < line.length() && line.charAt(i) >= '0' && line.charAt(i) <= '9') {
            i++;
        }
        if (i == head.length() || !line.startsWith(tail, i)) {
            return -1;
        }
        return i + tail.length();
    }

    /** The certified 1v1: two sides, one seat-bound Mudskipper each, seats 1/2 PILOT. */
    public static MatchDescriptor oneVsOne() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 1, 2L, newToken(rng)));
        return descriptor("1v1", rng, roster, seats);
    }

    /** The D2 spectacle shape: seat 1's own ship vs {@code n} engine-AI ships on the enemy side. */
    public static MatchDescriptor bots(int n) {
        return bots(0, n);
    }

    /**
     * The two-sided D2/TD2 shape: side 0 = seat 1's own ship + {@code allies}
     * engine-AI allies, side 1 = {@code enemies} engine-AI ships — the owner piloting AMONG a
     * bot battle. {@code allies == 0} mints the exact {@code bots:N} descriptor (back-compat:
     * same shape string, same roster rows), so {@code bots:0:N} ≡ {@code bots:N}.
     */
    public static MatchDescriptor bots(int allies, int enemies) {
        if (allies < 0) {
            throw new IllegalArgumentException("bots:A:B needs A >= 0, got: " + allies);
        }
        if (enemies < 1) {
            throw new IllegalArgumentException("bots needs at least one enemy-side ship, got: " + enemies);
        }
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        if (allies > 0) {
            roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, allies, MatchDescriptor.PilotKind.AI));
        }
        roster.add(new MatchDescriptor.RosterRow(1, CERTIFIED_VARIANT_ID, enemies, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        String shape = allies == 0 ? "bots:" + enemies : "bots:" + allies + ":" + enemies;
        return descriptor(shape, rng, roster, seats);
    }

    /**
     * The D3 multi-human shape: {@code k} PILOT seats, one ship each, split ceil/floor across the
     * two sides — seats {@code 1..ceil(k/2)} (ship ids likewise) on side 0, the rest on side 1.
     */
    public static MatchDescriptor seats(int k) {
        if (k < 2) {
            throw new IllegalArgumentException("seats:K needs K >= 2 (both sides need a ship), got: " + k);
        }
        SecureRandom rng = new SecureRandom();
        int side0 = (k + 1) / 2;
        int side1 = k - side0;
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, side0, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, CERTIFIED_VARIANT_ID, side1, MatchDescriptor.PilotKind.SEAT));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        for (int i = 1; i <= k; i++) {
            int side = (i <= side0) ? 0 : 1;
            seats.add(new MatchDescriptor.Seat(i, SeatRole.PILOT, side, i, newToken(rng)));
        }
        return descriptor("seats:" + k, rng, roster, seats);
    }

    /**
     * The D2 show shape (chairs leg 1): two symmetric sides of {@code hullsPerSide}
     * hulls each — {@code claimSeats} claimable PILOT seats split ceil/floor across the sides
     * (the {@code seats:K} convention: seats {@code 1..ceil(S/2)} on side 0, ship id == seat id),
     * every remaining hull an explicit engine-AI row. {@code teams:2:8} = the owner's stated D2
     * validation shape ("8v8 with 1–2 real clients + AI fill", demo-program.md). Under the
     * ratified seat model an unoccupied SEAT hull is ABSENT until claim⇒fly-in, so
     * {@code teams:2:8} at occupancy 0 fields 6v6 and each claim flies in toward the full 8v8;
     * full-strength-from-tick-0 fill semantics (claimable/elastic rows) is leg 2 — a separately
     * gated seat-model amendment, deliberately NOT minted here.
     *
     * <p>Row order is LOAD-BEARING: ALL SEAT rows (side 0 then side 1) precede ALL AI rows, so
     * the client plugin's dense id-contract walk (roster declaration order × count) lands each
     * seat's hull at wire id == seat id — the same positions the deploy layer (seats first)
     * assigns and position-asserts. Every shipping shape keeps this seats-before-AI invariant.
     */
    public static MatchDescriptor teams(int claimSeats, int hullsPerSide) {
        if (claimSeats < 1) {
            throw new IllegalArgumentException(
                    "teams:S:N needs at least one claimable seat, got: " + claimSeats);
        }
        // The seat-id ceiling binds a teams mint too.
        if (claimSeats > MAX_SEATS) {
            throw new IllegalArgumentException("teams:S:N takes at most " + MAX_SEATS
                    + " claimable seats (the seat-id ceiling), got: " + claimSeats);
        }
        if (hullsPerSide < 1) {
            throw new IllegalArgumentException(
                    "teams:S:N needs at least one hull per side, got: " + hullsPerSide);
        }
        int side0Seats = (claimSeats + 1) / 2;
        int side1Seats = claimSeats - side0Seats;
        if (side0Seats > hullsPerSide) {
            throw new IllegalArgumentException(
                    "teams:" + claimSeats + ":" + hullsPerSide + " overfills side 0: its ceil(S/2)="
                            + side0Seats + " seats exceed the side's " + hullsPerSide
                            + " hulls (need S <= 2*N)");
        }
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, side0Seats, MatchDescriptor.PilotKind.SEAT));
        if (side1Seats > 0) {
            roster.add(new MatchDescriptor.RosterRow(1, CERTIFIED_VARIANT_ID, side1Seats, MatchDescriptor.PilotKind.SEAT));
        }
        if (hullsPerSide > side0Seats) {
            roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, hullsPerSide - side0Seats,
                    MatchDescriptor.PilotKind.AI));
        }
        if (hullsPerSide > side1Seats) {
            roster.add(new MatchDescriptor.RosterRow(1, CERTIFIED_VARIANT_ID, hullsPerSide - side1Seats,
                    MatchDescriptor.PilotKind.AI));
        }
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        for (int i = 1; i <= claimSeats; i++) {
            int side = (i <= side0Seats) ? 0 : 1;
            seats.add(new MatchDescriptor.Seat(i, SeatRole.PILOT, side, i, newToken(rng)));
        }
        return descriptor("teams:" + claimSeats + ":" + hullsPerSide, rng, roster, seats);
    }

    /**
     * The 1-vs-N Onslaught gauntlet:
     * side 0 = ONE seat-bound {@link #GAUNTLET_OWNER_VARIANT_ID} (seat 1, ship 1 —
     * the owner), side 1 = {@code joiners} seat-bound certified Mudskippers (seats/ships
     * {@code 2..joiners+1}). All rows are SEAT rows — under the seat model an unoccupied seat's hull is
     * ABSENT until claim⇒fly-in (D4.4); AI fill is only ever an explicit mint choice, and the
     * gauntlet mints none. {@code joiners} in [1, {@link #GAUNTLET_MAX_JOINERS}], else THROW.
     */
    public static MatchDescriptor gauntlet(int joiners) {
        return gauntlet(joiners, GAUNTLET_OWNER_VARIANT_ID, "gauntlet:" + joiners);
    }

    /**
     * The all-Mudskipper gauntlet (owner decisions): the
     * SAME 1-vs-N join shape as {@link #gauntlet(int)} with the owner hull swapped to
     * {@link #CERTIFIED_VARIANT_ID} — THE standing joiner/kit + watch descriptor since the
     * migration. The {@code :skipper} spec suffix (not the full variant id) keeps the minted
     * {@code match.id} short enough that the D1 fingerprint composite stays under its 128-char
     * cap, while the shape string keeps its {@code gauntlet:} prefix — the deployed client's
     * registryShape key matches on the prefix, so no client change rides this mint.
     */
    public static MatchDescriptor gauntletSkipper(int joiners) {
        return gauntlet(joiners, CERTIFIED_VARIANT_ID, "gauntlet:" + joiners + ":skipper");
    }

    private static MatchDescriptor gauntlet(int joiners, String ownerVariantId, String shape) {
        if (joiners < 1 || joiners > GAUNTLET_MAX_JOINERS) {
            throw new IllegalArgumentException(
                    "gauntlet:N needs 1 <= N <= " + GAUNTLET_MAX_JOINERS + " joiner seats, got: " + joiners);
        }
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, ownerVariantId, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, CERTIFIED_VARIANT_ID, joiners, MatchDescriptor.PilotKind.SEAT));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        for (int i = 2; i <= joiners + 1; i++) {
            seats.add(new MatchDescriptor.Seat(i, SeatRole.PILOT, 1, i, newToken(rng)));
        }
        return descriptor(shape, rng, roster, seats);
    }

    /**
     * The BASTION D5 leg-1 shape: seat 1's certified Mudskipper
     * plus {@code allies} certified-Mudskipper engine-AI ships on side 0, versus
     * {@code grendels} {@link #BASTION_GRENDEL_VARIANT_ID} engine-AI phase hulls on side 1.
     * Grendel rows are deliberately AI-only — DD6 de-risks the fast-time sim loop (the engine
     * flies phase for free via BasicShipAI) before any human phase lane exists; under the seat model
     * an occupancy-0 deploy leaves the SEAT hull ABSENT, so the headless probe fields
     * {@code allies}-vs-{@code grendels} pure AI. Row order keeps the seats-before-AI invariant.
     */
    public static MatchDescriptor bastion(int allies, int grendels) {
        if (allies < 0) {
            throw new IllegalArgumentException("bastion:A:B needs A >= 0 allied AI, got: " + allies);
        }
        if (grendels < 1) {
            throw new IllegalArgumentException(
                    "bastion:A:B needs at least one Grendel on side 1, got: " + grendels);
        }
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        if (allies > 0) {
            roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, allies, MatchDescriptor.PilotKind.AI));
        }
        roster.add(new MatchDescriptor.RosterRow(1, BASTION_GRENDEL_VARIANT_ID, grendels, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:" + allies + ":" + grendels, rng, roster, seats);
    }

    /**
     * {@code bastion:anchor} — the offline emergency-dive repro field: one
     * certified Mudskipper (side 0, engine AI) versus ONE {@link #ANCHOR_GRENDEL_VARIANT_ID}
     * (side 1, engine AI). Field mirrors {@code bastion:1:1} (seat row kept for boot-shape
     * parity; occupancy-0 leaves the seat hull absent) with the Grendel row swapped to the
     * {@code phase_anchor}-carrying HVD hull. The emergency dive is a battle-GLOBAL one-shot
     * ({@code phaseAnchor_canDive}), so a probe gets exactly one repro arm per boot.
     */
    public static MatchDescriptor bastionAnchor() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, ANCHOR_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:anchor", rng, roster, seats);
    }

    /**
     * {@code bastion:ram} — the offline vanilla ram-collision baseline field:
     * one {@link #BASTION_AURORA_VARIANT_ID} (side 0, engine AI) versus one
     * {@link #REMNANT_GLIMMER_SUPPORT_VARIANT_ID} (side 1, engine AI) — the recorded
     * specimen-#4 hull pair, so the headless measurement grades against the hand-flown ram's
     * geometry (Aurora CR 200/nose ~140 vs glimmer CR 70/nose ~41). Seat row kept for
     * boot-shape parity (occupancy-0 leaves the seat hull absent; anchor-shape precedent).
     * Probe-only ({@code RealEngineRamProbeMain}); nothing player-facing rides it.
     */
    public static MatchDescriptor bastionRam() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:ram", rng, roster, seats);
    }

    /**
     * The RIG arm: {@code bastion:A:B:phase} — identical
     * to {@link #bastion} except the SEAT-1 roster row swaps to
     * {@link #BASTION_GRENDEL_VARIANT_ID}, so the human/rig seat flies the Grendel phase hull
     * itself (a {@code bastion:A:B} descriptor cannot: {@code desiredShipId}/PasswordSeatLedger
     * pick SEATS, never hulls — the seat's HULL is its roster row's variant at mint). Suffix-arm
     * precedent = {@code gauntlet:N:skipper}: the shape string keeps its {@code bastion:} prefix
     * (deployed clients key registryShape on the prefix — no client change rides this mint) and
     * the minted {@code match.id} stays far under the D1 fingerprint 128-char cap.
     */
    public static MatchDescriptor bastionPhase(int allies, int grendels) {
        if (allies < 0) {
            throw new IllegalArgumentException("bastion:A:B:phase needs A >= 0 allied AI, got: " + allies);
        }
        if (grendels < 1) {
            throw new IllegalArgumentException(
                    "bastion:A:B:phase needs at least one Grendel on side 1, got: " + grendels);
        }
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        if (allies > 0) {
            roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, allies, MatchDescriptor.PilotKind.AI));
        }
        roster.add(new MatchDescriptor.RosterRow(1, BASTION_GRENDEL_VARIANT_ID, grendels, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:" + allies + ":" + grendels + ":phase", rng, roster, seats);
    }

    /**
     * {@code bastion:A:B:anubis} — {@link #bastionPhase} with the seat-1
     * hull swapped to {@link #BASTION_ANUBIS_VARIANT_ID} (the owner's temporal-shell Anubis),
     * A allied certified Mudskippers + B Grendel AI foes unchanged. The headless seat-lane probe
     * ({@code RealEngineTemporalShellProbeMain}, wrapper)
     * mints {@code bastion:0:1:anubis}; nothing product-side reads this arm. Same suffix-arm
     * grammar + {@code bastion:} prefix law as {@code :phase} / {@code :aurora}.
     */
    public static MatchDescriptor bastionAnubis(int allies, int grendels) {
        if (allies < 0) {
            throw new IllegalArgumentException("bastion:A:B:anubis needs A >= 0 allied AI, got: " + allies);
        }
        if (grendels < 1) {
            throw new IllegalArgumentException(
                    "bastion:A:B:anubis needs at least one Grendel on side 1, got: " + grendels);
        }
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_ANUBIS_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        if (allies > 0) {
            roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, allies, MatchDescriptor.PilotKind.AI));
        }
        roster.add(new MatchDescriptor.RosterRow(1, BASTION_GRENDEL_VARIANT_ID, grendels, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:" + allies + ":" + grendels + ":anubis", rng, roster, seats);
    }

    /**
     * The WATCH arm: {@code bastion:duel} — seat 1 (side 0) flies the
     * {@link #BASTION_GRENDEL_VARIANT_ID} phase hull, seat 2 (side 1) the certified Mudskipper,
     * NO AI rows: the standing dual-window human duel topology (w1 = own-phase feel, w2 = the
     * foe-phase presentation of a HUMAN-piloted cloak — the first live exercise of the v10 foe
     * lane against seat input; opposing sides so each is the other's foe and the duel ends on a
     * kill). Seats-before-AI holds trivially (no AI rows).
     */
    public static MatchDescriptor bastionDuel() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 1, 2L, newToken(rng)));
        return descriptor("bastion:duel", rng, roster, seats);
    }

    /**
     * The leg-2 headless arm: {@code bastion:A:B:aurora} — identical to
     * {@link #bastion} except the side-1 AI rows fly {@link #BASTION_AURORA_VARIANT_ID}, so the
     * Move-2 headless probe fields engine-AI Auroras (V2: does MANEUVERING_JETS AI activate
     * plasmajets headless?). Suffix-arm precedent = {@code bastion:A:B:phase}.
     */
    public static MatchDescriptor bastionAurora(int allies, int auroras) {
        if (allies < 0) {
            throw new IllegalArgumentException("bastion:A:B:aurora needs A >= 0 allied AI, got: " + allies);
        }
        if (auroras < 1) {
            throw new IllegalArgumentException(
                    "bastion:A:B:aurora needs at least one Aurora on side 1, got: " + auroras);
        }
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        if (allies > 0) {
            roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, allies, MatchDescriptor.PilotKind.AI));
        }
        roster.add(new MatchDescriptor.RosterRow(1, BASTION_AURORA_VARIANT_ID, auroras, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:" + allies + ":" + auroras + ":aurora", rng, roster, seats);
    }

    /**
     * The leg-2 WATCH arm: {@code bastion:duel:aurora} — seat 1 (side 0) flies
     * the {@link #BASTION_GRENDEL_VARIANT_ID} phase hull, seat 2 (side 1) the
     * {@link #BASTION_AURORA_VARIANT_ID}: the grendel-vs-aurora dual-window duel. Plain
     * {@code bastion:duel} keeps its CERTIFIED-Mudskipper seat 2 — reusing it for leg 2 would
     * silently field the wrong foe (the DD3 never-reuse pin).
     */
    public static MatchDescriptor bastionDuelAurora() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 1, 2L, newToken(rng)));
        return descriptor("bastion:duel:aurora", rng, roster, seats);
    }

    /**
     * The leg-3 tiny-wave WATCH arm: {@code bastion:wave} — seat 1 flies the
     * {@link #BASTION_GRENDEL_VARIANT_ID} and seat 2 the {@link #BASTION_AURORA_VARIANT_ID},
     * BOTH on side 0 (the first same-side two-human shape — legal v1: {@code teams()} already
     * mints side-1-seatless rosters, and the side-1 AI rows keep both sides populated), versus
     * the wave-1 side-1 AI rows = 1× {@link #REMNANT_GLIMMER_VARIANT_ID} + 1×
     * {@link #REMNANT_GLIMMER_SUPPORT_VARIANT_ID} vanilla Remnants (DD2 start-easy, foe DP 10
     * vs seat DP 48; the foe mix — a ballistic foe plus an all-beam foe now that
     * beams are wire-carried). Wave 2 (2× lumen + 1× glimmer)
     * is NOT a roster row: scheduled hulls fly in mid-round through
     * {@code WaveSchedule}/{@code midDeployWaveShip} (DD3), and the serve loop's stable-roster
     * bound learns the schedule total separately. Row order keeps the seats-before-AI invariant
     * (seat wire ids 1/2 == deploy positions).
     */
    public static MatchDescriptor bastionWave() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 0, 2L, newToken(rng)));
        return descriptor("bastion:wave", rng, roster, seats);
    }

    /**
     * The leg-3 headless RIG arm: {@code bastion:wave:rig} — the
     * {@link #bastionWave} shape with the human seats replaced by AI stand-ins (1× grendel +
     * 1× aurora side-0 AI rows; the {@code bots:A:B} occupancy-0 ladder precedent), so the
     * Move-2 ownerless headless proof fields stand-ins-vs-waves with zero humans. Seat 1 stays
     * a certified-Mudskipper SEAT row (unoccupied ⇒ ABSENT) so the descriptor
     * remains seat-servable for debugging without changing the AI matchup.
     */
    public static MatchDescriptor bastionWaveRig() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:wave:rig", rng, roster, seats);
    }

    /**
     * The beam WATCH arm: {@code bastion:beam} — the
     * {@link #bastionWave} topology with seat 2's Aurora swapped to
     * {@link #BASTION_AURORA_BEAM_VARIANT_ID} (graviton ×2 + phasebeam burst), so the dual-seat
     * WATCH exercises own-side beams AND the replay-driven puppet beam lane on the same hull;
     * foe rows = the same wave-1 glimmer mix. NO {@code WaveSchedule} branch (single-wave
     * fight — {@code forShape} returns null for this shape): the beam verify + dTheta
     * re-measure need no wave chain. {@code bastion:wave} keeps the hvb2 Aurora (the DD3
     * never-reuse pin; the commission keeps hvb2 the default).
     */
    public static MatchDescriptor bastionBeam() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_BEAM_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 0, 2L, newToken(rng)));
        return descriptor("bastion:beam", rng, roster, seats);
    }

    /**
     * The headless beam-evidence arm: {@code bastion:beam:rig} — {@link #bastionBeam}
     * with the human seats replaced by AI stand-ins (the {@link #bastionWaveRig} precedent:
     * certified-Mudskipper SEAT row kept seat-servable, grendel + beam-aurora side-0 AI rows),
     * so an ownerless serve fields beams on BOTH sides (foe graviton/taclaser vs Aurora
     * graviton/phasebeam) and the {@code .ccwr} filmstrip proves the wire half before any WATCH.
     */
    public static MatchDescriptor bastionBeamRig() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_BEAM_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:beam:rig", rng, roster, seats);
    }

    /**
     * The Hyperion own-teleport WATCH arm:
     * {@code bastion:hyperion} — the {@link #bastionWave} topology with seat 2's Aurora
     * swapped to {@link #BASTION_HYPERION_VARIANT_ID}, so the dual-seat WATCH exercises the
     * D5 own-ship reconcile patience on the long-range {@code teleporter} system. UNLIKE
     * {@code bastion:beam} this shape KEEPS the full {@code bastion:wave} WaveSchedule chain
     * ({@code forShape} compiles it): the commission swaps the seat hull on the STANDING wave
     * watch ("instead of the Aurora"), so the chain is the single-variable choice against the
     * earlier recorded baselines — and the wave-2+ lumen displacers keep the live-proven
     * foe-teleport lane under regression watch in the same run. {@code bastion:wave} keeps the
     * hvb2 Aurora (the never-reuse pin).
     */
    public static MatchDescriptor bastionHyperion() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_HYPERION_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 0, 2L, newToken(rng)));
        return descriptor("bastion:hyperion", rng, roster, seats);
    }

    /**
     * The headless AI stand-in twin ({@link #bastionBeamRig} precedent): {@code
     * bastion:hyperion:rig} — certified-Mudskipper SEAT row kept seat-servable, grendel +
     * hyperion side-0 AI rows, same wave-1 foe mix — so an unattended serve proves the fixture
     * resolves through the engine build path AND the engine AI drives the {@code teleporter}
     * (server {@code gpee_jump} rows) BEFORE a live WATCH spends a human's attention. No
     * WaveSchedule (beam-rig precedent — the fly-in machinery is already contract- and
     * live-proven; the rig's purpose is fixture + AI-teleport evidence, not the full fight).
     */
    public static MatchDescriptor bastionHyperionRig() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_HYPERION_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:hyperion:rig", rng, roster, seats);
    }

    /**
     * The first-carrier WATCH arm (carriers arc):
     * {@code bastion:carrier} — the exact {@link #bastionWave} shape plus ONE side-1 AI
     * {@link #CARRIER_CONDOR_VARIANT_ID} row at t=0, appended AFTER the standing glimmer mix
     * so every wave-baseline row keeps its deploy position (single-variable vs the
     * {@code bastion:wave} baselines — the 1275 hyperion design-call precedent, which swaps
     * where this ADDS). KEEPS the full {@code bastion:wave} WaveSchedule chain
     * ({@code forShape} compiles it). Purpose: the engine-born-growth tier's first live
     * subject — with {@code cc.server.engineBornGrowth=true} the condor's broadsword wings
     * are the round's engine-born fighters (growth_bound term, seam capture → RAPP announce,
     * client fighter adopt). Seats stay grendel/aurora.
     */
    public static MatchDescriptor bastionCarrier() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, CARRIER_CONDOR_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 0, 2L, newToken(rng)));
        return descriptor("bastion:carrier", rng, roster, seats);
    }

    /**
     * The headless AI stand-in twin ({@link #bastionWaveRig} precedent): {@code
     * bastion:carrier:rig} — certified-Mudskipper SEAT row kept seat-servable, grendel +
     * aurora side-0 AI stand-ins, the wave-1 foe mix plus the side-1 condor. Schedule-less
     * ({@code forShape} returns null naturally — the hyperion-rig precedent): the rig's
     * purpose is the unattended growth positive control (knob-ON serve births broadswords,
     * growth_bound + growth_spawn + RAPP receipts, no fx_drone blank-id IOException) BEFORE
     * a live WATCH spends a human's attention, not the full wave fight.
     */
    public static MatchDescriptor bastionCarrierRig() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, CARRIER_CONDOR_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:carrier:rig", rng, roster, seats);
    }

    /**
     * The Bastion leg-4 STAR FORTRESS WATCH arm:
     * {@code bastion:fortress} — a DEFEND mode. Grendel/aurora seats and ONE AI
     * {@link #BASTION_STATION_VARIANT_ID} row are ALLIED on side 0; EVERY foe arrives as a
     * side-1 {@code WaveSchedule} hull — the mint declares NO side-1 rows. The t=0 glimmer
     * pair (the "CC AI 1.x" de-facto wave 1) was REMOVED so every foe is a wave enemy;
     *
     * the empty opening that leaves is closed by the front-loaded w2 timer
     * ({@code WaveSchedule.FORTRESS_W2_FALLBACK_DUE_SEC}). A one-sided t=0 field is round-end
     * safe: match-over stays suppressed until two sides have been SEEN live (no tick-0 latch).
     * The rig twin KEEPS its glimmer rows — they are its only attackers and its
     * LOSE-leg control. The station row is appended
     * LAST (the carrier mint's append-don't-swap rationale, so every wave-baseline row keeps its
     * deploy position) and LAST by CONTRACT, not by taste: the client's id-contract law interns
     * wire ids 1..N in roster-row-flattened DECLARATION order, so a last
     * station row takes wire id N and its engine-minted modules intern first-seen ABOVE the band
     * at [N+1..N+24] — a station anywhere else would push every later row's declared id off its
     * hull. The flattened order is side-BLIND, so the DEFEND side flip leaves it intact.
     *
     * <p>Side 0 is deliberate: a side-1 station
     * was an inverted SIEGE — the waves could never attack the fortress and the fortress fired
     * on the seats. LOSE = the engine-native base-{@code isHulk} seam
     * ({@code NPeerHooks.StationHook}), now fired by WAVE damage. WIN = every scheduled wave
     * drained with side 1 cleared ({@code WaveSchedule.latchesRoundEndOnWaveClear}). Requires
     * {@code cc.server.engineBornGrowth=true} — the station's module-hosted drones are
     * engine-born (serveNPeer fail-fasts otherwise).
     */
    public static MatchDescriptor bastionFortress() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        // W2a pod seat (owner wishlist): before the station row, which stays LAST by contract.
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_SHUTTLEPOD_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        // NO side-1 rows BY COMMISSION: all foes are WaveSchedule-fielded.
        // DEFEND: the fortress is ALLIED — side 0, LAST, AI, count 1.
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_STATION_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 0, 2L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(3, SeatRole.PILOT, 0, 3L, newToken(rng)));
        return descriptor("bastion:fortress", rng, roster, seats);
    }

    /**
     * The A/B ping-discriminator arm: {@code
     * bastion:fortress_ab} — {@link #bastionFortress} with the grendel seat swapped for a
     * SECOND {@link #BASTION_AURORA_VARIANT_ID} seat, so the owner flies the SAME hull in both
     * windows (w1 direct ~0ms vs w2 VPN hairpin ~250ms) and feels how ping shapes autofire
     * lead. Pod + station rows verbatim from the fortress mint (station LAST by the
     * id-contract law; NO side-1 rows — all foes WaveSchedule-fielded). Fortress behavior
     * rides {@link #isFortressShape} everywhere. DP LAW (the one behavioral
     * non-identity): the seat basis reads 60 (30+30) vs the ratified 48 ⇒ AUTO mode boots OUT
     * OF BAND by design — AB serves MUST run SCALED ({@code CC_MATCH_FORTRESS_DP=575}). No new
     * officers.txt row owed: requireTwinRows de-dupes by variant id and the aurora row exists.
     */
    public static MatchDescriptor bastionFortressAb() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        // W2a pod seat: before the station row, which stays LAST by contract.
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_SHUTTLEPOD_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        // NO side-1 rows BY COMMISSION: all foes are WaveSchedule-fielded.
        // DEFEND: the fortress is ALLIED — side 0, LAST, AI, count 1.
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_STATION_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 0, 2L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(3, SeatRole.PILOT, 0, 3L, newToken(rng)));
        return descriptor(FORTRESS_AB_SHAPE, rng, roster, seats);
    }

    /**
     * The PvP fortress {@link #PVP_SHAPE}: the fortress's Grendel / Aurora / shuttlepod SEAT rows
     * on side 0 (South), then the same three on side 1 (North); NO AI row and NO station, so every
     * foe a team meets is the other team or a scheduled wave (side 1, the north edge). Seats 1-3
     * South, 4-6 North, ship id == seat id (every SEAT row precedes every AI row, the
     * {@link #regrowSeatRows} law; there is no AI row here at all). The sides keep the ISS / HSS
     * names every mint writes (a side name is the kit's ship-name prefix); only the taglines name
     * the teams ({@link #descriptor}). A players mint splits ceil / floor (South takes the odd
     * seat) and trims the pods first.
     */
    public static MatchDescriptor bastionFortressPvp() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        int ship = 0;
        for (int side = 0; side < 2; side++) {
            for (String variant : new String[] {BASTION_GRENDEL_VARIANT_ID,
                    BASTION_AURORA_VARIANT_ID, BASTION_SHUTTLEPOD_VARIANT_ID}) {
                roster.add(new MatchDescriptor.RosterRow(side, variant, 1,
                        MatchDescriptor.PilotKind.SEAT));
                ship++;
                seats.add(new MatchDescriptor.Seat(ship, SeatRole.PILOT, side, ship,
                        newToken(rng)));
            }
        }
        return descriptor(PVP_SHAPE, rng, roster, seats);
    }

    /**
     * The headless AI stand-in twin ({@link #bastionCarrierRig} precedent): {@code
     * bastion:fortress:rig} — certified-Mudskipper SEAT row kept seat-servable, grendel +
     * aurora side-0 AI stand-ins, the wave-1 foe mix, and the side-0 station row LAST (same
     * DEFEND alliance and same contract position as the WATCH arm — the id-contract law is
     * exactly what the rig proves). Schedule-less ({@code forShape} returns null naturally):
     * the rig's purpose is the unattended station positive control — deploy census, module bound
     * term, F3 id readback, repo-order==descriptor-order, and the base-hulk lose path — BEFORE
     * a live WATCH spends a human's attention, not the full wave fight.
     */
    public static MatchDescriptor bastionFortressRig() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        // DEFEND: the fortress is ALLIED — side 0, LAST, AI, count 1.
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_STATION_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:fortress:rig", rng, roster, seats);
    }

    /**
     * The slice-3 wire-ordnance WATCH arm: {@code bastion:strike} — the
     * exact {@link #bastionCarrier} shape plus ONE side-1 AI {@link #STRIKE_DROVER_VARIANT_ID}
     * row APPENDED after the condor, so every carrier-baseline row keeps its deploy position
     * (single-variable vs the proven {@code bastion:carrier} baselines — the same
     * append-don't-swap rationale the carrier mint used against {@code bastion:wave}).
     * KEEPS the full {@code bastion:wave} WaveSchedule chain ({@code forShape} compiles it).
     * Purpose: ONE row delivers the slice-3 ordnance surface — harpoon guided bursts, dagger
     * atropos torpedoes + per-salvo re-dock traffic, broadsword fighter regression, and the
     * {@code flarelauncher_active} flare subject. Foe t=0 DP 32 vs seat 48 (authored from
     * feel data; recorded fallback = wave + drover only, DP 22). Seats stay grendel/aurora.
     */
    public static MatchDescriptor bastionStrike() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, CARRIER_CONDOR_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, STRIKE_DROVER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 0, 2L, newToken(rng)));
        return descriptor("bastion:strike", rng, roster, seats);
    }

    /**
     * The headless AI stand-in twin ({@link #bastionCarrierRig} precedent): {@code
     * bastion:strike:rig} — certified-Mudskipper SEAT row kept seat-servable, grendel +
     * aurora side-0 AI stand-ins, the wave-1 foe mix plus the condor plus the drover.
     * Schedule-less ({@code forShape} returns null naturally): the rig's purpose is the
     * unattended slice-3 (b) baseline — the return-order→landing gap MEASURE,
     * the guided-track baseline, and the flare GPEE emission-shape confirm —
     * BEFORE a live WATCH spends a human's attention, not the full wave fight.
     */
    public static MatchDescriptor bastionStrikeRig() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, CERTIFIED_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, CARRIER_CONDOR_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, STRIKE_DROVER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        return descriptor("bastion:strike:rig", rng, roster, seats);
    }

    /**
     * The missile-split ACCEPTANCE arm: {@code bastion:mirv} — the exact
     * {@link #bastionStrike} roster plus side-1 AI {@link #MIRV_GRYPHON_VARIANT_ID} (hurricane)
     * + {@link #MIRV_CHAMPION_VARIANT_ID} (hydra) rows APPENDED after the drover, so every
     * strike-baseline row keeps its deploy position (the append-don't-swap rationale strike
     * itself used vs carrier — like-with-like against the recorded differ baseline reel). With
     * the condor's pilum the foe side fields all three eyeball-check families. KEEPS the full
     * {@code bastion:wave} WaveSchedule chain ({@code forShape} compiles it). Foe t=0 DP is
     * heavier than strike (authored from feel data; the shape's purpose is split moments in
     * view, not survival pacing).
     */
    public static MatchDescriptor bastionMirv() {
        SecureRandom rng = new SecureRandom();
        List<MatchDescriptor.RosterRow> roster = new ArrayList<MatchDescriptor.RosterRow>();
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_GRENDEL_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(0, BASTION_AURORA_VARIANT_ID, 1, MatchDescriptor.PilotKind.SEAT));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, REMNANT_GLIMMER_SUPPORT_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, CARRIER_CONDOR_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, STRIKE_DROVER_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, MIRV_GRYPHON_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        roster.add(new MatchDescriptor.RosterRow(1, MIRV_CHAMPION_VARIANT_ID, 1, MatchDescriptor.PilotKind.AI));
        List<MatchDescriptor.Seat> seats = new ArrayList<MatchDescriptor.Seat>();
        seats.add(new MatchDescriptor.Seat(1, SeatRole.PILOT, 0, 1L, newToken(rng)));
        seats.add(new MatchDescriptor.Seat(2, SeatRole.PILOT, 0, 2L, newToken(rng)));
        return descriptor("bastion:mirv", rng, roster, seats);
    }

    private static MatchDescriptor descriptor(String shape, SecureRandom rng,
                                              List<MatchDescriptor.RosterRow> roster,
                                              List<MatchDescriptor.Seat> seats) {
        return descriptor(shape, rng, roster, seats, CERTIFIED_MAP_HALF);
    }

    /** The one construction site takes the half explicitly (knob single-source).
     *  Refuses more than {@link #MAX_SEATS} seats: no mint may field a seat id the wire cannot carry.
     * */
    private static MatchDescriptor descriptor(String shape, SecureRandom rng,
                                              List<MatchDescriptor.RosterRow> roster,
                                              List<MatchDescriptor.Seat> seats, float mapHalf) {
        if (seats.size() > MAX_SEATS) {
            throw new IllegalArgumentException("shape '" + shape + "' would mint " + seats.size()
                    + " seats, over the " + MAX_SEATS + "-seat ceiling");
        }
        List<MatchDescriptor.Side> sides = new ArrayList<MatchDescriptor.Side>();
        // PvP keeps the side NAMES (the kit's ship-name
        // prefix) and names the teams in the taglines; keyed on the shape here so the players
        // regrow, which rebuilds through this method, keeps them too.
        boolean pvp = isPvpShape(shape);
        sides.add(new MatchDescriptor.Side(PLAYER_SIDE_NAME,
                pvp ? PVP_SOUTH_TAGLINE : PLAYER_SIDE_TAGLINE, "ATTACK"));
        sides.add(new MatchDescriptor.Side(ENEMY_SIDE_NAME,
                pvp ? PVP_NORTH_TAGLINE : ENEMY_SIDE_TAGLINE, "ATTACK"));
        String matchId = shape.replace(':', '_') + "-" + hex(rng, 8);
        return new MatchDescriptor(MatchDescriptor.VERSION, matchId, shape,
                mapHalf, mapHalf,
                CERTIFIED_DEPLOY_CR, "BUDGET",
                sides, roster, seats);
    }

    private static int parseIntArg(String spec, String arg) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("shape spec '" + spec + "': '" + arg + "' is not an int");
        }
    }

    /** A fresh 32-hex seat token (16 random bytes) — admission-handshake identity, R8 enforces. */
    private static String newToken(SecureRandom rng) {
        return hex(rng, 16);
    }

    private static String hex(SecureRandom rng, int byteCount) {
        byte[] bytes = new byte[byteCount];
        rng.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(byteCount * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
