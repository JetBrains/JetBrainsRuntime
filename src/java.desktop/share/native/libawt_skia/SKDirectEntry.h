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
#ifndef SKDirectEntry_h_Included
#define SKDirectEntry_h_Included

// The bracket of a direct JNI entry (SKRenderQueue.flushAndInvokeNow: an operation larger than the render
// buffer, drawn by the pipe's own native: SKRenderPipe.drawPolyNative, SKMaskFill.maskFillNative,
// SKTextPipe.drawGlyphListNative). The entry lock is taken and the platform scope opened for the entry's
// life, in this order (released in the other); end() ends the flush as the render queue's flushBuffer
// does, before either is released, and returns what flushBuffer returns: whether the flush holds its
// recordings, for the flusher's timeout flush (SKRenderQueue.directEntryDone).
//     SKDirectEntry entry(env);
//     ... draw ...
//     return entry.end();

#include "SKEntryLock.h"
#include "SKPlatform.h"

#include "jni.h"

#include <mutex>

class SKDirectEntry {
public:
    explicit SKDirectEntry(JNIEnv *env) : m_lock(SKEntryLock::mutex()), m_env(env) {}
    SKDirectEntry(const SKDirectEntry &) = delete;
    SKDirectEntry &operator=(const SKDirectEntry &) = delete;

    // The end of the flush (SKPresentation::endOfFlush): JNI_TRUE if it holds its recordings.
    [[nodiscard]] jboolean end();

private:
    std::lock_guard<std::recursive_mutex> m_lock;   // first: the platform scope opens under it
    SKPlatformScope m_scope;
    JNIEnv *m_env;
};

#endif /* SKDirectEntry_h_Included */
