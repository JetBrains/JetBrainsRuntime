// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaTabButtonAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

static BOOL javaObjectEquals(JNIEnv *env, jobject a, jobject b, jobject component);

@implementation JavaTabButtonAccessibility

- (id)initWithParent:(NSObject *)parent withEnv:(JNIEnv *)env withAccessible:(jobject)accessible withIndex:(jint)index withTabGroup:(jobject)tabGroup withView:(NSView *)view withJavaRole:(NSString *)javaRole {
    self = [super initWithParent:parent withEnv:env withAccessible:accessible withIndex:index withView:view withJavaRole:javaRole];
    if (self) {
        if (tabGroup != NULL) {
            fTabGroupAxContext = (*env)->NewWeakGlobalRef(env, tabGroup);
            CHECK_EXCEPTION();
        } else {
            fTabGroupAxContext = NULL;
        }
    }
    return self;
}

- (jobject)tabGroup {
    if (fTabGroupAxContext == NULL) {
        JNIEnv* env = [ThreadUtilities getJNIEnv];
        jobject tabGroupAxContext = [(JavaComponentAccessibility *)[self parent] axContextWithEnv:env];
        fTabGroupAxContext = (*env)->NewWeakGlobalRef(env, tabGroupAxContext);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, tabGroupAxContext);
    }
    return fTabGroupAxContext;
}

- (void)performPressAction {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    TabGroupAction *action = [[TabGroupAction alloc] initWithEnv:env withTabGroup:[self tabGroup] withIndex:fIndex withComponent:fComponent];
    [action perform];
    [action release];
}

// NSAccessibilityElement protocol methods

- (NSAccessibilitySubrole)accessibilitySubrole {
    if (@available(macOS 10.13, *)) {
        return NSAccessibilityTabButtonSubrole;
    }
    return NSAccessibilityUnknownSubrole;
}

- (id)accessibilityValue {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    jobject selAccessible = getAxContextSelection(env, [self tabGroup], fIndex, fComponent);

    // Returns the current selection of the page tab list
    id val = [NSNumber numberWithBool:javaObjectEquals(env, axContext, selAccessible, fComponent)];

    (*env)->DeleteLocalRef(env, selAccessible);
    (*env)->DeleteLocalRef(env, axContext);
    return val;
}

- (BOOL)accessibilityPerformPress {
    [self performPressAction];
    return YES;
}

@end

static BOOL javaObjectEquals(JNIEnv *env, jobject a, jobject b, jobject component) {
    DECLARE_CLASS_RETURN(sjc_Object, "java/lang/Object", NO);
    DECLARE_METHOD_RETURN(jm_equals, sjc_Object, "equals", "(Ljava/lang/Object;)Z", NO);

    if ((a == NULL) && (b == NULL)) return YES;
    if ((a == NULL) || (b == NULL)) return NO;

    if (pthread_main_np() != 0) {
        // If we are on the AppKit thread
        DECLARE_CLASS_RETURN(sjc_LWCToolkit, "sun/lwawt/macosx/LWCToolkit", NO);
        DECLARE_STATIC_METHOD_RETURN(jm_doEquals, sjc_LWCToolkit, "doEquals",
                                     "(Ljava/lang/Object;Ljava/lang/Object;Ljava/awt/Component;)Z", NO);
        return (*env)->CallStaticBooleanMethod(env, sjc_LWCToolkit, jm_doEquals, a, b, component); // AWT_THREADING Safe (AWTRunLoopMode)
        CHECK_EXCEPTION();
    }

    jboolean jb = (*env)->CallBooleanMethod(env, a, jm_equals, b); // AWT_THREADING Safe (!appKit)
    CHECK_EXCEPTION();
    return jb;
}
