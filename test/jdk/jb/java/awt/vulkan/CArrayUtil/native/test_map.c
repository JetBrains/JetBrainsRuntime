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

#define map_key_t uint8_t
#define map_value_t uint8_t
#include "test_map.h"
#define map_key_t uint8_t
#define map_value_t uint16_t
#include "test_map.h"
#define map_key_t uint8_t
#define map_value_t uint32_t
#include "test_map.h"
#define map_key_t uint8_t
#define map_value_t uint64_t
#include "test_map.h"
#define map_key_t uint16_t
#define map_value_t uint8_t
#include "test_map.h"
#define map_key_t uint16_t
#define map_value_t uint16_t
#include "test_map.h"
#define map_key_t uint16_t
#define map_value_t uint32_t
#include "test_map.h"
#define map_key_t uint16_t
#define map_value_t uint64_t
#include "test_map.h"
#define map_key_t uint32_t
#define map_value_t uint8_t
#include "test_map.h"
#define map_key_t uint32_t
#define map_value_t uint16_t
#include "test_map.h"
#define map_key_t uint32_t
#define map_value_t uint32_t
#include "test_map.h"
#define map_key_t uint32_t
#define map_value_t uint64_t
#include "test_map.h"
#define map_key_t uint64_t
#define map_value_t uint8_t
#include "test_map.h"
#define map_key_t uint64_t
#define map_value_t uint16_t
#include "test_map.h"
#define map_key_t uint64_t
#define map_value_t uint32_t
#include "test_map.h"
#define map_key_t uint64_t
#define map_value_t uint64_t
#include "test_map.h"

#include "test_map_struct.h"

void test_map() {
    RUN_TEST(map_linear_probing_uint8_t_uint8_t);
    RUN_TEST(map_linear_probing_uint8_t_uint16_t);
    RUN_TEST(map_linear_probing_uint8_t_uint32_t);
    RUN_TEST(map_linear_probing_uint8_t_uint64_t);
    RUN_TEST(map_linear_probing_uint16_t_uint8_t);
    RUN_TEST(map_linear_probing_uint16_t_uint16_t);
    RUN_TEST(map_linear_probing_uint16_t_uint32_t);
    RUN_TEST(map_linear_probing_uint16_t_uint64_t);
    RUN_TEST(map_linear_probing_uint32_t_uint8_t);
    RUN_TEST(map_linear_probing_uint32_t_uint16_t);
    RUN_TEST(map_linear_probing_uint32_t_uint32_t);
    RUN_TEST(map_linear_probing_uint32_t_uint64_t);
    RUN_TEST(map_linear_probing_uint64_t_uint8_t);
    RUN_TEST(map_linear_probing_uint64_t_uint16_t);
    RUN_TEST(map_linear_probing_uint64_t_uint32_t);
    RUN_TEST(map_linear_probing_uint64_t_uint64_t);
    RUN_TEST(map_linear_probing_struct);
}
