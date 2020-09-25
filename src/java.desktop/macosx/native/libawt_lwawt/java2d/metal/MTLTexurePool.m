#import "MTLTexturePool.h"
#import "Trace.h"

#define SCREEN_MEMORY_SIZE_4K (4096*2160*4) //~33,7 mb
#define MAX_POOL_MEMORY SCREEN_MEMORY_SIZE_4K/2
#define MAX_POOL_ITEM_LIFETIME_SEC 30

#define CELL_WIDTH_BITS 5 // ~ 32 pixel
#define CELL_HEIGHT_BITS 5 // ~ 32 pixel

@implementation MTLTexturePoolItem

@synthesize texture, isBusy, lastUsed, isMultiSample;

- (id) initWithTexture:(id<MTLTexture>)tex {
    self = [super init];
    if (self == nil) return self;
    self.texture = tex;
    isBusy = NO;
    return self;
}

- (void) dealloc {
    [lastUsed release];
    [texture release];
    [super dealloc];
}

@end

@implementation MTLPooledTextureHandle
{
    MTLRegion _rect;
    id<MTLTexture> _texture;
    MTLTexturePoolItem * _poolItem;
}
@synthesize texture = _texture, rect = _rect;

- (id) initWithPoolItem:(id<MTLTexture>)texture rect:(MTLRegion)rectangle poolItem:(MTLTexturePoolItem *)poolItem {
    self = [super init];
    if (self == nil) return self;

    self->_rect = rectangle;
    self->_texture = texture;
    self->_poolItem = poolItem;
    return self;
}

- (void) releaseTexture {
    self->_poolItem.isBusy = NO;
}
@end

@implementation MTLTexturePool {
    int _memoryTotalAllocated;

    void ** _cells;
    int _poolCellWidth;
    int _poolCellHeight;
}

@synthesize device;

- (id) initWithDevice:(id<MTLDevice>)dev {
    self = [super init];
    if (self == nil) return self;

    _memoryTotalAllocated = 0;
    _poolCellWidth = 10;
    _poolCellHeight = 10;
    const int cellsCount = _poolCellWidth * _poolCellHeight;
    _cells = (void **)malloc(cellsCount * sizeof(void*));
    memset(_cells, 0, cellsCount * sizeof(void*));
    self.device = dev;
    return self;
}

- (void) dealloc {
    for (int c = 0; c < _poolCellWidth * _poolCellHeight; ++c) {
        NSMutableArray * cell = _cells[c];
        if (cell != NULL) {
            [cell release];
        }
    }
    free(_cells);
    [super dealloc];
}

// NOTE: called from RQ-thread (on blit operations)
- (MTLPooledTextureHandle *) getTexture:(int)width height:(int)height format:(MTLPixelFormat)format {
    return [self getTexture:width height:height format:format isMultiSample:NO];
}

// NOTE: called from RQ-thread (on blit operations)
- (MTLPooledTextureHandle *) getTexture:(int)width height:(int)height format:(MTLPixelFormat)format
                          isMultiSample:(bool)isMultiSample {
        // 1. clean pool if necessary
        const int requestedPixels = width*height;
        const int requestedBytes = requestedPixels*4;
        if (_memoryTotalAllocated + requestedBytes > MAX_POOL_MEMORY) {
            [self cleanIfNecessary:0]; // release all free textures
        } else if (_memoryTotalAllocated + requestedBytes > MAX_POOL_MEMORY/2) {
            [self cleanIfNecessary:MAX_POOL_ITEM_LIFETIME_SEC]; // release only old free textures
        }

        // 2. find free item
        const int cellX0 = width    >> CELL_WIDTH_BITS;
        const int cellY0 = height   >> CELL_HEIGHT_BITS;
        const int cellX1 = cellX0 + 1;
        const int cellY1 = cellY0 + 1;
        if (cellX1 > _poolCellWidth || cellY1 > _poolCellHeight) {
            const int newCellWidth = cellX1 <= _poolCellWidth ? _poolCellWidth : cellX1;
            const int newCellHeight = cellY1 <= _poolCellHeight ? _poolCellHeight : cellY1;
            const int newCellsCount = newCellWidth*newCellHeight;
            J2dTraceLn2(J2D_TRACE_VERBOSE, "MTLTexturePool: resize: %d -> %d", _poolCellWidth * _poolCellHeight, newCellsCount);
            void ** newcells = malloc(newCellsCount*sizeof(void*));
            const int strideBytes = _poolCellWidth * sizeof(void*);
            for (int cy = 0; cy < _poolCellHeight; ++cy) {
                void ** dst = newcells + cy*newCellWidth;
                void ** src = _cells + cy * _poolCellWidth;
                memcpy(dst, src, strideBytes);
                if (newCellWidth > _poolCellWidth)
                    memset(dst + _poolCellWidth, 0, (newCellWidth - _poolCellWidth) * sizeof(void*));
            }
            if (newCellHeight > _poolCellHeight) {
                void ** dst = newcells + _poolCellHeight * newCellWidth;
                memset(dst, 0, (newCellHeight - _poolCellHeight) * newCellWidth * sizeof(void*));
            }
            free(_cells);
            _cells = newcells;
            _poolCellWidth = newCellWidth;
            _poolCellHeight = newCellHeight;
        }

        MTLTexturePoolItem * minDeltaTpi = nil;
        for (int cy = cellY0; cy < cellY1; ++cy) {
            for (int cx = cellX0; cx < cellX1; ++cx) {
                NSMutableArray * cell = _cells[cy * _poolCellWidth + cx];
                if (cell == NULL)
                    continue;

                const int count = [cell count];
                int minDeltaArea = -1;
                int minDeltaAreaIndex = -1;
                for (int c = 0; c < count; ++c) {
                    MTLTexturePoolItem *tpi = [cell objectAtIndex:c];
                    if (tpi == nil || tpi.isBusy || tpi.texture.pixelFormat != format
                        || tpi.isMultiSample != isMultiSample) { // TODO: use swizzle when formats are not equal
                        continue;
                    }
                    if (tpi.texture.width < width || tpi.texture.height < height) {
                        continue;
                    }
                    const int deltaArea = tpi.texture.width*tpi.texture.height - requestedPixels;
                    if (minDeltaArea < 0 || deltaArea < minDeltaArea) {
                        minDeltaAreaIndex = c;
                        minDeltaArea = deltaArea;
                        minDeltaTpi = tpi;
                        if (deltaArea == 0) {
                            // found exact match in current cell
                            break;
                        }
                    }
                }
                if (minDeltaTpi != nil) {
                    break;
                }
            }
            if (minDeltaTpi != nil) {
                break;
            }
        }

        if (minDeltaTpi == NULL) {
            MTLTextureDescriptor *textureDescriptor =
                    [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format
                                                                       width:(NSUInteger) width
                                                                      height:(NSUInteger) height
                                                                   mipmapped:NO];
            if (isMultiSample) {
                textureDescriptor.textureType = MTLTextureType2DMultisample;
                textureDescriptor.sampleCount = MTLAASampleCount;
                textureDescriptor.storageMode = MTLStorageModePrivate;
            }

            id <MTLTexture> tex = [[self.device newTextureWithDescriptor:textureDescriptor] autorelease];
            minDeltaTpi = [[[MTLTexturePoolItem alloc] initWithTexture:tex] autorelease];
            minDeltaTpi.isMultiSample = isMultiSample;
            NSMutableArray * cell = _cells[cellY0 * _poolCellWidth + cellX0];
            if (cell == NULL) {
                cell = [[NSMutableArray arrayWithCapacity:10] retain];
                _cells[cellY0 * _poolCellWidth + cellX0] = cell;
            }
            [cell addObject:minDeltaTpi];
            _memoryTotalAllocated += requestedBytes;
            J2dTraceLn5(J2D_TRACE_VERBOSE, "MTLTexturePool: created pool item: tex=%p, w=%d h=%d, pf=%d | total memory = %d Kb", tex, width, height, format, _memoryTotalAllocated/1024);
        }

        minDeltaTpi.isBusy = YES;
        minDeltaTpi.lastUsed = [NSDate date];
        return [[[MTLPooledTextureHandle alloc] initWithPoolItem:minDeltaTpi.texture
                                                            rect:MTLRegionMake2D(0, 0,
                                                                                 minDeltaTpi.texture.width,
                                                                                 minDeltaTpi.texture.height)
                                                        poolItem:minDeltaTpi] autorelease];
}

- (void) cleanIfNecessary:(int)lastUsedTimeThreshold {
    for (int cy = 0; cy < _poolCellHeight; ++cy) {
        for (int cx = 0; cx < _poolCellWidth; ++cx) {
            NSMutableArray * cell = _cells[cy * _poolCellWidth + cx];
            if (cell == NULL)
                continue;

            for (int c = 0; c < [cell count];) {
                MTLTexturePoolItem *tpi = [cell objectAtIndex:c];
                if (!tpi.isBusy) {
                    if (
                        lastUsedTimeThreshold <= 0
                        || (int)(-[tpi.lastUsed timeIntervalSinceNow]) > lastUsedTimeThreshold
                    ) {
#ifdef DEBUG
                        J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE, "MTLTexturePool: remove pool item: tex=%p, w=%d h=%d, elapsed=%d", tpi.texture, tpi.texture.width, tpi.texture.height, (int)(-[tpi.lastUsed timeIntervalSinceNow]));
#endif //DEBUG
                        _memoryTotalAllocated -= tpi.texture.width * tpi.texture.height * 4;
                        [cell removeObjectAtIndex:c];
                        continue;
                    }
                }
                ++c;
            }
        }
    }
}

@end
