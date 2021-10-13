// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaCellAccessibility.h"
#import "JavaColumnAccessibility.h"
#import "JavaTableAccessibility.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"

// GET* macros defined in JavaAccessibilityUtilities.h, so they can be shared.
static jclass sjc_CAccessibility = NULL;

static jmethodID jm_getChildrenAndRoles = NULL;
#define GET_CHILDRENANDROLES_METHOD_RETURN(ret) \
    GET_CACCESSIBILITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles",\
                      "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;", ret);

@implementation JavaColumnAccessibility

// NSAccessibilityElement protocol methods

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityColumnRole;
}


- (NSUInteger)columnNumberInTable {
    return self->fIndex;
}

@end
