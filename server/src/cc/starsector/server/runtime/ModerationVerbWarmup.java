package cc.starsector.server.runtime;

/**
 * The moderation-verb class warm-up. A live match's
 * ban tick was a 90.19 ms frame with 34.1 ms of first-use class loading on the SIM thread
 * (the ban-session deposit parse, the label
 * sanitizer, the ban entry, the KICKED notice codec, the grace lane's enum switch map) and ~31 ms
 * on the reader thread (the {@code /ban} chat verb), whose DatagramReliability monitor stalled
 * the sim thread's send 17.8 ms. {@link #touch()} loads and initializes those PRODUCT classes
 * ONCE at serve boot — boot-time only, never on the frame loop. Engine-internal classes
 * are never touched. Every listed class's static init is side-effect free (constants, a date
 * formatter, enum switch maps). A class that fails to load logs ONE row and is counted; the
 * serve boots regardless.
 */
public final class ModerationVerbWarmup {

    /** The JFR-measured first-use classes of the ban path (binary names: nested = {@code $}). */
    static final String[] CLASSES = {
        // sim thread (the serve loop's operator poll → banSession → the grace pump)
        "cc.starsector.server.runtime.OperatorCommandFile$BanSessionDeposit",
        "cc.starsector.server.runtime.VoiceMuteCommand",
        "cc.starsector.server.runtime.BanSet$Entry",
        "cc.starsector.server.runtime.ReservedControlFrameCodec",
        "cc.starsector.server.runtime.SeatGraceLane$1",
        "cc.starsector.server.runtime.ChatFrameCodec",
        "cc.starsector.server.runtime.VoiceRelay$MuteCarry", // The ban's mute stash (R S2)
        // reader thread (the /ban chat verb's dispatch + deposit)
        "cc.starsector.server.runtime.AdminBanCommand",
        "cc.starsector.server.runtime.AdminBanCommand$Target",
        "cc.starsector.server.runtime.AdminBanCommand$1",
        "cc.starsector.server.runtime.BanSet$RetimeResult",
        "cc.starsector.server.runtime.OperatorCommandFile$Command",
    };

    /** One warm-up pass: {@code loaded} + {@code missed} == {@link #CLASSES}{@code .length}. */
    public record Result(int loaded, int missed, long millis) {}

    private ModerationVerbWarmup() {}

    /** Load + initialize every {@link #CLASSES} entry once; one summary row; never throws. */
    public static Result touch() {
        long start = System.nanoTime();
        ClassLoader loader = ModerationVerbWarmup.class.getClassLoader();
        int loaded = 0;
        int missed = 0;
        for (String name : CLASSES) {
            try {
                Class.forName(name, true, loader);
                loaded++;
            } catch (ClassNotFoundException | LinkageError failure) {
                missed++;
                System.out.println("npeer-serve moderation_warmup_miss class="
                        + name.substring(name.lastIndexOf('.') + 1)
                        + " err=" + failure.getClass().getSimpleName());
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        System.out.println("npeer-serve moderation_warmup classes=" + loaded + " millis=" + millis
                + " missed=" + missed);
        return new Result(loaded, missed, millis);
    }
}
