package cc.starsector.server.runtime;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * The engine's control bindings as TEXT, shared by the kit (a binding string -> the key code it polls and sends at
 * HELLO) and the serve (a seat's HELLO key code -> the string the engine shows for it), so a by-name control read
 * redirected on the serve hears exactly the text the pilot's own game shows. WHY a replica: the engine exposes a
 * binding only as a string; that string is the LWJGL {@code KEY_*} suffix except for the entries below, which the
 * engine names its own way (measured from the install, 0.98a-RC8); the kit cannot call the engine's reverse lookup
 * and has no reflection. Pure tables, java.base only: both jars pack it. A mouse binding is one of the mouse names
 * (buttons 0..7 expressible); a modifier-prefixed binding ("Ctrl-", "Alt-", "Shift-"), a wheel binding, a button
 * past 7 and an unknown string are unexpressible = 0 (unmapped). A duplicate name ("Shift", "Ctrl", "Alt") resolves
 * to its FIRST code (the left key, the engine's own default for the strafe key); the engine's reverse map keeps the
 * last, so a pilot bound to the right modifier is sent the left code — the string API is lossy. Main-written.
 *
 */
public final class PilotControlNames {
    public static final int CONTROLS = 16;
    /** The highest LWJGL key code a keymap byte may carry ({@code KEY_SLEEP}). */
    public static final int KEY_MAX = 0xDF;
    public static final int MOUSE_BASE = 0xF0;
    public static final int MOUSE_BUTTONS = 8;
    private static final int[] OVERRIDE_CODES = {41, 181, 55, 78, 74, 28, 42, 54, 29, 157, 219, 220, 56, 184, 183, 57, 1, 15};
    private static final String[] OVERRIDE_NAMES = {"Tilde", "/", "*", "+", "-", "Enter", "Shift", "Shift", "Ctrl", "Ctrl",
            "Ctrl", "Ctrl", "Alt", "Alt", "Print Screen", "Space", "Esc", "Tab"};
    private static final String[] MOUSE_NAMES = {"Left click", "Right click", "Middle click", "MOUSE3", "MOUSE4", "MOUSE5",
            "MOUSE6", "MOUSE7"};

    private PilotControlNames() {
    }

    /** The keymap byte domain: 0, 1..{@link #KEY_MAX}, {@link #MOUSE_BASE}..+7. */
    public static boolean lawful(int b) {
        return b == 0 || (b >= 1 && b <= KEY_MAX) || (b >= MOUSE_BASE && b < MOUSE_BASE + MOUSE_BUTTONS);
    }

    public static String overrideNameOrNull(int code) {
        for (int i = 0; i < OVERRIDE_CODES.length; i++) {
            if (OVERRIDE_CODES[i] == code) {
                return OVERRIDE_NAMES[i];
            }
        }
        return null;
    }

    /** The FIRST code of an override name (the left modifier); -1 for any other string. */
    public static int overrideCodeOrMinusOne(String name) {
        if (name != null) {
            for (int i = 0; i < OVERRIDE_NAMES.length; i++) {
                if (OVERRIDE_NAMES[i].equals(name)) {
                    return OVERRIDE_CODES[i];
                }
            }
        }
        return -1;
    }

    public static String mouseNameOrNull(int button) {
        return button >= 0 && button < MOUSE_BUTTONS ? MOUSE_NAMES[button] : null;
    }

    public static int mouseButtonOrMinusOne(String name) {
        if (name != null) {
            for (int b = 0; b < MOUSE_BUTTONS; b++) {
                if (MOUSE_NAMES[b].equals(name)) {
                    return b;
                }
            }
        }
        return -1;
    }

    public static boolean hasModifierPrefix(String name) {
        return name != null && (name.startsWith("Ctrl-") || name.startsWith("Alt-") || name.startsWith("Shift-"));
    }

    /** The control bit of an engine control-enum name among {@link PilotIntentFrame#CONTROL_KEY_NAMES}; -1 otherwise. */
    public static int controlIndexOrMinusOne(String enumName) {
        if (enumName != null) {
            String[] names = PilotIntentFrame.CONTROL_KEY_NAMES;
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(enumName)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * One binding string -> its keymap byte: 0 for null / empty / a modifier prefix; a mouse name -> 0xF0+b; an
     * override name -> its first code; else {@code keyIndex} (the caller's {@code Keyboard.getKeyIndex}) when 1..0xDF.
     */
    public static int keymapByteOf(String bindingOrNull, ToIntFunction<String> keyIndex) {
        if (bindingOrNull == null || bindingOrNull.isEmpty() || hasModifierPrefix(bindingOrNull)) {
            return 0;
        }
        int b = mouseButtonOrMinusOne(bindingOrNull);
        if (b >= 0) {
            return MOUSE_BASE + b;
        }
        int code = overrideCodeOrMinusOne(bindingOrNull);
        if (code < 0) {
            code = keyIndex.applyAsInt(bindingOrNull);
        }
        return code >= 1 && code <= KEY_MAX ? code : 0;
    }

    /**
     * The 16-byte HELLO keymap: {@code controlString(name)} per {@link PilotIntentFrame#CONTROL_KEY_NAMES} entry (the
     * caller's {@code SettingsAPI.getControlStringForEnumName}); a null or a RuntimeException from either lookup = 0
     * for that entry, never a throw out.
     */
    public static byte[] resolveKeymap(Function<String, String> controlString, ToIntFunction<String> keyIndex) {
        byte[] km = new byte[CONTROLS];
        String[] names = PilotIntentFrame.CONTROL_KEY_NAMES;
        for (int i = 0; i < CONTROLS && i < names.length; i++) {
            int b = 0;
            try {
                b = keymapByteOf(controlString.apply(names[i]), keyIndex);
            } catch (RuntimeException unresolvable) {
                b = 0;
            }
            km[i] = (byte) b;
        }
        return km;
    }

    /**
     * A keymap byte -> the engine-format string: null for 0 or an unlawful byte; the mouse name; the override name;
     * else {@code lwjglNameOrNull} (the caller's {@code Keyboard.getKeyName(code)}).
     */
    public static String nameOrNull(int keymapByte, String lwjglNameOrNull) {
        if (keymapByte <= 0 || !lawful(keymapByte)) {
            return null;
        }
        if (keymapByte >= MOUSE_BASE) {
            return MOUSE_NAMES[keymapByte - MOUSE_BASE];
        }
        String o = overrideNameOrNull(keymapByte);
        return o != null ? o : lwjglNameOrNull;
    }

    /** Byte {@code i} (0..15) of a packed keymap ({@code lo} = bytes 0..7, {@code hi} = bytes 8..15). */
    public static int entry(long lo, long hi, int i) {
        return (int) (((i < 8 ? lo : hi) >>> ((i & 7) << 3)) & 0xFF);
    }

    /** The install's default bindings of the 16 controls (W S C A D Shift Q E LMB RMB F V Z X R U): goldens + drills. */
    public static byte[] vanillaDefaults() {
        return new byte[] {17, 31, 46, 30, 32, 42, 16, 18, (byte) 0xF0, (byte) 0xF1, 33, 47, 44, 45, 19, 22};
    }
}
