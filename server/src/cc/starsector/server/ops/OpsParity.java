package cc.starsector.server.ops;

import java.util.List;

/**
 * The control-id lists of the ops-console PARITY law.
 * WHY: the web console is the only operator surface, and a
 * release must carry every control the host's own setup carries; that contract is enforced by the build
 * gate, never by a reading - the console selftest's {@code parity-web-twin} row fails when an id in
 * {@link #PACKAGE_IDS} has no {@code data-cc="<id>"} twin on the served page, and {@code parity-owner-twin}
 * does the same for {@link #OWNER_IDS}.
 *
 * <p>Provenance: the ids are the checklist's
 * control-kind rows ({@code button / textField / combo /
 * spinner / checkbox / toggle / tab / tableColumn}, the {@code ≡}/{@code +} suffixes dropped),
 * MINUS {@link #OWNER_IDS} (owner-mode only, hidden on a package page by
 * {@code CC.packageChrome}), MINUS {@link #DEAD_PACKAGE_IDS} (read by nothing in a
 * package: checklist {@code pkg.setup.deadControls}; EMPTY), MINUS {@code tab.voice}
 * (the v1 arrangement only; a package runs v3) and MINUS {@code live.seat.col.cidkey} (model-only,
 * removed from the seat table's view); PLUS {@code mods.table} (the Mods table itself, a new id
 * beside its columns); PLUS the parity port's package-only controls ({@link #PACKAGE_ONLY_IDS}),
 * not yet checklist rows.
 *
 * <p>The law: an id added to {@link #PACKAGE_IDS} FAILS {@code parity-web-twin} until the served page
 * carries its {@code data-cc} twin, and an id dropped from it is a release cut ({@code parity-list-sane}
 * pins the cut list).
 *
 * <p>The served page runs in owner mode too, so its ONE chrome rule
 * ({@code CC.packageChrome}) reads {@code /api/config}'s {@code packaged}:
 * {@link #PACKAGE_ONLY_IDS} shown on a package only, {@link #OWNER_IDS}
 * in owner mode only. The page's literal arrays copy these lists.
 */
public final class OpsParity {

    private OpsParity() {
    }

    /** Every control id a PACKAGED console shows from the checklist-derived set - the release contract the
     *  served page twins (the row compares as sets; 101 ids, measured by
     *  {@code parity-web-twin}'s {@code ids=} line - the per-addition counts below trail it by one).
     *  {@code verb.customKeep} + {@code verb.customForget} (Keep for
     *  everyone / Forget kept, both modes; their page twins landed with them, so
     *  {@code WEB_TWIN_PENDING} stayed empty).
     *  + the eight {@code setup.map.*} ids - a
     *  package edits the picked match shape's map size, station, backdrop and deploy CR through
     *  their four Applies ({@code OpsMatchApply.applyPackageMatchSetting}); the three fields left
     *  {@link #DEAD_PACKAGE_IDS}, the three Applies left {@link #OWNER_IDS}, the CR pair is new.
     *  + {@code setup.record} (the package-only
     *  Recording combo, also in {@link #PACKAGE_ONLY_IDS}) and {@code setup.reach} (Check from the
     *  internet, shown in BOTH modes) - 94 ids.
     *  + {@code setup.matchText} (Copy match credential) and
     *  {@code setup.shapeNew} (New match shape...) - both package-only (also in
     *  {@link #PACKAGE_ONLY_IDS}), their page twins owed ({@code WEB_TWIN_PENDING}) - 96 ids.
     *  + {@code verb.makeKit} (Make joiner kit),
     *  package-only (also in {@link #PACKAGE_ONLY_IDS}); its page twin landed with it, so it never rode
     *  {@code WEB_TWIN_PENDING} - 97 ids.
     *  + {@code setup.net.seatsPerSource} (Seats per address) and
     *  {@code setup.officer.allSkills} (All skills), shown in BOTH modes (never package-only), their page twins
     *  landed with them - 97 + 2 by this chain's count, 100 in the list.
     *  + {@code setup.replay.rngPins} (Deterministic replay pins (RNG)),
     *  shown in BOTH modes (never package-only), its page twin landed with it - 101 in the list.
     *  + {@code verb.watch} (Launch debug WATCH), moved from
     *  {@link #OWNER_IDS}: a package shows it where Join from this PC shows (a Windows host)
     *  as a Start with the debug posture, never a script;
     *  {@code parity-web-twin} requires its page twin (the web console's watch button) - 102 in the list.
     *  Minus {@code setup.matchText} + {@code verb.makeKit} (the match
     *  goes over the wire at join) - 100 in the list.
     *  Minus {@code setup.gameRoot.browse} (a folder picker over the server's own disk has no page form - a
     *  browser's file input yields the client's files; the typed Game folder field {@code setup.gameRoot} is the
     *  control) - 99 in the list.
     *  + {@code verb.shipHide} + {@code verb.shipShow} (Hide / Show on a
     *  row of the ships that come with the server), shown in BOTH modes (never package-only); their page twins are
     *  the row template's two buttons, landed with them - 101 in the list.
     *  The {@code WEB_TWIN_PENDING} list (the parity port's hatch: a control that landed before its
     *  page twin was skipped by the twin row) is DELETED
     * - an id listed here without a page twin turns {@code parity-web-twin} red as soon as
     *  it lands; {@code parity-list-sane} pins the OWNER + DEAD set as the release-cut list. */
    public static final List<String> PACKAGE_IDS = List.of(
            "tab.live", "tab.setup", "tab.mods",
            "live.seat.pick", "live.seat.col.seat", "live.seat.col.name", "live.seat.col.client",
            "live.seat.col.ship", "live.seat.col.endpoint", "live.seat.col.live",
            "live.seat.col.txRate", "live.seat.col.rxRate", "live.seat.col.txTotal",
            "live.seat.col.rxTotal", "live.seat.col.ctrl", "live.seat.col.custom",
            "live.seat.col.customs",
            "load.uplink",
            "verb.startServer", "verb.watch", "verb.endRound", "verb.endMatch", "verb.kill", "verb.kickSelected",
            "verb.banPlayer", "verb.banSource", "verb.unbanPlayer", "verb.customBan",
            "verb.customExport", "verb.customAllow", "verb.customUnban", "verb.customKeep",
            "verb.customForget", "verb.shipHide", "verb.shipShow", "verb.simSpeed.field",
            "verb.simSpeedSet", "verb.simSpeedAuto", "verb.customSubmissions", "verb.joinerGui",
            "setup.bindHost", "setup.bindPort", "setup.lease", "setup.advertise", "setup.brick",
            "setup.reach", "setup.gameRoot",
            "setup.tickRate", "setup.record",
            "setup.net.seatsPerSource", "setup.officer.allSkills", "setup.replay.rngPins",
            "setup.map.half", "setup.map.apply", "setup.map.station", "setup.map.stationApply",
            "setup.map.backdrop", "setup.map.backdropApply", "setup.map.deployCr",
            "setup.map.deployCrApply",
            "setup.fortress.dp", "setup.fortress.terrain", "setup.fortress.terrainLevel",
            "setup.fortress.terrainLive", "setup.fortress.asteroids", "setup.fortress.asteroidsLive",
            "setup.wave.scale", "setup.wave.rosterDp", "setup.wave.rosterMultiplier", "setup.wave.category",
            "setup.wave.categoryLive", "setup.shape", "setup.shapeNew", "setup.joiner.default",
            "setup.joiner.defaultLive", "setup.joiner.endless", "setup.joiner.endlessLive",
            "setup.password", "setup.adminPassword",
            "voice.enabled", "voice.fec", "voice.bitrateKbps", "voice.frameMs", "voice.complexity",
            "voice.maxTalkers", "voice.proxFullSu", "voice.proxCullSu", "voice.relayTickMs",
            "voice.jitterMinFrames", "voice.jitterMaxMs", "voice.jitterShrinkMs", "voice.apply",
            "mods.table", "mods.col.mod", "mods.col.id", "mods.col.version", "mods.col.use",
            "mods.col.folder", "mods.roots.add", "mods.remove", "mods.rescan");

    /** The owner-mode-only controls (the page's {@code CC.packageChrome} hides them in a
     * package: each reaches pwsh, repo scripts or owner-mode kit descriptors;
     * {@code setup.brick} moved to {@link #PACKAGE_IDS}, its package handler lease-free).
     * {@code setup.lease} moved there too - a package's button
     * is Fill from ProtonVPN (the Java probe, no pwsh).
     * {@code verb.joinerGui} moved to {@link #PACKAGE_IDS} -
     * a package's button is Join from this PC, the Java core ({@link OpsJoinSelf}), no pwsh.
     * The three map Applies ({@code setup.map.apply},
     * {@code .stationApply}, {@code .backdropApply}) moved to {@link #PACKAGE_IDS} - a package's
     * handler re-makes the picked shape's own match files (no kit descriptor, no pwsh).
     * {@code verb.watch} moved to {@link #PACKAGE_IDS} - a
     * package's Launch debug WATCH is a Start with the debug posture (no pwsh); Dev editor alone stays.
     * The console serves its press at {@code POST /api/dev-editor}
     * (refused in a package). Its page button {@code #btn-dev-editor} sits in the SERVER cluster right after Join
     * from this PC, {@code hidden} in the HTML; {@code CC.packageChrome} shows it only when {@code /api/config} says
     * {@code packaged:false}, and the page's verb-bar order row pins its place in the bar.
     * The Dev editor stays owner-only; a package's release twin of it is
     * the Players section's ships list (Hide / Show, in {@link #PACKAGE_IDS}) with the kept ships' Keep / Forget,
     * and owner mode carries both. */
    public static final List<String> OWNER_IDS = List.of("verb.devEditor");

    /** The controls DEAD in a package (checklist {@code pkg.setup.deadControls}: a control nothing
     * reads there, hidden with its row). EMPTIED -
     * map half, station and backdrop (the three it held) are live in a package (their
     * Applies re-make the picked shape) and moved to {@link #PACKAGE_IDS}. The constant stays
     * because its readers do: {@link OpsPressSelftest}'s {@code parity-list-sane} (owner + dead never
     * listed as package-visible) and {@code pkg.setup.deadControls} (pins it EMPTY) - a later dead
     * control lands here again. */
    public static final List<String> DEAD_PACKAGE_IDS = List.of();

    /** Ids in {@link #PACKAGE_IDS} an OWNER-mode
     * page does not show - the Game folder row (owner mode's stage writes {@code game.root}
     * itself; {@code CC.packageChrome} shows the row only in a package).
     * + the deploy-CR pair - owner mode sets CR
     * through its config row only, so its chrome hides the field and its Apply.
     * + {@code setup.record} - owner mode's
     * {@code record=} row is MINT-extracted from owner mode's own serve argv (the launcher reads it in
     * both modes), so the PICK is a package's only.
     * + {@code setup.shapeNew} - a package's host mints
     * shapes here; owner mode mints by script. */
    public static final List<String> PACKAGE_ONLY_IDS = List.of("setup.gameRoot",
            "setup.map.deployCr", "setup.map.deployCrApply", "setup.record",
            "setup.shapeNew");

    /** Ids the served PAGE carries beyond the checklist-derived
     * {@link #PACKAGE_IDS}, by design - page controls born after that set, so they never join it and
     * {@code parity-web-twin} does not read this list. {@code setup.gameRootSecond} = the debug WATCH's second game folder (the
     * launcher's {@code game.root.second} row, a draftable Setup fact); {@code setup.shape.players} +
     * {@code setup.shape.playersApply} = the picked shape's player count and its Apply. Disjoint from
     * {@link #PACKAGE_IDS}, {@link #OWNER_IDS} and {@link #PACKAGE_ONLY_IDS} (row {@code draft-ids-parity}).
     * + {@code setup.match.pvp} = the PvP mode select (a draftable Start-time
     * fact). */
    public static final List<String> PAGE_ONLY_IDS = List.of("setup.gameRootSecond", "setup.shape.players",
            "setup.shape.playersApply", "setup.match.pvp");
}
