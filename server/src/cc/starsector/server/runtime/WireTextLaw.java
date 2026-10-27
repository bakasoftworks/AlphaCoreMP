package cc.starsector.server.runtime;

/**
 * The WIDE form of the ONE single-line
 * printable character law for every peer-authored wire text — HELLO {@code clientId} /
 * {@code seatToken} / {@code fingerprint} / {@code variantId} + the WELCOME reject reason
 * ({@code HandshakeCodec.HelloPayload#cleanHandshakeText}), seat names
 * ({@link SeatNameFrameCodec#validateSeatName}), chat text ({@link ChatFrameCodec#validateChatText}),
 * posture experiment tags ({@link PostureFrameCodec#validateExperimentTag}), listing occupant labels
 * ({@link ListingFrameCodec#validateOccupantLabel}) and the roster seat-name knob
 * ({@code bridge.DescriptorLayout}). Before this law the family shared the identical
 * {@code c < 0x20 || c == 0x7f} test, so C1 controls, NEL, the Unicode line/paragraph separators and
 * the bidi controls passed EVERY content law (a known security residual). Widening in one helper
 * keeps the family skew-free — a HELLO-only widening would have minted the skew the original claim
 * wrongly alleged.
 *
 * <p>The widened set is the listing lane's strip set,
 * already rejected at listing decode BOTH sides and stripped at paste by the client's
 * {@code ChatInputLaw}; the client-side typed-path/validator mirrors ride a client night (the
 * client reader accepts everything the server now emits, so the two sides never disagree on a
 * live value). Every currently-emitted id/token/fingerprint is ASCII printable single-line, so the
 * law rejects ZERO live values (emitter survey).
 *
 * <p>Deliberately NOT rejected (documented residuals, not omissions): non-ASCII printables (the
 * chat law admits them by contract), the Unicode spaces U+00A0/U+2000–U+200A/U+3000 (whitespace
 * look-alikes — {@code String.trim} does not strip them; a name-forging class of its own), lone
 * surrogate code units OUTSIDE the HELLO (the modified-UTF-8
 * {@code DatagramFrameCodec.PayloadReader#readUtf} 3-byte branch appends U+D800..U+DFFF unchecked,
 * so one DOES arrive from the wire; the HELLO text
 * fields + the WELCOME reason now REJECT one via {@link #hasLoneSurrogate} in
 * {@code HandshakeCodec.HelloPayload#cleanHandshakeText} — a deliberate HELLO-only skew: every other
 * family still ADMITS them, because the client's typed chat path appends per UTF-16 unit up to the
 * cap, so an honest line can end in a lone high surrogate and a family-wide reject would drop it
 * silently [hypothesis: LWJGL delivers a supplementary char as two events]), zero-width joiners/spaces (U+200B–U+200D, U+2060, U+FEFF — legitimate in some scripts).
 */
public final class WireTextLaw {

    private WireTextLaw() {}

    /** The pre-widening family test: C0 controls + DEL. */
    public static boolean isAsciiControl(char c) {
        return c < 0x20 || c == 0x7f;
    }

    /**
     * The widened smuggle set: C1 controls (NEL U+0085 included), LINE/PARAGRAPH SEPARATOR, and the
     * bidi controls (ALM, LRM/RLM, the embeddings/overrides LRE..RLO, the isolates LRI..PDI).
     */
    public static boolean isWidenedSmuggleChar(char c) {
        return (c >= 0x80 && c <= 0x9f)         // C1 controls incl. NEL U+0085
                || c == 0x2028 || c == 0x2029   // LINE / PARAGRAPH SEPARATOR
                || c == 0x061c                  // Arabic letter mark (bidi)
                || c == 0x200e || c == 0x200f   // LRM / RLM
                || (c >= 0x202a && c <= 0x202e) // LRE / RLE / PDF / LRO / RLO
                || (c >= 0x2066 && c <= 0x2069); // LRI / RLI / FSI / PDI
    }

    /**
     * The slash LOOK-ALIKES a chat line can hide a command behind: the backslash, U+FF0F (fullwidth solidus),
     * U+FF3C (fullwidth backslash), U+2215 (division slash), U+2044 (fraction slash), U+FE68 (small backslash) —
     * six in all. ONE predicate for both sides, so
     * the kit can never class a line PLAIN that the door reads as a led {@code /auth}: the door
     * ({@code ChatCommands.ledCommandLine}, which never relays or logs such a line) and the kit's send-side class
     * ({@code ChatInputLaw.slashLike}; this class is kit-folded, {@code client-mod/build.ps1 $pilotClasses}). The
     * kit's own earlier copy held only the first two, so the other four + {@code auth <secret>} left the kit
     * readable. NOT every key a hand can land on — the named residuals sit on {@code ChatCommands.slashLookAlike}.
     */
    public static boolean isSlashLookAlike(char c) {
        return c == '\\' || c == (char) 0xFF0F || c == (char) 0xFF3C || c == (char) 0x2215 || c == (char) 0x2044
                || c == (char) 0xFE68;
    }

    /** The family law: a char may sit in a peer wire string iff it is neither set above. */
    public static boolean isPrintableWireChar(char c) {
        return !isAsciiControl(c) && !isWidenedSmuggleChar(c);
    }

    /**
     * true iff {@code s} holds a UTF-16 surrogate
     * code unit (U+D800..U+DFFF) that is not half of a valid high+low pair. HELLO-ONLY by law (the
     * class javadoc residual): {@code getBytes(UTF_8)} maps a lone surrogate to {@code ?}, so a cid
     * carrying one makes {@code BanSet.cidKey} non-injective; rejecting it at HELLO decode keeps the
     * key exact on every admitted cid. Never wired into the chat / seat-name / posture / VCHK /
     * descriptor cleaners.
     */
    public static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++; // a valid pair
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Index of the first char that fails {@link #isPrintableWireChar}, or {@code -1} when clean. */
    public static int firstUnprintableIndex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isPrintableWireChar(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The EMIT-side fold — every char
     * {@link #isPrintableWireChar} refuses becomes {@code '?'}; {@code s} itself is returned when
     * clean (one scan, no allocation) and null passes through (the caller's own null law stands).
     * WHY: the kit refuses a law-violating RAPP row fail-closed (the WHOLE frame), so text the server
     * emits or logs but did not author (engine / table names and ids) folds instead of throwing on a
     * frame-loop path; {@code '?'}, never a space ({@code PeerText.bound}), so a folded name is
     * never blank. No length cut: RAPP names carry no char cap (the frame byte budget bounds them).
     */
    public static String foldUnprintable(String s) {
        int first = s == null ? -1 : firstUnprintableIndex(s);
        if (first < 0) {
            return s;
        }
        char[] out = s.toCharArray();
        for (int i = first; i < out.length; i++) {
            if (!isPrintableWireChar(out[i])) {
                out[i] = '?';
            }
        }
        return new String(out);
    }
}
