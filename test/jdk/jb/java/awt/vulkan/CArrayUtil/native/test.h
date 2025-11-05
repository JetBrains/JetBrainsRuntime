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

#ifndef TEST_H
#define TEST_H

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "CArrayUtil.h"

typedef char* pchar;

extern int argc;
extern char **argv;
extern int test_nesting_level;

static inline void fail() {
    exit(1);
}

#define PRINT_INDENT(INDENT, ...) printf("%*s", (int)((INDENT) + strlen(__VA_ARGS__)), (__VA_ARGS__))
#define RUN_TEST(NAME) do { \
    if (argc < 2 || strncmp(argv[1], #NAME, CARR_MIN(strlen(#NAME), strlen(argv[1]))) == 0) { \
        PRINT_INDENT(test_nesting_level*2, "Start: " #NAME "\n");                             \
        test_nesting_level++;                                                                 \
        test_ ## NAME();                                                                      \
        test_nesting_level--;                                                                 \
        PRINT_INDENT(test_nesting_level*2, "End: " #NAME "\n");                               \
    }                                                                                         \
} while(0)

#define CONCATENATE_IMPL(A, B) A ## B
#define CONCATENATE(A, B) CONCATENATE_IMPL(A, B)

// Peek into the map impl to check if a rehash has taken place; good enough for a test...
typedef struct CARR_hash_map_probing_impl_data_struct {
    void* key_data;
    void* value_data;

    uint32_t probing_limit;
    float load_factor;
    void* zero_key_slot; // points to the all-zero key if one exists (to distinguish from a missing key)

    CARR_equals_fp equals;
    CARR_hash_fp hash;
} CARR_hash_map_probing_impl_data_t;

#endif //TEST_H
