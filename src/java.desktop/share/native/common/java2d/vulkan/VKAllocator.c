/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

#include <assert.h>
#include "VKUtil.h"
#include "VKAllocator.h"
#include "VKEnv.h"

/**
 * Block size is a minimum allocation size.
 * Using block size of 256 bytes gives us 256*2^21 = 512MiB addressable space per shared page
 * (read further for explanations). Such block size is less than typical alignment,
 * so there is no point in decreasing block size further to decrease possible fragmentation.
 */
#define BLOCK_POWER 8U
#define BLOCK_SIZE (1ULL<<BLOCK_POWER)

/**
 * Starting page level for small allocations.
 * With block size of 256 bytes and page level of 12, smallest pages will be 1MiB in size.
 */
#define MIN_SHARED_PAGE_LEVEL 12

/**
 * Pair of memory blocks - "buddies".
 * We want to keep bookkeeping footprint low, so single BlockPair should fit into 64 bits.
 * This implies the following limits:
 * - Max number of bottom-level blocks in a page (flat)         = 2^21
 * - Max number of bottom-level block pairs in a page (flat)    = 2^20
 * - Max number of all block pair nodes in a page (binary tree) = 2^21-1
 */
typedef struct {
    uint64_t offset     : 20; // Memory offset in block pairs from the beginning of the page.
    uint64_t parent     : 21; // Parent BlockPair index starting from 1, or 0 if none.
    uint64_t nextFree   : 21; // Index of the next free BlockPair of the same level starting from 1, or 0 if none.
    uint64_t firstFree  :  1; // Whether first block is free.
    uint64_t secondFree :  1; // Whether second block is free.
} BlockPair;

/**
 * Memory handle passed outside of allocator, we also need it to fit into 64 bits.
 * Memory offset and BlockPair indices are both fixed to 21 bits (see BlockPair).
 * We use 5 bits for block level, as this gives us full range over available offsets [0, 2^21-1].
 * Special block level value of 31 means that its size covers the entire page, and may not be power of two.
 * Note, however, that 21 bits for offset is not enough to cover whole range of levels [0, 30],
 * therefore maximum size of shared pages will be bounded by available offset range (2^21 blocks).
 * This leaves us with 17 bits, which we use for page index, there is no any special reason for this,
 * we just use what's left. This naturally limits total possible number of allocated pages to 2^17.
 */
typedef union MemoryHandle {
    uint64_t value;
    struct {
        uint64_t page   : 17; // Page index.
        uint64_t offset : 21; // Memory offset in blocks from the beginning of the page.
        uint64_t level  :  5; // Block level = log2(size), or 31 if size is not power of two.
        uint64_t pair   : 21; // BlockPair index starting from 1, or 0 if none.
    };
} MemoryHandle;

// Define limits discussed earlier.
#define MAX_PAGES (1ULL << 17)
// These are hard constants and not meant to be adjusted:
#define MAX_BLOCK_LEVEL (21U)
#define MAX_SHARED_PAGE_SIZE ((1ULL << MAX_BLOCK_LEVEL) * BLOCK_SIZE)

typedef struct {
    ARRAY(BlockPair) blockPairs;
    void*            mappedData;
    uint32_t         freeLevelIndices[MAX_BLOCK_LEVEL+1]; // Indices start from 1
    uint32_t         freeBlockPairIndex;                  // Indices start from 1
    uint32_t         nextPageIndex;
    uint32_t         memoryType;
} SharedPageData;

typedef struct {
    VkDeviceMemory memory;
    union {
        VkDeviceSize    dedicatedSize;  // If this is a dedicated page.
        SharedPageData* sharedPageData; // If this is a shared page.
        uint32_t        nextFreePage;   // If this struct is unused.
    };
#ifdef DEBUG
    VkDeviceSize debugPageSize;
    uint32_t debugMemoryType;
#endif
} Page;

typedef struct {
    uint32_t sharedPagesIndex;
    uint32_t allocationLevelTracker; // Used to manage page growth. Each new page size = (allocationLevelTracker++) / 2
#ifdef DEBUG
    VkDeviceSize debugTotalPagesSize;
#endif
} Pool;

struct VKAllocator {
    VKDevice* device;
    VkPhysicalDeviceMemoryProperties memoryProperties;

    ARRAY(Page) pages;
    uint32_t    freePageIndex;
    Pool        pools[VK_MAX_MEMORY_TYPES];
};

#define NO_PAGE_INDEX (~0U)

VKMemoryRequirements VKAllocator_NoRequirements(VKAllocator* allocator) {
    return (VKMemoryRequirements) {
        .allocator = allocator,
        .dedicatedRequirements.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS,
        .requirements.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2,
        .requirements.memoryRequirements = {
                .size = 0,
                .alignment = 1,
                .memoryTypeBits = VK_NO_MEMORY_TYPE
        },
        .memoryType = VK_NO_MEMORY_TYPE
    };
}
VKMemoryRequirements VKAllocator_BufferRequirements(VKAllocator* allocator, VkBuffer buffer) {
    assert(allocator != NULL);
    VKMemoryRequirements r = VKAllocator_NoRequirements(allocator);
    r.requirements.pNext = &r.dedicatedRequirements;
    const VkBufferMemoryRequirementsInfo2 bufferRequirementsInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_REQUIREMENTS_INFO_2,
            .buffer = buffer
    };
    allocator->device->vkGetBufferMemoryRequirements2(allocator->device->handle, &bufferRequirementsInfo, &r.requirements);
    r.requirements.pNext = NULL;
    return r;
}
VKMemoryRequirements VKAllocator_ImageRequirements(VKAllocator* allocator, VkImage image) {
    assert(allocator != NULL);
    VKMemoryRequirements r = VKAllocator_NoRequirements(allocator);
    r.requirements.pNext = &r.dedicatedRequirements;
    const VkImageMemoryRequirementsInfo2 imageRequirementsInfo = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2,
            .image = image
    };
    allocator->device->vkGetImageMemoryRequirements2(allocator->device->handle, &imageRequirementsInfo, &r.requirements);
    r.requirements.pNext = NULL;
    return r;
}

void VKAllocator_PadToAlignment(VKMemoryRequirements* requirements) {
    assert(requirements != NULL);
    VkMemoryRequirements* t = &requirements->requirements.memoryRequirements;
    t->size = ((t->size + t->alignment - 1) / t->alignment) * t->alignment;
    requirements->dedicatedRequirements.requiresDedicatedAllocation = VK_FALSE;
    requirements->dedicatedRequirements.prefersDedicatedAllocation = VK_FALSE;
}

void VKAllocator_FindMemoryType(VKMemoryRequirements* requirements,
                                VkMemoryPropertyFlags requiredProperties, VkMemoryPropertyFlags allowedProperties) {
    if (requirements->memoryType != VK_NO_MEMORY_TYPE) return;
    // TODO also skip heaps with insufficient memory?
    allowedProperties |= requiredProperties;
    for (uint32_t i = 0; i < requirements->allocator->memoryProperties.memoryTypeCount; i++) {
        if ((requirements->requirements.memoryRequirements.memoryTypeBits & (1 << i)) == 0) continue;
        VkMemoryPropertyFlags flags = requirements->allocator->memoryProperties.memoryTypes[i].propertyFlags;
        if ((flags & requiredProperties) == requiredProperties && (flags & allowedProperties) == flags) {
            requirements->memoryType = i;
            return;
        }
    }
}

static uint32_t VKAllocator_AllocatePage(VKAllocator* alloc, uint32_t memoryType, VkDeviceSize size,
                                         VkImage dedicatedImage, VkBuffer dedicatedBuffer) {
    assert(alloc != NULL);
    assert(memoryType < VK_MAX_MEMORY_TYPES);

    uint32_t heapIndex = alloc->memoryProperties.memoryTypes[memoryType].heapIndex;
    VkDeviceSize heapSize = alloc->memoryProperties.memoryHeaps[heapIndex].size;
    if (size > heapSize) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKAllocator_AllocatePage: not enough memory in heap, heapIndex=%d, heapSize=%d, size=%d", heapIndex, heapSize, size);
        return NO_PAGE_INDEX;
    }

    // Allocate memory.
    VkBool32 dedicated = dedicatedImage != VK_NULL_HANDLE || dedicatedBuffer != VK_NULL_HANDLE;
    VKDevice* device = alloc->device;
    VkMemoryDedicatedAllocateInfo dedicatedAllocateInfo = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
            .image = dedicatedImage,
            .buffer = dedicatedBuffer
    };
    VkMemoryAllocateInfo allocateInfo = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .pNext = dedicated ? &dedicatedAllocateInfo : NULL,
            .allocationSize = size,
            .memoryTypeIndex = memoryType
    };
    VkDeviceMemory memory;
    VK_IF_ERROR(device->vkAllocateMemory(device->handle, &allocateInfo, NULL, &memory)) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKAllocator_AllocatePage: FAILED memoryType=%d, size=%d, dedicated=%d", memoryType, size, dedicated);
        return NO_PAGE_INDEX;
    }

    // Allocate page struct.
    uint32_t index;
    Page* page;
    if (alloc->freePageIndex != NO_PAGE_INDEX) {
        index = alloc->freePageIndex;
        page = &alloc->pages[index];
        alloc->freePageIndex = page->nextFreePage;
    } else {
        index = ARRAY_SIZE(alloc->pages);
        VK_RUNTIME_ASSERT(index < MAX_PAGES);
        ARRAY_PUSH_BACK(alloc->pages) = (Page) {};
        page = &ARRAY_LAST(alloc->pages);
    }
    assert(page->memory == VK_NULL_HANDLE);
    *page = (Page) { .memory = memory };

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKAllocator_AllocatePage: #%d memoryType=%d, size=%d, dedicated=%d", index, memoryType, size, dedicated);
#ifdef DEBUG
    page->debugPageSize = size;
    page->debugMemoryType = memoryType;
    alloc->pools[memoryType].debugTotalPagesSize += size;
    J2dTraceLn(J2D_TRACE_INFO, "VKAllocator_AllocatePage: memoryType=%d, debugTotalPagesSize=%d", memoryType, alloc->pools[memoryType].debugTotalPagesSize);
#endif
    return index;
}

static void VKAllocator_FreePage(VKAllocator* alloc, Page* page, uint32_t pageIndex) {
    assert(alloc != NULL);
    VKDevice* device = alloc->device;
    device->vkFreeMemory(device->handle, page->memory, NULL);
    page->memory = VK_NULL_HANDLE;
    page->nextFreePage = alloc->freePageIndex;
    alloc->freePageIndex = pageIndex;
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKAllocator_FreePage: #%d", pageIndex);
#ifdef DEBUG
    alloc->pools[page->debugMemoryType].debugTotalPagesSize -= page->debugPageSize;
    J2dTraceLn(J2D_TRACE_INFO, "VKAllocator_FreePage: memoryType=%d, debugTotalPagesSize=%d",
               page->debugMemoryType, alloc->pools[page->debugMemoryType].debugTotalPagesSize);
#endif
}

/**
 * Pop free block pair of the specified level from the free list.
 * Subdivides upper level blocks if there are no existing matching blocks.
 */
static uint32_t VKAllocator_PopFreeBlockPair(SharedPageData* data, uint32_t level) {
    assert(data != NULL && level <= MAX_BLOCK_LEVEL);
    uint32_t pairIndex = data->freeLevelIndices[level];
    if (pairIndex != 0) {
        // Pop existing free block pair.
        BlockPair pair = data->blockPairs[pairIndex-1];
        assert(pair.firstFree ^ pair.secondFree); // Only one must be free.
        data->freeLevelIndices[level] = pair.nextFree;
        return pairIndex;
    } else if (level < MAX_BLOCK_LEVEL && (pairIndex = VKAllocator_PopFreeBlockPair(data, level+1)) != 0) {
        // Allocate block pair struct.
        uint32_t parentIndex = pairIndex;
        BlockPair* pair;
        if (data->freeBlockPairIndex != 0) {
            pairIndex = data->freeBlockPairIndex;
            pair = &data->blockPairs[pairIndex-1];
            data->freeBlockPairIndex = pair->nextFree;
        } else {
            ARRAY_PUSH_BACK(data->blockPairs) = (BlockPair) {};
            pairIndex = ARRAY_SIZE(data->blockPairs);
            pair = &data->blockPairs[pairIndex-1];
        }
        // Subdivide parent block.
        BlockPair* parent = &data->blockPairs[parentIndex-1];
        assert(parent->firstFree || parent->secondFree);
        *pair = (BlockPair) {
                .offset     = parent->offset,
                .parent     = parentIndex,
                .nextFree   = 0,
                .firstFree  = 1,
                .secondFree = 1,
        };
        if (!parent->firstFree) {
            pair->offset |= 1ULL << level;
            parent->secondFree = 0;
        } else parent->firstFree = 0;
        data->freeLevelIndices[level] = pairIndex;
        return pairIndex;
    } else return 0;
}

/**
 * Push free block pair of the specified level to the free list.
 * Merges into upper level blocks if there are free buddies.
 * Returns VK_TRUE, if page was completely freed.
 */
static VkBool32 VKAllocator_PushFreeBlockPair(SharedPageData* data, BlockPair* pair, uint32_t pairIndex, uint32_t level) {
    assert(data != NULL && level <= MAX_BLOCK_LEVEL);
    assert(pair->firstFree || pair->secondFree);
    if (pair->firstFree && pair->secondFree) {
        // Merge.
        uint32_t parentIndex = pair->parent;
        assert(parentIndex != 0);
        BlockPair* parent = &data->blockPairs[parentIndex-1];
        if (pair->offset == parent->offset) {
            assert(!parent->firstFree);
            parent->firstFree = 1;
        } else {
            assert(!parent->secondFree);
            parent->secondFree = 1;
        }
        // Remove from free list.
        if (data->freeLevelIndices[level] == pairIndex) {
            data->freeLevelIndices[level] = pair->nextFree;
        } else {
            assert(data->freeLevelIndices[level] != 0);
            BlockPair* b = &data->blockPairs[data->freeLevelIndices[level]-1];
            for (;;) {
                if (b->nextFree == pairIndex) {
                    b->nextFree = pair->nextFree;
                    break;
                }
                assert(b->nextFree != 0);
                b = &data->blockPairs[b->nextFree-1];
            }
        }
        // Return block pair struct to pool.
        pair->nextFree = data->freeBlockPairIndex;
        data->freeBlockPairIndex = pairIndex;
        return VKAllocator_PushFreeBlockPair(data, parent, parentIndex, level+1);
    } else {
        pair->nextFree = data->freeLevelIndices[level];
        data->freeLevelIndices[level] = pairIndex;
        return pair->parent == 0;
    }
}

typedef struct {
    MemoryHandle handle;
    VkDeviceMemory memory;
} AllocationResult;

/**
 * Provided image or buffer may be used for dedicated allocation.
 */
static AllocationResult VKAllocator_AllocateForResource(VKMemoryRequirements* requirements, VkImage image, VkBuffer buffer) {
    assert(requirements != NULL && requirements->allocator != NULL);
    VKAllocator* alloc      = requirements->allocator;
    uint32_t     memoryType = requirements->memoryType;
    VkDeviceSize size       = requirements->requirements.memoryRequirements.size;
    VkDeviceSize alignment  = requirements->requirements.memoryRequirements.alignment;
    VkBool32     dedicated  = requirements->dedicatedRequirements.requiresDedicatedAllocation ||
                              requirements->dedicatedRequirements.prefersDedicatedAllocation;
    assert(memoryType != VK_NO_MEMORY_TYPE);
    assert(size > 0);
    assert(alignment > 0 && (alignment & (alignment - 1)) == 0); // Alignment must be power of 2.

    uint32_t level = size <= BLOCK_SIZE ? 0 : VKUtil_Log2(size - 1) + 1 - BLOCK_POWER;
    uint32_t blockSize = BLOCK_SIZE << level;
    // Adjust level to ensure proper alignment. Not very optimal, but this is a very rare case.
    while (blockSize % alignment != 0) { level++; blockSize <<= 1; }

    J2dRlsTraceLn(J2D_TRACE_VERBOSE2,
                  "VKAllocator_Allocate: level=%d, blockSize=%d, size=%d, alignment=%d, memoryType=%d, dedicated=%d",
                  level, blockSize, size, alignment, memoryType, dedicated);

    if (!dedicated && level <= MAX_BLOCK_LEVEL) {
        // Try to sub-allocate.
        AllocationResult result = { .handle.value = 0 };
        result.handle.level = level;
        Pool* pool = &alloc->pools[memoryType];
        uint32_t pageIndex = pool->sharedPagesIndex;
        // Try to find block in existing pages.
        Page* page;
        SharedPageData* data;
        uint32_t pairIndex;
        while (pageIndex != NO_PAGE_INDEX) {
            page = &alloc->pages[pageIndex];
            data = page->sharedPageData;
            pairIndex = VKAllocator_PopFreeBlockPair(data, level);
            if (pairIndex != 0) break;
            pageIndex = data->nextPageIndex;
        }
        // Allocate new page.
        if (pageIndex == NO_PAGE_INDEX) {
            uint32_t pageLevel = (pool->allocationLevelTracker++) / 2;
            if (pageLevel < level) pool->allocationLevelTracker = (pageLevel = level) * 2 + 1;
            else if (pageLevel > MAX_BLOCK_LEVEL) pool->allocationLevelTracker = (pageLevel = MAX_BLOCK_LEVEL) * 2 + 1;
            pageIndex = VKAllocator_AllocatePage(alloc, memoryType, BLOCK_SIZE << pageLevel, VK_NULL_HANDLE, VK_NULL_HANDLE);
            if (pageIndex == NO_PAGE_INDEX) return (AllocationResult) {{0}, VK_NULL_HANDLE};
            page = &alloc->pages[pageIndex];
            data = page->sharedPageData = (SharedPageData*) calloc(1, sizeof(SharedPageData));
            VK_RUNTIME_ASSERT(page->sharedPageData);
            data->memoryType = memoryType;
            ARRAY_PUSH_BACK(data->blockPairs) = (BlockPair) {
                .offset     = 0,
                .parent     = 0,
                .nextFree   = 0,
                .firstFree  = 1,
                .secondFree = 0,
            };
            data->freeLevelIndices[pageLevel] = 1;
            data->nextPageIndex = pool->sharedPagesIndex;
            pool->sharedPagesIndex = pageIndex;
            pairIndex = VKAllocator_PopFreeBlockPair(data, level);
            assert(pairIndex != 0);
        }
        // Take the block.
        BlockPair* pair = &data->blockPairs[pairIndex-1];
        result.handle.page = pageIndex;
        result.handle.pair = pairIndex;
        // No need to check alignment, all blocks are aligned on their size.
        if (pair->firstFree) {
            result.handle.offset = (pair->offset << 1U);
            pair->firstFree = 0;
        } else {
            result.handle.offset = (pair->offset << 1U) + (1ULL << level);
            pair->secondFree = 0;
        }
        result.memory = page->memory;
        return result;
    } else {
        // Dedicated allocation.
        uint32_t pageIndex = VKAllocator_AllocatePage(alloc, memoryType, size, image, buffer);
        if (pageIndex == NO_PAGE_INDEX) return (AllocationResult) {{0}, VK_NULL_HANDLE};
        Page* page = &alloc->pages[pageIndex];
        page->dedicatedSize = size;
        return (AllocationResult) {
                .handle = {
                        .page =   pageIndex,
                        .offset = 0,
                        .level =  31, // Special value, same meaning as VK_WHOLE_SIZE
                        .pair =   0
                },
                .memory = page->memory
        };
    }
}

VKMemory VKAllocator_Allocate(VKMemoryRequirements* requirements) {
    return (VKMemory) VKAllocator_AllocateForResource(requirements, VK_NULL_HANDLE, VK_NULL_HANDLE).handle.value;
}
VKMemory VKAllocator_AllocateForImage(VKMemoryRequirements* requirements, VkImage image) {
    AllocationResult result = VKAllocator_AllocateForResource(requirements, image, VK_NULL_HANDLE);
    if (result.handle.value == 0) return VK_NULL_HANDLE;
    assert(result.memory != VK_NULL_HANDLE);
    VKDevice* device = requirements->allocator->device;
    VK_IF_ERROR(device->vkBindImageMemory(device->handle, image, result.memory, result.handle.offset << BLOCK_POWER)) {
        VKAllocator_Free(requirements->allocator, (VKMemory) result.handle.value);
        return VK_NULL_HANDLE;
    }
    return (VKMemory) result.handle.value;
}
VKMemory VKAllocator_AllocateForBuffer(VKMemoryRequirements* requirements, VkBuffer buffer) {
    AllocationResult result = VKAllocator_AllocateForResource(requirements, VK_NULL_HANDLE, buffer);
    if (result.handle.value == 0) return VK_NULL_HANDLE;
    assert(result.memory != VK_NULL_HANDLE);
    VKDevice* device = requirements->allocator->device;
    VK_IF_ERROR(device->vkBindBufferMemory(device->handle, buffer, result.memory, result.handle.offset << BLOCK_POWER)) {
        VKAllocator_Free(requirements->allocator, (VKMemory) result.handle.value);
        return VK_NULL_HANDLE;
    }
    return (VKMemory) result.handle.value;
}

void VKAllocator_Free(VKAllocator* allocator, VKMemory memory) {
    assert(allocator != NULL);
    if (memory == VK_NULL_HANDLE) return;
    MemoryHandle handle = { .value = (uint64_t) memory };
    Page* page = &allocator->pages[handle.page];
    if (handle.pair != 0) {
        // Return block into shared page.
        SharedPageData* data = page->sharedPageData;
        BlockPair* pair = &data->blockPairs[handle.pair-1];
        if ((pair->offset << 1U) == handle.offset) pair->firstFree = 1;
        else pair->secondFree = 1;
        VkBool32 cleared = VKAllocator_PushFreeBlockPair(data, pair, handle.pair, handle.level);
        J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                      "VKAllocator_Free: shared, level=%d, blockSize=%d, memoryType=%d",
                      handle.level, BLOCK_SIZE << handle.level, data->memoryType);
        // If page is empty and not the last created, release it.
        if (cleared) {
            Pool* pool = &allocator->pools[data->memoryType];
            if (pool->sharedPagesIndex != handle.page) {
                assert(pool->sharedPagesIndex != NO_PAGE_INDEX);
                Page* p = &allocator->pages[pool->sharedPagesIndex];
                for (;;) {
                    if (p->sharedPageData->nextPageIndex == handle.page) {
                        p->sharedPageData->nextPageIndex = data->nextPageIndex;
                        break;
                    }
                    assert(p->sharedPageData->nextPageIndex != 0);
                    p = &allocator->pages[p->sharedPageData->nextPageIndex];
                }
                VKAllocator_FreePage(allocator, page, handle.page);
                free(data);
            }
        }
    } else {
        // Release dedicated allocation.
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKAllocator_Free: dedicated, level=%d, blockSize=%d", handle.level, BLOCK_SIZE << handle.level);
        VKAllocator_FreePage(allocator, page, handle.page);
    }
}

VkMappedMemoryRange VKAllocator_GetMemoryRange(VKAllocator* allocator, VKMemory memory) {
    assert(allocator != NULL && memory != VK_NULL_HANDLE);
    MemoryHandle handle = { .value = (uint64_t) memory };
    return (VkMappedMemoryRange) {
            .sType  = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            .memory = allocator->pages[handle.page].memory,
            .offset = handle.offset * BLOCK_SIZE,
            .size   = handle.level == 31 ? VK_WHOLE_SIZE : BLOCK_SIZE << handle.level
    };
}
void* VKAllocator_Map(VKAllocator* allocator, VKMemory memory) {
    assert(allocator != NULL && memory != VK_NULL_HANDLE);
    MemoryHandle handle = { .value = (uint64_t) memory };
    Page* page = &allocator->pages[handle.page];
    void *p;
    if (handle.pair != 0) {
        if (page->sharedPageData->mappedData == NULL) {
            VK_IF_ERROR(allocator->device->vkMapMemory(allocator->device->handle, page->memory,
                                                       0, VK_WHOLE_SIZE, 0, &p)) VK_UNHANDLED_ERROR();
            page->sharedPageData->mappedData = p;
        }
        p = (void*) (((uint8_t*) page->sharedPageData->mappedData) + (handle.offset * BLOCK_SIZE));
    } else {
        VK_IF_ERROR(allocator->device->vkMapMemory(allocator->device->handle, page->memory,
                                                   0, VK_WHOLE_SIZE, 0, &p)) VK_UNHANDLED_ERROR();
    }
    return p;
}
void VKAllocator_Unmap(VKAllocator* allocator, VKMemory memory) {
    assert(allocator != NULL && memory != VK_NULL_HANDLE);
    MemoryHandle handle = { .value = (uint64_t) memory };
    Page* page = &allocator->pages[handle.page];
    if (handle.pair == 0) allocator->device->vkUnmapMemory(allocator->device->handle, page->memory);
}
void VKAllocator_Flush(VKAllocator* allocator, VKMemory memory, VkDeviceSize offset, VkDeviceSize size) {
    VkMappedMemoryRange range = VKAllocator_GetMemoryRange(allocator, memory);
    assert((size == VK_WHOLE_SIZE && offset <= range.size) || offset + size <= range.size);
    range.offset += offset;
    range.size = size == VK_WHOLE_SIZE ? range.size - offset : size;
    VK_IF_ERROR(allocator->device->vkFlushMappedMemoryRanges(allocator->device->handle, 1, &range)) VK_UNHANDLED_ERROR();
}
void VKAllocator_Invalidate(VKAllocator* allocator, VKMemory memory, VkDeviceSize offset, VkDeviceSize size) {
    VkMappedMemoryRange range = VKAllocator_GetMemoryRange(allocator, memory);
    assert((size == VK_WHOLE_SIZE && offset <= range.size) || offset + size <= range.size);
    range.offset += offset;
    range.size = size == VK_WHOLE_SIZE ? range.size - offset : size;
    VK_IF_ERROR(allocator->device->vkInvalidateMappedMemoryRanges(allocator->device->handle, 1, &range)) VK_UNHANDLED_ERROR();
}

VKAllocator* VKAllocator_Create(VKDevice* device) {
    VKAllocator* allocator = calloc(1, sizeof(VKAllocator));
    allocator->device = device;
    allocator->freePageIndex = NO_PAGE_INDEX;
    for (uint32_t i = 0; i < VK_MAX_MEMORY_TYPES; i++) {
        allocator->pools[i] = (Pool) {
                .sharedPagesIndex = NO_PAGE_INDEX,
                .allocationLevelTracker = MIN_SHARED_PAGE_LEVEL * 2
        };
    }
    VKEnv_GetInstance()->vkGetPhysicalDeviceMemoryProperties(device->physicalDevice, &allocator->memoryProperties);

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKAllocator_Create: allocator=%p", allocator);
    return allocator;
}

void VKAllocator_Destroy(VKAllocator* allocator) {
    if (allocator == NULL) return;

    for (uint32_t i = 0; i < VK_MAX_MEMORY_TYPES; i++) {
        uint32_t pageIndex;
        while ((pageIndex = allocator->pools[i].sharedPagesIndex) != NO_PAGE_INDEX) {
            Page* page = &allocator->pages[pageIndex];
            SharedPageData* data = page->sharedPageData;
#ifdef DEBUG
            // Check that all shared allocations were freed.
            for (uint32_t j = MAX_BLOCK_LEVEL;; j--) {
                if (data->freeLevelIndices[j] != 0) {
                    BlockPair* pair = &data->blockPairs[data->freeLevelIndices[j]-1];
                    if (pair->parent == 0) break;
                    else VK_FATAL_ERROR("VKAllocator_Destroy: leaked memory in shared page");
                }
                assert(j > 0);
            }
#endif
            ARRAY_FREE(data->blockPairs);
            allocator->pools[i].sharedPagesIndex = data->nextPageIndex;
            free(data);
            VKAllocator_FreePage(allocator, page, pageIndex);
        }
#ifdef DEBUG
        // Check that all dedicated allocations were freed.
        if (allocator->pools[i].debugTotalPagesSize > 0) VK_FATAL_ERROR("VKAllocator_Destroy: leaked memory in dedicated page");
#endif
    }
    ARRAY_FREE(allocator->pages);

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKAllocator_Destroy(%p)", allocator);
    free(allocator);
}
