#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

#include <jni.h>

static id <MTLTexture>
createMTLTextureFromNSData(id <MTLDevice> device, NSData *textureData, NSUInteger width, NSUInteger height) {
    if (!device || !textureData) {
        NSLog(@"Invalid device or texture data.");
        return nil;
    }

    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                          width:width
                                                                                         height:height
                                                                                      mipmapped:NO];
    descriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    descriptor.storageMode = MTLStorageModeShared;

    id <MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
    if (!texture) {
        NSLog(@"Failed to create Metal texture.");
        return nil;
    }

    NSUInteger bytesPerPixel = 4;
    NSUInteger bytesPerRow = bytesPerPixel * width;
    MTLRegion region = MTLRegionMake2D(0, 0, width, height);
    [texture replaceRegion:region mipmapLevel:0 withBytes:textureData.bytes bytesPerRow:bytesPerRow];

    return texture;
}

JNIEXPORT jlong JNICALL Java_SharedTexturesTest_createTexture
        (JNIEnv *env, jclass clazz, jbyteArray byteArray, jint width, jint height) {
    @autoreleasepool {
        id <MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) {
            NSLog(@"Metal is not supported on this device.");
            return 0;
        }

        jsize length = (*env)->GetArrayLength(env, byteArray);
        jbyte *bytes = (*env)->GetByteArrayElements(env, byteArray, NULL);
        NSMutableData *textureData = [NSMutableData dataWithBytes:bytes length:length];

        id <MTLTexture> texture = createMTLTextureFromNSData(device, textureData, width, height);
        (*env)->ReleaseByteArrayElements(env, byteArray, bytes, 0);
        return (jlong) texture;
    }
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_disposeTexture
        (JNIEnv *env, jclass clazz, jlong pTexture) {
    id <MTLTexture> texture = (__bridge id <MTLTexture>) (void *) pTexture;
    if (texture != nil) {
        [texture release];
    }
}
