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
    ARRAY(pchar) a = ARRAY_ALLOC(pchar, 10);

    if (ARRAY_CAPACITY(a) != 10) fail();

    ARRAY_PUSH_BACK(a) = "0";
    ARRAY_PUSH_BACK(a) = "1";
    ARRAY_PUSH_BACK(a) = "2";
    ARRAY_PUSH_BACK(a) = "3";

    if (ARRAY_SIZE(a) != 4) fail();

    for (int i = 0; i < ARRAY_SIZE(a); i++) {
        char str[10];
        sprintf(str, "%d", i);
        if (strcmp(a[i], str) != 0) fail();
    }

    ARRAY_FREE(a);
}

static void test_array_shrink_to_fit() {
    ARRAY(pchar) a = ARRAY_ALLOC(pchar, 10);
    ARRAY(pchar) aa = a;

    if (ARRAY_CAPACITY(a) != 10) fail();

    ARRAY_PUSH_BACK(a) = "0";
    ARRAY_PUSH_BACK(a) = "1";
    ARRAY_PUSH_BACK(a) = "2";
    ARRAY_PUSH_BACK(a) = "3";


    if (ARRAY_SIZE(a) != 4) fail();

    ARRAY_SHRINK_TO_FIT(a);

    if (a == aa) fail();

    if (ARRAY_CAPACITY(a) != 4) fail();
    if (ARRAY_SIZE(a) != 4) fail();

    for (int i = 0; i < ARRAY_SIZE(a); i++) {
        char str[10];
        sprintf(str, "%d", i);
        if (strcmp(a[i], str) != 0) fail();
    }

    ARRAY_FREE(a);
}

static void test_array_expand() {
    ARRAY(pchar) a = ARRAY_ALLOC(pchar, 3);

    ARRAY_PUSH_BACK(a) = "0";
    ARRAY_PUSH_BACK(a) = "1";
    ARRAY_PUSH_BACK(a) = "2";
    ARRAY_PUSH_BACK(a) = "3";

    if (ARRAY_SIZE(a) != 4) fail();
    if (ARRAY_CAPACITY(a) <= 3) fail();

    for (int i = 0; i < ARRAY_SIZE(a); i++) {
        char str[10];
        sprintf(str, "%d", i);
        if (strcmp(a[i], str) != 0) fail();
    }

    ARRAY_FREE(a);
}

#define TEST_USER_DATA1 123
#define TEST_USER_DATA2 321
static void pchar_apply_callback(pchar* c) {
    if (c == NULL) fail();
}
static void pchar_apply_leading_callback(pchar* c, int user_data1, int user_data2) {
    if (c == NULL) fail();
    if (user_data1 != TEST_USER_DATA1) fail();
    if (user_data2 != TEST_USER_DATA2) fail();
}
static void pchar_apply_trailing_callback(int user_data1, int user_data2, pchar* c) {
    if (c == NULL) fail();
    if (user_data1 != TEST_USER_DATA1) fail();
    if (user_data2 != TEST_USER_DATA2) fail();
}

static void test_array_null_safe() {
    ARRAY(pchar) a = NULL;

    if (ARRAY_SIZE(a) != 0) fail();
    if (ARRAY_CAPACITY(a) != 0) fail();
    ARRAY_FREE(a);

    ARRAY_APPLY(a, pchar_apply_callback);
    ARRAY_APPLY_LEADING(a, pchar_apply_leading_callback, TEST_USER_DATA1, TEST_USER_DATA2);
    ARRAY_APPLY_TRAILING(a, pchar_apply_trailing_callback, TEST_USER_DATA1, TEST_USER_DATA2);

    ARRAY_PUSH_BACK(a) = "test";
    if (ARRAY_SIZE(a) != 1) fail();
    if (ARRAY_CAPACITY(a) < 1) fail();
    if (strcmp(a[0], "test") != 0) fail();

    ARRAY_FREE(a);
}

static void test_array_ensure_capacity() {
    ARRAY(pchar) a = NULL;

    ARRAY_ENSURE_CAPACITY(a, 1);
    if (ARRAY_CAPACITY(a) < 1) fail();

    size_t expanded_capacity = ARRAY_CAPACITY(a) + 1;
    ARRAY_ENSURE_CAPACITY(a, expanded_capacity);
    if (ARRAY_CAPACITY(a) < expanded_capacity) fail();

    ARRAY_FREE(a);
}

static void test_array_resize() {
    ARRAY(pchar) a = NULL;

    ARRAY_RESIZE(a, 10);
    if (ARRAY_SIZE(a) != 10) fail();
    if (ARRAY_CAPACITY(a) < 10) fail();

    ARRAY_RESIZE(a, 20);
    if (ARRAY_SIZE(a) != 20) fail();
    if (ARRAY_CAPACITY(a) < 20) fail();

    ARRAY_FREE(a);
}

static void test_array_struct() {
    typedef struct {
        size_t data[123];
    } struct_t;
    ARRAY(struct_t) a = NULL;

    for (size_t i = 0; i < 1000; i++) {
        ARRAY_PUSH_BACK(a) = (struct_t){i};
    }
    if (ARRAY_SIZE(a) != 1000) fail();
    for (size_t i = 0; i < 1000; i++) {
        if (a[i].data[0] != i) fail();
    }

    ARRAY_FREE(a);
}

static void test_array_struct_align() {
    typedef struct {
        alignas(256) size_t data[123];
    } struct_t;
    ARRAY(struct_t) a = NULL;

    for (size_t i = 0; i < 1000; i++) {
        ARRAY_PUSH_BACK(a) = (struct_t){i};
        if ((uintptr_t)&a[i] % 256 != 0) fail();
    }
    if (ARRAY_SIZE(a) != 1000) fail();
    for (size_t i = 0; i < 1000; i++) {
        if (a[i].data[0] != i) fail();
        if ((uintptr_t)&a[i] % 256 != 0) fail();
    }

    ARRAY_FREE(a);
}

void test_array() {
    RUN_TEST(array_uint8_t);
    RUN_TEST(array_uint16_t);
    RUN_TEST(array_uint32_t);
    RUN_TEST(array_uint64_t);
    RUN_TEST(array_pchar);
    RUN_TEST(array_shrink_to_fit);
    RUN_TEST(array_expand);
    RUN_TEST(array_null_safe);
    RUN_TEST(array_ensure_capacity);
    RUN_TEST(array_resize);
    RUN_TEST(array_struct);
    RUN_TEST(array_struct_align);
}
