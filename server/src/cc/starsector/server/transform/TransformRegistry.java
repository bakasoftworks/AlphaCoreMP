package cc.starsector.server.transform;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The product-owned declarative transform registry — registration, not
 * adoption. Every class-load transform the product depends on is declared here as a fail-closed,
 * fingerprint-keyed entry; {@code cc.starsector.server.premain.CcTransformPremain} consumes the
 * registry and refuses to launch on any fingerprint mismatch, naming the boundary. The registry is
 * infrastructure, not a blanket license (its governance taxonomy):
 *
 * <ul>
 *   <li><b>class (i) crossing-neutering</b> — no-op a side-restricted crossing the headless process
 *       never meaningfully had (windowing/audio/input/UI/preferences); faithfulness-neutral by
 *       construction.</li>
 *   <li><b>class (ii) behavior-correcting</b> — changes what the engine computes; every class (ii)
 *       entry individually passes the unfaithful-shortcut owner gate BEFORE registration.</li>
 * </ul>
 *
 * Nitrox invariant, imported verbatim: no transform ships without a fingerprint + a re-find recipe.
 * Standing Rule 3: entries match by fingerprint/descriptor/hash only — no
 * copied original bytecode sequences live here. Entries: HOOK-0043 (day one) + HOOK-0044
 * + HOOK-0048 (the guarded texel-upload no-op) + the two
 * replay-determinism call-site redirects + the
 * threat-table iteration-order swap.
 */
public final class TransformRegistry {

    /** What the registered transform DOES — dispatches gate semantics and the applier. */
    public enum Kind {
        /**
         * Replace whole method bodies with faithful no-ops (HOOK-0043 semantics, unchanged): every
         * registered method's fingerprint must match the target-jar bytes (ALL-match), applied by
         * {@code RegistryNoopApplier} at class load.
         */
        METHOD_NOOP,
        /**
         * The keyed {@code java.util.Random.<init>()V} pin (HOOK-0044): target is a JDK site
         * ({@code java.base} MODULE — {@code targetJarFileName} is null; bytes come from the RUNNING
         * JVM via {@code getSystemResourceAsStream}), gated by an ANY-OF accepted-fingerprint set
         * (the two bundled JVMs ship different Random ctor bytes — both measured 2026-07-02), armed
         * by a one-shot premain retransform with the scout's structural fail-closed anchor (exactly
         * one {@code nanoTime} replaced + exactly one {@code lxor} tapped, else refuse launch).
         */
        RNG_KEYED_CTOR_PIN,
        /**
         * Swap exactly ONE call instruction inside ONE
         * fingerprinted game method for a static call into the bootstrap-appended holder
         * ({@code cc.starsector.server.rngpin.CcRngRedirect}) of the SAME stack shape, so maxs and
         * frames stay valid. The entry carries ONE {@link TargetMethod} (ALL-match fingerprint,
         * {@code noop} null) and a {@link Redirect} naming the call to swap, its replacement and a
         * fingerprint of the instructions that must FOLLOW it (the anchor that tells the one site
         * apart from any other same-signature call). Applied at class load by {@code CallSiteRedirectApplier}; a site
         * count other than exactly 1 refuses. WHY a call-site swap and not a constructor pin: the
         * engine's other explicitly-seeded {@code Random(long)} constructions must keep their seeds,
         * and only the call site sees the object the key is derived from. Arms ONLY together with
         * {@link #RNG_KEYED_CTOR_PIN} (its holder append + master seed).
         */
        CALL_SITE_REDIRECT,
        /**
         * Inside ONE fingerprinted game method, every
         * {@code NEW <from>} and its {@code INVOKESPECIAL <from>.<init><ctorDesc>} become the same
         * pair on {@code <to>}, a strict subclass of {@code <from>} that declares that constructor,
         * so the stack shape, maxs and frames stay valid. The entry carries ONE {@link TargetMethod}
         * (ALL-match fingerprint, {@code noop} null) and a {@link TypeSwap}; an allocation count or
         * a constructor-call count other than the entry's {@code expectedSites} refuses. Applied at
         * class load by {@code NewTypeSwapApplier}. WHY: a {@code HashMap} keyed by objects without a
         * {@code hashCode} override iterates in identity-hash order, a different order in every JVM,
         * so a strict-max or nearest pick over its values breaks an exact tie one way in a recorded
         * match and another in its re-simulation; constructing a {@code LinkedHashMap} instead makes
         * the walk the engine's own insertion order while every computation stays the engine's.
         * Arms alone: no holder, no seed.
         */
        NEW_TYPE_SWAP
    }

    /** The faithful replacement body for a neutralized method ({@link Kind#METHOD_NOOP} only). */
    public enum NoopKind {
        /** Body becomes a bare {@code return} (pure windowing side-effect, nothing to compute). */
        RETURN_VOID,
        /** Body becomes {@code return false} (the absent off-screen window is never in that state). */
        RETURN_FALSE,
        /**
         * GUARDED no-op (HOOK-0048): the original body is kept and prefixed with
         * {@code if (lastArg != null) return;} — the method runs unchanged when its LAST parameter
         * (a reference type) is null and returns at once otherwise. Why: LWJGL's
         * {@code glTexImage2D(..ByteBuffer pixels)} does two different things by that one argument —
         * {@code pixels == null} ALLOCATES texture storage (the engine's FBO attachment path,
         * whose completeness check the boot reads back) and must stay
         * byte-identical; {@code pixels != null} COPIES texels into the driver, the one thing a
         * headless serve never reads back. The registry stays whole-method + fingerprint-keyed; the
         * applier prepends the guard and streams the verified original instructions through.
         */
        RETURN_VOID_UNLESS_LAST_ARG_NULL
    }

    /**
     * One targeted method: descriptor-grade identity plus the recorded SHA-256 over its
     * {@code Code} attribute {@code code[]} bytes ({@link MethodBytecodeFingerprint}). For
     * {@link Kind#METHOD_NOOP} each row is a distinct method (ALL must match; {@code noop} is the
     * replacement body). For {@link Kind#RNG_KEYED_CTOR_PIN} the rows share one name+descriptor and
     * form the ANY-OF accepted set — one row per legitimate runtime; {@code noop} is null.
     */
    public record TargetMethod(String name, String descriptor, String sha256, NoopKind noop) {
    }

    /**
     * The one call a
     * {@link Kind#CALL_SITE_REDIRECT} entry swaps: {@code fromOpcode} is the JVMS opcode value
     * (182 = invokevirtual, 184 = invokestatic; the registry stays free of the bytecode library),
     * the replacement is always a static call {@code toOwner.toName toDesc} whose stack effect
     * equals the original's.
     *
     * <p>The anchor that tells the one site apart from any other same-signature call is a
     * FINGERPRINT, never a copy of the instructions (Standing Rule 3, class header):
     * {@code followingCount} instructions must come right after the call (labels / frames / line
     * numbers between them are carried through, not counted), and {@code followingSha256} is the
     * lowercase hex SHA-256 over the UTF-8 bytes of their canonical tokens joined by {@code "\n"}
     * (no trailing separator). Token forms: a zero-operand instruction = its upper-case mnemonic
     * ({@code DUP}, {@code LADD}) where the applier's mnemonic table names it, else
     * {@code OP<decimal opcode>}; a constant = {@code LDC:<I|J|F|D|S>:<value>}; a call =
     * {@code <INVOKEVIRTUAL|INVOKESPECIAL|INVOKESTATIC|INVOKEINTERFACE>:<owner>.<name><descriptor>};
     * any other instruction ends the candidate unmatched. {@code followingCount == 0} with an empty
     * {@code followingSha256} = the call alone is the site. The applier refuses a malformed pair at
     * construction (a negative count, a count without a 64-hex hash or the reverse); a well-formed
     * but wrong hash matches no site, which the exactly-one-site rule refuses at class load.
     */
    public record Redirect(
            int fromOpcode,
            String fromOwner,
            String fromName,
            String fromDesc,
            String toOwner,
            String toName,
            String toDesc,
            int followingCount,
            String followingSha256) {
    }

    /**
     * The allocation a {@link Kind#NEW_TYPE_SWAP} entry
     * retypes. {@code fromInternalName} / {@code toInternalName} are JVM internal names
     * ({@code java/util/HashMap}) of bootstrap-loaded classes, so the rewritten class links the
     * replacement from any loader; {@code to} is a strict subclass of {@code from}.
     * {@code ctorDesc} is the constructor descriptor the sites call ({@code ()V}), which {@code to}
     * must declare publicly. {@code expectedSites} (&gt;= 1) is the exact number of
     * {@code NEW from} + {@code INVOKESPECIAL from.<init>ctorDesc} pairs the target method holds;
     * the applier refuses any other count (allocations and constructor calls counted apart).
     */
    public record TypeSwap(String fromInternalName, String toInternalName, String ctorDesc, int expectedSites) {
    }

    /**
     * One registered transform: stable ID, its {@link Kind}, fingerprint-keyed target, its gate,
     * its evidence (the records that back it, named in words),
     * and the re-find recipe a game/JDK update runs.
     * {@code targetJarFileName} is null for JDK-site kinds (the target rides the running JVM's
     * {@code java.base}, not a jar). {@code redirect} is set exactly when the kind is
     * {@link Kind#CALL_SITE_REDIRECT} and {@code typeSwap} exactly when it is
     * {@link Kind#NEW_TYPE_SWAP} (the compact constructor refuses any other pairing).
     */
    public record Entry(
            String id,
            Kind kind,
            String targetClassInternalName,
            String targetJarFileName,
            List<TargetMethod> methods,
            String governanceClass,
            String gate,
            String evidence,
            String refindRecipe,
            Redirect redirect,
            TypeSwap typeSwap) {

        /**
         * A redirect without its Kind, or the Kind without one, is a registry-authoring error.
         * The same mirror rule binds a type swap to {@link Kind#NEW_TYPE_SWAP}.
         */
        public Entry {
            if ((kind == Kind.CALL_SITE_REDIRECT) != (redirect != null)) {
                throw new IllegalArgumentException(id + ": redirect must be set exactly for "
                        + Kind.CALL_SITE_REDIRECT + " (kind=" + kind + ", redirect=" + redirect + ")");
            }
            if ((kind == Kind.NEW_TYPE_SWAP) != (typeSwap != null)) {
                throw new IllegalArgumentException(id + ": typeSwap must be set exactly for "
                        + Kind.NEW_TYPE_SWAP + " (kind=" + kind + ", typeSwap=" + typeSwap + ")");
            }
        }

        /** The kinds that carry neither a call-site redirect nor a type swap. */
        public Entry(String id, Kind kind, String targetClassInternalName, String targetJarFileName,
                List<TargetMethod> methods, String governanceClass, String gate, String evidence,
                String refindRecipe) {
            this(id, kind, targetClassInternalName, targetJarFileName, methods, governanceClass, gate,
                    evidence, refindRecipe, null, null);
        }

        /** A {@link Kind#CALL_SITE_REDIRECT} entry's shape (no type swap). */
        public Entry(String id, Kind kind, String targetClassInternalName, String targetJarFileName,
                List<TargetMethod> methods, String governanceClass, String gate, String evidence,
                String refindRecipe, Redirect redirect) {
            this(id, kind, targetClassInternalName, targetJarFileName, methods, governanceClass, gate,
                    evidence, refindRecipe, redirect, null);
        }

        /** A {@link Kind#NEW_TYPE_SWAP} entry's shape (no redirect). */
        public Entry(String id, Kind kind, String targetClassInternalName, String targetJarFileName,
                List<TargetMethod> methods, String governanceClass, String gate, String evidence,
                String refindRecipe, TypeSwap typeSwap) {
            this(id, kind, targetClassInternalName, targetJarFileName, methods, governanceClass, gate,
                    evidence, refindRecipe, null, typeSwap);
        }

        public String targetClassName() {
            return targetClassInternalName.replace('/', '.');
        }

        /** The classfile resource path of the target inside its jar. */
        public String targetClassResource() {
            return targetClassInternalName + ".class";
        }
    }

    /**
     * HOOK-0043: the LWJGL2 created-Display-gated
     * windowing statics the engine's headless resource loader calls over the Pbuffer substrate.
     * {@code update(...)} only pumps the OS message queue / swaps the absent window's buffer;
     * {@code isCloseRequested()} asks whether the absent window was close-requested (false). Class
     * (i): there is no window for these to act on, so no-opping them is faithfulness-neutral by
     * construction. Fingerprints measured 2026-06-10 against the install's
     * {@code starsector-core\lwjgl.jar} (LWJGL 2.9.3) via {@code TransformRegistryVerifierMain --print}.
     */
    private static final Entry HOOK_0043 = new Entry(
            "HOOK-0043",
            Kind.METHOD_NOOP,
            "org/lwjgl/opengl/Display",
            "lwjgl.jar",
            List.of(
                    new TargetMethod("update", "()V",
                            "7b66e552a131ad49eecb62b4da003dac25ffcf1854b4cf959dbebd720159be8f",
                            NoopKind.RETURN_VOID),
                    new TargetMethod("update", "(Z)V",
                            "d39eda117e6fd11e9d0ccb6a745d8034e8420ef75db566d62ad2e3312a7cf4a0",
                            NoopKind.RETURN_VOID),
                    new TargetMethod("isCloseRequested", "()Z",
                            "0d976aa388418197c0dc12cac07f1bae1f25ca726a8370475790507f77b99a0f",
                            NoopKind.RETURN_FALSE)),
            "i",
            "-Dcc.transform.enable=HOOK-0043 (or env CC_TRANSFORM_ENABLE); absent => premain inert",
            "transform-registry governance decision + hook map row HOOK-0043 + minimal-premain build record "
                    + "(boundary: off-screen GL context proof)",
            "on a game update: run TransformRegistryVerifierMain --print <starsector-core dir>; "
                    + "confirm that update()V/update(Z)V/isCloseRequested()Z still exist on "
                    + "org.lwjgl.opengl.Display and are still pure created-Display-gated windowing "
                    + "calls; update the sha256s here; re-run the install-facing registry verifier");

    /**
     * HOOK-0044: the keyed
     * {@code java.util.Random.<init>()V} pin — replace the ctor's single {@code System.nanoTime()}
     * with the master seed and tap its single {@code lxor} with
     * {@code CcRngKeyed.deriveKeyedSeed(J)J} (order-independent caller-keyed seeds). Class (ii)
     * behavior-correcting, staged server-first and default-OFF.
     * JDK site, so the accepted set carries BOTH bundled runtimes'
     * measured fingerprints (they differ): jdk-23+7 Temurin (contract/build JVM) and the game
     * jre 17.0.10 Zulu17.48+15-CA (serve runtime). ANY-OF gate semantics per {@link Kind}.
     */
    private static final Entry HOOK_0044 = new Entry(
            "HOOK-0044",
            Kind.RNG_KEYED_CTOR_PIN,
            "java/util/Random",
            null,
            List.of(
                    new TargetMethod("<init>", "()V",
                            "15d9db472467218b88f0b06fbba79ab7522e36b30364901669dfcb2726c7d7e8",
                            null),
                    new TargetMethod("<init>", "()V",
                            "f2ace37eb0c996f26763ce30d4c78db36858976c2365b57defbc9aeb28c7a757",
                            null)),
            "ii",
            "-Dcc.transform.enable=HOOK-0044 (or env CC_TRANSFORM_ENABLE) + -Dcc.rngpin.seed=<long>"
                    + " + -Dcc.rngpin.keyed=true (CombatRngPinBoot fail-fasts request!=armed);"
                    + " absent => premain inert, boot byte-identical",
            "transform-registry governance decision + engine-authoritative-first gate (pre-cleared) + "
                    + "hook map row HOOK-0044 + deep RNG pin record (scout proof: determinism study)",
            "on a game/JDK update: run TransformRegistryVerifierMain --print on BOTH bundled JVMs "
                    + "(jdk-23+7 and jre; the install-facing registry verifier covers the game jre); "
                    + "confirm that java.util.Random.<init>()V is still "
                    + "this(seedUniquifier() ^ System.nanoTime()); update the accepted sha256 set here; "
                    + "re-run the install-facing registry verifier + server/build.ps1 (RngKeyedCtorPinContract)");

    /**
     * HOOK-0048: the texel
     * upload the headless serve pays for and never reads back. Under llvmpipe every decoded texture
     * is copied into Mesa's private storage (314–417 MB per serve, measured) although
     * the serve never rasterizes and no sim value reads a texel (sprite/asteroid dims are
     * set from the decoded {@code BufferedImage} BEFORE this call;
     * the 10 GL read-backs are capability/status, none a texel). Class (i)
     * crossing-neutering, same governance as HOOK-0043: the driver simply never receives the pixels;
     * texture ids, dims, colours, mip flags are all still computed and registered.
     *
     * <p>GUARDED, not bare (the build finding): the same {@code (..ByteBuffer)} overload with
     * a NULL buffer is the engine's FBO-attachment storage allocation,
     * followed by a framebuffer-completeness read-back; a whole-body no-op would turn that into an
     * INCOMPLETE_ATTACHMENT at boot. {@link NoopKind#RETURN_VOID_UNLESS_LAST_ARG_NULL} keeps the
     * null path byte-identical and skips only the texel copy. {@code glTexSubImage2D} is the
     * loader's re-upload path (reload of an already-allocated id).
     * Fingerprints measured 2026-09-23 against the install's {@code starsector-core\lwjgl.jar}
     * (LWJGL 2.9.3, class major 49) via {@code MethodBytecodeFingerprint} (the same hash
     * {@code TransformRegistryVerifierMain --print} emits); descriptors confirmed against the class files.
     */
    private static final Entry HOOK_0048 = new Entry(
            "HOOK-0048",
            Kind.METHOD_NOOP,
            "org/lwjgl/opengl/GL11",
            "lwjgl.jar",
            List.of(
                    new TargetMethod("glTexImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V",
                            "82c165bbb9f0055dbaba9107e831a77353809daa3aea18084cfee9ad00d864e7",
                            NoopKind.RETURN_VOID_UNLESS_LAST_ARG_NULL),
                    new TargetMethod("glTexSubImage2D", "(IIIIIIIILjava/nio/ByteBuffer;)V",
                            "3f9d58b252eea8a5ae647fb262db00d62328db3460f6a21d6175089d1557880e",
                            NoopKind.RETURN_VOID_UNLESS_LAST_ARG_NULL)),
            "i",
            "-Dcc.transform.enable=HOOK-0043,HOOK-0048 (or env CC_TRANSFORM_ENABLE); absent => GL11 stock",
            "software-GL substrate decision (amended) + transform-registry governance decision + "
                    + "hook map row HOOK-0048 + GL-context study record",
            "on a game update: run TransformRegistryVerifierMain --print <starsector-core dir>; "
                    + "confirm that glTexImage2D/glTexSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V still exist "
                    + "on org.lwjgl.opengl.GL11 with the ByteBuffer as the LAST parameter and that "
                    + "the engine's FBO texture is still allocated with a null buffer; update the "
                    + "sha256s here; re-run the install-facing registry verifier (checks 8-9) + "
                    + "the serve working-set measurement (-Arms llvmpipe:HOOK-0043:,llvmpipe)");

    /** JVMS opcode values the redirect rows name (the registry stays free of the bytecode library). */
    private static final int JVM_INVOKEVIRTUAL = 182;
    private static final int JVM_INVOKESTATIC = 184;

    /** The call-site redirects' stable ids (the enable knob's values); the landing redirect requires the id redirect. */
    public static final String ID_REDIRECT_ID = "HOOK-0049";
    public static final String LANDING_REDIRECT_ID = "HOOK-0050";
    /** The threat-table order swap's stable id (the enable knob's value; it requires no other entry). */
    public static final String ORDER_SWAP_ID = "HOOK-0051";

    /**
     * HOOK-0049: the ship / fleet-member / person id mint.
     * Outside the campaign {@code Misc.genUID()} is {@code UUID.randomUUID().toString()},
     * and every combat {@code Ship} takes its id from it; that id seeds
     * the AI's point-move randomness and keys the fighter landing slot (HOOK-0050), so a
     * replay of a recorded match cannot even start equal while it stays random. The ONE
     * {@code invokestatic UUID.randomUUID()} becomes {@code CcRngRedirect.keyedUuid()} (same
     * descriptor, stack-neutral; the following {@code toString} is untouched): a version-4 UUID
     * derived from the master seed + the CALLER class (ships, fleet members and persons each own a
     * stream, so a wall-clock-timed pass minting fleet members cannot shift the ship ids) + that
     * class's per-caller index. Class (ii) behavior-correcting, server JVM only, default OFF, the
     * installed game untouched. Fingerprint measured 2026-10-14 against the install's
     * {@code starsector-core\starfarer.api.jar} (class major 61) via {@code MethodBytecodeFingerprint};
     * the site confirmed in the class file at bytecode 48.
     */
    private static final Entry HOOK_0049 = new Entry(
            ID_REDIRECT_ID,
            Kind.CALL_SITE_REDIRECT,
            "com/fs/starfarer/api/util/Misc",
            "starfarer.api.jar",
            List.of(new TargetMethod("genUID", "()Ljava/lang/String;",
                    "635db3326765a254ecbd6b05fe5cc80514af94a488d0ca0ff82b3cc0fcbab977", null)),
            "ii",
            "-Dcc.transform.enable=" + HOOK_0044.id() + "," + ID_REDIRECT_ID + " (or env CC_TRANSFORM_ENABLE)"
                    + " + -Dcc.rngpin.seed=<long> + -Dcc.rngpin.keyed=true; refused without " + HOOK_0044.id()
                    + " (its bootstrap holder append + master seed); absent => premain inert, genUID stock",
            "transform-registry governance decision + RNG-determinism pin pre-clearance (class (ii), recorded) + "
                    + "hook map row " + ID_REDIRECT_ID + " + replay-determinism design record (caller-keyed id stream)",
            "on a game update: run TransformRegistryVerifierMain --print <starsector-core dir>; confirm "
                    + "that com.fs.starfarer.api.util.Misc.genUID()Ljava/lang/String; still mints outside the "
                    + "campaign with exactly ONE invokestatic java/util/UUID.randomUUID()Ljava/util/UUID; "
                    + "(disassemble the method); update the sha256 here; re-run the install-facing registry "
                    + "verifier + the keyed-pin contract rows",
            new Redirect(JVM_INVOKESTATIC, "java/util/UUID", "randomUUID", "()Ljava/util/UUID;",
                    "cc/starsector/server/rngpin/CcRngRedirect", "keyedUuid", "()Ljava/util/UUID;",
                    0, ""));

    /**
     * HOOK-0050: the fighter landing
     * slot. A returning fighter is removed from its wing list first, so {@code getLandingLocation}
     * normally takes the fallback that seeds a fresh {@code Random} from {@code fighter.hashCode()}
     * and draws the slot from it — an IDENTITY hash ({@code Ship} has no
     * {@code hashCode} override), different every run. The ONE {@code invokevirtual
     * Object.hashCode()I} whose following instructions carry this entry's anchor fingerprint
     * becomes {@code CcRngRedirect.landingKey(Object)I} (pops one reference, pushes one int:
     * stack-neutral) = {@code fighter.getId().hashCode()}, deterministic only while the id redirect
     * (HOOK-0049) pins the ids — so this entry refuses to arm without it. The seed arithmetic and
     * the draw stay the engine's own. Method fingerprint measured 2026-10-14 against the install's
     * engine jar (class major 61); the anchor fingerprint computed 2026-10-15 over the canonical
     * tokens the applier matched at that site, which the class file holds at bytecode 42 (the
     * anchor does not depend on the offset); no bridge method carries it. The anchor's instruction
     * sequence itself lives only in the private hook map row, never in shipped source.
     */
    private static final Entry HOOK_0050 = new Entry(
            LANDING_REDIRECT_ID,
            Kind.CALL_SITE_REDIRECT,
            "com/fs/starfarer/combat/entities/ship/A/C",
            "starfarer_obf.jar",
            List.of(new TargetMethod("getLandingLocation",
                    "(Lcom/fs/starfarer/api/combat/ShipAPI;)Lorg/lwjgl/util/vector/Vector2f;",
                    "0e7316d0e19127d31b03d45dfd9173a6e91cf7d68f7b5c6a133d4b8dec1aa4c3", null)),
            "ii",
            "-Dcc.transform.enable=" + HOOK_0044.id() + "," + ID_REDIRECT_ID + "," + LANDING_REDIRECT_ID
                    + " (or env CC_TRANSFORM_ENABLE) + -Dcc.rngpin.seed=<long> + -Dcc.rngpin.keyed=true; refused"
                    + " without " + HOOK_0044.id() + " and without " + ID_REDIRECT_ID + " (the landing key is the"
                    + " pinned ship id); absent => premain inert, landing slot stock",
            "transform-registry governance decision + RNG-determinism pin pre-clearance (class (ii), recorded) + "
                    + "hook map row " + LANDING_REDIRECT_ID + " + replay-determinism design record (fighter landing seed)",
            "on a game update: run TransformRegistryVerifierMain --print <starsector-core dir>; locate the "
                    + "fighter landing-slot method again (disassemble the carrier landing-bay class): "
                    + "confirm exactly ONE invokevirtual java/lang/Object.hashCode()I whose following-instruction "
                    + "fingerprint (count + sha256 over the canonical tokens the Redirect record documents) "
                    + "equals this entry's, and no bridge carrying it - the instruction sequence itself lives "
                    + "in the private hook map row, never here; update the class, method, descriptor, method "
                    + "sha256 and the anchor fingerprint; re-run the install-facing registry verifier + the "
                    + "keyed-pin contract rows (the one-site row counts the fingerprint-matching sites)",
            new Redirect(JVM_INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I",
                    "cc/starsector/server/rngpin/CcRngRedirect", "landingKey", "(Ljava/lang/Object;)I",
                    4, "94f2d472d0d227bd9757397a9c2364a4c5fafa9dda80997e68f26f6cda4e2fc3"));

    /**
     * HOOK-0051: the threat table's iteration order. The
     * engine's threat module builds its per-ship threat tables as two {@code HashMap}s keyed by
     * {@code Ship} in its constructor, and {@code Ship} has no {@code hashCode} override, so each
     * table walks in IDENTITY-hash order, different every run; every strict-max or nearest pick
     * over its values (the omni-shield facing among them) resolves an exact tie by that order, so
     * a re-simulation of a recorded match can face a shield differently from the recording with
     * every input equal. Both constructions become {@code LinkedHashMap} (a {@code HashMap}
     * subclass with the same no-argument constructor; both fields are declared {@code Map}): the
     * walk becomes the engine's own insertion order, itself one of the orders an identity hash may
     * produce, and every computation stays the engine's. Class (ii) behavior-correcting, server JVM
     * only, default OFF, the installed game untouched; needs no other entry (no holder, no seed).
     * Method fingerprint measured 2026-10-15 against the install's engine jar (class major 61) via
     * {@code MethodBytecodeFingerprint}; the class file holds the two pairs at bytecodes 5 / 9 and
     * 38 / 42 of the constructor, no other method of the class names {@code HashMap}, and the
     * constructor carries no stack-map frame. The evidence field below states the governance
     * decision in words only.
     *
     */
    private static final Entry HOOK_0051 = new Entry(
            ORDER_SWAP_ID,
            Kind.NEW_TYPE_SWAP,
            "com/fs/starfarer/combat/ai/D",
            "starfarer_obf.jar",
            List.of(new TargetMethod("<init>",
                    "(Lcom/fs/starfarer/combat/entities/Ship;Lcom/fs/starfarer/combat/ai/movement/maneuvers/M$o;"
                            + "Lcom/fs/starfarer/combat/ai/movement/A;)V",
                    "52799650b5106a4fe139eacf46b61bdeeb17b457b47308494811ba55b073d8d7", null)),
            "ii",
            "-Dcc.transform.enable=" + ORDER_SWAP_ID + " (or env CC_TRANSFORM_ENABLE); no prerequisite (no holder,"
                    + " no seed); absent => premain inert, the threat table walks in identity-hash order (stock)",
            "transform-registry governance decision: " + ORDER_SWAP_ID + " stays as built and joins the default-ON"
                    + " pin set behind a host OFF switch (class (ii), recorded) + hook map row " + ORDER_SWAP_ID
                    + " + replay-determinism design record (threat-table iteration order)",
            "on a game update: run TransformRegistryVerifierMain --print <starsector-core dir>; disassemble the "
                    + "threat-module class (locate it again if its class name moved: the class whose constructor "
                    + "builds the per-ship threat tables): confirm its constructor still holds exactly TWO "
                    + "new java/util/HashMap + invokespecial java/util/HashMap.<init>()V pairs and that nothing else "
                    + "in the class names HashMap; update the class, the method sha256 and expectedSites here; re-run "
                    + "the install-facing registry verifier + the keyed-pin contract rows",
            new TypeSwap("java/util/HashMap", "java/util/LinkedHashMap", "()V", 2));

    private static final List<Entry> ENTRIES =
            List.of(HOOK_0043, HOOK_0044, HOOK_0048, HOOK_0049, HOOK_0050, HOOK_0051);

    private static final Map<String, Entry> BY_ID =
            ENTRIES.stream().collect(Collectors.toUnmodifiableMap(Entry::id, e -> e));

    private TransformRegistry() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** The registered entry for the ID, or null — callers refuse on null (unknown ID is a boundary). */
    public static Entry byId(String id) {
        return BY_ID.get(id);
    }
}
