#include <memory.h>
#include <stddef.h>
#include "CArrayUtil.h"

#if defined(_MSC_VER)
#   include <malloc.h>
#   define ALIGNED_ALLOC(ALIGNMENT, SIZE) _aligned_malloc((SIZE), (ALIGNMENT))
#   define ALIGNED_FREE(PTR) _aligned_free(PTR)
#else
#   include <stdlib.h>
#   define ALIGNED_ALLOC(ALIGNMENT, SIZE) aligned_alloc((ALIGNMENT), (SIZE))
#   define ALIGNED_FREE(PTR) free(PTR)
#endif

// === Allocation helpers ===

typedef struct {
    size_t total_alignment;
    size_t aligned_header_size;
    void* new_data;
} CARR_context_t;

static size_t CARR_align_size(size_t alignment, size_t size) {
    // assert alignment is power of 2
    size_t alignment_mask = alignment - 1;
    return (size + alignment_mask) & ~alignment_mask;
}

static CARR_context_t CARR_context_init(size_t header_alignment, size_t header_size, size_t data_alignment) {
    CARR_context_t context;
    // assert header_alignment and data_alignment are powers of 2
    context.total_alignment = CARR_MAX(header_alignment, data_alignment);
    // assert header_size is multiple of header_alignment
    context.aligned_header_size = CARR_align_size(context.total_alignment, header_size);
    context.new_data = NULL;
    return context;
}

static bool CARR_context_alloc(CARR_context_t* context, size_t data_size) {
    void* block = ALIGNED_ALLOC(context->total_alignment, context->aligned_header_size + data_size);
    if (block == NULL) return false;
    context->new_data = (char*)block + context->aligned_header_size;
    return true;
}

static void CARR_context_free(CARR_context_t* context, void* old_data) {
    if (old_data != NULL) {
        void* block = (char*)old_data - context->aligned_header_size;
        ALIGNED_FREE(block);
    }
}

// === Arrays ===

bool CARR_array_realloc(void** handle, size_t element_alignment, size_t element_size, size_t new_capacity) {
    void* old_data = *handle;
    if (old_data != NULL && CARR_ARRAY_T(old_data)->capacity == new_capacity) return true;
    CARR_context_t context = CARR_context_init(alignof(CARR_array_t), sizeof(CARR_array_t), element_alignment);
    if (new_capacity != 0) {
        if (!CARR_context_alloc(&context, element_size * new_capacity)) return false;
        CARR_ARRAY_T(context.new_data)->capacity = new_capacity;
        if (old_data == NULL) {
            CARR_ARRAY_T(context.new_data)->size = 0;
        } else {
            CARR_ARRAY_T(context.new_data)->size = CARR_MIN(CARR_ARRAY_T(old_data)->size, new_capacity);
            memcpy(context.new_data, old_data, element_size * CARR_ARRAY_T(context.new_data)->size);
        }
    }
    CARR_context_free(&context, old_data);
    *handle = context.new_data;
    return true;
}

// === Ring buffers ===

bool CARR_ring_buffer_realloc(void** handle, size_t element_alignment, size_t element_size, size_t new_capacity) {
    void* old_data = *handle;
    if (old_data != NULL) {
        CARR_ring_buffer_t* old_buf = CARR_RING_BUFFER_T(old_data);
        if (old_buf->capacity == new_capacity) return true;
        // Shrinking is not supported.
        if ((old_buf->capacity + old_buf->tail - old_buf->head) % old_buf->capacity > new_capacity) return false;
    }
    CARR_context_t context =
        CARR_context_init(alignof(CARR_ring_buffer_t), sizeof(CARR_ring_buffer_t), element_alignment);
    if (new_capacity != 0) {
        if (!CARR_context_alloc(&context, element_size * new_capacity)) return false;
        CARR_ring_buffer_t* new_buf = CARR_RING_BUFFER_T(context.new_data);
        new_buf->capacity = new_capacity;
        new_buf->head = new_buf->tail = 0;
        if (old_data != NULL) {
            CARR_ring_buffer_t* old_buf = CARR_RING_BUFFER_T(old_data);
            if (old_buf->tail > old_buf->head) {
                new_buf->tail = old_buf->tail - old_buf->head;
                memcpy(context.new_data, (char*)old_data + old_buf->head*element_size, new_buf->tail*element_size);
            } else if (old_buf->tail < old_buf->head) {
                new_buf->tail = old_buf->capacity + old_buf->tail - old_buf->head;
                memcpy(context.new_data, (char*)old_data + old_buf->head*element_size,
                    (old_buf->capacity-old_buf->head)*element_size);
                memcpy((char*)context.new_data + (new_buf->tail-old_buf->tail)*element_size, old_data,
                    old_buf->tail*element_size);
            }
        }
    }
    CARR_context_free(&context, old_data);
    *handle = context.new_data;
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

// Check whether memory chunk is non-zero.
static bool CARR_check_range(const void* p, size_t alignment, size_t size) {
    switch (alignment) {
        case sizeof(uint8_t):
        case sizeof(uint16_t):{
                const uint8_t* data = p;
                for (size_t i = 0; i < size; i++) {
                    if (data[i] != (uint8_t) 0) return true;
                }
        }break;
        case sizeof(uint32_t):{
                size >>= 2;
                const uint32_t* data = p;
                for (size_t i = 0; i < size; i++) {
                    if (data[i] != (uint32_t) 0) return true;
                }
        }break;
        default:{
                size >>= 3;
                const uint64_t* data = p;
                for (size_t i = 0; i < size; i++) {
                    if (data[i] != (uint64_t) 0) return true;
                }
        }break;
    }
    return false;
}

static bool CARR_map_insert_all(CARR_MAP_LAYOUT_ARGS, void* src, void* dst) {
    if (src == NULL) return true;
    const CARR_map_dispatch_t* src_dispatch = ((const CARR_map_dispatch_t**)src)[-1];
    const CARR_map_dispatch_t* dst_dispatch = ((const CARR_map_dispatch_t**)dst)[-1];
    for (const void* key = NULL; (key = src_dispatch->next_key(CARR_MAP_LAYOUT_PASS, src, key)) != NULL;) {
        const void* value = src_dispatch->find(CARR_MAP_LAYOUT_PASS, src, key, NULL, false);
        void* new_value = dst_dispatch->find(CARR_MAP_LAYOUT_PASS, dst, key, NULL, true);
        if (new_value == NULL) return false; // Cannot insert.
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
typedef struct {
    size_t capacity;
    size_t size;
    uint32_t probing_limit;
    float load_factor;
    void* null_key_slot;
    CARR_equals_fp equals;
    CARR_hash_fp hash;
    void* dispatch_placeholder;
} CARR_hash_map_probing_t;

static inline void* CARR_hash_map_probing_value_for(CARR_MAP_LAYOUT_ARGS, const void* data, const void* key_slot) {
    if (key_slot == NULL) return NULL;
    CARR_hash_map_probing_t* map = (CARR_hash_map_probing_t*) data - 1;
    size_t value_block_offset = CARR_align_size(value_alignment, key_size * map->capacity);
    return (char*)data + value_block_offset + ((const char*)key_slot - (char*)data) / key_size * value_size;
}

static size_t CARR_hash_map_probing_check_extra_capacity(CARR_hash_map_probing_t* map, size_t count) {
    // Run length is a local metric, which directly correlate with lookup performance,
    // but can suffer from clustering, bad hash function, or bad luck.
    // Load factor is a global metric, which reflects "fullness",
    // but doesn't capture local effects, like clustering,
    // and is over-conservative for good distributions.
    // Therefore, we only rehash when both load factor and probing limit are exceeded.
    size_t new_capacity = map->size + count;
    if (new_capacity <= map->capacity) {
        if (!(map->probing_limit & CARR_hash_map_probing_rehash_bit)) { // Rehashing not requested.
            new_capacity = 0;
        } else if (map->size < (size_t)(map->load_factor * (float)map->capacity)) {
            map->probing_limit &= CARR_hash_map_probing_limit_mask; // Load factor too low, reset rehash flag.
            new_capacity = 0;
        } else new_capacity = map->capacity + 1;
    }
    return new_capacity;
}

static const void* CARR_hash_map_probing_next_key(CARR_MAP_LAYOUT_ARGS, const void* data, const void* key_slot) {
    CARR_hash_map_probing_t* map = (CARR_hash_map_probing_t*) data - 1;
    char* slot;
    if (key_slot == NULL) slot = (char*)data;
    else if (key_slot < data) return NULL;
    else slot = (char*)key_slot + key_size;
    char* limit = (char*)data + key_size * (map->capacity - 1);
    for (; slot <= limit; slot += key_size) {
        if (CARR_check_range(slot, key_alignment, key_size) || slot == map->null_key_slot) return slot;
    }
    return NULL;
}

static void CARR_hash_map_probing_clear(CARR_MAP_LAYOUT_ARGS, void* data) {
    CARR_hash_map_probing_t* map = (CARR_hash_map_probing_t*) data - 1;
    memset(data, 0, key_size * map->capacity);
    map->probing_limit &= CARR_hash_map_probing_limit_mask;
    map->null_key_slot = NULL;
    map->size = 0;
}

static void CARR_hash_map_probing_free(CARR_MAP_LAYOUT_ARGS, void* data) {
    if (data == NULL) return;
    CARR_context_t context = CARR_context_init(alignof(CARR_hash_map_probing_t), sizeof(CARR_hash_map_probing_t),
                                               CARR_MAX(key_alignment, value_alignment));
    CARR_context_free(&context, data);
}

// === Linear probing hash map ===
static inline void CARR_hash_map_linear_probing_check_run(CARR_MAP_LAYOUT_ARGS, CARR_hash_map_probing_t* map,
                                                          const char* from, const char* to) {
    if (map->probing_limit & CARR_hash_map_probing_rehash_bit) return; // Rehashing already requested.
    if (map->size < (size_t)(map->load_factor * (float)map->capacity)) return; // Load factor too low.
    ptrdiff_t offset = to - from;
    if (to < from) offset += (ptrdiff_t)(map->capacity * key_size);
    size_t run = (size_t)offset / key_size;
    // Set rehash bit if our probing length exceeded the limit.
    if (run > (size_t)map->probing_limit) map->probing_limit |= CARR_hash_map_probing_rehash_bit;
}

static void* CARR_hash_map_linear_probing_find(CARR_MAP_LAYOUT_ARGS,
                                               void* data, const void* key, const void** resolved_key, bool insert) {
    CARR_hash_map_probing_t* map = (CARR_hash_map_probing_t*) data - 1;
    char* wrap = (char*)data + key_size * map->capacity;
    if (key >= data && key < (void*) wrap && ((const char*)key - (char*)data) % key_size == 0) {
        // Try fast access for resolved key.
        if (key == map->null_key_slot || CARR_check_range(key, key_alignment, key_size)) {
            if (resolved_key != NULL) *resolved_key = key;
            return CARR_hash_map_probing_value_for(CARR_MAP_LAYOUT_PASS, data, key);
        }
    }
    size_t hash = map->hash(key);
    char* start = (char*)data + key_size * (hash % map->capacity);
    char* slot = start;
    for (;;) {
        bool is_null = !CARR_check_range(slot, key_alignment, key_size);
        if (map->equals(key, slot)) {
            // Special case to distinguish null key from missing one.
            if (is_null) {
                if (map->null_key_slot == NULL && insert) {
                    map->null_key_slot = slot;
                    break; // Insert.
                }
                slot = map->null_key_slot;
            }
            if (resolved_key != NULL) *resolved_key = slot;
            return CARR_hash_map_probing_value_for(CARR_MAP_LAYOUT_PASS, data, slot);
        }
        if (is_null && slot != map->null_key_slot) { // Key not found.
            if (insert) break; // Insert.
            return resolved_key != NULL ? (void*)(*resolved_key = NULL) : NULL;
        }
        slot += key_size;
        if (slot == wrap) slot = (char*)data;
        if (slot == start) {
            return resolved_key != NULL ? (void*)(*resolved_key = NULL) : NULL; // We traversed the whole map.
        }
    }
    // Insert.
    void* value = CARR_hash_map_probing_value_for(CARR_MAP_LAYOUT_PASS, data, slot);
    memcpy(slot, key, key_size); // Copy key into slot.
    memset(value, 0, value_size); // Clear value.
    map->size++;
    if (resolved_key != NULL) {
        *resolved_key = slot;
        value = NULL; // Indicate that value was just inserted.
    }
    CARR_hash_map_linear_probing_check_run(CARR_MAP_LAYOUT_PASS, map, start, slot);
    return value;
}

static bool CARR_hash_map_linear_probing_remove(CARR_MAP_LAYOUT_ARGS, void* data, const void* key) {
    char* key_slot;
    CARR_hash_map_linear_probing_find(CARR_MAP_LAYOUT_PASS, data, key, (const void**) &key_slot, false);
    if (key_slot == NULL) return false;
    char* start = key_slot;
    CARR_hash_map_probing_t* map = (CARR_hash_map_probing_t*) data - 1;
    char* wrap = (char*)data + key_size * map->capacity;
    for (;;) {
        if (map->null_key_slot == key_slot) map->null_key_slot = NULL;
        char* slot = key_slot;
        for (;;) {
            slot += key_size;
            if (slot == wrap) slot = (char*)data;
            if (slot == start || (!CARR_check_range(slot, key_alignment, key_size) && slot != map->null_key_slot)) {
                memset(key_slot, 0, key_size); // Clear key slot.
                CARR_hash_map_linear_probing_check_run(CARR_MAP_LAYOUT_PASS, map, start, slot);
                return true;
            }
            size_t hash = map->hash(slot);
            char* expected_slot = (char*)data + key_size * (hash % map->capacity);
            if (slot >= expected_slot) {
                if (key_slot >= expected_slot && key_slot <= slot) break;
            } else {
                if (key_slot >= expected_slot || key_slot <= slot) break;
            }
        }
        // Move another entry into the gap.
        if (map->null_key_slot == slot) map->null_key_slot = key_slot;
        memcpy(key_slot, slot, key_size);
        memcpy(CARR_hash_map_probing_value_for(CARR_MAP_LAYOUT_PASS, data, key_slot),
               CARR_hash_map_probing_value_for(CARR_MAP_LAYOUT_PASS, data, slot), value_size);
        key_slot = slot; // Repeat with the new entry.
    }
}

static bool CARR_hash_map_linear_probing_ensure_extra_capacity(CARR_MAP_LAYOUT_ARGS, void** handle, size_t count) {
    void* data = *handle;
    CARR_hash_map_probing_t* map = (CARR_hash_map_probing_t*) data - 1;
    size_t new_capacity = CARR_hash_map_probing_check_extra_capacity(map, count);
    if (new_capacity == 0) return true;
    return CARR_hash_map_linear_probing_rehash(CARR_MAP_LAYOUT_PASS, handle, map->equals, map->hash, new_capacity,
                                               map->probing_limit & CARR_hash_map_probing_limit_mask, map->load_factor);
}

bool CARR_hash_map_linear_probing_rehash(CARR_MAP_LAYOUT_ARGS, void** handle, CARR_equals_fp equals, CARR_hash_fp hash,
                                         size_t new_capacity, uint32_t probing_limit, float load_factor) {
    size_t table_capacity = HASH_MAP_FIND_SIZE(CARR_hash_map_primes, new_capacity);
    if (table_capacity != 0) new_capacity = table_capacity;

    CARR_context_t context = CARR_context_init(alignof(CARR_hash_map_probing_t), sizeof(CARR_hash_map_probing_t),
                                               CARR_MAX(key_alignment, value_alignment));
    size_t value_block_offset = CARR_align_size(value_alignment, key_size * new_capacity);
    if (!CARR_context_alloc(&context, value_block_offset + value_size * new_capacity)) return false;

    CARR_hash_map_probing_t* map = (CARR_hash_map_probing_t*) context.new_data - 1;
    *map = (CARR_hash_map_probing_t) {
        .capacity = new_capacity,
        .size = 0,
        .probing_limit = CARR_MIN(probing_limit, CARR_hash_map_probing_limit_mask),
        .load_factor = load_factor,
        .null_key_slot = NULL,
        .equals = equals,
        .hash = hash
    };
    static const CARR_map_dispatch_t dispatch = {
        &CARR_hash_map_probing_next_key,
        &CARR_hash_map_linear_probing_find,
        &CARR_hash_map_linear_probing_remove,
        &CARR_hash_map_linear_probing_ensure_extra_capacity,
        &CARR_hash_map_probing_clear,
        &CARR_hash_map_probing_free,
    };
    ((const CARR_map_dispatch_t**)context.new_data)[-1] = &dispatch;

    CARR_hash_map_probing_clear(CARR_MAP_LAYOUT_PASS, context.new_data);
    if (!CARR_map_insert_all(CARR_MAP_LAYOUT_PASS, *handle, context.new_data)) {
        CARR_context_free(&context, context.new_data);
        return false;
    }

    if (*handle != NULL) ((const CARR_map_dispatch_t**)*handle)[-1]->free(CARR_MAP_LAYOUT_PASS, *handle);
    *handle = context.new_data;
    return true;
}
