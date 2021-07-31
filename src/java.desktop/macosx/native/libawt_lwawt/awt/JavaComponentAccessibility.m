// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComponentAccessibility.h"

#import "sun_lwawt_macosx_CAccessibility.h"

#import <AppKit/AppKit.h>

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
#import "JNIUtilities.h"
#import "AWTView.h"

// GET* macros defined in JavaAccessibilityUtilities.h, so they can be shared.
static jclass sjc_CAccessibility = NULL;

static jmethodID sjm_getAccessibleName = NULL;
#define GET_ACCESSIBLENAME_METHOD_RETURN(ret) \
    GET_CACCESSIBILITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", \
                     "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;", ret);

static jmethodID jm_getChildrenAndRoles = NULL;
#define GET_CHILDRENANDROLES_METHOD_RETURN(ret) \
    GET_CACCESSIBILITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles",\
                      "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;", ret);

static jmethodID sjm_getAccessibleComponent = NULL;
#define GET_ACCESSIBLECOMPONENT_STATIC_METHOD_RETURN(ret) \
    GET_CACCESSIBILITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(sjm_getAccessibleComponent, sjc_CAccessibility, "getAccessibleComponent", \
           "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleComponent;", ret);

static jmethodID sjm_getAccessibleIndexInParent = NULL;
#define GET_ACCESSIBLEINDEXINPARENT_STATIC_METHOD_RETURN(ret) \
    GET_CACCESSIBILITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(sjm_getAccessibleIndexInParent, sjc_CAccessibility, "getAccessibleIndexInParent", \
                             "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)I", ret);

static jclass sjc_CAccessible = NULL;
#define GET_CACCESSIBLE_CLASS_RETURN(ret) \
    GET_CLASS_RETURN(sjc_CAccessible, "sun/lwawt/macosx/CAccessible", ret);


static jobject sAccessibilityClass = NULL;

NSMutableArray *sJavaComponentAccessibilityPtrs = nil; // a list of pointers to allocated JavaComponentAccessibility objects

static void RaiseMustOverrideException(NSString *method)
{
    @throw [NSException exceptionWithName:NSInternalInconsistencyException
                                   reason:[NSString stringWithFormat:@"You must override %@ in a subclass", method]
                                 userInfo:nil];
};

@implementation JavaComponentAccessibility

- (instancetype)init
{
    if (self = [super init]) {
        if (sJavaComponentAccessibilityPtrs == nil) {
            sJavaComponentAccessibilityPtrs = [[NSMutableArray alloc] init];
            [sJavaComponentAccessibilityPtrs retain]; // per-process persistent
        }
        [sJavaComponentAccessibilityPtrs addObject:[NSNumber numberWithLong:(jlong)self]];
    }
    return self;
}

+ (bool) isAllocated:(long)ptr
{
    assert([NSThread isMainThread]);
    return [sJavaComponentAccessibilityPtrs containsObject:[NSNumber numberWithLong:ptr]];
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

    [sJavaComponentAccessibilityPtrs removeObject:[NSNumber numberWithLong:(jlong)self]];

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
    NSAccessibilityPostNotification(self, (NSString *)kAXMenuOpenedNotification);
}

- (void)postMenuClosed
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, (NSString *)kAXMenuClosedNotification);
}

- (void)postMenuItemSelected
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, (NSString *)kAXMenuItemSelectedNotification);
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
        JNIEnv *env = [ThreadUtilities getJNIEnv];

        GET_CACCESSIBILITY_CLASS();
        DECLARE_STATIC_METHOD(jm_getAccessibility, sjc_CAccessibility, "getAccessibility", "([Ljava/lang/String;)Lsun/lwawt/macosx/CAccessibility;");

#ifdef JAVA_AX_NO_IGNORES
        NSArray *ignoredKeys = [NSArray array];
#else
        NSArray *ignoredKeys = [sRoles allKeysForObject:JavaAccessibilityIgnore];
#endif
        jobjectArray result = NULL;
        jsize count = [ignoredKeys count];

        DECLARE_CLASS(jc_String, "java/lang/String");
        result = (*env)->NewObjectArray(env, count, jc_String, NULL);
        CHECK_EXCEPTION();
        if (!result) {
            NSLog(@"In %s, can't create Java array of String objects", __FUNCTION__);
            return;
        }

        NSInteger i;
        for (i = 0; i < count; i++) {
            jstring jString = NSStringToJavaString(env, [ignoredKeys objectAtIndex:i]);
            (*env)->SetObjectArrayElement(env, result, i, jString);
            (*env)->DeleteLocalRef(env, jString);
        }

        sAccessibilityClass = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getAccessibility, result); // AWT_THREADING Safe (known object)
        CHECK_EXCEPTION();
    }
}

+ (void)postFocusChanged:(id)message
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification([NSApp accessibilityFocusedUIElement], NSAccessibilityFocusedUIElementChangedNotification);
}

+ (jobject) getCAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env {
    JNI_COCOA_DURING(env);
    DECLARE_CLASS_RETURN(sjc_Accessible, "javax/accessibility/Accessible", NULL);
    GET_CACCESSIBLE_CLASS_RETURN(NULL);
    DECLARE_STATIC_METHOD_RETURN(sjm_getCAccessible, sjc_CAccessible, "getCAccessible",
                                "(Ljavax/accessibility/Accessible;)Lsun/lwawt/macosx/CAccessible;", NULL);
    if ((*env)->IsInstanceOf(env, jaccessible, sjc_CAccessible)) {
        return jaccessible;
    } else if ((*env)->IsInstanceOf(env, jaccessible, sjc_Accessible)) {
        jobject o = (*env)->CallStaticObjectMethod(env, sjc_CAccessible,  sjm_getCAccessible, jaccessible);
        CHECK_EXCEPTION();
        return o;
    }
    JNI_COCOA_HANDLE(env);
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
                                                                         withJavaRole:JavaAccessibilityIgnore]];
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
                                                                         withJavaRole:JavaAccessibilityIgnore]];
            }
            return [NSArray arrayWithArray:children];
        } else {
            return [NSArray arrayWithObject:[[JavaTableRowAccessibility alloc] initWithParent:parent
                                                                                      withEnv:env
                                                                               withAccessible:NULL
                                                                                    withIndex:whichChildren
                                                                                     withView:[parent view]
                                                                                 withJavaRole:JavaAccessibilityIgnore]];
        }
    }
    if (parent->fAccessible == NULL) return nil;
    jobjectArray jchildrenAndRoles = NULL;
    if (recursive) {
        GET_CACCESSIBILITY_CLASS_RETURN(nil);
        DECLARE_STATIC_METHOD_RETURN(jm_getChildrenAndRolesRecursive, sjc_CAccessibility, "getChildrenAndRolesRecursive",
                                     "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZI)[Ljava/lang/Object;", nil);
        jchildrenAndRoles = (jobjectArray)(*env)->CallStaticObjectMethod(env, sjc_CAccessibility,
                              parent->fAccessible, parent->fComponent, whichChildren, allowIgnored, 1);
    } else {
        GET_CHILDRENANDROLES_METHOD_RETURN(nil);
        jchildrenAndRoles = (jobjectArray)(*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getChildrenAndRoles,
                      parent->fAccessible, parent->fComponent, whichChildren, allowIgnored);
    }
    CHECK_EXCEPTION();
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
            DECLARE_CLASS_RETURN(sjc_AccessibleRole, "javax/accessibility/AccessibleRole", nil);
            DECLARE_FIELD_RETURN(sjf_key, sjc_AccessibleRole, "key", "Ljava/lang/String;", nil);
            jobject jkey = (*env)->GetObjectField(env, jchildJavaRole, sjf_key);
            CHECK_EXCEPTION();
            childJavaRole = JavaStringToNSString(env, jkey);
            (*env)->DeleteLocalRef(env, jkey);
        }

        JavaComponentAccessibility *child = [self createWithParent:parent accessible:jchild role:childJavaRole index:childIndex withEnv:env withView:parent->fView];
        if ([child isKindOfClass:[JavaOutlineRowAccessibility class]]) {
            jobject jLevel = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i+2);
            NSString *sLevel = nil;
            if (jLevel != NULL) {
                sLevel = JavaStringToNSString(env, jLevel);
                (*env)->DeleteLocalRef(env, jLevel);
                int level = sLevel.intValue;
                [(JavaOutlineRowAccessibility *)child setAccessibleLevel:level];
            }
        }

        [children addObject:child];

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
    GET_ACCESSIBLEINDEXINPARENT_STATIC_METHOD_RETURN(nil);
    JavaComponentAccessibility *ret = nil;
    jobject jcomponent = [(AWTView *)view awtComponent:env];
    JNI_COCOA_DURING(env);
    jint index = (*env)->CallStaticIntMethod(env, sjc_CAccessibility, sjm_getAccessibleIndexInParent, jaccessible, jcomponent);
    CHECK_EXCEPTION();
    NSString *javaRole = getJavaRole(env, jaccessible, jcomponent);
    if ((index >= 0) || current) {
        ret = [self createWithAccessible:jaccessible role:javaRole index:index withEnv:env withView:view];
    }
    JNI_COCOA_HANDLE(env);
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
    GET_CACCESSIBLE_CLASS_RETURN(NULL);
    DECLARE_FIELD_RETURN(jf_ptr, sjc_CAccessible, "ptr", "J", NULL);
    // try to fetch the jCAX from Java, and return autoreleased
    jobject jCAX = [JavaComponentAccessibility getCAccessible:jaccessible withEnv:env];
    if (jCAX == NULL) return nil;
    if (!wrapped) { // If wrapped is true, then you don't need to get an existing instance, you need to create a new one
    JavaComponentAccessibility *value = (JavaComponentAccessibility *) jlong_to_ptr((*env)->GetLongField(env, jCAX, jf_ptr));
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
    (*env)->SetLongField(env, jCAX, jf_ptr, ptr_to_jlong(newChild));

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
    if(fParent == nil) {
        JNIEnv* env = [ThreadUtilities getJNIEnv];
        GET_CACCESSIBILITY_CLASS_RETURN(nil);
        DECLARE_STATIC_METHOD_RETURN(sjm_getAccessibleParent, sjc_CAccessibility, "getAccessibleParent",
                                 "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/Accessible;", nil);
        GET_CACCESSIBLE_CLASS_RETURN(nil);
        DECLARE_STATIC_METHOD_RETURN(sjm_getSwingAccessible, sjc_CAccessible, "getSwingAccessible",
                                 "(Ljavax/accessibility/Accessible;)Ljavax/accessibility/Accessible;", nil);
        DECLARE_CLASS_RETURN(sjc_Window, "java/awt/Window", nil);

        jobject jparent = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility,  sjm_getAccessibleParent, fAccessible, fComponent);
        CHECK_EXCEPTION();

        if (jparent == NULL) {
            fParent = fView;
        } else {
            AWTView *view = fView;
            jobject jax = (*env)->CallStaticObjectMethod(env, sjc_CAccessible, sjm_getSwingAccessible, fAccessible);
            CHECK_EXCEPTION();

            if ((*env)->IsInstanceOf(env, jax, sjc_Window)) {
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
    NSString *role = [self accessibilityRole];
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
    GET_ACCESSIBLECOMPONENT_STATIC_METHOD_RETURN(NSMakeSize(0, 0));
    jobject axComponent = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility,
                           sjm_getAccessibleComponent, fAccessible, fComponent);
    CHECK_EXCEPTION();
    NSSize size = getAxComponentSize(env, axComponent, fComponent);
    (*env)->DeleteLocalRef(env, axComponent);

    return size;
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

- (NSUInteger)accessibilityIndexOfChild:(id)child
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLEINDEXINPARENT_STATIC_METHOD_RETURN(0);
    jint returnValue =
        (*env)->CallStaticIntMethod( env,
                                sjc_CAccessibility,
                                sjm_getAccessibleIndexInParent,
                                [child accessible],
                                [child component]);
    CHECK_EXCEPTION();
    return (returnValue == -1) ? NSNotFound : returnValue;
}

- (int)accessibleIndexOfParent {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLEINDEXINPARENT_STATIC_METHOD_RETURN(0);
    jint returnValue =
        (*env)->CallStaticIntMethod(env, sjc_CAccessibility, sjm_getAccessibleIndexInParent, fAccessible, fComponent);
    CHECK_EXCEPTION();
    return (int) returnValue;
}

- (void)getActionsWithEnv:(JNIEnv *)env {
    GET_CACCESSIBILITY_CLASS();
    DECLARE_STATIC_METHOD(jm_getAccessibleAction, sjc_CAccessibility, "getAccessibleAction",
                           "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleAction;");

    jobject axAction = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getAccessibleAction, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axAction != NULL) {
        DECLARE_CLASS(jc_AccessibleAction, "javax/accessibility/AccessibleAction");
        DECLARE_METHOD(jm_getAccessibleActionCount, jc_AccessibleAction, "getAccessibleActionCount", "()I");
        jint count = (*env)->CallIntMethod(env, axAction, jm_getAccessibleActionCount);
        CHECK_EXCEPTION();
        fActions = [[NSMutableDictionary alloc] initWithCapacity:count];
        fActionSElectors = [[NSMutableArray alloc] initWithCapacity:count];
        for (int i =0; i < count; i++) {
            JavaAxAction *action = [[JavaAxAction alloc] initWithEnv:env withAccessibleAction:axAction withIndex:i withComponent:fComponent];
            if ([fParent isKindOfClass:[JavaComponentAccessibility class]] &&
                [(JavaComponentAccessibility *)fParent isMenu] &&
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

// NSAccessibilityElement protocol methods

- (BOOL)isAccessibilityElement
{
    return ![[self accessibilityRole] isEqualToString:JavaAccessibilityIgnore];
}

- (NSString *)accessibilityLabel
{
    //   RaiseMustOverrideException(@"accessibilityLabel");
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLENAME_METHOD_RETURN(nil);
    jobject axName = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getAccessibleName, self->fAccessible, self->fComponent);
    CHECK_EXCEPTION();

    NSString* str = JavaStringToNSString(env, axName);
    (*env)->DeleteLocalRef(env, axName);
    return str;
}

- (NSString *)accessibilityHelp {
    //   RaiseMustOverrideException(@"accessibilityLabel");
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(sjm_getAccessibleDescription, sjc_CAccessibility, "getAccessibleDescription",
                                 "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;", nil);
    jobject axName = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility,
                                   sjm_getAccessibleDescription, self->fAccessible, self->fComponent);
    CHECK_EXCEPTION();
    NSString* str = JavaStringToNSString(env, axName);
    (*env)->DeleteLocalRef(env, axName);
    return str;
}

- (NSArray *)accessibilityChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaComponentAccessibility childrenOfParent:self
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
    NSArray *selectedChildren = [JavaComponentAccessibility childrenOfParent:self
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
    NSArray *children = [JavaComponentAccessibility childrenOfParent:self
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
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLECOMPONENT_STATIC_METHOD_RETURN(NSMakeRect(0, 0, 0, 0));
    jobject axComponent = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getAccessibleComponent,
                           fAccessible, fComponent);
    CHECK_EXCEPTION();

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

- (id)accessibilityParent
{
    id parent = [self parent];
    if ([parent isKindOfClass:[JavaComponentAccessibility class]]) {
        return NSAccessibilityUnignoredAncestor(((JavaComponentAccessibility *)parent));
    }
    return (id)fView;
}

- (BOOL)isAccessibilityEnabled
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_isEnabled, sjc_CAccessibility, "isEnabled", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Z", nil);

    NSNumber *value = [NSNumber numberWithBool:(*env)->CallStaticBooleanMethod(env, sjc_CAccessibility, jm_isEnabled, fAccessible, fComponent)];
    CHECK_EXCEPTION();
    if (value == nil) {
        NSLog(@"WARNING: %s called on component that has no accessible component: %@", __FUNCTION__, self);
        return NO;
    }
    return [value boolValue];
}

- (id)accessibilityApplicationFocusedUIElement
{
    return [self accessibilityFocusedUIElement];
}

- (id)accessibilityWindow
{
    return [self window];
}

- (void)setAccessibilityParent:(id)accessibilityParent
{
    [self setParent:accessibilityParent];
}

- (NSAccessibilityRole)accessibilityRole
{
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

- (NSString *)accessibilityRoleDescription
{
    // first ask AppKit for its accessible role description for a given AXRole
    NSString *value = NSAccessibilityRoleDescription([self accessibilityRole], nil);

    if (value == nil) {
        // query java if necessary
        JNIEnv* env = [ThreadUtilities getJNIEnv];
        GET_CACCESSIBILITY_CLASS_RETURN(nil);
        DECLARE_STATIC_METHOD_RETURN(jm_getAccessibleRoleDisplayString, sjc_CAccessibility, "getAccessibleRoleDisplayString",
                                     "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;", nil);


        jobject axRole = (*env)->CallStaticObjectMethod(env, jm_getAccessibleRoleDisplayString, fAccessible, fComponent);
        CHECK_EXCEPTION();
        if (axRole != NULL) {
            value = JavaStringToNSString(env, axRole);
            (*env)->DeleteLocalRef(env, axRole);
        } else {
            value = @"unknown";
        }
    }

    return value;
}

- (BOOL)isAccessibilityFocused
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(NO);
    DECLARE_STATIC_METHOD_RETURN(sjm_isFocusTraversable, sjc_CAccessibility, "isFocusTraversable",
                                 "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Z", NO);
    // According to javadoc, a component that is focusable will return true from isFocusTraversable,
    // as well as having AccessibleState.FOCUSABLE in its AccessibleStateSet.
    // We use the former heuristic; if the component focus-traversable, add a focused attribute
    // See also initializeAttributeNamesWithEnv:
    if ((*env)->CallStaticBooleanMethod(env, sjc_CAccessibility, sjm_isFocusTraversable, fAccessible, fComponent)) {
        return YES;
    }
    CHECK_EXCEPTION();

    return NO;
}

- (void)setAccessibilityFocused:(BOOL)accessibilityFocused
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];

    GET_CACCESSIBILITY_CLASS();
    DECLARE_STATIC_METHOD(jm_requestFocus, sjc_CAccessibility, "requestFocus", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)V");

    if (accessibilityFocused) {
        (*env)->CallStaticVoidMethod(env, sjc_CAccessibility, jm_requestFocus, fAccessible, fComponent);
        CHECK_EXCEPTION();
    }
}

- (NSInteger)accessibilityIndex
{
    return [self index];
}

/*
 * The java/lang/Number concrete class could be for any of the Java primitive
 * numerical types or some other subclass.
 * All existing A11Y code uses Integer so that is what we look for first
 * But all must be able to return a double and NSNumber accepts a double,
 * so that's the fall back.
 */
static NSNumber* JavaNumberToNSNumber(JNIEnv *env, jobject jnumber) {
    if (jnumber == NULL) {
        return nil;
    }
    DECLARE_CLASS_RETURN(jnumber_Class, "java/lang/Number", nil);
    DECLARE_CLASS_RETURN(jinteger_Class, "java/lang/Integer", nil);
    DECLARE_METHOD_RETURN(jm_intValue, jnumber_Class, "intValue", "()I", nil);
    DECLARE_METHOD_RETURN(jm_doubleValue, jnumber_Class, "doubleValue", "()D", nil);
    if ((*env)->IsInstanceOf(env, jnumber, jinteger_Class)) {
        jint i = (*env)->CallIntMethod(env, jnumber, jm_intValue);
        CHECK_EXCEPTION();
        return [NSNumber numberWithInteger:i];
    } else {
        jdouble d = (*env)->CallDoubleMethod(env, jnumber, jm_doubleValue);
        CHECK_EXCEPTION();
        return [NSNumber numberWithDouble:d];
    }
}

- (id)accessibilityMaxValue
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getMaximumAccessibleValue, sjc_CAccessibility, "getMaximumAccessibleValue",
                                  "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/Number;", nil);

    jobject axValue = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getMaximumAccessibleValue, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axValue == NULL) {
        return [NSNumber numberWithInt:0];
    }
    NSNumber* num = JavaNumberToNSNumber(env, axValue);
    (*env)->DeleteLocalRef(env, axValue);
    return num;
}

- (id)accessibilityMinValue
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getMinimumAccessibleValue, sjc_CAccessibility, "getMinimumAccessibleValue",
                                  "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/Number;", nil);

    jobject axValue = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getMinimumAccessibleValue, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axValue == NULL) {
        return [NSNumber numberWithInt:0];
    }
    NSNumber* num = JavaNumberToNSNumber(env, axValue);
    (*env)->DeleteLocalRef(env, axValue);
    return num;
}

- (NSAccessibilityOrientation)accessibilityOrientation
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];

    // cmcnote - should batch these two calls into one that returns an array of two bools, one for vertical and one for horiz
    if (isVertical(env, axContext, fComponent)) {
        (*env)->DeleteLocalRef(env, axContext);
        return NSAccessibilityOrientationVertical;
    }

    if (isHorizontal(env, axContext, fComponent)) {
        (*env)->DeleteLocalRef(env, axContext);
        return NSAccessibilityOrientationHorizontal;
    }

    (*env)->DeleteLocalRef(env, axContext);
    return NSAccessibilityOrientationUnknown;
}

- (NSPoint)accessibilityActivationPoint
{
    NSRect bounds = [self accessibilityFrame];
    return NSMakePoint(bounds.origin.x, bounds.origin.y);
}

- (BOOL)isAccessibilitySelected
{
    return [self isSelected:[ThreadUtilities getJNIEnv]];
}

- (void)setAccessibilitySelected:(BOOL)accessibilitySelected
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS();
    DECLARE_STATIC_METHOD(jm_requestSelection,
                          sjc_CAccessibility,
                          "requestSelection",
                          "(Ljavax/accessibility/Accessible;Ljava/awt/Component;Z)V");

    if ([self isSelectable:env]) {
        (*env)->CallStaticVoidMethod(env, sjc_CAccessibility, jm_requestSelection, fAccessible, fComponent, accessibilitySelected);
        CHECK_EXCEPTION();
    }
}

- (id)accessibilityValue
{
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
                [self accessibilitySelectedChildren];
            JavaComponentAccessibility *selectedMenuItem =
                [selectedChildrenOfMenu objectAtIndex:0];
            if (selectedMenuItem != nil) {
                GET_CACCESSIBILITY_CLASS_RETURN(nil);
                GET_ACCESSIBLENAME_METHOD_RETURN(nil);
                jobject itemValue =
                        (*env)->CallStaticObjectMethod( env,
                                                   sjm_getAccessibleName,
                                                   selectedMenuItem->fAccessible,
                                                   selectedMenuItem->fComponent );
                CHECK_EXCEPTION();
                if (itemValue == NULL) {
                    return nil;
                }
                NSString* itemString = JavaStringToNSString(env, itemValue);
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
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(sjm_getAccessibleValue, sjc_CAccessibility, "getAccessibleValue",
                "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleValue;", nil);
    jobject axValue = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getAccessibleValue, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axValue != NULL) {
        DECLARE_STATIC_METHOD_RETURN(jm_getCurrentAccessibleValue, sjc_CAccessibility, "getCurrentAccessibleValue",
                                     "(Ljavax/accessibility/AccessibleValue;Ljava/awt/Component;)Ljava/lang/Number;", nil);
        jobject str = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getCurrentAccessibleValue, axValue, fComponent);
        CHECK_EXCEPTION();
        if (str != NULL) {
            num = JavaNumberToNSNumber(env, str);
            (*env)->DeleteLocalRef(env, str);
        }
        (*env)->DeleteLocalRef(env, axValue);
    }
    if (num == nil) {
        num = [NSNumber numberWithInt:0];
    }
    return num;
}

- (id)accessibilityHitTest:(NSPoint)point
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];

    DECLARE_CLASS_RETURN(jc_Container, "java/awt/Container", nil);
    DECLARE_STATIC_METHOD_RETURN(jm_accessibilityHitTest, sjc_CAccessibility, "accessibilityHitTest",
                                 "(Ljava/awt/Container;FF)Ljavax/accessibility/Accessible;", nil);

    // Make it into java screen coords
    point.y = [[[[self view] window] screen] frame].size.height - point.y;

    jobject jparent = fComponent;

    id value = nil;
    if ((*env)->IsInstanceOf(env, jparent, jc_Container)) {
        jobject jaccessible = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_accessibilityHitTest,
                               jparent, (jfloat)point.x, (jfloat)point.y);
        CHECK_EXCEPTION();
        if (jaccessible != NULL) {
            value = [JavaComponentAccessibility createWithAccessible:jaccessible withEnv:env withView:fView];
            (*env)->DeleteLocalRef(env, jaccessible);
        }
    }

    if (value == nil) {
        value = self;
    }

    if (![value isAccessibilityElement]) {
        value = NSAccessibilityUnignoredAncestor(value);
    }

#ifdef JAVA_AX_DEBUG
    NSLog(@"%s: %@", __FUNCTION__, value);
#endif
    return value;
}

- (BOOL)isAccessibilitySelectorAllowed:(SEL)selector {
    if ([sAllActionSelectores containsObject:NSStringFromSelector(selector)] &&
        ![[self actionSelectores] containsObject:NSStringFromSelector(selector)]) {
        return NO;
    }
    return [super isAccessibilitySelectorAllowed:selector];
}

- (BOOL)accessibilityPerformPick {
    return [self accessiblePerformAction:NSAccessibilityPickAction];
}

- (BOOL)accessibilityPerformPress {
    return [self accessiblePerformAction:NSAccessibilityPressAction];
}

- (BOOL)accessibilityPerformShowMenu {
    return [self accessiblePerformAction:NSAccessibilityShowMenuAction];
}

- (BOOL)accessibilityPerformDecrement {
    return [self accessiblePerformAction:NSAccessibilityDecrementAction];
}

- (BOOL)accessibilityPerformIncrement {
    return [self accessiblePerformAction:NSAccessibilityIncrementAction];
}

- (id)accessibilityFocusedUIElement {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getFocusOwner, sjc_CAccessibility, "getFocusOwner",
                                  "(Ljava/awt/Component;)Ljavax/accessibility/Accessible;", nil);
    id value = nil;

    NSWindow* hostWindow = [[self->fView window] retain];
    jobject focused = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getFocusOwner, fComponent);
    [hostWindow release];
    CHECK_EXCEPTION();

    if (focused != NULL) {
        DECLARE_CLASS_RETURN(sjc_Accessible, "javax/accessibility/Accessible", nil);
        if ((*env)->IsInstanceOf(env, focused, sjc_Accessible)) {
            value = [JavaComponentAccessibility createWithAccessible:focused withEnv:env withView:fView];
        }
        CHECK_EXCEPTION();
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
JNI_COCOA_ENTER(env);
[ThreadUtilities performOnMainThread:@selector(postFocusChanged:) on:[JavaComponentAccessibility class] withObject:nil waitUntilDone:NO];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    valueChanged
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_valueChanged
(JNIEnv *env, jclass jklass, jlong element)
{
JNI_COCOA_ENTER(env);
[ThreadUtilities performOnMainThread:@selector(postValueChanged) on:(JavaComponentAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    selectedTextChanged
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_selectedTextChanged
(JNIEnv *env, jclass jklass, jlong element)
{
JNI_COCOA_ENTER(env);
[ThreadUtilities performOnMainThread:@selector(postSelectedTextChanged)
on:(JavaComponentAccessibility *)jlong_to_ptr(element)
withObject:nil
        waitUntilDone:NO];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    selectionChanged
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_selectionChanged
(JNIEnv *env, jclass jklass, jlong element)
{
JNI_COCOA_ENTER(env);
[ThreadUtilities performOnMainThread:@selector(postSelectionChanged) on:(JavaComponentAccessibility *)jlong_to_ptr(element) withObject:nil waitUntilDone:NO];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    menuOpened
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_menuOpened
(JNIEnv *env, jclass jklass, jlong element)
{
JNI_COCOA_ENTER(env);
[ThreadUtilities performOnMainThread:@selector(postMenuOpened)
on:(JavaComponentAccessibility *)jlong_to_ptr(element)
withObject:nil
        waitUntilDone:NO];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    menuClosed
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_menuClosed
(JNIEnv *env, jclass jklass, jlong element)
{
JNI_COCOA_ENTER(env);
[ThreadUtilities performOnMainThread:@selector(postMenuClosed)
on:(JavaComponentAccessibility *)jlong_to_ptr(element)
withObject:nil
        waitUntilDone:NO];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    menuItemSelected
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_menuItemSelected
(JNIEnv *env, jclass jklass, jlong element)
{
JNI_COCOA_ENTER(env);
[ThreadUtilities performOnMainThread:@selector(postMenuItemSelected)
on:(JavaComponentAccessibility *)jlong_to_ptr(element)
withObject:nil
        waitUntilDone:NO];
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    unregisterFromCocoaAXSystem
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_unregisterFromCocoaAXSystem
(JNIEnv *env, jclass jklass, jlong ptr)
{
JNI_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^() {
        if ([JavaComponentAccessibility isAllocated:ptr]) {
            [(JavaComponentAccessibility *)jlong_to_ptr(ptr) unregisterFromCocoaAXSystem];
        }
    }];
JNI_COCOA_EXIT(env);
}

extern void nativeCFRelease(JNIEnv *env, jlong ptr, jboolean releaseOnAppKitThread, bool (^condition)(jlong));

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    nativeCFRelease
 * Signature: (IZ)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_nativeCFRelease
(JNIEnv *env, jobject peer, jlong ptr, jboolean releaseOnAppKitThread)
{
    assert(releaseOnAppKitThread);
    nativeCFRelease(env, ptr, true, ^bool (jlong ptr) {
        return [JavaComponentAccessibility isAllocated:ptr];
    });
}
