// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaBaseAccessibility.h"

#import "sun_lwawt_macosx_CAccessibility.h"

#import <AppKit/AppKit.h>

#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import <JavaRuntimeSupport/JavaRuntimeSupport.h>

#import <dlfcn.h>

#import "JavaBaseAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaListAccessibility.h"
#import "JavaTableAccessibility.h"
#import "JavaListRowAccessibility.h"
#import "JavaTableRowAccessibility.h"
#import "JavaCellAccessibility.h"
#import "JavaOutlineAccessibility.h"
#import "JavaOutlineRowAccessibility.h"
#import "JavaStaticTextAccessibility.h"
#import "JavaNavigableTextAccessibility.h"
#import "JavaScrollAreaAccessibility.h"
#import "JavaTabGroupAccessibility.h"
#import "JavaComboBoxAccessibility.h"
#import "JavaComponentAccessibility.h"
#import "ThreadUtilities.h"
#import "AWTView.h"

// getChildrenAndRolesRecursive

static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");
static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRolesRecursive, sjc_CAccessibility, "getChildrenAndRolesRecursive", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZI)[Ljava/lang/Object;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleComponent, sjc_CAccessibility, "getAccessibleComponent", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleComponent;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleValue, sjc_CAccessibility, "getAccessibleValue", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleValue;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleDescription, sjc_CAccessibility, "getAccessibleDescription", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");
static JNF_STATIC_MEMBER_CACHE(sjm_isFocusTraversable, sjc_CAccessibility, "isFocusTraversable", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Z");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleIndexInParent, sjc_CAccessibility, "getAccessibleIndexInParent", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)I");

static JNF_CLASS_CACHE(sjc_CAccessible, "sun/lwawt/macosx/CAccessible");

static JNF_MEMBER_CACHE(jf_ptr, sjc_CAccessible, "ptr", "J");
static JNF_STATIC_MEMBER_CACHE(sjm_getCAccessible, sjc_CAccessible, "getCAccessible", "(Ljavax/accessibility/Accessible;)Lsun/lwawt/macosx/CAccessible;");

static jobject sAccessibilityClass = NULL;

@implementation JavaBaseAccessibility

@synthesize platformAxElement;
@synthesize javaBase;

- (id)init
{
    self = [super init];
    if (self) {
        NSString *className = [self getPlatformAxElementClassName];
        self.platformAxElement = className != NULL ? [[NSClassFromString(className) alloc] init] : self; // defaults to [self]
        self.platformAxElement.javaBase = self;
    }
    return self;
}

// to override in subclasses
- (NSString *)getPlatformAxElementClassName
{
    return NULL;
}

- (id)initWithParent:(NSObject *)parent withEnv:(JNIEnv *)env withAccessible:(jobject)accessible withIndex:(jint)index withView:(NSView *)view withJavaRole:(NSString *)javaRole
{
    self = [self init];
    if (self) {
        fParent = [parent retain];
        fView = [view retain];
        fJavaRole = [javaRole retain];

            fAccessible = (*env)->NewWeakGlobalRef(env, accessible);
            (*env)->ExceptionClear(env); // in case of OOME
            jobject jcomponent = [(AWTView *)fView awtComponent:env];
            fComponent = (*env)->NewWeakGlobalRef(env, jcomponent);
            (*env)->DeleteLocalRef(env, jcomponent);

        fIndex = index;
    }
    return self;
}

- (void)unregisterFromCocoaAXSystem
{
    AWT_ASSERT_APPKIT_THREAD;
    static dispatch_once_t initialize_unregisterUniqueId_once;
    static void (*unregisterUniqueId)(id);
    dispatch_once(&initialize_unregisterUniqueId_once, ^{
        void *jrsFwk = dlopen("/System/Library/Frameworks/JavaVM.framework/Frameworks/JavaRuntimeSupport.framework/JavaRuntimeSupport", RTLD_LAZY | RTLD_LOCAL);
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

    if (self.platformAxElement != self) {
        [self.platformAxElement dealloc];
    }

    [super dealloc];
}

- (void)postValueChanged
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self.platformAxElement, NSAccessibilityValueChangedNotification);
}

- (void)postSelectedTextChanged
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self.platformAxElement, NSAccessibilitySelectedTextChangedNotification);
}

- (void)postSelectionChanged
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self.platformAxElement, NSAccessibilitySelectedChildrenChangedNotification);
}

- (void)postMenuOpened
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self.platformAxElement, (NSString *)kAXMenuOpenedNotification);
}

- (void)postMenuClosed
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self.platformAxElement, (NSString *)kAXMenuClosedNotification);
}

- (void)postMenuItemSelected
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self.platformAxElement, (NSString *)kAXMenuItemSelectedNotification);
}

- (BOOL)isEqual:(id)anObject
{
    if (![anObject isKindOfClass:[self class]]) return NO;
    JavaBaseAccessibility *accessibility = (JavaBaseAccessibility *)anObject;

    JNIEnv* env = [ThreadUtilities getJNIEnv];
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

    if (sAccessibilityClass == NULL) {
        JNF_STATIC_MEMBER_CACHE(jm_getAccessibility, sjc_CAccessibility, "getAccessibility", "([Ljava/lang/String;)Lsun/lwawt/macosx/CAccessibility;");

#ifdef JAVA_AX_NO_IGNORES
        NSArray *ignoredKeys = [NSArray array];
#else
        NSArray *ignoredKeys = [sRoles allKeysForObject:JavaAccessibilityIgnore];
#endif
        jobjectArray result = NULL;
        jsize count = [ignoredKeys count];

        JNIEnv *env = [ThreadUtilities getJNIEnv];

        static JNF_CLASS_CACHE(jc_String, "java/lang/String");
        result = JNFNewObjectArray(env, &jc_String, count);
        if (!result) {
            NSLog(@"In %s, can't create Java array of String objects", __FUNCTION__);
            return;
        }

        NSInteger i;
        for (i = 0; i < count; i++) {
            jstring jString = JNFNSToJavaString(env, [ignoredKeys objectAtIndex:i]);
            (*env)->SetObjectArrayElement(env, result, i, jString);
            (*env)->DeleteLocalRef(env, jString);
        }

        sAccessibilityClass = JNFCallStaticObjectMethod(env, jm_getAccessibility, result); // AWT_THREADING Safe (known object)
    }
}

+ (void)postFocusChanged:(id)message
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification([NSApp accessibilityFocusedUIElement], NSAccessibilityFocusedUIElementChangedNotification);
}

+ (jobject) getCAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env {
    if (JNFIsInstanceOf(env, jaccessible, &sjc_CAccessible)) {
        return jaccessible;
    } else if (JNFIsInstanceOf(env, jaccessible, &sjc_Accessible)) {
        return JNFCallStaticObjectMethod(env, sjm_getCAccessible, jaccessible);
    }
    return NULL;
}

+ (NSArray *) childrenOfParent:(JavaBaseAccessibility *)parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored
{
    return [JavaBaseAccessibility childrenOfParent:parent withEnv:env withChildrenCode:whichChildren allowIgnored:allowIgnored recursive:NO];
}

+ (NSArray *) childrenOfParent:(JavaBaseAccessibility *)parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored recursive:(BOOL)recursive
{
    if ([parent isKindOfClass:[JavaTableAccessibility class]]) {
        if (whichChildren == JAVA_AX_SELECTED_CHILDREN) {
            NSArray<NSNumber *> *selectedRowIndexses = [(JavaTableAccessibility *)parent selectedAccessibleRows];
            NSMutableArray *children = [NSMutableArray arrayWithCapacity:[selectedRowIndexses count]];
            for (NSNumber *index in selectedRowIndexses) {
                [children addObject:[[JavaTableRowAccessibility alloc] initWithParent:parent
                                                                              withEnv:env
                                                                       withAccessible:NULL
                                                                            withIndex:index.unsignedIntValue
                                                                             withView:[parent view]
                                                                         withJavaRole:JavaAccessibilityIgnore].platformAxElement];
            }
            return [NSArray arrayWithArray:children];
        } else if (whichChildren == JAVA_AX_ALL_CHILDREN) {
            int rowCount = [(JavaTableAccessibility *)parent accessibleRowCount];
            NSMutableArray *children = [NSMutableArray arrayWithCapacity:rowCount];
            for (int i = 0; i < rowCount; i++) {
                [children addObject:[[JavaTableRowAccessibility alloc] initWithParent:parent
                                                                              withEnv:env
                                                                       withAccessible:NULL
                                                                            withIndex:i
                                                                             withView:[parent view]
                                                                         withJavaRole:JavaAccessibilityIgnore].platformAxElement];
            }
            return [NSArray arrayWithArray:children];
        } else {
            return [NSArray arrayWithObject:[[JavaTableRowAccessibility alloc] initWithParent:parent
                                                                                      withEnv:env
                                                                               withAccessible:NULL
                                                                                    withIndex:whichChildren
                                                                                     withView:[parent view]
                                                                                 withJavaRole:JavaAccessibilityIgnore].platformAxElement];
        }
    }
    if (parent->fAccessible == NULL) return nil;
    jobjectArray jchildrenAndRoles = NULL;
    if (recursive) {
        jchildrenAndRoles = (jobjectArray)JNFCallStaticObjectMethod(env, jm_getChildrenAndRolesRecursive, parent->fAccessible, parent->fComponent, whichChildren, allowIgnored, 1);
    } else {
        jchildrenAndRoles = (jobjectArray)JNFCallStaticObjectMethod(env, jm_getChildrenAndRoles, parent->fAccessible, parent->fComponent, whichChildren, allowIgnored); // AWT_THREADING Safe (AWTRunLoop)
    }
    if (jchildrenAndRoles == NULL) return nil;

    jsize arrayLen = (*env)->GetArrayLength(env, jchildrenAndRoles);
    NSMutableArray *children = [NSMutableArray arrayWithCapacity:arrayLen/2]; //childrenAndRoles array contains two elements (child, role) for each child

    NSInteger i;
    NSUInteger childIndex = (whichChildren >= 0) ? whichChildren : 0; // if we're getting one particular child, make sure to set its index correctly
    int inc = recursive ? 3 : 2;
    for(i = 0; i < arrayLen; i += inc)
    {
        jobject /* Accessible */ jchild = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i);
        jobject /* String */ jchildJavaRole = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i+1);

        NSString *childJavaRole = nil;
        if (jchildJavaRole != NULL) {
            jobject jkey = JNFGetObjectField(env, jchildJavaRole, sjf_key);
            childJavaRole = JNFJavaToNSString(env, jkey);
            (*env)->DeleteLocalRef(env, jkey);
        }

        JavaBaseAccessibility *child = [self createWithParent:parent accessible:jchild role:childJavaRole index:childIndex withEnv:env withView:parent->fView];
        if ([child isKindOfClass:[JavaOutlineRowAccessibility class]]) {
            jobject jLevel = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i+2);
            NSString *sLevel = nil;
            if (jLevel != NULL) {
                sLevel = JNFJavaToNSString(env, jLevel);
                (*env)->DeleteLocalRef(env, jLevel);
                int level = sLevel.intValue;
                [(JavaOutlineRowAccessibility *)child setAccessibleLevel:level];
            }
        }

        [children addObject:child.platformAxElement];

        (*env)->DeleteLocalRef(env, jchild);
        (*env)->DeleteLocalRef(env, jchildJavaRole);

        childIndex++;
    }
    (*env)->DeleteLocalRef(env, jchildrenAndRoles);

    return children;
}

+ (JavaBaseAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [JavaBaseAccessibility createWithAccessible:jaccessible withEnv:env withView:view isCurrent:NO];
}

+ (JavaBaseAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view isCurrent:(BOOL)current
{
    JavaBaseAccessibility *ret = nil;
    jobject jcomponent = [(AWTView *)view awtComponent:env];
    jint index = JNFCallStaticIntMethod(env, sjm_getAccessibleIndexInParent, jaccessible, jcomponent);
    NSString *javaRole = getJavaRole(env, jaccessible, jcomponent);
    if ((index >= 0) || current) {
        ret = [self createWithAccessible:jaccessible role:javaRole index:index withEnv:env withView:view];
    }
    (*env)->DeleteLocalRef(env, jcomponent);
    return ret;
}

+ (JavaBaseAccessibility *) createWithAccessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [self createWithParent:nil accessible:jaccessible role:javaRole index:index withEnv:env withView:view];
}

+ (JavaBaseAccessibility *) createWithParent:(JavaBaseAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [JavaBaseAccessibility createWithParent:parent accessible:jaccessible role:javaRole index:index withEnv:env withView:view isWrapped:NO];
}

+ (JavaBaseAccessibility *) createWithParent:(JavaBaseAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view isWrapped:(BOOL)wrapped
{
    // try to fetch the jCAX from Java, and return autoreleased
    jobject jCAX = [JavaBaseAccessibility getCAccessible:jaccessible withEnv:env];
    if (jCAX == NULL) return nil;
    if (!wrapped) { // If wrapped is true, then you don't need to get an existing instance, you need to create a new one
    JavaBaseAccessibility *value = (JavaBaseAccessibility *) jlong_to_ptr(JNFGetLongField(env, jCAX, jf_ptr));
    if (value != nil) {
        (*env)->DeleteLocalRef(env, jCAX);
        return [[value retain] autorelease];
    }
    }

    // otherwise, create a new instance
    JavaBaseAccessibility *newChild = nil;
    if ([[sRoles objectForKey:[parent javaRole]] isEqualToString:NSAccessibilityListRole]) {
        newChild = [JavaListRowAccessibility alloc];
    } else if ([parent isKindOfClass:[JavaOutlineAccessibility class]]) {
        newChild = [JavaOutlineRowAccessibility alloc];
    } else if ([javaRole isEqualToString:@"pagetablist"]) {
        newChild = [JavaTabGroupAccessibility alloc];
    } else if ([javaRole isEqualToString:@"scrollpane"]) {
        newChild = [JavaScrollAreaAccessibility alloc];
    } else {
        NSString *nsRole = [sRoles objectForKey:javaRole];
        if ([nsRole isEqualToString:NSAccessibilityStaticTextRole]) {
            newChild = [JavaStaticTextAccessibility alloc];
        } else if ([nsRole isEqualToString:NSAccessibilityTextAreaRole] || [nsRole isEqualToString:NSAccessibilityTextFieldRole]) {
            newChild = [JavaNavigableTextAccessibility alloc];
        } else if ([nsRole isEqualToString:NSAccessibilityListRole]) {
            newChild = [JavaListAccessibility alloc];
        } else if ([nsRole isEqualToString:NSAccessibilityTableRole]) {
            newChild = [JavaTableAccessibility alloc];
        } else if ([nsRole isEqualToString:NSAccessibilityOutlineRole]) {
            newChild = [JavaOutlineAccessibility alloc];
        } else if ([nsRole isEqualToString:NSAccessibilityComboBoxRole]) {
            newChild = [JavaComboBoxAccessibility alloc];
        } else {
            newChild = [JavaComponentAccessibility alloc];
        }
    }

    // must init freshly -alloc'd object
    [newChild initWithParent:parent withEnv:env withAccessible:jCAX withIndex:index withView:view withJavaRole:javaRole]; // must init new instance

    // If creating a JPopupMenu (not a combobox popup list) need to fire menuOpened.
    // This is the only way to know if the menu is opening; visible state change
    // can't be caught because the listeners are not set up in time.
    if ( [javaRole isEqualToString:@"popupmenu"] &&
         ![[parent javaRole] isEqualToString:@"combobox"] ) {
        [newChild postMenuOpened];
    }

    // must hard retain pointer poked into Java object
    [newChild retain];
    JNFSetLongField(env, jCAX, jf_ptr, ptr_to_jlong(newChild));

    // the link is removed in the wrapper
    if (!wrapped) {
        (*env)->DeleteLocalRef(env, jCAX);
    }

    // return autoreleased instance
    return [newChild autorelease];
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

    if(fParent == nil) {
        JNIEnv* env = [ThreadUtilities getJNIEnv];

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
                fParent = [JavaBaseAccessibility createWithAccessible:jparent withEnv:env withView:view];
            }
            if (fParent == nil) {
                fParent = fView;
            }
            (*env)->DeleteLocalRef(env, jparent);
            (*env)->DeleteLocalRef(env, jax );
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
    if(fJavaRole == nil) {
        JNIEnv* env = [ThreadUtilities getJNIEnv];
        fJavaRole = getJavaRole(env, fAccessible, fComponent);
        [fJavaRole retain];
    }
    return fJavaRole;
}

- (BOOL)isMenu
{
    id role = [self accessibilityRoleAttribute];
    return [role isEqualToString:NSAccessibilityMenuBarRole] || [role isEqualToString:NSAccessibilityMenuRole] || [role isEqualToString:NSAccessibilityMenuItemRole];
}

- (BOOL)isSelected:(JNIEnv *)env
{
    if (fIndex == -1) {
        return NO;
    }

    return isChildSelected(env, ((JavaBaseAccessibility *)[self parent])->fAccessible, fIndex, fComponent);
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

- (NSSize)getSize
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    jobject axComponent = JNFCallStaticObjectMethod(env, sjm_getAccessibleComponent, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    NSSize size = getAxComponentSize(env, axComponent, fComponent);
    (*env)->DeleteLocalRef(env, axComponent);

    return size;
}

- (NSRect)getBounds
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    jobject axComponent = JNFCallStaticObjectMethod(env, sjm_getAccessibleComponent, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)

    // NSAccessibility wants the bottom left point of the object in
    // bottom left based screen coords

    // Get the java screen coords, and make a NSPoint of the bottom left of the AxComponent.
    NSSize size = getAxComponentSize(env, axComponent, fComponent);
    NSPoint point = getAxComponentLocationOnScreen(env, axComponent, fComponent);
    (*env)->DeleteLocalRef(env, axComponent);

    point.y += size.height;
    // Now make it into Cocoa screen coords.
    point.y = [[[[self view] window] screen] frame].size.height - point.y;

    return NSMakeRect(point.x, point.y, size.width, size.height);
}

- (id)getFocusedElement
{
    static JNF_STATIC_MEMBER_CACHE(jm_getFocusOwner, sjc_CAccessibility, "getFocusOwner", "(Ljava/awt/Component;)Ljavax/accessibility/Accessible;");

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    id value = nil;

    NSWindow* hostWindow = [[self->fView window] retain];
    jobject focused = JNFCallStaticObjectMethod(env, jm_getFocusOwner, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    [hostWindow release];

    if (focused != NULL) {
        if (JNFIsInstanceOf(env, focused, &sjc_Accessible)) {
            value = [JavaComponentAccessibility createWithAccessible:focused withEnv:env withView:fView];
            value = ((JavaBaseAccessibility *)value).platformAxElement;
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

- (jobject)accessible {
    return fAccessible;
}

- (jobject)component {
    return fComponent;
}

-(jint)index {
    return fIndex;
}

- (void)setParent:(id)javaBaseAccessibilityParent { 
    fParent = javaBaseAccessibilityParent;
}

- (NSString *)nsRole {
    return fNSRole;
}

- (NSUInteger)accessibilityIndexOfChild:(id)child {

    if ([child isKindOfClass:[PlatformAxElement class]]) {
        child = [child javaBase];
    }
    jint returnValue = JNFCallStaticIntMethod( [ThreadUtilities getJNIEnv],
                                sjm_getAccessibleIndexInParent,
                                [child accessible],
                                [child component]);
    return (returnValue == -1) ? NSNotFound : returnValue;
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
[ThreadUtilities performOnMainThread:@selector(postFocusChanged:) on:[JavaBaseAccessibility class] withObject:nil waitUntilDone:NO];
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
[ThreadUtilities performOnMainThread:@selector(postValueChanged) on:(JavaBaseAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
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
[ThreadUtilities performOnMainThread:@selector(postSelectedTextChanged)
on:(JavaBaseAccessibility *)jlong_to_ptr(element)
withObject:nil
        waitUntilDone:NO];
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
[ThreadUtilities performOnMainThread:@selector(postSelectionChanged) on:(JavaBaseAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
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
[ThreadUtilities performOnMainThread:@selector(postMenuOpened)
on:(JavaBaseAccessibility *)jlong_to_ptr(element)
withObject:nil
        waitUntilDone:NO];
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
[ThreadUtilities performOnMainThread:@selector(postMenuClosed)
on:(JavaBaseAccessibility *)jlong_to_ptr(element)
withObject:nil
        waitUntilDone:NO];
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
[ThreadUtilities performOnMainThread:@selector(postMenuItemSelected)
on:(JavaBaseAccessibility *)jlong_to_ptr(element)
withObject:nil
        waitUntilDone:NO];
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
[ThreadUtilities performOnMainThread:@selector(unregisterFromCocoaAXSystem) on:(JavaBaseAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}
