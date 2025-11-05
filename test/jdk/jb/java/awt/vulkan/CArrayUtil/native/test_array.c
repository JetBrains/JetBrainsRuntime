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

#define TYPE uint8_t
#include "test_array.h"
#define TYPE uint16_t
#include "test_array.h"
#define TYPE uint32_t
#include "test_array.h"
#define TYPE uint64_t
#include "test_array.h"

static void test_array_pchar() {
    ARRAY(pchar) a = {0};
    ARRAY_ENSURE_CAPACITY(a, 10);

    if (a.capacity != 10) fail();

    ARRAY_PUSH_BACK(a) = "0";
    ARRAY_PUSH_BACK(a) = "1";
    ARRAY_PUSH_BACK(a) = "2";
    ARRAY_PUSH_BACK(a) = "3";

    if (a.size != 4) fail();

    for (size_t i = 0; i < a.size; i++) {
        char str[21];
        sprintf(str, "%zu", i);
        if (strcmp(a.data[i], str) != 0) fail();
    }

    ARRAY_FREE(a);
}

static void test_array_null_safe() {
    ARRAY(pchar) a = {0};

    if (a.size != 0) fail();
    if (a.capacity != 0) fail();
    ARRAY_FREE(a); // check that free is NULL-safe

    ARRAY_ENSURE_CAPACITY(a, 1);
    ARRAY_PUSH_BACK(a) = "test";
    if (a.size != 1) fail();
    if (a.capacity < 1) fail();

    ARRAY_FREE(a);
}

static void test_array_shrink_to_fit() {
    ARRAY(pchar) a = {0};
    ARRAY_ENSURE_CAPACITY(a, 10);
    const pchar* initial_data = a.data;

    if (a.capacity != 10) fail();

    ARRAY_PUSH_BACK(a) = "0";
    ARRAY_PUSH_BACK(a) = "1";
    ARRAY_PUSH_BACK(a) = "2";
    ARRAY_PUSH_BACK(a) = "3";

    if (a.size != 4) fail();

    ARRAY_SHRINK_TO_FIT(a);

    if (a.data == initial_data) fail();

    if (a.capacity != 4) fail();
    if (a.size != 4) fail();

    for (size_t i = 0; i < a.size; i++) {
        char str[21];
        sprintf(str, "%zu", i);
        if (strcmp(a.data[i], str) != 0) fail();
    }

    ARRAY_FREE(a);
}

static void test_array_expand() {
    ARRAY(pchar) a = {0};
    ARRAY_ENSURE_CAPACITY(a, 3);

    if (a.capacity != 3) fail();

    ARRAY_PUSH_BACK(a) = "0";
    ARRAY_PUSH_BACK(a) = "1";
    ARRAY_PUSH_BACK(a) = "2";
    ARRAY_PUSH_BACK(a) = "3";

    if (a.size != 4) fail();
    if (a.capacity <= 3) fail();

    for (size_t i = 0; i < a.size; i++) {
        char str[21];
        sprintf(str, "%zu", i);
        if (strcmp(a.data[i], str) != 0) fail();
    }

    ARRAY_FREE(a);
}

static void test_array_ensure_capacity() {
    ARRAY(pchar) a = {0};

    ARRAY_ENSURE_CAPACITY(a, 1);
    if (a.capacity < 1) fail();

    size_t expanded_capacity = a.capacity + 1;
    ARRAY_ENSURE_CAPACITY(a, expanded_capacity);
    if (a.capacity < expanded_capacity) fail();

    ARRAY_FREE(a);
}

static void test_array_resize() {
    ARRAY(pchar) a = {0};

    ARRAY_RESIZE(a, 10);
    if (a.size != 10) fail();
    if (a.capacity < 10) fail();

    ARRAY_RESIZE(a, 20);
    if (a.size != 20) fail();
    if (a.capacity < 20) fail();

    ARRAY_FREE(a);
}

static void test_array_struct() {
    typedef struct {
        size_t data[123];
    } struct_t;
    ARRAY(struct_t) a = {0};

    for (size_t i = 0; i < 1000; i++) {
        ARRAY_PUSH_BACK(a) = (struct_t){{i}};
    }
    if (a.size != 1000) fail();
    for (size_t i = 0; i < 1000; i++) {
        if (a.data[i].data[0] != i) fail();
    }

    ARRAY_FREE(a);
}

void test_array() {
    RUN_TEST(array_uint8_t);
    RUN_TEST(array_uint16_t);
    RUN_TEST(array_uint32_t);
    RUN_TEST(array_uint64_t);
    RUN_TEST(array_pchar);

    RUN_TEST(array_null_safe);
    RUN_TEST(array_shrink_to_fit);
    RUN_TEST(array_expand);
    RUN_TEST(array_ensure_capacity);
    RUN_TEST(array_resize);
    RUN_TEST(array_struct);
}
