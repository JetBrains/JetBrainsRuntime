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

static void test_map_linear_probing_struct() {
    typedef struct {
        uint64_t data[123];
    } struct_t;
    MAP(struct_t, uint8_t) big_key_map = {0};
    MAP(uint8_t, struct_t) big_val_map = {0};
    HASH_MAP_REHASH(big_key_map, linear_probing, &equals_uint64_t_uint8_t, &good_hash_uint64_t_uint8_t, 0, -1, 1.0);
    HASH_MAP_REHASH(big_val_map, linear_probing, &equals_uint8_t_uint64_t, &good_hash_uint8_t_uint64_t, 0, -1, 1.0);

    for (uint8_t i = 0;; i++) {
        uint8_t key = ((i & 0b10101010) >> 1) | ((i & 0b01010101) << 1);
        struct_t big_key = {{key}};
        uint8_t  *bkm_val, *bvm_key = &key;
        struct_t *bvm_val, *bkm_key = &big_key;
        bkm_val = MAP_RESOLVE_OR_INSERT(big_key_map, bkm_key);
        bvm_val = MAP_RESOLVE_OR_INSERT(big_val_map, bvm_key);
        if (bkm_key == NULL || bvm_key == NULL) fail();
        if (bkm_val != NULL || bvm_val != NULL) fail();
        bkm_val = MAP_FIND(big_key_map, *bkm_key);
        bvm_val = MAP_FIND(big_val_map, *bvm_key);
        if (bkm_val == NULL || bvm_val == NULL) fail();
        *bkm_val = key;
        *bvm_val = big_key;
        if (i == 255) break;
    }

    uint32_t count = 0;
    for (const struct_t* k = NULL; (k = MAP_NEXT_KEY(big_key_map, k)) != NULL;) {
        count++;
        if (k->data[0] != *MAP_FIND(big_key_map, *k)) fail();
    }
    if (count != 256) fail();

    count = 0;
    for (const uint8_t* k = NULL; (k = MAP_NEXT_KEY(big_val_map, k)) != NULL;) {
        count++;
        if (*k != MAP_FIND(big_val_map, *k)->data[0]) fail();
    }
    if (count != 256) fail();

    for (uint8_t i = 255;; i--) {
        struct_t big_key = {{i}};
        uint8_t  *bkm_val, *bvm_key = &i;
        struct_t *bvm_val, *bkm_key = &big_key;
        bkm_val = MAP_RESOLVE(big_key_map, bkm_key);
        bvm_val = MAP_RESOLVE(big_val_map, bvm_key);
        if (bkm_key == NULL || bvm_key == NULL) fail();
        if (bkm_val == NULL || bvm_val == NULL) fail();
        if (!MAP_REMOVE(big_key_map, *bkm_key)) fail();
        if (!MAP_REMOVE(big_val_map, *bvm_key)) fail();
        if (i == 0) break;
    }

    if (MAP_NEXT_KEY(big_key_map, NULL) != NULL) fail();
    if (MAP_NEXT_KEY(big_val_map, NULL) != NULL) fail();

    MAP_FREE(big_key_map);
    MAP_FREE(big_val_map);
}
