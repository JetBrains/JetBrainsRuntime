/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @summary Regression test for JBR-10654: the Skia backend's GPU list matches adapters as
 *          Chromium's does, and the shipped list was generated for the pinned Skia.
 * @modules java.desktop/sun.java2d.skia
 * @run main SkiaGPUListTest
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import sun.java2d.skia.SKGPUList;
import sun.java2d.skia.SKGPUList.Adapter;
import sun.java2d.skia.SKGPUList.GraphiteAPI;
import sun.java2d.skia.SKGPUListData;

/**
 * Chromium's matching semantics on synthetic adapters (versions compared segment by segment with
 * the prefix rule, the Intel driver schema, exceptions, Intel generations, D3D11 feature levels,
 * OS versions), the shipped list's entries on adapters they must and must not catch, parse errors
 * with their line, and that the shipped list was generated for the pinned Skia (skia_prebuilt.conf:
 * SKIA_TAG and the recorded Chromium and Dawn revisions); a Skia bump regenerates it with
 * jb/project/tools/common/scripts/update_gpu_lists.sh. No GPU needed.
 */
public class SkiaGPUListTest {

    /** The shipped list has far more entries than this; fewer means the converter dropped most of Chromium's. */
    private static final int MIN_SHIPPED_ENTRIES = 10;

    /** A current NVIDIA adapter on Windows 10, as the native side describes one (SKGPUList.Adapter.parse). */
    private static final String NVIDIA_DRIVER = "32.0.15.6094";
    private static final String NVIDIA_ADAPTER = "vendor=0x10de device=0x2208 driver=" + NVIDIA_DRIVER
                                                 + " os=10.0.19045 fl=12.1 name=NVIDIA";

    /** A current Intel adapter on Windows 11. */
    private static final String INTEL_DRIVER = "31.0.101.5590";
    private static final String INTEL_ADAPTER = "vendor=0x8086 device=0x9a49 driver=" + INTEL_DRIVER
                                                + " os=10.0.22631 fl=12.1 name=Intel";

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        semantics();
        shippedEntries();
        parseErrors();
        pinned();
        if (!failures.isEmpty()) {
            throw new RuntimeException(failures.size() + " failures:\n  " + String.join("\n  ", failures));
        }
        System.out.println("PASSED");
    }

    /** The NVIDIA adapter with another driver version. */
    private static String nvidiaWithDriver(String driverVersion) {
        return NVIDIA_ADAPTER.replace(NVIDIA_DRIVER, driverVersion);
    }

    /** The Intel adapter with another driver version. */
    private static String intelWithDriver(String driverVersion) {
        return INTEL_ADAPTER.replace(INTEL_DRIVER, driverVersion);
    }

    /** Expects the list to block (or not) the API on the adapter; {@code expectedEntryId}: the id expected, or null. */
    private static void expect(SKGPUList list, GraphiteAPI api, String adapterLine, String expectedEntryId) {
        SKGPUList.Entry blockingEntry = list.blocking(api, Adapter.parse(adapterLine));
        String foundEntryId = blockingEntry == null ? null : blockingEntry.id();
        if (expectedEntryId == null ? foundEntryId != null : !expectedEntryId.equals(foundEntryId)) {
            failures.add(api + " on '" + adapterLine + "': expected "
                         + (expectedEntryId == null ? "no entry" : expectedEntryId)
                         + ", got " + (foundEntryId == null ? "no entry" : foundEntryId));
        }
    }

    private static SKGPUList list(String... lines) {
        return SKGPUList.parse(String.join("\n", lines), "test");
    }

    private static void semantics() {
        // a reference with fewer segments matches the prefix (Chromium's Version::Compare)
        SKGPUList prefixList = list("entry p", "blocks dawn", "vendor 0x10de", "driver = 32.0.15", "end");
        expect(prefixList, GraphiteAPI.DAWN, NVIDIA_ADAPTER, "p");
        expect(prefixList, GraphiteAPI.VULKAN, NVIDIA_ADAPTER, null);   // the entry blocks Dawn only
        expect(prefixList, GraphiteAPI.DAWN, nvidiaWithDriver("32.0.16.1714"), null);
        // numeric, segment by segment (not lexical: 9 < 10)
        SKGPUList lessThanList = list("entry l", "blocks dawn", "driver < 10.18.10.5161", "end");
        expect(lessThanList, GraphiteAPI.DAWN, nvidiaWithDriver("9.18.10.6000"), "l");
        expect(lessThanList, GraphiteAPI.DAWN, nvidiaWithDriver("10.18.10.5161"), null);
        expect(lessThanList, GraphiteAPI.DAWN, nvidiaWithDriver("10.18.10.5160"), "l");
        SKGPUList betweenList = list("entry b", "blocks dawn", "driver between 8.17.10.1000 8.17.10.2000", "end");
        expect(betweenList, GraphiteAPI.DAWN, nvidiaWithDriver("8.17.10.1500"), "b");
        expect(betweenList, GraphiteAPI.DAWN, nvidiaWithDriver("8.17.10.2001"), null);
        // an unknown driver version matches no driver condition; an all-zero version is none
        expect(lessThanList, GraphiteAPI.DAWN, "vendor=0x10de device=0x2208 os=10.0.19045 name=NVIDIA", null);
        expect(lessThanList, GraphiteAPI.DAWN, nvidiaWithDriver("0.0.0.0"), null);
        // Chromium's intel_driver schema: the last two of four segments
        SKGPUList intelSchemaList = list("entry i", "blocks vulkan", "vendor 0x8086", "driver intel < 30.0.101.2111", "end");
        expect(intelSchemaList, GraphiteAPI.VULKAN, INTEL_ADAPTER, null);
        expect(intelSchemaList, GraphiteAPI.VULKAN, intelWithDriver("27.20.100.9999"), "i");
        expect(intelSchemaList, GraphiteAPI.VULKAN, intelWithDriver("26.20.101.9999"), null);   // build 101.9999
        expect(intelSchemaList, GraphiteAPI.VULKAN, intelWithDriver("101.2000"), null);         // not four segments
        // devices, exceptions (with the vendor the converter copies from the entry)
        SKGPUList microsoftList = list("entry m", "blocks dawn vulkan", "vendor 0x1414",
                                       "except vendor 0x1414 ; devices 0x02c1 0xd000", "end");
        expect(microsoftList, GraphiteAPI.DAWN,
               "vendor=0x1414 device=0x008c driver=10.0.26100.1 os=10.0.26100 name=Basic", "m");
        expect(microsoftList, GraphiteAPI.VULKAN,
               "vendor=0x1414 device=0x02c1 driver=10.0.26100.1 os=10.0.26100 name=RemoteFX", null);
        // OS versions
        SKGPUList osVersionList = list("entry o", "blocks dawn", "osVersion < 10.0.22000.0", "vendor 0x10de", "end");
        expect(osVersionList, GraphiteAPI.DAWN, NVIDIA_ADAPTER, "o");
        expect(osVersionList, GraphiteAPI.DAWN, NVIDIA_ADAPTER.replace("os=10.0.19045", "os=10.0.22631"), null);
        // Intel generations: the adapter's own, Intel only, unknown devices never
        SKGPUList intelGenerationList = list("entry g", "blocks dawn vulkan", "vendor 0x8086", "intelGeneration <= 8", "end");
        expect(intelGenerationList, GraphiteAPI.DAWN,
               "vendor=0x8086 device=0x0a16 driver=20.19.15.5107 os=10.0.19045 fl=11.1 name=HD4400", "g");
        expect(intelGenerationList, GraphiteAPI.DAWN,
               "vendor=0x8086 device=0x1616 driver=20.19.15.5107 os=10.0.19045 name=HD5500", "g");
        expect(intelGenerationList, GraphiteAPI.DAWN,
               "vendor=0x8086 device=0x5917 driver=31.0.101.2111 os=10.0.19045 name=UHD620", null);
        expect(intelGenerationList, GraphiteAPI.DAWN,
               "vendor=0x8086 device=0x8701 driver=31.0.101.2111 os=10.0.19045 name=unknown", null);
        expect(intelGenerationList, GraphiteAPI.DAWN,
               "vendor=0x10de device=0x0a16 driver=31.0.101.2111 os=10.0.19045 name=not Intel", null);
        checkGeneration(0x8086, 0x9A49, 12);   // Tigerlake: Xe
        checkGeneration(0x8086, 0x64A0, 13);   // Lunarlake: Xe2
        checkGeneration(0x8086, 0x5A85, 9);    // Apollolake in the Cannonlake range
        checkGeneration(0x8086, 0x5A52, 10);   // Cannonlake
        checkGeneration(0x8086, 0x0152, 7);    // Ivybridge in the Sandybridge range
        checkGeneration(0x10DE, 0x0152, 0);    // not Intel
        // D3D11 feature levels; an adapter whose level is unknown matches no level condition
        SKGPUList featureLevelList = list("entry f", "blocks dawn vulkan", "d3d11FeatureLevel < 12.0", "end");
        expect(featureLevelList, GraphiteAPI.DAWN, NVIDIA_ADAPTER.replace("fl=12.1", "fl=11.1"), "f");
        expect(featureLevelList, GraphiteAPI.DAWN, NVIDIA_ADAPTER, null);
        expect(featureLevelList, GraphiteAPI.DAWN, NVIDIA_ADAPTER.replace(" fl=12.1", ""), null);
        // any, also under the Intel schema (decided before it, as Chromium does)
        expect(list("entry y", "blocks dawn", "driver any", "end"), GraphiteAPI.DAWN, NVIDIA_ADAPTER, "y");
        expect(list("entry y", "blocks dawn", "driver intel any", "end"), GraphiteAPI.DAWN, NVIDIA_ADAPTER, "y");
        // the Intel schema with between: the last two segments
        SKGPUList intelBetweenList = list("entry ib", "blocks vulkan", "vendor 0x8086",
                                          "driver intel between 0.0.100.1000 0.0.101.2000", "end");
        expect(intelBetweenList, GraphiteAPI.VULKAN, intelWithDriver("31.0.100.9999"), "ib");
        expect(intelBetweenList, GraphiteAPI.VULKAN, intelWithDriver("31.0.101.2001"), null);
        // an exception carrying an OS version
        SKGPUList osExceptionList = list("entry eo", "blocks dawn", "vendor 0x10de", "except osVersion >= 10.0.22000", "end");
        expect(osExceptionList, GraphiteAPI.DAWN, NVIDIA_ADAPTER, "eo");
        expect(osExceptionList, GraphiteAPI.DAWN, NVIDIA_ADAPTER.replace("os=10.0.19045", "os=10.0.22631"), null);
        // an adapter without an OS version matches no OS-version condition
        expect(osVersionList, GraphiteAPI.DAWN, NVIDIA_ADAPTER.replace(" os=10.0.19045", ""), null);
        // no conditions: every adapter
        expect(list("entry a", "blocks vulkan", "end"), GraphiteAPI.VULKAN, NVIDIA_ADAPTER, "a");
        expect(SKGPUList.none(), GraphiteAPI.DAWN, NVIDIA_ADAPTER, null);
    }

    private static void checkGeneration(int vendorId, int deviceId, int expectedGeneration) {
        int generation = SKGPUList.intelGeneration(vendorId, deviceId);
        if (generation != expectedGeneration) {
            failures.add(String.format("Intel generation of 0x%04x/0x%04x: expected %d, got %d",
                                       vendorId, deviceId, expectedGeneration, generation));
        }
    }

    private static void shippedEntries() {
        SKGPUList shipped = SKGPUList.parse(SKGPUListData.LIST, "shipped");
        if (shipped.entries().size() < MIN_SHIPPED_ENTRIES) {
            failures.add("the shipped list has only " + shipped.entries().size() + " entries");
        }
        String rtxAdapter = "vendor=0x10de device=0x2208 driver=32.0.16.1714 os=10.0.26300 fl=12.1 name=RTX 3080 Ti";
        expect(shipped, GraphiteAPI.DAWN, rtxAdapter, null);
        expect(shipped, GraphiteAPI.VULKAN, rtxAdapter, null);
        // VMware's adapter: no GPU acceleration at all (software_rendering_list #68)
        String vmwareAdapter = "vendor=0x15ad device=0x0405 driver=9.17.6.0 os=10.0.19045 fl=11.0 name=VMware SVGA 3D";
        expect(shipped, GraphiteAPI.DAWN, vmwareAdapter, "chromium/software_rendering_list#68");
        expect(shipped, GraphiteAPI.VULKAN, vmwareAdapter, "chromium/software_rendering_list#68");
        // Microsoft Basic Render Driver: blocked; RemoteFX: excepted (#74)
        expect(shipped, GraphiteAPI.DAWN, "vendor=0x1414 device=0x008c driver=10.0.26100.1 os=10.0.26100 fl=12.1 name=Basic",
               "chromium/software_rendering_list#74");
        expect(shipped, GraphiteAPI.DAWN, "vendor=0x1414 device=0x02c1 driver=10.0.26100.1 os=10.0.26100 fl=12.1 name=RemoteFX", null);
        // Intel HD 4400 on Windows 10 (#181, Graphite)
        expect(shipped, GraphiteAPI.DAWN, "vendor=0x8086 device=0x0a16 driver=20.19.15.5107 os=10.0.19045 fl=11.1 name=HD4400",
               "chromium/software_rendering_list#181");
        // an old NVIDIA driver on Windows 10: Direct3D 11 off (gpu_driver_bug_list #70), Vulkan allowed
        String oldNvidiaAdapter = "vendor=0x10de device=0x0fc6 driver=8.17.12.6973 os=10.0.19045 fl=11.0 name=GTX 650";
        expect(shipped, GraphiteAPI.DAWN, oldNvidiaAdapter.replace("fl=11.0", "fl=12.0"), "chromium/gpu_driver_bug_list#70");
        expect(shipped, GraphiteAPI.VULKAN, oldNvidiaAdapter.replace("fl=11.0", "fl=12.0"), null);
        // feature level 11.0: Graphite off (#188)
        expect(shipped, GraphiteAPI.VULKAN, oldNvidiaAdapter, "chromium/software_rendering_list#188");
        // AMD 8.17.10.*: blocked below 8.17.10.1433 (#166 and its exception)
        String amdAdapter = "vendor=0x1002 device=0x6798 driver=8.17.10.1129 os=10.0.19045 fl=12.0 name=HD 7970";
        expect(shipped, GraphiteAPI.DAWN, amdAdapter, "chromium/software_rendering_list#166");
        expect(shipped, GraphiteAPI.DAWN, amdAdapter.replace("8.17.10.1129", "8.17.10.1433"), null);
        // Dawn's rule for Intel's Vulkan driver, carried over for the native Vulkan API
        String oldIntelDriverAdapter = "vendor=0x8086 device=0x9a49 driver=27.20.100.9999 os=10.0.22631 fl=12.1 name=Iris Xe";
        expect(shipped, GraphiteAPI.VULKAN, oldIntelDriverAdapter, "dawn/PhysicalDeviceVk#intel-windows-driver");
        expect(shipped, GraphiteAPI.DAWN, oldIntelDriverAdapter, null);
    }

    private static void parseErrors() {
        expectError("entry x\nblocks dawn\nfoo 1\nend", "line 3");
        expectError("entry x\nblocks dawn\ndevices 0x1234\nend", "needs a 'vendor'");
        expectError("entry x\nend", "blocks nothing");
        expectError("entry x\nblocks dawn\nend\nentry x\nblocks dawn\nend", "unique id");
        expectError("entry x\nblocks dawn\ndriver ~ 1\nend", "unknown version operator");
        expectError("entry x\nblocks dawn\n", "no 'end'");
        expectError("blocks dawn", "outside an entry");
        expectError("entry x\nblocks metal\nend", "METAL");
        expectError("entry x\nblocks dawn\ndriver intel < 30.0.101\nend", "four segments");
    }

    private static void expectError(String listText, String expectedMessage) {
        String oneLine = listText.replace("\n", "|");
        try {
            SKGPUList.parse(listText, "test");
            failures.add("no error for '" + oneLine + "'");
        } catch (IllegalArgumentException exception) {
            if (!exception.getMessage().contains(expectedMessage)) {
                failures.add("'" + oneLine + "': expected '" + expectedMessage + "' in: " + exception.getMessage());
            }
        }
    }

    /** The shipped list follows the pin: regenerated with every Skia bump. */
    private static void pinned() throws Exception {
        Path conf = Path.of(System.getProperty("test.root"), "..", "..", "jb", "project", "tools", "common",
                            "skia_prebuilt.conf");
        if (!Files.exists(conf)) {
            System.out.println("no " + conf + " (not a JBR source tree): pin check skipped");
            return;
        }
        String confText = Files.readString(conf);
        checkPinned(confText, "SKIA_TAG", SKGPUListData.SKIA_TAG);
        checkPinned(confText, "SKIA_GPU_LISTS_CHROMIUM", SKGPUListData.CHROMIUM_REVISION);
        checkPinned(confText, "SKIA_GPU_LISTS_DAWN", SKGPUListData.DAWN_REVISION);
    }

    /** Expects the value the shipped list was generated for to be what skia_prebuilt.conf pins under the key. */
    private static void checkPinned(String confText, String key, String generatedFor) {
        String pinnedValue = null;
        for (String line : confText.split("\r?\n")) {
            if (line.startsWith(key + "=")) {
                pinnedValue = line.substring(key.length() + 1).trim();
            }
        }
        if (!generatedFor.equals(pinnedValue)) {
            failures.add("the shipped GPU list was made for " + key + "=" + generatedFor + ", skia_prebuilt.conf has "
                         + pinnedValue + ": run jb/project/tools/common/scripts/update_gpu_lists.sh after a Skia bump");
        }
    }
}
