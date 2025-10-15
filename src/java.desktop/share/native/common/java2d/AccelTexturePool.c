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

#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "AccelTexturePool.h"
#include "jni.h"
#include "Trace.h"


#define USE_MAX_GPU_DEVICE_MEM      1
#define MAX_GPU_DEVICE_MEM          (512 * UNIT_MB)
#define SCREEN_MEMORY_SIZE_5K       (5120 * 4096 * 4) // ~ 84 mb

#define MAX_POOL_ITEM_LIFETIME_SEC  30

 // 32 pixel
#define CELL_WIDTH_BITS             5
#define CELL_HEIGHT_BITS            5

#define CELL_WIDTH_MASK             ((1 << CELL_WIDTH_BITS)  - 1)
#define CELL_HEIGHT_MASK            ((1 << CELL_HEIGHT_BITS) - 1)

#define USE_CEIL_SIZE               1

#define FORCE_GC                    1
// force gc (prune old textures):
#define FORCE_GC_INTERVAL_SEC       (MAX_POOL_ITEM_LIFETIME_SEC * 10)

// force young gc every 5 seconds (prune only not reused textures):
#define YOUNG_GC_INTERVAL_SEC       15
#define YOUNG_GC_LIFETIME_SEC       (FORCE_GC_INTERVAL_SEC * 2)

#define TRACE_GC                    1
#define TRACE_GC_ALIVE              0

#define TRACE_MEM_API               0
#define TRACE_USE_API               0
#define TRACE_REUSE                 0

#define INIT_TEST                   0
#define INIT_TEST_STEP              1
#define INIT_TEST_MAX               1024

#define LOCK_WRAPPER(cell)          ((cell)->pool->lockWrapper)
#define LOCK_WRAPPER_LOCK(cell)     (LOCK_WRAPPER(cell)->lockFunc((cell)->lock))
#define LOCK_WRAPPER_UNLOCK(cell)   (LOCK_WRAPPER(cell)->unlockFunc((cell)->lock))


/* Private definitions */
struct ATexturePoolLockWrapper_ {
    ATexturePoolLock_init       *initFunc;
    ATexturePoolLock_dispose    *disposeFunc;
    ATexturePoolLock_lock       *lockFunc;
    ATexturePoolLock_unlock     *unlockFunc;
};

struct ATexturePoolItem_ {
    ATexturePool_freeTexture    *freeTextureFunc;
    ADevicePrivPtr              *device;
    ATexturePrivPtr             *texture;
    ATexturePoolCell            *cell;
    ATexturePoolItem            *prev;
    ATexturePoolItem            *next;
    time_t                      lastUsed;
    jint                        width;
    jint                        height;
    jlong                       format;
    jint                        reuseCount;
    jboolean                    isBusy;
};

struct ATexturePoolCell_ {
    ATexturePool                *pool;
    ATexturePoolLockPrivPtr     *lock;
    ATexturePoolItem            *available;
    ATexturePoolItem            *availableTail;
    ATexturePoolItem            *occupied;
};

static void ATexturePoolCell_releaseItem(ATexturePoolCell *cell, ATexturePoolItem *item);

struct ATexturePoolHandle_ {
    ATexturePrivPtr             *texture;
    ATexturePoolItem            *_poolItem;
    jint                        reqWidth;
    jint                        reqHeight;
};

// NOTE: owns all texture objects
struct ATexturePool_ {
    ATexturePool_createTexture  *createTextureFunc;
    ATexturePool_freeTexture    *freeTextureFunc;
    ATexturePool_bytesPerPixel  *bytesPerPixelFunc;
    ATexturePoolLockWrapper     *lockWrapper;
    ADevicePrivPtr              *device;
    ATexturePoolCell            **_cells;
    jint                        poolCellWidth;
    jint                        poolCellHeight;
    jlong                       maxPoolMemory;
    jlong                       memoryAllocated;
    jlong                       totalMemoryAllocated;
    jlong                       allocatedCount;
    jlong                       totalAllocatedCount;
    jlong                       cacheHits;
    jlong                       totalHits;
    time_t                      lastGC;
    time_t                      lastYoungGC;
    time_t                      lastFullGC;
    jboolean                    enableGC;
};


/* ATexturePoolLockWrapper API */
ATexturePoolLockWrapper* ATexturePoolLockWrapper_init(ATexturePoolLock_init     *initFunc,
                                                      ATexturePoolLock_dispose  *disposeFunc,
                                                      ATexturePoolLock_lock     *lockFunc,
                                                      ATexturePoolLock_unlock   *unlockFunc)
{
    CHECK_NULL_LOG_RETURN(initFunc, NULL, "ATexturePoolLockWrapper_init: initFunc function is null !");
    CHECK_NULL_LOG_RETURN(disposeFunc, NULL, "ATexturePoolLockWrapper_init: disposeFunc function is null !");
    CHECK_NULL_LOG_RETURN(lockFunc, NULL, "ATexturePoolLockWrapper_init: lockFunc function is null !");
    CHECK_NULL_LOG_RETURN(unlockFunc, NULL, "ATexturePoolLockWrapper_init: unlockFunc function is null !");

    ATexturePoolLockWrapper *lockWrapper = (ATexturePoolLockWrapper*)malloc(sizeof(ATexturePoolLockWrapper));
    CHECK_NULL_LOG_RETURN(lockWrapper, NULL, "ATexturePoolLockWrapper_init: could not allocate ATexturePoolLockWrapper");

    lockWrapper->initFunc    = initFunc;
    lockWrapper->disposeFunc = disposeFunc;
    lockWrapper->lockFunc    = lockFunc;
    lockWrapper->unlockFunc  = unlockFunc;

    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolLockWrapper_init: lockWrapper = %p", lockWrapper);
    return lockWrapper;
}

void ATexturePoolLockWrapper_Dispose(ATexturePoolLockWrapper *lockWrapper) {
    CHECK_NULL(lockWrapper);
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolLockWrapper_Dispose: lockWrapper = %p", lockWrapper);

    free(lockWrapper);
}


/* ATexturePoolItem API */
static ATexturePoolItem* ATexturePoolItem_initWithTexture(ATexturePool_freeTexture *freeTextureFunc,
                                                          ADevicePrivPtr *device,
                                                          ATexturePrivPtr *texture,
                                                          ATexturePoolCell *cell,
                                                          jint width,
                                                          jint height,
                                                          jlong format)
{
    CHECK_NULL_LOG_RETURN(freeTextureFunc, NULL, "ATexturePoolItem_initWithTexture: freeTextureFunc function is null !");
    CHECK_NULL_RETURN(texture, NULL);
    CHECK_NULL_RETURN(cell, NULL);

    ATexturePoolItem *item = (ATexturePoolItem*)malloc(sizeof(ATexturePoolItem));
    CHECK_NULL_LOG_RETURN(item, NULL, "ATexturePoolItem_initWithTexture: could not allocate ATexturePoolItem");

    item->freeTextureFunc = freeTextureFunc;
    item->device      = device;
    item->texture     = texture;
    item->cell        = cell;
    item->prev        = NULL;
    item->next        = NULL;
    item->lastUsed    = 0;
    item->width       = width;
    item->height      = height;
    item->format      = format;
    item->reuseCount  = 0;
    item->isBusy      = JNI_FALSE;

    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolItem_initWithTexture: item = %p", item);
    return item;
}

static void ATexturePoolItem_Dispose(ATexturePoolItem *item) {
    CHECK_NULL(item);
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolItem_Dispose: item = %p - reuse: %4d", item, item->reuseCount);

    // use texture (native API) to release allocated texture:
    item->freeTextureFunc(item->device, item->texture);
    free(item);
}

/* Callback from metal pipeline => multi-thread (cell lock) */
static void ATexturePoolItem_ReleaseItem(ATexturePoolItem *item) {
    CHECK_NULL(item);
    if (!item->isBusy) {
        return;
    }
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolItem_ReleaseItem: item = %p", item);

    if IS_NOT_NULL(item->cell) {
        ATexturePoolCell_releaseItem(item->cell, item);
    } else {
        J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolItem_ReleaseItem: item = %p (detached)", item);
        // item marked as detached:
        ATexturePoolItem_Dispose(item);
    }
}


/* ATexturePoolCell API */
static ATexturePoolCell* ATexturePoolCell_init(ATexturePool *pool) {
    CHECK_NULL_RETURN(pool, NULL);

    ATexturePoolCell *cell = (ATexturePoolCell*)malloc(sizeof(ATexturePoolCell));
    CHECK_NULL_LOG_RETURN(cell, NULL, "ATexturePoolCell_init: could not allocate ATexturePoolCell");

    cell->pool = pool;

    ATexturePoolLockPrivPtr* lock = LOCK_WRAPPER(cell)->initFunc();
    CHECK_NULL_LOG_RETURN(lock, NULL, "ATexturePoolCell_init: could not allocate ATexturePoolLockPrivPtr");

    cell->lock          = lock;
    cell->available     = NULL;
    cell->availableTail = NULL;
    cell->occupied      = NULL;

    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_init: cell = %p", cell);
    return cell;
}

static void ATexturePoolCell_removeAllItems(ATexturePoolCell *cell) {
    CHECK_NULL(cell);
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_removeAllItems");

    ATexturePoolItem *cur = cell->available;
    ATexturePoolItem *next = NULL;
    while IS_NOT_NULL(cur) {
        next = cur->next;
        ATexturePoolItem_Dispose(cur);
        cur = next;
    }
    cell->available = NULL;

    cur = cell->occupied;
    next = NULL;
    while IS_NOT_NULL(cur) {
        next = cur->next;
        J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_removeAllItems: occupied item = %p", cur);
        // Do not dispose (may leak) until ATexturePoolItem_Release() is called by handle:
        // mark item as detached:
        cur->cell = NULL;
        cur = next;
    }
    cell->occupied = NULL;
    cell->availableTail = NULL;
}

static void ATexturePoolCell_removeAvailableItem(ATexturePoolCell *cell, ATexturePoolItem *item) {
    CHECK_NULL(cell);
    CHECK_NULL(item);
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_removeAvailableItem: item = %p", item);

    if IS_NULL(item->prev) {
        cell->available = item->next;
        if IS_NOT_NULL(item->next) {
            item->next->prev = NULL;
            item->next = NULL;
        } else {
            cell->availableTail = item->prev;
        }
    } else {
        item->prev->next = item->next;
        if IS_NOT_NULL(item->next) {
            item->next->prev = item->prev;
            item->next = NULL;
        } else {
            cell->availableTail = item->prev;
        }
    }
    ATexturePoolItem_Dispose(item);
}

static void ATexturePoolCell_Dispose(ATexturePoolCell *cell) {
    CHECK_NULL(cell);
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_Dispose: cell = %p", cell);

    LOCK_WRAPPER_LOCK(cell);
    {
        ATexturePoolCell_removeAllItems(cell);
    }
    LOCK_WRAPPER_UNLOCK(cell);

    LOCK_WRAPPER(cell)->disposeFunc(cell->lock);
    free(cell);
}

/* RQ thread from metal pipeline (cell locked) */
static void ATexturePoolCell_occupyItem(ATexturePoolCell *cell, ATexturePoolItem *item) {
    CHECK_NULL(cell);
    CHECK_NULL(item);
    if (item->isBusy) {
        return;
    }
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_occupyItem: item = %p", item);

    if IS_NULL(item->prev) {
        cell->available = item->next;
        if IS_NOT_NULL(item->next) {
            item->next->prev = NULL;
        } else {
            cell->availableTail = item->prev;
        }
    } else {
        item->prev->next = item->next;
        if IS_NOT_NULL(item->next) {
            item->next->prev = item->prev;
        } else {
            cell->availableTail = item->prev;
        }
        item->prev = NULL;
    }
    if (cell->occupied) {
        cell->occupied->prev = item;
    }
    item->next = cell->occupied;
    cell->occupied = item;
    item->isBusy = JNI_TRUE;
}

/* Callback from native java2D pipeline => multi-thread (cell lock) */
static void ATexturePoolCell_releaseItem(ATexturePoolCell *cell, ATexturePoolItem *item) {
    CHECK_NULL(cell);
    CHECK_NULL(item);
    if (!item->isBusy) {
        return;
    }
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_releaseItem: item = %p", item);

    LOCK_WRAPPER_LOCK(cell);
    {
        if IS_NULL(item->prev) {
            cell->occupied = item->next;
            if IS_NOT_NULL(item->next) {
                item->next->prev = NULL;
            }
        } else {
            item->prev->next = item->next;
            if IS_NOT_NULL(item->next) {
                item->next->prev = item->prev;
            }
            item->prev = NULL;
        }
        if IS_NOT_NULL(cell->available) {
            cell->available->prev = item;
        } else {
            cell->availableTail = item;
        }
        item->next = cell->available;
        cell->available = item;
        item->isBusy = JNI_FALSE;
    }
    LOCK_WRAPPER_UNLOCK(cell);
}

static void ATexturePoolCell_addOccupiedItem(ATexturePoolCell *cell, ATexturePoolItem *item) {
    CHECK_NULL(cell);
    CHECK_NULL(item);
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_addOccupiedItem: item = %p", item);

    LOCK_WRAPPER_LOCK(cell);
    {
        cell->pool->allocatedCount++;
        cell->pool->totalAllocatedCount++;

        if IS_NOT_NULL(cell->occupied) {
            cell->occupied->prev = item;
        }
        item->next = cell->occupied;
        cell->occupied = item;
        item->isBusy = JNI_TRUE;
    }
    LOCK_WRAPPER_UNLOCK(cell);
}

static void ATexturePoolCell_cleanIfBefore(ATexturePoolCell *cell, time_t lastUsedTimeToRemove) {
    CHECK_NULL(cell);
    LOCK_WRAPPER_LOCK(cell);
    {
        ATexturePoolItem *cur = cell->availableTail;
        while IS_NOT_NULL(cur) {
            ATexturePoolItem *prev = cur->prev;
            if ((cur->reuseCount == 0) || (lastUsedTimeToRemove <= 0) || (cur->lastUsed < lastUsedTimeToRemove)) {

                if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePoolCell_cleanIfBefore: remove pool item: tex=%p, w=%d h=%d, elapsed=%d",
                                                 cur->texture, cur->width, cur->height,
                                                 time(NULL) - cur->lastUsed);

                const int requestedBytes = cur->width * cur->height * cell->pool->bytesPerPixelFunc(cur->format);
                // cur is NULL after removeAvailableItem:
                ATexturePoolCell_removeAvailableItem(cell, cur);
                cell->pool->allocatedCount--;
                cell->pool->memoryAllocated -= requestedBytes;
            } else {
                if (TRACE_MEM_API || TRACE_GC_ALIVE) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_cleanIfBefore: item = %p - ALIVE - reuse: %4d -> 0",
                                                                   cur, cur->reuseCount);
                // clear reuse count anyway:
                cur->reuseCount = 0;
            }
            cur = prev;
        }
    }
    LOCK_WRAPPER_UNLOCK(cell);
}

/* RQ thread from metal pipeline <=> multi-thread callbacks (cell lock) */
static ATexturePoolItem* ATexturePoolCell_occupyCellItem(ATexturePoolCell *cell,
                                                         jint width,
                                                         jint height,
                                                         jlong format)
{
    CHECK_NULL_RETURN(cell, NULL);
    int minDeltaArea = -1;
    const int requestedPixels = width * height;
    ATexturePoolItem *minDeltaTpi = NULL;
    LOCK_WRAPPER_LOCK(cell);
    {
        for (ATexturePoolItem *cur = cell->available; IS_NOT_NULL(cur); cur = cur->next) {
            // TODO: use swizzle when formats are not equal
            if (cur->format != format) {
                continue;
            }
            if (cur->width < width || cur->height < height) {
                continue;
            }
            const int deltaArea = (const int) (cur->width * cur->height - requestedPixels);
            if ((minDeltaArea < 0) || (deltaArea < minDeltaArea)) {
                minDeltaArea = deltaArea;
                minDeltaTpi = cur;
                if (deltaArea == 0) {
                    // found exact match in current cell
                    break;
                }
            }
        }
        if IS_NOT_NULL(minDeltaTpi) {
            ATexturePoolCell_occupyItem(cell, minDeltaTpi);
        }
    }
    LOCK_WRAPPER_UNLOCK(cell);

    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolCell_occupyCellItem: item = %p", minDeltaTpi);
    return minDeltaTpi;
}


/* ATexturePoolHandle API */
static ATexturePoolHandle* ATexturePoolHandle_initWithPoolItem(ATexturePoolItem *item, jint reqWidth, jint reqHeight) {
    CHECK_NULL_RETURN(item, NULL);
    ATexturePoolHandle *handle = (ATexturePoolHandle*)malloc(sizeof(ATexturePoolHandle));
    CHECK_NULL_LOG_RETURN(handle, NULL, "ATexturePoolHandle_initWithPoolItem: could not allocate ATexturePoolHandle");

    handle->texture  = item->texture;
    handle->_poolItem = item;

    handle->reqWidth = reqWidth;
    handle->reqHeight = reqHeight;

    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolHandle_initWithPoolItem: handle = %p", handle);
    return handle;
}

/* Callback from metal pipeline => multi-thread (cell lock) */
void ATexturePoolHandle_ReleaseTexture(ATexturePoolHandle *handle) {
    CHECK_NULL(handle);
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolHandle_ReleaseTexture: handle = %p", handle);

    ATexturePoolItem_ReleaseItem(handle->_poolItem);
    free(handle);
}

ATexturePrivPtr* ATexturePoolHandle_GetTexture(ATexturePoolHandle *handle) {
    CHECK_NULL_RETURN(handle, NULL);
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolHandle_GetTexture: handle = %p", handle);
    return handle->texture;
}

jint ATexturePoolHandle_GetRequestedWidth(ATexturePoolHandle *handle) {
   CHECK_NULL_RETURN(handle, 0);
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolHandle_GetRequestedWidth: handle = %p", handle);
    return handle->reqWidth;
 }

jint ATexturePoolHandle_GetRequestedHeight(ATexturePoolHandle *handle) {
   CHECK_NULL_RETURN(handle, 0);
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolHandle_GetRequestedHeight: handle = %p", handle);
    return handle->reqHeight;
 }

jint ATexturePoolHandle_GetActualWidth(ATexturePoolHandle *handle) {
    CHECK_NULL_RETURN(handle, 0);
    CHECK_NULL_RETURN(handle->_poolItem, 0);
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolHandle_GetActualWidth: handle = %p", handle);
    return handle->_poolItem->width;
}

jint ATexturePoolHandle_GetActualHeight(ATexturePoolHandle *handle) {
    CHECK_NULL_RETURN(handle, 0);
    CHECK_NULL_RETURN(handle->_poolItem, 0);
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePoolHandle_GetActualHeight: handle = %p", handle);
    return handle->_poolItem->height;
}

/* ATexturePool API */
static void ATexturePool_cleanIfNecessary(ATexturePool *pool, int lastUsedTimeThreshold);

static void ATexturePool_autoTest(ATexturePool *pool, jlong format) {
    CHECK_NULL(pool);
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_autoTest: step = %d", INIT_TEST_STEP);

    pool->enableGC = JNI_FALSE;

    for (int w = 1; w <= INIT_TEST_MAX; w += INIT_TEST_STEP) {
        for (int h = 1; h <= INIT_TEST_MAX; h += INIT_TEST_STEP) {
            /* use auto-release pool to free memory as early as possible */

            ATexturePoolHandle *texHandle = ATexturePool_getTexture(pool, w, h, format);
            ATexturePrivPtr *texture = ATexturePoolHandle_GetTexture(texHandle);

            if IS_NULL(texture) {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_autoTest: w= %d h= %d => texture is NULL !", w, h);
            } else {
                if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_autoTest: w=%d h=%d => tex=%p",
                                                 w, h, texture);
            }
            ATexturePoolHandle_ReleaseTexture(texHandle);
        }
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePool_autoTest: before GC: total allocated memory = %lld Mb (total allocs: %d)",
                  pool->totalMemoryAllocated / UNIT_MB, pool->totalAllocatedCount);

    pool->enableGC = JNI_TRUE;

    ATexturePool_cleanIfNecessary(pool, FORCE_GC_INTERVAL_SEC);

    J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePool_autoTest:  after GC: total allocated memory = %lld Mb (total allocs: %d)",
                  pool->totalMemoryAllocated / UNIT_MB, pool->totalAllocatedCount);
}

ATexturePool* ATexturePool_initWithDevice(ADevicePrivPtr *device,
                                          jlong maxDeviceMemory,
                                          ATexturePool_createTexture *createTextureFunc,
                                          ATexturePool_freeTexture   *freeTextureFunc,
                                          ATexturePool_bytesPerPixel *bytesPerPixelFunc,
                                          ATexturePoolLockWrapper    *lockWrapper,
                                          jlong                      autoTestFormat)
{
    CHECK_NULL_LOG_RETURN(device, NULL, "ATexturePool_initWithDevice: device is null !");
    CHECK_NULL_LOG_RETURN(createTextureFunc, NULL, "ATexturePool_initWithDevice: createTextureFunc function is null !");
    CHECK_NULL_LOG_RETURN(freeTextureFunc, NULL, "ATexturePool_initWithDevice: freeTextureFunc function is null !");
    CHECK_NULL_LOG_RETURN(bytesPerPixelFunc, NULL, "ATexturePool_initWithDevice: bytesPerPixelFunc function is null !");
    CHECK_NULL_LOG_RETURN(lockWrapper, NULL, "ATexturePool_initWithDevice: lockWrapper is null !");

    ATexturePool *pool = (ATexturePool*)malloc(sizeof(ATexturePool));
    CHECK_NULL_LOG_RETURN(pool, NULL, "ATexturePool_initWithDevice: could not allocate ATexturePool");

    pool->createTextureFunc = createTextureFunc;
    pool->freeTextureFunc   = freeTextureFunc;
    pool->bytesPerPixelFunc = bytesPerPixelFunc;
    pool->lockWrapper       = lockWrapper;
    pool->device            = device;

     // use (5K) 5120-by-2880 resolution:
    pool->poolCellWidth  = 5120 >> CELL_WIDTH_BITS;
    pool->poolCellHeight = 2880 >> CELL_HEIGHT_BITS;

    const int cellsCount = pool->poolCellWidth * pool->poolCellHeight;
    pool->_cells = (ATexturePoolCell**)malloc(cellsCount * sizeof(void*));
    CHECK_NULL_LOG_RETURN(pool->_cells, NULL, "ATexturePool_initWithDevice: could not allocate cells");
    memset(pool->_cells, 0, cellsCount * sizeof(void*));

    pool->maxPoolMemory = maxDeviceMemory / 2;
    // Set maximum to handle at least 5K screen size
    if (pool->maxPoolMemory < SCREEN_MEMORY_SIZE_5K) {
        pool->maxPoolMemory = SCREEN_MEMORY_SIZE_5K;
    } else if (USE_MAX_GPU_DEVICE_MEM && (pool->maxPoolMemory > MAX_GPU_DEVICE_MEM)) {
        pool->maxPoolMemory = MAX_GPU_DEVICE_MEM;
    }

    pool->allocatedCount = 0L;
    pool->totalAllocatedCount = 0L;

    pool->memoryAllocated = 0L;
    pool->totalMemoryAllocated = 0L;

    pool->enableGC = JNI_TRUE;
    pool->lastGC = time(NULL);
    pool->lastYoungGC = pool->lastGC;
    pool->lastFullGC  = pool->lastGC;

    pool->cacheHits = 0L;
    pool->totalHits = 0L;

    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePool_initWithDevice: pool = %p", pool);

    if (INIT_TEST) {
        static jboolean INIT_TEST_START = JNI_TRUE;
        if (INIT_TEST_START) {
            INIT_TEST_START = JNI_FALSE;
            ATexturePool_autoTest(pool, autoTestFormat);
        }
    }
    return pool;
}

void ATexturePool_Dispose(ATexturePool *pool) {
    CHECK_NULL(pool);
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePool_Dispose: pool = %p", pool);

    const int cellsCount = pool->poolCellWidth * pool->poolCellHeight;
    for (int c = 0; c < cellsCount; ++c) {
        ATexturePoolCell *cell = pool->_cells[c];
        if IS_NOT_NULL(cell) {
            ATexturePoolCell_Dispose(cell);
        }
    }
    free(pool->_cells);
    free(pool);
}

ATexturePoolLockWrapper* ATexturePool_getLockWrapper(ATexturePool *pool) {
    CHECK_NULL_RETURN(pool, NULL);
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "ATexturePool_getLockWrapper: pool = %p", pool);
    return pool->lockWrapper;
}

static void ATexturePool_cleanIfNecessary(ATexturePool *pool, int lastUsedTimeThreshold) {
    CHECK_NULL(pool);
    time_t lastUsedTimeToRemove =
            lastUsedTimeThreshold > 0 ?
                time(NULL) - lastUsedTimeThreshold :
                lastUsedTimeThreshold;

    if (TRACE_MEM_API || TRACE_GC) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_cleanIfNecessary: before GC: allocated memory = %lld Kb (allocs: %d)",
                      pool->memoryAllocated / UNIT_KB, pool->allocatedCount);
    }

    for (int cy = 0; cy < pool->poolCellHeight; ++cy) {
        for (int cx = 0; cx < pool->poolCellWidth; ++cx) {
            ATexturePoolCell *cell = pool->_cells[cy * pool->poolCellWidth + cx];
            if IS_NOT_NULL(cell) {
                ATexturePoolCell_cleanIfBefore(cell, lastUsedTimeToRemove);
            }
        }
    }
    if (TRACE_MEM_API || TRACE_GC) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_cleanIfNecessary:  after GC: allocated memory = %lld Kb (allocs: %d) - hits = %lld (%.3lf %% cached)",
                      pool->memoryAllocated / UNIT_KB, pool->allocatedCount,
                      pool->totalHits, (pool->totalHits != 0L) ? (100.0 * pool->cacheHits) / pool->totalHits : 0.0);
        // reset hits:
        pool->cacheHits = 0L;
        pool->totalHits = 0L;
    }
}

ATexturePoolHandle* ATexturePool_getTexture(ATexturePool* pool,
                                            jint width,
                                            jint height,
                                            jlong format)
{
    CHECK_NULL_RETURN(pool, NULL);

    const int reqWidth  = width;
    const int reqHeight = height;

    int cellX0 = width    >> CELL_WIDTH_BITS;
    int cellY0 = height   >> CELL_HEIGHT_BITS;

    if (USE_CEIL_SIZE) {
        // use upper cell size to maximize cache hits:
        const int remX0 = width & CELL_WIDTH_MASK;
        const int remY0 = height & CELL_HEIGHT_MASK;

        if (remX0 != 0) {
            cellX0++;
        }
        if (remY0 != 0) {
            cellY0++;
        }
        // adjust width / height to cell upper boundaries:
        width = (cellX0) << CELL_WIDTH_BITS;
        height = (cellY0) << CELL_HEIGHT_BITS;

        if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_getTexture: fixed tex size: (%d %d) => (%d %d)",
                                         reqWidth, reqHeight, width, height);
    }

    // 1. clean pool if necessary
    const int requestedPixels = width * height;
    const int requestedBytes = requestedPixels * pool->bytesPerPixelFunc(format);
    const jlong neededMemoryAllocated = pool->memoryAllocated + requestedBytes;

    if (neededMemoryAllocated > pool->maxPoolMemory) {
        // release all free textures
        ATexturePool_cleanIfNecessary(pool, 0);
    } else {
        time_t now = time(NULL);
        // ensure 1s at least:
        if ((now - pool->lastGC) > 0) {
            pool->lastGC = now;
            if (neededMemoryAllocated > pool->maxPoolMemory / 2) {
                // release only old free textures
                ATexturePool_cleanIfNecessary(pool, MAX_POOL_ITEM_LIFETIME_SEC);
            } else if (FORCE_GC && pool->enableGC) {
                if ((now - pool->lastFullGC) > FORCE_GC_INTERVAL_SEC) {
                    pool->lastFullGC = now;
                    pool->lastYoungGC = now;
                    // release only old free textures since last full-gc
                    ATexturePool_cleanIfNecessary(pool, FORCE_GC_INTERVAL_SEC);
                } else if ((now - pool->lastYoungGC) > YOUNG_GC_INTERVAL_SEC) {
                    pool->lastYoungGC = now;
                    // release only not reused and old textures
                    ATexturePool_cleanIfNecessary(pool, YOUNG_GC_LIFETIME_SEC);
                }
            }
        }
    }

    // 2. find free item
    const int cellX1 = cellX0 + 1;
    const int cellY1 = cellY0 + 1;

    // Note: this code (test + resizing) is not thread-safe:
    if (cellX1 > pool->poolCellWidth || cellY1 > pool->poolCellHeight) {
        const int newCellWidth = cellX1 <= pool->poolCellWidth ? pool->poolCellWidth : cellX1;
        const int newCellHeight = cellY1 <= pool->poolCellHeight ? pool->poolCellHeight : cellY1;
        const int newCellsCount = newCellWidth*newCellHeight;

        if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_getTexture: resize: %d -> %d",
                                         pool->poolCellWidth * pool->poolCellHeight, newCellsCount);

        ATexturePoolCell **newCells = (ATexturePoolCell **)malloc(newCellsCount * sizeof(void*));
        CHECK_NULL_LOG_RETURN(newCells, NULL, "ATexturePool_getTexture: could not allocate newCells");

        const size_t strideBytes = pool->poolCellWidth * sizeof(void*);
        for (int cy = 0; cy < pool->poolCellHeight; ++cy) {
            ATexturePoolCell **dst = newCells + cy * newCellWidth;
            ATexturePoolCell **src = pool->_cells + cy * pool->poolCellWidth;
            memcpy(dst, src, strideBytes);
            if (newCellWidth > pool->poolCellWidth)
                memset(dst + pool->poolCellWidth, 0, (newCellWidth - pool->poolCellWidth) * sizeof(void*));
        }
        if (newCellHeight > pool->poolCellHeight) {
            ATexturePoolCell **dst = newCells + pool->poolCellHeight * newCellWidth;
            memset(dst, 0, (newCellHeight - pool->poolCellHeight) * newCellWidth * sizeof(void*));
        }
        free(pool->_cells);
        pool->_cells = newCells;
        pool->poolCellWidth = newCellWidth;
        pool->poolCellHeight = newCellHeight;
    }

    ATexturePoolItem *minDeltaTpi = NULL;
    int minDeltaArea = -1;
    for (int cy = cellY0; cy < cellY1; ++cy) {
        for (int cx = cellX0; cx < cellX1; ++cx) {
            ATexturePoolCell* cell = pool->_cells[cy * pool->poolCellWidth + cx];
            if IS_NOT_NULL(cell) {
                ATexturePoolItem *tpi = ATexturePoolCell_occupyCellItem(cell, width, height, format);
                if IS_NULL(tpi) {
                    continue;
                }
                const int deltaArea = (const int) (tpi->width * tpi->height - requestedPixels);
                if (minDeltaArea < 0 || deltaArea < minDeltaArea) {
                    minDeltaArea = deltaArea;
                    minDeltaTpi = tpi;
                    if (deltaArea == 0) {
                        // found exact match in current cell
                        break;
                    }
                }
            }
        }
        if IS_NOT_NULL(minDeltaTpi) {
            break;
        }
    }

    if IS_NULL(minDeltaTpi) {
        ATexturePoolCell* cell = pool->_cells[cellY0 * pool->poolCellWidth + cellX0];
        if IS_NULL(cell) {
            cell = ATexturePoolCell_init(pool);
            CHECK_NULL_RETURN(cell, NULL);
            pool->_cells[cellY0 * pool->poolCellWidth + cellX0] = cell;
        }
        // use device to allocate NEW texture:
        ATexturePrivPtr* tex = pool->createTextureFunc(pool->device, width, height, format);
        CHECK_NULL_LOG_RETURN(tex, NULL, "ATexturePool_getTexture: createTextureFunc failed to allocate texture !");

        minDeltaTpi = ATexturePoolItem_initWithTexture(pool->freeTextureFunc, pool->device, tex, cell,
                                                       width, height, format);
        ATexturePoolCell_addOccupiedItem(cell, minDeltaTpi);

        pool->memoryAllocated += requestedBytes;
        pool->totalMemoryAllocated += requestedBytes;

        if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_getTexture: created pool item: tex=%p, w=%d h=%d, pf=%d | allocated memory = %lld Kb (allocs: %d)",
                                         minDeltaTpi->texture, width, height, format, pool->memoryAllocated / UNIT_KB, pool->allocatedCount);
    } else {
        pool->cacheHits++;
        minDeltaTpi->reuseCount++;
        if (TRACE_REUSE) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "ATexturePool_getTexture: reused  pool item: tex=%p, w=%d h=%d, pf=%d - reuse: %4d",
                          minDeltaTpi->texture, width, height, format, minDeltaTpi->reuseCount);
        }
    }
    pool->totalHits++;
    minDeltaTpi->lastUsed = time(NULL);
    return ATexturePoolHandle_initWithPoolItem(minDeltaTpi, reqWidth, reqHeight);
}
