package cc.starsector.server.runtime;

import cc.starsector.server.bridge.SeatRebind;

import java.io.IOException;

/**
 * W2b E3: the serve loop's module-seat engine seam —
 * MOUNT-EXISTING (re-point a seat's fixed wire id at a live station weapon module, AI yielded)
 * and its voluntary-dismount reverse (AI restored, module lives on unpiloted). The ONE real hook
 * ({@code PilotIntentCombatServerMain.buildModuleMountHook}) fronts
 * {@code SeatRebind.mountStationModule}/{@code dismountStationModule}; contract fakes
 * bind none (a null hook keeps the arbiter un-armed — {@code /module} honestly replies
 * "unavailable"). A THROW is fatal (an engine re-key failure is never per-session — the
 * fly-in/wave-spawn law).
 *
 * <p>Top-level rather than a {@code NPeerHooks} nested interface DELIBERATELY: the serve loop
 * file sits ~100 lines under its 5000-non-blank deferred-refactors trip, and the E4 drain budget
 * spends that headroom.
 */
public interface ModuleMountHook {

    /**
     * Mount {@code seatId} (fixed wire id {@code seatWireId}) onto the live module in engine
     * slot {@code slotId}, refusing unless it carries {@code expectedModuleVariantId} — the
     * census row's variant pin. Returns the deploy-recorded identity (stable id DIRECT — no
     * mint, so there is no ledger diff to dance around); the caller owns the registry re-key,
     * fire-lane resets, arbiter note and MMNT/RAPP fan.
     */
    SeatRebind.ModuleMountResult mount(int seatId, long seatWireId, String slotId,
            String expectedModuleVariantId) throws IOException;

    /**
     * Voluntary dismount — restore the module's engine AI and vacate the seat's live-map entry.
     * Returns the module's stable id (the caller's registry re-key + MMNT fan key).
     */
    String dismount(int seatId, long seatWireId) throws IOException;
}
