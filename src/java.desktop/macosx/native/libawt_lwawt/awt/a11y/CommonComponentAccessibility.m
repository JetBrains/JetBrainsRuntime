/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
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

#import "CommonComponentAccessibility.h"
#import <AppKit/AppKit.h>
#import <JavaRuntimeSupport/JavaRuntimeSupport.h>
#import <dlfcn.h>
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import "AWTView.h"
#import "sun_lwawt_macosx_CAccessible.h"
#import "sun_lwawt_macosx_CAccessibility.h"
#import "sun_swing_AccessibleAnnouncer.h"

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

static jmethodID jm_getChildrenAndRolesRecursive = NULL;
#define GET_CHILDRENANDROLESRECURSIVE_METHOD_RETURN(ret) \
    GET_CACCESSIBILITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(jm_getChildrenAndRolesRecursive, sjc_CAccessibility, "getChildrenAndRolesRecursive",\
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

#define GET_CACCESSIBLE_CLASS() \
    GET_CLASS(sjc_CAccessible, "sun/lwawt/macosx/CAccessible");

static NSMutableDictionary * _Nullable rolesMap;
static NSMutableDictionary * _Nullable rowRolesMapForParent;
NSString *const IgnoreClassName = @"IgnoreAccessibility";
static jobject sAccessibilityClass = NULL;

/*
 * Common ancestor for all the accessibility peers that implements the new method-based accessibility API
 */
@implementation CommonComponentAccessibility

- (BOOL)isMenu
{
    id role = [self accessibilityRole];
    return [role isEqualToString:NSAccessibilityMenuBarRole] || [role isEqualToString:NSAccessibilityMenuRole] || [role isEqualToString:NSAccessibilityMenuItemRole];
}

- (BOOL)isSelected:(JNIEnv *)env
{
    if (fIndex == -1) {
        return NO;
    }

    CommonComponentAccessibility* parent = [self typeSafeParent];
    if (parent != nil) {
        return isChildSelected(env, parent->fAccessible, fIndex, fComponent);
    }
    return NO;
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

+ (void) initializeRolesMap {
    /*
     * Here we should keep all the mapping between the accessibility roles and implementing classes
     */
    rolesMap = [[NSMutableDictionary alloc] initWithCapacity:52];

    [rolesMap setObject:@"ButtonAccessibility" forKey:@"pushbutton"];
    [rolesMap setObject:@"ImageAccessibility" forKey:@"icon"];
    [rolesMap setObject:@"ImageAccessibility" forKey:@"desktopicon"];
    [rolesMap setObject:@"SpinboxAccessibility" forKey:@"spinbox"];
    [rolesMap setObject:@"StaticTextAccessibility" forKey:@"hyperlink"];
    [rolesMap setObject:@"StaticTextAccessibility" forKey:@"label"];
    [rolesMap setObject:@"RadiobuttonAccessibility" forKey:@"radiobutton"];
    [rolesMap setObject:@"CheckboxAccessibility" forKey:@"checkbox"];
    [rolesMap setObject:@"SliderAccessibility" forKey:@"slider"];
    [rolesMap setObject:@"ScrollAreaAccessibility" forKey:@"scrollpane"];
    [rolesMap setObject:@"ScrollBarAccessibility" forKey:@"scrollbar"];
    [rolesMap setObject:@"GroupAccessibility" forKey:@"awtcomponent"];
    [rolesMap setObject:@"GroupAccessibility" forKey:@"canvas"];
    [rolesMap setObject:@"GroupAccessibility" forKey:@"groupbox"];
    [rolesMap setObject:@"GroupAccessibility" forKey:@"internalframe"];
    [rolesMap setObject:@"GroupAccessibility" forKey:@"swingcomponent"];
    [rolesMap setObject:@"ToolbarAccessibility" forKey:@"toolbar"];
    [rolesMap setObject:@"SplitpaneAccessibility" forKey:@"splitpane"];
    [rolesMap setObject:@"StatusbarAccessibility" forKey:@"statusbar"];
    [rolesMap setObject:@"NavigableTextAccessibility" forKey:@"textarea"];
    [rolesMap setObject:@"NavigableTextAccessibility" forKey:@"text"];
    [rolesMap setObject:@"NavigableTextAccessibility" forKey:@"passwordtext"];
    [rolesMap setObject:@"NavigableTextAccessibility" forKey:@"dateeditor"];
    [rolesMap setObject:@"ComboBoxAccessibility" forKey:@"combobox"];
    [rolesMap setObject:@"TabGroupAccessibility" forKey:@"pagetablist"];
    [rolesMap setObject:@"TabButtonAccessibility" forKey:@"pagetab"];
    [rolesMap setObject:@"ListAccessibility" forKey:@"list"];
    [rolesMap setObject:@"OutlineAccessibility" forKey:@"tree"];
    [rolesMap setObject:@"TableAccessibility" forKey:@"table"];
    [rolesMap setObject:@"MenuBarAccessibility" forKey:@"menubar"];
    [rolesMap setObject:@"MenuAccessibility" forKey:@"menu"];
    [rolesMap setObject:@"MenuAccessibility" forKey:@"popupmenu"];
    [rolesMap setObject:@"MenuItemAccessibility" forKey:@"menuitem"];
    [rolesMap setObject:@"ProgressIndicatorAccessibility" forKey:@"progressbar"];

    /*
     * All the components below should be ignored by the accessibility subsystem,
     * If any of the enclosed component asks for a parent the first ancestor
     * participating in accessibility exchange should be returned.
     */
    [rolesMap setObject:IgnoreClassName forKey:@"alert"];
    [rolesMap setObject:IgnoreClassName forKey:@"colorchooser"];
    [rolesMap setObject:IgnoreClassName forKey:@"desktoppane"];
    [rolesMap setObject:IgnoreClassName forKey:@"dialog"];
    [rolesMap setObject:IgnoreClassName forKey:@"directorypane"];
    [rolesMap setObject:IgnoreClassName forKey:@"filechooser"];
    [rolesMap setObject:IgnoreClassName forKey:@"filler"];
    [rolesMap setObject:IgnoreClassName forKey:@"fontchooser"];
    [rolesMap setObject:IgnoreClassName forKey:@"frame"];
    [rolesMap setObject:IgnoreClassName forKey:@"glasspane"];
    [rolesMap setObject:IgnoreClassName forKey:@"layeredpane"];
    [rolesMap setObject:IgnoreClassName forKey:@"optionpane"];
    [rolesMap setObject:IgnoreClassName forKey:@"panel"];
    [rolesMap setObject:IgnoreClassName forKey:@"rootpane"];
    [rolesMap setObject:IgnoreClassName forKey:@"separator"];
    [rolesMap setObject:IgnoreClassName forKey:@"tooltip"];
    [rolesMap setObject:IgnoreClassName forKey:@"viewport"];
    [rolesMap setObject:IgnoreClassName forKey:@"window"];

    rowRolesMapForParent = [[NSMutableDictionary alloc] initWithCapacity:3];
    [rowRolesMapForParent setObject:@"MenuItemAccessibility" forKey:@"MenuAccessibility"];

    [rowRolesMapForParent setObject:@"ListRowAccessibility" forKey:@"ListAccessibility"];
    [rowRolesMapForParent setObject:@"OutlineRowAccessibility" forKey:@"OutlineAccessibility"];

    /*
     * Initialize CAccessibility instance
     */
#ifdef JAVA_AX_NO_IGNORES
    NSArray *ignoredKeys = [NSArray array];
#else
    NSArray *ignoredKeys = [rolesMap allKeysForObject:IgnoreClassName];
#endif

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS();
    DECLARE_STATIC_METHOD(jm_getAccessibility, sjc_CAccessibility, "getAccessibility", "([Ljava/lang/String;)Lsun/lwawt/macosx/CAccessibility;");
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

    sAccessibilityClass = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getAccessibility, result);
    CHECK_EXCEPTION();
}

/*
 * If new implementation of the accessible component peer for the given role exists
 * return the component's class otherwise return CommonComponentAccessibility
 */
+ (Class) getComponentAccessibilityClass:(NSString *)role
{
    AWT_ASSERT_APPKIT_THREAD;
    if (rolesMap == nil) {
        [self initializeRolesMap];
    }

    NSString *className = [rolesMap objectForKey:role];
    if (className != nil) {
        return NSClassFromString(className);
    }
    return [CommonComponentAccessibility class];
}

+ (Class) getComponentAccessibilityClass:(NSString *)role andParent:(CommonComponentAccessibility *)parent
{
    AWT_ASSERT_APPKIT_THREAD;
    if (rolesMap == nil) {
        [self initializeRolesMap];
    }
    NSString *className = [rowRolesMapForParent objectForKey:[[parent class] className]];
    if (className == nil) {
        return [CommonComponentAccessibility getComponentAccessibilityClass:role];
    }
    return NSClassFromString(className);
}

- (id)initWithParent:(NSObject *)parent withEnv:(JNIEnv *)env withAccessible:(jobject)accessible withIndex:(jint)index withView:(NSView *)view withJavaRole:(NSString *)javaRole
{
    self = [super init];
    if (self)
    {
        fParent = [parent retain];
        fView = [view retain];
        fJavaRole = [javaRole retain];

        if (accessible != NULL) {
            fAccessible = (*env)->NewWeakGlobalRef(env, accessible);
            CHECK_EXCEPTION();
        }

        jobject jcomponent = [(AWTView *)fView awtComponent:env];
        fComponent = (*env)->NewWeakGlobalRef(env, jcomponent);
        CHECK_EXCEPTION();

        (*env)->DeleteLocalRef(env, jcomponent);

        fIndex = index;

        fActions = nil;
        fActionSelectors = nil;
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

    [fJavaRole release];
    fJavaRole = nil;

    [fView release];
    fView = nil;

    [fActions release];
    fActions = nil;

    [fActionsLOCK release];
    fActionsLOCK = nil;

    [fActionSelectors release];
    fActionSelectors = nil;

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

-(void)postTitleChanged
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, NSAccessibilityTitleChangedNotification);
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

- (void)postTreeNodeExpanded
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification([[self accessibilitySelectedRows] firstObject], NSAccessibilityRowExpandedNotification);
}

- (void)postTreeNodeCollapsed
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification([[self accessibilitySelectedRows] firstObject], NSAccessibilityRowCollapsedNotification);
}

- (void)postSelectedCellsChanged
{
    AWT_ASSERT_APPKIT_THREAD;
    NSAccessibilityPostNotification(self, NSAccessibilitySelectedCellsChangedNotification);
}

- (BOOL)isEqual:(id)anObject
{
    if (![anObject isKindOfClass:[self class]]) return NO;
    CommonComponentAccessibility *accessibility = (CommonComponentAccessibility *)anObject;

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
}

+ (void)postFocusChanged:(id)message
{
    AWT_ASSERT_APPKIT_THREAD;
    id focusedUIElement = [NSApp accessibilityFocusedUIElement];
    NSAccessibilityPostNotification(focusedUIElement, NSAccessibilityFocusedUIElementChangedNotification);

    if (UAZoomEnabled() && [focusedUIElement isKindOfClass:[CommonComponentAccessibility class]]) {
        [(CommonComponentAccessibility*)focusedUIElement postZoomChangeFocus];
    }
}

+ (void)postAnnounceWithCaller:(id)caller andText:(NSString *)text andPriority:(NSNumber *)priority
{
    AWT_ASSERT_APPKIT_THREAD;

    NSMutableDictionary<NSAccessibilityNotificationUserInfoKey, id> *dictionary = [NSMutableDictionary<NSAccessibilityNotificationUserInfoKey, id> dictionaryWithCapacity:2];
    [dictionary setObject:text forKey: NSAccessibilityAnnouncementKey];

    if (sAnnouncePriorities == nil) {
        initializeAnnouncePriorities();
    }

    NSNumber *nsPriority = [sAnnouncePriorities objectForKey:priority];

    if (nsPriority == nil) {
        nsPriority = [sAnnouncePriorities objectForKey:[NSNumber numberWithInt:sun_swing_AccessibleAnnouncer_ANNOUNCE_WITHOUT_INTERRUPTING_CURRENT_OUTPUT]];
    }

    [dictionary setObject:nsPriority forKey:NSAccessibilityPriorityKey];

    NSAccessibilityPostNotificationWithUserInfo(caller, NSAccessibilityAnnouncementRequestedNotification, dictionary);
}

+ (jobject) getCAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env {
    DECLARE_CLASS_RETURN(sjc_Accessible, "javax/accessibility/Accessible", NULL);
    GET_CACCESSIBLE_CLASS_RETURN(NULL);
    DECLARE_STATIC_METHOD_RETURN(sjm_getCAccessible, sjc_CAccessible, "getCAccessible",
                                "(Ljavax/accessibility/Accessible;)Lsun/lwawt/macosx/CAccessible;", NULL);

    // jaccessible is a weak ref, check it's still alive
    jobject jaccessibleLocal = (*env)->NewLocalRef(env, jaccessible);
    if ((*env)->IsSameObject(env, jaccessibleLocal, NULL)) return NULL;

    if ((*env)->IsInstanceOf(env, jaccessibleLocal, sjc_CAccessible)) {
        return jaccessibleLocal; // delete in the caller
    } else if ((*env)->IsInstanceOf(env, jaccessibleLocal, sjc_Accessible)) {
        jobject jCAX = (*env)->CallStaticObjectMethod(env, sjc_CAccessible,  sjm_getCAccessible, jaccessibleLocal);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, jaccessibleLocal);
        return jCAX; // delete in the caller
    }
    (*env)->DeleteLocalRef(env, jaccessibleLocal);
    return NULL;
}

+ (NSArray *)childrenOfParent:(CommonComponentAccessibility *)parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored
{
    return [CommonComponentAccessibility childrenOfParent:parent withEnv:env withChildrenCode:whichChildren allowIgnored:allowIgnored recursive:NO];
}

+ (NSArray *)childrenOfParent:(CommonComponentAccessibility *)parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored recursive:(BOOL)recursive
{
    if (parent->fAccessible == NULL) return nil;
    jobjectArray jchildrenAndRoles = NULL;
    if (recursive) {
        GET_CHILDRENANDROLESRECURSIVE_METHOD_RETURN(nil);
        jchildrenAndRoles = (jobjectArray)(*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getChildrenAndRolesRecursive,
                      parent->fAccessible, parent->fComponent, whichChildren, allowIgnored);
        CHECK_EXCEPTION();
    } else {
        GET_CHILDRENANDROLES_METHOD_RETURN(nil);
        jchildrenAndRoles = (jobjectArray)(*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getChildrenAndRoles,
                      parent->fAccessible, parent->fComponent, whichChildren, allowIgnored);
        CHECK_EXCEPTION();
    }
    if (jchildrenAndRoles == NULL) return nil;

    jsize arrayLen = (*env)->GetArrayLength(env, jchildrenAndRoles);
    NSMutableArray *children = [NSMutableArray arrayWithCapacity:(recursive ? arrayLen/3 : arrayLen/2)]; //childrenAndRoles array contains two elements (child, role) for each child

    NSInteger i;
    NSUInteger childIndex = (whichChildren >= 0) ? whichChildren : 0; // if we're getting one particular child, make sure to set its index correctly
    int inc = recursive ? 3 : 2;
    for(i = 0; i < arrayLen; i+=inc)
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

        CommonComponentAccessibility *child = [self createWithParent:parent accessible:jchild role:childJavaRole index:childIndex withEnv:env withView:parent->fView];

        if (recursive && [child respondsToSelector:@selector(accessibleLevel)]) {
            jobject jLevel = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i+2);
            NSString *sLevel = nil;
            if (jLevel != NULL) {
                sLevel = JavaStringToNSString(env, jLevel);
                if (sLevel != nil) {
                    int level = sLevel.intValue;
                    [child setAccessibleLevel:level];
                }
                (*env)->DeleteLocalRef(env, jLevel);
            }
        }

        (*env)->DeleteLocalRef(env, jchild);
        (*env)->DeleteLocalRef(env, jchildJavaRole);

        [children addObject:child];
        childIndex++;
    }
    (*env)->DeleteLocalRef(env, jchildrenAndRoles);

    return children;
}

+ (CommonComponentAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [CommonComponentAccessibility createWithAccessible:jaccessible withEnv:env withView:view isCurrent:NO];
}

+ (CommonComponentAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view isCurrent:(BOOL)current
{
    GET_ACCESSIBLEINDEXINPARENT_STATIC_METHOD_RETURN(nil);
    CommonComponentAccessibility *ret = nil;
    jobject jcomponent = [(AWTView *)view awtComponent:env];
    jint index = (*env)->CallStaticIntMethod(env, sjc_CAccessibility, sjm_getAccessibleIndexInParent, jaccessible, jcomponent);
    CHECK_EXCEPTION();
    if (index >= 0 || current) {
      NSString *javaRole = getJavaRole(env, jaccessible, jcomponent);
      ret = [self createWithAccessible:jaccessible role:javaRole index:index withEnv:env withView:view];
    }
    (*env)->DeleteLocalRef(env, jcomponent);
    return ret;
}

+ (CommonComponentAccessibility *) createWithAccessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    return [self createWithParent:nil accessible:jaccessible role:javaRole index:index withEnv:env withView:view];
}

+ (CommonComponentAccessibility *) createWithParent:(CommonComponentAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    Class classType = [self getComponentAccessibilityClass:javaRole andParent:parent];
    return [CommonComponentAccessibility createWithParent:parent withClass:classType accessible:jaccessible role:javaRole index:index withEnv:env withView:view];
}

+ (CommonComponentAccessibility *) createWithParent:(CommonComponentAccessibility *)parent withClass:(Class)classType accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view
{
    GET_CACCESSIBLE_CLASS_RETURN(NULL);
    DECLARE_FIELD_RETURN(jf_ptr, sjc_CAccessible, "ptr", "J", NULL);
    // try to fetch the jCAX from Java, and return autoreleased
    jobject jCAX = [CommonComponentAccessibility getCAccessible:jaccessible withEnv:env];
    if (jCAX == NULL) return nil;
    CommonComponentAccessibility *value = (CommonComponentAccessibility *) jlong_to_ptr((*env)->GetLongField(env, jCAX, jf_ptr));
    if (value != nil) {
        (*env)->DeleteLocalRef(env, jCAX);
        return [[value retain] autorelease];
    }

    CommonComponentAccessibility *newChild =
        [[classType alloc] initWithParent:parent withEnv:env withAccessible:jCAX withIndex:index withView:view withJavaRole:javaRole];

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

    (*env)->DeleteLocalRef(env, jCAX);

    // return autoreleased instance
    return [newChild autorelease];
}

- (NSDictionary *)getActions:(JNIEnv *)env
{
    @synchronized(fActionsLOCK) {
        if (fActions == nil) {
            [self getActionsWithEnv:env];
        }
    }

    return fActions;
}

- (void)getActionsWithEnv:(JNIEnv *)env
{
    GET_CACCESSIBILITY_CLASS();
    DECLARE_STATIC_METHOD(jm_getAccessibleAction, sjc_CAccessibility, "getAccessibleAction",
                           "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleAction;");

    jobject axAction = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getAccessibleAction, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axAction != NULL) {
        DECLARE_STATIC_METHOD(jm_getAccessibleActionCount, sjc_CAccessibility, "getAccessibleActionCount", "(Ljavax/accessibility/AccessibleAction;Ljava/awt/Component;)I");
        jint count = (*env)->CallStaticIntMethod(env, sjc_CAccessibility, jm_getAccessibleActionCount, axAction, fComponent);
        CHECK_EXCEPTION();
        fActions = [[NSMutableDictionary alloc] initWithCapacity:count];
        fActionSelectors = [[NSMutableArray alloc] initWithCapacity:count];
        for (int i =0; i < count; i++) {
            JavaAxAction *action = [[JavaAxAction alloc] initWithEnv:env withAccessibleAction:axAction withIndex:i withComponent:fComponent];
            if ([fParent isKindOfClass:[CommonComponentAccessibility class]] &&
                [(CommonComponentAccessibility *)fParent isMenu] &&
                [[sActions objectForKey:[action getDescription]] isEqualToString:NSAccessibilityPressAction]) {
                [fActions setObject:action forKey:NSAccessibilityPickAction];
                [fActionSelectors addObject:[sActionSelectors objectForKey:NSAccessibilityPickAction]];
            } else {
                NSString *nsActionName = [sActions objectForKey:[action getDescription]];
                if (nsActionName != nil) {
                    [fActions setObject:action forKey:nsActionName];
                    [fActionSelectors addObject:[sActionSelectors objectForKey:nsActionName]];
                }
            }
            [action release];
        }
        (*env)->DeleteLocalRef(env, axAction);
    }
}

- (BOOL)accessiblePerformAction:(NSAccessibilityActionName)actionName {
    NSMutableDictionary *currentAction = [self getActions:[ThreadUtilities getJNIEnv]];
    if (currentAction == nil) {
        return NO;
    }
    if ([[currentAction allKeys] containsObject:actionName]) {
        [(JavaAxAction *)[currentAction objectForKey:actionName] perform];
        return YES;
    }
    return NO;
}

- (NSArray *)actionSelectors {
    @synchronized(fActionsLOCK) {
        if (fActionSelectors == nil) {
            [self getActionsWithEnv:[ThreadUtilities getJNIEnv]];
        }
    }

    return [NSArray arrayWithArray:fActionSelectors];
}

- (NSArray *)accessibleChildrenWithChildCode:(NSInteger)childCode
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    NSArray *children = [CommonComponentAccessibility childrenOfParent:self
                                                    withEnv:env
                                                    withChildrenCode:childCode
                                                    allowIgnored:([[self accessibilityRole] isEqualToString:NSAccessibilityListRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityTableRole])
                                                             recursive:[[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole]];

    NSArray *value = nil;
    if ([children count] > 0) {
        value = children;
    }

    return value;
}

- (NSView *)view
{
    return fView;
}

- (NSWindow *)window
{
    return [[self view] window];
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
                fParent = [CommonComponentAccessibility createWithAccessible:jparent withEnv:env withView:view];
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

- (CommonComponentAccessibility *)typeSafeParent
{
    id parent = [self parent];
    if ([parent isKindOfClass:[CommonComponentAccessibility class]]) {
        return (CommonComponentAccessibility*)parent;
    }
    return nil;
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

- (jobject)axContextWithEnv:(JNIEnv *)env
{
    return getAxContext(env, fAccessible, fComponent);
}

// NSAccessibilityElement protocol implementation

- (BOOL)isAccessibilityElement
{
    return ![[self accessibilityRole] isEqualToString:JavaAccessibilityIgnore];
}

- (NSString *)accessibilityLabel
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];

    GET_ACCESSIBLENAME_METHOD_RETURN(nil);
    jobject val = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getAccessibleName, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (val == NULL) {
        return nil;
    }
    NSString* str = JavaStringToNSString(env, val);
    (*env)->DeleteLocalRef(env, val);
    return str;
}

- (NSString *)accessibilityHelp
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];

    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(sjm_getAccessibleDescription, sjc_CAccessibility, "getAccessibleDescription",
                                 "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;", nil);
    jobject val = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility,
                                   sjm_getAccessibleDescription, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (val == NULL) {
        return nil;
    }
    NSString* str = JavaStringToNSString(env, val);
    (*env)->DeleteLocalRef(env, val);
    return str;
}

- (NSArray *)accessibilityChildren
{
    return [self accessibleChildrenWithChildCode:sun_lwawt_macosx_CAccessibility_JAVA_AX_ALL_CHILDREN];
}

- (NSArray *)accessibilitySelectedChildren
{
    return [self accessibleChildrenWithChildCode:sun_lwawt_macosx_CAccessibility_JAVA_AX_SELECTED_CHILDREN];
}

- (NSArray *)accessibilityVisibleChildren
{
    return [self accessibleChildrenWithChildCode:sun_lwawt_macosx_CAccessibility_JAVA_AX_VISIBLE_CHILDREN];
}

- (NSRect)accessibilityFrame
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLECOMPONENT_STATIC_METHOD_RETURN(NSZeroRect);
    jobject axComponent = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility,
                                                         sjm_getAccessibleComponent,
                                                         fAccessible, fComponent);
    CHECK_EXCEPTION();

    NSSize size = getAxComponentSize(env, axComponent, fComponent);
    NSPoint point = getAxComponentLocationOnScreen(env, axComponent, fComponent);
    (*env)->DeleteLocalRef(env, axComponent);
    point.y += size.height;

    point.y = [[[NSScreen screens] objectAtIndex:0] frame].size.height - point.y;

    return NSMakeRect(point.x, point.y, size.width, size.height);
}

- (id _Nullable)accessibilityParent
{
    return NSAccessibilityUnignoredAncestor([self parent]);
}

- (BOOL)isAccessibilityEnabled
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(NO);
    DECLARE_STATIC_METHOD_RETURN(jm_isEnabled, sjc_CAccessibility, "isEnabled", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Z", NO);

    BOOL value = (*env)->CallStaticBooleanMethod(env, sjc_CAccessibility, jm_isEnabled, fAccessible, fComponent);
    CHECK_EXCEPTION();

    return value;
}

- (id)accessibilityApplicationFocusedUIElement
{
    return [self accessibilityFocusedUIElement];
}

- (NSAccessibilityRole)accessibilityRole
{
    if (fNSRole == nil) {
        NSString *javaRole = [self javaRole];
        fNSRole = [sRoles objectForKey:javaRole];
        CommonComponentAccessibility* parent = [self typeSafeParent];
        // The sRoles NSMutableDictionary maps popupmenu to Mac's popup button.
        // JComboBox behavior currently relies on this.  However this is not the
        // proper mapping for a JPopupMenu so fix that.
        if ( [javaRole isEqualToString:@"popupmenu"] &&
             parent != nil &&
             ![[parent javaRole] isEqualToString:@"combobox"] ) {
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
        return [self isEqual:[NSApp accessibilityFocusedUIElement]];
    }
    CHECK_EXCEPTION();

    return NO;
}

- (void)setAccessibilityFocused:(BOOL)accessibilityFocused
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];

    GET_CACCESSIBILITY_CLASS();
    DECLARE_STATIC_METHOD(jm_requestFocus, sjc_CAccessibility, "requestFocus", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)V");

    if (accessibilityFocused)
    {
        (*env)->CallStaticVoidMethod(env, sjc_CAccessibility, jm_requestFocus, fAccessible, fComponent);
        CHECK_EXCEPTION();
    }
}

- (NSUInteger)accessibilityIndexOfChild:(id)child
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLEINDEXINPARENT_STATIC_METHOD_RETURN(0);
    jint returnValue =
        (*env)->CallStaticIntMethod( env,
                                sjc_CAccessibility,
                                sjm_getAccessibleIndexInParent,
                                ((CommonComponentAccessibility *)child)->fAccessible,
                                ((CommonComponentAccessibility *)child)->fComponent );
    CHECK_EXCEPTION();
    return (returnValue == -1) ? NSNotFound : returnValue;
}

- (NSInteger)accessibilityIndex
{
    int index = 0;
    if (fParent != NULL) {
        index = [fParent accessibilityIndexOfChild:self];
    }
    return index;
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
    return NSAccessibilityOrientationUnknown;
}

- (NSPoint)accessibilityActivationPoint
{
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLECOMPONENT_STATIC_METHOD_RETURN(NSPointFromString(@""));
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
    point.y = [[[NSScreen screens] objectAtIndex:0] frame].size.height - point.y;

    return point;
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
                          "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)V");

    if (accessibilitySelected) {
        (*env)->CallStaticVoidMethod(env, sjc_CAccessibility, jm_requestSelection, fAccessible, fComponent);
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
    id parent = [self typeSafeParent];
    if ( [[self javaRole] isEqualToString:@"popupmenu"] &&
         parent != nil &&
         ![[parent javaRole] isEqualToString:@"combobox"] ) {
        NSArray *children =
            [CommonComponentAccessibility childrenOfParent:self
                                        withEnv:env
                                        withChildrenCode:sun_lwawt_macosx_CAccessibility_JAVA_AX_ALL_CHILDREN
                                        allowIgnored:YES];
        if ([children count] > 0) {
            // handle case of AXMenuItem
            // need to ask menu what is selected
            NSArray *selectedChildrenOfMenu =
                [self accessibilitySelectedChildren];
            CommonComponentAccessibility *selectedMenuItem =
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

    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_CLASS_RETURN(jc_Container, "java/awt/Container", nil);
    DECLARE_STATIC_METHOD_RETURN(jm_accessibilityHitTest, sjc_CAccessibility, "accessibilityHitTest",
                                 "(Ljava/awt/Container;FF)Ljavax/accessibility/Accessible;", nil);

    // Make it into java screen coords
    point.y = [[[NSScreen screens] objectAtIndex:0] frame].size.height - point.y;

    jobject jparent = fComponent;

    id value = nil;
    if ((*env)->IsInstanceOf(env, jparent, jc_Container)) {
        jobject jaccessible = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_accessibilityHitTest,
                               jparent, (jfloat)point.x, (jfloat)point.y);
        CHECK_EXCEPTION();
        if (jaccessible != NULL) {
            value = [CommonComponentAccessibility createWithAccessible:jaccessible withEnv:env withView:fView];
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

- (id)accessibilityFocusedUIElement
{
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
            value = [CommonComponentAccessibility createWithAccessible:focused withEnv:env withView:fView];
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

- (id)accessibilityWindow {
    return [self window];
}

// AccessibleAction support

- (BOOL)performAccessibleAction:(int)index
{
    AWT_ASSERT_APPKIT_THREAD;
    JNIEnv* env = [ThreadUtilities getJNIEnv];

    GET_CACCESSIBILITY_CLASS_RETURN(FALSE);
    DECLARE_STATIC_METHOD_RETURN(jm_doAccessibleAction, sjc_CAccessibility, "doAccessibleAction",
                                 "(Ljavax/accessibility/AccessibleAction;ILjava/awt/Component;)V", FALSE);
    (*env)->CallStaticVoidMethod(env, sjc_CAccessibility, jm_doAccessibleAction,
                                 [self axContextWithEnv:(env)], index, fComponent);
    CHECK_EXCEPTION();

    return TRUE;
}

// NSAccessibilityActions methods

- (BOOL)isAccessibilitySelectorAllowed:(SEL)selector {
    if ([sAllActionSelectors containsObject:NSStringFromSelector(selector)] &&
        ![[self actionSelectors] containsObject:NSStringFromSelector(selector)]) {
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

- (void)postZoomChangeFocus {
    AWT_ASSERT_APPKIT_THREAD;

    if (![self isEqual:[NSApp accessibilityFocusedUIElement]] || ![self isLocationOnScreenValid]) return;

    NSRect frame = [self accessibilityFrame];

    if (CGSizeEqualToSize(frame.size, CGSizeZero)) return;

    CommonComponentAccessibility* parent = [self typeSafeParent];
    if (parent != nil) {
        NSRect parentFrame = [parent accessibilityFrame];
        // For scrollable components, the accessibilityFrame's height/width is equal to the full scrollable length.
        // We want to focus only on the visible part, so limit it to the parent's frame.
        frame = NSIntersectionRect(frame, parentFrame);
    }

    // UAZoomChangeFocus needs non flipped coordinates (as in Java).
    NSRect screenFrame = [[[NSScreen screens] objectAtIndex:0] frame];
    frame.origin.y = screenFrame.size.height - frame.origin.y - frame.size.height;

    CGRect selectedChildBounds = CGRectNull;
    NSArray* selectedChildren = [self accessibilitySelectedChildren];
    if (selectedChildren != nil && selectedChildren.count > 0) {
        // Use accessibility frame of only the first selected child instead of combined frames of all selected children,
        // because there may be a lot of selected children and calculating all their accessibility frames
        // could become a performance issue.
        CommonComponentAccessibility *child = [selectedChildren objectAtIndex:0];
        if (child != nil && [child isLocationOnScreenValid]) {
            NSRect childFrame = [child accessibilityFrame];
            if (!CGSizeEqualToSize(childFrame.size, CGSizeZero)) {
                selectedChildBounds = childFrame;
                selectedChildBounds.origin.y = screenFrame.size.height - selectedChildBounds.origin.y - selectedChildBounds.size.height;
            }
        }
    }

    UAZoomChangeFocus(&frame, !CGRectIsNull(selectedChildBounds) ? &selectedChildBounds : NULL, kUAZoomFocusTypeOther);
}

// This method checks that getLocationOnScreen won't return null. The accessibilityFrame uses getLocationOnScreen, but
// it doesn't distinguish between 0;0 location and invalid (null) location. In case of posting UA Zoom focus change,
// we want to ignore invalid location, but not 0;0 coordinates, so we check it separately.
// We still need to use accessibilityFrame to get the location and size for Zoom, because components can override
// accessibilityFrame implementation.
- (BOOL)isLocationOnScreenValid {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLECOMPONENT_STATIC_METHOD_RETURN(FALSE);
    jobject axComponent = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getAccessibleComponent,
                                                         fAccessible, fComponent);
    CHECK_EXCEPTION();
    DECLARE_STATIC_METHOD_RETURN(jm_getLocationOnScreen, sjc_CAccessibility, "getLocationOnScreen",
                                 "(Ljavax/accessibility/AccessibleComponent;Ljava/awt/Component;)Ljava/awt/Point;", FALSE);
    jobject jpoint = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getLocationOnScreen,
                                                    axComponent, fComponent);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axComponent);
    BOOL isLocationValid = jpoint != NULL;
    (*env)->DeleteLocalRef(env, jpoint);
    return isLocationValid;
}

@end


static void treeNodeExpandedCollapsedImpl(
    JNIEnv * const env,
    const jobject cAccessible,
    const jlong ccAxJavaPtr,
    // @selector(postTreeNodeExpanded) or @selector(postTreeNodeCollapsed)
    const SEL ccAxSelector,
    // "onProcessedTreeNodeExpandedEvent" or "onProcessedTreeNodeCollapsedEvent"
    const char* const cAccessibleCompletionUpcallName
) {
    assert((ccAxSelector != NULL));
    assert((cAccessibleCompletionUpcallName != NULL));

    JNI_COCOA_ENTER(env);
        const jobject cAccessibleGlobal = (*env)->NewGlobalRef(env, cAccessible);

        if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
            if (cAccessibleGlobal != NULL) {
                (*env)->DeleteGlobalRef(env, cAccessibleGlobal);
            }
            return;
        }

        const id ccAx = (CommonComponentAccessibility *)jlong_to_ptr(ccAxJavaPtr);

        [ThreadUtilities performOnMainThreadWaiting:NO block:^{
            [ccAx performSelector:ccAxSelector];

            JNIEnv * const env = [ThreadUtilities getJNIEnv];
            if ( (env != NULL) && (cAccessibleGlobal != NULL) ) {
                (void)JNU_CallMethodByName(env, NULL, cAccessibleGlobal, cAccessibleCompletionUpcallName, "()V");

                (*env)->DeleteGlobalRef(env, cAccessibleGlobal);

                CHECK_EXCEPTION();
            }
        }];
    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    treeNodeExpanded
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_treeNodeExpanded
  (JNIEnv *env, jobject self, jlong element)
{
    treeNodeExpandedCollapsedImpl(env, self, element, @selector(postTreeNodeExpanded), "onProcessedTreeNodeExpandedEvent");
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    treeNodeCollapsed
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_treeNodeCollapsed
  (JNIEnv *env, jobject self, jlong element)
{
    treeNodeExpandedCollapsedImpl(env, self, element, @selector(postTreeNodeCollapsed), "onProcessedTreeNodeCollapsedEvent");
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    selectedCellsChanged
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_selectedCellsChanged
  (JNIEnv *env, jclass jklass, jlong element)
{
    JNI_COCOA_ENTER(env);
        [ThreadUtilities performOnMainThread:@selector(postSelectedCellsChanged)
                         on:(CommonComponentAccessibility *)jlong_to_ptr(element)
                         withObject:nil
                         waitUntilDone:NO];
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
                         on:(CommonComponentAccessibility *)jlong_to_ptr(element)
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
                         on:(CommonComponentAccessibility *)jlong_to_ptr(element)
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
                         on:(CommonComponentAccessibility *)jlong_to_ptr(element)
                         withObject:nil
                         waitUntilDone:NO];
    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessibility
 * Method:    focusChanged
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessibility_focusChanged
        (JNIEnv *env, jobject jthis)
{
    JNI_COCOA_ENTER(env);
        [ThreadUtilities performOnMainThread:@selector(postFocusChanged:)
                         on:[CommonComponentAccessibility class]
                         withObject:nil
                         waitUntilDone:NO];
    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    updateZoomFocus
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_updateZoomFocus
        (JNIEnv *env, jclass jklass, jlong element)
{
    if (!UAZoomEnabled()) return;

    JNI_COCOA_ENTER(env);
        [ThreadUtilities performOnMainThread:@selector(postZoomChangeFocus)
                         on:(CommonComponentAccessibility *) jlong_to_ptr(element)
                         withObject:nil
                         waitUntilDone:NO];
    JNI_COCOA_EXIT(env);
}


static void nativeAnnounceAppKit(jobject jAccessible, NSString *text, NSNumber *javaPriority);

/*
 * Class:     sun_swing_AccessibleAnnouncer
 * Method:    nativeAnnounce
 * Signature: (Ljavax/accessibility/Accessible;Ljava/lang/String;I)V
 */
JNIEXPORT void JNICALL Java_sun_swing_AccessibleAnnouncer_nativeAnnounce
        (JNIEnv *env, jclass cls, jobject jAccessible, jstring str, jint priority)
{
    JNI_COCOA_ENTER(env);

    NSString *const text = JavaStringToNSString(env, str);
    NSNumber *const javaPriority = [NSNumber numberWithInt:priority];

    // From JNI specification:
    // > Local references are only valid in the thread in which they are created.
    // > The native code must not pass local references from one thread to another.
    //
    // So we have to create a global ref and pass it to the AppKit thread.
    const jobject jAccessibleGlobalRef = (jAccessible == NULL) ? NULL : (*env)->NewGlobalRef(env, jAccessible);

    [ThreadUtilities performOnMainThreadWaiting:YES block:^{
        nativeAnnounceAppKit(jAccessibleGlobalRef, text, javaPriority);
    }];

    if (jAccessibleGlobalRef != NULL) {
        (*env)->DeleteGlobalRef(env, jAccessibleGlobalRef);
    }

    JNI_COCOA_EXIT(env);
}

void nativeAnnounceAppKit(
    const jobject jAccessible,
    NSString * const text,
    NSNumber * const javaPriority
) {
    AWT_ASSERT_APPKIT_THREAD;
    assert((text != NULL));
    assert((javaPriority != NULL));

    JNIEnv* const env = [ThreadUtilities getJNIEnv];
    if (env == NULL) { // unlikely
        NSLog(@"%s: failed to get JNIEnv instance\n%@\n", __func__, [NSThread callStackSymbols]);
        return; // I believe it's dangerous to go on announcing in that case
    }

    id caller = nil;

    // The meaning of this block is the following:
    //   if jAccessible is an instance of "javax/accessibility/Accessible"
    //   and sun.lwawt.macosx.CAccessible#getCAccessible(jAccessible) returns a non-null object
    //   then we obtain its private field sun.lwawt.macosx.CAccessible#ptr
    //     (which is a pointer to a native "part" of the accessible component)
    //   and assign it to caller
    if (jAccessible != NULL) {
        DECLARE_CLASS(jc_Accessible, "javax/accessibility/Accessible");

        if ((*env)->IsInstanceOf(env, jAccessible, jc_Accessible) == JNI_TRUE) {
            const jobject jCAX = [CommonComponentAccessibility getCAccessible:jAccessible withEnv:env];

            if (jCAX != NULL) {
                GET_CACCESSIBLE_CLASS();
                DECLARE_FIELD(jf_ptr, sjc_CAccessible, "ptr", "J");

                caller = (CommonComponentAccessibility*)jlong_to_ptr((*env)->GetLongField(env, jCAX, jf_ptr));

                (*env)->DeleteLocalRef(env, jCAX);
            }
        }
    }

    if (caller == nil) {
        caller = [NSApp accessibilityFocusedUIElement];
    }

    [CommonComponentAccessibility postAnnounceWithCaller:caller andText:text andPriority:javaPriority];
}
