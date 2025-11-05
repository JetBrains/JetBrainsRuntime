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

#include <stdbool.h>
#include <stdint.h>
#include <limits.h>

static bool alloc_failed = false;
#define C_ARRAY_UTIL_ALLOCATION_FAILED() alloc_failed = true

#include "test.h"

typedef MAP(int, int) map_t;
static void safe_map_rehash(map_t* map) {
    bool result = HASH_MAP_TRY_REHASH(*map, linear_probing, NULL, NULL, 0, -1, 1.0);
    if (!result) fail();
}
static void safe_map_free(map_t* map) {
    MAP_FREE(*map);
    *map = NULL;
}

// Providing invalid alignment helps to make allocation fail.
// It still needs to be a power of 2, otherwise the Microsoft CRT fails with an assert way before we can catch it.
#define CARR_LARGEST_POWER_OF_2 (1ULL << (CHAR_BIT * sizeof(size_t) - 1))

#ifdef alignof
#undef alignof
#endif
#define alignof(...) CARR_LARGEST_POWER_OF_2

#ifdef CARR_ALIGNMENT_FOR_TYPE
#undef CARR_ALIGNMENT_FOR_TYPE
#endif
#define CARR_ALIGNMENT_FOR_TYPE(...) CARR_LARGEST_POWER_OF_2

#ifdef CARR_ALIGNMENT_FOR_EXPR
#undef CARR_ALIGNMENT_FOR_EXPR
#endif
#define CARR_ALIGNMENT_FOR_EXPR(...) CARR_LARGEST_POWER_OF_2

static void expect_alloc_fail(bool expected) {
    if (alloc_failed != expected) fail();
    alloc_failed = false;
}

static void test_alloc_fail_array() {
    ARRAY(int) a = NULL;
    bool try_result;

    try_result = ARRAY_TRY_ENSURE_CAPACITY(a, 1);
    if (try_result) fail();
    expect_alloc_fail(false);
    try_result = ARRAY_TRY_RESIZE(a, 1);
    if (try_result) fail();
    expect_alloc_fail(false);

    a = ARRAY_ALLOC(int, 1);
    expect_alloc_fail(false);
    ARRAY_ENSURE_CAPACITY(a, 1);
    expect_alloc_fail(true);
    ARRAY_SHRINK_TO_FIT(a);
    expect_alloc_fail(false);
    ARRAY_RESIZE(a, 1);
    expect_alloc_fail(true);
    ARRAY_PUSH_BACK(a); // Do not write into "pushed" value, it should be invalid.
    expect_alloc_fail(true);
}

static void test_alloc_fail_ring_buffer() {
    RING_BUFFER(int) b = NULL;
    bool try_result;

    try_result = RING_BUFFER_TRY_ENSURE_CAN_PUSH(b);
    if (try_result) fail();
    expect_alloc_fail(false);

    RING_BUFFER_ENSURE_CAN_PUSH(b);
    expect_alloc_fail(true);
    RING_BUFFER_PUSH_FRONT(b); // Do not write into "pushed" value, it should be invalid.
    expect_alloc_fail(true);
    RING_BUFFER_PUSH_BACK(b); // Do not write into "pushed" value, it should be invalid.
    expect_alloc_fail(true);
    RING_BUFFER_FREE(b);
    expect_alloc_fail(false);
}

static void test_alloc_fail_map() {
    map_t map = NULL;
    bool try_result;

    try_result = HASH_MAP_TRY_REHASH(map, linear_probing, NULL, NULL, 0, -1, 1.0);
    if (try_result) fail();
    expect_alloc_fail(false);
    HASH_MAP_REHASH(map, linear_probing, NULL, NULL, 0, -1, 1.0);
    expect_alloc_fail(true);

    safe_map_rehash(&map);
    try_result = MAP_TRY_ENSURE_EXTRA_CAPACITY(map, 1000);
    if (try_result) fail();
    expect_alloc_fail(false);
    MAP_ENSURE_EXTRA_CAPACITY(map, 1000);
    expect_alloc_fail(true);
    safe_map_free(&map);
}

void test_alloc_fail() {
    alloc_failed = false;
    RUN_TEST(alloc_fail_array);
    RUN_TEST(alloc_fail_ring_buffer);
    RUN_TEST(alloc_fail_map);
}
