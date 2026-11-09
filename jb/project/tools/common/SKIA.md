# Skia in the JBR: the prebuilt, its pin, and how to bump it

The Skia rendering backend (`-Dsun.java2d.skia=true`, Windows x64; `src/java.desktop/*/native/libawt_skia`,
`sun.java2d.skia`) links a prebuilt Skia. This page says where it comes from and what a bump involves.

## Where the prebuilt comes from

- **Publisher:** the JetBrains/skia GitHub releases (https://github.com/JetBrains/skia/releases), built from
  google/skia's `chrome/m<N>` branch by `tools/skia_release/build.py` on the branch `skiko-m<N>` of that
  repository: `is_official_build`, Graphite, Dawn and Vulkan on Windows, the static CRT.
- **Pin:** `skia_prebuilt.conf` — `SKIA_TAG` (`m<N>-<commit>`, the milestone and the JetBrains/skia commit)
  and the SHA-256 of the Windows x64 archive (the GitHub release asset digest). Nothing else is used: the
  backend links Skia's core, Graphite with its Dawn extension, Dawn and the parts of Chromium's allocator it
  is built on, and skcms (`make/autoconf/lib-skia.m4`).
- **Fetch:** `scripts/fetch_skia.sh` downloads the pinned archive, verifies the digest and unpacks it
  (default `build/deps/skia`); the Windows mkimages script calls it when `WITH_SKIA=1`, configure takes the
  directory as `--with-skia`. configure warns when the unpacked tree is not the pinned one.

## The GPU list follows every bump

The backend tries the graphics APIs in order (Graphite on Dawn, then on Vulkan) and falls back to the next;
before creating a device it decides per machine whether the API may be used: `sun.java2d.skia.SKGPUList`,
with the shipped list `SKGPUListData.java` generated from Chromium's GPU blocklist
(`gpu/config/software_rendering_list.json`), its driver bug list (`gpu_driver_bug_list.json`) and Dawn's
adapter rules, at the revisions that match the pinned Skia — Google's field data on GPUs and drivers, adopted
anew with each Skia release. `skia-gpu-lists/policy.conf` holds the decisions (which upstream features block
which API, what is left out and why); `skia-gpu-lists/GPUListConverter.java` applies them.

## Bumping Skia

1. Pick the release (`m<N>-<commit>`) on https://github.com/JetBrains/skia/releases; set `SKIA_TAG` and
   `SKIA_SHA256_windows_x64` (the asset's digest) in `skia_prebuilt.conf`.
2. Run `scripts/update_gpu_lists.sh` (a JDK 17 or later `java` on PATH, or `-j <java>`). It derives Chromium's
   release branch for the milestone and the Dawn revision of the JetBrains/skia commit, fetches the lists
   and the upstream sources of the hand ports, regenerates `SKGPUListData.java` and records both revisions
   in `skia_prebuilt.conf`. It stops when something needs a decision:
   - a feature or workaround name `policy.conf` does not know: read the entries that name it, then map it
     to APIs or ignore it with the reason;
   - a condition the matcher does not implement: implement it in `SKGPUList.java` (Chromium's semantics,
     `gpu/config/gpu_control_list.cc`) and in the converter, with a case in `SkiaGPUListTest`;
   - a changed fingerprint: the upstream source of a hand port changed (Chromium's version comparison or
     Intel generation table, Dawn's Intel Vulkan rule); review the port against it, then record the new
     value in `policy.conf`.
   The entries added, removed and changed are printed: put them in the bump's commit message.
3. The device features Graphite uses. A device has only the features it was created with, so the backend
   asks for what Graphite reads (`libawt_skia`, Windows):
   - Vulkan (`VKSKPlatform.cpp`): Skia's `skgpu::VulkanPreferredFeatures` chooses the extensions and feature
     structs; compare `GRAPHITE_FEATURES` and `graphiteFeaturesOf`, the report of what Graphite reads, with
     `VulkanCaps::getEnabledFeatures` (`src/gpu/graphite/vk/VulkanCaps.cpp`) of the new release, and check
     that `VulkanPreferredFeatures::addToInstanceExtensions` still adds nothing (the backend skips it).
     Count the distinct `EnabledFeatures` fields that function assigns (the `enabled.f<Name> =` lines; 17 in
     m156) against `GRAPHITE_READ_FEATURE_COUNT` next to `GRAPHITE_FEATURES`:
     `awk '/::getEnabledFeatures\(/,/^}/' VulkanCaps.cpp | grep -o 'enabled\.f[A-Za-z0-9]* *=' | sort -u | wc -l`.
     A different count means Graphite reads a feature the backend does not know (or no longer reads one):
     find it, check that `VulkanPreferredFeatures` queries and enables it (if not, chain its struct in
     `createDevice` or leave it out with the reason, as `protectedMemory`), add it to `VKSKGraphiteFeatures`,
     `GRAPHITE_FEATURES` and `graphiteFeaturesOf`, and record the new count (a `static_assert` ties the count
     to `GRAPHITE_FEATURES` and the features left out);
   - Dawn (`DawnSKPlatform.cpp`): compare `PREFERRED_FEATURES` with `AddPreferredFeatures` in
     `tools/graphite/dawn/GraphiteDawnToggles.cpp`.
   `SkiaVulkanFeaturesTest` and `SkiaDawnFeaturesTest` fail where an offered feature is not granted; on
   Vulkan "offered" is what `VulkanPreferredFeatures` queried, so the test does not notice a feature it
   does not query: that is the count's job.
4. `scripts/fetch_skia.sh`, reconfigure, build with `--with-skia`, and run
   `make run-test TEST=jtreg:test/jdk/jb/java/awt/Graphics2D`. `SkiaGPUListTest` fails while the list
   was generated for another `SKIA_TAG` than the pin (configure warns about it too).
5. The legal notices for Skia and what it links (`src/java.desktop/windows/legal/`: skia.md, dawn.md,
   chromium.md; `src/java.desktop/share/legal/chromium-gpu-lists.md` for the generated list), when the release
   changed them: what is linked is in the linker map (`awt_skia.dll.map` next to the library in the build).

Notes. The Chromium revision is the head of the milestone's release branch at the time `update_gpu_lists.sh`
runs, so the same SKIA_TAG can yield a later revision on a later day (recorded in the generated file and the pin;
`-C <revision>` reproduces a run). libawt_skia carries the static MSVC runtime (the prebuilt is -MT): it is
serviced with the JBR, not by the Visual C++ redistributable.
