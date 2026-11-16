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
#ifndef SKPipelineCache_h_Included
#define SKPipelineCache_h_Included

// The on-disk cache of what the GPU backend compiles (sun.java2d.skia.pipelineCache):
// Dawn hands over the shaders and pipelines it compiled, by its own key, and asks
// for them in the next session (SKBlobStore, chained into the device descriptor by
// the Dawn platform). The files are in one directory, one per device, named by the
// backend. Off until sun.java2d.skia.SKPipelineCache names the directory
// (setDirectoryNative); its native side also reads the counters.

// ReSharper disable once CppUnusedIncludeDirective - size_t (SKBlobStore)
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <string>
#include <unordered_map>

// Keyed blobs (Dawn's blob cache): one append-only file of (key size, value size,
// key, value) records, read once; store() appends at once.
class SKBlobStore {
public:
    explicit SKBlobStore(std::filesystem::path path);
    // The blob's size (0: none); copies up to valueSize bytes when value is non-null.
    size_t load(const void *key, size_t keySize, void *value, size_t valueSize);
    void store(const void *key, size_t keySize, const void *value, size_t valueSize);

private:
    std::filesystem::path m_path;
    std::mutex m_mutex;
    std::unordered_map<std::string, std::string> m_blobs;
    bool m_directoryMade = false;   // the directory is created with the first append
};

namespace SKPipelineCache {

// The store of <directory>/<name>.bin (name reduced to [A-Za-z0-9._-]); null when off.
// Lives for the process: a device's callbacks may outlive its configuration.
SKBlobStore *blobStore(const std::string &name);

} // namespace SKPipelineCache

#endif /* SKPipelineCache_h_Included */
