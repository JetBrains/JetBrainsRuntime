package build.tools.jbrapi;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementScanner14;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

public class JBRApiPlugin implements Plugin {

    enum Binding {
        SERVICE,
        PROVIDES,
        PROVIDED,
        TWO_WAY
    }

    record DiagnosticTree(CompilationUnitTree root, Tree tree) {}
    record TypeBinding(DiagnosticTree diagnostic, TypeElement element, String currentType, String bindType, Binding binding) {}
    record MethodBinding(DiagnosticTree diagnostic, ExecutableElement element, Registry.StaticDescriptor currentMethod, Registry.StaticMethod bindMethod) {}

    final Map<String, TypeBinding> typeBindings = new HashMap<>();
    final List<MethodBinding> methodBindings = new ArrayList<>();
    Elements elements;
    Trees trees;
    Types types;

    class Registry {

        record Type(String type, Binding binding) {}
        record StaticMethod(String type, String name) {}
        record StaticDescriptor(StaticMethod method, String descriptor) {}

        final Map<String, Type> types = new HashMap<>();
        final Map<StaticDescriptor, StaticMethod> methods = new HashMap<>();
        final Set<Object> internal = new HashSet<>();

        void validateInternal(DiagnosticTree diagnostic, String currentType, Binding binding, TypeElement bindType) {
            if (bindType.getKind() != ElementKind.CLASS && bindType.getKind() != ElementKind.INTERFACE) {
                trees.printMessage(Diagnostic.Kind.ERROR, "Invalid JBR API binding:" + currentType + " -> " +
                                bindType.getQualifiedName().toString() + " (not a class or interface)",
                        diagnostic.tree, diagnostic.root);
            } else if (bindType.getModifiers().contains(Modifier.FINAL) || bindType.getModifiers().contains(Modifier.SEALED)) {
                trees.printMessage(Diagnostic.Kind.ERROR, "Invalid JBR API binding:" + currentType + " -> " +
                                bindType.getQualifiedName().toString() + " (not inheritable)",
                        diagnostic.tree, diagnostic.root);
            }
            if (binding != Binding.SERVICE) {
                trees.printMessage(Diagnostic.Kind.ERROR, "Invalid JBR API binding:" + currentType + " -> " +
                                bindType.getQualifiedName().toString() + " (internal, non-service)",
                        diagnostic.tree, diagnostic.root);
            }
        }

        void validateInternalMethod(DiagnosticTree diagnostic, StaticDescriptor currentMethod, TypeElement bindType, String bindMethod) {
            boolean methodFound = false;
            for (Element m : bindType.getEnclosedElements()) {
                if (m.getKind() == ElementKind.METHOD &&
                        !m.getModifiers().contains(Modifier.STATIC) &&
                        !m.getModifiers().contains(Modifier.FINAL) &&
                        m.getSimpleName().contentEquals(bindMethod) &&
                        descriptor(m.asType()).equals(currentMethod.descriptor)) {
                    methodFound = true;
                }
            }
            if (!methodFound) {
                trees.printMessage(Diagnostic.Kind.ERROR, "Invalid static binding: " +
                                currentMethod.method.type + "#" + currentMethod.method.name + " -> " +
                                bindType.getQualifiedName().toString() + "#" + bindMethod +
                                " (no matching method found, type conversions are not allowed for internal bindings)",
                        diagnostic.tree, diagnostic.root);
            }
        }

        List<String> addBindings() {
            List<String> unresolvedErrors = new ArrayList<>();
            List<TypeBinding> addedTypes = new ArrayList<>();
            List<MethodBinding> addedMethods = new ArrayList<>();
            Set<Object> validated = new HashSet<>();
            // Remove changed bindings.
            for (TypeBinding type : typeBindings.values()) {
                if (type.bindType != null) addedTypes.add(type);
                types.remove(type.currentType);
            }
            for (MethodBinding method : methodBindings) {
                if (method.bindMethod != null) addedMethods.add(method);
                methods.remove(method.currentMethod);
            }
            methods.entrySet().removeIf(m -> typeBindings.containsKey(m.getKey().method.type));
            // Build inverse binding map.
            Map<String, String> inverseTypes = types.entrySet().stream().collect(Collectors.toMap(
                    e -> e.getValue().type, Map.Entry::getKey, (a, b) -> {
                        unresolvedErrors.add("Conflicting JBR API binding: " + a + " and "+ b + " binds to the same type");
                        return a + "," + b;
                    }));
            Map<StaticDescriptor, StaticMethod> inverseMethods = methods.entrySet().stream().collect(Collectors.toMap(
                    e -> new StaticDescriptor(e.getValue(), e.getKey().descriptor), e -> e.getKey().method, (a, b) -> {
                        unresolvedErrors.add("Conflicting JBR API binding: " +
                                a.type + "#" + a.name + " and "+ b.type + "#" + b.name + " binds to the same method");
                        return new StaticMethod(a.type + "," + b.type, a.name + "," + b.name);
                    }));
            // Add new bindings.
            for (TypeBinding type : addedTypes) types.put(type.currentType, new Type(type.bindType, type.binding));
            for (MethodBinding method : addedMethods) methods.put(method.currentMethod, method.bindMethod);
            // Validate type bindings.
            for (TypeBinding type : addedTypes) {
                String inv = inverseTypes.get(type.bindType);
                if (inv != null) {
                    trees.printMessage(Diagnostic.Kind.ERROR,
                            "Conflicting JBR API binding: " + type.currentType + " -> " + type.bindType + " <- " + inv,
                            type.diagnostic.tree, type.diagnostic.root);
                    inverseTypes.put(type.bindType, inv + "," + type.currentType);
                } else inverseTypes.put(type.bindType, type.currentType);
                Type next = types.get(type.bindType);
                if (next != null) {
                    trees.printMessage(Diagnostic.Kind.ERROR,
                            "Conflicting JBR API binding: " + type.currentType + " -> " + type.bindType + " -> " + next,
                            type.diagnostic.tree, type.diagnostic.root);
                }
                String prev = inverseTypes.get(type.currentType);
                if (prev != null) {
                    trees.printMessage(Diagnostic.Kind.ERROR,
                            "Conflicting JBR API binding: " + prev + " -> " + type.currentType + " -> " + type.bindType,
                            type.diagnostic.tree, type.diagnostic.root);
                }
                if (validated.add(type.currentType)) {
                    TypeElement bindElement = elements.getTypeElement(type.bindType);
                    if (bindElement != null) {
                        internal.add(type.currentType);
                        validateInternal(type.diagnostic, type.currentType, type.binding, bindElement);
                    }
                }
            }
            // Validate method bindings.
            for (MethodBinding method : addedMethods) {
                StaticDescriptor invDescriptor = new StaticDescriptor(method.bindMethod, method.currentMethod.descriptor);
                StaticMethod inv = inverseMethods.get(invDescriptor);
                if (inv != null) {
                    trees.printMessage(Diagnostic.Kind.ERROR, "Conflicting JBR API binding: " +
                                    method.currentMethod.method.type + "#" + method.currentMethod.method.name + " -> " +
                                    method.bindMethod.type + "#" + method.bindMethod.name + " <- " +
                                    inv.type + "#" + inv.name,
                            method.diagnostic.tree, method.diagnostic.root);
                    inverseMethods.put(invDescriptor, new StaticMethod(
                            inv.type + "," + method.currentMethod.method.type, inv.name + "," + method.currentMethod.method.name));
                } else inverseMethods.put(invDescriptor, method.currentMethod.method);
                if (validated.add(method.currentMethod)) {
                    TypeElement bindElement = elements.getTypeElement(method.bindMethod.type);
                    if (bindElement != null) {
                        internal.add(method.currentMethod);
                        validateInternalMethod(method.diagnostic, method.currentMethod, bindElement, method.bindMethod.name);
                    }
                }
            }
            // [Re]validate remaining.
            types.forEach((k, v) -> {
                if (validated.add(k)) {
                    TypeBinding type = typeBindings.get(v.type);
                    if (type != null) {
                        internal.add(k);
                        validateInternal(type.diagnostic, k, v.binding, type.element);
                    } else if (elements.getTypeElement(v.type) != null) {
                        internal.add(k); // Couldn't validate, but at least found the type.
                        if (v.binding != Binding.SERVICE) {
                            unresolvedErrors.add("Invalid JBR API binding:" + k + " -> " + v.type + " (internal, non-service)");
                        }
                    }
                }
            });
            methods.forEach((k, v) -> {
                if (validated.add(k)) {
                    TypeBinding type = typeBindings.get(v.type);
                    if (type != null) {
                        internal.add(k);
                        validateInternalMethod(type.diagnostic, k, type.element, v.name);
                    } else if (elements.getTypeElement(v.type) != null) {
                        internal.add(k); // Couldn't validate, but at least found the type.
                    }
                }
            });
            return unresolvedErrors;
        }

        void read(RandomAccessFile file) throws IOException {
            String s;
            while ((s = file.readLine()) != null) {
                String[] tokens = s.split(" ");
                switch (tokens[0]) {
                    case "TYPE" -> {
                        types.put(tokens[1], new Type(tokens[2], Binding.valueOf(tokens[3])));
                        if (tokens.length > 4 && tokens[4].equals("INTERNAL")) internal.add(tokens[1]);
                    }
                    case "STATIC" -> {
                        StaticDescriptor descriptor = new StaticDescriptor(new StaticMethod(
                                tokens[1], tokens[2]), tokens[3]);
                        methods.put(descriptor, new StaticMethod(tokens[4], tokens[5]));
                        if (tokens.length > 6 && tokens[6].equals("INTERNAL")) internal.add(descriptor);
                    }
                }
            }
        }

        void write(RandomAccessFile file) throws IOException {
            for (var t : types.entrySet()) {
                file.writeBytes("TYPE " + t.getKey() + " " + t.getValue().type + " " + t.getValue().binding +
                        (internal.contains(t.getKey()) ? " INTERNAL\n" : "\n"));
            }
            for (var t : methods.entrySet()) {
                file.writeBytes("STATIC " + t.getKey().method.type + " " + t.getKey().method.name + " " +
                        t.getKey().descriptor + " " + t.getValue().type + " " + t.getValue().name +
                        (internal.contains(t.getKey()) ? " INTERNAL\n" : "\n"));
            }
        }
    }

    String descriptor(TypeMirror t) {
        return switch (t.getKind()) {
            case VOID       -> "V";
            case BOOLEAN    -> "Z";
            case BYTE       -> "B";
            case CHAR       -> "C";
            case SHORT      -> "S";
            case INT        -> "I";
            case LONG       -> "J";
            case FLOAT      -> "F";
            case DOUBLE     -> "D";
            case ARRAY      -> "[" + descriptor(((ArrayType) t).getComponentType());
            case DECLARED   -> "L" + elements.getBinaryName((TypeElement) ((DeclaredType) t).asElement())
                    .toString().replace('.', '/') + ";";
            case EXECUTABLE -> "(" + ((ExecutableType) t).getParameterTypes().stream().map(this::descriptor)
                    .collect(Collectors.joining()) + ")" + descriptor(((ExecutableType) t).getReturnType());
            case TYPEVAR, WILDCARD, UNION, INTERSECTION -> descriptor(types.erasure(t));
            default -> throw new RuntimeException("Cannot generate descriptor for type: " + t);
        };
    }

    Registry.StaticDescriptor staticDescriptor(String type, ExecutableElement e) {
        return new Registry.StaticDescriptor(new Registry.StaticMethod(type, e.getSimpleName().toString()), descriptor(e.asType()));
    }

    AnnotationValue annotationValue(AnnotationMirror m) {
        if (m == null) return null;
        return m.getElementValues().entrySet().stream()
                .filter(t -> t.getKey().getSimpleName().contentEquals("value"))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }

    static boolean isJavaIdentifier(String name, int from, int to) {
        if (!Character.isJavaIdentifierStart(name.charAt(from))) return false;
        for (int i = from + 1; i < to; i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }
    static boolean isJavaIdentifier(String name) {
        if (name == null || name.isEmpty()) return false;
        return isJavaIdentifier(name, 0, name.length());
    }
    static boolean isJavaTypeIdentifier(String name) {
        if (name == null || name.isEmpty()) return false;
        for (int i = 0; i < name.length();) {
            int next = name.indexOf('.', i);
            if (next == -1) next = name.length();
            if (!isJavaIdentifier(name, i, next)) return false;
            i = next + 1;
        }
        return true;
    }

    void scan(CompilationUnitTree root, Element e) {
        // Get current type name.
        String currentType;
        if (e.getKind() == ElementKind.CLASS || e.getKind() == ElementKind.INTERFACE) {
            currentType = ((TypeElement) e).getQualifiedName().toString();
        } else if (e.getKind() == ElementKind.METHOD && e.getModifiers().contains(Modifier.STATIC)) {
            currentType = ((QualifiedNameable) e.getEnclosingElement()).getQualifiedName().toString();
        } else currentType = null;

        // Find the annotation.
        AnnotationMirror providedMirror = null, providesMirror = null, serviceMirror = null;
        for (AnnotationMirror m :  elements.getAllAnnotationMirrors(e)) {
            switch (m.getAnnotationType().toString()) {
                case "com.jetbrains.exported.JBRApi.Provided" -> providedMirror = m;
                case "com.jetbrains.exported.JBRApi.Provides" -> providesMirror = m;
                case "com.jetbrains.exported.JBRApi.Service"  -> serviceMirror  = m;
            }
        }

        AnnotationMirror mirror = null;
        AnnotationValue value = null;
        Binding binding = null;
        if (serviceMirror != null) {
            if (providesMirror == null) {
                trees.printMessage(Diagnostic.Kind.ERROR,
                        "@Service also requires @Provides", trees.getTree(e, serviceMirror), root);
                return;
            }
            if (providedMirror != null) {
                trees.printMessage(Diagnostic.Kind.ERROR,
                        "@Service cannot be used with @Provided", trees.getTree(e, serviceMirror), root);
                return;
            }
            value = annotationValue(mirror = providesMirror);
            binding = Binding.SERVICE;
        } else if (providesMirror != null) {
            value = annotationValue(mirror = providesMirror);
            if (providedMirror != null) {
                AnnotationValue v = annotationValue(providedMirror);
                if (!value.getValue().toString().equals(v.getValue().toString())) {
                    trees.printMessage(Diagnostic.Kind.ERROR,
                            "@Provided and @Provides doesn't match", trees.getTree(e, mirror, value), root);
                    return;
                }
                binding = Binding.TWO_WAY;
            } else binding = Binding.PROVIDES;
        } else if (providedMirror != null) {
            value = annotationValue(mirror = providedMirror);
            binding = Binding.PROVIDED;
        }

        if (value != null && value.getValue().toString().isEmpty()) {
            trees.printMessage(Diagnostic.Kind.ERROR,
                    "Empty JBR API binding",
                    trees.getTree(e, mirror, value), root);
            return;
        }

        if (currentType == null) {
            if (value != null) {
                trees.printMessage(Diagnostic.Kind.ERROR,
                        "JBR API annotations are only allowed on classes, interfaces and static methods",
                        trees.getTree(e, mirror), root);
            }
            return;
        }
        if (value != null && e.getKind() == ElementKind.METHOD && binding != Binding.PROVIDES) {
            trees.printMessage(Diagnostic.Kind.ERROR,
                    "Only @Provides is allowed for static methods",
                    trees.getTree(e, mirror), root);
            return;
        }

        // Determine class/method names.
        String bindType = null, bindMethod = null;
        if (value != null) {
            bindType = value.getValue().toString();
            if (e.getKind() == ElementKind.METHOD) {
                int splitIndex = bindType.indexOf('#');
                if (splitIndex != -1) {
                    bindMethod = bindType.substring(splitIndex + 1);
                    bindType = bindType.substring(0, splitIndex);
                    if (!isJavaIdentifier(bindMethod)) {
                        trees.printMessage(Diagnostic.Kind.ERROR, "Invalid method identifier: " + bindMethod,
                                trees.getTree(e, mirror, value), root);
                        return;
                    }
                } else bindMethod = e.getSimpleName().toString();
            }
            if (!isJavaTypeIdentifier(bindType)) {
                trees.printMessage(Diagnostic.Kind.ERROR, "Invalid type identifier: " + bindType,
                        trees.getTree(e, mirror, value), root);
                return;
            }
            if (Character.isUpperCase(bindType.charAt(0))) bindType = "com.jetbrains." + bindType; // Short form
        }

        // Add entry.
        DiagnosticTree diagnostic = new DiagnosticTree(root, trees.getTree(e, mirror, value));
        if (e.getKind() == ElementKind.METHOD) {
            ExecutableElement m = (ExecutableElement) e;
            methodBindings.add(new MethodBinding(diagnostic, m, staticDescriptor(currentType, m),
                    bindType == null ? null : new Registry.StaticMethod(bindType, bindMethod)));
        } else {
            typeBindings.put(currentType, new TypeBinding(diagnostic, (TypeElement) e, currentType, bindType, binding));
        }
    }

    @Override
    public String getName() {
        return "jbr-api";
    }

    @Override
    public void init(JavacTask jt, String... args) {
        Path output = Path.of(args[0]);
        String implVersion;
        try {
            implVersion = Files.readString(Path.of(args[1])).strip();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        elements = jt.getElements();
        trees = Trees.instance(jt);
        types = jt.getTypes();
        jt.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent te) {
                if (te.getKind() == TaskEvent.Kind.ANALYZE && te.getTypeElement() != null) {
                    new ElementScanner14<Void, CompilationUnitTree>() {
                        @Override
                        public Void visitModule(ModuleElement e, CompilationUnitTree unused) { return null; }
                        @Override
                        public Void visitPackage(PackageElement e, CompilationUnitTree unused) { return null; }
                        @Override
                        public Void scan(Element e, CompilationUnitTree root) {
                            JBRApiPlugin.this.scan(root, e);
                            e.accept(this, root);
                            return null;
                        }
                    }.scan(te.getTypeElement(), te.getCompilationUnit());
                } else if (te.getKind() == TaskEvent.Kind.COMPILATION) {
                    try (RandomAccessFile file = new RandomAccessFile(output.toFile(), "rw");
                         FileChannel channel = file.getChannel()) {
                        for (;;) {
                            try { if (channel.lock() != null) break; } catch (OverlappingFileLockException ignore) {}
                            LockSupport.parkNanos(10_000000);
                        }
                        Registry r = new Registry();
                        r.read(file);
                        var unresolvedErrors = r.addBindings();
                        file.setLength(0);
                        file.writeBytes("VERSION " + implVersion + "\n");
                        r.write(file);
                        if (!unresolvedErrors.isEmpty()) {
                            throw new RuntimeException(String.join("\n", unresolvedErrors));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
    }
}