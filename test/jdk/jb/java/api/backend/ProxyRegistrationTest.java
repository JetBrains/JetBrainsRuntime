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
 * @build com.jetbrains.*
 * @run main ProxyRegistrationTest
 */

import com.jetbrains.internal.JBRApi;

import static com.jetbrains.Util.*;

public class ProxyRegistrationTest {

    public static void main(String[] args) {
        JBRApi.ModuleRegistry r = init();
        // Only service may not have target type
        r.service("s");
        mustFail(() -> r.proxy("i"), IllegalArgumentException.class);
        mustFail(() -> r.proxy("i", null), NullPointerException.class);
        mustFail(() -> r.clientProxy("i", null), NullPointerException.class);
        mustFail(() -> r.twoWayProxy("i", null), NullPointerException.class);
        // Invalid 2-way mapping
        r.proxy("a", "b");
        mustFail(() -> r.clientProxy("b", "c"), IllegalArgumentException.class);
        mustFail(() -> r.clientProxy("c", "a"), IllegalArgumentException.class);
    }
}
