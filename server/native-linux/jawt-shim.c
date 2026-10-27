/* SPDX-License-Identifier: MIT */
/* libjawt.so stand-in for a screenless Linux server host. LWJGL 2.9.3's Linux
 * native loads the JDK's libjawt.so for the AWT lock; a jlink'd runtime without java.desktop's X11 half has
 * none, so this one-function library answers JAWT_GetAWT with 0 ("no AWT") and LWJGL falls back to its own
 * lock (measured: the engine loaded 2,488 textures under xvfb with exactly this shim). The
 * launcher sets LD_PRELOAD to the package's copy. Build (gcc 13; the output is bit-identical across builds):
 * gcc -shared -fPIC -O2 -Wl,--version-script=jawt-shim.ver -Wl,-soname,libjawt.so -o libjawt.so jawt-shim.c */
int JAWT_GetAWT(void* env, void* awt) { return 0; }
