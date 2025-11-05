/*
 * Copyright 2025 JetBrains s.r.o.
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

static void CONCATENATE(test_array_, TYPE)() {
    ARRAY(TYPE) a = {0};
    ARRAY_ENSURE_CAPACITY(a, 10);

    if (a.capacity != 10) fail();

    ARRAY_PUSH_BACK(a) = 0;
    ARRAY_PUSH_BACK(a) = 1;
    ARRAY_PUSH_BACK(a) = 2;
    ARRAY_PUSH_BACK(a) = 3;

    if (a.size != 4) fail();

    for (TYPE i = 0; i < a.size; i++) {
        if (a.data[i] != i) fail();
    }
    ARRAY_FREE(a);
}

#undef TYPE
