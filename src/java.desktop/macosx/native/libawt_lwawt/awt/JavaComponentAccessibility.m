// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComponentAccessibility.h"

#import "sun_lwawt_macosx_CAccessibility.h"

#import <AppKit/AppKit.h>

#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import <JavaRuntimeSupport/JavaRuntimeSupport.h>

#import <dlfcn.h>

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
#import "ThreadUtilities.h"
#import "AWTView.h"

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

static JNF_MEMBER_CACHE(jm_getAccessibleContext, sjc_CAccessible, "getAccessibleContext", "()Ljavax/accessibility/AccessibleContext;");

static jobject sAccessibilityClass = NULL;

static void RaiseMustOverrideException(NSString *method)
{
    @throw [NSException exceptionWithName:NSInternalInconsistencyException
                                   reason:[NSString stringWithFormat:@"You must override %@ in a subclass", method]
                                 userInfo:nil];
};

@implementation JavaComponentAccessibility

- (NSString *)getPlatformAxElementClassName
{
    return @"PlatformAxElement";
}

@synthesize platformAxElement;

- (id)init {
    self = [super init];
    if (self) {
        NSString *className = [self getPlatformAxElementClassName];
        if (className == nil) className = @"PlatformAxElement";
        self.platformAxElement = [[NSClassFromString(className) alloc] init];
        self.platformAxElement.javaComponent = self;
    }
    return self;
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

    [fActions release];
    fActions = nil;

    [fActionSElectors release];
    fActionSElectors = nil;

    [fActionsLOCK release];

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
    JavaComponentAccessibility *accessibility = (JavaComponentAccessibility *)anObject;

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
    if (sActions == nil) {
        initializeActions();
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

+ (NSArray *) childrenOfParent:(JavaComponentAccessibility *)parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored
{
    return [JavaComponentAccessibility childrenOfParent:parent withEnv:env withChildrenCode:whichChildren allowIgnored:allowIgnored recursive:NO];
}

+ (NSArray *) childrenOfParent:(JavaComponentAccessibility *)parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored recursive:(BOOL)recursive
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

        JavaComponentAccessibility *child = [self createWithParent:parent accessible:jchild role:childJavaRole index:childIndex withEnv:env withView:parent->fView];
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

+ (JavaComponentAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [JavaComponentAccessibility createWithAccessible:jaccessible withEnv:env withView:view isCurrent:NO];
}

+ (JavaComponentAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view isCurrent:(BOOL)current
{
    JavaComponentAccessibility *ret = nil;
    jobject jcomponent = [(AWTView *)view awtComponent:env];
    jint index = JNFCallStaticIntMethod(env, sjm_getAccessibleIndexInParent, jaccessible, jcomponent);
    NSString *javaRole = getJavaRole(env, jaccessible, jcomponent);
    if ((index >= 0) || current) {
        ret = [self createWithAccessible:jaccessible role:javaRole index:index withEnv:env withView:view];
    }
    (*env)->DeleteLocalRef(env, jcomponent);
    return ret;
}

+ (JavaComponentAccessibility *) createWithAccessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [self createWithParent:nil accessible:jaccessible role:javaRole index:index withEnv:env withView:view];
}

+ (JavaComponentAccessibility *) createWithParent:(JavaComponentAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [JavaComponentAccessibility createWithParent:parent accessible:jaccessible role:javaRole index:index withEnv:env withView:view isWrapped:NO];
}

+ (JavaComponentAccessibility *) createWithParent:(JavaComponentAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view isWrapped:(BOOL)wrapped
{
    // try to fetch the jCAX from Java, and return autoreleased
    jobject jCAX = [JavaComponentAccessibility getCAccessible:jaccessible withEnv:env];
    if (jCAX == NULL) return nil;
    if (!wrapped) { // If wrapped is true, then you don't need to get an existing instance, you need to create a new one
    JavaComponentAccessibility *value = (JavaComponentAccessibility *) jlong_to_ptr(JNFGetLongField(env, jCAX, jf_ptr));
    if (value != nil) {
        (*env)->DeleteLocalRef(env, jCAX);
        return [[value retain] autorelease];
    }
    }

    // otherwise, create a new instance
    JavaComponentAccessibility *newChild = nil;
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
                fParent = [JavaComponentAccessibility createWithAccessible:jparent withEnv:env withView:view];
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
    id role = [self accessibleRole];
    return [role isEqualToString:NSAccessibilityMenuBarRole] || [role isEqualToString:NSAccessibilityMenuRole] || [role isEqualToString:NSAccessibilityMenuItemRole];
}

- (BOOL)isSelected:(JNIEnv *)env
{
    if (fIndex == -1) {
        return NO;
    }

    return isChildSelected(env, ((JavaComponentAccessibility *)[self parent])->fAccessible, fIndex, fComponent);
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
            value = ((JavaComponentAccessibility *)value).platformAxElement;
        }
        (*env)->DeleteLocalRef(env, focused);
    }

    if (value == nil) {
        value = [self platformAxElement];
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

- (void)setParent:(id)javaComponentAccessibilityParent {
    fParent = javaComponentAccessibilityParent;
}

- (NSString *)nsRole {
    return fNSRole;
}

- (NSUInteger)accessibilityIndexOfChild:(id)child {

    if ([child isKindOfClass:[PlatformAxElement class]]) {
        child = [child javaComponent];
    }
    jint returnValue = JNFCallStaticIntMethod( [ThreadUtilities getJNIEnv],
                                sjm_getAccessibleIndexInParent,
                                [child accessible],
                                [child component]);
    return (returnValue == -1) ? NSNotFound : returnValue;
}

- (int)accessibleIndexOfParent {
    return (int)JNFCallStaticIntMethod    ([ThreadUtilities getJNIEnv], sjm_getAccessibleIndexInParent, fAccessible, fComponent);
}

- (BOOL)accessibleEnabled {
    static JNF_STATIC_MEMBER_CACHE(jm_isEnabled, sjc_CAccessibility, "isEnabled", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Z");

    JNIEnv* env = [ThreadUtilities getJNIEnv];
    NSNumber *value = [NSNumber numberWithBool:JNFCallStaticBooleanMethod(env, jm_isEnabled, fAccessible, fComponent)]; // AWT_THREADING Safe (AWTRunLoop)
    if (value == nil) {
        NSLog(@"WARNING: %s called on component that has no accessible component: %@", __FUNCTION__, self);
        return NO;
    }
    return [value boolValue];
}

- (BOOL)accessibleFocused {
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    // According to javadoc, a component that is focusable will return true from isFocusTraversable,
    // as well as having AccessibleState.FOCUSABLE in its AccessibleStateSet.
    // We use the former heuristic; if the component focus-traversable, add a focused attribute
    // See also initializeAttributeNamesWithEnv:
    if (JNFCallStaticBooleanMethod(env, sjm_isFocusTraversable, fAccessible, fComponent)) { // AWT_THREADING Safe (AWTRunLoop)
        return YES;
    }

    return NO;
}

- (void)setAccessibleFocused:(BOOL)accessibleFocused {
    static JNF_STATIC_MEMBER_CACHE(jm_requestFocus, sjc_CAccessibility, "requestFocus", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)V");

    if (accessibleFocused) {
        JNIEnv* env = [ThreadUtilities getJNIEnv];
        JNFCallStaticVoidMethod(env, jm_requestFocus, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    }
}

- (NSNumber *)accessibleMaxValue {
    static JNF_STATIC_MEMBER_CACHE(jm_getMaximumAccessibleValue, sjc_CAccessibility, "getMaximumAccessibleValue", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/Number;");

    JNIEnv* env = [ThreadUtilities getJNIEnv];

    jobject axValue = JNFCallStaticObjectMethod(env, jm_getMaximumAccessibleValue, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axValue == NULL) {
        return [NSNumber numberWithInt:0];
    }
    NSNumber* num = JNFJavaToNSNumber(env, axValue);
    (*env)->DeleteLocalRef(env, axValue);
    return num;
}

- (NSNumber *)accessibleMinValue {
    static JNF_STATIC_MEMBER_CACHE(jm_getMinimumAccessibleValue, sjc_CAccessibility, "getMinimumAccessibleValue", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/Number;");

    JNIEnv* env = [ThreadUtilities getJNIEnv];

    jobject axValue = JNFCallStaticObjectMethod(env, jm_getMinimumAccessibleValue, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axValue == NULL) {
        return [NSNumber numberWithInt:0];
    }
    NSNumber* num = JNFJavaToNSNumber(env, axValue);
    (*env)->DeleteLocalRef(env, axValue);
    return num;
}

- (id)accessibleOrientation {
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];

    // cmcnote - should batch these two calls into one that returns an array of two bools, one for vertical and one for horiz
    if (isVertical(env, axContext, fComponent)) {
        (*env)->DeleteLocalRef(env, axContext);
        return NSAccessibilityVerticalOrientationValue;
    }

    if (isHorizontal(env, axContext, fComponent)) {
        (*env)->DeleteLocalRef(env, axContext);
        return NSAccessibilityHorizontalOrientationValue;
    }

    (*env)->DeleteLocalRef(env, axContext);
    return nil;
}

- (NSValue *)accessiblePosition {
    NSRect bounds = [self getBounds];
    return [NSValue valueWithPoint:NSMakePoint(bounds.origin.x, bounds.origin.y)];
}

- (NSString *)accessibleRole {
    if (fNSRole == nil) {
        NSString *javaRole = [self javaRole];
        fNSRole = [sRoles objectForKey:javaRole];
        // The sRoles NSMutableDictionary maps popupmenu to Mac's popup button.
        // JComboBox behavior currently relies on this.  However this is not the
        // proper mapping for a JPopupMenu so fix that.
        if ([[self parent] isKindOfClass:[JavaComponentAccessibility class]] &&
            [javaRole isEqualToString:@"popupmenu"] &&
             ![[[self parent] javaRole] isEqualToString:@"combobox"] ) {
             fNSRole = NSAccessibilityMenuRole;
        }
        if (fNSRole == nil) {
            // this component has assigned itself a custom AccessibleRole not in the sRoles array
            fNSRole = javaRole;
        }
        [fNSRole retain];
    }
    return fNSRole;
}

- (NSString *)accessibleRoleDescription {
    // first ask AppKit for its accessible role description for a given AXRole
    NSString *value = NSAccessibilityRoleDescription([self accessibleRole], nil);

    if (value == nil) {
        // query java if necessary
        static JNF_STATIC_MEMBER_CACHE(jm_getAccessibleRoleDisplayString, sjc_CAccessibility, "getAccessibleRoleDisplayString", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");

        JNIEnv* env = [ThreadUtilities getJNIEnv];

        jobject axRole = JNFCallStaticObjectMethod(env, jm_getAccessibleRoleDisplayString, fAccessible, fComponent);
        if (axRole != NULL) {
            value = JNFJavaToNSString(env, axRole);
            (*env)->DeleteLocalRef(env, axRole);
        } else {
            value = @"unknown";
        }
    }

    return value;
}

- (id)accessibleParent {
    id parent = [self parent];
    if ([parent isKindOfClass:[JavaComponentAccessibility class]]) {
        return NSAccessibilityUnignoredAncestor(((JavaComponentAccessibility *)parent).platformAxElement);
    }
    return (id)fView;
}

- (NSNumber *)accessibleSelected {
    return [NSNumber numberWithBool:[self isSelected:[ThreadUtilities getJNIEnv]]];
}

- (void)setAccessibleSelected:(NSNumber *)accessibleSelected {
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    if ([self isSelectable:env]) {
        static JNF_STATIC_MEMBER_CACHE( jm_requestSelection,
                                       sjc_CAccessibility,
                                       "requestSelection",
                                       "(Ljavax/accessibility/Accessible;Ljava/awt/Component;Z)V");

        JNFCallStaticVoidMethod(env, jm_requestSelection, fAccessible, fComponent, [accessibleSelected boolValue]); // AWT_THREADING Safe (AWTRunLoop)
    }
}

- (id)accessibleValue {
    static JNF_STATIC_MEMBER_CACHE(jm_getCurrentAccessibleValue, sjc_CAccessibility, "getCurrentAccessibleValue", "(Ljavax/accessibility/AccessibleValue;Ljava/awt/Component;)Ljava/lang/Number;");
    JNIEnv* env = [ThreadUtilities getJNIEnv];

    // Need to handle popupmenus differently.
    //
    // At least for now don't handle combo box menus.
    // This may change when later fixing issues which currently
    // exist for combo boxes, but for now the following is only
    // for JPopupMenus, not for combobox menus.
    id parent = [self parent];
    if ( [[self javaRole] isEqualToString:@"popupmenu"] &&
         ![[parent javaRole] isEqualToString:@"combobox"] ) {
        NSArray *children =
            [JavaComponentAccessibility childrenOfParent:self
                                        withEnv:env
                                        withChildrenCode:JAVA_AX_ALL_CHILDREN
                                        allowIgnored:YES];
        if ([children count] > 0) {
            // handle case of AXMenuItem
            // need to ask menu what is selected
            NSArray *selectedChildrenOfMenu =
                [(PlatformAxElement *)[self platformAxElement] accessibilitySelectedChildren];
            JavaComponentAccessibility *selectedMenuItem =
                    [[selectedChildrenOfMenu objectAtIndex:0] javaComponent];
            if (selectedMenuItem != nil) {
                jobject itemValue =
                    JNFCallStaticObjectMethod( env,
                                                   sjm_getAccessibleName,
                                                   selectedMenuItem->fAccessible,
                                                   selectedMenuItem->fComponent ); // AWT_THREADING Safe (AWTRunLoop)
                if (itemValue == NULL) {
                    return nil;
                }
                NSString* itemString = JNFJavaToNSString(env, itemValue);
                (*env)->DeleteLocalRef(env, itemValue);
                return itemString;
            } else {
                return nil;
            }
        }
    }

    // ask Java for the component's accessibleValue. In java, the "accessibleValue" just means a numerical value
    // a text value is taken care of in JavaTextAccessibility

    // cmcnote should coalesce these calls into one java call
    NSNumber *num = nil;
    jobject axValue = JNFCallStaticObjectMethod(env, sjm_getAccessibleValue, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axValue != NULL) {
        jobject str = JNFCallStaticObjectMethod(env, jm_getCurrentAccessibleValue, axValue, fComponent);
        if (str != NULL) {
            num = JNFJavaToNSNumber(env, str); // AWT_THREADING Safe (AWTRunLoop)
            (*env)->DeleteLocalRef(env, str);
        }
        (*env)->DeleteLocalRef(env, axValue);
    }
    if (num == nil) {
        num = [NSNumber numberWithInt:0];
    }
    return num;
}

- (id)accessibleHitTest:(NSPoint)point {
    JNIEnv* env = [ThreadUtilities getJNIEnv];

    static JNF_CLASS_CACHE(jc_Container, "java/awt/Container");
    static JNF_STATIC_MEMBER_CACHE(jm_accessibilityHitTest, sjc_CAccessibility, "accessibilityHitTest", "(Ljava/awt/Container;FF)Ljavax/accessibility/Accessible;");

    // Make it into java screen coords
    point.y = [[[[self view] window] screen] frame].size.height - point.y;

    jobject jparent = fComponent;

    id value = nil;
    if (JNFIsInstanceOf(env, jparent, &jc_Container)) {
        jobject jaccessible = JNFCallStaticObjectMethod(env, jm_accessibilityHitTest, jparent, (jfloat)point.x, (jfloat)point.y); // AWT_THREADING Safe (AWTRunLoop)
        if (jaccessible != NULL) {
            value = [JavaComponentAccessibility createWithAccessible:jaccessible withEnv:env withView:fView];
            value = ((JavaComponentAccessibility *)value).platformAxElement;
            (*env)->DeleteLocalRef(env, jaccessible);
        }
    }

    if (value == nil) {
        value = [self platformAxElement];
    }

    if (![value isAccessibilityElement]) {
        value = NSAccessibilityUnignoredAncestor(value);
    }

#ifdef JAVA_AX_DEBUG
    NSLog(@"%s: %@", __FUNCTION__, value);
#endif
    return value;
}

- (void)getActionsWithEnv:(JNIEnv *)env {
    static JNF_STATIC_MEMBER_CACHE(jm_getAccessibleAction, sjc_CAccessibility, "getAccessibleAction", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleAction;");
    
    jobject axAction = JNFCallStaticObjectMethod(env, jm_getAccessibleAction, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axAction != NULL) {
        JNF_CLASS_CACHE(jc_AccessibleAction, "javax/accessibility/AccessibleAction");
        JNF_MEMBER_CACHE(jm_getAccessibleActionCount, jc_AccessibleAction, "getAccessibleActionCount", "()I");
        jint count = JNFCallObjectMethod(env, axAction, jm_getAccessibleActionCount);
        fActions = [[NSMutableDictionary alloc] initWithCapacity:count];
        fActionSElectors = [[NSMutableArray alloc] initWithCapacity:count];
        for (int i =0; i < count; i++) {
            JavaAxAction *action = [[JavaAxAction alloc] initWithEnv:env withAccessibleAction:axAction withIndex:i withComponent:fComponent];
            if ([fParent isKindOfClass:[JavaComponentAccessibility class]] &&
                [fParent isMenu] &&
                [[sActions objectForKey:[action getDescription]] isEqualToString:NSAccessibilityPressAction]) {
                [fActions setObject:action forKey:NSAccessibilityPickAction];
                [fActionSElectors addObject:[sActionSelectores objectForKey:NSAccessibilityPickAction]];
            } else {
                [fActions setObject:action forKey:[sActions objectForKey:[action getDescription]]];
                [fActionSElectors addObject:[sActionSelectores objectForKey:[sActions objectForKey:[action getDescription]]]];
            }
            [action release];
        }
        (*env)->DeleteLocalRef(env, axAction);
    }
}

- (NSMutableDictionary *)getActions {
    @synchronized(fActionsLOCK) {
        if (fActions == nil) {
            [self getActionsWithEnv:[ThreadUtilities getJNIEnv]];
        }
    }

    return fActions;
}

- (BOOL)accessiblePerformAction:(NSAccessibilityActionName)actionName {
    NSMutableDictionary *currentAction = [self getActions];
    if (currentAction == nil) {
        return NO;
    }
    if ([[currentAction allKeys] containsObject:actionName]) {
        [(JavaAxAction *)[[self getActions] objectForKey:actionName] perform];
        return YES;;
    }
    return NO;
}

- (NSArray *)actionSelectores {
    @synchronized(fActionsLOCK) {
        if (fActionSElectors == nil) {
            [self getActionsWithEnv:[ThreadUtilities getJNIEnv]];
        }
    }

    return [NSArray arrayWithArray:fActionSElectors];
}

@end

@implementation PlatformAxElement

@synthesize javaComponent;

- (BOOL)isAccessibilityElement
{
    return ![[self accessibilityRole] isEqualToString:JavaAccessibilityIgnore];
}

- (NSString *)accessibilityLabel
{
    //   RaiseMustOverrideException(@"accessibilityLabel");
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axName = JNFCallStaticObjectMethod(env, sjm_getAccessibleName, [[self javaComponent] accessible], [[self javaComponent] component]);
    NSString* str = JNFJavaToNSString(env, axName);
    (*env)->DeleteLocalRef(env, axName);
    return str;
}

- (NSString *)accessibilityHelp {
    //   RaiseMustOverrideException(@"accessibilityLabel");
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axName = JNFCallStaticObjectMethod(env, sjm_getAccessibleDescription, [[self javaComponent] accessible], [[self javaComponent] component]);
    NSString* str = JNFJavaToNSString(env, axName);
    (*env)->DeleteLocalRef(env, axName);
    return str;
}

- (NSArray *)accessibilityChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaComponentAccessibility childrenOfParent:self.javaComponent
                                                        withEnv:env
                                               withChildrenCode:JAVA_AX_ALL_CHILDREN
                                                   allowIgnored:([[self accessibilityRole] isEqualToString:NSAccessibilityListRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityTableRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole])
                                                      recursive:[[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole]];
    if ([children count] > 0) {
        return children;
    }
    return nil;
}

- (NSArray *)accessibilitySelectedChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *selectedChildren = [JavaComponentAccessibility childrenOfParent:self.javaComponent
                                                                withEnv:env
                                                       withChildrenCode:JAVA_AX_SELECTED_CHILDREN
                                                           allowIgnored:([[self accessibilityRole] isEqualToString:NSAccessibilityListRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityTableRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole])
                                                              recursive:[[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole]];
    if ([selectedChildren count] > 0) {
        return selectedChildren;
    }
    return nil;
}

- (NSArray *)accessibilityVisibleChildren {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaComponentAccessibility childrenOfParent:self.javaComponent
                                                        withEnv:env
                                               withChildrenCode:JAVA_AX_VISIBLE_CHILDREN
                                                   allowIgnored:([[self accessibilityRole] isEqualToString:NSAccessibilityListRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityTableRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole])
                                                      recursive:[[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole]];
    if ([children count] > 0) {
        return children;
    }
    return nil;
}

- (NSRect)accessibilityFrame
{
    return [self.javaComponent getBounds];
}

- (id)accessibilityParent
{
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleParent];
}

- (BOOL)isAccessibilityEnabled
{
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleEnabled];
}

- (id)accessibilityApplicationFocusedUIElement {
    return [self.javaComponent getFocusedElement];
}

- (id)accessibilityWindow {
    return [self.javaComponent window];
}

- (void)setAccessibilityParent:(id)accessibilityParent {
    [[self javaComponent] setParent:accessibilityParent];
}

- (NSUInteger)accessibilityIndexOfChild:(id)child {
    return [[self javaComponent] accessibilityIndexOfChild:child];
}

- (NSAccessibilityRole)accessibilityRole {
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleRole];
}

- (NSString *)accessibilityRoleDescription {
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleRoleDescription];
}

- (BOOL)isAccessibilityFocused {
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleFocused];
}

- (void)setAccessibilityFocused:(BOOL)accessibilityFocused {
    [(JavaComponentAccessibility *) [self javaComponent] setAccessibleFocused:accessibilityFocused];
}

- (NSInteger)accessibilityIndex {
    return [[self javaComponent] index];
}

- (id)accessibilityMaxValue {
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleMaxValue];
}

- (id)accessibilityMinValue {
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleMinValue];
}

- (NSAccessibilityOrientation)accessibilityOrientation {
    return (NSAccessibilityOrientation)[(JavaComponentAccessibility *) [self javaComponent] accessibleOrientation];
}

- (NSPoint)accessibilityActivationPoint {
    return [[(JavaComponentAccessibility *) [self javaComponent] accessiblePosition] pointValue];
}

- (BOOL)isAccessibilitySelected {
    return [[(JavaComponentAccessibility *) [self javaComponent] accessibleSelected] boolValue];
}

- (void)setAccessibilitySelected:(BOOL)accessibilitySelected {
    [(JavaComponentAccessibility *) [self javaComponent] setAccessibleSelected:[NSNumber numberWithBool:accessibilitySelected]];
}

- (id)accessibilityValue {
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleValue];
}

- (id)accessibilityHitTest:(NSPoint)point {
    return [(JavaComponentAccessibility *) [self javaComponent] accessibleHitTest:point];
}

- (BOOL)isAccessibilitySelectorAllowed:(SEL)selector {
    if ([sAllActionSelectores containsObject:NSStringFromSelector(selector)] &&
        ![[(JavaComponentAccessibility *) [self javaComponent] actionSelectores] containsObject:NSStringFromSelector(selector)]) {
        return NO;
    }
    return [super isAccessibilitySelectorAllowed:selector];
}

- (BOOL)accessibilityPerformPick {
    return [(JavaComponentAccessibility *) [self javaComponent] accessiblePerformAction:NSAccessibilityPickAction];
}

- (BOOL)accessibilityPerformPress {
    return [(JavaComponentAccessibility *) [self javaComponent] accessiblePerformAction:NSAccessibilityPressAction];
}

- (BOOL)accessibilityPerformShowMenu {
    return [(JavaComponentAccessibility *) [self javaComponent] accessiblePerformAction:NSAccessibilityShowMenuAction];
}

- (BOOL)accessibilityPerformDecrement {
    return [(JavaComponentAccessibility *) [self javaComponent] accessiblePerformAction:NSAccessibilityDecrementAction];
}

- (BOOL)accessibilityPerformIncrement {
    return [(JavaComponentAccessibility *) [self javaComponent] accessiblePerformAction:NSAccessibilityIncrementAction];
}

- (id)accessibilityFocusedUIElement {
    return [[self javaComponent] getFocusedElement];
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
[ThreadUtilities performOnMainThread:@selector(postFocusChanged:) on:[JavaComponentAccessibility class] withObject:nil waitUntilDone:NO];
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
[ThreadUtilities performOnMainThread:@selector(postValueChanged) on:(JavaComponentAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
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
on:(JavaComponentAccessibility *)jlong_to_ptr(element)
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
[ThreadUtilities performOnMainThread:@selector(postSelectionChanged) on:(JavaComponentAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
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
on:(JavaComponentAccessibility *)jlong_to_ptr(element)
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
on:(JavaComponentAccessibility *)jlong_to_ptr(element)
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
on:(JavaComponentAccessibility *)jlong_to_ptr(element)
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
[ThreadUtilities performOnMainThread:@selector(unregisterFromCocoaAXSystem) on:(JavaComponentAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNF_COCOA_EXIT(env);
}
