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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts Chromium's GPU lists into the Skia backend's GPU list (SKGPUListData.java), as
 * policy.conf decides. Run by jb/project/tools/common/scripts/update_gpu_lists.sh, which fetches
 * the inputs at the revisions that match the pinned Skia:
 * <pre>
 * java GPUListConverter.java --policy policy.conf --sources &lt;dir&gt; --out SKGPUListData.java
 *      --skia-tag &lt;tag&gt; --chromium-revision &lt;sha&gt; --dawn-revision &lt;sha&gt;
 * </pre>
 * &lt;dir&gt; holds the fetched files under their upstream paths: chromium/gpu/config/*.json, the
 * Chromium and Dawn sources named by the policy's fingerprints. The previous --out file, if any, is
 * read first and the entries added, removed and changed are printed for the review of the bump.
 * Exit status 1 with a message on anything the policy does not decide.
 * <p>
 * Runs as a single-file source program on the developer's {@code java}, JDK 17 or later (the
 * floor update_gpu_lists.sh states): no language or library feature newer than 17 here.
 */
public class GPUListConverter {

    /** The keys of a Chromium entry or exception the matcher (SKGPUList.java) implements. */
    private static final Set<String> CONDITION_KEYS = Set.of(
            "os", "vendor_id", "device_id", "driver_version", "intel_gpu_generation", "d3d11_feature_level",
            "multi_gpu_category");
    /** Keys without effect on matching. */
    private static final Set<String> INERT_KEYS = Set.of(
            "id", "description", "cr_bugs", "webkit_bugs", "comment", "driver_update_url", "features",
            "exceptions", "test_group", "disabled_extensions", "disabled_webgl_extensions");
    /** The keys of a Chromium version object (gpu_control_list_format.txt); "comment" occurs in the lists. */
    private static final Set<String> VERSION_KEYS = Set.of("op", "value", "value2", "schema", "comment");
    private static final List<String> LISTS = List.of("software_rendering_list", "gpu_driver_bug_list");
    private static final List<String> REQUIRED_OPTIONS = List.of(
            "--policy", "--sources", "--out", "--skia-tag", "--chromium-revision", "--dawn-revision");
    /** Chromium's OS version is major.minor.build.UBR; the matcher (SKGPUList.java) compares the first three. */
    private static final int OS_VERSION_SEGMENTS_MATCHED = 3;
    /** The conditions of an entry go one per line; those of an exception on one line, joined by this. */
    private static final String ENTRY_CONDITION_SEPARATOR = "\n";
    private static final String EXCEPTION_CONDITION_SEPARATOR = " ; ";
    /** The keywords of the list format (SKGPUList.java) whose argument the review summary reads. */
    private static final String ENTRY_KEYWORD = "entry ";
    private static final String DESCRIPTION_KEYWORD = "description ";

    public static void main(String[] args) throws Exception {
        if (args.length % 2 != 0) {
            throw fail("unpaired argument: " + args[args.length - 1] + " (options come as --name value)");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int argumentIndex = 0; argumentIndex + 1 < args.length; argumentIndex += 2) {
            options.put(args[argumentIndex], args[argumentIndex + 1]);
        }
        for (String required : REQUIRED_OPTIONS) {
            if (!options.containsKey(required)) {
                throw fail("missing " + required);
            }
        }
        Path policyFile = Path.of(options.get("--policy"));
        Policy policy = Policy.read(policyFile);
        Path sourcesDir = Path.of(options.get("--sources"));
        Path outFile = Path.of(options.get("--out"));

        // 1. the hand ports against their upstream sources
        List<String> staleFingerprints = new ArrayList<>();
        Map<String, String> inputDigests = new TreeMap<>();
        for (Map.Entry<String, String> fingerprint : policy.fingerprints().entrySet()) {
            String[] parts = fingerprint.getKey().split(":", 3);   // repo:path:symbol
            Path sourceFile = sourcesDir.resolve(parts[0]).resolve(parts[1]);
            String actual = fingerprint(sourceFile, parts[2]);
            if (!actual.equals(fingerprint.getValue())) {
                staleFingerprints.add("fingerprint " + fingerprint.getKey() + " " + actual
                                      + "    (recorded: " + fingerprint.getValue() + ")");
            }
        }
        if (!staleFingerprints.isEmpty()) {
            throw fail("the upstream sources of these hand ports changed; review the ports (SKGPUList.java, "
                       + "GPUListConverter.java, the manual entries of policy.conf) against them, then record:\n  "
                       + String.join("\n  ", staleFingerprints));
        }

        // 2. the entries
        StringBuilder list = new StringBuilder();
        List<String> skipped = new ArrayList<>();
        Map<String, String> undecided = new LinkedHashMap<>();   // "ignore <list> <feature>" -> the first entry naming it
        int count = 0;
        for (String listName : LISTS) {
            Path listFile = sourcesDir.resolve("chromium/gpu/config/" + listName + ".json");
            byte[] listBytes = Files.readAllBytes(listFile);
            inputDigests.put("chromium/gpu/config/" + listName + ".json", sha256(listBytes));
            Map<String, Object> listJSON = obj(new JSON(new String(listBytes, StandardCharsets.UTF_8)).value());
            for (Object entryJSON : list(listJSON.get("entries"))) {
                Map<String, Object> entry = obj(entryJSON);
                String id = listName + "#" + str(entry.get("id"));
                if (!appliesOnWindows(entry)) {
                    continue;
                }
                if (entry.containsKey("test_group")) {
                    skipped.add(id + ": a test entry (test_group)");
                    continue;
                }
                Set<String> blockedAPIs = new LinkedHashSet<>();
                for (Object featureJSON : list(entry.get("features"))) {
                    if (!(featureJSON instanceof String feature)) {
                        throw fail(id + ": feature exceptions (" + featureJSON + ") are not implemented; decide in policy.conf");
                    }
                    List<String> mapped = policy.mappedFeatures().get(listName + " " + feature);
                    if (mapped != null) {
                        blockedAPIs.addAll(mapped);
                    } else if (!policy.ignoredFeatures().contains(listName + " " + feature)) {
                        undecided.putIfAbsent("ignore " + listName + " " + feature, id);
                    }
                }
                if (blockedAPIs.isEmpty()) {
                    continue;
                }
                String exclusionReason = policy.excludedEntries().get(id);
                if (exclusionReason != null) {
                    skipped.add(id + ": " + exclusionReason);
                    continue;
                }
                list.append("\nentry chromium/").append(id).append('\n');
                list.append("description ").append(oneLine(str(entry.getOrDefault("description", "")))).append('\n');
                list.append("blocks ").append(String.join(" ", blockedAPIs)).append('\n');
                list.append(conditions(id, entry, null, ENTRY_CONDITION_SEPARATOR));
                for (Object exceptionJSON : list(entry.getOrDefault("exceptions", List.of()))) {
                    Map<String, Object> exception = obj(exceptionJSON);
                    if (!appliesOnWindows(exception)) {
                        continue;   // an exception for another OS never applies here
                    }
                    String exceptionConditions = conditions(id + " (exception)", exception, entry, EXCEPTION_CONDITION_SEPARATOR);
                    if (exceptionConditions.isEmpty()) {
                        // it would match every Windows machine, voiding the entry: Chromium's meaning, but surely
                        // not the list author's; decide it in policy.conf (exclude the entry) rather than guess
                        throw fail(id + " has an exception that converts to no condition (it would void the entry on Windows)");
                    }
                    // without the separator that follows the last condition
                    list.append("except ").append(exceptionConditions, 0,
                                exceptionConditions.length() - EXCEPTION_CONDITION_SEPARATOR.length()).append('\n');
                }
                list.append("end\n");
                count++;
            }
        }
        if (!undecided.isEmpty()) {
            throw fail("policy.conf decides nothing for these features of Windows entries; map them to APIs or ignore\n"
                       + "them (with the reason), after reading the entries that name them:\n  " + String.join("\n  ",
                          undecided.entrySet().stream().map(undecidedFeature -> undecidedFeature.getKey()
                                  + "    # first named by " + undecidedFeature.getValue()).toList()));
        }
        for (String manualEntry : policy.manualEntries()) {
            list.append('\n').append(manualEntry);
            count++;
        }

        // 3. the review summary against the previous list
        Map<String, String> before = Files.exists(outFile) ? entries(Files.readString(outFile)) : Map.of();
        Map<String, String> after = entries(list.toString());
        System.out.println(count + " entries (" + skipped.size() + " left out: " + String.join("; ", skipped) + ")");
        for (String id : after.keySet()) {
            if (!before.containsKey(id)) {
                System.out.println("added    " + id + ": " + description(after.get(id)));
            } else if (!before.get(id).equals(after.get(id))) {
                System.out.println("changed  " + id + ": " + description(after.get(id)));
            }
        }
        for (String id : before.keySet()) {
            if (!after.containsKey(id)) {
                System.out.println("removed  " + id + ": " + description(before.get(id)));
            }
        }

        // 4. the generated source
        StringBuilder source = new StringBuilder();
        source.append(Files.readString(policyFile.resolveSibling("SKGPUListData.header")));
        source.append("\n// GENERATED by jb/project/tools/common/scripts/update_gpu_lists.sh (GPUListConverter.java,\n");
        source.append("// policy.conf) from:\n");
        source.append("//   Chromium ").append(options.get("--chromium-revision")).append('\n');
        source.append("//   Dawn ").append(options.get("--dawn-revision")).append('\n');
        for (Map.Entry<String, String> inputDigest : inputDigests.entrySet()) {
            source.append("//   ").append(inputDigest.getKey()).append(" sha256 ").append(inputDigest.getValue()).append('\n');
        }
        source.append("// Do not edit; run update_gpu_lists.sh with every Skia bump.\n\n");
        source.append("package sun.java2d.skia;\n\n");
        source.append("/** The shipped GPU list ({@link SKGPUList}); generated, see the header. */\n");
        source.append("public final class SKGPUListData {\n\n");
        source.append("    /** The Skia release this list was made for (skia_prebuilt.conf SKIA_TAG). */\n");
        source.append("    public static final String SKIA_TAG = \"").append(options.get("--skia-tag")).append("\";\n");
        source.append("    /** The Chromium revision the lists were taken from; named in verbose output (SKGPUList.load). */\n");
        source.append("    public static final String CHROMIUM_REVISION = \"").append(options.get("--chromium-revision")).append("\";\n");
        source.append("    /** The Dawn revision whose adapter rules the list carries; checked by SkiaGPUListTest. */\n");
        source.append("    @SuppressWarnings(\"unused\")\n");
        source.append("    public static final String DAWN_REVISION = \"").append(options.get("--dawn-revision")).append("\";\n\n");
        source.append("    public static final String LIST = \"\"\"\n");
        for (String line : list.toString().strip().split("\n", -1)) {
            source.append("        ").append(line.replace("\\", "\\\\")).append('\n');
        }
        source.append("        \"\"\";\n\n    private SKGPUListData() {}\n}\n");
        Files.writeString(outFile, source.toString().replace("        \n", "\n"));
        System.out.println("wrote " + outFile);
    }

    /** Whether an entry or exception is for Windows or for every OS (no "os", or os.type "any"). */
    private static boolean appliesOnWindows(Map<String, Object> entryOrException) {
        String osType = entryOrException.containsKey("os") ? str(obj(entryOrException.get("os")).get("type")) : "any";
        return osType.equals("win") || osType.equals("any");
    }

    /**
     * The conditions of an entry or exception as SKGPUList lines, each followed by {@code separator}. An
     * exception's devices are the parent entry's vendor's when the exception names none itself (Chromium).
     */
    private static String conditions(String id, Map<String, Object> entryOrException, Map<String, Object> parent,
                                     String separator) {
        StringBuilder lines = new StringBuilder();
        for (String key : entryOrException.keySet()) {
            if (!CONDITION_KEYS.contains(key) && !INERT_KEYS.contains(key)) {
                throw fail(id + " uses '" + key + "', which SKGPUList does not implement");
            }
        }
        if (entryOrException.containsKey("os")) {
            Map<String, Object> os = obj(entryOrException.get("os"));
            for (String osKey : os.keySet()) {
                if (!osKey.equals("type") && !osKey.equals("version")) {
                    throw fail(id + " uses os." + osKey);
                }
            }
            if (os.containsKey("version")) {
                if (!str(os.get("type")).equals("win")) {
                    throw fail(id + " gives an OS version for os type '" + os.get("type") + "'");
                }
                Map<String, Object> osVersion = obj(os.get("version"));
                for (String valueKey : List.of("value", "value2")) {
                    String[] segments = osVersion.containsKey(valueKey) ? str(osVersion.get(valueKey)).split("[.]") : new String[0];
                    if (segments.length > OS_VERSION_SEGMENTS_MATCHED && !segments[OS_VERSION_SEGMENTS_MATCHED].matches("0*")) {
                        throw fail(id + " compares the OS version's fourth segment (" + osVersion.get(valueKey)
                                   + "), which the matcher does not read");
                    }
                }
                lines.append("osVersion ").append(version(id, osVersion)).append(separator);
            }
        }
        if (entryOrException.containsKey("multi_gpu_category") && !str(entryOrException.get("multi_gpu_category")).equals("active")) {
            throw fail(id + " uses multi_gpu_category '" + entryOrException.get("multi_gpu_category")
                       + "'; only 'active' is the adapter in use");
        }
        Object vendor = entryOrException.containsKey("vendor_id") ? entryOrException.get("vendor_id")
                : (entryOrException.containsKey("device_id") && parent != null ? parent.get("vendor_id") : null);
        if (vendor != null) {
            lines.append("vendor ").append(str(vendor)).append(separator);
        }
        if (entryOrException.containsKey("device_id")) {
            List<String> deviceIDs = new ArrayList<>();
            for (Object deviceID : list(entryOrException.get("device_id"))) {
                deviceIDs.add(str(deviceID));
            }
            lines.append("devices ").append(String.join(" ", deviceIDs)).append(separator);
        }
        if (entryOrException.containsKey("driver_version")) {
            lines.append("driver ").append(version(id, obj(entryOrException.get("driver_version")))).append(separator);
        }
        if (entryOrException.containsKey("intel_gpu_generation") && entryOrException.containsKey("device_id")) {
            throw fail(id + " names devices and an intel_gpu_generation: Chromium ignores the devices then");
        }
        if (entryOrException.containsKey("intel_gpu_generation")) {
            lines.append("intelGeneration ").append(version(id, obj(entryOrException.get("intel_gpu_generation")))).append(separator);
        }
        if (entryOrException.containsKey("d3d11_feature_level")) {
            lines.append("d3d11FeatureLevel ").append(version(id, obj(entryOrException.get("d3d11_feature_level")))).append(separator);
        }
        return lines.toString();
    }

    /** A Chromium version object as the matcher's "[intel] <op> <value> [<value2>]" or "any". */
    private static String version(String id, Map<String, Object> versionJSON) {
        for (String key : versionJSON.keySet()) {
            if (!VERSION_KEYS.contains(key)) {
                throw fail(id + " uses a version with '" + key + "'");
            }
        }
        String prefix = "";
        if (versionJSON.containsKey("schema")) {
            if (!str(versionJSON.get("schema")).equals("intel_driver")) {
                throw fail(id + " uses the version schema '" + versionJSON.get("schema") + "'");
            }
            prefix = "intel ";
        }
        String op = str(versionJSON.get("op"));
        if (op.equals("any")) {
            return "any";   // before any schema, as Chromium decides it
        }
        return prefix + op + " " + str(versionJSON.get("value"))
               + (versionJSON.containsKey("value2") ? " " + str(versionJSON.get("value2")) : "");
    }

    /** id -> the entry's lines, of a list or of a generated SKGPUListData.java. */
    private static Map<String, String> entries(String text) {
        Map<String, String> result = new TreeMap<>();
        String id = null;
        StringBuilder body = new StringBuilder();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.strip();
            if (line.startsWith(ENTRY_KEYWORD)) {
                id = line.substring(ENTRY_KEYWORD.length());
                body.setLength(0);
            } else if (id != null && line.equals("end")) {
                result.put(id, body.toString());
                id = null;
            } else if (id != null) {
                body.append(line).append('\n');
            }
        }
        return result;
    }

    private static String description(String entryBody) {
        for (String line : entryBody.split("\n")) {
            if (line.startsWith(DESCRIPTION_KEYWORD)) {
                return line.substring(DESCRIPTION_KEYWORD.length());
            }
        }
        return "";
    }

    /**
     * The SHA-256 of a definition in a source file: from the line in column 0 that names {@code symbol} as a
     * whole name, followed by '(' or '{' and not ending with ';', to the next line that starts with '}'; trailing
     * whitespace ignored. The symbol must be defined once; {@code name(type} names one overload, {@code name*} all
     * definitions of the name together (overloads that cannot be told apart by their first line).
     */
    static String fingerprint(Path file, String spec) throws IOException, NoSuchAlgorithmException {
        final boolean allDefinitions = spec.endsWith("*");
        final String symbol = allDefinitions ? spec.substring(0, spec.length() - 1) : spec;
        List<String> lines = Files.readAllLines(file);
        String found = null;
        StringBuilder definitions = new StringBuilder();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            // a definition starts in column 0 (call sites and declarations inside bodies are indented)
            if (line.isEmpty() || Character.isWhitespace(line.charAt(0)) || line.strip().endsWith(";")) {
                continue;
            }
            for (int at = line.indexOf(symbol); at >= 0; at = line.indexOf(symbol, at + 1)) {
                // the whole name: not the end of a longer one (IntelConditions::Contains for Conditions::Contains)
                char before = at > 0 ? line.charAt(at - 1) : ' ';
                if (Character.isLetterOrDigit(before) || before == '_') {
                    continue;
                }
                // a symbol with its '(' (and first parameter) names one overload; otherwise '(' or '{' must follow
                String after = line.substring(at + symbol.length()).stripLeading();
                if (!symbol.contains("(") && !after.startsWith("(") && !after.startsWith("{")) {
                    continue;
                }
                if (found != null && !allDefinitions) {
                    throw fail(file + ": " + symbol + " is defined more than once; name one overload (name(type) or all (name*)");
                }
                StringBuilder definition = new StringBuilder();
                for (int bodyIndex = lineIndex; bodyIndex < lines.size(); bodyIndex++) {
                    definition.append(lines.get(bodyIndex).stripTrailing()).append('\n');
                    if (bodyIndex > lineIndex && lines.get(bodyIndex).startsWith("}")) {
                        break;
                    }
                }
                definitions.append(definition);
                found = sha256(definitions.toString().getBytes(StandardCharsets.UTF_8));
                break;
            }
        }
        if (found == null) {
            throw fail(file + ": no definition of " + symbol);
        }
        return found;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * Prints the message and exits with status 1. Declared to return the exception it never gets to throw, so a
     * caller writes {@code throw fail(...)} and the compiler knows the statement does not complete.
     */
    private static RuntimeException fail(String message) {
        System.err.println("GPUListConverter: " + message);
        System.exit(1);
        return new IllegalStateException(message);
    }

    // ---- policy.conf ----

    /**
     * The decisions of policy.conf. Keys are "{@code <list> <feature>}" for mapped and ignored features and
     * "{@code <list>#<id>}" for excluded entries; a fingerprint's key is "{@code <repo>:<path>:<symbol>}".
     */
    record Policy(Map<String, List<String>> mappedFeatures, Set<String> ignoredFeatures,
                  Map<String, String> excludedEntries, Map<String, String> fingerprints, List<String> manualEntries) {

        /** The fields of an exclude line: "exclude <list> <id> <reason...>"; the reason may contain blanks. */
        private static final int EXCLUDE_FIELDS = 4;
        /** The fields before the APIs of a map line: "map <list> <feature> <api>...". */
        private static final int MAP_FIELDS_BEFORE_APIS = 3;

        static Policy read(Path file) throws IOException {
            Map<String, List<String>> mappedFeatures = new LinkedHashMap<>();
            Set<String> ignoredFeatures = new LinkedHashSet<>();
            Map<String, String> excludedEntries = new LinkedHashMap<>();
            Map<String, String> fingerprints = new LinkedHashMap<>();
            List<String> manualEntries = new ArrayList<>();
            StringBuilder manualEntry = null;
            for (String rawLine : Files.readAllLines(file)) {
                String line = rawLine.strip();
                if (manualEntry != null) {
                    if (line.equals("endmanual")) {
                        manualEntries.add(manualEntry.toString());
                        manualEntry = null;
                    } else if (!line.isEmpty() && !line.startsWith("#")) {
                        manualEntry.append(line).append('\n');
                    }
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] words = line.split("\\s+");
                requireFields(line, words, switch (words[0]) {
                    case "map" -> MAP_FIELDS_BEFORE_APIS + 1;   // at least one API
                    case "ignore", "fingerprint" -> 3;
                    case "exclude" -> EXCLUDE_FIELDS;
                    default -> 1;
                });
                switch (words[0]) {
                    case "map" -> mappedFeatures.put(words[1] + " " + words[2],
                                                     List.of(words).subList(MAP_FIELDS_BEFORE_APIS, words.length));
                    case "ignore" -> ignoredFeatures.add(words[1] + " " + words[2]);
                    case "exclude" -> excludedEntries.put(words[1] + "#" + words[2],
                                                          line.split("\\s+", EXCLUDE_FIELDS)[EXCLUDE_FIELDS - 1]);
                    case "fingerprint" -> fingerprints.put(words[1], words[2]);
                    case "manual" -> manualEntry = new StringBuilder();
                    default -> throw fail("policy.conf: unknown line: " + line);
                }
            }
            return new Policy(mappedFeatures, ignoredFeatures, excludedEntries, fingerprints, manualEntries);
        }

        /** A policy line with fewer fields than its keyword takes stops the converter with the line, not an exception. */
        private static void requireFields(String line, String[] words, int fields) {
            if (words.length < fields) {
                throw fail("policy.conf: '" + line + "' has too few fields: " + USAGE.get(words[0]));
            }
        }

        private static final Map<String, String> USAGE = Map.of(
                "map", "map <list> <feature> <api>...",
                "ignore", "ignore <list> <feature>",
                "exclude", "exclude <list> <id> <reason...>",
                "fingerprint", "fingerprint <symbol> <sha256>");
    }

    // ---- a small JSON reader (objects, arrays, strings, numbers, true/false/null) ----

    static final class JSON {
        /** The hex digits of a \\uXXXX escape. */
        private static final int UNICODE_ESCAPE_DIGITS = 4;
        private static final List<String> LITERALS = List.of("true", "false", "null");

        private final String text;
        private int position;

        JSON(String text) {
            this.text = text;
        }

        Object value() {
            skipWhitespace();
            char first = text.charAt(position);
            if (first == '{') {
                Map<String, Object> object = new LinkedHashMap<>();
                position++;
                skipWhitespace();
                if (text.charAt(position) == '}') {
                    position++;
                    return object;
                }
                while (true) {
                    skipWhitespace();
                    String key = string();
                    skipWhitespace();
                    expect(':');
                    object.put(key, value());
                    skipWhitespace();
                    if (text.charAt(position) == ',') {
                        position++;
                        continue;
                    }
                    expect('}');
                    return object;
                }
            }
            if (first == '[') {
                List<Object> array = new ArrayList<>();
                position++;
                skipWhitespace();
                if (text.charAt(position) == ']') {
                    position++;
                    return array;
                }
                while (true) {
                    array.add(value());
                    skipWhitespace();
                    if (text.charAt(position) == ',') {
                        position++;
                        continue;
                    }
                    expect(']');
                    return array;
                }
            }
            if (first == '"') {
                return string();
            }
            for (String literal : LITERALS) {
                if (text.startsWith(literal, position)) {
                    position += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            if (start == position) {
                throw new IllegalArgumentException("JSON: unexpected '" + first + "' at " + position);
            }
            return Double.valueOf(text.substring(start, position));
        }

        private String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (text.charAt(position) != '"') {
                char current = text.charAt(position++);
                if (current == '\\') {
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case 'n' -> value.append('\n');
                        case 't' -> value.append('\t');
                        case 'r' -> value.append('\r');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'u' -> {
                            value.append((char) Integer.parseInt(text.substring(position, position + UNICODE_ESCAPE_DIGITS), 16));
                            position += UNICODE_ESCAPE_DIGITS;
                        }
                        default -> value.append(escaped);
                    }
                } else {
                    value.append(current);
                }
            }
            position++;
            return value.toString();
        }

        private void skipWhitespace() {
            while (position < text.length()) {
                char current = text.charAt(position);
                if (current == '/' && position + 1 < text.length() && text.charAt(position + 1) == '/') {
                    // comments in Chromium's JSON files
                    while (position < text.length() && text.charAt(position) != '\n') {
                        position++;
                    }
                } else if (Character.isWhitespace(current)) {
                    position++;
                } else {
                    break;
                }
            }
        }

        private void expect(char expected) {
            if (text.charAt(position) != expected) {
                throw new IllegalArgumentException("JSON: expected '" + expected + "' at " + position
                                                   + ", found '" + text.charAt(position) + "'");
            }
            position++;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    /** A JSON value as the list's text: numbers (Chromium's ids) without the fraction the reader gives them. */
    private static String str(Object value) {
        return value instanceof Double number ? Long.toString(number.longValue()) : String.valueOf(value);
    }
}
