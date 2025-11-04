/*
 * Copyright (c) 2012, 2025, Oracle and/or its affiliates. All rights reserved.
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

#import "LWCToolkit.h"
#import "ThreadUtilities.h"
#include "GeomUtilities.h"
#include "JNIUtilities.h"

/**
 * Some default values for invalid CoreGraphics display ID.
 */
#define DEFAULT_DEVICE_WIDTH 1024
#define DEFAULT_DEVICE_HEIGHT 768
#define DEFAULT_DEVICE_DPI 72

#define TRACE_DISPLAY_CHANGE_CONF 0

static NSInteger architecture = -1;
/*
 * Convert the mode string to the more convenient bits per pixel value
 */
static int getBPPFromModeString(CFStringRef mode)
{
    if ((CFStringCompare(mode, CFSTR(kIO30BitDirectPixels), kCFCompareCaseInsensitive) == kCFCompareEqualTo)) {
        // This is a strange mode, where we using 10 bits per RGB component and pack it into 32 bits
        // Java is not ready to work with this mode but we have to specify it as supported
        return 30;
    }
    else if (CFStringCompare(mode, CFSTR(IO32BitDirectPixels), kCFCompareCaseInsensitive) == kCFCompareEqualTo) {
        return 32;
    }
    else if (CFStringCompare(mode, CFSTR(IO16BitDirectPixels), kCFCompareCaseInsensitive) == kCFCompareEqualTo) {
        return 16;
    }
    else if (CFStringCompare(mode, CFSTR(IO8BitIndexedPixels), kCFCompareCaseInsensitive) == kCFCompareEqualTo) {
        return 8;
    }
    return 0;
}

void dumpDisplayInfo(jint displayID)
{
    // Returns a Boolean value indicating whether a display is active.
    jint displayIsActive = CGDisplayIsActive(displayID);

    // Returns a Boolean value indicating whether a display is always in a mirroring set.
    jint displayIsalwaysInMirrorSet = CGDisplayIsAlwaysInMirrorSet(displayID);

    // Returns a Boolean value indicating whether a display is sleeping (and is therefore not drawable).
    jint displayIsAsleep = CGDisplayIsAsleep(displayID);

    // Returns a Boolean value indicating whether a display is built-in, such as the internal display in portable systems.
    jint displayIsBuiltin = CGDisplayIsBuiltin(displayID);

    // Returns a Boolean value indicating whether a display is in a mirroring set.
    jint displayIsInMirrorSet = CGDisplayIsInMirrorSet(displayID);

    // Returns a Boolean value indicating whether a display is in a hardware mirroring set.
    jint displayIsInHWMirrorSet = CGDisplayIsInHWMirrorSet(displayID);

    // Returns a Boolean value indicating whether a display is the main display.
    jint displayIsMain = CGDisplayIsMain(displayID);

    // Returns a Boolean value indicating whether a display is connected or online.
    jint displayIsOnline = CGDisplayIsOnline(displayID);

    // Returns a Boolean value indicating whether a display is running in a stereo graphics mode.
    jint displayIsStereo = CGDisplayIsStereo(displayID);

    // For a secondary display in a mirroring set, returns the primary display.
    CGDirectDisplayID displayMirrorsDisplay = CGDisplayMirrorsDisplay(displayID);

    // Returns the primary display in a hardware mirroring set.
    CGDirectDisplayID displayPrimaryDisplay = CGDisplayPrimaryDisplay(displayID);

    // Returns the width and height of a display in millimeters.
    CGSize size = CGDisplayScreenSize(displayID);

    NSLog(@"CGDisplay[%d]{\n"
           "displayIsActive=%d\n"
           "displayIsalwaysInMirrorSet=%d\n"
           "displayIsAsleep=%d\n"
           "displayIsBuiltin=%d\n"
           "displayIsInMirrorSet=%d\n"
           "displayIsInHWMirrorSet=%d\n"
           "displayIsMain=%d\n"
           "displayIsOnline=%d\n"
           "displayIsStereo=%d\n"
           "displayMirrorsDisplay=%d\n"
           "displayPrimaryDisplay=%d\n"
           "displayScreenSizey=[%.1lf %.1lf]\n",
           displayID,
           displayIsActive,
           displayIsalwaysInMirrorSet,
           displayIsAsleep,
           displayIsBuiltin,
           displayIsInMirrorSet,
           displayIsInHWMirrorSet,
           displayIsMain,
           displayIsOnline,
           displayIsStereo,
           displayMirrorsDisplay,
           displayPrimaryDisplay,
           size.width, size.height
    );

    // CGDisplayCopyDisplayMode can return NULL if displayID is invalid
    CGDisplayModeRef mode = CGDisplayCopyDisplayMode(displayID);
    if (mode != NULL) {
        // Getting Information About a Display Mode
        jint h = -1, w = -1, bpp = -1;
        jdouble refreshRate = 0.0;

        // Returns the width of the specified display mode.
        w = CGDisplayModeGetWidth(mode);

        // Returns the height of the specified display mode.
        h = CGDisplayModeGetHeight(mode);

        // Returns the pixel encoding of the specified display mode.
        // Deprecated
        CFStringRef currentBPP = CGDisplayModeCopyPixelEncoding(mode);
        bpp = getBPPFromModeString(currentBPP);
        CFRelease(currentBPP);

        // Returns the refresh rate of the specified display mode.
        refreshRate = CGDisplayModeGetRefreshRate(mode);

        NSLog(@"CGDisplayMode[%d]: w=%d, h=%d, bpp=%d, freq=%.2lf hz",
              displayID, w, h, bpp, refreshRate);

        CGDisplayModeRelease(mode);
    }
}

BOOL isValidDisplayMode(CGDisplayModeRef mode) {
    if (!CGDisplayModeIsUsableForDesktopGUI(mode)) {
        return NO;
    }
    // Workaround for apple bug FB13261205, since it only affects arm based macs
    // and arm support started with macOS 11 ignore the workaround for previous versions
    if (@available(macOS 11, *)) {
        if (architecture == -1) {
            architecture = [[NSRunningApplication currentApplication] executableArchitecture];
        }
        if (architecture == NSBundleExecutableArchitectureARM64) {
            return (CGDisplayModeGetPixelWidth(mode) >= 800);
        }
    }
    return (1 < CGDisplayModeGetWidth(mode) && 1 < CGDisplayModeGetHeight(mode));
}

static CFDictionaryRef getDisplayModesOptions() {
    // note: this dictionnary is never released:
    static CFDictionaryRef options = NULL;
    if (options == NULL) {
        CFStringRef keys[1] = { kCGDisplayShowDuplicateLowResolutionModes };
        CFBooleanRef values[1] = { kCFBooleanTrue };
        options = CFDictionaryCreate(kCFAllocatorDefault, (const void**) keys, (const void**) values, 1,
                                     &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks );
    }
    return options;
}

static CFMutableArrayRef getAllValidDisplayModes(jint displayID) {
    // Use the options dictionnary to get low resolution modes (scaled):
    // CGDisplayCopyAllDisplayModes can return NULL if displayID is invalid
    CFArrayRef allModes = CGDisplayCopyAllDisplayModes(displayID, getDisplayModesOptions());
    CFMutableArrayRef validModes = NULL;
    if (allModes != NULL) {
        CFIndex numModes = CFArrayGetCount(allModes);
        validModes = CFArrayCreateMutable(kCFAllocatorDefault, numModes + 1, &kCFTypeArrayCallBacks);

        CFIndex n;
        for (n=0; n < numModes; n++) {
            CGDisplayModeRef cRef = (CGDisplayModeRef) CFArrayGetValueAtIndex(allModes, n);
            if ((cRef != NULL) && isValidDisplayMode(cRef)) {
                if (TRACE_DISPLAY_CHANGE_CONF) {
                    NSLog(@"getAllValidDisplayModes[%d]: w=%d, h=%d, freq=%.2lf hz", displayID,
                          (int)CGDisplayModeGetWidth(cRef), (int)CGDisplayModeGetHeight(cRef),
                          CGDisplayModeGetRefreshRate(cRef));
                }
                CFArrayAppendValue(validModes, cRef);
            }
        }
        CFRelease(allModes);

        // CGDisplayCopyDisplayMode can return NULL if displayID is invalid
        CGDisplayModeRef currentMode = CGDisplayCopyDisplayMode(displayID);
        if (currentMode != NULL) {
            BOOL containsCurrentMode = NO;
            numModes = CFArrayGetCount(validModes);
            for (n=0; n < numModes; n++) {
                if(CFArrayGetValueAtIndex(validModes, n) == currentMode) {
                    containsCurrentMode = YES;
                    break;
                }
            }
            if (!containsCurrentMode) {
                CFArrayAppendValue(validModes, currentMode);
            }
            CGDisplayModeRelease(currentMode);
        }
    }
    return validModes;
}

/*
 * Find the best possible match in the list of display modes that we can switch to based on
 * the provided parameters.
 */
static CGDisplayModeRef getBestModeForParameters(CFArrayRef allModes, int w, int h, int bpp, int refrate) {
    CGDisplayModeRef bestGuess = NULL;
    CFIndex numModes = allModes ? CFArrayGetCount(allModes) : 0, n;

    for(n = 0; n < numModes; n++ ) {
        CGDisplayModeRef cRef = (CGDisplayModeRef) CFArrayGetValueAtIndex(allModes, n);
        if(cRef == NULL) {
            continue;
        }
        CFStringRef modeString = CGDisplayModeCopyPixelEncoding(cRef);
        int thisBpp = getBPPFromModeString(modeString);
        CFRelease(modeString);
        int thisH = (int)CGDisplayModeGetHeight(cRef);
        int thisW = (int)CGDisplayModeGetWidth(cRef);
        if (thisBpp != bpp || thisH != h || thisW != w) {
            // One of the key parameters does not match
            continue;
        }

        if (refrate == 0) { // REFRESH_RATE_UNKNOWN
            return cRef;
        }

        // Refresh rate might be 0 in display mode and we ask for specific display rate
        // but if we do not find exact match then 0 refresh rate might be just Ok
        int thisRefrate = (int)CGDisplayModeGetRefreshRate(cRef);
        if (thisRefrate == refrate) {
            // Exact match
            return cRef;
        }
        if (thisRefrate == 0) {
            // Not exactly what was asked for, but may fit our needs if we don't find an exact match
            bestGuess = cRef;
        }
    }
    return bestGuess;
}

/*
 * Create a new java.awt.DisplayMode instance based on provided
 * CGDisplayModeRef, if CGDisplayModeRef is NULL, then some stub is returned.
 */
static jobject createJavaDisplayMode(CGDisplayModeRef mode, JNIEnv *env) {
    jobject ret = NULL;
    jint h = DEFAULT_DEVICE_HEIGHT, w = DEFAULT_DEVICE_WIDTH, bpp = 0, refrate = 0;
    JNI_COCOA_ENTER(env);
    BOOL isDisplayModeDefault = NO;
    if (mode != NULL) {
        CFStringRef currentBPP = CGDisplayModeCopyPixelEncoding(mode);
        bpp = getBPPFromModeString(currentBPP);
        CFRelease(currentBPP);
        refrate = CGDisplayModeGetRefreshRate(mode);
        h = CGDisplayModeGetHeight(mode);
        w = CGDisplayModeGetWidth(mode);
        uint32_t flags = CGDisplayModeGetIOFlags(mode);
        isDisplayModeDefault = (flags & kDisplayModeDefaultFlag) ? YES : NO;
    }
    DECLARE_CLASS_RETURN(jc_DisplayMode, "java/awt/DisplayMode", ret);
    DECLARE_METHOD_RETURN(jc_DisplayMode_ctor, jc_DisplayMode, "<init>", "(IIIIZ)V", ret);
    ret = (*env)->NewObject(env, jc_DisplayMode, jc_DisplayMode_ctor, w, h, bpp, refrate, (jboolean)isDisplayModeDefault);
    CHECK_EXCEPTION();
    JNI_COCOA_EXIT(env);
    return ret;
}


/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeGetXResolution
 * Signature: (I)D
 */
JNIEXPORT jdouble JNICALL
Java_sun_awt_CGraphicsDevice_nativeGetXResolution
  (JNIEnv *env, jclass class, jint displayID)
{
    // CGDisplayScreenSize can return 0 if displayID is invalid
    CGSize size = CGDisplayScreenSize(displayID);
    CGRect rect = CGDisplayBounds(displayID);
    // 1 inch == 25.4 mm
    jfloat inches = size.width / 25.4f;
    return inches > 0 ? rect.size.width / inches : DEFAULT_DEVICE_DPI;
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeGetYResolution
 * Signature: (I)D
 */
JNIEXPORT jdouble JNICALL
Java_sun_awt_CGraphicsDevice_nativeGetYResolution
  (JNIEnv *env, jclass class, jint displayID)
{
    // CGDisplayScreenSize can return 0 if displayID is invalid
    CGSize size = CGDisplayScreenSize(displayID);
    CGRect rect = CGDisplayBounds(displayID);
    // 1 inch == 25.4 mm
    jfloat inches = size.height / 25.4f;
    return inches > 0 ? rect.size.height / inches : DEFAULT_DEVICE_DPI;
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeIsMirroring
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL
Java_sun_awt_CGraphicsDevice_nativeIsMirroring
  (JNIEnv *env, jclass class, jint displayID)
{
    return (CGDisplayIsInMirrorSet(displayID)
            || CGDisplayIsInHWMirrorSet(displayID)) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeGetBounds
 * Signature: (I)Ljava/awt/Rectangle;
 */
JNIEXPORT jobject JNICALL
Java_sun_awt_CGraphicsDevice_nativeGetBounds
(JNIEnv *env, jclass class, jint displayID)
{
    CGRect rect = CGDisplayBounds(displayID);
    if (rect.size.width == 0) {
        rect.size.width = DEFAULT_DEVICE_WIDTH;
    }
    if (rect.size.height == 0) {
        rect.size.height = DEFAULT_DEVICE_HEIGHT;
    }
    return CGToJavaRect(env, rect);
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeGetScreenInsets
 * Signature: (I)D
 */
JNIEXPORT jobject JNICALL
Java_sun_awt_CGraphicsDevice_nativeGetScreenInsets
  (JNIEnv *env, jclass class, jint displayID)
{
    jobject ret = NULL;
    __block NSRect frame = NSZeroRect;
    __block NSRect visibleFrame = NSZeroRect;
JNI_COCOA_ENTER(env);

    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        NSArray *screens = [NSScreen screens];
        for (NSScreen *screen in screens) {
            NSDictionary *screenInfo = [screen deviceDescription];
            NSNumber *screenID = [screenInfo objectForKey:@"NSScreenNumber"];
            if ([screenID unsignedIntValue] == displayID){
                frame = [screen frame];
                visibleFrame = [screen visibleFrame];
                break;
            }
        }
    }];
    // Convert between Cocoa's coordinate system and Java.
    jint bottom = visibleFrame.origin.y - frame.origin.y;
    jint top = frame.size.height - visibleFrame.size.height - bottom;
    jint left = visibleFrame.origin.x - frame.origin.x;
    jint right = frame.size.width - visibleFrame.size.width - left;

    DECLARE_CLASS_RETURN(jc_Insets, "java/awt/Insets", ret);
    DECLARE_METHOD_RETURN(jc_Insets_ctor, jc_Insets, "<init>", "(IIII)V", ret);
    ret = (*env)->NewObject(env, jc_Insets, jc_Insets_ctor, top, left, bottom, right);

JNI_COCOA_EXIT(env);

    return ret;
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeResetDisplayMode
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_CGraphicsDevice_nativeResetDisplayMode
(JNIEnv *env, jclass class)
{
    CGRestorePermanentDisplayConfiguration();
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeSetDisplayMode
 * Signature: (IIIII)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_CGraphicsDevice_nativeSetDisplayMode
(JNIEnv *env, jclass class, jint displayID, jint w, jint h, jint bpp, jint refrate)
{
    CGError retCode = kCGErrorSuccess;

JNI_COCOA_ENTER(env);
    // global lock to ensure only 1 display change transaction at the same time:
    static NSLock* configureDisplayLock;
    static dispatch_once_t oncePredicate;

    dispatch_once(&oncePredicate, ^{
        configureDisplayLock = [[NSLock alloc] init];
    });

    // Avoid reentrance and ensure consistency between the best mode and ConfigureDisplay transaction:
    [configureDisplayLock lock];
    @try {
        if (TRACE_DISPLAY_CHANGE_CONF) {
            NSLog(@"nativeSetDisplayMode: displayID: %d w:%d h:%d bpp: %d refrate:%d", displayID, w, h, bpp, refrate);
        }
        CFArrayRef allModes = getAllValidDisplayModes(displayID);
        CGDisplayModeRef closestMatch = getBestModeForParameters(allModes, (int)w, (int)h, (int)bpp, (int)refrate);

        if (closestMatch != NULL) {
            /*
             * 2025.01: Do not call the following DisplayConfiguration transaction on
             * main thread as it hangs for several seconds on macbook intel + macOS 15
             */
            CGDisplayConfigRef config;
            retCode = CGBeginDisplayConfiguration(&config);
            if (TRACE_DISPLAY_CHANGE_CONF) {
                NSLog(@"nativeSetDisplayMode: CGBeginDisplayConfiguration = %d", retCode);
            }

            if (retCode == kCGErrorSuccess) {
                retCode = CGConfigureDisplayWithDisplayMode(config, displayID, closestMatch, NULL);
                if (TRACE_DISPLAY_CHANGE_CONF) {
                    NSLog(@"nativeSetDisplayMode: CGConfigureDisplayWithDisplayMode = %d", retCode);
                }

                if (retCode == kCGErrorSuccess) {
                    retCode = CGCompleteDisplayConfiguration(config, kCGConfigureForAppOnly);
                    if (TRACE_DISPLAY_CHANGE_CONF) {
                        NSLog(@"nativeSetDisplayMode: CGCompleteDisplayConfiguration = %d", retCode);
                    }
                } else {
                    int retCode2 = CGCancelDisplayConfiguration(config);
                    if (TRACE_DISPLAY_CHANGE_CONF) {
                        NSLog(@"nativeSetDisplayMode: CGCancelDisplayConfiguration = %d", retCode2);
                    }
                }
            }
        } else {
            JNU_ThrowIllegalArgumentException(env, "Invalid display mode");
        }
        if (allModes) {
            CFRelease(allModes);
        }
    } @finally {
        [configureDisplayLock unlock];
    }
    if (retCode != kCGErrorSuccess) {
        JNU_ThrowIllegalArgumentException(env, "Unable to set display mode!");
    }
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeGetDisplayMode
 * Signature: (I)Ljava/awt/DisplayMode
 */
JNIEXPORT jobject JNICALL
Java_sun_awt_CGraphicsDevice_nativeGetDisplayMode
(JNIEnv *env, jclass class, jint displayID)
{
    jobject ret = NULL;

JNI_COCOA_ENTER(env);
    // CGDisplayCopyDisplayMode can return NULL if displayID is invalid
    CGDisplayModeRef currentMode = CGDisplayCopyDisplayMode(displayID);
    ret = createJavaDisplayMode(currentMode, env);
    if (currentMode != NULL) {
        CGDisplayModeRelease(currentMode);
    }
JNI_COCOA_EXIT(env);
    return ret;
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeGetDisplayMode
 * Signature: (I)[Ljava/awt/DisplayModes
 */
JNIEXPORT jobjectArray JNICALL
Java_sun_awt_CGraphicsDevice_nativeGetDisplayModes
(JNIEnv *env, jclass class, jint displayID)
{
    jobjectArray jreturnArray = NULL;

    JNI_COCOA_ENTER(env);
    CFArrayRef allModes = getAllValidDisplayModes(displayID);

    CFIndex numModes = allModes ? CFArrayGetCount(allModes): 0;
    DECLARE_CLASS_RETURN(jc_DisplayMode, "java/awt/DisplayMode", NULL);

    jreturnArray = (*env)->NewObjectArray(env, (jsize)numModes, jc_DisplayMode, NULL);
    if (!jreturnArray) {
        NSLog(@"CGraphicsDevice can't create java array of DisplayMode objects");
    } else {
        CFIndex n;
        for (n = 0; n < numModes; n++) {
            CGDisplayModeRef cRef = (CGDisplayModeRef) CFArrayGetValueAtIndex(allModes, n);
            if (cRef != NULL) {
                jobject oneMode = createJavaDisplayMode(cRef, env);
                (*env)->SetObjectArrayElement(env, jreturnArray, n, oneMode);
                if ((*env)->ExceptionCheck(env)) {
                    (*env)->ExceptionDescribe(env);
                    (*env)->ExceptionClear(env);
                    continue;
                }
                (*env)->DeleteLocalRef(env, oneMode);
            }
        }
    }
    if (allModes) {
        CFRelease(allModes);
    }
    JNI_COCOA_EXIT(env);

    return jreturnArray;
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeGetScaleFactor
 * Signature: (I)D
 */
JNIEXPORT jdouble JNICALL
Java_sun_awt_CGraphicsDevice_nativeGetScaleFactor
(JNIEnv *env, jclass class, jint displayID)
{
    __block jdouble ret = 1.0f;

JNI_COCOA_ENTER(env);

    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
        NSArray *screens = [NSScreen screens];
        for (NSScreen *screen in screens) {
            NSDictionary *screenInfo = [screen deviceDescription];
            NSNumber *screenID = [screenInfo objectForKey:@"NSScreenNumber"];
            if ([screenID unsignedIntValue] == displayID){
                if ([screen respondsToSelector:@selector(backingScaleFactor)]) {
                    ret = [screen backingScaleFactor];
                }
                break;
            }
        }
    }];

JNI_COCOA_EXIT(env);
    return ret;
}

/*
 * Class:     sun_awt_CGraphicsDevice
 * Method:    nativeGetDisplayConfiguration
 * Signature: (Lsun/awt/CGraphicsDevice$DisplayConfiguration;)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_CGraphicsDevice_nativeGetDisplayConfiguration
(JNIEnv *env, jclass class, jobject config) {
AWT_ASSERT_APPKIT_THREAD;
JNI_COCOA_ENTER(env);

    DECLARE_CLASS(jc_DisplayConfiguration, "sun/awt/CGraphicsDevice$DisplayConfiguration");
    DECLARE_METHOD(jc_DisplayConfiguration_addDescriptor, jc_DisplayConfiguration, "addDescriptor", "(IIIIID)V");

    NSArray *screens = [NSScreen screens];
    for (NSScreen *screen in screens) {
        NSDictionary *screenInfo = [screen deviceDescription];
        NSNumber *screenID = [screenInfo objectForKey:@"NSScreenNumber"];

        jint displayID = [screenID unsignedIntValue];
        jdouble scale = 1.0;
        if ([screen respondsToSelector:@selector(backingScaleFactor)]) {
            scale = [screen backingScaleFactor];
        }
        NSRect frame = [screen frame];
        NSRect visibleFrame = [screen visibleFrame];
        // Convert between Cocoa's coordinate system and Java.
        jint bottom = visibleFrame.origin.y - frame.origin.y;
        jint top = frame.size.height - visibleFrame.size.height - bottom;
        jint left = visibleFrame.origin.x - frame.origin.x;
        jint right = frame.size.width - visibleFrame.size.width - left;

        (*env)->CallVoidMethod(env, config, jc_DisplayConfiguration_addDescriptor,
            displayID, top, left, bottom, right, scale);
        CHECK_EXCEPTION();
    }

JNI_COCOA_EXIT(env);
}
