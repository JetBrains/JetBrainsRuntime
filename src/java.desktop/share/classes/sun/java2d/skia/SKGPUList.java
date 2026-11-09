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

package sun.java2d.skia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The GPU list: adapters and drivers on which a graphics API the backend tries
 * (Graphite on Dawn, Graphite on Vulkan) is not used, so that the backend falls
 * back to the next one ({@code SKGraphicsAPIFallback.startFirstUsableAPI}). The shipped
 * list ({@link SKGPUListData}) is generated from Chromium's GPU blocklist
 * ({@code gpu/config/software_rendering_list.json}), its driver bug list
 * ({@code gpu_driver_bug_list.json}) and Dawn's adapter rules at the revisions
 * that match the pinned Skia, by
 * {@code jb/project/tools/common/scripts/update_gpu_lists.sh} with every Skia
 * bump; {@code sun.java2d.skia.gpuBlocklist=<file>} replaces it with a list in the
 * same format, {@code =ignore} turns it off.
 * <p>
 * The format, one item per line (a line starting with {@code #} is a comment):
 * <pre>
 * entry &lt;id&gt;
 * description &lt;text&gt;
 * blocks dawn|vulkan ...
 * &lt;condition&gt;
 * except &lt;condition&gt; [; &lt;condition&gt; ...]
 * end
 * </pre>
 * where a condition is {@code osVersion <version>}, {@code vendor 0x<id>},
 * {@code devices 0x<id> ...}, {@code driver <version>}, {@code intelGeneration
 * <version>} or {@code d3d11FeatureLevel <version>}, and a version is {@code [intel] <op>
 * <value> [<value2>]} with the operators {@code = < <= > >= between any} ({@code intel}: Chromium's
 * intel_driver schema, comparing the last two of four segments). The
 * conditions of an entry all hold for the adapter, and no exception does, for
 * the entry to match. The semantics are Chromium's ({@code gpu_control_list.cc}
 * at the recorded revision): the conditions apply to the adapter the API would
 * use; a version compares segment by segment as numbers, a reference with fewer
 * segments than the version matches its prefix; an entry naming an Intel
 * generation matches Intel adapters of that generation only (its vendor is not
 * checked separately); an unknown driver version matches no driver condition (Chromium's
 * DriverInfo::Contains takes it as satisfied, but its NeedsMoreInfo then keeps the entry out of the
 * decision - the same outcome).
 */
public final class SKGPUList {

    /** The graphics APIs Graphite runs on, in the order the backend tries them; an entry blocks one or both. */
    public enum GraphiteAPI {
        DAWN("Dawn"), VULKAN("Vulkan");

        private final String displayName;

        GraphiteAPI(String displayName) {
            this.displayName = displayName;
        }

        /** The name in messages: {@code Dawn}, {@code Vulkan}. */
        public String displayName() {
            return displayName;
        }
    }

    /**
     * The adapter an API would use, as the platform describes it before a device is created:
     * PCI vendor and device, the driver's version as DXGI reports it (a.b.c.d), the OS version
     * (major.minor.build) and the highest Direct3D 11 feature level the adapter offers (12.1, 11.0, ...).
     */
    public record Adapter(int vendor, int device, String driver, String os, String d3d11FeatureLevel, String name) {

        /** The last item of an adapter line: the rest of the line is the name, spaces included. */
        private static final String NAME_ITEM = "name=";

        /**
         * Parses {@code vendor=0x10de device=0x2208 driver=32.0.15.6094 os=10.0.26300 fl=12.1 name=<rest of line>}.
         */
        public static Adapter parse(String line) {
            int vendor = 0, device = 0;
            String driver = "", os = "", featureLevel = "", name = "";
            String rest = line.trim();
            while (!rest.isEmpty()) {
                if (rest.startsWith(NAME_ITEM)) {
                    name = rest.substring(NAME_ITEM.length()).trim();
                    break;
                }
                int space = rest.indexOf(' ');
                String item = space < 0 ? rest : rest.substring(0, space);
                rest = space < 0 ? "" : rest.substring(space + 1).trim();
                int equalsSign = item.indexOf('=');
                if (equalsSign < 0) {
                    throw new IllegalArgumentException("adapter item without '=': " + item);
                }
                String key = item.substring(0, equalsSign), value = item.substring(equalsSign + 1);
                switch (key) {
                    case "vendor" -> vendor = parseID(value);
                    case "device" -> device = parseID(value);
                    case "driver" -> driver = value;
                    case "os" -> os = value;
                    case "fl" -> featureLevel = value;
                    default -> throw new IllegalArgumentException("unknown adapter item " + key);
                }
            }
            return new Adapter(vendor, device, driver, os, featureLevel, name);
        }

        @Override
        public String toString() {
            return String.format("%s (vendor 0x%04x, device 0x%04x, driver %s, Windows %s, D3D11 feature level %s)",
                                 name, vendor, device, driver, os, d3d11FeatureLevel);
        }
    }

    /**
     * A version condition: Chromium's {@code GpuControlList::Version}.
     *
     * @param operator       {@code = < <= > >= between any}
     * @param reference      the version compared against; empty for {@code any}
     * @param upperReference the upper bound of {@code between}; empty otherwise
     * @param intelDriver    Chromium's intel_driver schema: only the build, the last two of four segments, compares
     */
    record Version(String operator, List<Long> reference, List<Long> upperReference, boolean intelDriver) {

        private static final String ANY = "any";
        private static final String BETWEEN = "between";

        /** An Intel Windows driver version has four segments, and its build is the last two. */
        private static final int INTEL_DRIVER_SEGMENTS = 4;
        private static final int INTEL_BUILD_FIRST_SEGMENT = 2;

        /**
         * The most digits a version segment has; a longer run of digits is not a segment
         * (Chromium's segments are unsigned 32-bit numbers).
         */
        private static final int MAX_SEGMENT_DIGITS = 10;

        /** A version, or {@code intel <version>}: Chromium's intel_driver schema, see {@link #contains}. */
        static Version parse(List<String> tokens) {
            if (!tokens.isEmpty() && tokens.getFirst().equals("intel")) {
                Version version = parse(tokens.subList(1, tokens.size()));
                if (version.operator().equals(ANY)) {
                    return version;   // decided before the schema, as Chromium does
                }
                if (version.reference().size() != INTEL_DRIVER_SEGMENTS
                        || (version.operator().equals(BETWEEN) && version.upperReference().size() != INTEL_DRIVER_SEGMENTS)) {
                    throw new IllegalArgumentException("an intel driver version has four segments: " + tokens);
                }
                return new Version(version.operator(), intelBuild(version.reference()),
                                   version.upperReference().isEmpty() ? List.of() : intelBuild(version.upperReference()), true);
            }
            if (tokens.isEmpty()) {
                throw new IllegalArgumentException("a version needs an operator");
            }
            String operator = tokens.getFirst();
            return switch (operator) {
                case ANY -> new Version(operator, List.of(), List.of(), false);
                case "=", "<", "<=", ">", ">=" -> {
                    if (tokens.size() != 2) {
                        throw new IllegalArgumentException("'" + operator + "' takes one version: " + tokens);
                    }
                    yield new Version(operator, reference(tokens.get(1)), List.of(), false);
                }
                case BETWEEN -> {
                    if (tokens.size() != 3) {
                        throw new IllegalArgumentException("'between' takes two versions: " + tokens);
                    }
                    yield new Version(operator, reference(tokens.get(1)), reference(tokens.get(2)), false);
                }
                default -> throw new IllegalArgumentException("unknown version operator " + operator);
            };
        }

        /** The build of a four-segment Intel driver version: its last two segments. */
        private static List<Long> intelBuild(List<Long> segments) {
            return segments.subList(INTEL_BUILD_FIRST_SEGMENT, INTEL_DRIVER_SEGMENTS);
        }

        private static List<Long> reference(String value) {
            List<Long> segments = segments(value);
            if (segments == null) {
                throw new IllegalArgumentException("not a version: " + value);
            }
            return segments;
        }

        /**
         * Chromium's {@code ProcessVersionString}: the numeric segments up to the first that is not
         * a number; null if there is none or all are zero.
         */
        static List<Long> segments(String version) {
            List<Long> result = new ArrayList<>();
            for (String segment : version.split("\\.", -1)) {
                segment = segment.trim();
                if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit) || segment.length() > MAX_SEGMENT_DIGITS) {
                    break;
                }
                result.add(Long.parseLong(segment));
            }
            if (result.isEmpty() || result.stream().allMatch(segment -> segment == 0)) {
                return null;
            }
            return result;
        }

        /** Chromium's {@code Version::Compare}: segment by segment, as far as the reference goes. */
        private static int compare(List<Long> version, List<Long> reference) {
            for (int segment = 0; segment < reference.size(); segment++) {
                if (segment >= version.size()) {
                    return 0;
                }
                int comparison = Long.compare(version.get(segment), reference.get(segment));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        boolean contains(String versionText) {
            if (operator.equals(ANY)) {
                return true;
            }
            List<Long> version = segments(versionText);
            if (version == null) {
                return false;
            }
            if (intelDriver) {
                // Intel's Windows driver versions: only the last two segments (the build) compare,
                // and only a four-segment version is one (gpu_control_list.cc, kVersionSchemaIntelDriver)
                if (version.size() != INTEL_DRIVER_SEGMENTS) {
                    return false;
                }
                version = intelBuild(version);
            }
            int relation = compare(version, reference);
            return switch (operator) {
                case "=" -> relation == 0;
                case "<" -> relation < 0;
                case "<=" -> relation <= 0;
                case ">" -> relation > 0;
                case ">=" -> relation >= 0;
                case BETWEEN -> relation >= 0 && compare(version, upperReference) <= 0;
                default -> false;
            };
        }
    }

    /** The conditions of an entry or of one of its exceptions; absent ones are null or empty. */
    record Conditions(Version osVersion, int vendor, Set<Integer> devices, Version driver,
                      Version intelGeneration, Version d3d11FeatureLevel) {

        boolean matches(Adapter adapter) {
            if (osVersion != null && !osVersion.contains(adapter.os())) {
                return false;
            }
            if (intelGeneration != null) {
                int generation = SKGPUList.intelGeneration(adapter.vendor(), adapter.device());
                if (generation <= INTEL_UNKNOWN || !intelGeneration.contains(Integer.toString(generation))) {
                    return false;
                }
            } else if (vendor != 0) {
                if (adapter.vendor() != vendor || (!devices.isEmpty() && !devices.contains(adapter.device()))) {
                    return false;
                }
            }
            if (driver != null && !driver.contains(adapter.driver())) {
                return false;
            }
            return d3d11FeatureLevel == null || d3d11FeatureLevel.contains(adapter.d3d11FeatureLevel());
        }
    }

    /** An entry: the graphics APIs it blocks for the adapters it matches. */
    public record Entry(String id, String description, Set<GraphiteAPI> blocks, Conditions conditions, List<Conditions> exceptions) {

        boolean matches(Adapter adapter) {
            return conditions.matches(adapter) && exceptions.stream().noneMatch(exception -> exception.matches(adapter));
        }

        @Override
        public String toString() {
            return id + " (" + description + ")";
        }
    }

    /** The abbreviated revisions in names, as update_gpu_lists.sh abbreviates them (short_revision_length). */
    private static final int SHORT_REVISION_LENGTH = 12;

    private final List<Entry> entries;
    private final String source;

    private SKGPUList(List<Entry> entries, String source) {
        this.entries = List.copyOf(entries);
        this.source = source;
    }

    /**
     * The GPU list: the shipped one ({@link SKGPUListData}), the file {@code sun.java2d.skia.gpuBlocklist}
     * names instead, or none for {@code =ignore}. A file that cannot be read or parsed leaves the shipped
     * list in place, with a warning.
     */
    static SKGPUList load() {
        String requested = System.getProperty("sun.java2d.skia.gpuBlocklist");
        if ("ignore".equalsIgnoreCase(requested)) {
            return none();
        }
        if (requested != null && !requested.isEmpty()) {
            try {
                return parse(Files.readString(Path.of(requested)), requested);
            } catch (IOException | RuntimeException exception) {
                SKEnvironment.warn("sun.java2d.skia.gpuBlocklist=" + requested + " cannot be used (" + exception.getMessage()
                                   + "); the shipped GPU list applies");
            }
        }
        String shippedListName = "the shipped GPU list (Skia " + SKGPUListData.SKIA_TAG
                                 + ", Chromium " + SKGPUListData.CHROMIUM_REVISION.substring(0, SHORT_REVISION_LENGTH) + ")";
        try {
            return parse(SKGPUListData.LIST, shippedListName);
        } catch (IllegalArgumentException exception) {
            // a build defect (SkiaGPUListTest parses the shipped list); never a reason not to start
            SKEnvironment.warn(shippedListName + " cannot be read: " + exception.getMessage());
            return none();
        }
    }

    /** The empty list ({@code sun.java2d.skia.gpuBlocklist=ignore}). */
    public static SKGPUList none() {
        return new SKGPUList(List.of(), "no list (ignored)");
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Where the list came from, for {@code verbose}. */
    public String source() {
        return source;
    }

    /** The first entry that blocks the API for the adapter, or null if the API may be used. */
    public Entry blocking(GraphiteAPI api, Adapter adapter) {
        for (Entry entry : entries) {
            if (entry.blocks().contains(api) && entry.matches(adapter)) {
                return entry;
            }
        }
        return null;
    }

    /** Parses a list; throws IllegalArgumentException naming the line of the first error. */
    public static SKGPUList parse(String text, String source) {
        List<Entry> entries = new ArrayList<>();
        String id = null, description = "";
        Set<GraphiteAPI> blocks = EnumSet.noneOf(GraphiteAPI.class);
        ConditionsBuilder conditions = null;
        List<Conditions> exceptions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String[] lines = text.split("\r?\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String content = lines[lineIndex].trim();
            if (content.isEmpty() || content.startsWith("#")) {
                continue;
            }
            try {
                String keyword = firstWord(content);
                String rest = content.substring(keyword.length()).trim();
                if (keyword.equals("entry")) {
                    if (id != null) {
                        throw new IllegalArgumentException("entry " + id + " has no 'end'");
                    }
                    if (rest.isEmpty() || !ids.add(rest)) {
                        throw new IllegalArgumentException("an entry needs a unique id: '" + rest + "'");
                    }
                    id = rest;
                    description = "";
                    blocks = EnumSet.noneOf(GraphiteAPI.class);
                    conditions = new ConditionsBuilder();
                    exceptions = new ArrayList<>();
                    continue;
                }
                if (id == null) {
                    throw new IllegalArgumentException("'" + keyword + "' outside an entry");
                }
                switch (keyword) {
                    case "description" -> description = rest;
                    case "blocks" -> {
                        for (String apiName : words(rest)) {
                            blocks.add(GraphiteAPI.valueOf(apiName.toUpperCase(Locale.ROOT)));
                        }
                    }
                    case "except" -> {
                        ConditionsBuilder exception = new ConditionsBuilder();
                        for (String condition : rest.split(";")) {
                            exception.add(words(condition.trim()));
                        }
                        exceptions.add(exception.build());
                    }
                    case "end" -> {
                        if (blocks.isEmpty()) {
                            throw new IllegalArgumentException("entry " + id + " blocks nothing");
                        }
                        entries.add(new Entry(id, description, Collections.unmodifiableSet(blocks),
                                              conditions.build(), List.copyOf(exceptions)));
                        id = null;
                    }
                    default -> conditions.add(words(content));
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(source + ", line " + (lineIndex + 1) + ": " + exception.getMessage(), exception);
            }
        }
        if (id != null) {
            throw new IllegalArgumentException(source + ": entry " + id + " has no 'end'");
        }
        return new SKGPUList(entries, source);
    }

    private static final class ConditionsBuilder {
        private Version osVersion, driver, intelGeneration, d3d11FeatureLevel;
        private int vendor;
        private final Set<Integer> devices = new HashSet<>();

        void add(List<String> words) {
            if (words.isEmpty()) {
                throw new IllegalArgumentException("empty condition");
            }
            String condition = words.getFirst();
            List<String> arguments = words.subList(1, words.size());
            switch (condition) {
                case "osVersion" -> osVersion = Version.parse(arguments);
                case "vendor" -> {
                    if (arguments.size() != 1) {
                        throw new IllegalArgumentException("'vendor' takes one id");
                    }
                    vendor = parseID(arguments.getFirst());
                }
                case "devices" -> {
                    if (arguments.isEmpty()) {
                        throw new IllegalArgumentException("'devices' takes at least one id");
                    }
                    for (String deviceID : arguments) {
                        devices.add(parseID(deviceID));
                    }
                }
                case "driver" -> driver = Version.parse(arguments);
                case "intelGeneration" -> intelGeneration = Version.parse(arguments);
                case "d3d11FeatureLevel" -> d3d11FeatureLevel = Version.parse(arguments);
                default -> throw new IllegalArgumentException("unknown condition " + condition);
            }
        }

        Conditions build() {
            if (!devices.isEmpty() && vendor == 0) {
                throw new IllegalArgumentException("'devices' needs a 'vendor'");
            }
            return new Conditions(osVersion, vendor, Set.copyOf(devices), driver, intelGeneration, d3d11FeatureLevel);
        }
    }

    private static String firstWord(String line) {
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(0, space);
    }

    private static List<String> words(String line) {
        List<String> result = new ArrayList<>();
        for (String word : line.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                result.add(word);
            }
        }
        return result;
    }

    /** A PCI id is written {@code 0x} and one to four hex digits. */
    private static final String ID_PREFIX = "0x";
    private static final int ID_MAX_HEX_DIGITS = 4;

    static int parseID(String text) {
        String hex = text.toLowerCase(Locale.ROOT);
        if (!hex.startsWith(ID_PREFIX) || hex.length() == ID_PREFIX.length()
                || hex.length() > ID_PREFIX.length() + ID_MAX_HEX_DIGITS) {
            throw new IllegalArgumentException("not a 16-bit PCI id: " + text);
        }
        return Integer.parseInt(hex.substring(ID_PREFIX.length()), 16);
    }

    // ---- Intel GPU generations ------------------------------------------------------------------
    // A port of Chromium's GetIntelGpuSeriesType / GetIntelGpuGeneration(vendor, device)
    // (gpu/config/gpu_util.cc) and enum IntelGpuGeneration (gpu/config/device_perf_info.h), whose
    // values the generation numbers below are (kGen4 = 4 ... kGen11 = 11, then the Xe ones);
    // update_gpu_lists.sh compares their upstream sources with the fingerprints recorded in
    // the policy file and stops when they changed, so this port is reviewed with each bump.

    static final int INTEL_NONE = 0;
    static final int INTEL_UNKNOWN = 1;
    static final int INTEL_XE = 12, INTEL_XE2 = 13, INTEL_XE3 = 14;

    private static final int INTEL_VENDOR_ID = 0x8086;
    /** The high byte of an Intel device id names its family (gpu_util.cc masks it the same way). */
    private static final int INTEL_FAMILY_MASK = 0xFF00;

    public static int intelGeneration(int vendor, int device) {
        if (vendor != INTEL_VENDOR_ID) {
            return INTEL_NONE;
        }
        return switch (device & INTEL_FAMILY_MASK) {
            case 0x2900 -> 4;                                                 // Broadwater
            case 0x2A00 -> (device == 0x2A02 || device == 0x2A12 || device == 0x2A42)
                    ? 4 : INTEL_UNKNOWN;                                      // Broadwater, Eaglelake
            case 0x2E00 -> 4;                                                 // Eaglelake
            case 0x0000 -> 5;                                                 // Ironlake
            case 0x0100 -> {
                if (device == 0x0152 || device == 0x0156 || device == 0x015A
                        || device == 0x0162 || device == 0x0166 || device == 0x016A) {
                    yield 7;                                                  // Ivybridge
                }
                if (device == 0x0155 || device == 0x0157) {
                    yield 7;                                                  // Baytrail
                }
                yield 6;                                                      // Sandybridge
            }
            case 0x0F00 -> 7;                                                 // Baytrail
            case 0x0A00 -> device == 0x0A84 ? 9 : 7;                          // Apollolake, Haswell
            case 0x0400, 0x0C00, 0x0D00 -> 7;                                 // Haswell
            case 0x2200 -> 8;                                                 // Cherrytrail
            case 0x1600 -> 8;                                                 // Broadwell
            case 0x5A00 -> (device == 0x5A85 || device == 0x5A84) ? 9 : 10;   // Apollolake, Cannonlake
            case 0x1900 -> 9;                                                 // Skylake
            case 0x1A00 -> 9;                                                 // Apollolake
            case 0x3100 -> 9;                                                 // Geminilake
            case 0x5900 -> 9;                                                 // Amberlake, Kabylake
            case 0x8700 -> (device == 0x87C0 || device == 0x87CA) ? 9 : INTEL_UNKNOWN;   // Kabylake, Coffeelake
            case 0x3E00 -> 9;                                                 // Whiskeylake, Coffeelake
            case 0x9B00 -> 9;                                                 // Cometlake
            case 0x8A00 -> 11;                                                // Icelake
            case 0x4500 -> 11;                                                // Elkhartlake
            case 0x4E00 -> 11;                                                // Jasperlake
            case 0x9A00, 0x4C00, 0x4900, 0x4600, 0x4F00, 0x5600, 0xA700, 0x7D00, 0xB600 ->
                    INTEL_XE;     // Tigerlake, Rocketlake, DG1, Alderlake, Alchemist, Raptorlake, Meteorlake/Arrowlake
            case 0x6400, 0xE200 -> INTEL_XE2;                                 // Lunarlake, Battlemage
            case 0xB000 -> INTEL_XE3;                                         // Pantherlake
            default -> INTEL_UNKNOWN;
        };
    }
}
