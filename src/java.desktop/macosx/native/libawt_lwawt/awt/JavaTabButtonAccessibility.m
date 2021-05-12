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
            fTabGroupAxContext = JNFNewWeakGlobalRef(env, tabGroup);
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
        fTabGroupAxContext = JNFNewWeakGlobalRef(env, tabGroupAxContext);
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
    return NSAccessibilityTabButtonSubrole;
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

static JNF_CLASS_CACHE(sjc_Object, "java/lang/Object");
static BOOL javaObjectEquals(JNIEnv *env, jobject a, jobject b, jobject component) {
    static JNF_MEMBER_CACHE(jm_equals, sjc_Object, "equals", "(Ljava/lang/Object;)Z");

    if ((a == NULL) && (b == NULL)) return YES;
    if ((a == NULL) || (b == NULL)) return NO;

    if (pthread_main_np() != 0) {
        // If we are on the AppKit thread
        static JNF_CLASS_CACHE(sjc_LWCToolkit, "sun/lwawt/macosx/LWCToolkit");
        static JNF_STATIC_MEMBER_CACHE(jm_doEquals, sjc_LWCToolkit, "doEquals", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/awt/Component;)Z");
        return JNFCallStaticBooleanMethod(env, jm_doEquals, a, b, component); // AWT_THREADING Safe (AWTRunLoopMode)
    }

    return JNFCallBooleanMethod(env, a, jm_equals, b); // AWT_THREADING Safe (!appKit)
}
