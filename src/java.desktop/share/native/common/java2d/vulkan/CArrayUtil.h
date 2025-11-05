#ifndef C_ARRAY_UTIL_H
#define C_ARRAY_UTIL_H

#include <stdbool.h>
#include <stdint.h>

// C_ARRAY_UTIL_ALLOCATION_FAILED is called when allocation fails.
// Default implementation calls abort().
// Functions that can call C_ARRAY_UTIL_ALLOCATION_FAILED explicitly state
// this in the documentation. Functions with *_TRY_* keep the data structure unchanged.
#ifndef C_ARRAY_UTIL_ALLOCATION_FAILED
#include <stdlib.h>
#define C_ARRAY_UTIL_ALLOCATION_FAILED() abort()
#endif

// === Allocation helpers ===

#define CARR_MIN(a,b) (((a)<(b))?(a):(b))
#define CARR_MAX(a,b) (((a)>(b))?(a):(b))

static inline bool CARR_handle_alloc(bool CARR_result, bool CARR_force) {
    if (CARR_result || !CARR_force) return CARR_result;
    C_ARRAY_UTIL_ALLOCATION_FAILED();
    return false;
}

// === Arrays ===

#ifndef ARRAY_CAPACITY_GROW
#define ARRAY_CAPACITY_GROW(C) (((C) * 3 + 1) / 2) // 1.5 multiplier
#endif
#ifndef ARRAY_DEFAULT_CAPACITY
#define ARRAY_DEFAULT_CAPACITY 10
#endif

#define CARR_TYPED_ARRAY_T(T) struct { \
    size_t size; \
    size_t capacity; \
    T* data; \
}

#define CARR_ARRAY_ELEMENT_SIZE(ARRAY) (sizeof((ARRAY).data[0]))

typedef CARR_TYPED_ARRAY_T(void) untyped_array_t;

static inline void CARR_untyped_array_init(untyped_array_t* array) {
    array->size = 0;
    array->capacity = 0;
    array->data = NULL;
}

bool CARR_untyped_array_realloc(untyped_array_t* array, size_t element_size, size_t new_capacity);

static inline bool CARR_untyped_array_ensure_capacity(untyped_array_t* array, size_t element_size,
                                              size_t new_capacity, bool force) {
    if (new_capacity > array->capacity) {
        return CARR_handle_alloc(CARR_untyped_array_realloc(array, element_size, new_capacity), force);
    }
    return true;
}

static inline bool CARR_untyped_array_resize(untyped_array_t* array, size_t element_size, size_t new_size, bool force) {
    if (!CARR_untyped_array_ensure_capacity(array, element_size, new_size, force)) {
        return false;
    }

    array->size = new_size;
    return true;
}

static inline void CARR_untyped_array_push_back(untyped_array_t* array, size_t element_size) {
    // assert size <= capacity
    if (array->size == array->capacity) {
        const size_t new_capacity = array->size == 0 ? ARRAY_DEFAULT_CAPACITY : ARRAY_CAPACITY_GROW(array->size);
        if (!CARR_handle_alloc(CARR_untyped_array_realloc(array, element_size, new_capacity), true)) {
            return;
        }
    }
    ++array->size;
}

/**
 * Dynamic array declaration, e.g., ARRAY(int) my_array = {0};
 * @param TYPE type of the array element.
 */
#define ARRAY(T) union { \
    CARR_TYPED_ARRAY_T(T); \
    untyped_array_t as_untyped; \
}

/**
 * @param ARRAY array
 * @return dereferenced pointer to the last element in the array
 */
#define ARRAY_LAST(ARRAY) ((ARRAY).data[(ARRAY).size - 1])

/**
 * Deallocate the dynamic array
 * @param ARRAY array
 */
#define ARRAY_FREE(ARRAY) ((void)CARR_untyped_array_realloc(&(ARRAY).as_untyped, CARR_ARRAY_ELEMENT_SIZE((ARRAY)), 0))

/**
 * Ensure array capacity. Array is implicitly initialized when necessary.
 * On allocation failure, array is left unchanged.
 * @param ARRAY array
 * @param CAPACITY required capacity of the array
 * @return true if the operation succeeded
 */
#define ARRAY_TRY_ENSURE_CAPACITY(ARRAY, CAPACITY) \
    CARR_untyped_array_ensure_capacity(&(ARRAY).as_untyped, CARR_ARRAY_ELEMENT_SIZE((ARRAY)), (CAPACITY), false)

/**
 * Ensure array capacity. Array is implicitly initialized when necessary.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param ARRAY array
 * @param CAPACITY required capacity of the array
 */
#define ARRAY_ENSURE_CAPACITY(ARRAY, CAPACITY) \
    ((void)CARR_untyped_array_ensure_capacity(&(ARRAY).as_untyped, CARR_ARRAY_ELEMENT_SIZE((ARRAY)), (CAPACITY), true))

/**
 * Shrink capacity of the array to its size.
 * On allocation failure, array is left unchanged.
 * @param ARRAY array
 * @return true if the operation succeeded
 */
#define ARRAY_SHRINK_TO_FIT(ARRAY) CARR_untyped_array_realloc(&(ARRAY).as_untyped, CARR_ARRAY_ELEMENT_SIZE((ARRAY)), (ARRAY).size)

/**
 * Resize an array. Array is implicitly initialized when necessary.
 * On allocation failure, array is left unchanged.
 * @param ARRAY array
 * @param SIZE required size of the array
 * @return true if the operation succeeded
 */
#define ARRAY_TRY_RESIZE(ARRAY, SIZE) \
    CARR_untyped_array_resize(&(ARRAY).as_untyped, CARR_ARRAY_ELEMENT_SIZE((ARRAY)), (SIZE), false)

/**
 * Resize an array. Array is implicitly initialized when necessary.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param ARRAY array
 * @param SIZE required size of the array
 */
#define ARRAY_RESIZE(ARRAY, SIZE) \
    ((void)CARR_untyped_array_resize(&(ARRAY).as_untyped, CARR_ARRAY_ELEMENT_SIZE((ARRAY)), (SIZE), true))

/**
 * Add element to the end of the array. Array is implicitly initialized when necessary.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param ARRAY array
 * @return dereferenced pointer to the inserted element
 */
#define ARRAY_PUSH_BACK(ARRAY) \
    (*(CARR_untyped_array_push_back(&(ARRAY).as_untyped, CARR_ARRAY_ELEMENT_SIZE((ARRAY))), ((ARRAY).data) + ((ARRAY).size) - 1))

/**
 * Compile-time length of the static array.
 */
#define SARRAY_COUNT_OF(STATIC_ARRAY) (sizeof(STATIC_ARRAY)/sizeof((STATIC_ARRAY)[0]))

// === Ring buffers ===

#define CARR_TYPED_RING_BUFFER_T(T) struct { \
    size_t head_idx; \
    size_t size; \
    size_t capacity; \
    T* data; \
}

typedef CARR_TYPED_RING_BUFFER_T(void) untyped_ring_buffer_t;

bool CARR_untyped_ring_buffer_realloc(untyped_ring_buffer_t* ring_buffer, size_t element_size, size_t new_capacity);

static inline bool CARR_untyped_ring_buffer_ensure_can_push(untyped_ring_buffer_t* ring_buffer, size_t element_size, bool force) {
    // assert size <= capacity
    if (ring_buffer->size == ring_buffer->capacity) {
        const size_t new_capacity = ring_buffer->size == 0 ? ARRAY_DEFAULT_CAPACITY : ARRAY_CAPACITY_GROW(ring_buffer->size);
        return CARR_handle_alloc(CARR_untyped_ring_buffer_realloc(ring_buffer, element_size, new_capacity), force);
    }
    return true;
}

static inline void CARR_untyped_ring_buffer_push_front(untyped_ring_buffer_t* ring_buffer, size_t element_size) {
    CARR_untyped_ring_buffer_ensure_can_push(ring_buffer, element_size, true);
    ring_buffer->head_idx = (ring_buffer->head_idx + ring_buffer->capacity - 1) % ring_buffer->capacity;
    ++ring_buffer->size;
}

static inline void CARR_untyped_ring_buffer_push_back(untyped_ring_buffer_t* ring_buffer, size_t element_size) {
    CARR_untyped_ring_buffer_ensure_can_push(ring_buffer, element_size, true);
    ++ring_buffer->size;
}

static inline void CARR_untyped_ring_buffer_pop_front(untyped_ring_buffer_t* ring_buffer) {
    // assert size > 0
    ring_buffer->head_idx = (ring_buffer->head_idx + 1) % ring_buffer->capacity;
    --ring_buffer->size;
}

static inline void CARR_untyped_ring_buffer_pop_back(untyped_ring_buffer_t* ring_buffer) {
    // assert size > 0
    --ring_buffer->size;
}


/**
 * Ring buffer declaration, e.g. RING_BUFFER(int) my_ring = NULL;
 * @param TYPE type of the ring buffer element.
 */
#define RING_BUFFER(T) union { \
    CARR_TYPED_RING_BUFFER_T(T); \
    untyped_ring_buffer_t as_untyped; \
}

/**
 * Ensure enough capacity to push an element into ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, buffer is left unchanged.
 * @param RING_BUFFER ring buffer
 * @return true if the operation succeeded
 */
#define RING_BUFFER_TRY_ENSURE_CAN_PUSH(RING_BUFFER) \
    CARR_untyped_ring_buffer_ensure_can_push(&(RING_BUFFER).as_untyped, CARR_ARRAY_ELEMENT_SIZE((RING_BUFFER)), false)

/**
 * Ensure enough capacity to push an element into ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param RING_BUFFER ring buffer
 */
#define RING_BUFFER_ENSURE_CAN_PUSH(RING_BUFFER) \
    ((void)CARR_untyped_ring_buffer_ensure_can_push(&(RING_BUFFER).as_untyped, CARR_ARRAY_ELEMENT_SIZE((RING_BUFFER)), true))

/**
 * Add element to the beginning of the ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param RING_BUFFER ring buffer
 * @return dereferenced pointer to the inserted element
 */
#define RING_BUFFER_PUSH_FRONT(RING_BUFFER) \
    (*(CARR_untyped_ring_buffer_push_front(&(RING_BUFFER).as_untyped, CARR_ARRAY_ELEMENT_SIZE((RING_BUFFER))), (RING_BUFFER).data + (RING_BUFFER).head_idx))

/**
 * Add element to the end of the ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param RING_BUFFER ring buffer
 * @return dereferenced pointer to the inserted element
 */
#define RING_BUFFER_PUSH_BACK(RING_BUFFER) \
    (*(CARR_untyped_ring_buffer_push_back(&(RING_BUFFER).as_untyped, CARR_ARRAY_ELEMENT_SIZE((RING_BUFFER))), (RING_BUFFER).data + ((RING_BUFFER).head_idx + (RING_BUFFER).size - 1) % (RING_BUFFER).capacity))

/**
 * Get pointer to the first element of the ring buffer.
 * @param RING_BUFFER ring buffer
 * @return pointer to the first element of the ring buffer, or NULL
 */
#define RING_BUFFER_FRONT(RING_BUFFER) \
    ((RING_BUFFER).size == 0 ? NULL : (RING_BUFFER).data + (RING_BUFFER).head_idx)

/**
 * Get pointer to the last element of the ring buffer.
 * @param RING_BUFFER ring buffer
 * @return pointer to the last element of the ring buffer, or NULL
 */
#define RING_BUFFER_BACK(RING_BUFFER) \
    ((RING_BUFFER).size == 0 ? NULL : (RING_BUFFER).data + ((RING_BUFFER).head_idx + (RING_BUFFER).size) % ((RING_BUFFER).capacity))


/**
 * Move beginning of the ring buffer forward (remove first element).
 * @param RING_BUFFER ring buffer
 */
#define RING_BUFFER_POP_FRONT(RING_BUFFER) \
    CARR_untyped_ring_buffer_pop_front(&(RING_BUFFER).as_untyped)

/**
 * Move end of the ring buffer backward (remove last element).
 * @param RING_BUFFER ring buffer
 */
#define RING_BUFFER_POP_BACK(RING_BUFFER) \
    CARR_untyped_ring_buffer_pop_back(&(RING_BUFFER).as_untyped)

/**
 * Deallocate the ring buffer
 * @param RING_BUFFER ring buffer
 */
#define RING_BUFFER_FREE(RING_BUFFER) \
    ((void)CARR_untyped_ring_buffer_realloc(&(RING_BUFFER).as_untyped, CARR_ARRAY_ELEMENT_SIZE((RING_BUFFER)), 0))

// === Maps ===

typedef struct CARR_map_dispatch_struct CARR_map_dispatch_t;

#define CARR_TYPED_MAP_T(K, V) struct { \
    size_t size; \
    size_t capacity; \
    const CARR_map_dispatch_t* vptr; \
    void* impl_data; \
    const K* scratch_key_ptr; \
    V* scratch_value_ptr; \
}

typedef CARR_TYPED_MAP_T(void, void) untyped_map_t;

typedef bool (*CARR_equals_fp)(const void* a, const void* b);
typedef size_t (*CARR_hash_fp)(const void* data);

typedef const void* (*CARR_map_dispatch_next_key_fp)(const untyped_map_t* map, size_t key_size, size_t value_size, const void* key_slot);
typedef void* (*CARR_map_dispatch_find_fp)(untyped_map_t* map, size_t key_size, size_t value_size,
                                           const void* key, const void** resolved_key, bool insert);
typedef bool (*CARR_map_dispatch_remove_fp)(untyped_map_t* map, size_t key_size, size_t value_size, const void* key);
typedef bool (*CARR_map_dispatch_ensure_extra_capacity_fp)(untyped_map_t* map, size_t key_size, size_t value_size, size_t count);
typedef void (*CARR_map_dispatch_clear_fp)(untyped_map_t* map, size_t key_size, size_t value_size);
typedef void (*CARR_map_dispatch_free_fp)(untyped_map_t* map, size_t key_size, size_t value_size);

struct CARR_map_dispatch_struct {
    CARR_map_dispatch_next_key_fp next_key;
    CARR_map_dispatch_find_fp find;
    CARR_map_dispatch_remove_fp remove;
    CARR_map_dispatch_ensure_extra_capacity_fp ensure_extra_capacity;
    CARR_map_dispatch_clear_fp clear;
    CARR_map_dispatch_free_fp free;
};

#define CARR_MAP_LAYOUT(MAP) sizeof(*(MAP).scratch_key_ptr), sizeof(*(MAP).scratch_value_ptr)

#define CARR_MAP_DISPATCH_NO_ARGS(MAP, NAME) \
    ((MAP).vptr->NAME(&(MAP).as_untyped, CARR_MAP_LAYOUT((MAP))))

#define CARR_MAP_DISPATCH(MAP, NAME, ...) \
    ((MAP).vptr->NAME(&(MAP).as_untyped, CARR_MAP_LAYOUT((MAP)), __VA_ARGS__))

#define CARR_MAP_KEY_GUARD(MAP, ...) \
    (true ? (__VA_ARGS__) : (MAP).scratch_key_ptr) // Guard against wrong key types.


bool CARR_hash_map_linear_probing_rehash(untyped_map_t* map, size_t key_size, size_t value_size, CARR_equals_fp equals, CARR_hash_fp hash,
                                         size_t new_capacity, uint32_t probing_limit, float load_factor);

/**
 * Map declaration, e.g. MAP(int, int) my_map = {0};
 * Map must be explicitly initialized before usage, e.g. via HASH_MAP_REHASH.
 * @param K type of the map key.
 * @param V type of the map value.
 */
#define MAP(K, V) union { \
    CARR_TYPED_MAP_T(K, V); \
    untyped_map_t as_untyped; \
}

/**
 * Rehash a hash map with given strategy. It will be initialized if NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * List of available strategies with accepted parameters and sensible defaults:
 * 1. linear_probing (
 *      CARR_equals_fp equals,  // Key comparison function.
 *      CARR_hash_fp hash,      // Key hash calculation function.
 *      size_t new_capacity,    // New (min) capacity, must not be less than current number of items. Can be 0.
 *      uint32_t probing_limit, // Search length, triggering rehash. Must not be too low, around 10 should be fine?
 *      float load_factor       // Min load factor needed to allow rehash triggered by probing_limit. 0.75 is fine.
 *    )
 * @param MAP map
 * @param STRATEGY strategy to use
 * @param ... parameters for the rehash strategy
 */
#define HASH_MAP_REHASH(MAP, STRATEGY, ...) \
    ((void)CARR_handle_alloc(CARR_hash_map_##STRATEGY##_rehash(&(MAP).as_untyped, CARR_MAP_LAYOUT((MAP)), __VA_ARGS__), true))

/**
 * Rehash a hash map with given strategy. It will be initialized if NULL.
 * On allocation failure, map is left unchanged.
 * For list of available strategies see HASH_MAP_REHASH.
 * @param MAP map
 * @param STRATEGY strategy to use
 * @return true if the operation succeeded
 */
#define HASH_MAP_TRY_REHASH(MAP, STRATEGY, ...) \
    (CARR_hash_map_##STRATEGY##_rehash(&(MAP).as_untyped, CARR_MAP_LAYOUT((MAP)), __VA_ARGS__))

/**
 * Find the next resolved key present in the map, or NULL.
 * Enumeration order is implementation-defined.
 * @param MAP map
 * @param KEY_PTR pointer to the current resolved key, or NULL
 * @return pointer to the next resolved key
 */
#define MAP_NEXT_KEY(MAP, KEY_PTR) \
    (((MAP).scratch_key_ptr = CARR_MAP_DISPATCH((MAP), next_key, CARR_MAP_KEY_GUARD((MAP), (KEY_PTR)))))

/**
 * Find a value for the provided key.
 * @param MAP map
 * @param ... key to find, can be a compound literal, like (int){0}
 * @return pointer to the found value, or NULL
 */
#define MAP_FIND(MAP, ...) \
    (((MAP).scratch_value_ptr = CARR_MAP_DISPATCH((MAP), find, CARR_MAP_KEY_GUARD((MAP), &(__VA_ARGS__)), NULL, false)))

/**
 * Find a value for the provided key, or insert a new one.
 * Value is zeroed for newly inserted items.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param MAP map
 * @param ... key to find, can be a compound literal, like (int){0}
 * @return dereferenced pointer to the found value
 */
#define MAP_AT(MAP, ...) (*( \
    MAP_TRY_ENSURE_EXTRA_CAPACITY((MAP), 1), \
    (((MAP).scratch_value_ptr = CARR_MAP_DISPATCH((MAP), find, CARR_MAP_KEY_GUARD((MAP), &(__VA_ARGS__)), NULL, true))) \
))

/**
 * Resolve provided key and find corresponding value.
 * Using resolved key addresses speeds up subsequent map operations.
 * @param MAP map
 * @param KEY_PTR pointer to the key to find, replaced with resolved key address, or NULL
 * @return pointer to the found value, or NULL
 */
#define MAP_RESOLVE(MAP, KEY_PTR) \
    (((MAP).scratch_value_ptr = CARR_MAP_DISPATCH((MAP), find, CARR_MAP_KEY_GUARD((MAP), (KEY_PTR)), (const void**)&(KEY_PTR), false)))

/**
 * Resolve provided key and find corresponding value, or insert a new one.
 * Using resolved key addresses speeds up subsequent map operations.
 * Returned value pointer may be NULL, indicating that the entry was just inserted, use MAP_FIND or MAP_AT to access it.
 * On allocation failure, map is left unchanged.
 * @param MAP map
 * @param KEY_PTR pointer to the key to find, replaced with resolved key address
 * @return pointer to the found value, or NULL
 */
#define MAP_RESOLVE_OR_INSERT(MAP, KEY_PTR) ( \
    MAP_TRY_ENSURE_EXTRA_CAPACITY((MAP), 1), \
    (((MAP).scratch_value_ptr = CARR_MAP_DISPATCH((MAP), find, CARR_MAP_KEY_GUARD((MAP), (KEY_PTR)), (const void**)&(KEY_PTR), true))) \
)
// This kind of cast to const void** is UB (I think), but a proper use needs a fresh variable, which we can't really do in a macro.
// It's possible to add a map.scratch_resolved_key_ptr for this, but the current thing will work everywhere anyway...

/**
 * Remove the provided key, if one exists.
 * @param MAP map
 * @param ... key to remove, can be a compound literal, like (int){0}
 * @return true if the key was removed
 */
#define MAP_REMOVE(MAP, ...) CARR_MAP_DISPATCH((MAP), remove, CARR_MAP_KEY_GUARD((MAP), &(__VA_ARGS__)))

/**
 * Ensure that map has enough capacity to insert COUNT more items without reallocation.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param MAP map
 * @param COUNT number of new items
 */
#define MAP_ENSURE_EXTRA_CAPACITY(MAP, COUNT) ((void)CARR_handle_alloc(MAP_TRY_ENSURE_EXTRA_CAPACITY((MAP), (COUNT)), true))

/**
 * Ensure that map has enough capacity to insert COUNT more items without reallocation.
 * On allocation failure, map is left unchanged.
 * @param MAP map
 * @param COUNT number of new items
 * @return true if the operation succeeded
 */
#define MAP_TRY_ENSURE_EXTRA_CAPACITY(MAP, COUNT) CARR_MAP_DISPATCH((MAP), ensure_extra_capacity, (COUNT))

/**
 * Clear the map.
 * @param MAP map
 */
#define MAP_CLEAR(MAP) CARR_MAP_DISPATCH_NO_ARGS((MAP), clear)

/**
 * Free the map.
 * @param MAP map
 */
#define MAP_FREE(MAP) ((void)((MAP).vptr == NULL ? 0 : CARR_MAP_DISPATCH_NO_ARGS((MAP), free)))

#endif // C_ARRAY_UTIL_H
