#include <memory.h>
#include "CArrayUtil.h"

#define MIN(a,b) (((a)<(b))?(a):(b))

void* CARR_array_alloc(size_t elem_size, size_t capacity) {
    CARR_array_t *pvec = malloc(elem_size * capacity + offsetof(CARR_array_t, data));
    if (pvec == NULL) {
        return NULL;
    }
    pvec->size = 0;
    pvec->capacity = capacity;
    return pvec->data;
}

void* CARR_array_realloc(CARR_array_t* vec, size_t elem_size, size_t new_capacity) {
    if (vec->capacity == new_capacity) {
        return vec->data;
    }
    CARR_array_t* new_vec =
            (CARR_array_t*)((char*)CARR_array_alloc(elem_size, new_capacity) - offsetof(CARR_array_t, data));
    if (new_vec == NULL) {
        return vec == NULL ? NULL : vec->data;
    }
    new_vec->capacity = new_capacity;
    new_vec->size = MIN(vec->size, new_capacity);
    memcpy(new_vec->data, vec->data, new_vec->size*elem_size);
    free(vec);
    return new_vec->data;
}

void* CARR_ring_buffer_realloc(CARR_ring_buffer_t* buf, size_t elem_size, size_t new_capacity) {
    if (buf != NULL && buf->capacity == new_capacity) {
        return buf->data;
    }
    CARR_ring_buffer_t* new_buf =
            (CARR_ring_buffer_t*) malloc(elem_size * new_capacity + offsetof(CARR_ring_buffer_t, data));
    if (new_buf == NULL) {
        return NULL;
    }
    new_buf->head = new_buf->tail = 0;
    new_buf->capacity = new_capacity;
    if (buf != NULL) {
        if (buf->tail > buf->head) {
            new_buf->tail = buf->tail - buf->head;
            memcpy(new_buf->data, buf->data + buf->head*elem_size, new_buf->tail*elem_size);
        } else if (buf->tail < buf->head) {
            new_buf->tail = buf->capacity + buf->tail - buf->head;
            memcpy(new_buf->data, buf->data + buf->head*elem_size, (buf->capacity-buf->head)*elem_size);
            memcpy(new_buf->data + (new_buf->tail-buf->tail)*elem_size, buf->data, buf->tail*elem_size);
        }
        free(buf);
    }
    return new_buf->data;
}
