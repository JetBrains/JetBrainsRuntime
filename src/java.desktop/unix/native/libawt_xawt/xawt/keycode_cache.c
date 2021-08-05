/*
 * Copyright 2021 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "keycode_cache.h"
#include "awt.h"

#ifdef DEBUG
#include <stdio.h>
#endif

#ifdef USE_KEYCODE_CACHE

/**
 * Keeps the KeyCode -> KeySym mapping.
 */
typedef struct {
    KeySym* symbols;        // array of KeySym indexed by the key code with min_code corresponding to index 0
    int     syms_per_code;  // number of elements in 'symbols' corresponding to one key code
    int     min_code;       // minimum valid key code (typically 8)
    int     max_code;       // maximum valid key code (typically 255)
} KeyCodeCache;

static KeyCodeCache keycode_cache = {0};

#ifdef DEBUG
static void
dump_keycode_cache(const KeyCodeCache* cache) {
    fprintf(stderr, "KeyCodeCache dump\n");
    if (cache->symbols == NULL) {
        fprintf(stderr, "-- empty --\n");
    } else {
        fprintf(stderr, "syms_per_code=%d, min_code=%d, max_code=%d\n",
                cache->syms_per_code, cache->min_code, cache->max_code);
        for(int i = cache->min_code; i <= cache->max_code; i++) {
            fprintf(stderr, "0x%02x --", i);
            for(int j = 0; j < cache->syms_per_code; j++) {
                const int sym_index = (i - cache->min_code)*cache->syms_per_code + j;
                fprintf(stderr, "%04d - ", cache->symbols[sym_index]);
            }
            fprintf(stderr, "\n");
        }
    }
}
#endif // DEBUG

/**
 * Clears the cache and frees memory, if allocated.
 *
 * NB: not thread safe and is supposed to be called only when holding the AWT lock.
 */
extern void
resetKeyCodeCache(void) {
    if (keycode_cache.symbols) {
        XFree(keycode_cache.symbols);
    }
    keycode_cache = (KeyCodeCache){0};
}

/**
 * Translates the given keycode to the corresponding KeySym at the given index.
 * Caches the mapping for all valid key codes by using just one XGetKeyboardMapping() Xlib call,
 * which greatly reduces delays when working with a remote X server.
 *
 * NB: not thread safe and is supposed to be called only when holding the AWT lock.
 */
extern KeySym
keycodeToKeysym(Display* display, KeyCode keycode, int index) {
    if (!keycode_cache.symbols) {
        XDisplayKeycodes(display, &keycode_cache.min_code, &keycode_cache.max_code);
        const int count_all = keycode_cache.max_code - keycode_cache.min_code + 1;
        keycode_cache.symbols = XGetKeyboardMapping(display, keycode_cache.min_code, count_all, &keycode_cache.syms_per_code);
        // NB: this may not always get free'ed
    }

    if (keycode_cache.symbols) {
        const Boolean code_within_range  = (keycode >= keycode_cache.min_code && keycode <= keycode_cache.max_code);
        const Boolean index_within_range = (index >= 0 && index < keycode_cache.syms_per_code);
        if (code_within_range && index_within_range) {
            const int sym_index = (keycode - keycode_cache.min_code)*keycode_cache.syms_per_code + index;
            KeySym sym = keycode_cache.symbols[sym_index];
            return sym;
        }
    }

    return NoSymbol;
}

#endif /* USE_KEYCODE_CACHE */
