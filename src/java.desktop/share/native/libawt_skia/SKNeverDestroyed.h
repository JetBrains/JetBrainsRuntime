/*
 * Copyright 2026 JetBrains s.r.o.
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
#ifndef SKNeverDestroyed_h_Included
#define SKNeverDestroyed_h_Included

// The backend's objects with static storage that are never destroyed: the configurations' Graphite
// contexts and everything that holds Graphite or device objects (the render state, the rect batch,
// the last glyph list, the folded presents, the platform's instances, devices and graphics APIs),
// and the entry lock. At process exit their destructors would run during DLL detach, under the loader
// lock and after the other threads were killed: releasing a context waits for the GPU, shuts the
// resource cache down and frees the API's objects, and Dawn's Direct3D 12 device fails fast released
// there. The object lives in static storage (at a link-time address, no heap allocation, no pointer
// to load) and is constructed in place where the variable is initialized; the wrapper has no
// destructor that would destroy it. Skia's SkNoDestructor does the same but is in Skia's sources
// (src/core), not its public headers.

#include <new>
#include <utility>

template <typename T>
class SKNeverDestroyed {
public:
    template <typename... Arguments>
    explicit SKNeverDestroyed(Arguments &&...arguments) {
        new (m_storage) T(std::forward<Arguments>(arguments)...);
    }

    SKNeverDestroyed(const SKNeverDestroyed &) = delete;
    SKNeverDestroyed &operator=(const SKNeverDestroyed &) = delete;

    [[nodiscard]] T *get() { return std::launder(reinterpret_cast<T *>(m_storage)); }
    [[nodiscard]] const T *get() const { return std::launder(reinterpret_cast<const T *>(m_storage)); }
    [[nodiscard]] T &operator*() { return *get(); }
    [[nodiscard]] const T &operator*() const { return *get(); }
    T *operator->() { return get(); }
    const T *operator->() const { return get(); }

private:
    alignas(T) unsigned char m_storage[sizeof(T)];
};

#endif /* SKNeverDestroyed_h_Included */
