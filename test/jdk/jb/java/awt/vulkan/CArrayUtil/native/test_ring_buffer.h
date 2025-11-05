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

static void CONCATENATE(test_ring_buffer_wrap_, TYPE)() {
    const size_t EXPAND_COUNT = 1000;
    const int INNER_COUNT  = 1000;
    RING_BUFFER(TYPE) b = {0};

    TYPE read = 0;
    TYPE write = 0;
    for (size_t i = 0; i < EXPAND_COUNT; i++) {
        for (int j = 0; j < INNER_COUNT; j++) {
            RING_BUFFER_PUSH_BACK(b) = write;
            write++;
            TYPE* value = RING_BUFFER_FRONT(b);
            if (value == NULL) fail();
            if (*value != read) fail();
            read++;
            RING_BUFFER_POP_FRONT(b);
        }
        RING_BUFFER_PUSH_BACK(b) = write;
        write++;
    }
    if (b.size != EXPAND_COUNT) fail();

    for (size_t i = 0; i < EXPAND_COUNT; i++) {
        TYPE* value = RING_BUFFER_FRONT(b);
        if (value == NULL) fail();
        if (*value != read) fail();
        read++;
        RING_BUFFER_POP_FRONT(b);
    }
    if (RING_BUFFER_FRONT(b) != NULL) fail();
    if (b.size != 0) fail();

    RING_BUFFER_FREE(b);
}

#undef TYPE
