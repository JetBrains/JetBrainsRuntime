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

#include "SKPipelineCache.h"

#include "SKStats.h"
#include "Trace.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <fstream>

using skgpu::graphite::PersistentPipelineStorage;

namespace {

constexpr size_t MAX_BLOB_FILE = 64u << 20;   // a blob file beyond this starts over

std::mutex gLock;
std::string gDirectory;   // empty: off
std::unordered_map<std::string, SKBlobStore *> gBlobStores;
std::unordered_map<std::string, PersistentPipelineStorage *> gStorages;
std::atomic<uint64_t> gLoads, gHits, gStores;

std::string fileFor(const std::string &name) {
    std::string file = name;
    for (char &c : file) {
        if (!isalnum((unsigned char) c) && c != '.' && c != '_' && c != '-') {
            c = '_';
        }
    }
    return gDirectory + "/" + file + ".bin";
}

size_t fileSize(const std::string &path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    return in ? (size_t) in.tellg() : 0;
}

// Graphite's PersistentPipelineStorage over one file: load() reads it whole, store()
// writes a temporary file next to it and renames (a reader never sees a partial file).
class FileStorage : public PersistentPipelineStorage {
public:
    explicit FileStorage(std::string path) : path(std::move(path)) {}

    sk_sp<SkData> load() override {
        gLoads++;
        SKStats_Count(SK_COUNT_BLOB_LOADS);
        std::ifstream in(path, std::ios::binary | std::ios::ate);
        if (!in) {
            return nullptr;
        }
        const std::streamsize size = in.tellg();
        in.seekg(0);
        sk_sp<SkData> data = SkData::MakeUninitialized((size_t) size);
        if (!in.read((char *) data->writable_data(), size)) {
            return nullptr;
        }
        gHits++;
        SKStats_Count(SK_COUNT_BLOB_HITS);
        J2dRlsTraceLn(J2D_TRACE_INFO, "SKPipelineCache: %zu bytes of pipeline data from %s", (size_t) size, path.c_str());
        return data;
    }

    void store(const SkData &data) override {
        gStores++;
        SKStats_Count(SK_COUNT_BLOB_STORES);
        const std::string temp = path + ".tmp";
        {
            std::ofstream out(temp, std::ios::binary | std::ios::trunc);
            if (!out.write((const char *) data.data(), (std::streamsize) data.size())) {
                J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot write %s", temp.c_str());
                return;
            }
        }
        std::remove(path.c_str());
        if (std::rename(temp.c_str(), path.c_str()) != 0) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot replace %s", path.c_str());
        }
    }

private:
    std::string path;
};

} // namespace

SKBlobStore::SKBlobStore(std::string path) : path(std::move(path)) {
    if (fileSize(this->path) > MAX_BLOB_FILE) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "SKPipelineCache: %s is over %zu MB, starting over", this->path.c_str(),
                      (size_t) (MAX_BLOB_FILE >> 20));
        std::remove(this->path.c_str());
        return;
    }
    std::ifstream in(this->path, std::ios::binary);
    uint32_t sizes[2];
    while (in.read((char *) sizes, sizeof sizes)) {
        std::string key(sizes[0], '\0'), value(sizes[1], '\0');
        if (!in.read(key.data(), sizes[0]) || !in.read(value.data(), sizes[1])) {
            break;   // a truncated last record (the process ended mid-write) is dropped
        }
        blobs[key] = std::move(value);
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "SKPipelineCache: %zu blobs from %s", blobs.size(), this->path.c_str());
}

size_t SKBlobStore::load(const void *key, size_t keySize, void *value, size_t valueSize) {
    std::lock_guard<std::mutex> lock(mutex);
    gLoads++;
    SKStats_Count(SK_COUNT_BLOB_LOADS);
    auto it = blobs.find(std::string((const char *) key, keySize));
    if (it == blobs.end()) {
        return 0;
    }
    gHits++;
    SKStats_Count(SK_COUNT_BLOB_HITS);
    if (value != nullptr) {
        memcpy(value, it->second.data(), std::min(valueSize, it->second.size()));
    }
    return it->second.size();
}

void SKBlobStore::store(const void *key, size_t keySize, const void *value, size_t valueSize) {
    std::lock_guard<std::mutex> lock(mutex);
    gStores++;
    SKStats_Count(SK_COUNT_BLOB_STORES);
    std::string k((const char *) key, keySize);
    if (blobs.count(k) != 0) {
        return;
    }
    blobs[k] = std::string((const char *) value, valueSize);
    std::ofstream out(path, std::ios::binary | std::ios::app);
    const uint32_t sizes[2] = { (uint32_t) keySize, (uint32_t) valueSize };
    out.write((const char *) sizes, sizeof sizes);
    out.write((const char *) key, (std::streamsize) keySize);
    if (!out.write((const char *) value, (std::streamsize) valueSize)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot append to %s", path.c_str());
    }
}

void SKPipelineCache_SetDirectory(const char *directory) {
    std::lock_guard<std::mutex> lock(gLock);
    gDirectory = directory != nullptr ? directory : "";
}

bool SKPipelineCache_Enabled() {
    std::lock_guard<std::mutex> lock(gLock);
    return !gDirectory.empty();
}

SKBlobStore *SKPipelineCache_BlobStore(const std::string &name) {
    std::lock_guard<std::mutex> lock(gLock);
    if (gDirectory.empty()) {
        return nullptr;
    }
    SKBlobStore *&store = gBlobStores[name];
    if (store == nullptr) {
        store = new SKBlobStore(fileFor(name));
    }
    return store;
}

PersistentPipelineStorage *SKPipelineCache_Storage(const std::string &name) {
    std::lock_guard<std::mutex> lock(gLock);
    if (gDirectory.empty()) {
        return nullptr;
    }
    PersistentPipelineStorage *&storage = gStorages[name];
    if (storage == nullptr) {
        storage = new FileStorage(fileFor(name));
    }
    return storage;
}

SKPipelineCacheCounters SKPipelineCache_Counters() {
    return { gLoads.load(), gHits.load(), gStores.load() };
}
