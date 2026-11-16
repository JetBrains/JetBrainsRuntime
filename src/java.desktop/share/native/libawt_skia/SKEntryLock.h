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
#ifndef SKEntryLock_h_Included
#define SKEntryLock_h_Included

// The backend's state belongs to the flusher thread while it runs a flush, under the render queue
// lock. Some entry points run on other threads without that lock: the configurations' creation and
// disposal (Win32SKGraphicsConfig.getConfigInfo, disposeConfigInfo from the Disposer), the
// device-loss queries of the screen update manager and the simulated device loss of the tests,
// and the counters the tests read. Every JNI entry point that touches backend or platform state
// holds this, so that those never run in the middle of a flush (recursive: a flush calls back
// into Java, which may call an entry point).

#include "SKNeverDestroyed.h"

#include <mutex>

namespace SKEntryLock {

[[nodiscard]] inline std::recursive_mutex &mutex() {
    static SKNeverDestroyed<std::recursive_mutex> entryMutex;   // entry points may run during exit
    return *entryMutex;
}

} // namespace SKEntryLock

#define SK_ENTRY_LOCK std::lock_guard<std::recursive_mutex> skEntryLock(SKEntryLock::mutex())

#endif /* SKEntryLock_h_Included */
