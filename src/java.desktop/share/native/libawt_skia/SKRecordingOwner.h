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
#ifndef SKRecordingOwner_h_Included
#define SKRecordingOwner_h_Included

// What the caches of a context (SKGlyphCache, SKMaskCache, SKImageAtlas) need of the context that owns
// them: the Graphite recorder they record their atlases with, and the recordings it makes. A cache
// overwrites an atlas cell that a draw of the current recording samples only after ending the recording
// (Graphite orders an upload ahead of the recording's draws), and the glyph caches date their cells by the
// recording generation to tell which cells are free of the current recording. SKGraphicsConfig implements
// it; the caches see only this, below the context.
// All functions run on the Skia queue flusher thread.

#include <cstdint>

namespace skgpu::graphite {
class Recorder;
}

class SKRecordingOwner {
public:
    virtual ~SKRecordingOwner() = default;

    [[nodiscard]] virtual skgpu::graphite::Recorder *recorder() const = 0;
    // Advanced by every recording ended (endRecording).
    [[nodiscard]] virtual uint32_t recordingGeneration() const = 0;
    // Ends the recording: every pending batch is drawn into it, and it is inserted into the context (no
    // submit), so that what is recorded afterwards is ordered after it.
    virtual void endRecording() = 0;
};

#endif /* SKRecordingOwner_h_Included */
