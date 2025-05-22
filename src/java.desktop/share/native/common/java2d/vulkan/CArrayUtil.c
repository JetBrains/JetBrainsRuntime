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
