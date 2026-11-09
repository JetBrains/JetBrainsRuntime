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

// EXPERIMENT (x64hw, 2026-11-04), diagnostics only, Windows:
// - Ganesh's per-draw floor on the Vulkan device the backend's Graphite context uses (SKEnvironment.benchGanesh),
//   the same kinds as SKBackend_BenchGraphite, so the two Skia backends compare on one device, one queue and one
//   thread. Nothing else uses Ganesh.
// - The same kinds on two CPU canvases (SKEnvironment.benchCanvas): one without pixels (SkCanvas(w, h), whose
//   device ignores every draw: Skia's canvas front end alone) and a raster surface (the CPU rasterizer).
// - A sampling profile of any of them (SKEnvironment.profileBench): a thread suspends the benchmark's thread about
//   every millisecond, unwinds its stack (RtlVirtualUnwind) and, afterwards, names the frames with DbgHelp;
//   inclusive and exclusive samples per function. No admin rights, no ETW.

#include "VKSKPlatform.h"
#include "SKBackend.h"
#include "SKStats.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"
#include "include/core/SkVertices.h"
#include "include/gpu/GpuTypes.h"
#include "include/gpu/ganesh/GrContextOptions.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/vk/GrVkDirectContext.h"

#include <algorithm>
#include <atomic>
#include <map>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <windows.h>
#include <dbghelp.h>
#include <timeapi.h>
#include "jni.h"

// The kinds of SKBackend_BenchGraphite (keep in step): 0 rect, 1 rects alternating colours, 2 one-quad mesh,
// 3 a 32x32 image, 4 images of 16 textures in turn, 5 rect, mesh and image in turn, 6 two surfaces in turn.
static void drawKind(SkCanvas *canvas, SkCanvas *second, int kind, int draws, const sk_sp<SkImage> &image,
                     const std::vector<sk_sp<SkImage>> &images) {
    static const SkPoint pos[4] = {{0, 0}, {16, 0}, {16, 16}, {0, 16}};
    static const uint16_t idx[6] = {0, 1, 2, 0, 2, 3};
    SkPaint paint;
    for (int i = 0; i < draws; i++) {
        const float x = (float) ((i * 37) % 1000), y = (float) ((i * 53) % 1000);
        switch (kind) {
            case 0: paint.setColor(SK_ColorRED); canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint); break;
            case 1: paint.setColor(i % 2 ? SK_ColorRED : SK_ColorGREEN); canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint); break;
            case 2: {
                canvas->save();
                canvas->translate(x, y);
                paint.setColor(SK_ColorRED);
                canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, pos, nullptr, nullptr, 6, idx),
                                     SkBlendMode::kModulate, paint);
                canvas->restore();
                break;
            }
            case 3: canvas->drawImageRect(image, SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint); break;
            case 4: canvas->drawImageRect(images[(size_t) i % images.size()], SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint); break;
            case 5:
                if (i % 3 == 0) {
                    paint.setColor(SK_ColorRED);
                    canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint);
                } else if (i % 3 == 1) {
                    canvas->save();
                    canvas->translate(x, y);
                    canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, pos, nullptr, nullptr, 6, idx),
                                         SkBlendMode::kModulate, paint);
                    canvas->restore();
                } else {
                    canvas->drawImageRect(images[(size_t) i % images.size()], SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint);
                }
                break;
            default: {
                SkCanvas *c = (i / 50) % 2 == 0 ? canvas : second;
                paint.setColor(SK_ColorRED);
                c->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint);
                break;
            }
        }
    }
}

static void medians(std::vector<double> *t, int phases, int draws, double *out) {
    for (int i = 0; i < phases; i++) {
        std::sort(t[i].begin(), t[i].end());
        out[i] = t[i][t[i].size() / 2] / draws;
    }
}

// `draws` draws of one kind into an off-screen 1024x1024 surface, then the context's flush (the ops
// prepared and executed into the command buffer: Graphite's snap and insert together) and submit;
// per frame the nanoseconds of each, the median over `frames` frames (after as many warm-up frames)
// divided by the draws in out[0..2] (record, flush, submit). A Ganesh context of its own per call, on
// the device's queue; Graphite's work is drained first (the caller synced the queue) and the device is
// idle when it returns.
static bool benchGanesh(int kind, int draws, int frames, bool reduceOpsTaskSplitting, double out[3]) {
    std::shared_ptr<const skgpu::VulkanBackendContext> backend = VKSK_SharedBackend();
    if (backend == nullptr || draws <= 0 || frames <= 0) {
        return false;
    }
    GrContextOptions options;
    options.fReduceOpsTaskSplitting = reduceOpsTaskSplitting ? GrContextOptions::Enable::kYes
                                                             : GrContextOptions::Enable::kNo;
    sk_sp<GrDirectContext> context = GrDirectContexts::MakeVulkan(*backend, options);
    if (!context) {
        return false;
    }
    const SkImageInfo big = SkImageInfo::Make(1024, 1024, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    const SkImageInfo small = SkImageInfo::Make(32, 32, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> surface = SkSurfaces::RenderTarget(context.get(), skgpu::Budgeted::kYes, big, 0,
                                                        kTopLeft_GrSurfaceOrigin, nullptr);
    sk_sp<SkSurface> second = SkSurfaces::RenderTarget(context.get(), skgpu::Budgeted::kYes, big, 0,
                                                       kTopLeft_GrSurfaceOrigin, nullptr);
    sk_sp<SkSurface> source = SkSurfaces::RenderTarget(context.get(), skgpu::Budgeted::kYes, small, 0,
                                                       kTopLeft_GrSurfaceOrigin, nullptr);
    if (!surface || !second || !source) {
        return false;
    }
    source->getCanvas()->clear(SK_ColorBLUE);
    sk_sp<SkImage> image = source->makeImageSnapshot();
    std::vector<sk_sp<SkImage>> images;
    for (int i = 0; i < 16; i++) {
        source->getCanvas()->clear(SkColorSetARGB(255, (U8CPU) (i * 16), 80, 160));
        images.push_back(source->makeImageSnapshot());   // copy on write: 16 textures
    }
    context->flushAndSubmit(GrSyncCpu::kYes);
    std::vector<double> t[3];
    for (int f = 0; f < 2 * frames; f++) {
        const uint64_t t0 = SKStats_Now();
        drawKind(surface->getCanvas(), second->getCanvas(), kind, draws, image, images);
        const uint64_t t1 = SKStats_Now();
        context->flush(GrFlushInfo());
        const uint64_t t2 = SKStats_Now();
        context->submit(GrSyncCpu::kNo);
        const uint64_t t3 = SKStats_Now();
        context->submit(GrSyncCpu::kYes);   // drained between frames, not timed
        if (f >= frames) {
            t[0].push_back((double) (t1 - t0));
            t[1].push_back((double) (t2 - t1));
            t[2].push_back((double) (t3 - t2));
        }
    }
    images.clear();
    image.reset();
    source.reset();
    second.reset();
    surface.reset();
    context->flushAndSubmit(GrSyncCpu::kYes);
    context.reset();
    medians(t, 3, draws, out);
    return true;
}

// The same kinds on a CPU canvas: without pixels (raster false: SkCanvas(w, h), its device ignores every draw,
// so what is left is Skia's canvas front end: the state, the paint, the quick reject, the dispatch) or on a raster
// surface (the CPU rasterizer); out[0] the recording (here: drawing) in ns per draw, median over `frames` frames.
static bool benchCanvas(int kind, int draws, int frames, bool raster, double out[1]) {
    if (draws <= 0 || frames <= 0) {
        return false;
    }
    const SkImageInfo big = SkImageInfo::Make(1024, 1024, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    const SkImageInfo small = SkImageInfo::Make(32, 32, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> source = SkSurfaces::Raster(small);
    sk_sp<SkSurface> surface, second;
    std::unique_ptr<SkCanvas> plain, plainSecond;
    if (raster) {
        surface = SkSurfaces::Raster(big);
        second = SkSurfaces::Raster(big);
    } else {
        plain = std::make_unique<SkCanvas>(1024, 1024);
        plainSecond = std::make_unique<SkCanvas>(1024, 1024);
    }
    if (!source || (raster && (!surface || !second))) {
        return false;
    }
    source->getCanvas()->clear(SK_ColorBLUE);
    sk_sp<SkImage> image = source->makeImageSnapshot();
    std::vector<sk_sp<SkImage>> images;
    for (int i = 0; i < 16; i++) {
        source->getCanvas()->clear(SkColorSetARGB(255, (U8CPU) (i * 16), 80, 160));
        images.push_back(source->makeImageSnapshot());
    }
    SkCanvas *canvas = raster ? surface->getCanvas() : plain.get();
    SkCanvas *other = raster ? second->getCanvas() : plainSecond.get();
    std::vector<double> t[1];
    for (int f = 0; f < 2 * frames; f++) {
        const uint64_t t0 = SKStats_Now();
        drawKind(canvas, other, kind, draws, image, images);
        const uint64_t t1 = SKStats_Now();
        if (f >= frames) {
            t[0].push_back((double) (t1 - t0));
        }
    }
    medians(t, 1, draws, out);
    return true;
}

// --- the sampler ---------------------------------------------------------------------------------------------

namespace {

constexpr int DEPTH = 48;
constexpr size_t MAX_SAMPLES = 60000;

struct Sampler {
    HANDLE target = nullptr;
    std::atomic<bool> stop{false};
    std::vector<DWORD64> frames;   // MAX_SAMPLES * DEPTH, allocated before sampling: nothing allocates while the target is suspended
    std::vector<uint8_t> depths;
    size_t samples = 0;

    void run() {
        timeBeginPeriod(1);
        while (!stop.load() && samples < MAX_SAMPLES) {
            Sleep(1);
            if (SuspendThread(target) == (DWORD) -1) {
                break;
            }
            CONTEXT ctx = {};
            ctx.ContextFlags = CONTEXT_FULL;
            if (GetThreadContext(target, &ctx)) {
                DWORD64 *out = &frames[samples * DEPTH];
                int d = 0;
                while (d < DEPTH && ctx.Rip != 0) {
                    out[d++] = ctx.Rip;
                    DWORD64 base = 0;
                    PRUNTIME_FUNCTION fe = RtlLookupFunctionEntry(ctx.Rip, &base, nullptr);
                    if (fe == nullptr) {
                        if (d > 1) {
                            break;   // code without unwind data (the JVM's) beyond the leaf: the walk ends
                        }
                        ctx.Rip = *(DWORD64 *) ctx.Rsp;   // a leaf function: the return address on top
                        ctx.Rsp += 8;
                        continue;
                    }
                    PVOID handlerData = nullptr;
                    DWORD64 establisher = 0;
                    RtlVirtualUnwind(UNW_FLAG_NHANDLER, base, ctx.Rip, fe, &ctx, &handlerData, &establisher, nullptr);
                }
                depths[samples] = (uint8_t) d;
                samples++;
            }
            ResumeThread(target);
        }
        timeEndPeriod(1);
    }
};

typedef BOOL (WINAPI *SymInitializeFn)(HANDLE, PCSTR, BOOL);
typedef DWORD (WINAPI *SymSetOptionsFn)(DWORD);
typedef BOOL (WINAPI *SymFromAddrFn)(HANDLE, DWORD64, PDWORD64, PSYMBOL_INFO);
typedef BOOL (WINAPI *SymCleanupFn)(HANDLE);
typedef BOOL (WINAPI *SymSetSearchPathFn)(HANDLE, PCSTR);
typedef BOOL (WINAPI *SymRefreshModuleListFn)(HANDLE);

std::string moduleOf(DWORD64 address) {
    HMODULE module = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            (LPCWSTR) address, &module)) {
        return "?";
    }
    char path[MAX_PATH];
    const DWORD n = GetModuleFileNameA(module, path, MAX_PATH);
    std::string s(path, n);
    const size_t slash = s.find_last_of("\\/");
    return slash == std::string::npos ? s : s.substr(slash + 1);
}

// "module!function" for each address (once per address)
std::unordered_map<DWORD64, std::string> nameAll(const Sampler &s, const char *symbolPath) {
    std::unordered_map<DWORD64, std::string> names;
    HMODULE dbghelp = LoadLibraryW(L"dbghelp.dll");
    SymInitializeFn symInitialize = dbghelp ? (SymInitializeFn) GetProcAddress(dbghelp, "SymInitialize") : nullptr;
    SymSetOptionsFn symSetOptions = dbghelp ? (SymSetOptionsFn) GetProcAddress(dbghelp, "SymSetOptions") : nullptr;
    SymFromAddrFn symFromAddr = dbghelp ? (SymFromAddrFn) GetProcAddress(dbghelp, "SymFromAddr") : nullptr;
    SymCleanupFn symCleanup = dbghelp ? (SymCleanupFn) GetProcAddress(dbghelp, "SymCleanup") : nullptr;
    SymSetSearchPathFn symSetSearchPath = dbghelp ? (SymSetSearchPathFn) GetProcAddress(dbghelp, "SymSetSearchPath") : nullptr;
    SymRefreshModuleListFn symRefresh = dbghelp ? (SymRefreshModuleListFn) GetProcAddress(dbghelp, "SymRefreshModuleList") : nullptr;
    const HANDLE process = GetCurrentProcess();
    bool symbols = false;
    bool ours = false;   // initialized here (else the JVM's DbgHelp session is reused)
    if (symInitialize && symSetOptions && symFromAddr && symCleanup) {
        symSetOptions(SYMOPT_UNDNAME | SYMOPT_DEFERRED_LOADS | SYMOPT_FAIL_CRITICAL_ERRORS);
        ours = symbols = symInitialize(process, symbolPath, TRUE) == TRUE;
        if (!symbols && symSetSearchPath && symRefresh) {
            symbols = symSetSearchPath(process, symbolPath) == TRUE;
            symRefresh(process);
        }
    }
    alignas(SYMBOL_INFO) char buffer[sizeof(SYMBOL_INFO) + 512];
    std::unordered_map<std::string, std::string> moduleCache;
    for (size_t i = 0; i < s.samples; i++) {
        for (int d = 0; d < s.depths[i]; d++) {
            const DWORD64 a = s.frames[i * DEPTH + d];
            if (names.count(a)) {
                continue;
            }
            std::string name;
            if (symbols) {
                SYMBOL_INFO *info = (SYMBOL_INFO *) buffer;
                memset(info, 0, sizeof(SYMBOL_INFO));
                info->SizeOfStruct = sizeof(SYMBOL_INFO);
                info->MaxNameLen = 511;
                DWORD64 displacement = 0;
                if (symFromAddr(process, a, &displacement, info)) {
                    name = info->Name;
                }
            }
            const std::string module = moduleOf(a);
            if (name.empty()) {
                name = "?";
            }
            names[a] = module + "!" + name;
        }
    }
    if (ours) {
        symCleanup(process);
    }
    return names;
}

// The report: all samples, then per focus (a substring of "module!function") the samples whose stack holds a
// frame matching it, as shares of those samples, prefixed "focus <name>".
std::string report(const Sampler &s, const char *symbolPath, double seconds, const std::vector<std::string> &focus = {}) {
    const std::unordered_map<DWORD64, std::string> names = nameAll(s, symbolPath);
    auto top = [](const std::map<std::string, size_t> &m, size_t count) {
        std::vector<std::pair<std::string, size_t>> v(m.begin(), m.end());
        std::sort(v.begin(), v.end(), [](const auto &a, const auto &b) { return a.second > b.second; });
        if (v.size() > count) {
            v.resize(count);
        }
        return v;
    };
    std::string out;
    char line[1024];
    snprintf(line, sizeof line, "#samples %zu in %.1f s\n", s.samples, seconds);
    out += line;
    auto section = [&](const std::string &prefix, const std::string *match, size_t inclusiveCount, size_t exclusiveCount) {
        std::map<std::string, size_t> inclusive, exclusive, modules;
        size_t selected = 0;
        for (size_t i = 0; i < s.samples; i++) {
            if (match != nullptr) {
                bool hit = false;
                for (int d = 0; d < s.depths[i] && !hit; d++) {
                    hit = names.at(s.frames[i * DEPTH + d]).find(*match) != std::string::npos;
                }
                if (!hit) {
                    continue;
                }
            }
            selected++;
            std::unordered_set<std::string> seen;
            for (int d = 0; d < s.depths[i]; d++) {
                const std::string &n = names.at(s.frames[i * DEPTH + d]);
                if (d == 0) {
                    exclusive[n]++;
                    modules[n.substr(0, n.find('!'))]++;
                }
                if (seen.insert(n).second) {
                    inclusive[n]++;
                }
            }
        }
        if (match != nullptr) {
            snprintf(line, sizeof line, "%s#samples %zu\n", prefix.c_str(), selected);
            out += line;
        }
        const double n = (double) std::max<size_t>(1, selected);
        for (const auto &e : top(modules, 12)) {
            snprintf(line, sizeof line, "%smodule\t%.1f%%\t%zu\t%s\n", prefix.c_str(), 100.0 * e.second / n, e.second, e.first.c_str());
            out += line;
        }
        for (const auto &e : top(inclusive, inclusiveCount)) {
            snprintf(line, sizeof line, "%sinclusive\t%.1f%%\t%zu\t%s\n", prefix.c_str(), 100.0 * e.second / n, e.second, e.first.c_str());
            out += line;
        }
        for (const auto &e : top(exclusive, exclusiveCount)) {
            snprintf(line, sizeof line, "%sexclusive\t%.1f%%\t%zu\t%s\n", prefix.c_str(), 100.0 * e.second / n, e.second, e.first.c_str());
            out += line;
        }
    };
    section("", nullptr, 70, 50);
    for (const std::string &f : focus) {
        section("focus " + f + "\t", &f, 90, 60);
    }
    return out;
}

} // namespace

// backend: 0 Graphite (SKBackend_BenchGraphite), 1 Ganesh, 2 the canvas without pixels, 3 raster; the benchmark
// runs on this thread while the sampler samples it; the report as text, empty if the benchmark could not run.
static std::string profileBench(int backend, int kind, int draws, int frames, const char *symbolPath) {
    Sampler sampler;
    if (!DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), GetCurrentProcess(), &sampler.target,
                         THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT | THREAD_QUERY_INFORMATION, FALSE, 0)) {
        return std::string();
    }
    sampler.frames.resize(MAX_SAMPLES * DEPTH);
    sampler.depths.resize(MAX_SAMPLES);
    const uint64_t t0 = SKStats_Now();
    std::thread thread([&sampler] { sampler.run(); });
    double out[4];
    bool ran;
    switch (backend) {
        case 0: ran = SKBackend_BenchGraphite(kind, draws, frames, out); break;
        case 1: ran = benchGanesh(kind, draws, frames, false, out); break;
        default: ran = benchCanvas(kind, draws, frames, backend == 3, out); break;
    }
    sampler.stop.store(true);
    thread.join();
    const double seconds = (SKStats_Now() - t0) / 1e9;
    CloseHandle(sampler.target);
    return ran ? report(sampler, symbolPath, seconds) : std::string();
}

// The flusher thread profiled in place (sun.java2d.skia.profileFlusher=<seconds>, after
// sun.java2d.skia.profileFlusherDelay seconds, into sun.java2d.skia.profileFlusherOut): the first flush on the
// flusher thread starts a sampler thread on it; the report (as profileBench's, of all the flusher's samples, idle
// ones included) is written to the file when the time is up. The flusher never waits for any of it.
namespace {
struct FlusherProfile {
    int delayMs = 0;
    int durationMs = 0;
    std::string out;
    std::string symbols;
    std::vector<std::string> focus;   // sun.java2d.skia.profileFlusherFocus, comma-separated
    std::atomic<bool> started{false};
};
FlusherProfile gFlusherProfile;
} // namespace

void SKBackend_NoteFlusherThread() {
    if (gFlusherProfile.durationMs <= 0 || gFlusherProfile.started.exchange(true)) {
        return;
    }
    HANDLE target = nullptr;
    if (!DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), GetCurrentProcess(), &target,
                         THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT | THREAD_QUERY_INFORMATION, FALSE, 0)) {
        return;
    }
    std::thread([target] {
        Sleep((DWORD) gFlusherProfile.delayMs);
        auto *sampler = new Sampler();
        sampler->target = target;
        sampler->frames.resize(MAX_SAMPLES * DEPTH);
        sampler->depths.resize(MAX_SAMPLES);
        const uint64_t t0 = SKStats_Now();
        std::thread run([sampler] { sampler->run(); });
        Sleep((DWORD) gFlusherProfile.durationMs);
        sampler->stop.store(true);
        run.join();
        const double seconds = (SKStats_Now() - t0) / 1e9;
        const std::string text = report(*sampler, gFlusherProfile.symbols.c_str(), seconds, gFlusherProfile.focus);
        if (FILE *f = fopen(gFlusherProfile.out.c_str(), "wb")) {
            fwrite(text.data(), 1, text.size(), f);
            fclose(f);
        }
        CloseHandle(sampler->target);
        delete sampler;
    }).detach();
}

extern "C" JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKEnvironment_profileFlusherNative(JNIEnv *env, jclass clazz, jint delaySeconds, jint seconds,
                                                        jstring out, jstring symbolPath, jstring focus)
{
    const char *o = env->GetStringUTFChars(out, nullptr);
    const char *s = env->GetStringUTFChars(symbolPath, nullptr);
    if (o != nullptr && s != nullptr) {
        gFlusherProfile.out = o;
        gFlusherProfile.symbols = s;
        if (focus != nullptr) {
            if (const char *f = env->GetStringUTFChars(focus, nullptr)) {
                std::string all(f);
                env->ReleaseStringUTFChars(focus, f);
                for (size_t at = 0; at <= all.size();) {
                    const size_t comma = std::min(all.find(',', at), all.size());
                    if (comma > at) {
                        gFlusherProfile.focus.push_back(all.substr(at, comma - at));
                    }
                    at = comma + 1;
                }
            }
        }
        gFlusherProfile.delayMs = std::max(0, (int) delaySeconds) * 1000;
        gFlusherProfile.durationMs = std::min((int) seconds, (int) (MAX_SAMPLES / 1000)) * 1000;   // at most the buffer's samples
    }
    if (o != nullptr) {
        env->ReleaseStringUTFChars(out, o);
    }
    if (s != nullptr) {
        env->ReleaseStringUTFChars(symbolPath, s);
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_sun_java2d_skia_SKEnvironment_benchGaneshNative(JNIEnv *env, jclass clazz, jint kind, jint draws, jint frames,
                                                     jboolean reduceOpsTaskSplitting)
{
    SK_ENTRY_LOCK;   // the flusher may be running a handed-off flush (SKBackend_EntryMutex)
    double out[3];
    if (!benchGanesh(kind, draws, frames, reduceOpsTaskSplitting == JNI_TRUE, out)) {
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(3);
    if (result != nullptr) {
        env->SetDoubleArrayRegion(result, 0, 3, out);
    }
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_sun_java2d_skia_SKEnvironment_benchCanvasNative(JNIEnv *env, jclass clazz, jint kind, jint draws, jint frames,
                                                     jboolean raster)
{
    double out[1];
    if (!benchCanvas(kind, draws, frames, raster == JNI_TRUE, out)) {
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(1);
    if (result != nullptr) {
        env->SetDoubleArrayRegion(result, 0, 1, out);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_profileBenchNative(JNIEnv *env, jclass clazz, jint backend, jint kind, jint draws,
                                                      jint frames, jstring symbolPath)
{
    SK_ENTRY_LOCK;   // the flusher may be running a handed-off flush (SKBackend_EntryMutex)
    const char *path = symbolPath != nullptr ? env->GetStringUTFChars(symbolPath, nullptr) : nullptr;
    const std::string text = profileBench(backend, kind, draws, frames, path);
    if (path != nullptr) {
        env->ReleaseStringUTFChars(symbolPath, path);
    }
    return text.empty() ? nullptr : env->NewStringUTF(text.c_str());
}
