#ifndef C_ARRAY_UTIL_H
#define C_ARRAY_UTIL_H

#include <malloc.h>

// C_ARRAY_UTIL_ALLOCATION_FAILED is called when allocation fails.
// Default implementation calls abort().
// Functions that can call C_ARRAY_UTIL_ALLOCATION_FAILED explicitly state
// this in the documentation. Functions with *_TRY_* return NULL on failure.
#ifndef C_ARRAY_UTIL_ALLOCATION_FAILED
#include <stdlib.h>
#define C_ARRAY_UTIL_ALLOCATION_FAILED() abort()
#endif

#define ARRAY_CAPACITY_GROW(C) (((C) * 3 + 1) / 2) // 1.5 multiplier
#define ARRAY_DEFAULT_CAPACITY 10
typedef struct {
    size_t size;
    size_t capacity;
    char data[];
} CARR_array_t;

void* CARR_array_alloc(size_t elem_size, size_t capacity);
void* CARR_array_realloc(CARR_array_t* vec, size_t elem_size, size_t new_capacity);

typedef struct {
    size_t head;
    size_t tail;
    size_t capacity;
    char data[];
} CARR_ring_buffer_t;

void* CARR_ring_buffer_realloc(CARR_ring_buffer_t* buf, size_t elem_size, size_t new_capacity);

/**
 * Allocate array. Returns NULL on allocation failure.
 * @param T type of elements
 * @param CAPACITY capacity of the array
 */
#define ARRAY_ALLOC(T, CAPACITY) (T*)CARR_array_alloc(sizeof(T), CAPACITY)

#define ARRAY_T(P) ((CARR_array_t *)((P) == NULL ? NULL : (char*)(P) - offsetof(CARR_array_t, data)))

/**
 * @param P pointer to the first data element of the array
 * @return size of the array
 */
#define ARRAY_SIZE(P) ((P) == NULL ? (size_t) 0 : (ARRAY_T(P))->size)

/**
 * @param P pointer to the first data element of the array
 * @return capacity of the array
 */
#define ARRAY_CAPACITY(P) ((P) == NULL ? (size_t) 0 : (ARRAY_T(P))->capacity)

/**
 * @param P pointer to the first data element of the array
 * @return last element in the array
 */
#define ARRAY_LAST(P) ((P)[ARRAY_SIZE(P) - 1])

/**
 * Deallocate the vector
 * @param P pointer to the first data element of the array
 */
#define ARRAY_FREE(P) free(ARRAY_T(P))

/**
 * Apply function to the array elements
 * @param P pointer to the first data element of the array
 * @param F function to apply
 */
#define ARRAY_APPLY(P, F) do {                                   \
    for (size_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(&((P)[_i])); \
} while(0)

/**
 * Apply function to the array elements, passing pointer to an element as first parameter
 * @param P pointer to the first data element of the array
 * @param F function to apply
 */
#define ARRAY_APPLY_LEADING(P, F, ...) do {                                   \
    for (size_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(&((P)[_i]), __VA_ARGS__); \
} while(0)

/**
 * Apply function to the array elements, passing pointer to an element as last parameter
 * @param P pointer to the first data element of the array
 * @param F function to apply
 */
#define ARRAY_APPLY_TRAILING(P, F, ...) do {                                  \
    for (size_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(__VA_ARGS__, &((P)[_i])); \
} while(0)

/**
 * Ensure array capacity. Implicitly initializes when array is NULL.
 * On allocation failure, array is unchanged.
 * @param P pointer to the first data element of the array
 * @param CAPACITY required capacity of the array
 */
#define ARRAY_TRY_ENSURE_CAPACITY(P, CAPACITY) do {                            \
    if ((P) == NULL) {                                                         \
         if ((CAPACITY) > 0) (P) = CARR_array_alloc(sizeof((P)[0]), CAPACITY); \
    } else if (ARRAY_CAPACITY(P) < (CAPACITY)) {                               \
         (P) = CARR_array_realloc(ARRAY_T(P), sizeof((P)[0]), CAPACITY);       \
    }                                                                          \
} while(0)

/**
 * Ensure array capacity. Implicitly initializes when array is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P pointer to the first data element of the array
 * @param CAPACITY required capacity of the array
 */
#define ARRAY_ENSURE_CAPACITY(P, CAPACITY) do {                           \
    ARRAY_TRY_ENSURE_CAPACITY(P, CAPACITY);                               \
    if (ARRAY_CAPACITY(P) < (CAPACITY)) C_ARRAY_UTIL_ALLOCATION_FAILED(); \
} while(0)

/**
 * Shrink capacity of the array to its size.
 * On allocation failure, array is unchanged.
 * @param P pointer to the first data element of the array
 */
#define ARRAY_SHRINK_TO_FIT(P) do {                                          \
    if ((P) != NULL) {                                                       \
        (P) = CARR_array_realloc(ARRAY_T(P), sizeof((P)[0]), ARRAY_SIZE(P)); \
    }                                                                        \
} while(0)

#define ARRAY_RESIZE_IMPL(P, SIZE, ...) do {                   \
    if ((P) != NULL || (SIZE) > 0) {                           \
        ARRAY_ENSURE_CAPACITY(P, SIZE);                        \
        if ((P) != NULL && (ARRAY_T(P))->capacity >= (SIZE)) { \
            (ARRAY_T(P))->size = (SIZE);                       \
        } __VA_ARGS__                                          \
    }                                                          \
} while(0)

/**
 * Resize an array. Implicitly initializes when array is NULL.
 * On allocation failure, array is unchanged.
 * @param P pointer to the first data element of the array
 * @param SIZE required size of the array
 */
#define ARRAY_TRY_RESIZE(P, SIZE) ARRAY_RESIZE_IMPL(P, SIZE, )

/**
 * Resize an array. Implicitly initializes when array is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P pointer to the first data element of the array
 * @param SIZE required size of the array
 */
#define ARRAY_RESIZE(P, SIZE) ARRAY_RESIZE_IMPL(P, SIZE, else if ((SIZE) > 0) C_ARRAY_UTIL_ALLOCATION_FAILED();)

/**
 * Add element to the end of the array. Implicitly initializes when array is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P pointer to the first data element of the array
 */
#define ARRAY_PUSH_BACK(P, ...) do {                                                               \
    if ((P) == NULL) {                                                                             \
         (P) = CARR_array_alloc(sizeof((P)[0]), ARRAY_DEFAULT_CAPACITY);                           \
    } else if (ARRAY_SIZE(P) >= ARRAY_CAPACITY(P)) {                                               \
         (P) = CARR_array_realloc(ARRAY_T(P), sizeof((P)[0]), ARRAY_CAPACITY_GROW(ARRAY_SIZE(P))); \
    }                                                                                              \
    if (ARRAY_SIZE(P) >= ARRAY_CAPACITY(P)) C_ARRAY_UTIL_ALLOCATION_FAILED();                      \
    *((P) + ARRAY_SIZE(P)) = (__VA_ARGS__);                                                        \
    (ARRAY_T(P))->size++;                                                                          \
} while(0)

#define SARRAY_COUNT_OF(STATIC_ARRAY) (sizeof(STATIC_ARRAY)/sizeof((STATIC_ARRAY)[0]))

#define RING_BUFFER_T(P) ((CARR_ring_buffer_t *)((P) == NULL ? NULL : (char*)(P) - offsetof(CARR_ring_buffer_t, data)))

/**
 * @param P pointer to the first data element of the ring buffer
 * @return size of the ring buffer
 */
#define RING_BUFFER_SIZE(P) ((P) == NULL ? (size_t) 0 : \
    (RING_BUFFER_T(P)->capacity + RING_BUFFER_T(P)->tail - RING_BUFFER_T(P)->head) % RING_BUFFER_T(P)->capacity)

/**
 * @param P pointer to the first data element of the ring buffer
 * @return capacity of the ring buffer
 */
#define RING_BUFFER_CAPACITY(P) ((P) == NULL ? (size_t) 0 : RING_BUFFER_T(P)->capacity)

/**
 * Add element to the end of the ring buffer. Implicitly initializes when buffer is NULL.
 * On allocation failure, C_ARRAY_UTIL_ALLOCATION_FAILED is called.
 * @param P pointer to the first data element of the buffer
 */
#define RING_BUFFER_PUSH(P, ...) RING_BUFFER_PUSH_CUSTOM(P, (P)[tail] = (__VA_ARGS__);)
#define RING_BUFFER_PUSH_CUSTOM(P, ...) do {                                                                                 \
    size_t head, tail, new_tail;                                                                                             \
    if ((P) == NULL) {                                                                                                       \
        (P) = CARR_ring_buffer_realloc(NULL, sizeof((P)[0]), ARRAY_DEFAULT_CAPACITY);                                        \
        if ((P) == NULL) C_ARRAY_UTIL_ALLOCATION_FAILED();                                                                   \
        head = tail = 0;                                                                                                     \
        new_tail = 1;                                                                                                        \
    } else {                                                                                                                 \
        head = RING_BUFFER_T(P)->head;                                                                                       \
        tail = RING_BUFFER_T(P)->tail;                                                                                       \
        new_tail = (tail + 1) % RING_BUFFER_T(P)->capacity;                                                                  \
        if (new_tail == head) {                                                                                              \
            (P) = CARR_ring_buffer_realloc(RING_BUFFER_T(P), sizeof(P[0]), ARRAY_CAPACITY_GROW(RING_BUFFER_T(P)->capacity)); \
            if ((P) == NULL) C_ARRAY_UTIL_ALLOCATION_FAILED();                                                               \
            head = 0;                                                                                                        \
            tail = RING_BUFFER_T(P)->tail;                                                                                   \
            new_tail = RING_BUFFER_T(P)->tail + 1;                                                                           \
        }                                                                                                                    \
    }                                                                                                                        \
    __VA_ARGS__                                                                                                              \
    RING_BUFFER_T(P)->tail = new_tail;                                                                                       \
} while(0)

/**
 * Get pointer to the first element of the ring buffer.
 * @param P pointer to the first data element of the buffer
 */
#define RING_BUFFER_PEEK(P) ((P) == NULL || RING_BUFFER_T(P)->head == RING_BUFFER_T(P)->tail ? NULL : &(P)[RING_BUFFER_T(P)->head])

/**
 * Move beginning of the ring buffer forward (remove first element).
 * @param P pointer to the first data element of the buffer
 */
#define RING_BUFFER_POP(P) RING_BUFFER_T(P)->head = (RING_BUFFER_T(P)->head + 1) % RING_BUFFER_T(P)->capacity

/**
 * Deallocate the ring buffer
 * @param P pointer to the first data element of the buffer
 */
#define RING_BUFFER_FREE(P) free(RING_BUFFER_T(P))

#endif // CARRAYUTILS_H
