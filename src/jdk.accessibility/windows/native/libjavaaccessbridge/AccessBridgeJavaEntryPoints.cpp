/*
 * Copyright (c) 2005, 2015, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

/*
 * A class to manage JNI calls into AccessBridge.java
 */

#include "AccessBridgeJavaEntryPoints.h"
#include "AccessBridgeDebug.h"
#include "AccessBridgeUtils.h"
#include "JavaAccessBridgeJniRefsTracker.h"


/**
 * Initialize the AccessBridgeJavaEntryPoints class
 *
 */
AccessBridgeJavaEntryPoints::AccessBridgeJavaEntryPoints(JNIEnv *jniEnvironment, jobject bridgeObject)
    : jniEnv(jniEnvironment)
    , accessBridgeObject(bridgeObject)
{
    PrintDebugString(" [INFO]: AccessBridgeJavaEntryPoints(%p, %p) called", jniEnv, accessBridgeObject);
}


/**
 * Destructor
 *
 */
AccessBridgeJavaEntryPoints::~AccessBridgeJavaEntryPoints() {
}

// -----------------------------------

#define FIND_CLASS(classRef, className) \
    localClassRef = jniEnv->FindClass(className); \
    if (localClassRef == nullptr) { \
        PrintDebugString("[ERROR]:   FindClass(%s) failed! -> jniEnv = %p", className, jniEnv); \
        return FALSE; \
    } \
    classRef = (jclass) jniEnv->NewGlobalRef(localClassRef); \
    jniEnv->DeleteLocalRef(localClassRef); \
    if (classRef == nullptr) { \
        PrintDebugString("[ERROR]:   FindClass(%s) failed! -> (ran out of RAM)", className); \
        return FALSE; \
    }


#define FIND_METHOD(methodID, classRef, methodString, methodSignature); \
    methodID = jniEnv->GetMethodID(classRef, methodString, methodSignature); \
    if (methodID == nullptr) { \
        PrintDebugString("[ERROR]:   GetMethodID(\"%s\") failed! -> jniEnv = %p; classRef = %p", methodString, jniEnv, classRef); \
        return FALSE; \
    }

#define EXCEPTION_CHECK(situationDescription, returnVal)                                        \
    if (jniEnv->ExceptionCheck() == JNI_TRUE) {                                                 \
        PrintDebugString("[ERROR]:   Exception occurred while doing: %s; returning %d", situationDescription, returnVal); \
        jniEnv->ExceptionDescribe();                                                            \
        jniEnv->ExceptionClear();                                                               \
        return (returnVal);                                                                     \
    }

#define EXCEPTION_CHECK_VOID(situationDescription)                                              \
    if (jniEnv->ExceptionCheck() == JNI_TRUE) {                                                 \
        PrintDebugString("[ERROR]:   Exception occurred while doing: %s", situationDescription);\
        jniEnv->ExceptionDescribe();                                                            \
        jniEnv->ExceptionClear();                                                               \
        return;                                                                                 \
    }

/**
 * Make all of the getClass() & getMethod() calls
 *
 */
BOOL
AccessBridgeJavaEntryPoints::BuildJavaEntryPoints() {
    jclass localClassRef;

    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::BuildJavaEntryPoints():");

    FIND_CLASS(bridgeClass, "com/sun/java/accessibility/internal/AccessBridge");

    // ------- general methods

    // GetMethodID(decrementReference)
    FIND_METHOD(decrementReferenceMethod, bridgeClass,
                "decrementReference",
                "(Ljava/lang/Object;)V");

    // GetMethodID(getJavaVersionPropertyMethod)
    FIND_METHOD(getJavaVersionPropertyMethod, bridgeClass,
                "getJavaVersionProperty",
                "()Ljava/lang/String;");

    // ------- Window methods

    // GetMethodID(isJavaWindow)
    FIND_METHOD(isJavaWindowMethod, bridgeClass,
                "isJavaWindow",
                "(I)Z");

    // GetMethodID(getAccessibleContextFromHWND)
    FIND_METHOD(getAccessibleContextFromHWNDMethod, bridgeClass,
                "getContextFromNativeWindowHandle",
                "(I)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getHWNDFromAccessibleContext)
    FIND_METHOD(getHWNDFromAccessibleContextMethod, bridgeClass,
                "getNativeWindowHandleFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleParentFromContext)
    FIND_METHOD(getAccessibleParentFromContextMethod, bridgeClass,
                "getAccessibleParentFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleContext;");

    // ===== utility methods ===== */

    // GetMethodID(setTextContents)
    FIND_METHOD(setTextContentsMethod, bridgeClass,
                "setTextContents",
                "(Ljavax/accessibility/AccessibleContext;Ljava/lang/String;)Z");

    // GetMethodID(getParentWithRole)
    FIND_METHOD(getParentWithRoleMethod, bridgeClass,
                "getParentWithRole",
                "(Ljavax/accessibility/AccessibleContext;Ljava/lang/String;)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getTopLevelObject)
    FIND_METHOD(getTopLevelObjectMethod, bridgeClass,
                "getTopLevelObject",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getParentWithRoleElseRoot)
    FIND_METHOD(getParentWithRoleElseRootMethod, bridgeClass,
                "getParentWithRoleElseRoot",
                "(Ljavax/accessibility/AccessibleContext;Ljava/lang/String;)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getObjectDepth)
    FIND_METHOD(getObjectDepthMethod, bridgeClass,
                "getObjectDepth",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getActiveDescendent)
    FIND_METHOD(getActiveDescendentMethod, bridgeClass,
                "getActiveDescendent",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleContext;");

    // ------- AccessibleContext methods

    // GetMethodID(getAccessibleContextAt)
    FIND_METHOD(getAccessibleContextAtMethod, bridgeClass,
                "getAccessibleContextAt",
                "(IILjavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleContextWithFocus)
    FIND_METHOD(getAccessibleContextWithFocusMethod, bridgeClass,
                "getAccessibleContextWithFocus",
                "()Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleNameFromContext)
    FIND_METHOD(getAccessibleNameFromContextMethod, bridgeClass,
                "getAccessibleNameFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getAccessibleDescriptionFromContext)
    FIND_METHOD(getAccessibleDescriptionFromContextMethod, bridgeClass,
                "getAccessibleDescriptionFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getAccessibleRoleStringFromContext)
    FIND_METHOD(getAccessibleRoleStringFromContextMethod, bridgeClass,
                "getAccessibleRoleStringFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getAccessibleRoleStringFromContext_en_US)
    FIND_METHOD(getAccessibleRoleStringFromContext_en_USMethod, bridgeClass,
                "getAccessibleRoleStringFromContext_en_US",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getAccessibleStatesStringFromContext)
    FIND_METHOD(getAccessibleStatesStringFromContextMethod, bridgeClass,
                "getAccessibleStatesStringFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getAccessibleStatesStringFromContext_en_US)
    FIND_METHOD(getAccessibleStatesStringFromContext_en_USMethod, bridgeClass,
                "getAccessibleStatesStringFromContext_en_US",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getAccessibleParentFromContext)
    FIND_METHOD(getAccessibleParentFromContextMethod, bridgeClass,
                "getAccessibleParentFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleIndexInParentFromContext)
    FIND_METHOD(getAccessibleIndexInParentFromContextMethod, bridgeClass,
                "getAccessibleIndexInParentFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleChildrenCountFromContext)
    FIND_METHOD(getAccessibleChildrenCountFromContextMethod, bridgeClass,
                "getAccessibleChildrenCountFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleChildFromContext)
    FIND_METHOD(getAccessibleChildFromContextMethod, bridgeClass,
                "getAccessibleChildFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleBoundsOnScreenFromContext)
    FIND_METHOD(getAccessibleBoundsOnScreenFromContextMethod, bridgeClass,
                "getAccessibleBoundsOnScreenFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/awt/Rectangle;");

    // GetMethodID(getAccessibleXcoordFromContext)
    FIND_METHOD(getAccessibleXcoordFromContextMethod, bridgeClass,
                "getAccessibleXcoordFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleYcoordFromContext)
    FIND_METHOD(getAccessibleYcoordFromContextMethod, bridgeClass,
                "getAccessibleYcoordFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleHeightFromContext)
    FIND_METHOD(getAccessibleHeightFromContextMethod, bridgeClass,
                "getAccessibleHeightFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleWidthFromContext)
    FIND_METHOD(getAccessibleWidthFromContextMethod, bridgeClass,
                "getAccessibleWidthFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleComponentFromContext)
    FIND_METHOD(getAccessibleComponentFromContextMethod, bridgeClass,
                "getAccessibleComponentFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleComponent;");

    // GetMethodID(getAccessibleActionFromContext)
    FIND_METHOD(getAccessibleActionFromContextMethod, bridgeClass,
                "getAccessibleActionFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleAction;");

    // GetMethodID(getAccessibleSelectionFromContext)
    FIND_METHOD(getAccessibleSelectionFromContextMethod, bridgeClass,
                "getAccessibleSelectionFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleSelection;");

    // GetMethodID(getAccessibleTextFromContext)
    FIND_METHOD(getAccessibleTextFromContextMethod, bridgeClass,
                "getAccessibleTextFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleText;");

    // GetMethodID(getAccessibleValueFromContext)
    FIND_METHOD(getAccessibleValueFromContextMethod, bridgeClass,
                "getAccessibleValueFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleValue;");


    // ------- begin AccessibleTable methods

    // GetMethodID(getAccessibleTableFromContext)
    FIND_METHOD(getAccessibleTableFromContextMethod, bridgeClass,
                "getAccessibleTableFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleTable;");

    // GetMethodID(getContextFromAccessibleTable)
    FIND_METHOD(getContextFromAccessibleTableMethod, bridgeClass,
                "getContextFromAccessibleTable",
                "(Ljavax/accessibility/AccessibleTable;)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleTableRowHeader)
    FIND_METHOD(getAccessibleTableRowHeaderMethod, bridgeClass,
                "getAccessibleTableRowHeader",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleTable;");


    // GetMethodID(getAccessibleTableColumnHeader)
    FIND_METHOD(getAccessibleTableColumnHeaderMethod, bridgeClass,
                "getAccessibleTableColumnHeader",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleTable;");


    // GetMethodID(getAccessibleTableRowCount)
    FIND_METHOD(getAccessibleTableRowCountMethod, bridgeClass,
                "getAccessibleTableRowCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleTableColumnCount)
    FIND_METHOD(getAccessibleTableColumnCountMethod, bridgeClass,
                "getAccessibleTableColumnCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleTableCellAccessibleContext)
    FIND_METHOD(getAccessibleTableCellAccessibleContextMethod, bridgeClass,
                "getAccessibleTableCellAccessibleContext",
                "(Ljavax/accessibility/AccessibleTable;II)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleTableCellIndex)
    FIND_METHOD(getAccessibleTableCellIndexMethod, bridgeClass,
                "getAccessibleTableCellIndex",
                "(Ljavax/accessibility/AccessibleTable;II)I");

    // GetMethodID(getAccessibleTableCellRowExtent)
    FIND_METHOD(getAccessibleTableCellRowExtentMethod, bridgeClass,
                "getAccessibleTableCellRowExtent",
                "(Ljavax/accessibility/AccessibleTable;II)I");

    // GetMethodID(getAccessibleTableCellColumnExtent)
    FIND_METHOD(getAccessibleTableCellColumnExtentMethod, bridgeClass,
                "getAccessibleTableCellColumnExtent",
                "(Ljavax/accessibility/AccessibleTable;II)I");

    // GetMethodID(isAccessibleTableCellSelected)
    FIND_METHOD(isAccessibleTableCellSelectedMethod, bridgeClass,
                "isAccessibleTableCellSelected",
                "(Ljavax/accessibility/AccessibleTable;II)Z");

    // GetMethodID(getAccessibleTableRowHeaderRowCount)
    FIND_METHOD(getAccessibleTableRowHeaderRowCountMethod, bridgeClass,
                "getAccessibleTableRowHeaderRowCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleTableColumnHeaderRowCount)
    FIND_METHOD(getAccessibleTableColumnHeaderRowCountMethod, bridgeClass,
                "getAccessibleTableColumnHeaderRowCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleTableRowHeaderColumnCount)
    FIND_METHOD(getAccessibleTableRowHeaderColumnCountMethod, bridgeClass,
                "getAccessibleTableRowHeaderColumnCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleTableColumnHeaderColumnCount)
    FIND_METHOD(getAccessibleTableColumnHeaderColumnCountMethod, bridgeClass,
                "getAccessibleTableColumnHeaderColumnCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleTableRowDescription)
    FIND_METHOD(getAccessibleTableRowDescriptionMethod, bridgeClass,
                "getAccessibleTableRowDescription",
                "(Ljavax/accessibility/AccessibleTable;I)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleTableColumnDescription)
    FIND_METHOD(getAccessibleTableColumnDescriptionMethod, bridgeClass,
                "getAccessibleTableColumnDescription",
                "(Ljavax/accessibility/AccessibleTable;I)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleTableRowSelectionCount)
    FIND_METHOD(getAccessibleTableRowSelectionCountMethod, bridgeClass,
                "getAccessibleTableRowSelectionCount",
                "(Ljavax/accessibility/AccessibleTable;)I");

    // GetMethodID(isAccessibleTableRowSelected)
    FIND_METHOD(isAccessibleTableRowSelectedMethod, bridgeClass,
                "isAccessibleTableRowSelected",
                "(Ljavax/accessibility/AccessibleTable;I)Z");

    // GetMethodID(getAccessibleTableRowSelections)
    FIND_METHOD(getAccessibleTableRowSelectionsMethod, bridgeClass,
                "getAccessibleTableRowSelections",
                "(Ljavax/accessibility/AccessibleTable;I)I");

    // GetMethodID(getAccessibleTableColumnSelectionCount)
    FIND_METHOD(getAccessibleTableColumnSelectionCountMethod, bridgeClass,
                "getAccessibleTableColumnSelectionCount",
                "(Ljavax/accessibility/AccessibleTable;)I");

    // GetMethodID(isAccessibleTableColumnSelected)
    FIND_METHOD(isAccessibleTableColumnSelectedMethod, bridgeClass,
                "isAccessibleTableColumnSelected",
                "(Ljavax/accessibility/AccessibleTable;I)Z");

    // GetMethodID(getAccessibleTableColumnSelections)
    FIND_METHOD(getAccessibleTableColumnSelectionsMethod, bridgeClass,
                "getAccessibleTableColumnSelections",
                "(Ljavax/accessibility/AccessibleTable;I)I");

    // GetMethodID(getAccessibleTableRow)
    FIND_METHOD(getAccessibleTableRowMethod, bridgeClass,
                "getAccessibleTableRow",
                "(Ljavax/accessibility/AccessibleTable;I)I");

    // GetMethodID(getAccessibleTableColumn)
    FIND_METHOD(getAccessibleTableColumnMethod, bridgeClass,
                "getAccessibleTableColumn",
                "(Ljavax/accessibility/AccessibleTable;I)I");

    // GetMethodID(getAccessibleTableIndex)
    FIND_METHOD(getAccessibleTableIndexMethod, bridgeClass,
                "getAccessibleTableIndex",
                "(Ljavax/accessibility/AccessibleTable;II)I");

    /* ------- end AccessibleTable methods */

    /* start AccessibleRelationSet methods ----- */

    // GetMethodID(getAccessibleRelationCount)
    FIND_METHOD(getAccessibleRelationCountMethod, bridgeClass,
                "getAccessibleRelationCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleRelationKey)
    FIND_METHOD(getAccessibleRelationKeyMethod, bridgeClass,
                "getAccessibleRelationKey",
                "(Ljavax/accessibility/AccessibleContext;I)Ljava/lang/String;");

    // GetMethodID(getAccessibleRelationTargetCount)
    FIND_METHOD(getAccessibleRelationTargetCountMethod, bridgeClass,
                "getAccessibleRelationTargetCount",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleRelationTarget)
    FIND_METHOD(getAccessibleRelationTargetMethod, bridgeClass,
                "getAccessibleRelationTarget",
                "(Ljavax/accessibility/AccessibleContext;II)Ljavax/accessibility/AccessibleContext;");


    // ------- AccessibleHypertext methods

    // GetMethodID(getAccessibleHypertext)
    FIND_METHOD(getAccessibleHypertextMethod, bridgeClass,
                "getAccessibleHypertext",
                "(Ljavax/accessibility/AccessibleContext;)Ljavax/accessibility/AccessibleHypertext;");

    // GetMethodID(activateAccessibleHyperlink)
    FIND_METHOD(activateAccessibleHyperlinkMethod, bridgeClass,
                "activateAccessibleHyperlink",
                "(Ljavax/accessibility/AccessibleContext;Ljavax/accessibility/AccessibleHyperlink;)Z");

    // GetMethodID(getAccessibleHyperlinkCount)
    FIND_METHOD(getAccessibleHyperlinkCountMethod, bridgeClass,
                "getAccessibleHyperlinkCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleHyperlink)
    FIND_METHOD(getAccessibleHyperlinkMethod, bridgeClass,
                "getAccessibleHyperlink",
                "(Ljavax/accessibility/AccessibleHypertext;I)Ljavax/accessibility/AccessibleHyperlink;");

    // GetMethodID(getAccessibleHyperlinkText)
    FIND_METHOD(getAccessibleHyperlinkTextMethod, bridgeClass,
                "getAccessibleHyperlinkText",
                "(Ljavax/accessibility/AccessibleHyperlink;)Ljava/lang/String;");

    // GetMethodID(getAccessibleHyperlinkURL)
    FIND_METHOD(getAccessibleHyperlinkURLMethod, bridgeClass,
                "getAccessibleHyperlinkURL",
                "(Ljavax/accessibility/AccessibleHyperlink;)Ljava/lang/String;");

    // GetMethodID(getAccessibleHyperlinkStartIndex)
    FIND_METHOD(getAccessibleHyperlinkStartIndexMethod, bridgeClass,
                "getAccessibleHyperlinkStartIndex",
                "(Ljavax/accessibility/AccessibleHyperlink;)I");

    // GetMethodID(getAccessibleHyperlinkEndIndex)
    FIND_METHOD(getAccessibleHyperlinkEndIndexMethod, bridgeClass,
                "getAccessibleHyperlinkEndIndex",
                "(Ljavax/accessibility/AccessibleHyperlink;)I");

    // GetMethodID(getAccessibleHypertextLinkIndex)
    FIND_METHOD(getAccessibleHypertextLinkIndexMethod, bridgeClass,
                "getAccessibleHypertextLinkIndex",
                "(Ljavax/accessibility/AccessibleHypertext;I)I");

    // Accessible KeyBinding, Icon and Action ====================

    // GetMethodID(getAccessibleKeyBindingsCount)
    FIND_METHOD(getAccessibleKeyBindingsCountMethod, bridgeClass,
                "getAccessibleKeyBindingsCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleKeyBindingChar)
    FIND_METHOD(getAccessibleKeyBindingCharMethod, bridgeClass,
                "getAccessibleKeyBindingChar",
                "(Ljavax/accessibility/AccessibleContext;I)C");

    // GetMethodID(getAccessibleKeyBindingModifiers)
    FIND_METHOD(getAccessibleKeyBindingModifiersMethod, bridgeClass,
                "getAccessibleKeyBindingModifiers",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleIconsCount)
    FIND_METHOD(getAccessibleIconsCountMethod, bridgeClass,
                "getAccessibleIconsCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleIconDescription)
    FIND_METHOD(getAccessibleIconDescriptionMethod, bridgeClass,
                "getAccessibleIconDescription",
                "(Ljavax/accessibility/AccessibleContext;I)Ljava/lang/String;");

    // GetMethodID(getAccessibleIconHeight)
    FIND_METHOD(getAccessibleIconHeightMethod, bridgeClass,
                "getAccessibleIconHeight",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleIconWidth)
    FIND_METHOD(getAccessibleIconWidthMethod, bridgeClass,
                "getAccessibleIconWidth",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleActionsCount)
    FIND_METHOD(getAccessibleActionsCountMethod, bridgeClass,
                "getAccessibleActionsCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleActionName)
    FIND_METHOD(getAccessibleActionNameMethod, bridgeClass,
                "getAccessibleActionName",
                "(Ljavax/accessibility/AccessibleContext;I)Ljava/lang/String;");

    // GetMethodID(doAccessibleActions)
    FIND_METHOD(doAccessibleActionsMethod, bridgeClass,
                "doAccessibleActions",
                "(Ljavax/accessibility/AccessibleContext;Ljava/lang/String;)Z");

    // ------- AccessibleText methods

    // GetMethodID(getAccessibleCharCountFromContext)
    FIND_METHOD(getAccessibleCharCountFromContextMethod, bridgeClass,
                "getAccessibleCharCountFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleCaretPositionFromContext)
    FIND_METHOD(getAccessibleCaretPositionFromContextMethod, bridgeClass,
                "getAccessibleCaretPositionFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleIndexAtPointFromContext)
    FIND_METHOD(getAccessibleIndexAtPointFromContextMethod, bridgeClass,
                "getAccessibleIndexAtPointFromContext",
                "(Ljavax/accessibility/AccessibleContext;II)I");

    // GetMethodID(getAccessibleLetterAtIndexFromContext)
    FIND_METHOD(getAccessibleLetterAtIndexFromContextMethod, bridgeClass,
                "getAccessibleLetterAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Ljava/lang/String;");

    // GetMethodID(getAccessibleWordAtIndexFromContext)
    FIND_METHOD(getAccessibleWordAtIndexFromContextMethod, bridgeClass,
                "getAccessibleWordAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Ljava/lang/String;");

    // GetMethodID(getAccessibleSentenceAtIndexFromContext)
    FIND_METHOD(getAccessibleSentenceAtIndexFromContextMethod, bridgeClass,
                "getAccessibleSentenceAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Ljava/lang/String;");

    // GetMethodID(getAccessibleTextSelectionStartFromContext)
    FIND_METHOD(getAccessibleTextSelectionStartFromContextMethod, bridgeClass,
                "getAccessibleTextSelectionStartFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleTextSelectionEndFromContext)
    FIND_METHOD(getAccessibleTextSelectionEndFromContextMethod, bridgeClass,
                "getAccessibleTextSelectionEndFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getAccessibleTextSelectedTextFromContext)
    FIND_METHOD(getAccessibleTextSelectedTextFromContextMethod, bridgeClass,
                "getAccessibleTextSelectedTextFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getAccessibleAttributesAtIndexFromContext)
    FIND_METHOD(getAccessibleAttributesAtIndexFromContextMethod, bridgeClass,
                "getAccessibleAttributesAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Ljava/lang/String;");

    // GetMethodID(getAccessibleAttributeSetAtIndexFromContext)
    FIND_METHOD(getAccessibleAttributeSetAtIndexFromContextMethod, bridgeClass,
                "getAccessibleAttributeSetAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Ljavax/swing/text/AttributeSet;");

    // GetMethodID(getAccessibleTextRectAtIndexFromContext)
    FIND_METHOD(getAccessibleTextRectAtIndexFromContextMethod, bridgeClass,
                "getAccessibleTextRectAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Ljava/awt/Rectangle;");

    // GetMethodID(getAccessibleXcoordTextRectAtIndexFromContext)
    FIND_METHOD(getAccessibleXcoordTextRectAtIndexFromContextMethod, bridgeClass,
                "getAccessibleXcoordTextRectAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleYcoordTextRectAtIndexFromContext)
    FIND_METHOD(getAccessibleYcoordTextRectAtIndexFromContextMethod, bridgeClass,
                "getAccessibleYcoordTextRectAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleHeightTextRectAtIndexFromContext)
    FIND_METHOD(getAccessibleHeightTextRectAtIndexFromContextMethod, bridgeClass,
                "getAccessibleHeightTextRectAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleWidthTextRectAtIndexFromContext)
    FIND_METHOD(getAccessibleWidthTextRectAtIndexFromContextMethod, bridgeClass,
                "getAccessibleWidthTextRectAtIndexFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getCaretLocationX)
    FIND_METHOD(getCaretLocationXMethod, bridgeClass,
                "getCaretLocationX",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getCaretLocationY)
    FIND_METHOD(getCaretLocationYMethod, bridgeClass,
                "getCaretLocationY",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getCaretLocationHeight)
    FIND_METHOD(getCaretLocationHeightMethod, bridgeClass,
                "getCaretLocationHeight",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getCaretLocationWidth)
    FIND_METHOD(getCaretLocationWidthMethod, bridgeClass,
                "getCaretLocationWidth",
                "(Ljavax/accessibility/AccessibleContext;)I");


    // GetMethodID(getAccessibleTextLineLeftBoundsFromContextMethod)
    FIND_METHOD(getAccessibleTextLineLeftBoundsFromContextMethod, bridgeClass,
                "getAccessibleTextLineLeftBoundsFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleTextLineRightBoundsFromContextMethod)
    FIND_METHOD(getAccessibleTextLineRightBoundsFromContextMethod, bridgeClass,
                "getAccessibleTextLineRightBoundsFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)I");

    // GetMethodID(getAccessibleTextRangeFromContextMethod)
    FIND_METHOD(getAccessibleTextRangeFromContextMethod, bridgeClass,
                "getAccessibleTextRangeFromContext",
                "(Ljavax/accessibility/AccessibleContext;II)Ljava/lang/String;");


    // ------- AccessibleValue methods

    // GetMethodID(getCurrentAccessibleValueFromContext)
    FIND_METHOD(getCurrentAccessibleValueFromContextMethod, bridgeClass,
                "getCurrentAccessibleValueFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getMaximumAccessibleValueFromContext)
    FIND_METHOD(getMaximumAccessibleValueFromContextMethod, bridgeClass,
                "getMaximumAccessibleValueFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    // GetMethodID(getMinimumAccessibleValueFromContext)
    FIND_METHOD(getMinimumAccessibleValueFromContextMethod, bridgeClass,
                "getMinimumAccessibleValueFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");


    // ------- AccessibleSelection methods

    // GetMethodID(addAccessibleSelectionFromContext)
    FIND_METHOD(addAccessibleSelectionFromContextMethod, bridgeClass,
                "addAccessibleSelectionFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)V");

    // GetMethodID(clearAccessibleSelectionFromContext)
    FIND_METHOD(clearAccessibleSelectionFromContextMethod, bridgeClass,
                "clearAccessibleSelectionFromContext",
                "(Ljavax/accessibility/AccessibleContext;)V");

    // GetMethodID(getAccessibleSelectionFromContext)
    FIND_METHOD(getAccessibleSelectionContextFromContextMethod, bridgeClass,
                "getAccessibleSelectionFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(getAccessibleSelectionCountFromContext)
    FIND_METHOD(getAccessibleSelectionCountFromContextMethod, bridgeClass,
                "getAccessibleSelectionCountFromContext",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(isAccessibleChildSelectedFromContext)
    FIND_METHOD(isAccessibleChildSelectedFromContextMethod, bridgeClass,
                "isAccessibleChildSelectedFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)Z");

    // GetMethodID(removeAccessibleSelectionFromContext)
    FIND_METHOD(removeAccessibleSelectionFromContextMethod, bridgeClass,
                "removeAccessibleSelectionFromContext",
                "(Ljavax/accessibility/AccessibleContext;I)V");

    // GetMethodID(selectAllAccessibleSelectionFromContext)
    FIND_METHOD(selectAllAccessibleSelectionFromContextMethod, bridgeClass,
                "selectAllAccessibleSelectionFromContext",
                "(Ljavax/accessibility/AccessibleContext;)V");


    // ------- Event Notification methods

    // GetMethodID(addJavaEventNotification)
    FIND_METHOD(addJavaEventNotificationMethod, bridgeClass,
                "addJavaEventNotification", "(J)V");

    // GetMethodID(removeJavaEventNotification)
    FIND_METHOD(removeJavaEventNotificationMethod, bridgeClass,
                "removeJavaEventNotification", "(J)V");

    // GetMethodID(addAccessibilityEventNotification)
    FIND_METHOD(addAccessibilityEventNotificationMethod, bridgeClass,
                "addAccessibilityEventNotification", "(J)V");

    // GetMethodID(removeAccessibilityEventNotification)
    FIND_METHOD(removeAccessibilityEventNotificationMethod, bridgeClass,
                "removeAccessibilityEventNotification", "(J)V");


    // ------- AttributeSet methods

    // GetMethodID(getBoldFromAttributeSet)
    FIND_METHOD(getBoldFromAttributeSetMethod, bridgeClass,
                "getBoldFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Z");

    // GetMethodID(getItalicFromAttributeSet)
    FIND_METHOD(getItalicFromAttributeSetMethod, bridgeClass,
                "getItalicFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Z");

    // GetMethodID(getUnderlineFromAttributeSet)
    FIND_METHOD(getUnderlineFromAttributeSetMethod, bridgeClass,
                "getUnderlineFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Z");

    // GetMethodID(getStrikethroughFromAttributeSet)
    FIND_METHOD(getStrikethroughFromAttributeSetMethod, bridgeClass,
                "getStrikethroughFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Z");

    // GetMethodID(getSuperscriptFromAttributeSet)
    FIND_METHOD(getSuperscriptFromAttributeSetMethod, bridgeClass,
                "getSuperscriptFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Z");

    // GetMethodID(getSubscriptFromAttributeSet)
    FIND_METHOD(getSubscriptFromAttributeSetMethod, bridgeClass,
                "getSubscriptFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Z");

    // GetMethodID(getBackgroundColorFromAttributeSet)
    FIND_METHOD(getBackgroundColorFromAttributeSetMethod, bridgeClass,
                "getBackgroundColorFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Ljava/lang/String;");

    // GetMethodID(getForegroundColorFromAttributeSet)
    FIND_METHOD(getForegroundColorFromAttributeSetMethod, bridgeClass,
                "getForegroundColorFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Ljava/lang/String;");

    // GetMethodID(getFontFamilyFromAttributeSet)
    FIND_METHOD(getFontFamilyFromAttributeSetMethod, bridgeClass,
                "getFontFamilyFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)Ljava/lang/String;");

    // GetMethodID(getFontSizeFromAttributeSet)
    FIND_METHOD(getFontSizeFromAttributeSetMethod, bridgeClass,
                "getFontSizeFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)I");

    // GetMethodID(getAlignmentFromAttributeSet)
    FIND_METHOD(getAlignmentFromAttributeSetMethod, bridgeClass,
                "getAlignmentFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)I");

    // GetMethodID(getBidiLevelFromAttributeSet)
    FIND_METHOD(getBidiLevelFromAttributeSetMethod, bridgeClass,
                "getBidiLevelFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)I");

    // GetMethodID(getFirstLineIndentFromAttributeSet)
    FIND_METHOD(getFirstLineIndentFromAttributeSetMethod, bridgeClass,
                "getFirstLineIndentFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)F");

    // GetMethodID(getLeftIndentFromAttributeSet)
    FIND_METHOD(getLeftIndentFromAttributeSetMethod, bridgeClass,
                "getLeftIndentFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)F");

    // GetMethodID(getRightIndentFromAttributeSet)
    FIND_METHOD(getRightIndentFromAttributeSetMethod, bridgeClass,
                "getRightIndentFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)F");

    // GetMethodID(getLineSpacingFromAttributeSet)
    FIND_METHOD(getLineSpacingFromAttributeSetMethod, bridgeClass,
                "getLineSpacingFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)F");

    // GetMethodID(getSpaceAboveFromAttributeSet)
    FIND_METHOD(getSpaceAboveFromAttributeSetMethod, bridgeClass,
                "getSpaceAboveFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)F");

    // GetMethodID(getSpaceBelowFromAttributeSet)
    FIND_METHOD(getSpaceBelowFromAttributeSetMethod, bridgeClass,
                "getSpaceBelowFromAttributeSet", "(Ljavax/swing/text/AttributeSet;)F");


    /**
     * Additional methods for Teton
     */

    // GetMethodID(requestFocus)
    FIND_METHOD(requestFocusMethod, bridgeClass,
                "requestFocus",
                "(Ljavax/accessibility/AccessibleContext;)Z");

    // GetMethodID(selectTextRange)
    FIND_METHOD(selectTextRangeMethod, bridgeClass,
                "selectTextRange",
                "(Ljavax/accessibility/AccessibleContext;II)Z");

    // GetMethodID(getVisibleChildrenCount)
    FIND_METHOD(getVisibleChildrenCountMethod, bridgeClass,
                "getVisibleChildrenCount",
                "(Ljavax/accessibility/AccessibleContext;)I");

    // GetMethodID(getVisibleChild)
    FIND_METHOD(getVisibleChildMethod, bridgeClass,
                "getVisibleChild",
                "(Ljavax/accessibility/AccessibleContext;I)Ljavax/accessibility/AccessibleContext;");

    // GetMethodID(setCaretPosition)
    FIND_METHOD(setCaretPositionMethod, bridgeClass,
                "setCaretPosition",
                "(Ljavax/accessibility/AccessibleContext;I)Z");

    // GetMethodID(getVirtualAccessibleNameFromContextMethod) Ben Key
    FIND_METHOD(getVirtualAccessibleNameFromContextMethod, bridgeClass,
                "getVirtualAccessibleNameFromContext",
                "(Ljavax/accessibility/AccessibleContext;)Ljava/lang/String;");

    return TRUE;
}

// Note for the following code which makes JNI upcalls...
//
// Problem, bug DB 16818166, JBS DB JDK-8015400
// AccessibleContext is a JOBJECT64 which is a jobject (32 bit pointer)
// for a Legacy (XP) build and a jlong (64 bits) for a -32 or -64 build.
// For the -32 build the lower 32 bits needs to be extracted into a jobject.
// Otherwise, if AccessibleContext is used directly what happens is that
// the JNI code consumes the lower 32 of its 64 bits and that is not a
// problem, but then when the JNI code consumes the next 32 bits for the
// reference to the role String it gets the higher 0x00000000 bits from
// the 64 bit JOBJECT64 AccessibleContext variable and thus a null reference
// is passed as the String reference.
//
// Solution:
// Cast the JOBJECT64 to a jobject.  For a 64 bit compile this is basically
// a noop, i.e. JOBJECT64 is a 64 bit jlong and a jobject is a 64 bit reference.
// For a 32 bit compile the cast drops the high order 32 bits, i.e. JOBJECT64
// is a 64 bit jlong and jobject is a 32 bit reference.  For a Legacy build
// JOBJECT64 is a jobject so this is also basically a noop.  The casts are
// done in the methods in JavaAccessBridge::processPackage.

// -----------------------------------

/**
 * isJavaWindow - returns whether the HWND is a Java window or not
 *
 */
BOOL
AccessBridgeJavaEntryPoints::isJavaWindow(jint window) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::isJavaWindow(%lX):", long{ window });

    if (isJavaWindowMethod != nullptr) {
        const jboolean returnVal = jniEnv->CallBooleanMethod(accessBridgeObject, isJavaWindowMethod, window);
        EXCEPTION_CHECK("Getting isJavaWindow - call to CallBooleanMethod()", FALSE);
        return (returnVal == JNI_TRUE) ? TRUE : FALSE;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or isJavaWindowMethod == 0");
        return FALSE;
    }
}

// -----------------------------------

/**
 * isSameObject - returns whether two object reference refer to the same object
 *
 */
BOOL
AccessBridgeJavaEntryPoints::isSameObject(jobject obj1, jobject obj2) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::isSameObject(%p, %p):", obj1, obj2);

    const jboolean returnVal = jniEnv->IsSameObject(obj1, obj2);
    EXCEPTION_CHECK("IsSameObject", FALSE);

    PrintDebugString(" [INFO]:   isSameObject returning %d", int{ returnVal });

    return (returnVal == JNI_TRUE) ? TRUE : FALSE;
}

// -----------------------------------

/**
 * getAccessibleContextFromHWND - returns the AccessibleContext, if any, for an HWND
 *
 */
jobject
AccessBridgeJavaEntryPoints::getAccessibleContextFromHWND(jint window) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleContextFromHWND(%lX):", long{ window });

    if (getAccessibleContextFromHWNDMethod != nullptr) {
        const jobject returnedAccessibleContext =
            jniEnv->CallObjectMethod(accessBridgeObject, getAccessibleContextFromHWNDMethod, window);
        EXCEPTION_CHECK("Getting AccessibleContextFromHWND - call to CallObjectMethod()", NULL);

        const jobject globalRef = jniEnv->NewGlobalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleContextFromHWND - call to NewGlobalRef()", NULL);

        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getAccessibleContextFromHWNDMethod == 0");
        return nullptr;
    }
}

// -----------------------------------

/**
 * getHWNDFromAccessibleContext - returns the HWND for an AccessibleContext, if any.
 * Returns nullptr on error.
 */
HWND
AccessBridgeJavaEntryPoints::getHWNDFromAccessibleContext(jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getHWNDFromAccessibleContext(%p):", accessibleContext);

    if (getHWNDFromAccessibleContextMethod != nullptr) {
        const HWND rHWND = (HWND)jniEnv->CallIntMethod(accessBridgeObject, getHWNDFromAccessibleContextMethod,
                                                       accessibleContext);
        EXCEPTION_CHECK("Getting HWNDFromAccessibleContext - call to CallIntMethod()", NULL);

        PrintDebugString(" [INFO]:   rHWND = %p", rHWND);

        return rHWND;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getHWNDFromAccessibleContextMethod == 0");
        return nullptr;
    }
}


/* ====== Utility methods ===== */

/**
 * Sets a text field to the specified string. Returns whether successful.
 */
BOOL
AccessBridgeJavaEntryPoints::setTextContents(const jobject accessibleContext, const wchar_t *text) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::setTextContents(%p, \"%ls\"):",
                     accessibleContext, text);

    if (setTextContentsMethod != nullptr) {
        // create a Java String for the text
        jstring textString = jniEnv->NewString(text, (jsize)wcslen(text));
        if (textString == nullptr) {
            PrintDebugString("[ERROR]:   NewString failed");
            return FALSE;
        }

        const jboolean result = (BOOL)jniEnv->CallBooleanMethod(accessBridgeObject, setTextContentsMethod,
                                                                accessibleContext, textString);
        EXCEPTION_CHECK("setTextContents - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   result = %d", int{ result });

        return (result == JNI_TRUE) ? TRUE : FALSE;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or setTextContentsMethod == 0");
        return FALSE;
    }
}

/**
 * Returns the Accessible Context of a Page Tab object that is the
 * ancestor of a given object. If the object is a Page Tab object
 * or a Page Tab ancestor object was found, returns the object
 * AccessibleContext.
 * If there is no ancestor object that has an Accessible Role of Page Tab,
 * returns nullptr.
 */
jobject
AccessBridgeJavaEntryPoints::getParentWithRole(const jobject accessibleContext, const wchar_t *role) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getParentWithRole(%p, %p):", accessibleContext, role);

    if (getParentWithRoleMethod != nullptr) {
        // create a Java String for the role
        jstring roleName = jniEnv->NewString(role, (jsize)wcslen(role));
        if (roleName == nullptr) {
            PrintDebugString("[ERROR]:   NewString failed");
            return FALSE;
        }

        const jobject rAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject, getParentWithRoleMethod,
                                                                    accessibleContext, roleName);
        EXCEPTION_CHECK("Getting ParentWithRole - call to CallObjectMethod()", NULL);

        PrintDebugString(" [INFO]:   rAccessibleContext = %p", rAccessibleContext);

        const jobject globalRef = jniEnv->NewGlobalRef(rAccessibleContext);
        EXCEPTION_CHECK("Getting ParentWithRole - call to NewGlobalRef()", FALSE);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         rAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getParentWithRoleMethod == 0");
        return nullptr;
    }
}

/**
 * Returns the Accessible Context for the top level object in
 * a Java Window. This is same Accessible Context that is obtained
 * from GetAccessibleContextFromHWND for that window.
 * Returns nullptr on error.
 */
jobject
AccessBridgeJavaEntryPoints::getTopLevelObject(const jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getTopLevelObject(%p):",
                     accessibleContext);

    if (getTopLevelObjectMethod != nullptr) {
        const jobject rAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                    getTopLevelObjectMethod,
                                                                    accessibleContext);
        EXCEPTION_CHECK("Getting TopLevelObject - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   rAccessibleContext = %p", rAccessibleContext);

        const jobject globalRef = jniEnv->NewGlobalRef(rAccessibleContext);
        EXCEPTION_CHECK("Getting TopLevelObject - call to NewGlobalRef()", FALSE);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         rAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getTopLevelObjectMethod == 0");
        return nullptr;
    }
}

/**
 * If there is an Ancestor object that has an Accessible Role of
 * Internal Frame, returns the Accessible Context of the Internal
 * Frame object. Otherwise, returns the top level object for that
 * Java Window.
 * Returns nullptr on error.
 */
jobject
AccessBridgeJavaEntryPoints::getParentWithRoleElseRoot(const jobject accessibleContext, const wchar_t *role) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getParentWithRoleElseRoot(%p, %p):",
                     accessibleContext, role);

    if (getParentWithRoleElseRootMethod != nullptr) {

        // create a Java String for the role
        jstring roleName = jniEnv->NewString(role, (jsize)wcslen(role));
        if (roleName == nullptr) {
            PrintDebugString("[ERROR]:   NewString failed");
            return nullptr;
        }

        const jobject rAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                    getParentWithRoleElseRootMethod,
                                                                    accessibleContext, roleName);
        EXCEPTION_CHECK("Getting ParentWithRoleElseRoot - call to CallObjectMethod()", NULL);

        PrintDebugString(" [INFO]:   rAccessibleContext = %p", rAccessibleContext);

        const jobject globalRef = jniEnv->NewGlobalRef(rAccessibleContext);
        EXCEPTION_CHECK("Getting ParentWithRoleElseRoot - call to NewGlobalRef()", FALSE);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         rAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getParentWithRoleElseRootMethod == 0");
        return nullptr;
    }
}

/**
 * Returns how deep in the object hierarchy a given object is.
 * The top most object in the object hierarchy has an object depth of 0.
 * Returns -1 on error.
 */
jint
AccessBridgeJavaEntryPoints::getObjectDepth(const jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getObjectDepth(%p):",
                     accessibleContext);

    if (getObjectDepthMethod != nullptr) {
        const jint rResult = jniEnv->CallIntMethod(accessBridgeObject,
                                                   getObjectDepthMethod,
                                                   accessibleContext);
        EXCEPTION_CHECK("Getting ObjectDepth - call to CallIntMethod()", -1);

        PrintDebugString(" [INFO]:   rResult = %d", rResult);
        return rResult;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getObjectDepthMethod == 0");
        return -1;
    }
}



/**
 * Returns the Accessible Context of the current ActiveDescendent of an object.
 * Returns nullptr on error.
 */
jobject
AccessBridgeJavaEntryPoints::getActiveDescendent(const jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getActiveDescendent(%p):",
                     accessibleContext);

    if (getActiveDescendentMethod != nullptr) {
        const jobject rAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                    getActiveDescendentMethod,
                                                                    accessibleContext);
        EXCEPTION_CHECK("Getting ActiveDescendent - call to CallObjectMethod()", NULL);

        PrintDebugString(" [INFO]:   rAccessibleContext = %p", rAccessibleContext);

        const jobject globalRef = jniEnv->NewGlobalRef(rAccessibleContext);
        EXCEPTION_CHECK("Getting ActiveDescendant - call to NewGlobalRef()", FALSE);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         rAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getActiveDescendentMethod == 0");
        return nullptr;
    }
}

/**
 * Additional methods for Teton
 */

/**
 * Returns an AccessibleName for a component using an algorithm optimized
 * for the JAWS screen reader by Ben Key (Freedom Scientific). This method
 * is only intended for JAWS. All other uses are entirely optional.
 *
 * Bug ID 4916682 - Implement JAWS AccessibleName policy
 */
BOOL
AccessBridgeJavaEntryPoints::getVirtualAccessibleName (
    IN const jobject object,
    OUT wchar_t * const name,
    IN const int nameSize)
{
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getVirtualAccessibleName(%p, %p, %d):",
                     object, name, nameSize);

    // Parameter validation
    if ((name == nullptr) || (nameSize == 0)) {
        return FALSE;
    }

    ::memset (name, 0, nameSize * sizeof (wchar_t));

    if (nullptr == object) {
        return FALSE;
    }

    if (getVirtualAccessibleNameFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getVirtualAccessibleNameFromContextMethod,
                                                              object);
        EXCEPTION_CHECK("Getting AccessibleName - call to CallObjectMethod()", FALSE);

        if (js != nullptr)
        {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, name, nameSize);
            EXCEPTION_CHECK("Getting AccessibleName - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleName - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Name = \"%ls\"", name);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleName - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [INFO]:   Accessible Name is null.");
        }
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getVirtualAccessibleNameFromContextMethod == 0");
        return FALSE;
    }

    if ( 0 != name [0] ) {
        return TRUE;
    }

    return FALSE;
}


/**
 * Request focus for a component. Returns whether successful;
 *
 * Bug ID 4944757 - requestFocus method needed
 */
BOOL
AccessBridgeJavaEntryPoints::requestFocus(const jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::requestFocus(%p):",
                     accessibleContext);

    if (requestFocusMethod != nullptr) {
        const jboolean result = jniEnv->CallBooleanMethod(accessBridgeObject,
                                                          requestFocusMethod,
                                                          accessibleContext);
        EXCEPTION_CHECK("requestFocus - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   result = %d", int{ result });

        return (result == JNI_TRUE) ? TRUE : FALSE;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or requestFocusMethod == 0");
        return FALSE;
    }
}

/**
 * Selects text between two indices.  Selection includes the text at the start index
 * and the text at the end index. Returns whether successful;
 *
 * Bug ID 4944758 - selectTextRange method needed
 */
BOOL
AccessBridgeJavaEntryPoints::selectTextRange(const jobject accessibleContext, int startIndex, int endIndex) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::selectTextRange(%p start = %d end = %d):",
                     accessibleContext, startIndex, endIndex);

    if (selectTextRangeMethod != nullptr) {
        const jboolean result = jniEnv->CallBooleanMethod(accessBridgeObject,
                                                          selectTextRangeMethod,
                                                          accessibleContext,
                                                          startIndex, endIndex);
        EXCEPTION_CHECK("selectTextRange - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   result = %d", result);
        return (result == JNI_TRUE) ? TRUE : FALSE;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or selectTextRangeMethod == 0");
        return FALSE;
    }
}

/**
 * Returns whether two text attributes are the same.
 */
static BOOL CompareAccessibleTextAttributesInfo(AccessibleTextAttributesInfo *one,
                                                AccessibleTextAttributesInfo *two) {
    return (one->bold == two->bold
            && one->italic == two->italic
            && one->underline == two->underline
            && one->strikethrough == two->strikethrough
            && one->superscript == two->superscript
            && one->subscript == two->subscript
            && one->fontSize == two->fontSize
            && one->alignment == two->alignment
            && one->bidiLevel == two->bidiLevel
            && one->firstLineIndent == two->firstLineIndent
            && one->leftIndent == two->leftIndent
            && one->rightIndent == two->rightIndent
            && one->lineSpacing == two->lineSpacing
            && one->spaceAbove == two->spaceAbove
            && one->spaceBelow == two->spaceBelow
            && !wcscmp(one->backgroundColor,two->backgroundColor)
            && !wcscmp(one->foregroundColor,two->foregroundColor)
            && !wcscmp(one->fullAttributesString,two->fullAttributesString));
}

/**
 * Get text attributes between two indices.
 *
 * Only one AccessibleTextAttributesInfo structure is passed - which
 * contains the attributes for the first character, the function then goes
 * through the following characters in the range specified and stops when the
 * attributes are different from the first, it then returns in the passed
 * parameter len the number of characters with the attributes returned. In most
 * situations this will be all the characters, and if not the calling program
 * can easily get the attributes for the next characters with different
 * attributes
 *
 * Bug ID 4944761 - getTextAttributes between two indices method needed
 */

/* NEW FASTER CODE!!*/
BOOL
AccessBridgeJavaEntryPoints::getTextAttributesInRange(
    const jobject accessibleContext,
    const int startIndex,
    const int endIndex,
    AccessibleTextAttributesInfo * const attributes,
    short * const len
) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getTextAttributesInRange(%p, start=%d, end=%d, %p, %p):",
                     accessibleContext, startIndex, endIndex, attributes, len);

    *len = 0;

    BOOL result = getAccessibleTextAttributes((jobject)accessibleContext, startIndex, attributes);
    if (result != TRUE) {
        return FALSE;
    }

    (*len)++;

    for (jint i = startIndex + 1; i <= endIndex; ++i) {
        AccessibleTextAttributesInfo test_attributes = *attributes;

        // Get the full test_attributes string at i
        if (getAccessibleAttributesAtIndexFromContextMethod != nullptr) {
            PrintDebugString(" [INFO]:   getting full test_attributes string from Context...");

            const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleAttributesAtIndexFromContextMethod,
                                                                 accessibleContext, i);
            EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - call to CallObjectMethod()", FALSE);
            PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);

            if (js != nullptr) {
                (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, test_attributes.fullAttributesString);
                EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - attempt to copy the java string content", FALSE);

                jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
                EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - call to CallVoidMethod()", FALSE);

                wPrintDebugString(L" [INFO]:   Accessible Text attributes = \"%ls\"", test_attributes.fullAttributesString);

                jniEnv->DeleteLocalRef(js);
                EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - call to DeleteLocalRef()", FALSE);
            } else {
                PrintDebugString(" [WARN]:   Accessible Text attributes is null.");
                test_attributes.fullAttributesString[0] = wchar_t{ 0 };
                return FALSE;
            }
        } else {
            PrintDebugString("[ERROR]:   either env == 0 or getAccessibleAttributesAtIndexFromContextMethod == 0");
            return FALSE;
        }

        if (wcscmp(attributes->fullAttributesString, test_attributes.fullAttributesString) != 0)
            break;

        if (result != TRUE) {
            return FALSE;
        }

        (*len)++;
    }
    return TRUE;
}

/**
 * Returns the number of visible children of a component
 *
 * Bug ID 4944762- getVisibleChildren for list-like components needed
 */
int
AccessBridgeJavaEntryPoints::getVisibleChildrenCount(const jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getVisibleChildrenCount(%p)",
                     accessibleContext);

    // get the visible children count
    const int numChildren = jniEnv->CallIntMethod(accessBridgeObject, getVisibleChildrenCountMethod,
                                                  accessibleContext);
    EXCEPTION_CHECK("Getting visible children count - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   visible children count = %d", numChildren);

    return numChildren;
}


/**
 * This method is used to iterate through the visible children of a component.  It
 * returns visible children information for a component starting at nStartIndex.
 * No more than MAX_VISIBLE_CHILDREN VisibleChildrenInfo objects will
 * be returned for each call to this method. Returns FALSE on error.
 *
 * Bug ID 4944762- getVisibleChildren for list-like components needed
 */
BOOL AccessBridgeJavaEntryPoints::getVisibleChildren(const jobject accessibleContext,
                                                     const int nStartIndex,
                                                     /* OUT */ VisibleChildrenInfo * const visibleChildrenInfo) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getVisibleChildren(%p, startIndex=%d, %p)",
                     accessibleContext, nStartIndex, visibleChildrenInfo);

    // get the visible children count
    const int numChildren = jniEnv->CallIntMethod(accessBridgeObject, getVisibleChildrenCountMethod, accessibleContext);
    EXCEPTION_CHECK("Getting visible children count - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   visible children count = %d", numChildren);

    if (nStartIndex >= numChildren) {
        return FALSE;
    }

    // get the visible children
    int bufIndex = 0;
    for (int i = nStartIndex; (i < numChildren) && (i < nStartIndex + MAX_VISIBLE_CHILDREN); i++) {
        PrintDebugString(" [INFO]:   getting visible child %d...", i);

        // get the visible child at index i
        const jobject ac = jniEnv->CallObjectMethod(accessBridgeObject, getVisibleChildMethod,
                                                    accessibleContext, i);
        EXCEPTION_CHECK("getVisibleChildMethod - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(ac);
        EXCEPTION_CHECK("getVisibleChildMethod - call to NewGlobalRef()", FALSE);

        visibleChildrenInfo->children[bufIndex] = (JOBJECT64)globalRef;
        PrintDebugString(" [INFO]:   visible child = %p", globalRef);

        bufIndex++;
    }
    visibleChildrenInfo->returnedChildrenCount = bufIndex;

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getVisibleChildren succeeded");

    return TRUE;
}

/**
 * Set the caret to a text position. Returns whether successful.
 *
 * Bug ID 4944770 - setCaretPosition method needed
 */
BOOL
AccessBridgeJavaEntryPoints::setCaretPosition(const jobject accessibleContext, int position) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::setCaretPosition(%p, position=%d):",
                     accessibleContext, position);

    if (setCaretPositionMethod != nullptr) {
        const jboolean result = jniEnv->CallBooleanMethod(accessBridgeObject,
                                                          setCaretPositionMethod,
                                                          accessibleContext, position);
        EXCEPTION_CHECK("setCaretPosition - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   result = %d", int{ result });

        return (result == JNI_TRUE) ? TRUE : FALSE;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or setCaretPositionMethod == 0");
        return FALSE;
    }
}


// -----------------------------------

/**
 * getVersionInfo - returns the version string of the java.version property
 *                  and the AccessBridge.java version
 *
 */
BOOL
AccessBridgeJavaEntryPoints::getVersionInfo(AccessBridgeVersionInfo *info) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getVersionInfo(%p):", info);

    if (getJavaVersionPropertyMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject, getJavaVersionPropertyMethod);
        EXCEPTION_CHECK("Getting JavaVersionProperty - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->bridgeJavaDLLVersion);
            EXCEPTION_CHECK("Getting JavaVersionProperty - attempt to copy the java string content (to bridgeJavaDLLVersion)", FALSE);

            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->VMversion);
            EXCEPTION_CHECK("Getting JavaVersionProperty - attempt to copy the java string content (to VMversion)", FALSE);

            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->bridgeJavaClassVersion);
            EXCEPTION_CHECK("Getting JavaVersionProperty - attempt to copy the java string content (to bridgeJavaClassVersion)", FALSE);

            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->bridgeWinDLLVersion);
            EXCEPTION_CHECK("Getting JavaVersionProperty - attempt to copy the java string content (to bridgeWinDLLVersion)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting JavaVersionProperty - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Java version = \"%ls\"", info->VMversion);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting JavaVersionProperty - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Java version is null.");
            info->VMversion[0] = wchar_t{ 0 };
            return FALSE;
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getJavaVersionPropertyMethod == 0");
        return FALSE;
    }

    return TRUE;
}


/*
 * Verifies the Java VM still exists and obj is an
 * instance of AccessibleText
 */
BOOL AccessBridgeJavaEntryPoints::verifyAccessibleText(jobject obj) {
    JavaVM *vm = nullptr;
    BOOL retval = FALSE;

    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::verifyAccessibleText");

    if (jniEnv->GetJavaVM(&vm) != 0) {
        PrintDebugString("[ERROR]:   No Java VM");
        return FALSE;
    }

    if (obj == nullptr) {
        PrintDebugString("[ERROR]:   Null jobject");
        return FALSE;
    }

    // Copied from getAccessibleContextInfo
    if (getAccessibleTextFromContextMethod != nullptr) {
        const jobject returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleTextFromContextMethod,
                                                                 obj);
        EXCEPTION_CHECK("Getting AccessibleText - call to CallObjectMethod()", FALSE);

        PrintDebugString("[ERROR]:   AccessibleText = %p", returnedJobject);

        retval = (returnedJobject == nullptr) ? FALSE : TRUE;

        jniEnv->DeleteLocalRef(returnedJobject);
        EXCEPTION_CHECK("Getting AccessibleText - call to DeleteLocalRef()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTextFromContextMethod == 0");
        return FALSE;
    }

    if (retval == FALSE) {
        PrintDebugString("[ERROR]:   jobject is not an AccessibleText");
    }

    return retval;
}


/********** AccessibleContext routines ***********************************/

/**
 * getAccessibleContextAt - performs the Java method call:
 *   Accessible AccessBridge.getAccessibleContextAt(x, y)
 *
 * Note: this call explicitly goes through the AccessBridge,
 * so that it can keep a reference the returned jobject for the JavaVM.
 * You must explicitly call releaseJavaObject() when you are through using
 * the Accessible returned, to let the AccessBridge know it can release the
 * object, so that the can then garbage collect it.
 *
 */
jobject
AccessBridgeJavaEntryPoints::getAccessibleContextAt(jint x, jint y, jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleContextAt(%d, %d, %p):",
                     x, y, accessibleContext);

    if (getAccessibleContextAtMethod != nullptr) {
        const jobject returnedAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                           getAccessibleContextAtMethod,
                                                                           x, y, accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleContextAt - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleContextAt - call to NewGlobalRef()", FALSE);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         returnedAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleContextAtMethod == 0");
        return nullptr;
    }
}

/**
 * getAccessibleWithFocus - performs the Java method calls:
 *   Accessible Translator.getAccessible(SwingEventMonitor.getComponentWithFocus())
 *
 * Note: this call explicitly goes through the AccessBridge,
 * so that the AccessBridge can hide expected changes in how this functions
 * between JDK 1.1.x w/AccessibilityUtility classes, and JDK 1.2, when some
 * of this functionality may be built into the platform
 *
 */
jobject
AccessBridgeJavaEntryPoints::getAccessibleContextWithFocus() {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleContextWithFocus()");

    if (getAccessibleContextWithFocusMethod != nullptr) {
        const jobject returnedAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                           getAccessibleContextWithFocusMethod);
        EXCEPTION_CHECK("Getting AccessibleContextWithFocus - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleContextWithFocus - call to NewGlobalRef()", FALSE);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         returnedAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or getAccessibleContextWithFocusMethod == 0");
        return nullptr;
    }
}

/**
 * getAccessibleContextInfo - fills a struct with a bunch of information
 * contained in the Java Accessibility API
 *
 * Note: if the AccessibleContext parameter is bogus, this call will blow up
 *
 * Note: this call explicitly goes through the AccessBridge,
 * so that it can keep a reference the returned jobject for the JavaVM.
 * You must explicitly call releaseJavaObject() when you are through using
 * the AccessibleContext returned, to let the AccessBridge know it can release the
 * object, so that the JavaVM can then garbage collect it.
 */
BOOL
AccessBridgeJavaEntryPoints::getAccessibleContextInfo(jobject accessibleContext, AccessibleContextInfo *info) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleContextInfo(%p):", accessibleContext);

    ZeroMemory(info, sizeof(AccessibleContextInfo));

    if (accessibleContext == nullptr) {
        PrintDebugString("[ERROR]:   passed in AccessibleContext == null!");
        return FALSE;
    }

    // Get the Accessible Name
    if (getAccessibleNameFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleNameFromContextMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleName - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->name);
            EXCEPTION_CHECK("Getting AccessibleName - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleName - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Name = \"%ls\"", info->name);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleName - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible Name is null.");
            info->name[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleNameFromContextMethod == 0");
        return FALSE;
    }

    // Get the Accessible Description
    if (getAccessibleDescriptionFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleDescriptionFromContextMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleDescription - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->description);
            EXCEPTION_CHECK("Getting AccessibleName - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleName - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Description = \"%ls\"", info->description);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleName - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible Description is null.");
            info->description[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleDescriptionFromContextMethod == 0");
        return FALSE;
    }

    // Get the Accessible Role String
    if (getAccessibleRoleStringFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleRoleStringFromContextMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleRole - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->role);
            EXCEPTION_CHECK("Getting AccessibleRole - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleRole - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Role = \"%ls\"", info->role);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleRole - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible Role is null.");
            info->role[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleRoleStringFromContextMethod == 0");
        return FALSE;
    }

    // Get the Accessible Role String in the en_US locale
    if (getAccessibleRoleStringFromContext_en_USMethod != nullptr) {
        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleRoleStringFromContext_en_USMethod,
                                                             accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleRole_en_US - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->role_en_US);
            EXCEPTION_CHECK("Getting AccessibleRole_en_US - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleRole_en_US - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Role en_US = \"%ls\"", info->role_en_US);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleRole_en_US - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible Role en_US is null.");
            info->role[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleRoleStringFromContext_en_USMethod == 0");
        return FALSE;
    }

    // Get the Accessible States String
    if (getAccessibleStatesStringFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleStatesStringFromContextMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleState - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->states);
            EXCEPTION_CHECK("Getting AccessibleState - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleState - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible States = \"%ls\"", info->states);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleState - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible States is null.");
            info->states[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleStatesStringFromContextMethod == 0");
        return FALSE;
    }

    // Get the Accessible States String in the en_US locale
    if (getAccessibleStatesStringFromContext_en_USMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleStatesStringFromContext_en_USMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleState_en_US - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->states_en_US);
            EXCEPTION_CHECK("Getting AccessibleState_en_US - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleState_en_US - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible States en_US = \"%ls\"", info->states_en_US);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleState_en_US - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible States en_US is null.");
            info->states[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleStatesStringFromContext_en_USMethod == 0");
        return FALSE;
    }

    // Get the index in Parent
    if (getAccessibleIndexInParentFromContextMethod != nullptr) {
        info->indexInParent = jniEnv->CallIntMethod(accessBridgeObject,
                                                    getAccessibleIndexInParentFromContextMethod,
                                                    accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleIndexInParent - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   Index in Parent = %ld", long{ info->indexInParent });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleIndexInParentFromContextMethod == 0");
        return FALSE;
    }

    PrintDebugString(" [INFO]:   jniEnv = %p; accessBridgeObject = %p; AccessibleContext = %p",
                     jniEnv, accessBridgeObject, accessibleContext);

    // Get the children count
    if (getAccessibleChildrenCountFromContextMethod != nullptr) {
        info->childrenCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                    getAccessibleChildrenCountFromContextMethod,
                                                    accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleChildrenCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   Children count = %ld", long{ info->childrenCount });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleChildrenCountFromContextMethod == 0");
        return FALSE;
    }

    PrintDebugString(" [INFO]:   jniEnv = %p; accessBridgeObject = %p; AccessibleContext = %p",
                     jniEnv, accessBridgeObject, accessibleContext);

    // Get the x coord
    if (getAccessibleXcoordFromContextMethod != nullptr) {
        info->x = jniEnv->CallIntMethod(accessBridgeObject,
                                        getAccessibleXcoordFromContextMethod,
                                        accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleXcoord - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   X coord = %ld", long{ info->x });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleXcoordFromContextMethod == 0");
        return FALSE;
    }

    PrintDebugString(" [INFO]:   jniEnv = %X; accessBridgeObject = %X; AccessibleContext = %p",
                     jniEnv, accessBridgeObject, accessibleContext);

    // Get the y coord
    if (getAccessibleYcoordFromContextMethod != nullptr) {
        info->y = jniEnv->CallIntMethod(accessBridgeObject,
                                        getAccessibleYcoordFromContextMethod,
                                        accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleYcoord - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   Y coord = %ld", long{ info->y });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleYcoordFromContextMethod == 0");
        return FALSE;
    }

    // Get the width
    if (getAccessibleWidthFromContextMethod != nullptr) {
        info->width = jniEnv->CallIntMethod(accessBridgeObject,
                                            getAccessibleWidthFromContextMethod,
                                            accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleWidth - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   Width = %ld", long{ info->width });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleWidthFromContextMethod == 0");
        return FALSE;
    }

    // Get the height
    if (getAccessibleHeightFromContextMethod != nullptr) {
        info->height = jniEnv->CallIntMethod(accessBridgeObject,
                                             getAccessibleHeightFromContextMethod,
                                             accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleHeight - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   Height = %ld", long{ info->height });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleHeightFromContextMethod == 0");
        return FALSE;
    }

    // Get the AccessibleComponent
    if (getAccessibleComponentFromContextMethod != nullptr) {
        const jobject returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleComponentFromContextMethod,
                                                                 accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleComponent - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   AccessibleComponent = %p", returnedJobject);

        info->accessibleComponent = (returnedJobject != nullptr ? TRUE : FALSE);

        jniEnv->DeleteLocalRef(returnedJobject);
        EXCEPTION_CHECK("Getting AccessibleComponent - call to DeleteLocalRef()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleComponentFromContextMethod == 0");
        return FALSE;
    }

    // Get the AccessibleAction
    if (getAccessibleActionFromContextMethod != nullptr) {
        const jobject returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleActionFromContextMethod,
                                                                 accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleAction - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   AccessibleAction = %p", returnedJobject);

        info->accessibleAction = (returnedJobject != nullptr ? TRUE : FALSE);

        jniEnv->DeleteLocalRef(returnedJobject);
        EXCEPTION_CHECK("Getting AccessibleAction - call to DeleteLocalRef()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleActionFromContextMethod == 0");
        return FALSE;
    }

    // Get the AccessibleSelection
    if (getAccessibleSelectionFromContextMethod != nullptr) {
        const jobject returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleSelectionFromContextMethod,
                                                                 accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleSelection - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   AccessibleSelection = %p", returnedJobject);

        info->accessibleSelection = (returnedJobject != nullptr ? TRUE : FALSE);

        jniEnv->DeleteLocalRef(returnedJobject);
        EXCEPTION_CHECK("Getting AccessibleSelection - call to DeleteLocalRef()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleSelectionFromContextMethod == 0");
        return FALSE;
    }

    // Get the AccessibleTable
    if (getAccessibleTableFromContextMethod != nullptr) {
        PrintDebugString(" [INFO]:   calling getAccessibleTableFromContextMethod ...");

        const jobject returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleTableFromContextMethod,
                                                                 accessibleContext);
        PrintDebugString(" [INFO]:   returned from getAccessibleTableFromContextMethod");
        EXCEPTION_CHECK("Getting AccessibleTable - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   AccessibleTable = %p", returnedJobject);

        if (returnedJobject != nullptr) {
            info->accessibleInterfaces |= cAccessibleTableInterface;
        }

        jniEnv->DeleteLocalRef(returnedJobject);
        EXCEPTION_CHECK("Getting AccessibleTable - call to DeleteLocalRef()", FALSE);

        /*
          returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
          getAccessibleTableFromContextMethod,
          AccessibleContext);
          PrintDebugString(" [INFO]:   returned from getAccessibleTableFromContextMethod");
          EXCEPTION_CHECK("Getting AccessibleTable - call to CallObjectMethod()", FALSE);
          PrintDebugString(" [INFO]:   AccessibleTable = %p", returnedJobject);
          info->accessibleTable = returnedJobject;
        */

    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableFromContextMethod == 0");
        return FALSE;
    }

    // Get the AccessibleText
    if (getAccessibleTextFromContextMethod != nullptr) {
        const jobject returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleTextFromContextMethod,
                                                                 accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleText - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   AccessibleText = %p", returnedJobject);

        info->accessibleText = (returnedJobject != nullptr ? TRUE : FALSE);

        jniEnv->DeleteLocalRef(returnedJobject);
        EXCEPTION_CHECK("Getting AccessibleText - call to DeleteLocalRef()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTextFromContextMethod == 0");
        return FALSE;
    }

    // Get the AccessibleValue
    if (getAccessibleValueFromContextMethod != nullptr) {
        const jobject returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleValueFromContextMethod,
                                                                 accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleValue - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   AccessibleValue = %p", returnedJobject);

        if (returnedJobject != nullptr) {
            info->accessibleInterfaces |= cAccessibleValueInterface;
        }

        jniEnv->DeleteLocalRef(returnedJobject);
        EXCEPTION_CHECK("Getting AccessibleValue - call to DeleteLocalRef()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleValueFromContextMethod == 0");
        return FALSE;
    }

    // FIX
    // get the AccessibleHypertext
    if (getAccessibleHypertextMethod           != nullptr &&
        getAccessibleHyperlinkCountMethod      != nullptr &&
        getAccessibleHyperlinkMethod           != nullptr &&
        getAccessibleHyperlinkTextMethod       != nullptr &&
        getAccessibleHyperlinkStartIndexMethod != nullptr &&
        getAccessibleHyperlinkEndIndexMethod   != nullptr) {
        const jobject returnedJobject = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                 getAccessibleHypertextMethod,
                                                                 accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleHypertext - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   AccessibleHypertext = %p", returnedJobject);

        if (returnedJobject != nullptr) {
            info->accessibleInterfaces |= cAccessibleHypertextInterface;
        }

        jniEnv->DeleteLocalRef(returnedJobject);
        EXCEPTION_CHECK("Getting AccessibleHypertext - call to DeleteLocalRef()", FALSE);
    }

    // set new accessibleInterfaces flags from old BOOL values
    if (info->accessibleComponent)
        info->accessibleInterfaces |= cAccessibleComponentInterface;
    if (info->accessibleAction)
        info->accessibleInterfaces |= cAccessibleActionInterface;
    if (info->accessibleSelection)
        info->accessibleInterfaces |= cAccessibleSelectionInterface;
    if (info->accessibleText)
        info->accessibleInterfaces |= cAccessibleTextInterface;
    // FIX END

    return TRUE;
}

/**
 * getAccessibleChildFromContext - performs the Java method call:
 *   AccessibleContext AccessBridge.getAccessibleChildContext(AccessibleContext)
 *
 * Note: if the AccessibleContext parameter is bogus, this call will blow up
 *
 * Note: this call explicitly goes through the AccessBridge,
 * so that it can keep a reference the returned jobject for the JavaVM.
 * You must explicitly call releaseJavaObject() when you are through using
 * the AccessibleContext returned, to let the AccessBridge know it can release the
 * object, so that the JavaVM can then garbage collect it.
 */
jobject
AccessBridgeJavaEntryPoints::getAccessibleChildFromContext(jobject accessibleContext, jint childIndex) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleChildContext(%p, %ld):",
                     accessibleContext, long{ childIndex });

    if (getAccessibleChildFromContextMethod != nullptr) {
        const jobject returnedAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleChildFromContextMethod,
                                                             accessibleContext, childIndex);
        EXCEPTION_CHECK("Getting AccessibleChild - call to CallObjectMethod()", NULL);

        const jobject globalRef = jniEnv->NewGlobalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleChild - call to NewGlobalRef()", NULL);

        jniEnv->DeleteLocalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleChild - call to DeleteLocalRef()", NULL);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         returnedAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleChildContextMethod == 0");
        return nullptr;
    }
}

/**
 * getAccessibleParentFromContext - returns the AccessibleContext parent
 *
 */
jobject
AccessBridgeJavaEntryPoints::getAccessibleParentFromContext(jobject accessibleContext)
{
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleParentFromContext(%p):", accessibleContext);

    if (getAccessibleParentFromContextMethod != nullptr) {
        const jobject returnedAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                           getAccessibleParentFromContextMethod,
                                                                           accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleParent - call to CallObjectMethod()", NULL);

        const jobject globalRef = jniEnv->NewGlobalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleParent - call to NewGlobalRef()", NULL);

        jniEnv->DeleteLocalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleParent - call to DeleteLocalRef()", NULL);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         returnedAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleParentFromContextMethod == 0");
        return nullptr;
    }
}


/********** AccessibleTable routines **********************************/

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTableInfo(jobject accessibleContext,
                                                    AccessibleTableInfo *tableInfo) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableInfo(%p):",
                     accessibleContext);

    // get the table row count
    if (getAccessibleTableRowCountMethod != nullptr) {
        tableInfo->rowCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                    getAccessibleTableRowCountMethod,
                                                    accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTableRowCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table row count = %ld", long{ tableInfo->rowCount });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleRowCountMethod == 0");
        return FALSE;
    }

    // get the table column count
    if (getAccessibleTableColumnCountMethod != nullptr) {
        tableInfo->columnCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                       getAccessibleTableColumnCountMethod,
                                                       accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTableColumnCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table column count = %ld", long{ tableInfo->columnCount });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableColumnCountMethod == 0");
        return FALSE;
    }

    // get the AccessibleTable
    if (getAccessibleTableFromContextMethod != nullptr) {
        PrintDebugString(" [INFO]:   calling getAccessibleTableFromContextMethod ...");

        const jobject accTable = jniEnv->CallObjectMethod(accessBridgeObject,
                                                          getAccessibleTableFromContextMethod,
                                                          accessibleContext);
        PrintDebugString(" [INFO]:   returned from getAccessibleTableFromContextMethod");
        EXCEPTION_CHECK("Getting AccessibleTable - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(accTable);
        EXCEPTION_CHECK("Getting AccessibleTable - call to NewGlobalRef()", FALSE);

        tableInfo->accessibleTable = (JOBJECT64)globalRef;

        PrintDebugString(" [INFO]:   accessibleTable = %p", globalRef);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableFromContextMethod == 0");
        return FALSE;
    }

    // cache the AccessibleContext
    if (getContextFromAccessibleTableMethod != nullptr) {
        PrintDebugString(" [INFO]:   calling getContextFromAccessibleTable Method ...");

        const jobject ac = jniEnv->CallObjectMethod(accessBridgeObject,
                                                    getContextFromAccessibleTableMethod,
                                                    accessibleContext);
        PrintDebugString(" [INFO]:   returned from getContextFromAccessibleTable Method");
        EXCEPTION_CHECK("Getting AccessibleTable - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(ac);
        EXCEPTION_CHECK("Getting AccessibleTable - call to NewGlobalRef()", FALSE);

        tableInfo->accessibleContext = (JOBJECT64)globalRef;

        PrintDebugString(" [INFO]:   accessibleContext = %p", globalRef);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getContextFromAccessibleTable Method == 0");
        return FALSE;
    }

    // FIX - set unused elements
    tableInfo->caption = NULL;
    tableInfo->summary = NULL;

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleTableInfo succeeded");
    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTableCellInfo(jobject accessibleTable, jint row, jint column,
                                                        AccessibleTableCellInfo *tableCellInfo) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableCellInfo(%p): row=%d, column=%d",
                     accessibleTable, row, column);

    ZeroMemory(tableCellInfo, sizeof(AccessibleTableCellInfo));
    tableCellInfo->row = row;
    tableCellInfo->column = column;

    // get the table cell index
    if (getAccessibleTableCellIndexMethod != nullptr) {
        tableCellInfo->index = jniEnv->CallIntMethod(accessBridgeObject,
                                                     getAccessibleTableCellIndexMethod,
                                                     accessibleTable, row, column);
        EXCEPTION_CHECK("Getting AccessibleTableCellIndex - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table cell index = %ld", long{ tableCellInfo->index });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableCellIndexMethod == 0");
        return FALSE;
    }

    // get the table cell row extent
    if (getAccessibleTableCellRowExtentMethod != nullptr) {
        tableCellInfo->rowExtent = jniEnv->CallIntMethod(accessBridgeObject,
                                                         getAccessibleTableCellRowExtentMethod,
                                                         accessibleTable, row, column);
        EXCEPTION_CHECK("Getting AccessibleTableCellRowExtentCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table cell row extent = %ld", long{ tableCellInfo->rowExtent });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableCellRowExtentMethod == 0");
        return FALSE;
    }

    // get the table cell column extent
    if (getAccessibleTableCellColumnExtentMethod != nullptr) {
        tableCellInfo->columnExtent = jniEnv->CallIntMethod(accessBridgeObject,
                                                            getAccessibleTableCellColumnExtentMethod,
                                                            accessibleTable, row, column);
        EXCEPTION_CHECK("Getting AccessibleTableCellColumnExtentCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table cell column extent = %d", long{ tableCellInfo->columnExtent });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableCellColumnExtentMethod == 0");
        return FALSE;
    }

    // get whether the table cell is selected
    if (isAccessibleTableCellSelectedMethod != nullptr) {
        tableCellInfo->isSelected = jniEnv->CallBooleanMethod(accessBridgeObject,
                                                              isAccessibleTableCellSelectedMethod,
                                                              accessibleTable, row, column);
        EXCEPTION_CHECK("Getting isAccessibleTableCellSelected - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   table cell isSelected = %d", int{ tableCellInfo->isSelected });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or isAccessibleTableCellSelectedMethod == 0");
        return FALSE;
    }

    // get the table cell AccessibleContext
    if (getAccessibleTableCellAccessibleContextMethod != nullptr) {
        const jobject tableCellAC = jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleTableCellAccessibleContextMethod,
                                                             accessibleTable, row, column);
        EXCEPTION_CHECK("Getting AccessibleTableCellAccessibleContext - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(tableCellAC);
        EXCEPTION_CHECK("Getting AccessibleTableCellAccessibleContext - call to NewGlobalRef()", FALSE);

        tableCellInfo->accessibleContext = (JOBJECT64)globalRef;

        PrintDebugString(" [INFO]:   table cell AccessibleContext = %p", globalRef);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableCellAccessibleContextMethod == 0");
        return FALSE;
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleTableCellInfo succeeded");
    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTableRowHeader(jobject acParent, AccessibleTableInfo *tableInfo) {

    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableRowHeader(%p, %p):",
                     acParent, tableInfo);

    // get the header row count
    if (getAccessibleTableRowHeaderRowCountMethod != nullptr) {
        tableInfo->rowCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                    getAccessibleTableRowHeaderRowCountMethod,
                                                    acParent);
        EXCEPTION_CHECK("Getting AccessibleTableRowHeaderRowCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table row count = %ld", long{ tableInfo->rowCount });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleRowHeaderRowCountMethod == 0");
        return FALSE;
    }

    // get the header column count
    if (getAccessibleTableRowHeaderColumnCountMethod != nullptr) {
        tableInfo->columnCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                       getAccessibleTableRowHeaderColumnCountMethod,
                                                       acParent);
        EXCEPTION_CHECK("Getting AccessibleTableRowHeaderColumnCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table column count = %ld", long{ tableInfo->columnCount });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableRowHeaderColumnCountMethod == 0");
        return FALSE;
    }

    // get the header AccessibleTable
    if (getAccessibleTableRowHeaderMethod != nullptr) {
        const jobject accTable = jniEnv->CallObjectMethod(accessBridgeObject,
                                                          getAccessibleTableRowHeaderMethod,
                                                          acParent);
        EXCEPTION_CHECK("Getting AccessibleTableRowHeader - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(accTable);
        EXCEPTION_CHECK("Getting AccessibleTableRowHeader - call to NewGlobalRef()", FALSE);

        tableInfo->accessibleTable = (JOBJECT64)globalRef;
        PrintDebugString(" [INFO]:   row header AccessibleTable = %p", globalRef);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableRowHeaderMethod == 0");
        return FALSE;
    }

    // FIX - set unused elements
    tableInfo->caption = NULL;
    tableInfo->summary = NULL;
    tableInfo->accessibleContext = NULL;

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleTableRowHeader succeeded");
    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTableColumnHeader(jobject acParent, AccessibleTableInfo *tableInfo) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableColumnHeader(%p, %p):",
                     acParent, tableInfo);

    // get the header row count
    if (getAccessibleTableColumnHeaderRowCountMethod != nullptr) {
        tableInfo->rowCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                    getAccessibleTableColumnHeaderRowCountMethod,
                                                    acParent);
        EXCEPTION_CHECK("Getting AccessibleTableColumnHeaderRowCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table row count = %ld", long{ tableInfo->rowCount });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleColumnHeaderRowCountMethod == 0");
        return FALSE;
    }

    // get the header column count
    if (getAccessibleTableColumnHeaderColumnCountMethod != nullptr) {
        tableInfo->columnCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                       getAccessibleTableColumnHeaderColumnCountMethod,
                                                       acParent);
        EXCEPTION_CHECK("Getting AccessibleTableColumnHeaderColumnCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table column count = %ld", long{ tableInfo->columnCount });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableColumnHeaderColumnCountMethod == 0");
        return FALSE;
    }
    // get the header AccessibleTable
    if (getAccessibleTableColumnHeaderMethod != nullptr) {
        const jobject accTable = jniEnv->CallObjectMethod(accessBridgeObject,
                                                          getAccessibleTableColumnHeaderMethod,
                                                          acParent);
        EXCEPTION_CHECK("Getting AccessibleTableColumnHeader - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(accTable);
        EXCEPTION_CHECK("Getting AccessibleTableColumnHeader - call to NewGlobalRef()", FALSE);

        tableInfo->accessibleTable = (JOBJECT64)globalRef;
        PrintDebugString(" [INFO]:   column header AccessibleTable = %p", globalRef);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableColumnHeaderMethod == 0");
        return FALSE;
    }

    // FIX - set unused elements
    tableInfo->caption = NULL;
    tableInfo->summary = NULL;
    tableInfo->accessibleContext = NULL;

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleTableColumnHeader succeeded");
    return TRUE;
}

jobject
AccessBridgeJavaEntryPoints::getAccessibleTableRowDescription(jobject acParent, jint row) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableRowDescription(%p, %ld):",
                     acParent, long{ row });

    if (getAccessibleTableRowDescriptionMethod != nullptr) {
        const jobject returnedAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                           getAccessibleTableRowDescriptionMethod,
                                                                           acParent, row);
        EXCEPTION_CHECK("Getting AccessibleTableRowDescription - call to CallObjectMethod()", NULL);
        const jobject globalRef = jniEnv->NewGlobalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTableRowDescription - call to NewGlobalRef()", NULL);
        jniEnv->DeleteLocalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTableRowDescription - call to DeleteLocalRef()", NULL);
        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         returnedAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableRowDescriptionMethod == 0");
        return nullptr;
    }
}

jobject
AccessBridgeJavaEntryPoints::getAccessibleTableColumnDescription(jobject acParent, jint column) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableColumnDescription(%p, %ld):",
                     acParent, long{ column });

    if (getAccessibleTableColumnDescriptionMethod != nullptr) {
        const jobject returnedAccessibleContext = jniEnv->CallObjectMethod(accessBridgeObject,
                                                                           getAccessibleTableColumnDescriptionMethod,
                                                                           acParent, column);
        EXCEPTION_CHECK("Getting AccessibleTableColumnDescription - call to CallObjectMethod()", NULL);

        const jobject globalRef = jniEnv->NewGlobalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTableColumnDescription - call to NewGlobalRef()", NULL);

        jniEnv->DeleteLocalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTableColumnDescription - call to DeleteLocalRef()", NULL);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         returnedAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableColumnDescriptionMethod == 0");
        return nullptr;
    }
}

jint
AccessBridgeJavaEntryPoints::getAccessibleTableRowSelectionCount(jobject accessibleTable) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableRowSelectionCount(%p):",
                     accessibleTable);

    // Get the table row selection count
    if (getAccessibleTableRowSelectionCountMethod != nullptr) {
        const jint count = jniEnv->CallIntMethod(accessBridgeObject,
                                                 getAccessibleTableRowSelectionCountMethod,
                                                 accessibleTable);
        EXCEPTION_CHECK("Getting AccessibleTableRowSelectionCount - call to CallIntMethod()", 0);

        PrintDebugString(" [INFO]:   table row selection count = %ld", long{ count });
        return count;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableRowSelectionCountMethod == 0");
        return 0;
    }

    PrintDebugString("[ERROR]:   AccessBridgeJavaEntryPoints::getAccessibleTableRowSelectionCount failed");
    return 0;
}

BOOL
AccessBridgeJavaEntryPoints::isAccessibleTableRowSelected(jobject accessibleTable, jint row) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::isAccessibleTableRowSelected(%p, %ld):",
                     accessibleTable, long{ row });

    if (isAccessibleTableRowSelectedMethod != nullptr) {
        const jboolean result = jniEnv->CallBooleanMethod(accessBridgeObject,
                                                          isAccessibleTableRowSelectedMethod,
                                                          accessibleTable, row);
        EXCEPTION_CHECK("Getting isAccessibleTableRowSelected - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   table row isSelected = %d", int{ result });
        return (result == JNI_TRUE) ? TRUE : FALSE;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or isAccessibleTableRowSelectedMethod == 0");
        return FALSE;
    }

    PrintDebugString("[ERROR]:   AccessBridgeJavaEntryPoints::isAccessibleTableRowSelected failed");
    return FALSE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTableRowSelections(jobject accessibleTable, jint count,
                                                             jint *selections) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableRowSelections(%p, %ld, %p):",
                     accessibleTable, long{ count }, selections);

    if (getAccessibleTableRowSelectionsMethod == nullptr) {
        return FALSE;
    }

    // Get the table row selections
    for (int i = 0; i < count; i++) {
        selections[i] = jniEnv->CallIntMethod(accessBridgeObject,
                                              getAccessibleTableRowSelectionsMethod,
                                              accessibleTable,
                                              i);
        EXCEPTION_CHECK("Getting AccessibleTableRowSelections - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table row selection[%d] = %ld", i, long{ selections[i] });
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleTableRowSelections succeeded");
    return TRUE;
}


jint
AccessBridgeJavaEntryPoints::getAccessibleTableColumnSelectionCount(jobject accessibleTable) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableColumnSelectionCount(%p):",
                     accessibleTable);

    // Get the table column selection count
    if (getAccessibleTableColumnSelectionCountMethod != nullptr) {
        const jint count = jniEnv->CallIntMethod(accessBridgeObject,
                                                 getAccessibleTableColumnSelectionCountMethod,
                                                 accessibleTable);
        EXCEPTION_CHECK("Getting AccessibleTableColumnSelectionCount - call to CallIntMethod()", 0);

        PrintDebugString(" [INFO]:   table column selection count = %d", count);
        return count;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleRowCountMethod == 0");
        return 0;
    }

    PrintDebugString("[ERROR]:   AccessBridgeJavaEntryPoints::getAccessibleTableColumnSelectionCount failed");
    return 0;
}

BOOL
AccessBridgeJavaEntryPoints::isAccessibleTableColumnSelected(jobject accessibleTable, jint column) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::isAccessibleTableColumnSelected(%p, %ld):",
                     accessibleTable, long{ column });

    if (isAccessibleTableColumnSelectedMethod != nullptr) {
        const jboolean result = jniEnv->CallBooleanMethod(accessBridgeObject,
                                                          isAccessibleTableColumnSelectedMethod,
                                                          accessibleTable, column);
        EXCEPTION_CHECK("Getting isAccessibleTableColumnSelected - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   table column isSelected = %d", int{ result });
        return (result == JNI_TRUE) ? TRUE : FALSE;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or isAccessibleTableColumnSelectedMethod == 0");
        return FALSE;
    }

    PrintDebugString("[ERROR]:   AccessBridgeJavaEntryPoints::isAccessibleTableColumnSelected failed");
    return FALSE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTableColumnSelections(jobject accessibleTable, jint count,
                                                                jint *selections) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableColumnSelections(%p, %ld, %p):",
                     accessibleTable, long{ count }, selections);

    if (getAccessibleTableColumnSelectionsMethod == nullptr) {
        return FALSE;
    }

    // Get the table column selections
    for (int i = 0; i < count; i++) {
        selections[i] = jniEnv->CallIntMethod(accessBridgeObject,
                                              getAccessibleTableColumnSelectionsMethod,
                                              accessibleTable,
                                              i);
        EXCEPTION_CHECK("Getting AccessibleTableColumnSelections - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   table Column selection[%d] = %ld", i, long{ selections[i] });
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleTableColumnSelections succeeded");
    return TRUE;
}


jint
AccessBridgeJavaEntryPoints::getAccessibleTableRow(jobject accessibleTable, jint index) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableRow(%p, index=%ld):",
                     accessibleTable, long{ index });

    if (getAccessibleTableRowMethod != nullptr) {
        const jint result = jniEnv->CallIntMethod(accessBridgeObject,
                                                  getAccessibleTableRowMethod,
                                                  accessibleTable, index);
        EXCEPTION_CHECK("Getting AccessibleTableRow - call to CallIntMethod()", -1);

        PrintDebugString(" [INFO]:   table row = %ld", long{ result });
        return result;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableRowMethod == 0");
        return -1;
    }

    PrintDebugString("[ERROR]:   AccessBridgeJavaEntryPoints::getAccessibleTableRow failed");
    return -1;
}

jint
AccessBridgeJavaEntryPoints::getAccessibleTableColumn(jobject accessibleTable, jint index) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableColumn(%p, index=%ld):",
                     accessibleTable, long{ index });

    if (getAccessibleTableColumnMethod != nullptr) {
        const jint result = jniEnv->CallIntMethod(accessBridgeObject,
                                                  getAccessibleTableColumnMethod,
                                                  accessibleTable, index);
        EXCEPTION_CHECK("Getting AccessibleTableColumn - call to CallIntMethod()", -1);

        PrintDebugString(" [INFO]:   table column = %ld", long{ result });
        return result;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableColumnMethod == 0");
        return -1;
    }

    PrintDebugString("[ERROR]:   AccessBridgeJavaEntryPoints::getAccessibleTableColumn failed");
    return -1;
}

jint
AccessBridgeJavaEntryPoints::getAccessibleTableIndex(jobject accessibleTable, jint row, jint column) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTableIndex(%p, row=%ld, col=%ld):",
                     accessibleTable, long{ row }, long{ column });

    if (getAccessibleTableIndexMethod != nullptr) {
        const jint result = jniEnv->CallIntMethod(accessBridgeObject,
                                                  getAccessibleTableIndexMethod,
                                                  accessibleTable, row, column);
        EXCEPTION_CHECK("Getting getAccessibleTableIndex - call to CallIntMethod()", -1);

        PrintDebugString(" [INFO]:   table index = %ld", long{ result });
        return result;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTableIndexMethod == 0");
        return -1;
    }

    PrintDebugString("[ERROR]:   AccessBridgeJavaEntryPoints::getAccessibleTableIndex failed");
    return -1;
}

/********** end AccessibleTable routines ******************************/


/********** begin AccessibleRelationSet routines **********************/

BOOL
AccessBridgeJavaEntryPoints::getAccessibleRelationSet(jobject accessibleContext,
                                                      AccessibleRelationSetInfo *relationSet) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleRelationSet(%p, %p):",
                     accessibleContext, relationSet);

    if (getAccessibleRelationCountMethod       == nullptr ||
        getAccessibleRelationKeyMethod         == nullptr ||
        getAccessibleRelationTargetCountMethod == nullptr ||
        getAccessibleRelationTargetMethod      == nullptr) {
        return FALSE;
    }

    // Get the relations set count
    relationSet->relationCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                       getAccessibleRelationCountMethod,
                                                       accessibleContext);
    EXCEPTION_CHECK("Getting AccessibleRelationCount - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   AccessibleRelation count = %ld", long{ relationSet->relationCount });

    // Get the relation set
    for (int i = 0; i < relationSet->relationCount && i < MAX_RELATIONS; i++) {
        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleRelationKeyMethod,
                                                             accessibleContext,
                                                             i);

        EXCEPTION_CHECK("Getting AccessibleRelationKey - call to CallObjectMethod()", FALSE);
        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, relationSet->relations[i].key);
            EXCEPTION_CHECK("Getting AccessibleRelation key - attempt to copy the java string content", FALSE);

            // jniEnv->CallVoidMethod(accessBridgeObject,
            //                        decrementReferenceMethod, js);
            //EXCEPTION_CHECK("Getting AccessibleRelation key - call to CallVoidMethod()", FALSE);

            PrintDebugString(" [INFO]:   AccessibleRelation key = \"%ls\"", relationSet->relations[i].key );

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleRelation key - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   AccessibleRelation key is null.");
            relationSet->relations[i].key[0] = wchar_t{ 0 };
        }

        relationSet->relations[i].targetCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                                      getAccessibleRelationTargetCountMethod,
                                                                      accessibleContext,
                                                                      i);

        for (int j = 0; j < relationSet->relations[i].targetCount && j < MAX_RELATION_TARGETS; j++) {
            const jobject target = jniEnv->CallObjectMethod(accessBridgeObject, getAccessibleRelationTargetMethod,
                                                            accessibleContext, i, j);
            EXCEPTION_CHECK("Getting AccessibleRelationSet - call to CallObjectMethod()", FALSE);

            const jobject globalRef = jniEnv->NewGlobalRef(target);
            EXCEPTION_CHECK("Getting AccessibleRelationSet - call to NewGlobalRef()", FALSE);

            relationSet->relations[i].targets[j] = (JOBJECT64)globalRef;
            PrintDebugString(" [INFO]:   relation set item: %p", globalRef);
        }
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleRelationSet succeeded");
    return TRUE;
}


/********** end AccessibleRelationSet routines ************************/


/********** begin AccessibleHypertext routines **********************/

BOOL
AccessBridgeJavaEntryPoints::getAccessibleHypertext(jobject accessibleContext,
                                                    AccessibleHypertextInfo *hypertext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleHypertext(%p, %p):",
                     accessibleContext, hypertext);

    // get the AccessibleHypertext
    const jobject ht = jniEnv->CallObjectMethod(accessBridgeObject,
                                                getAccessibleHypertextMethod,
                                                accessibleContext);
    EXCEPTION_CHECK("Getting AccessibleHypertext - call to CallObjectMethod()", FALSE);

    const jobject globalRef = jniEnv->NewGlobalRef(ht);
    EXCEPTION_CHECK("Getting AccessibleHypertext - call to NewGlobalRef()", FALSE);

    hypertext->accessibleHypertext = (JOBJECT64)globalRef;
    PrintDebugString(" [INFO]:   AccessibleHypertext = %p", globalRef);

    if (hypertext->accessibleHypertext == 0) {
        PrintDebugString(" [WARN]:   null AccessibleHypertext; returning FALSE");
        return false;
    }

    // get the hyperlink count
    hypertext->linkCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                 getAccessibleHyperlinkCountMethod,accessibleContext);

    EXCEPTION_CHECK("Getting hyperlink count - call to CallIntMethod()", FALSE);
    PrintDebugString(" [INFO]:   hyperlink count = %ld", long{ hypertext->linkCount });

    // get the hypertext links
    for (int i = 0; i < hypertext->linkCount && i < MAX_HYPERLINKS; i++) {
        // get the hyperlink
        const jobject hl = jniEnv->CallObjectMethod(accessBridgeObject,
                                                    getAccessibleHyperlinkMethod,
                                                    accessibleContext,
                                                    i);
        EXCEPTION_CHECK("Getting AccessibleHyperlink - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(hl);
        EXCEPTION_CHECK("Getting AccessibleHyperlink - call to NewGlobalRef()", FALSE);

        hypertext->links[i].accessibleHyperlink = (JOBJECT64)globalRef;
        PrintDebugString(" [INFO]:   AccessibleHyperlink = %p", globalRef);

        // get the hyperlink text
        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleHyperlinkTextMethod,
                                                             hypertext->links[i].accessibleHyperlink,
                                                             i);
        EXCEPTION_CHECK("Getting hyperlink text - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, hypertext->links[i].text);
            EXCEPTION_CHECK("Getting AccessibleHyperlink text - attempt to copy the java string content", FALSE);

            // jniEnv->CallVoidMethod(accessBridgeObject,
            //                                     decrementReferenceMethod, js);
            //EXCEPTION_CHECK("Getting AccessibleHyperlink text - call to CallVoidMethod()", FALSE);

            PrintDebugString(" [INFO]:   AccessibleHyperlink text = \"%ls\"", hypertext->links[i].text);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleHyperlink text - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   AccessibleHyperlink text is null.");
            hypertext->links[i].text[0] = wchar_t{ 0 };
        }

        hypertext->links[i].startIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                                               getAccessibleHyperlinkStartIndexMethod,
                                                               hypertext->links[i].accessibleHyperlink,
                                                               i);
        EXCEPTION_CHECK("Getting hyperlink start index - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   hyperlink start index = %ld", long{ hypertext->links[i].startIndex });

        hypertext->links[i].endIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                                             getAccessibleHyperlinkEndIndexMethod,
                                                             hypertext->links[i].accessibleHyperlink,
                                                             i);
        EXCEPTION_CHECK("Getting hyperlink end index - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   hyperlink end index = %ld", long{ hypertext->links[i].endIndex });
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleHypertext succeeded");
    return TRUE;
}

/*
 * Activates an AccessibleHyperlink
 */
BOOL
AccessBridgeJavaEntryPoints::activateAccessibleHyperlink(jobject accessibleContext,
                                                         jobject accessibleHyperlink) {

    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::activateAccessibleHyperlink(%p, %p):",
                     accessibleContext, accessibleHyperlink);

    if (activateAccessibleHyperlinkMethod != nullptr) {
        const jboolean returnVal = jniEnv->CallBooleanMethod(accessBridgeObject, activateAccessibleHyperlinkMethod,
                                                             accessibleContext, accessibleHyperlink);
        EXCEPTION_CHECK("activateAccessibleHyperlink - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   returning %d", int{ returnVal });

        return (returnVal == JNI_TRUE) ? TRUE : FALSE;
    } else {
        PrintDebugString("[ERROR]:   either jniEnv == 0 or activateAccessibleHyperlinkMethod == 0");
        return FALSE;
    }
}


/*
 * This method is used to iterate through the hyperlinks in a component.  It
 * returns hypertext information for a component starting at hyperlink index
 * nStartIndex.  No more than MAX_HYPERLINKS AccessibleHypertextInfo objects will
 * be returned for each call to this method.
 * returns FALSE on error.
 */
BOOL
AccessBridgeJavaEntryPoints::getAccessibleHypertextExt(const jobject accessibleContext,
                                                       const jint nStartIndex,
                                                       /* OUT */ AccessibleHypertextInfo *hypertext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleHypertextExt(%p, startIndex=%ld, %p):",
                     accessibleContext, long{ nStartIndex }, hypertext);

    // get the AccessibleHypertext
    const jobject ht = jniEnv->CallObjectMethod(accessBridgeObject, getAccessibleHypertextMethod, accessibleContext);
    EXCEPTION_CHECK("Getting AccessibleHypertext - call to CallObjectMethod()", FALSE);

    const jobject globalRef = jniEnv->NewGlobalRef(ht);
    EXCEPTION_CHECK("Getting AccessibleHypertext - call to NewGlobalRef()", FALSE);

    hypertext->accessibleHypertext = (JOBJECT64)globalRef;

    PrintDebugString(" [INFO]:   AccessibleHypertext = %p", globalRef);

    if (hypertext->accessibleHypertext == NULL) {
        PrintDebugString(" [WARN]:   null AccessibleHypertext; returning FALSE");
        return FALSE;
    }

    // get the hyperlink count
    hypertext->linkCount = jniEnv->CallIntMethod(accessBridgeObject, getAccessibleHyperlinkCountMethod,
                                                 accessibleContext);
    EXCEPTION_CHECK("Getting hyperlink count - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   hyperlink count = %ld", long{ hypertext->linkCount });

    if (nStartIndex >= hypertext->linkCount) {
        return FALSE;
    }

    // get the hypertext links
    // NOTE: To avoid a crash when there are more than MAX_HYPERLINKS (64) links
    // in the document, test for i < MAX_HYPERLINKS in addition to
    // i < hypertext->linkCount
    int bufIndex = 0;
    for (int i = nStartIndex; (i < hypertext->linkCount) && (i < nStartIndex + MAX_HYPERLINKS); i++) {
        PrintDebugString(" [INFO]:   getting hyperlink %d ...", i);

        // get the hyperlink
        const jobject hl = jniEnv->CallObjectMethod(accessBridgeObject,
                                                    getAccessibleHyperlinkMethod,
                                                    hypertext->accessibleHypertext,
                                                    i);
        EXCEPTION_CHECK("Getting AccessibleHyperlink - call to CallObjectMethod()", FALSE);

        const jobject globalRef = jniEnv->NewGlobalRef(hl);
        EXCEPTION_CHECK("Getting AccessibleHyperlink - call to NewGlobalRef()", FALSE);

        hypertext->links[bufIndex].accessibleHyperlink = (JOBJECT64)globalRef;
        PrintDebugString(" [INFO]:   AccessibleHyperlink = %p", globalRef);

        // get the hyperlink text
        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleHyperlinkTextMethod,
                                                             hypertext->links[bufIndex].accessibleHyperlink,
                                                             i);
        EXCEPTION_CHECK("Getting hyperlink text - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, hypertext->links[bufIndex].text);
            EXCEPTION_CHECK("Getting AccessibleHyperlink text - attempt to copy the java string content", FALSE);

            // jniEnv->CallVoidMethod(accessBridgeObject,
            //                        decrementReferenceMethod, js);
            //EXCEPTION_CHECK("Getting AccessibleHyperlink text - call to CallVoidMethod()", FALSE);

            PrintDebugString(" [INFO]:   AccessibleHyperlink text = \"%ls\"", hypertext->links[bufIndex].text);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleHyperlink text - call to DeleteLocalRef()", FALSE);

        } else {
            PrintDebugString(" [WARN]:   AccessibleHyperlink text is null.");
            hypertext->links[bufIndex].text[0] = wchar_t{ 0 };
        }

        hypertext->links[bufIndex].startIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                                                      getAccessibleHyperlinkStartIndexMethod,
                                                                      hypertext->links[bufIndex].accessibleHyperlink,
                                                                      i);
        EXCEPTION_CHECK("Getting hyperlink start index - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   hyperlink start index = %ld", long{ hypertext->links[bufIndex].startIndex });

        hypertext->links[bufIndex].endIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                                                    getAccessibleHyperlinkEndIndexMethod,
                                                                    hypertext->links[bufIndex].accessibleHyperlink,
                                                                    i);
        EXCEPTION_CHECK("Getting hyperlink end index - call to CallIntMethod()", FALSE);
        PrintDebugString(" [INFO]:   hyperlink end index = %ld", long{ hypertext->links[bufIndex].endIndex });

        bufIndex++;
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::getAccessibleHypertextExt succeeded");
    return TRUE;
}

jint AccessBridgeJavaEntryPoints::getAccessibleHyperlinkCount(const jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleHyperlinkCount(%p)",
                     accessibleContext);

    if (getAccessibleHyperlinkCountMethod == nullptr) {
        return -1;
    }

    // get the hyperlink count
    jint linkCount = jniEnv->CallIntMethod(accessBridgeObject, getAccessibleHyperlinkCountMethod,
                                           accessibleContext);
    EXCEPTION_CHECK("Getting hyperlink count - call to CallIntMethod()", -1);

    PrintDebugString(" [INFO]:   hyperlink count = %ld", long{ linkCount });

    return linkCount;
}


jint AccessBridgeJavaEntryPoints::getAccessibleHypertextLinkIndex(const jobject hypertext,
                                                                  const jint nIndex) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleHypertextLinkIndex(%p, index=%ld)",
                     hypertext, long{ nIndex });

    if (getAccessibleHypertextLinkIndexMethod == nullptr) {
        return -1;
    }

    // get the hyperlink index
    jint index = jniEnv->CallIntMethod(accessBridgeObject, getAccessibleHypertextLinkIndexMethod,
                                       hypertext, nIndex);
    EXCEPTION_CHECK("Getting hyperlink index - call to CallIntMethod()", -1);

    PrintDebugString(" [INFO]:   hyperlink index = %ld", long{ index });

    return index;
}

BOOL AccessBridgeJavaEntryPoints::getAccessibleHyperlink(jobject hypertext,
                                                         const jint index,
                                                         /* OUT */ AccessibleHyperlinkInfo *info) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleHyperlink(%p, index=%ld, %p)",
                     hypertext, long{ index }, info);

    // get the hyperlink
    const jobject hl = jniEnv->CallObjectMethod(accessBridgeObject,
                                                getAccessibleHyperlinkMethod,
                                                hypertext,
                                                index);
    EXCEPTION_CHECK("Getting AccessibleHyperlink - call to CallObjectMethod()", FALSE);

    const jobject globalRef = jniEnv->NewGlobalRef(hl);
    EXCEPTION_CHECK("Getting AccessibleHyperlink - call to NewGlobalRef()", FALSE);

    info->accessibleHyperlink = (JOBJECT64)globalRef;
    PrintDebugString(" [INFO]:   AccessibleHyperlink = %p", globalRef);

    // get the hyperlink text
    const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                         getAccessibleHyperlinkTextMethod,
                                                         info->accessibleHyperlink,
                                                         index);
    EXCEPTION_CHECK("Getting hyperlink text - call to CallObjectMethod()", FALSE);

    if (js != nullptr) {
        (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, info->text);
        EXCEPTION_CHECK("Getting AccessibleHyperlink text - attempt to copy the java string content", FALSE);

        // jniEnv->CallVoidMethod(accessBridgeObject,
        //                        decrementReferenceMethod, js);
        //EXCEPTION_CHECK("Getting AccessibleHyperlink text - call to CallVoidMethod()", FALSE);

        PrintDebugString(" [INFO]:   AccessibleHyperlink text = \"%ls\"", info->text);

        jniEnv->DeleteLocalRef(js);
        EXCEPTION_CHECK("Getting AccessibleHyperlink text - call to DeleteLocalRef()", FALSE);

    } else {
        PrintDebugString(" [WARN]:   AccessibleHyperlink text is null.");
        info->text[0] = wchar_t{ 0 };
    }

    info->startIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                             getAccessibleHyperlinkStartIndexMethod,
                                             info->accessibleHyperlink,
                                             index);
    EXCEPTION_CHECK("Getting hyperlink start index - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   hyperlink start index = %ld", long{ info->startIndex });

    info->endIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                           getAccessibleHyperlinkEndIndexMethod,
                                           info->accessibleHyperlink,
                                           index);
    EXCEPTION_CHECK("Getting hyperlink end index - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   hyperlink end index = %ld", long{ info->endIndex });

    return TRUE;
}


/********** end AccessibleHypertext routines ************************/

// Accessible Keybinding methods
BOOL AccessBridgeJavaEntryPoints::getAccessibleKeyBindings(jobject accessibleContext,
                                                           AccessibleKeyBindings *keyBindings) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleKeyBindings(%p, %p):",
                     accessibleContext, keyBindings);

    if (getAccessibleKeyBindingsCountMethod    == nullptr ||
        getAccessibleKeyBindingCharMethod      == nullptr ||
        getAccessibleKeyBindingModifiersMethod == nullptr) {
        return FALSE;
    }

    // get the key binding count
    keyBindings->keyBindingsCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                          getAccessibleKeyBindingsCountMethod, accessibleContext);
    EXCEPTION_CHECK("Getting key bindings count - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   key bindings count = %d", keyBindings->keyBindingsCount);

    // get the key bindings
    for (int i = 0; i < keyBindings->keyBindingsCount && i < MAX_KEY_BINDINGS; i++) {
        // get the key binding character
        keyBindings->keyBindingInfo[i].character = jniEnv->CallCharMethod(accessBridgeObject,
                                                                          getAccessibleKeyBindingCharMethod,
                                                                          accessibleContext,
                                                                          i);
        EXCEPTION_CHECK("Getting key binding character - call to CallCharMethod()", FALSE);

        PrintDebugString(" [INFO]:   key binding character = '%c'"\
                                 "; key binding character in hex = %x"\
                         , int{ keyBindings->keyBindingInfo[i].character }, unsigned{ keyBindings->keyBindingInfo[i].character });

        // get the key binding modifiers
        keyBindings->keyBindingInfo[i].modifiers = jniEnv->CallIntMethod(accessBridgeObject,
                                                                         getAccessibleKeyBindingModifiersMethod,
                                                                         accessibleContext,
                                                                         i);
        EXCEPTION_CHECK("Getting key binding modifiers - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   key binding modifiers = %lx", long{ keyBindings->keyBindingInfo[i].modifiers });
    }

    return TRUE;
}

// AccessibleIcon methods
BOOL AccessBridgeJavaEntryPoints::getAccessibleIcons(jobject accessibleContext,
                                                     AccessibleIcons *icons) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleIcons(%p, %p):",
                     accessibleContext, icons);

    if (getAccessibleIconsCountMethod      == nullptr ||
        getAccessibleIconDescriptionMethod == nullptr ||
        getAccessibleIconHeightMethod      == nullptr ||
        getAccessibleIconWidthMethod       == nullptr) {
        PrintDebugString("[ERROR]:   missing method(s) !!!");
        return FALSE;
    }


    // get the icons count
    icons->iconsCount = jniEnv->CallIntMethod(accessBridgeObject,
                                              getAccessibleIconsCountMethod, accessibleContext);
    EXCEPTION_CHECK("Getting icons count - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   icons count = %ld", long{ icons->iconsCount });

    // get the icons
    for (int i = 0; i < icons->iconsCount && i < MAX_ICON_INFO; i++) {

        // get the icon description
        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleIconDescriptionMethod,
                                                             accessibleContext,
                                                             i);

        EXCEPTION_CHECK("Getting icon description - call to CallObjectMethod()", FALSE);
        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, icons->iconInfo[i].description);
            EXCEPTION_CHECK("Getting AccessibleIcon description - attempt to copy the java string content", FALSE);

            // jniEnv->CallVoidMethod(accessBridgeObject,
            //                        decrementReferenceMethod, js);
            //EXCEPTION_CHECK("Getting AccessibleIcon description - call to CallVoidMethod()", FALSE);

            PrintDebugString(" [INFO]:   AccessibleIcon description = \"%ls\"", icons->iconInfo[i].description);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleIcon description - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   AccessibleIcon description is null.");
            icons->iconInfo[i].description[0] = wchar_t{ 0 };
        }

        // get the icon height
        icons->iconInfo[i].height = jniEnv->CallIntMethod(accessBridgeObject,
                                                          getAccessibleIconHeightMethod,
                                                          accessibleContext,
                                                          i);
        EXCEPTION_CHECK("Getting icon height - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   icon height = %d", icons->iconInfo[i].height);

        // get the icon width
        icons->iconInfo[i].width = jniEnv->CallIntMethod(accessBridgeObject,
                                                         getAccessibleIconWidthMethod,
                                                         accessibleContext,
                                                         i);
        EXCEPTION_CHECK("Getting icon width - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   icon width = %d", icons->iconInfo[i].width);
    }

    return TRUE;
}

// AccessibleActionMethods
BOOL AccessBridgeJavaEntryPoints::getAccessibleActions(jobject accessibleContext,
                                                       AccessibleActions *actions) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleIcons(%p, %p):",
                     accessibleContext, actions);

    if (getAccessibleActionsCountMethod == nullptr ||
        getAccessibleActionNameMethod   == nullptr) {
        PrintDebugString("[ERROR]:   missing method(s) !!!");
        return FALSE;
    }


    // get the icons count
    actions->actionsCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                  getAccessibleActionsCountMethod,accessibleContext);
    EXCEPTION_CHECK("Getting actions count - call to CallIntMethod()", FALSE);

    PrintDebugString(" [INFO]:   key actions count = %ld", long{ actions->actionsCount });

    // get the actions
    for (int i = 0; i < actions->actionsCount && i < MAX_ACTION_INFO; i++) {
        // get the action name
        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleActionNameMethod,
                                                             accessibleContext,
                                                             i);
        EXCEPTION_CHECK("Getting Action Name  - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, actions->actionInfo[i].name);
            EXCEPTION_CHECK("Getting AccessibleAction Name  - attempt to copy the java string content", FALSE);

            // jniEnv->CallVoidMethod(accessBridgeObject,
            //                        decrementReferenceMethod, js);
            //EXCEPTION_CHECK("Getting AccessibleAction name  - call to CallVoidMethod()", FALSE);

            PrintDebugString(" [INFO]:   AccessibleAction name  = \"%ls\"", actions->actionInfo[i].name);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleAction name  - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   AccessibleAction name  is null.");
            actions->actionInfo[i].name [0] = wchar_t{ 0 };
        }
    }

    return TRUE;
}

BOOL AccessBridgeJavaEntryPoints::doAccessibleActions(jobject accessibleContext,
                                                      AccessibleActionsToDo *actionsToDo,
                                                      jint *failure) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::doAccessibleActions(%p, #actions %ld \"%ls\"):",
                     accessibleContext,
                     long{ actionsToDo->actionsCount },
                     actionsToDo->actions[0].name);

    if (doAccessibleActionsMethod == nullptr) {
        *failure = 0;
        return FALSE;
    }

    PrintDebugString(" [INFO]:   doing %ld actions ...", long{ actionsToDo->actionsCount });
    for (int i = 0; i < actionsToDo->actionsCount && i < MAX_ACTIONS_TO_DO; i++) {
        PrintDebugString(" [INFO]:   doing action %d: \"%ls\" ...", i, actionsToDo->actions[i].name);

        // create a Java String for the action name
        wchar_t * const actionName = (wchar_t *)actionsToDo->actions[i].name;
        const jstring javaName = jniEnv->NewString(actionName, (jsize)wcslen(actionName));
        if (javaName == nullptr) {
            PrintDebugString("[ERROR]:   NewString failed");
            *failure = i;
            return FALSE;
        }

        const jboolean returnVal = jniEnv->CallBooleanMethod(accessBridgeObject, doAccessibleActionsMethod,
                                                             accessibleContext, javaName);

        jniEnv->DeleteLocalRef(javaName);

        EXCEPTION_CHECK("doAccessibleActions - call to CallBooleanMethod()", FALSE);

        if (returnVal != JNI_TRUE) {
            PrintDebugString("[ERROR]:   Action %d failed", i);
            *failure = i;
            return FALSE;
        }
    }

    *failure = -1;
    return TRUE;
}


/********** AccessibleText routines ***********************************/

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTextInfo(jobject accessibleContext,
                                                   AccessibleTextInfo *textInfo,
                                                   jint x, jint y) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTextInfo(%p, %p, %ld, %ld):",
                     accessibleContext, textInfo, long{ x }, long{ y });

    // Verify the Java VM still exists and AccessibleContext is
    // an instance of AccessibleText
    if (verifyAccessibleText(accessibleContext) == FALSE) {
        PrintDebugString("[ERROR]:   verifyAccessibleText(accessibleContext) == FALSE.");
        return FALSE;
    }

    // Get the character count
    if (getAccessibleCharCountFromContextMethod != nullptr) {
        textInfo->charCount = jniEnv->CallIntMethod(accessBridgeObject,
                                                    getAccessibleCharCountFromContextMethod,
                                                    accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleCharCount - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   char count = %ld", long{ textInfo->charCount });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleCharCountFromContextMethod == 0");
        return FALSE;
    }

    // Get the index of the caret
    if (getAccessibleCaretPositionFromContextMethod != nullptr) {
        textInfo->caretIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                                     getAccessibleCaretPositionFromContextMethod,
                                                     accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleCaretPosition - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   index at caret = %ld", long{ textInfo->caretIndex });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleCaretPositionFromContextMethod == 0");
        return FALSE;
    }

    // Get the index at the given point
    if (getAccessibleIndexAtPointFromContextMethod != nullptr) {
        // If x or y is -1 return -1
        if (x == -1 || y == -1) {
            textInfo->indexAtPoint = -1;
        } else {
            textInfo->indexAtPoint = jniEnv->CallIntMethod(accessBridgeObject,
                                                           getAccessibleIndexAtPointFromContextMethod,
                                                           accessibleContext, x, y);
            EXCEPTION_CHECK("Getting AccessibleIndexAtPoint - call to CallIntMethod()", FALSE);
        }
        PrintDebugString(" [INFO]:   index at point = %ld", long{ textInfo->indexAtPoint });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleIndexAtPointFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTextItems(jobject accessibleContext,
                                                    AccessibleTextItemsInfo *textItems, jint index) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTextItems(%p, %p, index=%ld):",
                     accessibleContext, textItems, long{ index });

    // Verify the Java VM still exists and AccessibleContext is
    // an instance of AccessibleText
    if (verifyAccessibleText(accessibleContext) == FALSE) {
        return FALSE;
    }

    // Get the letter at index
    if (getAccessibleLetterAtIndexFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleLetterAtIndexFromContextMethod,
                                                              accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleLetterAtIndex - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);
        if (js != nullptr) {
            // it's like "textItems->letter = js[0];"
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, &textItems->letter, 1, false);
            EXCEPTION_CHECK("Getting AccessibleLetterAtIndex - attempt to copy the java string content (textItems->letter)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleLetterAtIndex - call to CallVoidMethod()", FALSE);

            PrintDebugString(" [INFO]:   Accessible Text letter = '%c'", int{ textItems->letter });

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleLetterAtIndex - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible Text letter is null.");
            textItems->letter = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleLetterAtIndexFromContextMethod == 0");
        return FALSE;
    }

    // Get the word at index
    if (getAccessibleWordAtIndexFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleWordAtIndexFromContextMethod,
                                                              accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleWordAtIndex - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);
        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, textItems->word);
            EXCEPTION_CHECK("Getting AccessibleWordAtIndex - attempt to copy the java string content (textItems->word)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleWordAtIndex - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Text word = \"%ls\"", textItems->word);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleWordAtIndex - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible Text word is null.");
            textItems->word[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleWordAtIndexFromContextMethod == 0");
        return FALSE;
    }

    // Get the sentence at index
    if (getAccessibleSentenceAtIndexFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleSentenceAtIndexFromContextMethod,
                                                              accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleSentenceAtIndex - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);
        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, textItems->sentence);
            EXCEPTION_CHECK("Getting AccessibleSentenceAtIndex - attempt to copy the java string content (textItems->sentence)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleSentenceAtIndex - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Text sentence = \"%ls\"", textItems->sentence);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleSentenceAtIndex - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible Text sentence is null.");
            textItems->sentence[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleSentenceAtIndexFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTextSelectionInfo(jobject accessibleContext,
                                                            AccessibleTextSelectionInfo *selectionInfo) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTextSelectionInfo(%p, %p):",
                     accessibleContext, selectionInfo);

    // Verify the Java VM still exists and AccessibleContext is
    // an instance of AccessibleText
    if (verifyAccessibleText(accessibleContext) == FALSE) {
        return FALSE;
    }

    // Get the selection start index
    if (getAccessibleTextSelectionStartFromContextMethod != nullptr) {
        selectionInfo->selectionStartIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                                                   getAccessibleTextSelectionStartFromContextMethod,
                                                                   accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTextSelectionStart - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   Selection start = %ld", long{ selectionInfo->selectionStartIndex });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTextSelectionStartFromContextMethod == 0");
        return FALSE;
    }

    // Get the selection end index
    if (getAccessibleTextSelectionEndFromContextMethod != nullptr) {
        selectionInfo->selectionEndIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                                                 getAccessibleTextSelectionEndFromContextMethod,
                                                                 accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTextSelectionEnd - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   Selection end = %ld", long{ selectionInfo->selectionEndIndex });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTextSelectionEndFromContextMethod == 0");
        return FALSE;
    }

    // Get the selected text
    if (getAccessibleTextSelectedTextFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleTextSelectedTextFromContextMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleTextSelectedText - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);
        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, selectionInfo->selectedText);
            EXCEPTION_CHECK("Getting AccessibleTextSelectedText - attempt to copy the java string content"
                            " (selectionInfo->selectedText)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleTextSelectedText - call to CallVoidMethod()", FALSE);

            PrintDebugString(" [INFO]:   Accessible's selected text = \"%ls\"", selectionInfo->selectedText);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleTextSelectedText - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible's selected text is null.");
            selectionInfo->selectedText[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString(" [WARN]:   either env == 0 or getAccessibleTextSelectedTextFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTextAttributes(jobject accessibleContext, jint index, AccessibleTextAttributesInfo *attributes) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTextAttributes(%p, index=%ld, %p):",
                     accessibleContext, long{ index }, attributes);

    // Verify the Java VM still exists and AccessibleContext is
    // an instance of AccessibleText
    if (verifyAccessibleText(accessibleContext) == FALSE) {
        return FALSE;
    }

    if (accessibleContext == nullptr) {
        PrintDebugString(" [WARN]:   passed in AccessibleContext == null! (oops)");

        attributes->bold = FALSE;
        attributes->italic = FALSE;
        attributes->underline = FALSE;
        attributes->strikethrough = FALSE;
        attributes->superscript = FALSE;
        attributes->subscript = FALSE;
        attributes->backgroundColor[0] = wchar_t{ 0 };
        attributes->foregroundColor[0] = wchar_t{ 0 };
        attributes->fontFamily[0] = wchar_t{ 0 };
        attributes->fontSize = -1;
        attributes->alignment = -1;
        attributes->bidiLevel = -1;
        attributes->firstLineIndent = -1;
        attributes->leftIndent = -1;
        attributes->rightIndent = -1;
        attributes->lineSpacing = -1;
        attributes->spaceAbove = -1;
        attributes->spaceBelow = -1;
        attributes->fullAttributesString[0] = wchar_t{ 0 };

        return FALSE;
    }

    jobject AttributeSet = nullptr;

    // Get the AttributeSet
    if (getAccessibleAttributeSetAtIndexFromContextMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting AttributeSet at index...");
        AttributeSet = jniEnv->CallObjectMethod(accessBridgeObject,
                                                getAccessibleAttributeSetAtIndexFromContextMethod,
                                                accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleAttributeSetAtIndex - call to CallObjectMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleAttributeSetAtIndexFromContextMethod == 0");
        return FALSE;
    }

    // It is legal for the AttributeSet object to be null, in which case we return false!
    if (AttributeSet == nullptr) {
        PrintDebugString(" [WARN]:   AttributeSet returned at index is null (this is legal! - see AWT in J2SE 1.3");

        attributes->bold = FALSE;
        attributes->italic = FALSE;
        attributes->underline = FALSE;
        attributes->strikethrough = FALSE;
        attributes->superscript = FALSE;
        attributes->subscript = FALSE;
        attributes->backgroundColor[0] = wchar_t{ 0 };
        attributes->foregroundColor[0] = wchar_t{ 0 };
        attributes->fontFamily[0] = wchar_t{ 0 };
        attributes->fontSize = -1;
        attributes->alignment = -1;
        attributes->bidiLevel = -1;
        attributes->firstLineIndent = -1;
        attributes->leftIndent = -1;
        attributes->rightIndent = -1;
        attributes->lineSpacing = -1;
        attributes->spaceAbove = -1;
        attributes->spaceBelow = -1;
        attributes->fullAttributesString[0] = wchar_t{ 0 };

        return FALSE;
    }

    // Get the bold setting
    if (getBoldFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting bold from AttributeSet...");

        attributes->bold = (jniEnv->CallBooleanMethod(accessBridgeObject,
                                                      getBoldFromAttributeSetMethod,
                                                      AttributeSet) == JNI_TRUE) ? TRUE : FALSE;
        EXCEPTION_CHECK("Getting BoldFromAttributeSet - call to CallBooleanMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getBoldFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting BoldFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting BoldFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the italic setting
    if (getItalicFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting italic from AttributeSet...");

        attributes->italic = (jniEnv->CallBooleanMethod(accessBridgeObject,
                                                        getItalicFromAttributeSetMethod,
                                                        AttributeSet) == JNI_TRUE) ? TRUE : FALSE;
        EXCEPTION_CHECK("Getting ItalicFromAttributeSet - call to CallBooleanMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getItalicFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting ItalicFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting ItalicFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the underline setting
    if (getUnderlineFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting underline from AttributeSet...");

        attributes->underline = (jniEnv->CallBooleanMethod(accessBridgeObject,
                                                           getUnderlineFromAttributeSetMethod,
                                                           AttributeSet) == JNI_TRUE) ? TRUE : FALSE;
        EXCEPTION_CHECK("Getting UnderlineFromAttributeSet - call to CallBooleanMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getUnderlineFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting UnderlineFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting UnderlineFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the strikethrough setting
    if (getStrikethroughFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting strikethrough from AttributeSet...");

        attributes->strikethrough = (jniEnv->CallBooleanMethod(accessBridgeObject,
                                                               getStrikethroughFromAttributeSetMethod,
                                                               AttributeSet) == JNI_TRUE) ? TRUE : FALSE;
        EXCEPTION_CHECK("Getting StrikethroughFromAttributeSet - call to CallBooleanMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getStrikethroughFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting StrikethroughFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting StrikethroughFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the superscript setting
    if (getSuperscriptFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting superscript from AttributeSet...");

        attributes->superscript = (jniEnv->CallBooleanMethod(accessBridgeObject,
                                                             getSuperscriptFromAttributeSetMethod,
                                                             AttributeSet) == JNI_TRUE) ? TRUE : FALSE;
        EXCEPTION_CHECK("Getting SuperscriptFromAttributeSet - call to CallBooleanMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getSuperscriptFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting SuperscriptFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting SuperscriptFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the subscript setting
    if (getSubscriptFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting subscript from AttributeSet...");

        attributes->subscript = (jniEnv->CallBooleanMethod(accessBridgeObject,
                                                           getSubscriptFromAttributeSetMethod,
                                                           AttributeSet) == JNI_TRUE) ? TRUE : FALSE;
        EXCEPTION_CHECK("Getting SubscriptFromAttributeSet - call to CallBooleanMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getSubscriptFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting SubscriptFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting SubscriptFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the backgroundColor setting
    if (getBackgroundColorFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting backgroundColor from AttributeSet...");

        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getBackgroundColorFromAttributeSetMethod,
                                                              AttributeSet);
        EXCEPTION_CHECK("Getting BackgroundColorFromAttributeSet - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, attributes->backgroundColor);
            EXCEPTION_CHECK("Getting BackgroundColorFromAttributeSet - attempt to copy the java string content"
                            " (attributes->backgroundColor)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting BackgroundColorFromAttributeSet - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   AttributeSet's background color = \"%ls\"", attributes->backgroundColor);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting BackgroundColorFromAttributeSet - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   AttributeSet's background color is null.");
            attributes->backgroundColor[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getBackgroundColorFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting BackgroundColorFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting BackgroundColorFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the foregroundColor setting
    if (getForegroundColorFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting foregroundColor from AttributeSet...");

        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getForegroundColorFromAttributeSetMethod,
                                                             AttributeSet);
        EXCEPTION_CHECK("Getting ForegroundColorFromAttributeSet - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, attributes->foregroundColor);
            EXCEPTION_CHECK("Getting ForegroundColorFromAttributeSet - attempt to copy the java string content"
                            " (attributes->foregroundColor)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting ForegroundColorFromAttributeSet - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   AttributeSet's foreground color = \"%ls\"", attributes->foregroundColor);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting ForegroundColorFromAttributeSet - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   AttributeSet's foreground color is null.");
            attributes->foregroundColor[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getForegroundColorFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting ForegroundColorFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting ForegroundColorFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the fontFamily setting
    if (getFontFamilyFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting fontFamily from AttributeSet...");

        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getFontFamilyFromAttributeSetMethod,
                                                             AttributeSet);
        EXCEPTION_CHECK("Getting FontFamilyFromAttributeSet - call to CallObjectMethod()", FALSE);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, attributes->fontFamily);
            EXCEPTION_CHECK("Getting FontFamilyFromAttributeSet - attempt to copy the java string content"
                            " (attributes->fontFamily)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting FontFamilyFromAttributeSet - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   AttributeSet's fontFamily = \"%ls\"", attributes->fontFamily);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting FontFamilyFromAttributeSet - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   AttributeSet's fontFamily is null.");
            attributes->backgroundColor[0] = wchar_t{ 0 };
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getFontFamilyFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting FontFamilyFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting FontFamilyFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the font size
    if (getFontSizeFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting font size from AttributeSet...");

        attributes->fontSize = jniEnv->CallIntMethod(accessBridgeObject,
                                                     getFontSizeFromAttributeSetMethod,
                                                     AttributeSet);
        EXCEPTION_CHECK("Getting FontSizeFromAttributeSet - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   AttributeSet's font size = %ld", long{ attributes->fontSize });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAlignmentFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting FontSizeFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting FontSizeFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the alignment setting
    if (getAlignmentFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting alignment from AttributeSet...");

        attributes->alignment = jniEnv->CallIntMethod(accessBridgeObject,
                                                      getAlignmentFromAttributeSetMethod,
                                                      AttributeSet);
        EXCEPTION_CHECK("Getting AlignmentFromAttributeSet - call to CallIntMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAlignmentFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting AlignmentFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting AlignmentFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the bidiLevel setting
    if (getBidiLevelFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting bidiLevel from AttributeSet...");

        attributes->bidiLevel = jniEnv->CallIntMethod(accessBridgeObject,
                                                      getBidiLevelFromAttributeSetMethod,
                                                      AttributeSet);
        EXCEPTION_CHECK("Getting BidiLevelFromAttributeSet - call to CallIntMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getBidiLevelFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting BidiLevelFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting BidiLevelFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the firstLineIndent setting
    if (getFirstLineIndentFromAttributeSetMethod != nullptr) {
        PrintDebugString("[ERROR]:   Getting firstLineIndent from AttributeSet...");

        attributes->firstLineIndent = jniEnv->CallFloatMethod(accessBridgeObject,
                                                              getFirstLineIndentFromAttributeSetMethod,
                                                              AttributeSet);
        EXCEPTION_CHECK("Getting FirstLineIndentFromAttributeSet - call to CallIntMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getFirstLineIndentFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting FirstLineIndentFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting FirstLineIndentFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the leftIndent setting
    if (getLeftIndentFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting leftIndent from AttributeSet...");

        attributes->leftIndent = jniEnv->CallFloatMethod(accessBridgeObject,
                                                         getLeftIndentFromAttributeSetMethod,
                                                         AttributeSet);
        EXCEPTION_CHECK("Getting LeftIndentFromAttributeSet - call to CallIntMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getLeftIndentFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting LeftIndentFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting LeftIndentFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the rightIndent setting
    if (getRightIndentFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting rightIndent from AttributeSet...");

        attributes->rightIndent = jniEnv->CallFloatMethod(accessBridgeObject,
                                                          getRightIndentFromAttributeSetMethod,
                                                          AttributeSet);
        EXCEPTION_CHECK("Getting RightIndentFromAttributeSet - call to CallIntMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getRightIndentFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting RightIndentFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting RightIndentFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the lineSpacing setting
    if (getLineSpacingFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting lineSpacing from AttributeSet...");

        attributes->lineSpacing = jniEnv->CallFloatMethod(accessBridgeObject,
                                                          getLineSpacingFromAttributeSetMethod,
                                                          AttributeSet);
        EXCEPTION_CHECK("Getting LineSpacingFromAttributeSet - call to CallIntMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getLineSpacingFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting LineSpacingFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting LineSpacingFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the spaceAbove setting
    if (getSpaceAboveFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting spaceAbove from AttributeSet...");

        attributes->spaceAbove = jniEnv->CallFloatMethod(accessBridgeObject,
                                                         getSpaceAboveFromAttributeSetMethod,
                                                         AttributeSet);
        EXCEPTION_CHECK("Getting SpaceAboveFromAttributeSet - call to CallIntMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getSpaceAboveFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting SpaceAboveFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting SpaceAboveFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the spaceBelow setting
    if (getSpaceBelowFromAttributeSetMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting spaceBelow from AttributeSet...");

        attributes->spaceBelow = jniEnv->CallFloatMethod(accessBridgeObject,
                                                         getSpaceBelowFromAttributeSetMethod,
                                                         AttributeSet);
        EXCEPTION_CHECK("Getting SpaceBelowFromAttributeSet - call to CallIntMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getSpaceBelowFromAttributeSetMethod == 0");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Getting SpaceBelowFromAttributeSet - call to CallVoidMethod()", FALSE);

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Getting SpaceBelowFromAttributeSet - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Release the AttributeSet object
    if (decrementReferenceMethod != nullptr) {
        PrintDebugString(" [INFO]:   Decrementing reference to AttributeSet...");

        jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, AttributeSet);
        EXCEPTION_CHECK("Releasing AttributeSet object - call to CallVoidMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or accessBridgeObject == 0");

        jniEnv->DeleteLocalRef(AttributeSet);
        EXCEPTION_CHECK("Releasing AttributeSet object - call to DeleteLocalRef()", FALSE);

        return FALSE;
    }

    // Get the full attributes string at index
    if (getAccessibleAttributesAtIndexFromContextMethod != nullptr) {
        PrintDebugString(" [INFO]:   Getting full attributes string from Context...");

        const jstring js = (jstring)jniEnv->CallObjectMethod(accessBridgeObject,
                                                             getAccessibleAttributesAtIndexFromContextMethod,
                                                             accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, attributes->fullAttributesString);
            EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - attempt to copy the java string content"
                            " (attributes->fullAttributesString)", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Text attributes = \"%ls\"", attributes->fullAttributesString);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   Accessible Text attributes is null.");

            attributes->fullAttributesString[0] = wchar_t{ 0 };

            jniEnv->DeleteLocalRef(AttributeSet);
            EXCEPTION_CHECK("Getting AccessibleAttributesAtIndex - call to DeleteLocalRef()", FALSE);

            return FALSE;
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleAttributesAtIndexFromContextMethod == 0");

        jniEnv->DeleteLocalRef(AttributeSet);
        return FALSE;
    }

    jniEnv->DeleteLocalRef(AttributeSet);
    EXCEPTION_CHECK("Getting AccessibleAttributeSetAtIndex - call to DeleteLocalRef()", FALSE);

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTextRect(jobject accessibleContext, AccessibleTextRectInfo *rectInfo, jint index) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTextRect(%p, %p, index=%ld):",
                     accessibleContext, rectInfo, long{ index });

    // Verify the Java VM still exists and AccessibleContext is
    // an instance of AccessibleText
    if (verifyAccessibleText(accessibleContext) == FALSE) {
        return FALSE;
    }

    // Get the x coord
    if (getAccessibleXcoordTextRectAtIndexFromContextMethod != nullptr) {
        rectInfo->x = jniEnv->CallIntMethod(accessBridgeObject,
                                            getAccessibleXcoordTextRectAtIndexFromContextMethod,
                                            accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleXcoordTextRect - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   x coord = %ld", long{ rectInfo->x });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleXcoordTextRectAtIndexFromContextMethod == 0");
        return FALSE;
    }

    // Get the y coord
    if (getAccessibleYcoordTextRectAtIndexFromContextMethod != nullptr) {
        rectInfo->y = jniEnv->CallIntMethod(accessBridgeObject,
                                            getAccessibleYcoordTextRectAtIndexFromContextMethod,
                                            accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleYcoordTextRect - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   y coord = %ld", long{ rectInfo->y });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleYcoordTextRectAtIndexFromContextMethod == 0");
        return FALSE;
    }

    // Get the width
    if (getAccessibleWidthTextRectAtIndexFromContextMethod != nullptr) {
        rectInfo->width = jniEnv->CallIntMethod(accessBridgeObject,
                                                getAccessibleWidthTextRectAtIndexFromContextMethod,
                                                accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleWidthTextRect - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   width = %ld", long{ rectInfo->width });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleWidthTextRectAtIndexFromContextMethod == 0");
        return FALSE;
    }

    // Get the height
    if (getAccessibleHeightTextRectAtIndexFromContextMethod != nullptr) {
        rectInfo->height = jniEnv->CallIntMethod(accessBridgeObject,
                                                 getAccessibleHeightTextRectAtIndexFromContextMethod,
                                                 accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleHeightTextRect - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   height = %ld", long{ rectInfo->height });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleHeightTextRectAtIndexFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}

// =====

/**
 * gets the bounding rectangle for the text caret
 */
BOOL
AccessBridgeJavaEntryPoints::getCaretLocation(jobject accessibleContext, AccessibleTextRectInfo *rectInfo, jint index) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getCaretLocation(%p, %p, index=%ld):",
                     accessibleContext, rectInfo, long{ index });

    // Verify the Java VM still exists and AccessibleContext is
    // an instance of AccessibleText
    if (verifyAccessibleText(accessibleContext) == FALSE) {
        return FALSE;
    }

    // Get the x coord
    if (getCaretLocationXMethod != nullptr) {
        rectInfo->x = jniEnv->CallIntMethod(accessBridgeObject,
                                            getCaretLocationXMethod,
                                            accessibleContext, index);
        EXCEPTION_CHECK("Getting caret X coordinate - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   x coord = %ld", long{ rectInfo->x });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getCaretLocationXMethod == 0");
        return FALSE;
    }

    // Get the y coord
    if (getCaretLocationYMethod != nullptr) {
        rectInfo->y = jniEnv->CallIntMethod(accessBridgeObject,
                                            getCaretLocationYMethod,
                                            accessibleContext, index);
        EXCEPTION_CHECK("Getting caret Y coordinate - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   y coord = %ld", long{ rectInfo->y });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getCaretLocationYMethod == 0");
        return FALSE;
    }

    // Get the width
    if (getCaretLocationWidthMethod != nullptr) {
        rectInfo->width = jniEnv->CallIntMethod(accessBridgeObject,
                                                getCaretLocationWidthMethod,
                                                accessibleContext, index);
        EXCEPTION_CHECK("Getting caret width - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   width = %ld", long{ rectInfo->width });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getCaretLocationWidthMethod == 0");
        return FALSE;
    }

    // Get the height
    if (getCaretLocationHeightMethod != nullptr) {
        rectInfo->height = jniEnv->CallIntMethod(accessBridgeObject,
                                                 getCaretLocationHeightMethod,
                                                 accessibleContext, index);
        EXCEPTION_CHECK("Getting caret height - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   height = %ld", long{ rectInfo->height });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getCaretLocationHeightMethod == 0");
        return FALSE;
    }

    return TRUE;
}

// =====

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTextLineBounds(jobject accessibleContext, jint index, jint *startIndex, jint *endIndex) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTextLineBounds(%p, index=%ld, %p, %p):",
                     accessibleContext, long{ index }, startIndex, endIndex);

    // Verify the Java VM still exists and AccessibleContext is
    // an instance of AccessibleText
    if (verifyAccessibleText(accessibleContext) == FALSE) {
        return FALSE;
    }

    // Get the index of the left boundary of the line containing 'index'
    if (getAccessibleTextLineLeftBoundsFromContextMethod != nullptr) {
        *startIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                            getAccessibleTextLineLeftBoundsFromContextMethod,
                                            accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleTextLineLeftBounds - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   startIndex = %ld", long{ *startIndex });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTextLineLeftBoundsFromContextMethod == 0");
        return FALSE;
    }

    // Get the index of the right boundary of the line containing 'index'
    if (getAccessibleTextLineRightBoundsFromContextMethod != nullptr) {
        *endIndex = jniEnv->CallIntMethod(accessBridgeObject,
                                          getAccessibleTextLineRightBoundsFromContextMethod,
                                          accessibleContext, index);
        EXCEPTION_CHECK("Getting AccessibleTextLineRightBounds - call to CallIntMethod()", FALSE);

        PrintDebugString(" [INFO]:   endIndex = %ld", long{ *endIndex });
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTextLineRightBoundsFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getAccessibleTextRange(jobject accessibleContext,
                                                    jint start, jint end, wchar_t *text, short len) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleTextRange(%p, start=%ld, end=%ld, %p, %hd):",
                     accessibleContext, long{ start }, long{ end }, text, len);

    // Verify the Java VM still exists and AccessibleContext is
    // an instance of AccessibleText
    if (verifyAccessibleText(accessibleContext) == FALSE) {
        return FALSE;
    }

    // range is inclusive
    if (end < start) {
        PrintDebugString("[ERROR]:   end < start!");
        text[0] = wchar_t{ 0 };
        return FALSE;
    }

    // Get the text range within [start, end] inclusive
    if (getAccessibleTextRangeFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getAccessibleTextRangeFromContextMethod,
                                                              accessibleContext, start, end);
        EXCEPTION_CHECK("Getting AccessibleTextRange - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);
        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, text, (len < 0) ? 0 : len);
            EXCEPTION_CHECK("Getting AccessibleTextRange - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting AccessibleTextRange - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   Accessible Text range = \"%ls\"", text);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting AccessibleTextRange - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   current Accessible Text range is null.");

            text[0] = wchar_t{ 0 };

            return FALSE;
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleTextRangeFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}

/********** AccessibleValue routines ***************/

BOOL
AccessBridgeJavaEntryPoints::getCurrentAccessibleValueFromContext(jobject accessibleContext, wchar_t *value, short len) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getCurrentAccessibleValueFromContext(%p, %p, len=%hd):",
                     accessibleContext, value, len);

    // Get the current Accessible Value
    if (getCurrentAccessibleValueFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getCurrentAccessibleValueFromContextMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting CurrentAccessibleValue - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, value, (len < 0) ? 0 : len);
            EXCEPTION_CHECK("Getting CurrentAccessibleValue - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting CurrentAccessibleValue - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   current Accessible Value = \"%ls\"", value);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting CurrentAccessibleValue - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   current Accessible Value is null.");

            value[0] = wchar_t{ 0 };

            return FALSE;
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getCurrentAccessibleValueFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getMaximumAccessibleValueFromContext(jobject accessibleContext, wchar_t *value, short len) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getMaximumAccessibleValueFromContext(%p, %p, len=%hd):",
                     accessibleContext, value, len);

    // Get the maximum Accessible Value
    if (getMaximumAccessibleValueFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getMaximumAccessibleValueFromContextMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting MaximumAccessibleValue - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, value, (len < 0) ? 0 : len);
            EXCEPTION_CHECK("Getting MaximumAccessibleValue - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting MaximumAccessibleValue - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   maximum Accessible Value = \"%ls\"", value);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting MaximumAccessibleValue - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   maximum Accessible Value is null.");

            value[0] = wchar_t{ 0 };

            return FALSE;
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getMaximumAccessibleValueFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::getMinimumAccessibleValueFromContext(jobject accessibleContext, wchar_t *value, short len) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getMinimumAccessibleValueFromContext(%p, %p, %hd):",
                     accessibleContext, value, len);

    // Get the minimum Accessible Value
    if (getMinimumAccessibleValueFromContextMethod != nullptr) {
        const jstring js = (jstring) jniEnv->CallObjectMethod(accessBridgeObject,
                                                              getMinimumAccessibleValueFromContextMethod,
                                                              accessibleContext);
        EXCEPTION_CHECK("Getting MinimumAccessibleValue - call to CallObjectMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), js = %p", js);

        if (js != nullptr) {
            (void)AccessBridgeUtils::CopyJavaStringToWCharBuffer(*jniEnv, js, value, (len < 0) ? 0 : len);
            EXCEPTION_CHECK("Getting MinimumAccessibleValue - attempt to copy the java string content", FALSE);

            jniEnv->CallVoidMethod(accessBridgeObject, decrementReferenceMethod, js);
            EXCEPTION_CHECK("Getting MinimumAccessibleValue - call to CallVoidMethod()", FALSE);

            wPrintDebugString(L" [INFO]:   minimum Accessible Value = \"%ls\"", value);

            jniEnv->DeleteLocalRef(js);
            EXCEPTION_CHECK("Getting MinimumAccessibleValue - call to DeleteLocalRef()", FALSE);
        } else {
            PrintDebugString(" [WARN]:   minimum Accessible Value is null.");
            value[0] = wchar_t{ 0 };

            return FALSE;
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getMinimumAccessibleValueFromContextMethod == 0");
        return FALSE;
    }

    return TRUE;
}


/********** AccessibleSelection routines ***************/

void
AccessBridgeJavaEntryPoints::addAccessibleSelectionFromContext(jobject accessibleContext, int i) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::addAccessibleSelectionFromContext(%p, i=%d):",
                     accessibleContext, i);

    // Add the child to the AccessibleSelection
    if (addAccessibleSelectionFromContextMethod != nullptr) {
        jniEnv->CallVoidMethod(accessBridgeObject,
                               addAccessibleSelectionFromContextMethod,
                               accessibleContext, i);
        EXCEPTION_CHECK_VOID("Doing addAccessibleSelection - call to CallVoidMethod()");

        PrintDebugString(" [INFO]:   returned from CallObjectMethod()");
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or addAccessibleSelectionFromContextMethod == 0");
    }
}

void
AccessBridgeJavaEntryPoints::clearAccessibleSelectionFromContext(jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::clearAccessibleSelectionFromContext(%p):", accessibleContext);

    // Clearing the Selection of the AccessibleSelection
    if (clearAccessibleSelectionFromContextMethod != nullptr) {
        jniEnv->CallVoidMethod(accessBridgeObject,
                               clearAccessibleSelectionFromContextMethod,
                               accessibleContext);
        EXCEPTION_CHECK_VOID("Doing clearAccessibleSelection - call to CallVoidMethod()");

        PrintDebugString(" [INFO]:   returned from CallObjectMethod()");
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or clearAccessibleSelectionFromContextMethod == 0");
    }
}

jobject
AccessBridgeJavaEntryPoints::getAccessibleSelectionFromContext(jobject accessibleContext, int i) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleSelectionFromContext(%p, i=%d):",
                     accessibleContext, i);

    if (getAccessibleSelectionContextFromContextMethod != nullptr) {
        const jobject returnedAccessibleContext = jniEnv->CallObjectMethod(
            accessBridgeObject, getAccessibleSelectionContextFromContextMethod, accessibleContext, i
        );
        EXCEPTION_CHECK("Getting AccessibleSelectionContext - call to CallObjectMethod()", NULL);

        const jobject globalRef = jniEnv->NewGlobalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleSelectionContext - call to NewGlobalRef()", NULL);

        jniEnv->DeleteLocalRef(returnedAccessibleContext);
        EXCEPTION_CHECK("Getting AccessibleSelectionContext - call to DeleteLocalRef()", NULL);

        PrintDebugString(" [INFO]:   Returning - returnedAccessibleContext = %p; globalRef = %p",
                         returnedAccessibleContext, globalRef);
        return globalRef;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleSelectionContextFromContextMethod == 0");
        return nullptr;
    }
}

int
AccessBridgeJavaEntryPoints::getAccessibleSelectionCountFromContext(jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::getAccessibleSelectionCountFromContext(%p):", accessibleContext);

    // Get (& return) the # of items selected in the AccessibleSelection
    if (getAccessibleSelectionCountFromContextMethod != nullptr) {
        const jint count = jniEnv->CallIntMethod(accessBridgeObject,
                                                 getAccessibleSelectionCountFromContextMethod,
                                                 accessibleContext);
        EXCEPTION_CHECK("Getting AccessibleSelectionCount - call to CallIntMethod()", -1);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod()");

        return (int)count;
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or getAccessibleSelectionCountFromContextMethod == 0");
        return -1;
    }
}

BOOL
AccessBridgeJavaEntryPoints::isAccessibleChildSelectedFromContext(jobject accessibleContext, int i) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::isAccessibleChildSelectedFromContext(%p, i=%d):",
                     accessibleContext, i);

    // Get (& return) the # of items selected in the AccessibleSelection
    if (isAccessibleChildSelectedFromContextMethod != nullptr) {
        const jboolean result = jniEnv->CallBooleanMethod(accessBridgeObject,
                                                          isAccessibleChildSelectedFromContextMethod,
                                                          accessibleContext, i);
        EXCEPTION_CHECK("Doing isAccessibleChildSelected - call to CallBooleanMethod()", FALSE);

        PrintDebugString(" [INFO]:   returned from CallObjectMethod(), result=%d", int{ result });

        if (result == JNI_TRUE) {
            return TRUE;
        }
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or isAccessibleChildSelectedFromContextMethod == 0");
    }

    return FALSE;
}


void
AccessBridgeJavaEntryPoints::removeAccessibleSelectionFromContext(jobject accessibleContext, int i) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::removeAccessibleSelectionFromContext(%p, i=%d):",
                     accessibleContext, i);

    // Remove the i-th child from the AccessibleSelection
    if (removeAccessibleSelectionFromContextMethod != nullptr) {
        jniEnv->CallVoidMethod(accessBridgeObject,
                               removeAccessibleSelectionFromContextMethod,
                               accessibleContext, i);
        EXCEPTION_CHECK_VOID("Doing removeAccessibleSelection - call to CallVoidMethod()");

        PrintDebugString(" [INFO]:   returned from CallObjectMethod()");
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or removeAccessibleSelectionFromContextMethod == 0");
    }
}

void
AccessBridgeJavaEntryPoints::selectAllAccessibleSelectionFromContext(jobject accessibleContext) {
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::selectAllAccessibleSelectionFromContext(%p):", accessibleContext);

    // Select all children (if possible) of the AccessibleSelection
    if (selectAllAccessibleSelectionFromContextMethod != nullptr) {
        jniEnv->CallVoidMethod(accessBridgeObject,
                               selectAllAccessibleSelectionFromContextMethod,
                               accessibleContext);
        EXCEPTION_CHECK_VOID("Doing selectAllAccessibleSelection - call to CallVoidMethod()");

        PrintDebugString(" [INFO]:   returned from CallObjectMethod()");
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or selectAllAccessibleSelectionFromContextMethod == 0");
    }
}


/********** Event Notification Registration routines ***************/

BOOL
AccessBridgeJavaEntryPoints::addJavaEventNotification(jlong type) {
    using LongLong = long long;
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::addJavaEventNotification(%llX);", LongLong{ type });

    // Let AccessBridge know we want to add an event type
    if (addJavaEventNotificationMethod != nullptr) {
        jniEnv->CallVoidMethod(accessBridgeObject,
                               addJavaEventNotificationMethod, type);
        EXCEPTION_CHECK("Doing addJavaEventNotification - call to CallVoidMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or addJavaEventNotificationMethod == 0");
        return FALSE;
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::addJavaEventNotification: returning true.");

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::removeJavaEventNotification(jlong type) {
    using LongLong = long long;
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::removeJavaEventNotification(%llX):", LongLong{ type });

    // Let AccessBridge know we want to remove an event type
    if (removeJavaEventNotificationMethod != nullptr) {
        jniEnv->CallVoidMethod(accessBridgeObject,
                               removeJavaEventNotificationMethod, type);
        EXCEPTION_CHECK("Doing removeJavaEventNotification - call to CallVoidMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or removeJavaEventNotificationMethod == 0");
        return FALSE;
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::removeJavaEventNotification: returning true.");

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::addAccessibilityEventNotification(jlong type) {
    using LongLong = long long;
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::addAccessibilityEventNotification(%llX);",
                     LongLong{ type });

    // Let AccessBridge know we want to add an event type
    if (addAccessibilityEventNotificationMethod != nullptr) {
        PrintDebugString(" [INFO]:   addAccessibilityEventNotification: In void method: accessBridgeObject = %p",
                         accessBridgeObject);

        jniEnv->CallVoidMethod(accessBridgeObject,addAccessibilityEventNotificationMethod, type);
        EXCEPTION_CHECK("Doing addAccessibilityEvent - call to CallVoidMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or addAccessibilityEventNotificationMethod == 0");
        return FALSE;
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::addAccessibilityEventNotification: returning true.");

    return TRUE;
}

BOOL
AccessBridgeJavaEntryPoints::removeAccessibilityEventNotification(jlong type) {
    using LongLong = long long;
    PrintDebugString(" [INFO]: In AccessBridgeJavaEntryPoints::removeAccessibilityEventNotification(%llX):",
                     LongLong{ type });

    // Let AccessBridge know we want to remove an event type
    if (removeAccessibilityEventNotificationMethod != nullptr) {
        jniEnv->CallVoidMethod(accessBridgeObject, removeAccessibilityEventNotificationMethod, type);
        EXCEPTION_CHECK("Doing removeAccessibilityEvent - call to CallVoidMethod()", FALSE);
    } else {
        PrintDebugString("[ERROR]:   either env == 0 or removeAccessibilityEventNotificationMethod == 0");
        return FALSE;
    }

    PrintDebugString(" [INFO]:   AccessBridgeJavaEntryPoints::removeAccessibilityEventNotification: returning true.");

    return TRUE;
}
