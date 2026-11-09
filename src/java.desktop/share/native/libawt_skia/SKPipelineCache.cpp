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
#include "SKEntryLock.h"
#include "SKPlatform.h"

#include "jni.h"
#include "Trace.h"
#include "sun_java2d_skia_SKPipelineCache.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cstring>
#include <fstream>
#include <system_error>

using skgpu::graphite::PersistentPipelineStorage;
namespace fs = std::filesystem;

namespace {

// Lookups, of which found, and blobs handed over for storing, both kinds together;
// for tests (SKPipelineCache.counters, which reads them in this order).
struct SKPipelineCacheCounters {
    uint64_t loads, hits, stores;
};
constexpr int SK_PIPELINE_CACHE_COUNTERS = 3;   // what the Java side's long[] holds (SKPipelineCache.COUNTERS)

constexpr size_t MEGABYTE = 1u << 20;
constexpr size_t MAX_BLOB_FILE = 64 * MEGABYTE;   // a blob file beyond this starts over
// A Windows path this long gets the \\?\ prefix: MAX_PATH is 260, and the file name and extension
// are added to the directory Java gave.
constexpr size_t LONG_PATH_THRESHOLD = 240;

std::mutex gCacheLock;   // guards the directory and the store tables
fs::path gDirectory;     // empty: off
std::unordered_map<std::string, SKBlobStore *> gBlobStores;
std::unordered_map<std::string, PersistentPipelineStorage *> gStorages;
std::atomic<uint64_t> gLoadCount, gHitCount, gStoreCount;
std::vector<SKPipelineDataProvider *> gProviders;   // in the order they registered; under the entry lock

// A blob file record: the two sizes, then the key and the value bytes.
struct RecordHeader {
    uint32_t keySize;
    uint32_t valueSize;
};
static_assert(sizeof(RecordHeader) == 2 * sizeof(uint32_t), "the record header is written as it is laid out");

// For trace messages: the path in UTF-8 (path::string() would convert to the ANSI code page).
std::string utf8(const fs::path &path) {
    const std::u8string utf8Bytes = path.u8string();
    return std::string(utf8Bytes.begin(), utf8Bytes.end());
}

// The file of a cache, opened by its UTF-16 path (a user's profile may have characters the ANSI
// code page lacks); on Windows a long one carries the \\?\ prefix, which lifts MAX_PATH.
fs::path fileFor(const std::string &name) {
    std::string fileName = name;
    for (char &character : fileName) {
        if (!isalnum((unsigned char) character) && character != '.' && character != '_' && character != '-') {
            character = '_';
        }
    }
    fs::path path = gDirectory / (fileName + ".bin");
#ifdef _WIN32
    const std::wstring native = path.wstring();
    if (native.size() >= LONG_PATH_THRESHOLD && path.is_absolute() && native.rfind(L"\\\\?\\", 0) != 0) {
        path = fs::path(L"\\\\?\\" + path.lexically_normal().wstring());
    }
#endif
    return path;
}

// The cache's directory is created with the first file written into it (the Java side only
// names it), so that a backend that does not start, or stores nothing, leaves nothing in the
// user's profile. False when it cannot be created: the cache then has no files.
bool makeDirectoryOf(const fs::path &file) {
    std::error_code errorCode;
    fs::create_directories(file.parent_path(), errorCode);
    if (errorCode) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot create %s: %s", utf8(file.parent_path()).c_str(),
                      errorCode.message().c_str());
        return false;
    }
    return true;
}

size_t fileSize(const fs::path &path) {
    std::error_code errorCode;
    const uintmax_t size = fs::file_size(path, errorCode);
    return errorCode ? 0 : (size_t) size;
}

// Graphite's PersistentPipelineStorage over one file: load() reads it whole, store()
// writes a temporary file next to it and renames (a reader never sees a partial file).
class FilePipelineStorage : public PersistentPipelineStorage {
public:
    explicit FilePipelineStorage(fs::path path) : m_path(std::move(path)) {}

    // Graphite calls both from a JNI entry: nothing may throw out of them, a file that cannot
    // be read is no cache.
    sk_sp<SkData> load() override try {
        gLoadCount++;
        std::ifstream in(m_path, std::ios::binary | std::ios::ate);
        if (!in) {
            return nullptr;
        }
        const std::streamsize size = in.tellg();
        if (size <= 0 || (uintmax_t) size > MAX_BLOB_FILE) {
            return nullptr;   // unreadable (tellg failed), empty, or not a file this cache wrote
        }
        in.seekg(0);
        sk_sp<SkData> data = SkData::MakeUninitialized((size_t) size);
        if (!in.read((char *) data->writable_data(), size)) {
            return nullptr;
        }
        gHitCount++;
        J2dRlsTraceLn(J2D_TRACE_INFO, "SKPipelineCache: %zu bytes of pipeline data from %s", (size_t) size,
                      utf8(m_path).c_str());
        return data;
    } catch (const std::exception &) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot read %s", utf8(m_path).c_str());
        return nullptr;
    }

    void store(const SkData &data) override try {
        gStoreCount++;
        if (!makeDirectoryOf(m_path)) {
            return;
        }
        fs::path temp = m_path;
        temp += ".tmp";
        {
            std::ofstream out(temp, std::ios::binary | std::ios::trunc);
            if (!out.write((const char *) data.data(), (std::streamsize) data.size())) {
                J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot write %s", utf8(temp).c_str());
                return;
            }
        }
        std::error_code errorCode;
        fs::rename(temp, m_path, errorCode);   // replaces an existing file
        if (errorCode) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot replace %s", utf8(m_path).c_str());
        }
    } catch (const std::exception &) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot write %s", utf8(m_path).c_str());
    }

private:
    fs::path m_path;
};

} // namespace

// The file is read once, under gCacheLock; every record is bounded by the bytes left in the file
// (itself at most MAX_BLOB_FILE), so a corrupt size allocates nothing. A record that does not
// fit (a corrupt header, or a last record cut short when a process ended mid-write) ends the
// file: it is deleted and the store starts over, since records appended after a bad one could
// never be read. Nothing throws out of here (the constructor and Dawn's callbacks run in JNI
// entries): a file that cannot be read is no cache.
SKBlobStore::SKBlobStore(fs::path path) : m_path(std::move(path)) {
    const size_t size = fileSize(m_path);
    bool startOver = size > MAX_BLOB_FILE;
    if (startOver) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "SKPipelineCache: %s is over %zu MB, starting over", utf8(m_path).c_str(),
                      MAX_BLOB_FILE / MEGABYTE);
    } else if (size > 0) {
        try {
            std::ifstream in(m_path, std::ios::binary);
            size_t remaining = size;
            RecordHeader header;
            while (remaining > 0) {
                if (remaining < sizeof header || !in.read((char *) &header, sizeof header)) {
                    startOver = true;
                    break;
                }
                remaining -= sizeof header;
                if ((size_t) header.keySize > remaining || (size_t) header.valueSize > remaining - header.keySize) {
                    startOver = true;
                    break;
                }
                std::string key(header.keySize, '\0'), value(header.valueSize, '\0');
                if (!in.read(key.data(), header.keySize) || !in.read(value.data(), header.valueSize)) {
                    startOver = true;
                    break;
                }
                remaining -= (size_t) header.keySize + header.valueSize;
                m_blobs[key] = std::move(value);
            }
        } catch (const std::exception &) {
            startOver = true;
        }
        if (startOver) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: %s is corrupt, starting over", utf8(m_path).c_str());
        }
    }
    if (startOver) {
        m_blobs.clear();
        std::error_code errorCode;
        fs::remove(m_path, errorCode);
        return;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "SKPipelineCache: %zu blobs from %s", m_blobs.size(), utf8(m_path).c_str());
}

size_t SKBlobStore::load(const void *key, size_t keySize, void *value, size_t valueSize) {
    std::lock_guard<std::mutex> lock(m_mutex);
    gLoadCount++;
    try {
        auto found = m_blobs.find(std::string((const char *) key, keySize));
        if (found == m_blobs.end()) {
            return 0;
        }
        gHitCount++;
        const std::string &blob = found->second;
        if (value != nullptr) {
            memcpy(value, blob.data(), std::min(valueSize, blob.size()));
        }
        return blob.size();
    } catch (const std::exception &) {
        return 0;
    }
}

void SKBlobStore::store(const void *key, size_t keySize, const void *value, size_t valueSize) {
    std::lock_guard<std::mutex> lock(m_mutex);
    gStoreCount++;
    if (keySize > UINT32_MAX || valueSize > UINT32_MAX) {
        return;   // the record's sizes are 32-bit (RecordHeader)
    }
    try {
        std::string keyString((const char *) key, keySize);
        if (m_blobs.count(keyString) != 0) {
            return;
        }
        m_blobs[keyString] = std::string((const char *) value, valueSize);
        if (!m_directoryMade && !(m_directoryMade = makeDirectoryOf(m_path))) {
            return;
        }
        std::ofstream out(m_path, std::ios::binary | std::ios::app);
        const RecordHeader header = { (uint32_t) keySize, (uint32_t) valueSize };
        out.write((const char *) &header, sizeof header);
        out.write((const char *) key, (std::streamsize) keySize);
        if (!out.write((const char *) value, (std::streamsize) valueSize)) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot append to %s", utf8(m_path).c_str());
        }
    } catch (const std::exception &) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPipelineCache: cannot append to %s", utf8(m_path).c_str());
    }
}

void SKPipelineCache::addProvider(SKPipelineDataProvider *provider) {
    gProviders.push_back(provider);
}

void SKPipelineCache::removeProvider(SKPipelineDataProvider *provider) {
    gProviders.erase(std::remove(gProviders.begin(), gProviders.end(), provider), gProviders.end());
}

bool SKPipelineCache::enabled() {
    std::lock_guard<std::mutex> lock(gCacheLock);
    return !gDirectory.empty();
}

SKBlobStore *SKPipelineCache::blobStore(const std::string &name) {
    std::lock_guard<std::mutex> lock(gCacheLock);
    if (gDirectory.empty()) {
        return nullptr;
    }
    SKBlobStore *&store = gBlobStores[name];
    if (store == nullptr) {
        try {
            store = new SKBlobStore(fileFor(name));
        } catch (const std::exception &) {
            return nullptr;   // no cache
        }
    }
    return store;
}

PersistentPipelineStorage *SKPipelineCache::storage(const std::string &name) {
    std::lock_guard<std::mutex> lock(gCacheLock);
    if (gDirectory.empty()) {
        return nullptr;
    }
    PersistentPipelineStorage *&storage = gStorages[name];
    if (storage == nullptr) {
        try {
            storage = new FilePipelineStorage(fileFor(name));
        } catch (const std::exception &) {
            return nullptr;   // no cache
        }
    }
    return storage;
}

// ---------------------------------------------------------------------------
// sun.java2d.skia.SKPipelineCache's native side
// ---------------------------------------------------------------------------

namespace {

// The directory as Java gives it (UTF-16: a profile path may have characters the ANSI code page
// lacks); null: off.
void setDirectory(const char16_t *directory, size_t length) {
    std::lock_guard<std::mutex> lock(gCacheLock);
    gDirectory = directory != nullptr ? fs::path(std::u16string(directory, length)) : fs::path();
}

SKPipelineCacheCounters counters() {
    return { gLoadCount.load(), gHitCount.load(), gStoreCount.load() };
}

// The counters as a Java long[], in their order; null if the array cannot be allocated (the
// pending OutOfMemoryError is the caller's).
template <size_t COUNT>
jlongArray toLongArray(JNIEnv *env, const std::array<jlong, COUNT> &values) {
    jlongArray array = env->NewLongArray((jsize) COUNT);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, (jsize) COUNT, values.data());
    }
    return array;
}

} // namespace

// The Skia release the library was built against (configure: SKIA_RELEASE, the pin's SKIA_TAG).
#define SK_STRINGIFY2(x) #x
#define SK_STRINGIFY(x) SK_STRINGIFY2(x)

extern "C" {

JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKPipelineCache_skiaReleaseNative(JNIEnv *env, jclass clazz)
{
    return env->NewStringUTF(SK_STRINGIFY(SKIA_RELEASE));
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKPipelineCache_setDirectoryNative(JNIEnv *env, jclass clazz, jstring directory)
{
    SK_ENTRY_LOCK;
    if (directory == nullptr) {
        setDirectory(nullptr, 0);
        return;
    }
    const jchar *chars = env->GetStringChars(directory, nullptr);
    if (chars != nullptr) {
        setDirectory((const char16_t *) chars, (size_t) env->GetStringLength(directory));
        env->ReleaseStringChars(directory, chars);
    }
}

JNIEXPORT jlongArray JNICALL
Java_sun_java2d_skia_SKPipelineCache_countersNative(JNIEnv *env, jclass clazz)
{
    SK_ENTRY_LOCK;
    const SKPipelineCacheCounters cacheCounters = counters();
    const std::array<jlong, SK_PIPELINE_CACHE_COUNTERS> values = {
        (jlong) cacheCounters.loads, (jlong) cacheCounters.hits, (jlong) cacheCounters.stores
    };
    return toLongArray(env, values);
}

// SKPipelineCache's shutdown hook: the pipeline data compiled since the last offer, one offer
// per registered provider (SKGraphicsConfig::syncPipelineData; a lost device offers nothing).
JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKPipelineCache_syncNative(JNIEnv *env, jclass clazz)
{
    SK_ENTRY_LOCK;
    SKPlatformScope scope;
    for (SKPipelineDataProvider *provider : gProviders) {
        provider->syncPipelineData();
    }
}

} // extern "C"
