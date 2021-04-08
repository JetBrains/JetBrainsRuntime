#include "jni.h"

#import <AppKit/AppKit.h>

//#define JAVA_AX_DEBUG 1
//#define JAVA_AX_NO_IGNORES 1
//#define JAVA_AX_DEBUG_PARMS 1

// these constants are duplicated in CAccessibility.java
#define JAVA_AX_ALL_CHILDREN (-1)
#define JAVA_AX_SELECTED_CHILDREN (-2)
#define JAVA_AX_VISIBLE_CHILDREN (-3)
// If the value is >=0, it's an index

@class JavaComponentAccessibility;

@protocol JavaComponentProvider

@property (nonatomic, retain) JavaComponentAccessibility *javaComponent;

@end

@interface PlatformAxElement : NSAccessibilityElement <JavaComponentProvider>

// begin of NSAccessibility protocol methods
- (BOOL)isAccessibilityElement;
- (NSString *)accessibilityLabel;
- (NSArray *)accessibilityChildren;
- (NSArray *)accessibilitySelectedChildren;
- (NSRect)accessibilityFrame;
- (id)accessibilityParent;
- (BOOL)isAccessibilityEnabled;
- (id)accessibilityApplicationFocusedUIElement;
- (id)getAccessibilityWindow;
// end of NSAccessibility protocol methods

@end

@protocol PlatformAxElementProvider
@required

- (NSString *)getPlatformAxElementClassName;

@property (nonatomic, retain) PlatformAxElement *platformAxElement;

@end

@interface JavaComponentAccessibility : NSObject <PlatformAxElementProvider> {
    NSView *fView;
    NSObject *fParent;

    NSString *fNSRole;
    NSString *fJavaRole;

    jint fIndex;
    jobject fAccessible;
    jobject fComponent;

    NSMutableDictionary *fActions;
    NSMutableArray *fActionSElectors;
    NSObject *fActionsLOCK;
}

- (id)initWithParent:(NSObject*)parent withEnv:(JNIEnv *)env withAccessible:(jobject)accessible withIndex:(jint)index withView:(NSView *)view withJavaRole:(NSString *)javaRole;
- (void)unregisterFromCocoaAXSystem;
- (void)postValueChanged;
- (void)postSelectedTextChanged;
- (void)postSelectionChanged;
- (BOOL)isEqual:(id)anObject;
- (BOOL)isAccessibleWithEnv:(JNIEnv *)env forAccessible:(jobject)accessible;

+ (void)postFocusChanged:(id)message;

+ (NSArray *)childrenOfParent:(JavaComponentAccessibility *) parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored;
+ (NSArray *)childrenOfParent:(JavaComponentAccessibility *) parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored recursive:(BOOL)recursive;
+ (JavaComponentAccessibility *) createWithParent:(JavaComponentAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view;
+ (JavaComponentAccessibility *) createWithAccessible:(jobject)jaccessible role:(NSString *)role index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view;
+ (JavaComponentAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view;

// If the isWraped parameter is true, then the object passed as a parent was created based on the same java component,
// but performs a different NSAccessibilityRole of a table cell, or a list row, or tree row,
// and we need to create an element whose role corresponds to the role in Java.
+ (JavaComponentAccessibility *) createWithParent:(JavaComponentAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view isWrapped:(BOOL)wrapped;

// The current parameter is used to bypass the check for an item's index on the parent so that the item is created. This is necessary,
// for example, for AccessibleJTreeNode, whose currentComponent has index -1
+ (JavaComponentAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view isCurrent:(BOOL)current;

@property(readonly) jobject accessible;
@property(readonly) jobject component;
@property(readonly) jint index;
@property(readonly, copy) NSArray *actionSelectores;;

- (jobject)axContextWithEnv:(JNIEnv *)env;
- (NSView*)view;
- (NSWindow*)window;
- (id)parent;
- (void)setParent:(id)javaComponentAccessibilityParent;
- (NSString *)javaRole;
- (NSString *)nsRole;
- (BOOL)isMenu;
- (BOOL)isSelected:(JNIEnv *)env;
- (BOOL)isSelectable:(JNIEnv *)env;
- (BOOL)isVisible:(JNIEnv *)env;
- (NSSize)getSize;
- (NSRect)getBounds;
- (id)getFocusedElement;

@property(readonly) int accessibleIndexOfParent;
@property(readonly) BOOL accessibleEnabled;
@property(readwrite) BOOL accessibleFocused;
@property(readonly) NSNumber *accessibleMaxValue;
@property(readonly) NSNumber *accessibleMinValue;
@property(readonly) id accessibleOrientation;
@property(readonly) NSValue *accessiblePosition;
@property(readonly) NSString *accessibleRole;
@property(readonly) NSString *accessibleRoleDescription;
@property(readonly) id accessibleParent;
@property(readwrite, copy) NSNumber *accessibleSelected;
@property(readonly) id accessibleValue;;
@property(readonly) NSMutableDictionary *getActions;

- (id)accessibleHitTest:(NSPoint)point;
- (void)getActionsWithEnv:(JNIEnv *)env;
- (BOOL)accessiblePerformAction:(NSAccessibilityActionName)actionName;

@end
