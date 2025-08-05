#include <memory.h>
#include "CArrayUtil.h"

#define MIN(a,b) (((a)<(b))?(a):(b))

void* CARR_array_alloc(size_t elem_size, size_t capacity) {
    CARR_array_t *pvec = malloc(elem_size * capacity + offsetof(CARR_array_t, data));
    if (pvec == NULL) {
        return NULL;
    }
    pvec->elem_size = elem_size;
    pvec->size = 0;
    pvec->capacity = capacity;
    return pvec->data;
}

void* CARR_array_realloc(CARR_array_t* vec, size_t new_capacity) {
    if (vec->capacity == new_capacity) {
        return vec->data;
    }
    CARR_array_t* new_vec =
            (CARR_array_t*)((char*)CARR_array_alloc(vec->elem_size, new_capacity) - offsetof(CARR_array_t, data));
    if (new_vec == NULL) {
        return NULL;
    }
    new_vec->capacity = new_capacity;
    new_vec->size = MIN(vec->size, new_capacity);
    new_vec->elem_size = vec->elem_size;
    memcpy(new_vec->data, vec->data, new_vec->size*new_vec->elem_size);
    free(vec);
    return new_vec->data;
}
