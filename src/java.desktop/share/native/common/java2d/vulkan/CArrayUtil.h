#ifndef C_ARRAY_UTIL_H
#define C_ARRAY_UTIL_H

#include <stdio.h>
#include <malloc.h>
#include <assert.h>

#define ARRAY_CAPACITY_MULT 2
typedef struct {
    size_t elem_size;
    size_t size;
    size_t capacity;
    char data[];
} CARR_array_t;

void* CARR_array_alloc(size_t elem_size, size_t capacity);
void* CARR_array_realloc(CARR_array_t* vec, size_t new_capacity);

/**
 * Allocate array
 * @param T type of elements
 * @param CAPACITY capacity of the array
 */
#define ARRAY_ALLOC(T, CAPACITY) (T*)CARR_array_alloc(sizeof(T), CAPACITY)


#define ARRAY_T(P) (CARR_array_t *)((char*)P - offsetof(CARR_array_t, data))

/**
 * @param P pointer to the first data element of the array
 * @return size of the array
 */
#define ARRAY_SIZE(P) (ARRAY_T(P))->size

/**
 * @param P pointer to the first data element of the array
 * @return capacity of the array
 */
#define ARRAY_CAPACITY(P) (ARRAY_T(P))->capacity

/**
 * @param P pointer to the first data element of the array
 * @return last element in the array
 */
#define ARRAY_LAST(P) (P[(ARRAY_T(P))->size - 1])

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
    for (uint32_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(&(P[_i])); \
} while(0)

/**
 * Apply function to the array elements, passing pointer to an element as first parameter
 * @param P pointer to the first data element of the array
 * @param F function to apply
 */
#define ARRAY_APPLY_LEADING(P, F, ...) do {                                   \
    for (uint32_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(&(P[_i]), __VA_ARGS__); \
} while(0)

/**
 * Apply function to the array elements, passing pointer to an element as last parameter
 * @param P pointer to the first data element of the array
 * @param F function to apply
 */
#define ARRAY_APPLY_TRAILING(P, F, ...) do {                                  \
    for (uint32_t _i = 0; _i < ARRAY_SIZE(P); _i++) F(__VA_ARGS__, &(P[_i])); \
} while(0)

/**
 * Shrink capacity of the array to its size
 * @param PP pointer to the pointer to the first data element of the array
 */
#define ARRAY_SHRINK_TO_FIT(PP) do {                    \
    *PP = CARR_array_realloc(ARRAY_T(*PP), ARRAY_SIZE(*PP)); \
} while(0)

/**
 * Add element to the end of the array
 * @param PP pointer to the pointer to the first data element of the array
 */
#define ARRAY_PUSH_BACK(PP, D) do {                                           \
    if (ARRAY_SIZE(*PP) >= ARRAY_CAPACITY(*PP)) {                               \
         *PP = CARR_array_realloc(ARRAY_T(*PP), ARRAY_SIZE(*PP)*ARRAY_CAPACITY_MULT);\
    }                                                                       \
    *(*PP + ARRAY_SIZE(*PP)) = (D);                                           \
    ARRAY_SIZE(*PP)++;                                                        \
} while(0)

#define SARRAY_COUNT_OF(STATIC_ARRAY) (sizeof(STATIC_ARRAY)/sizeof(STATIC_ARRAY[0]))

#endif // CARRAYUTILS_H
