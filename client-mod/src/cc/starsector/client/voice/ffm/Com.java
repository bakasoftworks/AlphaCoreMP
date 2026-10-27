package cc.starsector.client.voice.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Minimal COM / Win32 plumbing over the Java 22+ FFM API ({@code java.lang.foreign}), Windows x64
 * only, ported near-verbatim from a game-free
 * prototype.
 *
 * <p>WHY: the voice playback dragon needs WASAPI ({@code IAudioClient}), which has no JDK binding.
 * COM interfaces are reached the manual way: read the vtable pointer at offset 0 of the object,
 * read the function pointer at {@code slot * 8}, and downcall it with {@code this} as the leading
 * ADDRESS argument. Downcall handles are created ONCE per signature (leading target-address form
 * of {@link Linker#downcallHandle(FunctionDescriptor, Linker.Option...)}) so the per-period feeder
 * never pays for code generation; only the two pointer reads happen per call.
 *
 * <p>CLASS-VERSION LAW: this package is compiled {@code --release 22} (major 66) by the Temurin-25
 * toolchain and rides the mod jar inertly on the stock Zulu-17 game JVM — the ONLY 17-side
 * reference is one {@code invokestatic} in {@code VoiceSinkFactory} whose declared types are all
 * 17 types, so a 17 JVM never loads any class here (lazy resolution, JVMS section 5.4.3.3). Nothing in
 * this package may be named as a field/local/cast/catch type by 17-compiled code.
 *
 * <p>NATIVE-SEAM LAW (crash-class, not
 * machine-class): nothing externally derived crosses this boundary except inert PCM sample
 * values. Every length, period, format pointer, GUID and handle passed here is a compile-time
 * constant or a value clamped locally from what the native side itself returned. Classloader-B
 * legal: no {@code java.io} / {@code java.nio.file} / reflection ({@code check-b-deny-refs.ps1}).
 */
final class Com {

    static final Linker LINKER = Linker.nativeLinker();

    /** HRESULT names for the codes the sink can plausibly hit (audioclient.h AUDCLNT_E_* + COM). */
    static String hresultName(int hr) {
        switch (hr) {
            case 0x88890001: return "AUDCLNT_E_NOT_INITIALIZED";
            case 0x88890002: return "AUDCLNT_E_ALREADY_INITIALIZED";
            case 0x88890004: return "AUDCLNT_E_DEVICE_INVALIDATED";
            case 0x88890006: return "AUDCLNT_E_BUFFER_TOO_LARGE";
            case 0x88890007: return "AUDCLNT_E_OUT_OF_ORDER";
            case 0x88890008: return "AUDCLNT_E_UNSUPPORTED_FORMAT";
            case 0x88890009: return "AUDCLNT_E_INVALID_SIZE";
            case 0x8889000A: return "AUDCLNT_E_DEVICE_IN_USE";
            case 0x8889000B: return "AUDCLNT_E_BUFFER_OPERATION_PENDING";
            case 0x88890010: return "AUDCLNT_E_SERVICE_NOT_RUNNING";
            case 0x88890011: return "AUDCLNT_E_EVENTHANDLE_NOT_EXPECTED";
            case 0x88890014: return "AUDCLNT_E_EVENTHANDLE_NOT_SET";
            case 0x88890015: return "AUDCLNT_E_INCORRECT_BUFFER_SIZE";
            case 0x88890016: return "AUDCLNT_E_BUFFER_SIZE_ERROR";
            case 0x88890017: return "AUDCLNT_E_CPUUSAGE_EXCEEDED";
            case 0x88890018: return "AUDCLNT_E_BUFFER_ERROR";
            case 0x88890019: return "AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED";
            case 0x88890020: return "AUDCLNT_E_INVALID_DEVICE_PERIOD";
            case 0x88890021: return "AUDCLNT_E_INVALID_STREAM_FLAG";
            case 0x88890026: return "AUDCLNT_E_RESOURCES_INVALIDATED";
            case 0x88890028: return "AUDCLNT_E_ENGINE_PERIODICITY_LOCKED";
            case 0x88890029: return "AUDCLNT_E_ENGINE_FORMAT_LOCKED";
            case 0x80004002: return "E_NOINTERFACE";
            case 0x80070057: return "E_INVALIDARG";
            case 0x80004005: return "E_FAIL";
            case 0x80010106: return "RPC_E_CHANGED_MODE";
            case 0x800401F0: return "CO_E_NOTINITIALIZED";
            default: return "";
        }
    }

    static String hex(int hr) {
        String n = hresultName(hr);
        String h = Integer.toHexString(hr).toUpperCase();
        return "0x" + "00000000".substring(Math.min(8, h.length())) + h + (n.isEmpty() ? "" : " (" + n + ")");
    }

    /** Thrown for any negative HRESULT; carries the code so the fallback can name it (numbers only). */
    static final class HrException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int hr;

        HrException(String what, int hr) {
            super(what + " " + hex(hr));
            this.hr = hr;
        }
    }

    static int check(String what, int hr) {
        if (hr < 0) {
            throw new HrException(what, hr);
        }
        return hr;
    }

    // ---- Win32 flat functions (ole32 / kernel32 / avrt) -------------------------------------

    final Arena arena;
    final MethodHandle coInitializeEx;     // (void* reserved, DWORD coinit) -> HRESULT
    final MethodHandle coUninitialize;     // () -> void
    final MethodHandle coCreateInstance;   // (REFCLSID, IUnknown* outer, DWORD ctx, REFIID, void** out) -> HRESULT
    final MethodHandle coTaskMemFree;      // (void*) -> void
    final MethodHandle createEventW;       // (SECURITY_ATTRIBUTES*, BOOL manual, BOOL initial, LPCWSTR name) -> HANDLE
    final MethodHandle waitForSingleObject;// (HANDLE, DWORD ms) -> DWORD
    final MethodHandle closeHandle;        // (HANDLE) -> BOOL
    final MethodHandle avSetMmThreadCharacteristicsW;   // (LPCWSTR task, DWORD* index) -> HANDLE  (null if avrt missing)
    final MethodHandle avRevertMmThreadCharacteristics; // (HANDLE) -> BOOL

    Com(Arena arena) {
        this.arena = arena;
        SymbolLookup ole32 = SymbolLookup.libraryLookup("ole32", arena);
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
        coInitializeEx = fn(ole32, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        coUninitialize = fn(ole32, "CoUninitialize", FunctionDescriptor.ofVoid());
        coCreateInstance = fn(ole32, "CoCreateInstance",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        coTaskMemFree = fn(ole32, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));
        createEventW = fn(kernel32, "CreateEventW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        waitForSingleObject = fn(kernel32, "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        closeHandle = fn(kernel32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle set = null;
        MethodHandle revert = null;
        try {
            SymbolLookup avrt = SymbolLookup.libraryLookup("avrt", arena);
            set = fn(avrt, "AvSetMmThreadCharacteristicsW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            revert = fn(avrt, "AvRevertMmThreadCharacteristics", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        } catch (RuntimeException e) {
            // avrt.dll absent (server core SKUs) -> the feeder just runs without MMCSS; reported, not fatal.
        }
        avSetMmThreadCharacteristicsW = set;
        avRevertMmThreadCharacteristics = revert;
    }

    private static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment sym = lib.find(name).orElseThrow(() -> new IllegalStateException("symbol missing: " + name));
        return LINKER.downcallHandle(sym, fd);
    }

    /** CoInitializeEx on the CALLING thread; returns the HRESULT (S_FALSE = already initialised). */
    int coInitialize(int coinit) throws Throwable {
        return (int) coInitializeEx.invokeExact(MemorySegment.NULL, coinit);
    }

    void coUninit() throws Throwable {
        coUninitialize.invokeExact();
    }

    // ---- COM vtable dispatch ----------------------------------------------------------------

    /** Address-taking downcall handle: leading MemorySegment = function pointer; build once, reuse. */
    static MethodHandle method(FunctionDescriptor fd) {
        return LINKER.downcallHandle(fd);
    }

    /** Function pointer at vtable slot {@code slot} of COM object {@code obj} (IUnknown = slots 0..2). */
    static MemorySegment fp(MemorySegment obj, int slot) {
        MemorySegment vtbl = obj.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return vtbl.reinterpret((slot + 1L) * ADDRESS.byteSize()).get(ADDRESS, slot * ADDRESS.byteSize());
    }

    private static final MethodHandle RELEASE = method(FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** IUnknown::Release (slot 2). Null / NULL-address objects are ignored so finally blocks stay simple. */
    static void release(MemorySegment obj) {
        if (obj == null || obj.address() == 0) {
            return;
        }
        try {
            int ignored = (int) RELEASE.invokeExact(fp(obj, 2), obj);
        } catch (Throwable t) {
            // Best effort at teardown.
        }
    }

    /** A fresh 8-byte slot for an out-pointer written by a native call. */
    MemorySegment ptrSlot() {
        return arena.allocate(ADDRESS);
    }

    static MemorySegment deref(MemorySegment slot) {
        return slot.get(ADDRESS, 0);
    }

    // ---- GUIDs (u32, u16, u16, u8[8]; text form "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX") ------

    MemorySegment guid(String text) {
        String[] p = text.split("-");
        if (p.length != 5) {
            throw new IllegalArgumentException("bad GUID");
        }
        MemorySegment g = arena.allocate(16);
        g.set(JAVA_INT, 0, (int) Long.parseLong(p[0], 16));
        g.set(JAVA_SHORT, 4, (short) Integer.parseInt(p[1], 16));
        g.set(JAVA_SHORT, 6, (short) Integer.parseInt(p[2], 16));
        String tail = p[3] + p[4];
        for (int i = 0; i < 8; i++) {
            g.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(tail.substring(2 * i, 2 * i + 2), 16));
        }
        return g;
    }

    static boolean guidEquals(MemorySegment a, MemorySegment b) {
        for (int i = 0; i < 16; i++) {
            if (a.get(JAVA_BYTE, i) != b.get(JAVA_BYTE, i)) {
                return false;
            }
        }
        return true;
    }

    /** NUL-terminated UTF-16LE string for LPCWSTR parameters (compile-time constants only). */
    MemorySegment wstr(String s) {
        MemorySegment m = arena.allocate((s.length() + 1L) * 2);
        for (int i = 0; i < s.length(); i++) {
            m.set(JAVA_SHORT, 2L * i, (short) s.charAt(i));
        }
        m.set(JAVA_SHORT, 2L * s.length(), (short) 0);
        return m;
    }
}
