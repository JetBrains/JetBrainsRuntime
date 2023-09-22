/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
 * @modules java.base/com.jetbrains.internal:+open
 * @build com.jetbrains.* com.jetbrains.api.FindDependencies com.jetbrains.jbr.FindDependencies
 * @run main FindDependenciesTest
 */

import com.jetbrains.internal.JBRApi;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.jetbrains.Util.*;
import static com.jetbrains.api.FindDependencies.*;
import static com.jetbrains.jbr.FindDependencies.*;

public class FindDependenciesTest {

    public static void main(String[] args) throws Throwable {
        JBRApi.ModuleRegistry r = init(new String[] {}, names("""
                        A AL AR ARL /*ARR is skipped*/ ARRL ARRR
                        BV B1 B2 B3 B4 B5 B6 B7 B8 B9
                        C1 !C3 C5 C6
                        """).toArray(String[]::new));
        // Simple tree with non-proxy type ARR
        validateDependencies(AR.class, cs("AR ARL /*ARR is skipped*/ ARRL ARRR"));
        validateDependencies(A.class, cs("A AL AR ARL /*ARR is skipped*/ ARRL ARRR"));
        validateDependencies(ARRR.class, ARRR.class);
        // Complex graph with many cycles
        for (Class<?> c : cs("B4 B6 B2 B3 B5")) {
            validateDependencies(c, cs("BV B2 B3 B4 B5 B6 B7 B8 B9"));
        }
        validateDependencies(B1.class, cs("BV B1 B2 B3 B4 B5 B6 B7 B8 B9"));
        validateDependencies(B7.class, B7.class, BV.class);
        validateDependencies(B8.class, B8.class, BV.class);
        validateDependencies(B9.class, B9.class);
        validateDependencies(BV.class, BV.class);
        // Client proxy dependencies
        r.clientProxy(C3.class.getName(), C2.class.getName());
        r.proxy(C5.class.getName(), C4.class.getName());
        validateDependencies(C1.class, C1.class, C3.class, C5.class, C6.class);
        validateDependencies(C5.class, C5.class, C6.class);
        validateDependencies(C6.class, C6.class);
        validateDependencies(C3.class, C3.class, C5.class, C6.class);
    }

    private static Class<?>[] cs(String interfaces) {
        return names(interfaces).map(c -> {
            try {
                return Class.forName(c);
            } catch (ClassNotFoundException e) {
                throw new Error(e);
            }
        }).toArray(Class[]::new);
    }
    private static Stream<String> names(String interfaces) {
        return Stream.of(interfaces.replaceAll("/\\*[^*]*\\*/", "").split("(\s|\n)+")).map(String::strip)
                .map(s -> {
                    if (s.startsWith("!")) return "com.jetbrains.jbr.FindDependencies$" + s.substring(1);
                    else return "com.jetbrains.api.FindDependencies$" + s;
                });
    }

    private static void validateDependencies(Class<?> src, Class<?>... expected) throws Throwable {
        Set<Class<?>> actual = getProxyDependencies(src);
        if (actual.size() != expected.length || !actual.containsAll(List.of(expected))) {
            throw new Error("Invalid proxy dependencies for class " + src +
                    ". Expected: [" + Stream.of(expected).map(Class::getSimpleName).collect(Collectors.joining(" ")) +
                    "]. Actual: [" + actual.stream().map(Class::getSimpleName).collect(Collectors.joining(" ")) + "].");
        }
    }

    private static final ReflectedMethod getProxyDependencies =
            getMethod("com.jetbrains.internal.ProxyDependencyManager", "getProxyDependencies", Class.class);
    @SuppressWarnings("unchecked")
    static Set<Class<?>> getProxyDependencies(Class<?> interFace) throws Throwable {
        return (Set<Class<?>>) getProxyDependencies.invoke(null, interFace);
    }
}
