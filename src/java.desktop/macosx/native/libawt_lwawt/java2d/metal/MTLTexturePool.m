/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

#include "time.h"

#import "AccelTexturePool.h"
#import "MTLTexturePool.h"
#import "Trace.h"

#define USE_ACCEL_TEXTURE_POOL  0

#define TRACE_LOCK              0
#define TRACE_TEX               0


/* lock API */
ATexturePoolLockPrivPtr* MTLTexturePoolLock_initImpl(void) {
    NSLock* l = [[[NSLock alloc] init] autorelease];
    [l retain];
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePoolLock_initImpl: lock=%p", l);
    return l;
}

void MTLTexturePoolLock_DisposeImpl(ATexturePoolLockPrivPtr *lock) {
    NSLock* l = (NSLock*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePoolLock_DisposeImpl: lock=%p", l);
    [l release];
}

void MTLTexturePoolLock_lockImpl(ATexturePoolLockPrivPtr *lock) {
    NSLock* l = (NSLock*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePoolLock_lockImpl: lock=%p", l);
    [l lock];
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePoolLock_lockImpl: lock=%p - locked", l);
}

void MTLTexturePoolLock_unlockImpl(ATexturePoolLockPrivPtr *lock) {
    NSLock* l = (NSLock*)lock;
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePoolLock_unlockImpl: lock=%p", l);
    [l unlock];
    if (TRACE_LOCK) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePoolLock_unlockImpl: lock=%p - unlocked", l);
}


/* Texture allocate/free API */
static id<MTLTexture> MTLTexturePool_createTexture(id<MTLDevice> device,
                                                   int width,
                                                   int height,
                                                   long format)
{
    MTLTextureDescriptor *textureDescriptor =
            [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:(MTLPixelFormat)format
                                                               width:(NSUInteger) width
                                                              height:(NSUInteger) height
                                                           mipmapped:NO];
    // By default:
    // usage = MTLTextureUsageShaderRead
    // storage = MTLStorageModeManaged
    textureDescriptor.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;

    // Use auto-release => MTLTexturePoolItem.dealloc to free texture !
    id <MTLTexture> texture = (id <MTLTexture>) [[device newTextureWithDescriptor:textureDescriptor] autorelease];
    [texture retain];

    if (TRACE_TEX) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_createTexture: created texture: tex=%p, w=%d h=%d, pf=%d",
                                  texture, [texture width], [texture height], [texture pixelFormat]);
    return texture;
}

static int MTLTexturePool_bytesPerPixel(long format) {
    switch ((MTLPixelFormat)format) {
        case MTLPixelFormatBGRA8Unorm:
            return 4;
        case MTLPixelFormatA8Unorm:
            return 1;
        default:
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLTexturePool_bytesPerPixel: format=%d not supported (4 bytes by default)", format);
            return 4;
    }
}

static void MTLTexturePool_freeTexture(id<MTLDevice> device, id<MTLTexture> texture) {
    if (TRACE_TEX) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_freeTexture: free texture: tex=%p, w=%d h=%d, pf=%d",
                                 texture, [texture width], [texture height], [texture pixelFormat]);
    [texture release];
}

/*
 * Former but updated MTLTexturePool implementation
 */

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

// force young gc every 15 seconds (prune only not reused textures):
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

/* private definitions */
@class MTLPoolCell;

@interface MTLTexturePoolItem : NSObject
    @property (readwrite, assign) id<MTLTexture> texture;
    @property (readwrite, assign) MTLPoolCell* cell;
    @property (readwrite, assign) MTLTexturePoolItem* prev;
    @property (readwrite, retain) MTLTexturePoolItem* next;
    @property (readwrite) time_t lastUsed;
    @property (readwrite) int width;
    @property (readwrite) int height;
    @property (readwrite) MTLPixelFormat format;
    @property (readwrite) int reuseCount;
    @property (readwrite) bool isBusy;
@end

@interface MTLPoolCell : NSObject
    @property (readwrite, retain) MTLTexturePoolItem* available;
    @property (readwrite, assign) MTLTexturePoolItem* availableTail;
    @property (readwrite, retain) MTLTexturePoolItem* occupied;
@end

@implementation MTLTexturePoolItem

@synthesize texture, lastUsed, next, cell, width, height, format, reuseCount, isBusy;

- (id) initWithTexture:(id<MTLTexture>)tex
                  cell:(MTLPoolCell*)c
                 width:(int)w
                height:(int)h
                format:(MTLPixelFormat)f
{
    self = [super init];
    if (self == nil) return self;
    self.texture = tex;
    self.cell = c;
    self.next = nil;
    self.prev = nil;
    self.lastUsed = 0;
    self.width = w;
    self.height = h;
    self.format = f;
    self.reuseCount = 0;
    self.isBusy = NO;

    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLTexturePoolItem_initWithTexture: item = %p", self);
    return self;
}

- (void) dealloc {
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLTexturePoolItem_dealloc: item = %p - reuse: %4d", self, self.reuseCount);

    // use texture (native API) to release allocated texture:
    MTLTexturePool_freeTexture(nil, self.texture);
    [super dealloc];
}

- (void) releaseItem {
    if (!isBusy) {
        return;
    }
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLTexturePoolItem_releaseItem: item = %p", self);

    if (self.cell != nil) {
        [self.cell releaseCellItem:self];
    } else {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLTexturePoolItem_releaseItem: item = %p (detached)", self);
    }
}
@end


/* MTLPooledTextureHandle API */
@implementation MTLPooledTextureHandle
{
    id<MTLTexture>          _texture;
    MTLTexturePoolItem *    _poolItem;
    ATexturePoolHandle*     _texHandle;
    NSUInteger              _reqWidth;
    NSUInteger              _reqHeight;
}
@synthesize texture = _texture, reqWidth = _reqWidth, reqHeight = _reqHeight;

- (id) initWithPoolItem:(id<MTLTexture>)texture poolItem:(MTLTexturePoolItem *)poolItem reqWidth:(NSUInteger)w reqHeight:(NSUInteger)h {
    self = [super init];
    if (self == nil) return self;

    _texture = texture;
    _poolItem = poolItem;
    _texHandle = nil;

    _reqWidth = w;
    _reqHeight = h;

    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPooledTextureHandle_initWithPoolItem: handle = %p", self);
    return self;
}

- (id) initWithTextureHandle:(ATexturePoolHandle*)texHandle {
    self = [super init];
    if (self == nil) return self;

    _texture = ATexturePoolHandle_GetTexture(texHandle);
    _poolItem = nil;
    _texHandle = texHandle;

    _reqWidth = ATexturePoolHandle_GetRequestedWidth(texHandle);
    _reqHeight = ATexturePoolHandle_GetRequestedHeight(texHandle);

    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPooledTextureHandle_initWithTextureHandle: handle = %p", self);
    return self;
}

- (void) releaseTexture {
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPooledTextureHandle_ReleaseTexture: handle = %p", self);
    if (_texHandle != nil) {
        ATexturePoolHandle_ReleaseTexture(_texHandle);
    }
    if (_poolItem != nil) {
        [_poolItem releaseItem];
    }
}
@end


@implementation MTLPoolCell {
    MTLTexturePool* _pool;
    NSLock*         _lock;
}
@synthesize available, availableTail, occupied;

- (instancetype) init:(MTLTexturePool*)pool {
    self = [super init];
    if (self == nil) return self;

    self.available = nil;
    self.availableTail = nil;
    self.occupied = nil;
    _pool = pool;
    _lock = [[NSLock alloc] init];

    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_init: cell = %p", self);
    return self;
}

- (void) removeAllItems {
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_removeAllItems");

    MTLTexturePoolItem *cur = self.available;
    MTLTexturePoolItem *next = nil;
    while (cur != nil) {
        next = cur.next;
        self.available = cur;
        cur = next;
    }
    cur = self.occupied;
    next = nil;
    while (cur != nil) {
        next = cur.next;
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLPoolCell_removeAllItems: occupied item = %p (detached)", cur);
        // Do not dispose (may leak) until MTLTexturePoolItem_releaseItem() is called by handle:
        // mark item as detached:
        cur.cell = nil;
        cur = next;
        self.occupied = cur;
    }
    self.availableTail = nil;
}

- (void) removeAvailableItem:(MTLTexturePoolItem*)item {
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_removeAvailableItem: item = %p", item);
    [item retain];
    if (item.prev == nil) {
        self.available = item.next;
        if (item.next) {
            item.next.prev = nil;
            item.next = nil;
        } else {
            self.availableTail = item.prev;
        }
    } else {
        item.prev.next = item.next;
        if (item.next) {
            item.next.prev = item.prev;
            item.next = nil;
        } else {
            self.availableTail = item.prev;
        }
    }
    [item release];
}

- (void) dealloc {
    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_dealloc: cell = %p", self);
    [_lock lock];
    @try {
        [self removeAllItems];
    } @finally {
        [_lock unlock];
    }
    [_lock release];
    [super dealloc];
}

- (void) occupyItem:(MTLTexturePoolItem *)item {
    if (item.isBusy) {
        return;
    }
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_occupyItem: item = %p", item);
    [item retain];
    if (item.prev == nil) {
        self.available = item.next;
        if (item.next) {
            item.next.prev = nil;
        } else {
            self.availableTail = item.prev;
        }
    } else {
        item.prev.next = item.next;
        if (item.next) {
            item.next.prev = item.prev;
        } else {
            self.availableTail = item.prev;
        }
        item.prev = nil;
    }
    if (occupied) {
        occupied.prev = item;
    }
    item.next = occupied;
    self.occupied = item;
    item.isBusy = YES;
    [item release];
}

- (void) releaseCellItem:(MTLTexturePoolItem *)item {
    if (!item.isBusy) return;
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_releaseCellItem: item = %p", item);
    [_lock lock];
    @try {
        [item retain];
        if (item.prev == nil) {
            self.occupied = item.next;
            if (item.next) {
                item.next.prev = nil;
            }
        } else {
            item.prev.next = item.next;
            if (item.next) {
                item.next.prev = item.prev;
            }
            item.prev = nil;
        }
        if (self.available) {
            self.available.prev = item;
        } else {
            self.availableTail = item;
        }
        item.next = self.available;
        self.available = item;
        item.isBusy = NO;
        [item release];
    } @finally {
        [_lock unlock];
    }
}

- (void) addOccupiedItem:(MTLTexturePoolItem *)item {
    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_addOccupiedItem: item = %p", item);
    [_lock lock];
    @try {
        _pool.allocatedCount++;
        _pool.totalAllocatedCount++;

        if (self.occupied) {
            self.occupied.prev = item;
        }
        item.next = self.occupied;
        self.occupied = item;
        item.isBusy = YES;
    } @finally {
        [_lock unlock];
    }
}

- (void) cleanIfBefore:(time_t)lastUsedTimeToRemove {
    [_lock lock];
    @try {
        MTLTexturePoolItem *cur = availableTail;
        while (cur != nil) {
            MTLTexturePoolItem *prev = cur.prev;
            if ((cur.reuseCount == 0) || (lastUsedTimeToRemove <= 0) || (cur.lastUsed < lastUsedTimeToRemove)) {

                if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLPoolCell_cleanIfBefore: remove pool item: tex=%p, w=%d h=%d, elapsed=%d",
                                                 cur.texture, cur.width, cur.height,
                                                 time(NULL) - cur.lastUsed);

                const int requestedBytes = cur.width * cur.height * MTLTexturePool_bytesPerPixel(cur.format);
                // cur is nil after removeAvailableItem:
                [self removeAvailableItem:cur];
                _pool.allocatedCount--;
                _pool.memoryAllocated -= requestedBytes;
            } else {
                if (TRACE_MEM_API || TRACE_GC_ALIVE) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_cleanIfBefore: item = %p - ALIVE - reuse: %4d -> 0",
                                                                   cur, cur.reuseCount);
                // clear reuse count anyway:
                cur.reuseCount = 0;
            }
            cur = prev;
        }
    } @finally {
        [_lock unlock];
    }
}

- (MTLTexturePoolItem *) occupyCellItem:(int)width height:(int)height format:(MTLPixelFormat)format {
    int minDeltaArea = -1;
    const int requestedPixels = width*height;
    MTLTexturePoolItem *minDeltaTpi = nil;
    [_lock lock];
    @try {
        for (MTLTexturePoolItem *cur = available; cur != nil; cur = cur.next) {
            // TODO: use swizzle when formats are not equal:
            if (cur.format != format) {
                continue;
            }
            if (cur.width < width || cur.height < height) {
                continue;
            }
            const int deltaArea = (const int) (cur.width * cur.height - requestedPixels);
            if (minDeltaArea < 0 || deltaArea < minDeltaArea) {
                minDeltaArea = deltaArea;
                minDeltaTpi = cur;
                if (deltaArea == 0) {
                    // found exact match in current cell
                    break;
                }
            }
        }
        if (minDeltaTpi) {
            [self occupyItem:minDeltaTpi];
        }
    } @finally {
        [_lock unlock];
    }

    if (TRACE_USE_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLPoolCell_occupyCellItem: item = %p", minDeltaTpi);
    return minDeltaTpi;
}
@end


/* MTLTexturePool API */
@implementation MTLTexturePool {
    void **         _cells;
    int             _poolCellWidth;
    int             _poolCellHeight;
    uint64_t        _maxPoolMemory;
    uint64_t        _memoryAllocated;
    uint64_t        _memoryTotalAllocated;
    time_t          _lastYoungGC;
    time_t          _lastFullGC;
    time_t          _lastGC;
    ATexturePool*   _accelTexturePool;
    bool            _enableGC;
}

@synthesize device, allocatedCount, totalAllocatedCount,
            memoryAllocated = _memoryAllocated,
            totalMemoryAllocated = _memoryTotalAllocated;

- (void) autoTest:(MTLPixelFormat)format {
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_autoTest: step = %d", INIT_TEST_STEP);

    _enableGC = false;

    for (int w = 1; w <= INIT_TEST_MAX; w += INIT_TEST_STEP) {
        for (int h = 1; h <= INIT_TEST_MAX; h += INIT_TEST_STEP) {
            /* use auto-release pool to free memory as early as possible */
            @autoreleasepool {
                MTLPooledTextureHandle *texHandle = [self getTexture:w height:h format:format];
                id<MTLTexture> texture = texHandle.texture;
                if (texture == nil) {
                    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_autoTest: w= %d h= %d => texture is NULL !", w, h);
                } else {
                    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_autoTest: w=%d h=%d => tex=%p",
                                                     w, h, texture);
                }
                [texHandle releaseTexture];
            }
        }
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "MTLTexturePool_autoTest: before GC: total allocated memory = %lld Mb (total allocs: %d)",
                  _memoryTotalAllocated / UNIT_MB, self.totalAllocatedCount);

    _enableGC = true;

    [self cleanIfNecessary:FORCE_GC_INTERVAL_SEC];

    J2dRlsTraceLn(J2D_TRACE_INFO, "MTLTexturePool_autoTest:  after GC: total allocated memory = %lld Mb (total allocs: %d)",
                  _memoryTotalAllocated / UNIT_MB, self.totalAllocatedCount);
}

- (id) initWithDevice:(id<MTLDevice>)dev {
    // recommendedMaxWorkingSetSize typically greatly exceeds SCREEN_MEMORY_SIZE_5K constant.
    // It usually corresponds to the VRAM available to the graphics card
    uint64_t maxDeviceMemory = dev.recommendedMaxWorkingSetSize;

    self = [super init];
    if (self == nil) return self;

#if (USE_ACCEL_TEXTURE_POOL == 1)
    ATexturePoolLockWrapper *lockWrapper = ATexturePoolLockWrapper_init(&MTLTexturePoolLock_initImpl,
                                                                        &MTLTexturePoolLock_DisposeImpl,
                                                                        &MTLTexturePoolLock_lockImpl,
                                                                        &MTLTexturePoolLock_unlockImpl);

    _accelTexturePool = ATexturePool_initWithDevice(dev,
                                                    (jlong)maxDeviceMemory,
                                                    &MTLTexturePool_createTexture,
                                                    &MTLTexturePool_freeTexture,
                                                    &MTLTexturePool_bytesPerPixel,
                                                    lockWrapper,
                                                    MTLPixelFormatBGRA8Unorm);
#endif
    self.device = dev;

     // use (5K) 5120-by-2880 resolution:
    _poolCellWidth  = 5120 >> CELL_WIDTH_BITS;
    _poolCellHeight = 2880 >> CELL_HEIGHT_BITS;

    const int cellsCount = _poolCellWidth * _poolCellHeight;
    _cells = (void **)malloc(cellsCount * sizeof(void*));
    CHECK_NULL_LOG_RETURN(_cells, NULL, "MTLTexturePool_initWithDevice: could not allocate cells");
    memset(_cells, 0, cellsCount * sizeof(void*));

    _maxPoolMemory = maxDeviceMemory / 2;
    // Set maximum to handle at least 5K screen size
    if (_maxPoolMemory < SCREEN_MEMORY_SIZE_5K) {
        _maxPoolMemory = SCREEN_MEMORY_SIZE_5K;
    } else if (USE_MAX_GPU_DEVICE_MEM && (_maxPoolMemory > MAX_GPU_DEVICE_MEM)) {
        _maxPoolMemory = MAX_GPU_DEVICE_MEM;
    }

    self.allocatedCount = 0;
    self.totalAllocatedCount = 0;

    _memoryAllocated = 0;
    _memoryTotalAllocated = 0;

    _enableGC = true;
    _lastGC = time(NULL);
    _lastYoungGC = _lastGC;
    _lastFullGC  = _lastGC;

    self.cacheHits = 0;
    self.totalHits = 0;

    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLTexturePool_initWithDevice: pool = %p - maxPoolMemory = %lld", self, _maxPoolMemory);

    if (INIT_TEST) {
        static bool INIT_TEST_START = true;
        if (INIT_TEST_START) {
            INIT_TEST_START = false;
            [self autoTest:MTLPixelFormatBGRA8Unorm];
        }
    }
    return self;
}

- (void) dealloc {
#if (USE_ACCEL_TEXTURE_POOL == 1)
    ATexturePoolLockWrapper_Dispose(ATexturePool_getLockWrapper(_accelTexturePool));
    ATexturePool_Dispose(_accelTexturePool);
#endif

    if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_INFO, "MTLTexturePool_dealloc: pool = %p", self);

    for (int c = 0; c < _poolCellWidth * _poolCellHeight; ++c) {
        MTLPoolCell *cell = _cells[c];
        if (cell != NULL) {
            [cell release];
        }
    }
    free(_cells);
    [super dealloc];
}

- (void) cleanIfNecessary:(int)lastUsedTimeThreshold {
    time_t lastUsedTimeToRemove =
            lastUsedTimeThreshold > 0 ?
                time(NULL) - lastUsedTimeThreshold :
                lastUsedTimeThreshold;

    if (TRACE_MEM_API || TRACE_GC) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_cleanIfNecessary: before GC: allocated memory = %lld Kb (allocs: %d)",
                      _memoryAllocated / UNIT_KB, self.allocatedCount);
    }

    for (int cy = 0; cy < _poolCellHeight; ++cy) {
        for (int cx = 0; cx < _poolCellWidth; ++cx) {
            MTLPoolCell *cell = _cells[cy * _poolCellWidth + cx];
            if (cell != NULL) {
                [cell cleanIfBefore:lastUsedTimeToRemove];
            }
        }
    }
    if (TRACE_MEM_API || TRACE_GC) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_cleanIfNecessary:  after GC: allocated memory = %lld Kb (allocs: %d) - hits = %lld (%.3lf %% cached)",
                      _memoryAllocated / UNIT_KB, self.allocatedCount,
                      self.totalHits, (self.totalHits != 0) ? (100.0 * self.cacheHits) / self.totalHits : 0.0);
        // reset hits:
        self.cacheHits = 0;
        self.totalHits = 0;
    }
}

// NOTE: called from RQ-thread (on blit operations)
- (MTLPooledTextureHandle *) getTexture:(int)width height:(int)height format:(MTLPixelFormat)format {
#if (USE_ACCEL_TEXTURE_POOL == 1)
        ATexturePoolHandle* texHandle = ATexturePool_getTexture(_accelTexturePool, width, height, format);
        CHECK_NULL_RETURN(texHandle, NULL);
        return [[[MTLPooledTextureHandle alloc] initWithTextureHandle:texHandle] autorelease];
#else
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

            if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_getTexture: fixed tex size: (%d %d) => (%d %d)",
                                             reqWidth, reqHeight, width, height);
        }

        // 1. clean pool if necessary
        const int requestedPixels = width * height;
        const int requestedBytes = requestedPixels * MTLTexturePool_bytesPerPixel(format);
        const uint64_t neededMemoryAllocated = _memoryAllocated + requestedBytes;

        if (neededMemoryAllocated > _maxPoolMemory) {
            // release all free textures
            [self cleanIfNecessary:0];
        } else {
            time_t now = time(NULL);
            // ensure 1s at least:
            if ((now - _lastGC) > 0) {
                _lastGC = now;
                if (neededMemoryAllocated > _maxPoolMemory / 2) {
                    // release only old free textures
                    [self cleanIfNecessary:MAX_POOL_ITEM_LIFETIME_SEC];
                } else if (FORCE_GC && _enableGC) {
                    if ((now - _lastFullGC) > FORCE_GC_INTERVAL_SEC) {
                        _lastFullGC = now;
                        _lastYoungGC = now;
                        // release only old free textures since last full-gc
                        [self cleanIfNecessary:FORCE_GC_INTERVAL_SEC];
                    } else if ((now - _lastYoungGC) > YOUNG_GC_INTERVAL_SEC) {
                        _lastYoungGC = now;
                        // release only not reused and old textures
                        [self cleanIfNecessary:YOUNG_GC_LIFETIME_SEC];
                    }
                }
            }
        }

        // 2. find free item
        const int cellX1 = cellX0 + 1;
        const int cellY1 = cellY0 + 1;

        // Note: this code (test + resizing) is not thread-safe:
        if (cellX1 > _poolCellWidth || cellY1 > _poolCellHeight) {
            const int newCellWidth = cellX1 <= _poolCellWidth ? _poolCellWidth : cellX1;
            const int newCellHeight = cellY1 <= _poolCellHeight ? _poolCellHeight : cellY1;
            const int newCellsCount = newCellWidth * newCellHeight;

            if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_getTexture: resize: %d -> %d",
                                             _poolCellWidth * _poolCellHeight, newCellsCount);

            void **newcells = malloc(newCellsCount * sizeof(void*));
            CHECK_NULL_LOG_RETURN(newcells, NULL, "MTLTexturePool_getTexture: could not allocate newCells");

            const size_t strideBytes = _poolCellWidth * sizeof(void*);
            for (int cy = 0; cy < _poolCellHeight; ++cy) {
                void **dst = newcells + cy * newCellWidth;
                void **src = _cells + cy * _poolCellWidth;
                memcpy(dst, src, strideBytes);
                if (newCellWidth > _poolCellWidth)
                    memset(dst + _poolCellWidth, 0, (newCellWidth - _poolCellWidth) * sizeof(void*));
            }
            if (newCellHeight > _poolCellHeight) {
                void **dst = newcells + _poolCellHeight * newCellWidth;
                memset(dst, 0, (newCellHeight - _poolCellHeight) * newCellWidth * sizeof(void*));
            }
            free(_cells);
            _cells = newcells;
            _poolCellWidth = newCellWidth;
            _poolCellHeight = newCellHeight;
        }

        MTLTexturePoolItem *minDeltaTpi = nil;
        int minDeltaArea = -1;
        for (int cy = cellY0; cy < cellY1; ++cy) {
            for (int cx = cellX0; cx < cellX1; ++cx) {
                MTLPoolCell * cell = _cells[cy * _poolCellWidth + cx];
                if (cell != NULL) {
                    MTLTexturePoolItem* tpi = [cell occupyCellItem:width height:height format:format];
                    if (!tpi) {
                        continue;
                    }
                    const int deltaArea = (const int) (tpi.width * tpi.height - requestedPixels);
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
            if (minDeltaTpi != nil) {
                break;
            }
        }

        if (minDeltaTpi == NULL) {
            MTLPoolCell* cell = _cells[cellY0 * _poolCellWidth + cellX0];
            if (cell == NULL) {
                cell = [[MTLPoolCell alloc] init:self];
                _cells[cellY0 * _poolCellWidth + cellX0] = cell;
            }
            // use device to allocate NEW texture:
            id <MTLTexture> tex = MTLTexturePool_createTexture(device, width, height, format);

            minDeltaTpi = [[[MTLTexturePoolItem alloc] initWithTexture:tex cell:cell
                            width:width height:height format:format] autorelease];
            [cell addOccupiedItem: minDeltaTpi];

            _memoryAllocated += requestedBytes;
            _memoryTotalAllocated += requestedBytes;

            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_getTexture: created pool item: tex=%p, w=%d h=%d, pf=%d | allocated memory = %lld Kb (allocs: %d)",
                       minDeltaTpi.texture, width, height, format, _memoryAllocated / UNIT_KB, allocatedCount);
            if (TRACE_MEM_API) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_getTexture: created pool item: tex=%p, w=%d h=%d, pf=%d | allocated memory = %lld Kb (allocs: %d)",
                                             minDeltaTpi.texture, width, height, format, _memoryAllocated / UNIT_KB, allocatedCount);
        } else {
            self.cacheHits++;
            minDeltaTpi.reuseCount++;
            if (TRACE_REUSE) {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLTexturePool_getTexture: reused  pool item: tex=%p, w=%d h=%d, pf=%d - reuse=%d",
                              minDeltaTpi.texture, width, height, format, minDeltaTpi.reuseCount);
            }
        }
        self.totalHits++;
        minDeltaTpi.lastUsed = time(NULL);
        return [[[MTLPooledTextureHandle alloc] initWithPoolItem:minDeltaTpi.texture
                                                        poolItem:minDeltaTpi reqWidth:reqWidth reqHeight:reqHeight] autorelease];
#endif
}
@end
