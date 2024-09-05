#ifndef C_ARRAY_UTIL_H
#define C_ARRAY_UTIL_H

#include <malloc.h>
#include <stdalign.h>
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
static inline void consume(const void* value) {}

// === Arrays ===

#ifndef ARRAY_CAPACITY_GROW
#define ARRAY_CAPACITY_GROW(C) (((C) * 3 + 1) / 2) // 1.5 multiplier
#endif
#ifndef ARRAY_DEFAULT_CAPACITY
#define ARRAY_DEFAULT_CAPACITY 10
#endif

typedef struct {
    size_t size;
    size_t capacity;
} CARR_array_t;

bool CARR_array_realloc(void** handle, size_t element_alignment, size_t element_size, size_t new_capacity);

#define CARR_ARRAY_T(P) ((CARR_array_t*)(P) - 1) // NULL unsafe!

static inline void* CARR_array_alloc(size_t element_alignment, size_t element_size, size_t new_capacity) {
    void* data = NULL;
    CARR_array_realloc(&data, element_alignment, element_size, new_capacity);
    return data;
}

static inline bool CARR_array_ensure_capacity(void** handle, size_t alignment, size_t size,
                                              size_t new_capacity, bool force) {
    void* data = *handle;
    if (new_capacity > (data == NULL ? 0 : CARR_ARRAY_T(data)->capacity)) {
        return CARR_handle_alloc(CARR_array_realloc(handle, alignment, size, new_capacity), force);
    }
    return true;
}

static inline bool CARR_array_resize(void** handle, size_t alignment, size_t size, size_t new_size, bool force) {
    if (CARR_array_ensure_capacity(handle, alignment, size, new_size, force)) {
        void* data = *handle;
        if (data != NULL) CARR_ARRAY_T(data)->size = new_size;
        return true;
    }
    return false;
}

static inline void CARR_array_push_back(void** handle, size_t alignment, size_t size) {
    void* data = *handle;
    if (data == NULL || CARR_ARRAY_T(data)->size >= CARR_ARRAY_T(data)->capacity) {
        size_t new_capacity = data == NULL ? ARRAY_DEFAULT_CAPACITY : ARRAY_CAPACITY_GROW(CARR_ARRAY_T(data)->size);
        if (!CARR_handle_alloc(CARR_array_realloc(handle, alignment, size, new_capacity), true)) return;
        data = *handle; // assert data != NULL
    }
    CARR_ARRAY_T(data)->size++;
}

/**
 * Dynamic array declaration, e.g. ARRAY(int) my_array = NULL;
 * @param TYPE type of the array element.
 */
#define ARRAY(TYPE) TYPE*

/**
 * Allocate array. Returns NULL on allocation failure.
 * @param T type of elements
 * @param CAPACITY capacity of the array
 * @return pointer to the allocated array, or NULL
 */
#define ARRAY_ALLOC(T, CAPACITY) ((T*)CARR_array_alloc(alignof(T), sizeof(T), CAPACITY))

/**
 * @param P array
 * @return size of the array
 */
#define ARRAY_SIZE(P) ((P) == NULL ? (size_t) 0 : (CARR_ARRAY_T(P))->size)

/**
 * @param P array
 * @return capacity of the array
 */
#define ARRAY_CAPACITY(P) ((P) == NULL ? (size_t) 0 : (CARR_ARRAY_T(P))->capacity)

/**
 * @param P array
 * @return dereferenced pointer to the last element in the array
 */
#define ARRAY_LAST(P) ((P)[ARRAY_SIZE(P) - 1])

/**
 * Deallocate the vector
 * @param P array
 */
#define ARRAY_FREE(P) ((void)CARR_array_realloc((void**)&(P), alignof(*(P)), sizeof(*(P)), 0))

/**
 * Apply function to the array elements
 * @param P array
 * @param F function to apply
 */
#define ARRAY_APPLY(P, F) do {                                   \
    for (size_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(&((P)[_i])); \
} while(0)

/**
 * Apply function to the array elements, passing pointer to an element as first parameter
 * @param P array
 * @param F function to apply
 */
#define ARRAY_APPLY_LEADING(P, F, ...) do {                                   \
    for (size_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(&((P)[_i]), __VA_ARGS__); \
} while(0)

/**
 * Apply function to the array elements, passing pointer to an element as last parameter
 * @param P array
 * @param F function to apply
 */
#define ARRAY_APPLY_TRAILING(P, F, ...) do {                                  \
    for (size_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(__VA_ARGS__, &((P)[_i])); \
} while(0)

/**
 * Ensure array capacity. Array is implicitly initialized when necessary.
 * On allocation failure, array is left unchanged.
 * @param P array
 * @param CAPACITY required capacity of the array
 * @return true if the operation succeeded
 */
#define ARRAY_TRY_ENSURE_CAPACITY(P, CAPACITY) \
    CARR_array_ensure_capacity((void**)&(P), alignof(*(P)), sizeof(*(P)), (CAPACITY), false)

/**
 * Ensure array capacity. Array is implicitly initialized when necessary.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P array
 * @param CAPACITY required capacity of the array
 */
#define ARRAY_ENSURE_CAPACITY(P, CAPACITY) \
    ((void)CARR_array_ensure_capacity((void**)&(P), alignof(*(P)), sizeof(*(P)), (CAPACITY), true))

/**
 * Shrink capacity of the array to its size.
 * On allocation failure, array is left unchanged.
 * @param P array
 * @return the array
 * @return true if the operation succeeded
 */
#define ARRAY_SHRINK_TO_FIT(P) CARR_array_realloc((void**)&(P), alignof(*(P)), sizeof(*(P)), ARRAY_SIZE(P))

/**
 * Resize an array. Array is implicitly initialized when necessary.
 * On allocation failure, array is left unchanged.
 * @param P array
 * @param SIZE required size of the array
 * @return true if the operation succeeded
 */
#define ARRAY_TRY_RESIZE(P, SIZE) \
    CARR_array_resize((void**)&(P), alignof(*(P)), sizeof(*(P)), (SIZE), false)

/**
 * Resize an array. Array is implicitly initialized when necessary.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P array
 * @param SIZE required size of the array
 */
#define ARRAY_RESIZE(P, SIZE) \
    ((void)CARR_array_resize((void**)&(P), alignof(*(P)), sizeof(*(P)), (SIZE), true))

/**
 * Add element to the end of the array. Array is implicitly initialized when necessary.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P array
 * @return dereferenced pointer to the inserted element
 */
#define ARRAY_PUSH_BACK(P) \
    (*(CARR_array_push_back((void**)&(P), alignof(*(P)), sizeof(*(P))), (P) + ARRAY_SIZE(P) - 1))

/**
 * Compile-time length of the static array.
 */
#define SARRAY_COUNT_OF(STATIC_ARRAY) (sizeof(STATIC_ARRAY)/sizeof((STATIC_ARRAY)[0]))

// === Ring buffers ===

typedef struct {
    size_t head;
    size_t tail;
    size_t capacity;
} CARR_ring_buffer_t;

bool CARR_ring_buffer_realloc(void** handle, size_t element_alignment, size_t element_size, size_t new_capacity);

#define CARR_RING_BUFFER_T(P) ((CARR_ring_buffer_t*)(P) - 1) // NULL / type unsafe!
#define CARR_RING_BUFFER_IS_NULL(P) (&(P)->CARR_elem == NULL) // Guard against wrong pointer types.
#define CARR_RING_BUFFER_GUARD(P, ...) (consume(&(P)->CARR_elem), __VA_ARGS__) // Guard against wrong pointer types.

static inline size_t CARR_ring_buffer_size(void* data) {
    CARR_ring_buffer_t* buffer = CARR_RING_BUFFER_T(data);
    return (buffer->capacity + buffer->tail - buffer->head) % buffer->capacity;
}

static inline bool CARR_ring_buffer_ensure_can_push(void** handle, size_t alignment, size_t size, bool force) {
    void* data = *handle;
    if (data == NULL || CARR_ring_buffer_size(data) + 1 >= CARR_RING_BUFFER_T(data)->capacity) {
        size_t new_capacity = data == NULL ?
            ARRAY_DEFAULT_CAPACITY : ARRAY_CAPACITY_GROW(CARR_RING_BUFFER_T(data)->capacity);
        return CARR_handle_alloc(CARR_ring_buffer_realloc(handle, alignment, size, new_capacity), force);
    }
    return true;
}

static inline size_t CARR_ring_buffer_push_front(void* data) {
    if (data == NULL) return 0;
    CARR_ring_buffer_t* buffer = CARR_RING_BUFFER_T(data);
    return buffer->head = (buffer->head + buffer->capacity - 1) % buffer->capacity;
}

static inline size_t CARR_ring_buffer_push_back(void* data) {
    if (data == NULL) return 0;
    CARR_ring_buffer_t* buffer = CARR_RING_BUFFER_T(data);
    size_t i = buffer->tail;
    buffer->tail = (buffer->tail + 1) % buffer->capacity;
    return i;
}

/**
 * Ring buffer declaration, e.g. RING_BUFFER(int) my_ring = NULL;
 * @param TYPE type of the ring buffer element.
 */
#define RING_BUFFER(TYPE) struct { TYPE CARR_elem; }*

/**
 * @param P ring buffer
 * @return size of the ring buffer
 */
#define RING_BUFFER_SIZE(P) (CARR_RING_BUFFER_IS_NULL(P) ? (size_t) 0 : CARR_ring_buffer_size(P))

/**
 * @param P ring buffer
 * @return capacity of the ring buffer
 */
#define RING_BUFFER_CAPACITY(P) (CARR_RING_BUFFER_IS_NULL(P) ? (size_t) 0 : CARR_RING_BUFFER_T(P)->capacity)

/**
 * Ensure enough capacity to push an element into ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, buffer is left unchanged.
 * @param P ring buffer
 * @return true if the operation succeeded
 */
#define RING_BUFFER_TRY_ENSURE_CAN_PUSH(P) CARR_RING_BUFFER_GUARD((P), \
    CARR_ring_buffer_ensure_can_push((void**)&(P), alignof(*(P)), sizeof(*(P)), false))

/**
 * Ensure enough capacity to push an element into ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P ring buffer
 */
#define RING_BUFFER_ENSURE_CAN_PUSH(P) CARR_RING_BUFFER_GUARD((P), \
    (void)CARR_ring_buffer_ensure_can_push((void**)&(P), alignof(*(P)), sizeof(*(P)), true))

/**
 * Add element to the beginning of the ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P ring buffer
 * @return dereferenced pointer to the inserted element
 */
#define RING_BUFFER_PUSH_FRONT(P) \
    ((RING_BUFFER_ENSURE_CAN_PUSH(P), (P) + CARR_ring_buffer_push_front(P))->CARR_elem)

/**
 * Add element to the end of the ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P ring buffer
 * @return dereferenced pointer to the inserted element
 */
#define RING_BUFFER_PUSH_BACK(P) \
    ((RING_BUFFER_ENSURE_CAN_PUSH(P), (P) + CARR_ring_buffer_push_back(P))->CARR_elem)

/**
 * Get pointer to the first element of the ring buffer.
 * @param P ring buffer
 * @return pointer to the first element of the ring buffer, or NULL
 */
#define RING_BUFFER_FRONT(P) (CARR_RING_BUFFER_IS_NULL(P) || \
    CARR_RING_BUFFER_T(P)->head == CARR_RING_BUFFER_T(P)->tail ? NULL : &(P)[CARR_RING_BUFFER_T(P)->head].CARR_elem)

/**
 * Get pointer to the last element of the ring buffer.
 * @param P ring buffer
 * @return pointer to the last element of the ring buffer, or NULL
 */
#define RING_BUFFER_BACK(P) (CARR_RING_BUFFER_IS_NULL(P) ||             \
    CARR_RING_BUFFER_T(P)->head == CARR_RING_BUFFER_T(P)->tail ? NULL : \
    &(P)[(CARR_RING_BUFFER_T(P)->tail+CARR_RING_BUFFER_T(P)->capacity-1) % CARR_RING_BUFFER_T(P)->capacity].CARR_elem)

/**
 * Move beginning of the ring buffer forward (remove first element).
 * @param P ring buffer
 */
#define RING_BUFFER_POP_FRONT(P) CARR_RING_BUFFER_GUARD((P), (void)(CARR_RING_BUFFER_T(P)->head = \
    (CARR_RING_BUFFER_T(P)->head + 1) % CARR_RING_BUFFER_T(P)->capacity))

/**
 * Move end of the ring buffer backward (remove last element).
 * @param P ring buffer
 */
#define RING_BUFFER_POP_BACK(P) CARR_RING_BUFFER_GUARD((P), (void)(CARR_RING_BUFFER_T(P)->tail = \
    (CARR_RING_BUFFER_T(P)->tail + CARR_RING_BUFFER_T(P)->capacity - 1) % CARR_RING_BUFFER_T(P)->capacity))

/**
 * Deallocate the ring buffer
 * @param P ring buffer
 */
#define RING_BUFFER_FREE(P) CARR_RING_BUFFER_GUARD((P), \
    (void)CARR_ring_buffer_realloc((void**)&(P), alignof(*(P)), sizeof(*(P)), 0))

// === Maps ===

typedef bool (*CARR_equals_fp)(const void* a, const void* b);
typedef size_t (*CARR_hash_fp)(const void* data);

#define CARR_MAP_LAYOUT_ARGS size_t key_alignment, size_t key_size, size_t value_alignment, size_t value_size
#define CARR_MAP_LAYOUT_PASS key_alignment, key_size, value_alignment, value_size
#define CARR_MAP_LAYOUT(P)                                                         \
    alignof((P)->CARR_keys[0].CARR_key[0]), sizeof((P)->CARR_keys[0].CARR_key[0]), \
    alignof((P)->CARR_values[0].CARR_value[0]), sizeof((P)->CARR_values[0].CARR_value[0])

typedef const void* (*CARR_map_dispatch_next_key_fp)(CARR_MAP_LAYOUT_ARGS, const void* data, const void* key_slot);
typedef void* (*CARR_map_dispatch_find_fp)(CARR_MAP_LAYOUT_ARGS,
                                           void* data, const void* key, const void** resolved_key, bool insert);
typedef bool (*CARR_map_dispatch_remove_fp)(CARR_MAP_LAYOUT_ARGS, void* data, const void* key);
typedef bool (*CARR_map_dispatch_ensure_extra_capacity_fp)(CARR_MAP_LAYOUT_ARGS, void** handle, size_t count);
typedef void (*CARR_map_dispatch_clear_fp)(CARR_MAP_LAYOUT_ARGS, void* data);
typedef void (*CARR_map_dispatch_free_fp)(CARR_MAP_LAYOUT_ARGS, void* data);

typedef struct {
    CARR_map_dispatch_next_key_fp next_key;
    CARR_map_dispatch_find_fp find;
    CARR_map_dispatch_remove_fp remove;
    CARR_map_dispatch_ensure_extra_capacity_fp ensure_extra_capacity;
    CARR_map_dispatch_clear_fp clear;
    CARR_map_dispatch_free_fp free;
} CARR_map_dispatch_t;

#define CARR_MAP_KEY_PTR(P, ...) \
    (&((true ? NULL : (P))->CARR_keys[((uintptr_t)(__VA_ARGS__) / sizeof((P)->CARR_keys[0]) - 1)].CARR_key[0]))
#define CARR_MAP_VALUE_PTR(P, ...) \
    (&((true ? NULL : (P))->CARR_values[((uintptr_t)(__VA_ARGS__) / sizeof((P)->CARR_values[0]) - 1)].CARR_value[0]))
#define CARR_MAP_KEY_GUARD(P, ...) \
    (true ? (__VA_ARGS__) : &(P)->CARR_keys[0].CARR_key[0]) // Guard against wrong key types.
#define CARR_MAP_DISPATCH(P, NAME, ...) \
    (((const CARR_map_dispatch_t**)(P))[-1]->NAME(CARR_MAP_LAYOUT(P), __VA_ARGS__))

bool CARR_hash_map_linear_probing_rehash(CARR_MAP_LAYOUT_ARGS, void** handle, CARR_equals_fp equals, CARR_hash_fp hash,
                                         size_t new_capacity, uint32_t probing_limit, float load_factor);

/**
 * Map declaration, e.g. MAP(int, int) my_map = NULL;
 * Map must be explicitly initialized before usage, e.g. via HASH_MAP_REHASH.
 * @param KEY_TYPE type of the map key.
 * @param VALUE_TYPE type of the map value.
 */
#define MAP(KEY_TYPE, VALUE_TYPE) union {                                \
    struct { char CARR_dummy; const KEY_TYPE CARR_key[]; } CARR_keys[1]; \
    struct { char CARR_dummy; VALUE_TYPE CARR_value[]; } CARR_values[1]; \
}*

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
 * @param P map
 * @param STRATEGY strategy to use
 * @param ... parameters for the rehash strategy
 */
#define HASH_MAP_REHASH(P, STRATEGY, ...) \
    ((void)CARR_handle_alloc(CARR_hash_map_##STRATEGY##_rehash(CARR_MAP_LAYOUT(P), (void**)&(P), __VA_ARGS__), true))

/**
 * Rehash a hash map with given strategy. It will be initialized if NULL.
 * On allocation failure, map is left unchanged.
 * For list of available strategies see HASH_MAP_REHASH.
 * @param P map
 * @param STRATEGY strategy to use
 * @return true if the operation succeeded
 */
#define HASH_MAP_TRY_REHASH(P, STRATEGY, ...) \
    (CARR_hash_map_##STRATEGY##_rehash(CARR_MAP_LAYOUT(P), (void**)&(P), __VA_ARGS__))

/**
 * Find the next resolved key present in the map, or NULL.
 * Enumeration order is implementation-defined.
 * @param P map
 * @param KEY_PTR pointer to the current resolved key, or NULL
 * @return pointer to the next resolved key
 */
#define MAP_NEXT_KEY(P, KEY_PTR) \
    CARR_MAP_KEY_PTR((P), CARR_MAP_DISPATCH((P), next_key, (P), CARR_MAP_KEY_GUARD((P), (KEY_PTR))))

/**
 * Find a value for the provided key.
 * @param P map
 * @param KEY key to find, can be a compound literal, like (int){0}
 * @return pointer to the found value, or NULL
 */
#define MAP_FIND(P, KEY) \
    CARR_MAP_VALUE_PTR((P), CARR_MAP_DISPATCH((P), find, (P), CARR_MAP_KEY_GUARD((P), &(KEY)), NULL, false))

/**
 * Find a value for the provided key, or insert a new one.
 * Value is zeroed for newly inserted items.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P map
 * @param KEY key to find, can be a compound literal, like (int){0}
 * @return dereferenced pointer to the found value
 */
#define MAP_AT(P, KEY) (*(MAP_ENSURE_EXTRA_CAPACITY((P), 1), \
    CARR_MAP_VALUE_PTR((P), CARR_MAP_DISPATCH((P), find, (P), CARR_MAP_KEY_GUARD((P), &(KEY)), NULL, true))))

/**
 * Resolve provided key and find corresponding value.
 * Using resolved key addresses speeds up subsequent map operations.
 * @param P map
 * @param KEY_PTR pointer to the key to find, replaced with resolved key address, or NULL
 * @return pointer to the found value, or NULL
 */
#define MAP_RESOLVE(P, KEY_PTR) CARR_MAP_VALUE_PTR((P), \
    CARR_MAP_DISPATCH((P), find, (P), CARR_MAP_KEY_GUARD((P), (KEY_PTR)), (const void**) &(KEY_PTR), false))

/**
 * Resolve provided key and find corresponding value, or insert a new one.
 * Using resolved key addresses speeds up subsequent map operations.
 * Returned value pointer may be NULL, indicating that the entry was just inserted, use MAP_FIND or MAP_AT to access it.
 * On allocation failure, map is left unchanged.
 * @param P map
 * @param KEY_PTR pointer to the key to find, replaced with resolved key address
 * @return pointer to the found value, or NULL
 */
#define MAP_RESOLVE_OR_INSERT(P, KEY_PTR) (MAP_TRY_ENSURE_EXTRA_CAPACITY((P), 1), CARR_MAP_VALUE_PTR((P), \
    CARR_MAP_DISPATCH((P), find, (P), CARR_MAP_KEY_GUARD((P), (KEY_PTR)), (const void**) &(KEY_PTR), true)))

/**
 * Remove the provided key, if one exists.
 * @param P map
 * @param KEY key to remove, can be a compound literal, like (int){0}
 * @return true if the key was removed
 */
#define MAP_REMOVE(P, KEY) CARR_MAP_DISPATCH((P), remove, (P), CARR_MAP_KEY_GUARD((P), &(KEY)))

/**
 * Ensure that map has enough capacity to insert COUNT more items without reallocation.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P map
 * @param COUNT number of new items
 */
#define MAP_ENSURE_EXTRA_CAPACITY(P, COUNT) ((void)CARR_handle_alloc(MAP_TRY_ENSURE_EXTRA_CAPACITY((P), (COUNT)), true))

/**
 * Ensure that map has enough capacity to insert COUNT more items without reallocation.
 * On allocation failure, map is left unchanged.
 * @param P map
 * @param COUNT number of new items
 * @return true if the operation succeeded
 */
#define MAP_TRY_ENSURE_EXTRA_CAPACITY(P, COUNT) CARR_MAP_DISPATCH((P), ensure_extra_capacity, (void**)&(P), (COUNT))

/**
 * Clear the map.
 * @param P map
 */
#define MAP_CLEAR(P) CARR_MAP_DISPATCH((P), clear, (P))

/**
 * Free the map.
 * @param P map
 */
#define MAP_FREE(P) ((P) == NULL ? 0 : CARR_MAP_DISPATCH((P), free, (P)), (void)((P) = NULL))

#endif // C_ARRAY_UTIL_H
