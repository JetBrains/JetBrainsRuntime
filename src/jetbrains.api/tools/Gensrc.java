/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.*;
import static java.util.regex.Pattern.compile;

/**
 * Codegen script for {@code jetbrains.api} module.
 * It produces "main" {@link com.jetbrains.JBR} class from template by
 * inspecting interfaces and implementation code and inserting
 * static utility methods for public services as well as some metadata
 * needed by JBR at runtime.
 */
public class Gensrc {

    private static Path srcroot, src, gensrc;
    private static String apiVersion;
    private static JBRModules modules;

    /**
     * <ul>
     *     <li>$0 - absolute path to {@code JetBrainsRuntime/src} dir</li>
     *     <li>$1 - absolute path to jbr-api output dir ({@code JetBrainsRuntime/build/<conf>/jbr-api})</li>
     *     <li>$2 - {@code JBR} part of API version</li>
     * </ul>
     */
    public static void main(String[] args) throws IOException {
        srcroot = Path.of(args[0]);
        Path module = srcroot.resolve("jetbrains.api");
        src = module.resolve("src");
        Path output = Path.of(args[1]);
        gensrc = output.resolve("gensrc");
        Files.createDirectories(gensrc);

        Properties props = new Properties();
        props.load(Files.newInputStream(module.resolve("version.properties")));
        apiVersion = args[2] + "." + props.getProperty("VERSION");
        Files.writeString(output.resolve("jbr-api.version"), apiVersion,
                CREATE, WRITE, TRUNCATE_EXISTING);

        modules = new JBRModules();
        generateFiles();
    }

    private static void generateFiles() throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Path rel = src.relativize(file);
                    Path output = gensrc.resolve(rel);
                    Files.createDirectories(output.getParent());
                    String content = generateContent(file.getFileName().toString(), Files.readString(file));
                    Files.writeString(output, content, CREATE, WRITE, TRUNCATE_EXISTING);
                    return FileVisitResult.CONTINUE;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    private static String generateContent(String fileName, String content) throws IOException {
        return switch (fileName) {
            case "JBR.java" -> JBR.generate(content);
            default -> generate(content);
        };
    }

    private static String generate(String content) throws IOException {
        Pattern pattern = compile("/\\*CONST ((?:[a-zA-Z0-9]+\\.)+)([a-zA-Z0-9_*]+)\s*\\*/");
        for (;;) {
            Matcher matcher = pattern.matcher(content);
            if (!matcher.find()) return content;
            String placeholder = matcher.group(0), file = matcher.group(1), name = matcher.group(2);
            file = file.substring(0, file.length() - 1).replace('.', '/') + ".java";
            List<String> statements = new ArrayList<>();
            for (Path module : modules.paths) {
                Path f = module.resolve("share/classes").resolve(file);
                if (Files.exists(f)) {
                    Pattern namePattern = compile(name.replaceAll("\\*", "[a-zA-Z0-9_]+") + "\s*=");
                    Pattern statementPattern = compile("((?:(?:public|protected|private|static|final)\s+){2,3})([a-zA-Z0-9]+)\s+([^;]+);");
                    Matcher statementMatcher = statementPattern.matcher(Files.readString(f));
                    while (statementMatcher.find()) {
                        String mods = statementMatcher.group(1);
                        if (!mods.contains("static") || !mods.contains("final")) continue;
                        for (String s : statementMatcher.group(3).split(",")) {
                            if (!namePattern.matcher(s).find()) continue;
                            statements.add("public static final " + statementMatcher.group(2) + " " + s.strip() + ";");
                        }
                    }
                    break;
                }
            }
            if (statements.isEmpty()) throw new RuntimeException("Constant not found: " + placeholder);
            content = replaceTemplate(content, placeholder, statements, true);
        }
    }

    private static String findRegex(String src, Pattern regex) {
        Matcher matcher = regex.matcher(src);
        if (!matcher.find()) throw new IllegalArgumentException("Regex not found: " + regex.pattern());
        return matcher.group(1);
    }

    private static String replaceTemplate(String src, String placeholder, Iterable<String> statements, boolean compact) {
        int placeholderIndex = src.indexOf(placeholder);
        int indent = 0;
        while (placeholderIndex - indent >= 1 && src.charAt(placeholderIndex - indent - 1) == ' ') indent++;
        int nextLineIndex = src.indexOf('\n', placeholderIndex + placeholder.length()) + 1;
        if (nextLineIndex == 0) nextLineIndex = placeholderIndex + placeholder.length();
        String before = src.substring(0, placeholderIndex - indent), after = src.substring(nextLineIndex);
        StringBuilder sb = new StringBuilder(before);
        boolean firstStatement = true;
        for (String s : statements) {
            if (!firstStatement && !compact) sb.append('\n');
            sb.append(s.indent(indent));
            firstStatement = false;
        }
        sb.append(after);
        return sb.toString();
    }

    /**
     * Code for generating {@link com.jetbrains.JBR} class.
     */
    private static class JBR {

        private static String generate(String content) {
            Service[] interfaces = findPublicServiceInterfaces();
            List<String> statements = new ArrayList<>();
            for (Service i : interfaces) statements.add(generateMethodsForService(i));
            content = replaceTemplate(content, "/*GENERATED_METHODS*/", statements, false);
            content = content.replace("/*KNOWN_SERVICES*/",
                    modules.services.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
            content = content.replace("/*KNOWN_PROXIES*/",
                    modules.proxies.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
            content = content.replace("/*API_VERSION*/", apiVersion);
            return content;
        }

        private static Service[] findPublicServiceInterfaces() {
            Pattern javadocPattern = Pattern.compile("/\\*\\*((?:.|\n)*?)(\s|\n)*\\*/");
            return modules.services.stream()
                    .map(fullName -> {
                        if (fullName.indexOf('$') != -1) return null; // Only top level services can be public
                        Path path = src.resolve(fullName.replace('.', '/') + ".java");
                        String name = fullName.substring(fullName.lastIndexOf('.') + 1);
                        try {
                            String content = Files.readString(path);
                            int indexOfDeclaration = content.indexOf("public interface " + name);
                            if (indexOfDeclaration == -1) return null;
                            Matcher javadocMatcher = javadocPattern.matcher(content.substring(0, indexOfDeclaration));
                            String javadoc;
                            int javadocEnd;
                            if (javadocMatcher.find()) {
                                javadoc = javadocMatcher.group(1);
                                javadocEnd = javadocMatcher.end();
                            } else {
                                javadoc = "";
                                javadocEnd = 0;
                            }
                            return new Service(name, javadoc,
                                    content.substring(javadocEnd, indexOfDeclaration).contains("@Deprecated"));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(Objects::nonNull).toArray(Service[]::new);
        }

        private static String generateMethodsForService(Service service) {
            return """
                    private static class $__Holder {<DEPRECATED>
                        private static final $ INSTANCE = api != null ? api.getService($.class) : null;
                    }
                    /**
                     * @return true if current runtime has implementation for all methods in {@link $}
                     * and its dependencies (can fully implement given service).
                     * @see #get$()
                     */<DEPRECATED>
                    public static boolean is$Supported() {
                        return $__Holder.INSTANCE != null;
                    }
                    /**<JAVADOC>
                     * @return full implementation of {@link $} service if any, or {@code null} otherwise
                     */<DEPRECATED>
                    public static $ get$() {
                        return $__Holder.INSTANCE;
                    }
                    """
                    .replaceAll("\\$", service.name)
                    .replace("<JAVADOC>", service.javadoc)
                    .replaceAll("<DEPRECATED>", service.deprecated ? "\n@Deprecated" : "");
        }

        private record Service(String name, String javadoc, boolean deprecated) {}
    }

    /**
     * Finds and analyzes JBR API implementation modules and collects proxy definitions.
     */
    private static class JBRModules {

        private final Path[] paths;
        private final Set<String> proxies = new HashSet<>(), services = new HashSet<>();

        private JBRModules() throws IOException {
            String[] moduleNames = findJBRApiModules();
            paths = findPotentialJBRApiContributorModules();
            for (String moduleName : moduleNames) {
                Path module = findJBRApiModuleFile(moduleName, paths);
                findInModule(Files.readString(module));
            }
        }

        private void findInModule(String content) {
            Pattern servicePattern = compile("(service|proxy|twoWayProxy)\s*\\(([^)]+)");
            Matcher matcher = servicePattern.matcher(content);
            while (matcher.find()) {
                String type = matcher.group(1);
                String parameters = matcher.group(2);
                String interfaceName = extractFromStringLiteral(parameters.substring(0, parameters.indexOf(',')));
                if (type.equals("service")) services.add(interfaceName);
                else proxies.add(interfaceName);
            }
        }

        private static String extractFromStringLiteral(String value) {
            value = value.strip();
            return value.substring(1, value.length() - 1);
        }

        private static Path findJBRApiModuleFile(String module, Path[] potentialPaths) throws FileNotFoundException {
            for (Path p : potentialPaths) {
                Path m = p.resolve("share/classes").resolve(module + ".java");
                if (Files.exists(m)) return m;
            }
            throw new FileNotFoundException("JBR API module file not found: " + module);
        }

        private static String[] findJBRApiModules() throws IOException {
            String bootstrap = Files.readString(
                    srcroot.resolve("java.base/share/classes/com/jetbrains/bootstrap/JBRApiBootstrap.java"));
            Pattern modulePattern = compile("\"([^\"]+)");
            return Stream.of(findRegex(bootstrap, compile("MODULES *=([^;]+)")).split(","))
                    .map(m -> findRegex(m, modulePattern).replace('.', '/')).toArray(String[]::new);
        }

        private static Path[] findPotentialJBRApiContributorModules() throws IOException {
            return Files.list(srcroot)
                    .filter(p -> Files.exists(p.resolve("share/classes/com/jetbrains"))).toArray(Path[]::new);
        }
    }
}
