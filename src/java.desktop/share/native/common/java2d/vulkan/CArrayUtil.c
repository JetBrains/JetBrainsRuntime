#include <memory.h>
#include <stddef.h>
#include "CArrayUtil.h"

// === Arrays ===

bool CARR_untyped_array_realloc(untyped_array_t* array, size_t element_size, size_t new_capacity) {
    if (array->capacity == new_capacity) {
        return true;
    }

    untyped_array_t new_array = {
        .size = CARR_MIN(array->size, new_capacity),
        .capacity = new_capacity,
        .data = NULL
    };
    if (new_capacity != 0) {
        new_array.data = malloc(element_size * new_capacity);
        if (!new_array.data) {
            return false;
        }

        if (array->data != NULL) {
            memcpy(new_array.data, array->data, element_size * new_array.size);
        }
    }

    free(array->data);
    *array = new_array;
    return true;
}

// === Ring buffers ===

bool CARR_untyped_ring_buffer_realloc(untyped_ring_buffer_t* ring_buffer, size_t element_size, size_t new_capacity) {
    if (ring_buffer->capacity == new_capacity) {
        return true;
    }

    // Shrinking while discarding elements is not supported.
    if (ring_buffer->size > new_capacity) {
        return false;
    }

    untyped_ring_buffer_t new_ring_buffer = {
        .head_idx = 0,
        .size = ring_buffer->size,
        .capacity = new_capacity,
        .data = NULL
    };
    if (new_capacity != 0) {
        new_ring_buffer.data = malloc(element_size * new_capacity);
        if (!new_ring_buffer.data) {
            return false;
        }

        if (ring_buffer->data != NULL) {
            if (ring_buffer->head_idx + ring_buffer->size <= ring_buffer->capacity) {
                // The 'single span' case
                memcpy(new_ring_buffer.data, (char*)ring_buffer->data + ring_buffer->head_idx * element_size, ring_buffer->size * element_size);
            } else {
                // The 'two spans' case
                const size_t first_span_size = ring_buffer->capacity - ring_buffer->head_idx;
                memcpy(new_ring_buffer.data, (char*)ring_buffer->data + ring_buffer->head_idx * element_size, first_span_size * element_size);
                memcpy((char*)new_ring_buffer.data + first_span_size * element_size, ring_buffer->data, (ring_buffer->size - first_span_size) * element_size);
            }
        }
    }

    free(ring_buffer->data);
    *ring_buffer = new_ring_buffer;
    return true;
}

// === Maps ===

static const size_t CARR_hash_map_primes[] = { 11U, 23U, 47U, 97U, 193U, 389U, 769U, 1543U, 3079U, 6151U,
                                               12289U, 24593U, 49157U, 98317U, 196613U, 393241U, 786433U,
                                               1572869U, 3145739U, 6291469U, 12582917U, 25165843U, 50331653U,
                                               100663319U, 201326611U, 402653189U, 805306457U, 1610612741U };
static size_t CARR_hash_map_find_size(const size_t* table, unsigned int table_length, size_t min) {
    for (unsigned int i = 0; i < table_length; ++i) if (table[i] >= min) return table[i];
    return 0; // Do not return min, as this may break addressing variants which rely on specific numeric properties.
}
#define HASH_MAP_FIND_SIZE(TABLE, SIZE) CARR_hash_map_find_size(TABLE, SARRAY_COUNT_OF(TABLE), SIZE)

// Check whether the whole memory chunk is non-zero.
static bool CARR_check_range_is_nonzero(const void* data, size_t size) {
    // Not sure if we need anything 'faster' here...
    for (size_t i = 0; i < size; ++i) {
        if (((const char*)data)[i] != 0) {
            return true;
        }
    }
    return false;
}

static bool CARR_map_insert_all(untyped_map_t* src_map, untyped_map_t* dst_map, size_t key_size, size_t value_size) {
    if (src_map->vptr == NULL) {
        return true;
    }

    for (const void* key = NULL; (key = src_map->vptr->next_key(src_map, key_size, value_size, key)) != NULL;) {
        const void* value = src_map->vptr->find(src_map, key_size, value_size, key, NULL, false);
        void* new_value = dst_map->vptr->find(dst_map, key_size, value_size, key, NULL, true);
        if (new_value == NULL) {
            return false; // Cannot insert.
        }
        memcpy(new_value, value, value_size);
    }
    return true;
}

// === Open addressing (probing) hash maps ===
// Probing hash maps keep keys and values separately in two continuous aligned memory blocks.
// This class is the most memory-efficient with no overhead other than fixed size header.
// It provides O(1) lookup even when full, except for the cases of deletion and missing
// key, which degrade down to O(N). This makes it a good choice for caches, which
// only do "find or insert" and never delete elements.
static const uint32_t CARR_hash_map_probing_rehash_bit = 0x80000000;
static const uint32_t CARR_hash_map_probing_limit_mask = 0x7fffffff;
typedef struct CARR_hash_map_probing_impl_data_struct {
    void* key_data;
    void* value_data;

    uint32_t probing_limit;
    float load_factor;
    void* zero_key_slot; // points to the all-zero key if one exists (to distinguish from a missing key)

    CARR_equals_fp equals;
    CARR_hash_fp hash;
} CARR_hash_map_probing_impl_data_t;

static inline void* CARR_hash_map_probing_value_for(const untyped_map_t* map, size_t key_size, size_t value_size, const void* key_slot) {
    if (key_slot == NULL) {
        return NULL;
    }

    CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;
    return (char*)impl_data->value_data + ((const char*)key_slot - (char*)impl_data->key_data) / key_size * value_size;
}

static size_t CARR_hash_map_probing_check_extra_capacity(const untyped_map_t* map, size_t count) {
    // Run length is a local metric, which directly correlate with lookup performance,
    // but can suffer from clustering, bad hash function, or bad luck.
    // Load factor is a global metric, which reflects "fullness",
    // but doesn't capture local effects, like clustering,
    // and is over-conservative for good distributions.
    // Therefore, we only rehash when both load factor and probing limit are exceeded.
    CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;
    size_t new_capacity = map->size + count;
    if (new_capacity <= map->capacity) {
        if (!(impl_data->probing_limit & CARR_hash_map_probing_rehash_bit)) { // Rehashing not requested.
            new_capacity = 0;
        } else if (map->size < (size_t)(impl_data->load_factor * (float)map->capacity)) {
            impl_data->probing_limit &= CARR_hash_map_probing_limit_mask; // Load factor too low, reset rehash flag.
            new_capacity = 0;
        } else {
            new_capacity = map->capacity + 1;
        }
    }
    return new_capacity;
}

static const void* CARR_hash_map_probing_next_key(const untyped_map_t* map, size_t key_size, size_t value_size, const void* key_slot) {
    const CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;
    char* slot;
    if (key_slot == NULL) {
        slot = impl_data->key_data;
    } else {
        slot = (char*)key_slot + key_size;
    }
    for (const char* key_data_end = (char*)impl_data->key_data + key_size * map->capacity; slot < key_data_end; slot += key_size) {
        if (CARR_check_range_is_nonzero(slot, key_size) || slot == impl_data->zero_key_slot) {
            return slot;
        }
    }
    return NULL;
}

static void CARR_hash_map_probing_clear(untyped_map_t* map, size_t key_size, size_t value_size) {
    map->size = 0;

    CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;
    memset(impl_data->key_data, 0, key_size * map->capacity);
    impl_data->probing_limit &= CARR_hash_map_probing_limit_mask;
    impl_data->zero_key_slot = NULL;
}

static void CARR_hash_map_probing_free(untyped_map_t* map, size_t key_size, size_t value_size) {
    CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;
    if (impl_data == NULL) {
        return;
    }

    free(impl_data->key_data);
    free(impl_data->value_data);
    free(impl_data);
    *map = (untyped_map_t){0};
}

// === Linear probing hash map ===
static inline void CARR_hash_map_linear_probing_check_run(untyped_map_t* map, size_t key_size, size_t value_size,
                                                          const char* occupied_begin, const char* occupied_end) {
    CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;
    if (impl_data->probing_limit & CARR_hash_map_probing_rehash_bit) {
        return; // Rehashing already requested.
    }
    if (map->size < (size_t)(impl_data->load_factor * (float)map->capacity)) {
        return; // Load factor too low.
    }
    ptrdiff_t offset = occupied_end - occupied_begin;
    if (occupied_end < occupied_begin) {
        offset += (ptrdiff_t)(map->capacity * key_size);
    }
    const size_t run = (size_t)offset / key_size;
    // Set the rehash bit if our probing length exceeded the limit.
    if (run > (size_t)impl_data->probing_limit) {
        impl_data->probing_limit |= CARR_hash_map_probing_rehash_bit;
    }
}

static void* CARR_hash_map_linear_probing_find(untyped_map_t* map, size_t key_size, size_t value_size,
                                           const void* key, const void** resolved_key, bool insert) {
    CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;

    char* key_data_end = (char*)impl_data->key_data + key_size * map->capacity;

    // Resolved access path (`key` already points into the map):
    // WARNING: THIS CHECK IS UNDEFINED BEHAVIOR!
    // We are not really allowed by the C standard to check whether a pointer lies inside an array, but we're doing it anyway.
    if (key >= impl_data->key_data && key < (void*)key_data_end && ((const char*)key - (char*)impl_data->key_data) % key_size == 0) {
        // assert `key` is not an uninitialized slot (which would be a logical error anyway)
        if (resolved_key != NULL) {
            // We can discard const, since we now know the key is part of the map's (non-const) allocation.
            *resolved_key = key;
        }
        return CARR_hash_map_probing_value_for(map, key_size, value_size, key);
    }

    // The general case:

    const size_t hash = impl_data->hash(key);
    char* initial_slot = (char*)impl_data->key_data + key_size * (hash % map->capacity);
    char* slot = initial_slot;
    for (;;) {
        const bool is_null = !CARR_check_range_is_nonzero(slot, key_size);
        if (impl_data->equals(key, slot)) {
            // Special case to distinguish null key from missing one.
            if (is_null) {
                if (impl_data->zero_key_slot == NULL && insert) {
                    impl_data->zero_key_slot = slot;
                    break; // Insert.
                }
                slot = impl_data->zero_key_slot;
            }
            if (resolved_key != NULL) *resolved_key = slot;
            return CARR_hash_map_probing_value_for(map, key_size, value_size, slot);
        }
        if (is_null && slot != impl_data->zero_key_slot) { // Key not found.
            if (insert) {
                break; // Insert.
            }
            if (resolved_key != NULL) {
                *resolved_key = NULL;
            }
            return NULL;
        }
        slot += key_size;
        if (slot == key_data_end) slot = (char*)impl_data->key_data;
        if (slot == initial_slot) {
            // We traversed the whole map.
            if (resolved_key != NULL) {
                *resolved_key = NULL;
            }
            return NULL;
        }
    }

    // Insert.
    void* value = CARR_hash_map_probing_value_for(map, key_size, value_size, slot);
    memcpy(slot, key, key_size); // Copy key into slot.
    memset(value, 0, value_size); // Clear value.
    map->size++;
    if (resolved_key != NULL) {
        *resolved_key = slot;
        value = NULL; // Indicate that value was just inserted.
    }
    CARR_hash_map_linear_probing_check_run(map, key_size, value_size, initial_slot, slot);
    return value;
}

static bool CARR_hash_map_linear_probing_remove(untyped_map_t* map, size_t key_size, size_t value_size, const void* key) {
    const void* key_slot_void_ptr;
    CARR_hash_map_linear_probing_find(map, key_size, value_size, key, &key_slot_void_ptr, false);
    char* key_slot = (char*)key_slot_void_ptr; // It's ok to remove const from resolved key ptrs in this impl
    if (key_slot == NULL) {
        return false;
    }

    CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;
    const char* initial_slot = key_slot;
    const char* key_data_end = (char*)impl_data->key_data + key_size * map->capacity;
    for (;;) {
        if (impl_data->zero_key_slot == key_slot) {
            impl_data->zero_key_slot = NULL;
        }
        char* slot = key_slot;
        for (;;) {
            slot += key_size;
            if (slot == key_data_end) {
                slot = (char*)impl_data->key_data;
            }
            if (slot == initial_slot || (!CARR_check_range_is_nonzero(slot, key_size) && slot != impl_data->zero_key_slot)) {
                memset(key_slot, 0, key_size); // Clear the key slot.
                CARR_hash_map_linear_probing_check_run(map, key_size, value_size, initial_slot, slot);
                return true;
            }
            const size_t hash = impl_data->hash(slot);
            const char* expected_slot = (char*)impl_data->key_data + key_size * (hash % map->capacity);
            if (slot >= expected_slot) {
                if (key_slot >= expected_slot && key_slot <= slot) {
                    break;
                }
            } else {
                if (key_slot >= expected_slot || key_slot <= slot) {
                    break;
                }
            }
        }
        // Move another entry into the gap.
        if (impl_data->zero_key_slot == slot) {
            impl_data->zero_key_slot = key_slot;
        }
        memcpy(key_slot, slot, key_size);
        memcpy(CARR_hash_map_probing_value_for(map, key_size, value_size, key_slot),
               CARR_hash_map_probing_value_for(map, key_size, value_size, slot), value_size);
        key_slot = slot; // Repeat with the new entry.
    }
}

static bool CARR_hash_map_linear_probing_ensure_extra_capacity(untyped_map_t* map, size_t key_size, size_t value_size, size_t count) {

    const size_t new_capacity = CARR_hash_map_probing_check_extra_capacity(map, count);
    if (new_capacity == 0) {
        return true;
    }

    CARR_hash_map_probing_impl_data_t* impl_data = map->impl_data;
    return CARR_hash_map_linear_probing_rehash(map, key_size, value_size, impl_data->equals, impl_data->hash, new_capacity,
                                               impl_data->probing_limit & CARR_hash_map_probing_limit_mask, impl_data->load_factor);
}

bool CARR_hash_map_linear_probing_rehash(untyped_map_t* map, size_t key_size, size_t value_size, CARR_equals_fp equals, CARR_hash_fp hash,
                                         size_t new_capacity, uint32_t probing_limit, float load_factor) {
    const size_t table_capacity = HASH_MAP_FIND_SIZE(CARR_hash_map_primes, new_capacity);
    if (table_capacity != 0) {
        new_capacity = table_capacity;
    }

    static const CARR_map_dispatch_t dispatch = {
        &CARR_hash_map_probing_next_key,
        &CARR_hash_map_linear_probing_find,
        &CARR_hash_map_linear_probing_remove,
        &CARR_hash_map_linear_probing_ensure_extra_capacity,
        &CARR_hash_map_probing_clear,
        &CARR_hash_map_probing_free,
    };
    untyped_map_t new_map = {
        .size = 0,
        .capacity = new_capacity,
        .vptr = &dispatch,
        .impl_data = NULL,
        .scratch_key_ptr = NULL,
        .scratch_value_ptr = NULL
    };
    CARR_hash_map_probing_impl_data_t* new_impl_data = malloc(sizeof(CARR_hash_map_probing_impl_data_t));
    if (new_impl_data == NULL) {
        goto error_alloc_impl_data;
    }
    *new_impl_data = (CARR_hash_map_probing_impl_data_t){
        .key_data = NULL,
        .value_data = NULL,
        .probing_limit = CARR_MIN(probing_limit, CARR_hash_map_probing_limit_mask),
        .load_factor = load_factor,
        .zero_key_slot = NULL,
        .equals = equals,
        .hash = hash
    };
    new_map.impl_data = new_impl_data;

    new_impl_data->key_data = malloc(key_size * new_capacity);
    if (new_impl_data->key_data == NULL) {
        goto error_alloc_key_data;
    }

    new_impl_data->value_data = malloc(value_size * new_capacity);
    if (new_impl_data->value_data == NULL) {
        goto error_alloc_value_data;
    }

    CARR_hash_map_probing_clear(&new_map, key_size, value_size);
    if (!CARR_map_insert_all(map, &new_map, key_size, value_size)) {
        goto error_insert;
    }

    if (map->vptr != NULL) {
        map->vptr->free(map, key_size, value_size);
    }
    *map = new_map;
    return true;

    error_insert:
    free(new_impl_data->value_data);
    error_alloc_value_data:
    free(new_impl_data->key_data);
    error_alloc_key_data:
    free(new_impl_data);
    error_alloc_impl_data:
    return false;
}
