// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaElementAccessibility.h"

#import "sun_lwawt_macosx_CAccessibility.h"

#import <AppKit/AppKit.h>

#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import <JavaRuntimeSupport/JavaRuntimeSupport.h>

#import <dlfcn.h>

#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTextAccessibility.h"
#import "JavaListAccessibility.h"
#import "JavaComponentAccessibility.h"
#import "ThreadUtilities.h"
#import "AWTView.h"

JNF_CLASS_CACHE(sjc_CAccessible, "sun/lwawt/macosx/CAccessible");
JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");
JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleIndexInParent, sjc_CAccessibility, "getAccessibleIndexInParent", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)I");
JNF_STATIC_MEMBER_CACHE(sjm_getCAccessible, sjc_CAccessible, "getCAccessible", "(Ljavax/accessibility/Accessible;)Lsun/lwawt/macosx/CAccessible;");
JNF_MEMBER_CACHE(jf_ptr, sjc_CAccessible, "ptr", "J");

@class TabGroupAccessibility;
@class ScrollAreaAccessibility;

@implementation JavaElementAccessibility

- (NSRect)accessibilityFrame
{
    return [super accessibilityFrame]; // todo: implement
}

//  - (nullable id)accessibilityParent
//  {
//     return [self parent]; // conflicts
//  }

- (NSString *)description
{
    return [NSString stringWithFormat:@"%@(title:'%@', desc:'%@', value:'%@')", [self accessibilityRoleAttribute],
                                      [self accessibilityTitleAttribute], [self accessibilityRoleDescriptionAttribute], [self accessibilityValueAttribute]];
}

- (id)initWithParent:(NSObject *)parent withEnv:(JNIEnv *)env withAccessible:(jobject)accessible withIndex:(jint)index withView:(NSView *)view withJavaRole:(NSString *)javaRole
{
    self = [super init];
    if (self) {
        fParent = [parent retain];
        fView = [view retain];
        fJavaRole = [javaRole retain];

        fAccessible = (*env)->NewWeakGlobalRef(env, accessible);
        (*env)->ExceptionClear(env); // in case of OOME
        jobject jcomponent = [(AWTView *) fView awtComponent:env];
        fComponent = (*env)->NewWeakGlobalRef(env, jcomponent);
        (*env)->DeleteLocalRef(env, jcomponent);

        fIndex = index;

        fActions = nil;
        fActionsLOCK = [[NSObject alloc] init];
    }
    return self;
}

- (void)unregisterFromCocoaAXSystem
{
    AWT_ASSERT_APPKIT_THREAD;
    static dispatch_once_t initialize_unregisterUniqueId_once;
    static void (*unregisterUniqueId)(id);
    dispatch_once(&initialize_unregisterUniqueId_once, ^{
        void *jrsFwk = dlopen("/System/Library/Frameworks/JavaVM.framework/Frameworks/JavaRuntimeSupport.framework/JavaRuntimeSupport",
                              RTLD_LAZY | RTLD_LOCAL);
        unregisterUniqueId = dlsym(jrsFwk, "JRSAccessibilityUnregisterUniqueIdForUIElement");
    });
    if (unregisterUniqueId) unregisterUniqueId(self);
}

- (void)dealloc
{
    [self unregisterFromCocoaAXSystem];

    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];

    (*env)->DeleteWeakGlobalRef(env, fAccessible);
    fAccessible = NULL;

    (*env)->DeleteWeakGlobalRef(env, fComponent);
    fComponent = NULL;

    [fParent release];
    fParent = nil;

    [fNSRole release];
    fNSRole = nil;

    [fJavaRole release];
    fJavaRole = nil;

    [fView release];
    fView = nil;

    [fActions release];
    fActions = nil;

    [fActionsLOCK release];
    fActionsLOCK = nil;

    [super dealloc];
}

- (void)postValueChanged
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, NSAccessibilityValueChangedNotification);
}

- (void)postSelectedTextChanged
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, NSAccessibilitySelectedTextChangedNotification);
}

- (void)postSelectionChanged
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, NSAccessibilitySelectedChildrenChangedNotification);
}

- (void)postMenuOpened
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, (NSString *) kAXMenuOpenedNotification);
}

- (void)postMenuClosed
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, (NSString *) kAXMenuClosedNotification);
}

- (void)postMenuItemSelected
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, (NSString *) kAXMenuItemSelectedNotification);
}

- (BOOL)isEqual:(id)anObject
{
    if (![anObject isKindOfClass:[self class]]) return NO;
    JavaElementAccessibility *accessibility = (JavaElementAccessibility *) anObject;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    return (*env)->IsSameObject(env, accessibility->fAccessible, fAccessible);
}

- (BOOL)isAccessibleWithEnv:(JNIEnv *)env forAccessible:(jobject)accessible
{
    return (*env)->IsSameObject(env, fAccessible, accessible);
}

+ (void)initialize
{
    if (sRoles == nil) {
        initializeRoles();
    }
}

+ (void)postFocusChanged:(id)message
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification([NSApp accessibilityFocusedUIElement],
                                    NSAccessibilityFocusedUIElementChangedNotification);
}

+ (jobject)getCAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env
{
    if (JNFIsInstanceOf(env, jaccessible, &sjc_CAccessible)) {
        return jaccessible;
    } else if (JNFIsInstanceOf(env, jaccessible, &sjc_Accessible)) {
        return JNFCallStaticObjectMethod(env, sjm_getCAccessible, jaccessible);
    }
    return NULL;
}

+ (NSArray *)childrenOfParent:(JavaElementAccessibility *)parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored
{
    if (parent->fAccessible == NULL) return nil;
    jobjectArray jchildrenAndRoles = (jobjectArray) JNFCallStaticObjectMethod(env, jm_getChildrenAndRoles,
                                                                              parent->fAccessible, parent->fComponent,
                                                                              whichChildren,
                                                                              allowIgnored); // AWT_THREADING Safe (AWTRunLoop)
    if (jchildrenAndRoles == NULL) return nil;

    jsize arrayLen = (*env)->GetArrayLength(env, jchildrenAndRoles);
    NSMutableArray *children = [NSMutableArray arrayWithCapacity:arrayLen /
                                                                 2]; //childrenAndRoles array contains two elements (child, role) for each child

    NSInteger i;
    NSUInteger childIndex = (whichChildren >= 0) ? whichChildren
                                                 : 0; // if we're getting one particular child, make sure to set its index correctly
    for (i = 0; i < arrayLen; i += 2) {
        jobject /* Accessible */ jchild = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i);
        jobject /* String */ jchildJavaRole = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i + 1);

        NSString *childJavaRole = nil;
        if (jchildJavaRole != NULL) {
            jobject jkey = JNFGetObjectField(env, jchildJavaRole, sjf_key);
            childJavaRole = JNFJavaToNSString(env, jkey);
            (*env)->DeleteLocalRef(env, jkey);
        }

        JavaElementAccessibility *child = [self createWithParent:parent accessible:jchild role:childJavaRole index:childIndex withEnv:env withView:parent->fView];

        (*env)->DeleteLocalRef(env, jchild);
        (*env)->DeleteLocalRef(env, jchildJavaRole);

        [children addObject:child];
        childIndex++;
    }
    (*env)->DeleteLocalRef(env, jchildrenAndRoles);

    return children;
}

+ (JavaElementAccessibility *)createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view
{
    JavaElementAccessibility *ret = nil;
    jobject jcomponent = [(AWTView *) view awtComponent:env];
    jint index = JNFCallStaticIntMethod(env, sjm_getAccessibleIndexInParent, jaccessible, jcomponent);
    if (index >= 0) {
        NSString *javaRole = getJavaRole(env, jaccessible, jcomponent);
        ret = [self createWithAccessible:jaccessible role:javaRole index:index withEnv:env withView:view];
    }
    (*env)->DeleteLocalRef(env, jcomponent);
    return ret;
}

+ (JavaElementAccessibility *)createWithAccessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [self createWithParent:nil accessible:jaccessible role:javaRole index:index withEnv:env withView:view];
}

+ (JavaElementAccessibility *)createWithParent:(JavaElementAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    // try to fetch the jCAX from Java, and return autoreleased
    jobject jCAX = [JavaElementAccessibility getCAccessible:jaccessible withEnv:env];
    if (jCAX == NULL) return nil;
    JavaElementAccessibility *value = (JavaElementAccessibility *) jlong_to_ptr(JNFGetLongField(env, jCAX, jf_ptr));
    if (value != nil) {
        (*env)->DeleteLocalRef(env, jCAX);
        return [[value retain] autorelease];
    }

    // otherwise, create a new instance
    JavaElementAccessibility *newChild = nil;
    if ([javaRole isEqualToString:@"pagetablist"]) {
        newChild = [TabGroupAccessibility alloc];
    } else if ([javaRole isEqualToString:@"scrollpane"]) {
        newChild = [ScrollAreaAccessibility alloc];
    } else {
        NSString *nsRole = [sRoles objectForKey:javaRole];
        if ([nsRole isEqualToString:NSAccessibilityStaticTextRole] ||
            [nsRole isEqualToString:NSAccessibilityTextAreaRole] ||
            [nsRole isEqualToString:NSAccessibilityTextFieldRole]) {
            newChild = [JavaTextAccessibility alloc];
        } else if ([nsRole isEqualToString:NSAccessibilityListRole]) {
            newChild = [JavaListAccessibility alloc];
//      } else if ([nsRole isEqualToString:NSAccessibilityRowRole]) {
//        newChild = [JavaRowAccessibility alloc];
        } else {
            newChild = [JavaElementAccessibility alloc];
        }
    }

    // must init freshly -alloc'd object
    [newChild initWithParent:parent withEnv:env withAccessible:jCAX withIndex:index withView:view withJavaRole:javaRole]; // must init new instance

    // If creating a JPopupMenu (not a combobox popup list) need to fire menuOpened.
    // This is the only way to know if the menu is opening; visible state change
    // can't be caught because the listeners are not set up in time.
    if ([javaRole isEqualToString:@"popupmenu"] &&
        ![[parent javaRole] isEqualToString:@"combobox"]) {
        [newChild postMenuOpened];
    }

    // must hard retain pointer poked into Java object
    [newChild retain];
    JNFSetLongField(env, jCAX, jf_ptr, ptr_to_jlong(newChild));
    (*env)->DeleteLocalRef(env, jCAX);

    // return autoreleased instance
    return [newChild autorelease];
}

- (NSDictionary *)getActions:(JNIEnv *)env
{
    @synchronized (fActionsLOCK) {
        if (fActions == nil) {
            fActions = [[NSMutableDictionary alloc] initWithCapacity:3];
            [self getActionsWithEnv:env];
        }
    }

    return fActions;
}

- (void)getActionsWithEnv:(JNIEnv *)env
{
    static JNF_STATIC_MEMBER_CACHE(jm_getAccessibleAction, sjc_CAccessibility,
    "getAccessibleAction", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleAction;");

    // On MacOSX, text doesn't have actions, in java it does.
    // cmcnote: NOT TRUE - Editable text has AXShowMenu. Textfields have AXConfirm. Static text has no actions.
    jobject axAction = JNFCallStaticObjectMethod(env, jm_getAccessibleAction, fAccessible,
                                                 fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axAction != NULL) {
        //+++gdb NOTE: In MacOSX, there is just a single Action, not multiple. In java,
        //  the first one seems to be the most basic, so this will be used.
        // cmcnote: NOT TRUE - Sometimes there are multiple actions, eg sliders have AXDecrement AND AXIncrement (radr://3893192)
        JavaAxAction *action = [[JavaAxAction alloc] initWithEnv:env withAccessibleAction:axAction withIndex:0 withComponent:fComponent];
        [fActions setObject:action forKey:[self isMenu] ? NSAccessibilityPickAction : NSAccessibilityPressAction];
        [action release];
        (*env)->DeleteLocalRef(env, axAction);
    }
}

- (jobject)axContextWithEnv:(JNIEnv *)env
{
    return getAxContext(env, fAccessible, fComponent);
}

- (id)parent
{
    static JNF_CLASS_CACHE(sjc_Window, "java/awt/Window");
    static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleParent, sjc_CAccessibility, "getAccessibleParent", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/Accessible;");
    static JNF_STATIC_MEMBER_CACHE(sjm_getSwingAccessible, sjc_CAccessible, "getSwingAccessible", "(Ljavax/accessibility/Accessible;)Ljavax/accessibility/Accessible;");

    if (fParent == nil) {
        JNIEnv *env = [ThreadUtilities getJNIEnv];

        jobject jparent = JNFCallStaticObjectMethod(env, sjm_getAccessibleParent, fAccessible, fComponent);

        if (jparent == NULL) {
            fParent = fView;
        } else {
            AWTView *view = fView;
            jobject jax = JNFCallStaticObjectMethod(env, sjm_getSwingAccessible, fAccessible);

            if (JNFIsInstanceOf(env, jax, &sjc_Window)) {
                // In this case jparent is an owner toplevel and we should retrieve its own view
                view = [AWTView awtView:env ofAccessible:jparent];
            }
            if (view != nil) {
                fParent = [JavaElementAccessibility createWithAccessible:jparent withEnv:env withView:view];
            }
            if (fParent == nil) {
                fParent = fView;
            }
            (*env)->DeleteLocalRef(env, jparent);
            (*env)->DeleteLocalRef(env, jax);
        }
        [fParent retain];
    }
    return fParent;
}

- (NSView *)view
{
    return fView;
}

- (NSWindow *)window
{
    return [[self view] window];
}

- (NSString *)javaRole
{
    if (fJavaRole == nil) {
        JNIEnv *env = [ThreadUtilities getJNIEnv];
        fJavaRole = getJavaRole(env, fAccessible, fComponent);
        [fJavaRole retain];
    }
    return fJavaRole;
}

- (BOOL)isMenu
{
    id role = [self accessibilityRoleAttribute];
    return [role isEqualToString:NSAccessibilityMenuBarRole] || [role isEqualToString:NSAccessibilityMenuRole] ||
           [role isEqualToString:NSAccessibilityMenuItemRole];
}

- (BOOL)isSelected:(JNIEnv *)env
{
    if (fIndex == -1) {
        return NO;
    }

    return isChildSelected(env, ((JavaElementAccessibility *) [self parent])->fAccessible, fIndex, fComponent);
}

- (BOOL)isSelectable:(JNIEnv *)env
{
    jobject axContext = [self axContextWithEnv:env];
    BOOL selectable = isSelectable(env, axContext, fComponent);
    (*env)->DeleteLocalRef(env, axContext);
    return selectable;
}

- (BOOL)isVisible:(JNIEnv *)env
{
    if (fIndex == -1) {
        return NO;
    }

    jobject axContext = [self axContextWithEnv:env];
    BOOL showing = isShowing(env, axContext, fComponent);
    (*env)->DeleteLocalRef(env, axContext);
    return showing;
}

// -- misc accessibility --
- (BOOL)accessibilityIsIgnored
{
#ifdef JAVA_AX_NO_IGNORES
    return NO;
#else
    return [[self accessibilityRoleAttribute] isEqualToString:JavaAccessibilityIgnore];
#endif /* JAVA_AX_NO_IGNORES */
}

- (id)accessibilityHitTest:(NSPoint)point withEnv:(JNIEnv *)env
{
    static JNF_CLASS_CACHE(jc_Container, "java/awt/Container");
    static JNF_STATIC_MEMBER_CACHE(jm_accessibilityHitTest, sjc_CAccessibility, "accessibilityHitTest", "(Ljava/awt/Container;FF)Ljavax/accessibility/Accessible;");

    // Make it into java screen coords
    point.y = [[[[self view] window] screen] frame].size.height - point.y;

    jobject jparent = fComponent;

    id value = nil;
    if (JNFIsInstanceOf(env, jparent, &jc_Container)) {
        jobject jaccessible = JNFCallStaticObjectMethod(env, jm_accessibilityHitTest, jparent, (jfloat) point.x, (jfloat) point.y); // AWT_THREADING Safe (AWTRunLoop)
        if (jaccessible != NULL) {
            value = [JavaElementAccessibility createWithAccessible:jaccessible withEnv:env withView:fView];
            (*env)->DeleteLocalRef(env, jaccessible);
        }
    }

    if (value == nil) {
        value = self;
    }

    if ([value accessibilityIsIgnored]) {
        value = NSAccessibilityUnignoredAncestor(value);
    }

#ifdef JAVA_AX_DEBUG
    NSLog(@"%s: %@", __FUNCTION__, value);
#endif
    return value;
}

- (id)accessibilityFocusedUIElement
{
    static JNF_STATIC_MEMBER_CACHE(jm_getFocusOwner, sjc_CAccessibility, "getFocusOwner", "(Ljava/awt/Component;)Ljavax/accessibility/Accessible;");

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    id value = nil;

    NSWindow *hostWindow = [[self->fView window] retain];
    jobject focused = JNFCallStaticObjectMethod(env, jm_getFocusOwner, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    [hostWindow release];

    if (focused != NULL) {
        if (JNFIsInstanceOf(env, focused, &sjc_Accessible)) {
            value = [JavaElementAccessibility createWithAccessible:focused withEnv:env withView:fView];
        }
        (*env)->DeleteLocalRef(env, focused);
    }

    if (value == nil) {
        value = self;
    }
#ifdef JAVA_AX_DEBUG
    NSLog(@"%s: %@", __FUNCTION__, value);
#endif
    return value;
}

@end

/*
 * Class:     sun_lwawt_macosx_CAccessibility
 * Method:    focusChanged
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessibility_focusChanged
(JNIEnv *env, jobject jthis)
{
JNF_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(postFocusChanged:) on:[JavaElementAccessibility class] withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    valueChanged
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_valueChanged
(JNIEnv *env, jclass jklass, jlong element)
{
JNF_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(postValueChanged) on:(JavaElementAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    selectedTextChanged
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_selectedTextChanged
(JNIEnv *env, jclass jklass, jlong element)
{
JNF_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(postSelectedTextChanged) on:(JavaElementAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    selectionChanged
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_selectionChanged
(JNIEnv *env, jclass jklass, jlong element)
{
JNF_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(postSelectionChanged) on:(JavaElementAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    menuOpened
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_menuOpened
(JNIEnv *env, jclass jklass, jlong element)
{
JNF_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(postMenuOpened) on:(JavaElementAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    menuClosed
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_menuClosed
(JNIEnv *env, jclass jklass, jlong element)
{
JNF_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(postMenuClosed) on:(JavaElementAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    menuItemSelected
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_menuItemSelected
(JNIEnv *env, jclass jklass, jlong element)
{
JNF_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(postMenuItemSelected) on:(JavaElementAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    unregisterFromCocoaAXSystem
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_unregisterFromCocoaAXSystem
(JNIEnv *env, jclass jklass, jlong element)
{
JNF_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(unregisterFromCocoaAXSystem) on:(JavaElementAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}
