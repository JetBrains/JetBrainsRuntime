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

#include "test.h"

#define TYPE uint32_t
#include "test_ring_buffer.h"
#define TYPE uint64_t
#include "test_ring_buffer.h"

static void test_ring_buffer_null_safe() {
    RING_BUFFER(pchar) b = {0};

    if (b.size != 0) fail();
    if (b.capacity != 0) fail();
    RING_BUFFER_FREE(b);

    RING_BUFFER_PUSH_BACK(b) = "test";
    if (b.size != 1) fail();
    if (b.capacity < 1) fail();

    RING_BUFFER_FREE(b);
}

static void test_ring_buffer_struct() {
    typedef struct {
        size_t data[123];
    } struct_t;
    RING_BUFFER(struct_t) b = {0};

    for (size_t i = 0; i < 1000; i++) {
        RING_BUFFER_PUSH_BACK(b) = (struct_t){{i}};
    }
    if (b.size != 1000) fail();
    for (size_t i = 0;; i++) {
        struct_t* s = RING_BUFFER_FRONT(b);
        if (s == NULL) {
            if (i != 1000) fail();
            else break;
        }
        if (s->data[0] != i) fail();
        RING_BUFFER_POP_FRONT(b);
    }

    RING_BUFFER_FREE(b);
}

void test_ring_buffer() {
    RUN_TEST(ring_buffer_wrap_uint32_t);
    RUN_TEST(ring_buffer_wrap_uint64_t);
    RUN_TEST(ring_buffer_null_safe);
    RUN_TEST(ring_buffer_struct);
}
