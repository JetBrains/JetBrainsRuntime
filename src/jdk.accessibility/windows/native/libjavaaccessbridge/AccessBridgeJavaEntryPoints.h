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

#include "AccessBridgePackages.h"

#include <windows.h>
#include <jni.h>

#ifndef __AccessBridgeJavaEntryPoints_H__
#define __AccessBridgeJavaEntryPoints_H__

class AccessBridgeJavaEntryPoints {
    JNIEnv *jniEnv;

    jobject accessBridgeObject;

    jclass bridgeClass                                            = nullptr;
    jclass eventHandlerClass                                      = nullptr;

    jmethodID decrementReferenceMethod                            = nullptr;
    jmethodID getJavaVersionPropertyMethod                        = nullptr;

    jmethodID isJavaWindowMethod                                  = nullptr;
    jmethodID isSameObjectMethod                                  = nullptr;
    jmethodID getAccessibleContextFromHWNDMethod                  = nullptr;
    jmethodID getHWNDFromAccessibleContextMethod                  = nullptr;

    jmethodID getAccessibleContextAtMethod                        = nullptr;
    jmethodID getAccessibleContextWithFocusMethod                 = nullptr;

    jmethodID getAccessibleNameFromContextMethod                  = nullptr;
    jmethodID getAccessibleDescriptionFromContextMethod           = nullptr;
    jmethodID getAccessibleRoleStringFromContextMethod            = nullptr;
    jmethodID getAccessibleRoleStringFromContext_en_USMethod      = nullptr;
    jmethodID getAccessibleStatesStringFromContextMethod          = nullptr;
    jmethodID getAccessibleStatesStringFromContext_en_USMethod    = nullptr;
    jmethodID getAccessibleParentFromContextMethod                = nullptr;
    jmethodID getAccessibleIndexInParentFromContextMethod         = nullptr;
    jmethodID getAccessibleChildrenCountFromContextMethod         = nullptr;
    jmethodID getAccessibleChildFromContextMethod                 = nullptr;
    jmethodID getAccessibleBoundsOnScreenFromContextMethod        = nullptr;
    jmethodID getAccessibleXcoordFromContextMethod                = nullptr;
    jmethodID getAccessibleYcoordFromContextMethod                = nullptr;
    jmethodID getAccessibleHeightFromContextMethod                = nullptr;
    jmethodID getAccessibleWidthFromContextMethod                 = nullptr;

    jmethodID getAccessibleComponentFromContextMethod             = nullptr;
    jmethodID getAccessibleActionFromContextMethod                = nullptr;
    jmethodID getAccessibleSelectionFromContextMethod             = nullptr;
    jmethodID getAccessibleTextFromContextMethod                  = nullptr;
    jmethodID getAccessibleValueFromContextMethod                 = nullptr;

    /* begin AccessibleTable */
    jmethodID getAccessibleTableFromContextMethod                 = nullptr;
    jmethodID getAccessibleTableRowHeaderMethod                   = nullptr;
    jmethodID getAccessibleTableColumnHeaderMethod                = nullptr;
    jmethodID getAccessibleTableRowCountMethod                    = nullptr;
    jmethodID getAccessibleTableColumnCountMethod                 = nullptr;
    jmethodID getAccessibleTableCaptionMethod                     = nullptr;
    jmethodID getAccessibleTableSummaryMethod                     = nullptr;

    jmethodID getContextFromAccessibleTableMethod                 = nullptr;
    jmethodID getAccessibleTableCellAccessibleContextMethod       = nullptr;
    jmethodID getAccessibleTableCellIndexMethod                   = nullptr;
    jmethodID getAccessibleTableCellRowExtentMethod               = nullptr;
    jmethodID getAccessibleTableCellColumnExtentMethod            = nullptr;
    jmethodID isAccessibleTableCellSelectedMethod                 = nullptr;

    jmethodID getAccessibleTableRowHeaderRowCountMethod           = nullptr;
    jmethodID getAccessibleTableColumnHeaderRowCountMethod        = nullptr;

    jmethodID getAccessibleTableRowHeaderColumnCountMethod        = nullptr;
    jmethodID getAccessibleTableColumnHeaderColumnCountMethod     = nullptr;

    jmethodID getAccessibleTableRowDescriptionMethod              = nullptr;
    jmethodID getAccessibleTableColumnDescriptionMethod           = nullptr;

    jmethodID getAccessibleTableRowSelectionCountMethod           = nullptr;
    jmethodID isAccessibleTableRowSelectedMethod                  = nullptr;
    jmethodID getAccessibleTableRowSelectionsMethod               = nullptr;

    jmethodID getAccessibleTableColumnSelectionCountMethod        = nullptr;
    jmethodID isAccessibleTableColumnSelectedMethod               = nullptr;
    jmethodID getAccessibleTableColumnSelectionsMethod            = nullptr;

    jmethodID getAccessibleTableRowMethod                         = nullptr;
    jmethodID getAccessibleTableColumnMethod                      = nullptr;
    jmethodID getAccessibleTableIndexMethod                       = nullptr;

    /* end AccessibleTable */

    /* begin AccessibleRelationSet */

    jmethodID getAccessibleRelationSetMethod                      = nullptr;
    jmethodID getAccessibleRelationCountMethod                    = nullptr;
    jmethodID getAccessibleRelationKeyMethod                      = nullptr;
    jmethodID getAccessibleRelationTargetCountMethod              = nullptr;
    jmethodID getAccessibleRelationTargetMethod                   = nullptr;

    /* end AccessibleRelationSet */

    // AccessibleHypertext methods
    jmethodID getAccessibleHypertextMethod                        = nullptr;
    jmethodID getAccessibleHyperlinkCountMethod                   = nullptr;
    jmethodID getAccessibleHyperlinkTextMethod                    = nullptr;
    jmethodID getAccessibleHyperlinkURLMethod                     = nullptr;
    jmethodID getAccessibleHyperlinkStartIndexMethod              = nullptr;
    jmethodID getAccessibleHyperlinkEndIndexMethod                = nullptr;
    jmethodID getAccessibleHypertextLinkIndexMethod               = nullptr;
    jmethodID getAccessibleHyperlinkMethod                        = nullptr;
    jmethodID activateAccessibleHyperlinkMethod                   = nullptr;

    // AccessibleKeyBinding
    jmethodID getAccessibleKeyBindingsCountMethod                 = nullptr;
    jmethodID getAccessibleKeyBindingCharMethod                   = nullptr;
    jmethodID getAccessibleKeyBindingModifiersMethod              = nullptr;

    // AccessibleIcon
    jmethodID getAccessibleIconsCountMethod                       = nullptr;
    jmethodID getAccessibleIconDescriptionMethod                  = nullptr;
    jmethodID getAccessibleIconHeightMethod                       = nullptr;
    jmethodID getAccessibleIconWidthMethod                        = nullptr;

    // AccessibleAction
    jmethodID getAccessibleActionsCountMethod                     = nullptr;
    jmethodID getAccessibleActionNameMethod                       = nullptr;
    jmethodID doAccessibleActionsMethod                           = nullptr;

    // AccessibleText
    jmethodID getAccessibleCharCountFromContextMethod             = nullptr;
    jmethodID getAccessibleCaretPositionFromContextMethod         = nullptr;
    jmethodID getAccessibleIndexAtPointFromContextMethod          = nullptr;

    jmethodID getAccessibleLetterAtIndexFromContextMethod         = nullptr;
    jmethodID getAccessibleWordAtIndexFromContextMethod           = nullptr;
    jmethodID getAccessibleSentenceAtIndexFromContextMethod       = nullptr;

    jmethodID getAccessibleTextSelectionStartFromContextMethod    = nullptr;
    jmethodID getAccessibleTextSelectionEndFromContextMethod      = nullptr;
    jmethodID getAccessibleTextSelectedTextFromContextMethod      = nullptr;
    jmethodID getAccessibleAttributesAtIndexFromContextMethod     = nullptr;
    jmethodID getAccessibleAttributeSetAtIndexFromContextMethod   = nullptr;
    jmethodID getAccessibleTextRectAtIndexFromContextMethod       = nullptr;
    jmethodID getAccessibleXcoordTextRectAtIndexFromContextMethod = nullptr;
    jmethodID getAccessibleYcoordTextRectAtIndexFromContextMethod = nullptr;
    jmethodID getAccessibleHeightTextRectAtIndexFromContextMethod = nullptr;
    jmethodID getAccessibleWidthTextRectAtIndexFromContextMethod  = nullptr;
    jmethodID getAccessibleTextLineLeftBoundsFromContextMethod    = nullptr;
    jmethodID getAccessibleTextLineRightBoundsFromContextMethod   = nullptr;
    jmethodID getAccessibleTextRangeFromContextMethod             = nullptr;

    jmethodID getCurrentAccessibleValueFromContextMethod          = nullptr;
    jmethodID getMaximumAccessibleValueFromContextMethod          = nullptr;
    jmethodID getMinimumAccessibleValueFromContextMethod          = nullptr;

    jmethodID addAccessibleSelectionFromContextMethod             = nullptr;
    jmethodID clearAccessibleSelectionFromContextMethod           = nullptr;
    jmethodID getAccessibleSelectionContextFromContextMethod      = nullptr;
    jmethodID getAccessibleSelectionCountFromContextMethod        = nullptr;
    jmethodID isAccessibleChildSelectedFromContextMethod          = nullptr;
    jmethodID removeAccessibleSelectionFromContextMethod          = nullptr;
    jmethodID selectAllAccessibleSelectionFromContextMethod       = nullptr;

    jmethodID addJavaEventNotificationMethod                      = nullptr;
    jmethodID removeJavaEventNotificationMethod                   = nullptr;
    jmethodID addAccessibilityEventNotificationMethod             = nullptr;
    jmethodID removeAccessibilityEventNotificationMethod          = nullptr;

    jmethodID getBoldFromAttributeSetMethod                       = nullptr;
    jmethodID getItalicFromAttributeSetMethod                     = nullptr;
    jmethodID getUnderlineFromAttributeSetMethod                  = nullptr;
    jmethodID getStrikethroughFromAttributeSetMethod              = nullptr;
    jmethodID getSuperscriptFromAttributeSetMethod                = nullptr;
    jmethodID getSubscriptFromAttributeSetMethod                  = nullptr;
    jmethodID getBackgroundColorFromAttributeSetMethod            = nullptr;
    jmethodID getForegroundColorFromAttributeSetMethod            = nullptr;
    jmethodID getFontFamilyFromAttributeSetMethod                 = nullptr;
    jmethodID getFontSizeFromAttributeSetMethod                   = nullptr;
    jmethodID getAlignmentFromAttributeSetMethod                  = nullptr;
    jmethodID getBidiLevelFromAttributeSetMethod                  = nullptr;
    jmethodID getFirstLineIndentFromAttributeSetMethod            = nullptr;
    jmethodID getLeftIndentFromAttributeSetMethod                 = nullptr;
    jmethodID getRightIndentFromAttributeSetMethod                = nullptr;
    jmethodID getLineSpacingFromAttributeSetMethod                = nullptr;
    jmethodID getSpaceAboveFromAttributeSetMethod                 = nullptr;
    jmethodID getSpaceBelowFromAttributeSetMethod                 = nullptr;

    jmethodID setTextContentsMethod                               = nullptr;
    jmethodID getParentWithRoleMethod                             = nullptr;
    jmethodID getTopLevelObjectMethod                             = nullptr;
    jmethodID getParentWithRoleElseRootMethod                     = nullptr;
    jmethodID getObjectDepthMethod                                = nullptr;
    jmethodID getActiveDescendentMethod                           = nullptr;

    /**
     * Additional methods for Teton
     */
    jmethodID getVirtualAccessibleNameFromContextMethod           = nullptr; // Ben Key
    jmethodID requestFocusMethod                                  = nullptr;
    jmethodID selectTextRangeMethod                               = nullptr;
    jmethodID getTextAttributesInRangeMethod                      = nullptr;
    jmethodID getVisibleChildrenCountMethod                       = nullptr;
    jmethodID getVisibleChildMethod                               = nullptr;
    jmethodID setCaretPositionMethod                              = nullptr;

    jmethodID getCaretLocationMethod                              = nullptr;
    jmethodID getCaretLocationXMethod                             = nullptr;
    jmethodID getCaretLocationYMethod                             = nullptr;
    jmethodID getCaretLocationHeightMethod                        = nullptr;
    jmethodID getCaretLocationWidthMethod                         = nullptr;

public:
    AccessBridgeJavaEntryPoints(JNIEnv *jniEnvironment, jobject bridgeObject);
    ~AccessBridgeJavaEntryPoints();
    BOOL BuildJavaEntryPoints();

    // HWND management methods
    BOOL isJavaWindow(jint window);
    jobject getAccessibleContextFromHWND(jint window);
    HWND getHWNDFromAccessibleContext(jobject accessibleContext);

    // version methods
    BOOL getVersionInfo(AccessBridgeVersionInfo *info);

    // verification methods
    BOOL verifyAccessibleText(jobject obj);

    /* ===== utility methods ===== */
    BOOL isSameObject(jobject obj1, jobject obj2);
    BOOL setTextContents(jobject accessibleContext, const wchar_t *text);
    jobject getParentWithRole(jobject accessibleContext, const wchar_t *role);
    jobject getTopLevelObject(jobject accessibleContext);
    jobject getParentWithRoleElseRoot(jobject accessibleContext, const wchar_t *role);
    jint getObjectDepth(jobject accessibleContext);
    jobject getActiveDescendent(jobject accessibleContext);

    // Accessible Context methods
    jobject getAccessibleContextAt(jint x, jint y, jobject AccessibleContext);
    jobject getAccessibleContextWithFocus();
    BOOL getAccessibleContextInfo(jobject AccessibleContext, AccessibleContextInfo *info);
    jobject getAccessibleChildFromContext(jobject AccessibleContext, jint childIndex);
    jobject getAccessibleParentFromContext(jobject AccessibleContext);

    /* begin AccessibleTable methods */

    BOOL getAccessibleTableInfo(jobject acParent, AccessibleTableInfo *tableInfo);
    BOOL getAccessibleTableCellInfo(jobject accessibleTable,jint row, jint column,
                                    AccessibleTableCellInfo *tableCellInfo);

    BOOL getAccessibleTableRowHeader(jobject acParent, AccessibleTableInfo *tableInfo);
    BOOL getAccessibleTableColumnHeader(jobject acParent, AccessibleTableInfo *tableInfo);

    jobject getAccessibleTableRowDescription(jobject acParent, jint row);
    jobject getAccessibleTableColumnDescription(jobject acParent, jint column);

    jint getAccessibleTableRowSelectionCount(jobject accessibleTable);
    BOOL isAccessibleTableRowSelected(jobject accessibleTable, jint row);
    BOOL getAccessibleTableRowSelections(jobject accessibleTable, jint count, jint *selections);

    jint getAccessibleTableColumnSelectionCount(jobject accessibleTable);
    BOOL isAccessibleTableColumnSelected(jobject accessibleTable, jint column);
    BOOL getAccessibleTableColumnSelections(jobject accessibleTable, jint count, jint *selections);

    jint getAccessibleTableRow(jobject accessibleTable, jint index);
    jint getAccessibleTableColumn(jobject accessibleTable, jint index);
    jint getAccessibleTableIndex(jobject accessibleTable, jint row, jint column);

    /* end AccessibleTable methods */

    BOOL getAccessibleRelationSet(jobject accessibleContext, AccessibleRelationSetInfo *relationSetInfo);

    // AccessibleHypertext methods
    BOOL getAccessibleHypertext(jobject accessibleContext, AccessibleHypertextInfo *hyperlink);

    BOOL activateAccessibleHyperlink(jobject accessibleContext, jobject accessibleHyperlink);

    BOOL getAccessibleHypertextExt(jobject accessibleContext, jint nStartIndex,
                                   /* OUT */ AccessibleHypertextInfo *hypertext);
    jint getAccessibleHyperlinkCount(jobject accessibleContext);
    jint getAccessibleHypertextLinkIndex(jobject accessibleContext, jint nIndex);
    BOOL getAccessibleHyperlink(jobject accessibleContext, jint nIndex,
                                /* OUT */ AccessibleHyperlinkInfo *hyperlinkInfo);

    // Accessible Keybinding methods
    BOOL getAccessibleKeyBindings(jobject accessibleContext, AccessibleKeyBindings *keyBindings);

    // AccessibleIcon methods
    BOOL getAccessibleIcons(jobject accessibleContext, AccessibleIcons *icons);

    // AccessibleActionMethods
    BOOL getAccessibleActions(jobject accessibleContext, AccessibleActions *actions);
    BOOL doAccessibleActions(jobject accessibleContext, AccessibleActionsToDo *actionsToDo, jint *failure);

    // Accessible Text methods
    BOOL getAccessibleTextInfo(jobject AccessibleContext, AccessibleTextInfo *textInfo, jint x, jint y);
    BOOL getAccessibleTextItems(jobject AccessibleContext, AccessibleTextItemsInfo *textItems, jint index);
    BOOL getAccessibleTextSelectionInfo(jobject AccessibleContext, AccessibleTextSelectionInfo *selectionInfo);
    BOOL getAccessibleTextAttributes(jobject AccessibleContext, jint index, AccessibleTextAttributesInfo *attributes);
    BOOL getAccessibleTextRect(jobject AccessibleContext, AccessibleTextRectInfo *rectInfo, jint index);
    BOOL getAccessibleCaretRect(jobject AccessibleContext, AccessibleTextRectInfo *rectInfo, jint index);
    BOOL getAccessibleTextLineBounds(jobject AccessibleContext, jint index, jint *startIndex, jint *endIndex);
    BOOL getAccessibleTextRange(jobject AccessibleContext, jint start, jint end, wchar_t *text, short len);

    // Accessible Value methods
    BOOL getCurrentAccessibleValueFromContext(jobject AccessibleContext, wchar_t *value, short len);
    BOOL getMaximumAccessibleValueFromContext(jobject AccessibleContext, wchar_t *value, short len);
    BOOL getMinimumAccessibleValueFromContext(jobject AccessibleContext, wchar_t *value, short len);

    // Accessible Selection methods
    void addAccessibleSelectionFromContext(jobject AccessibleContext, int i);
    void clearAccessibleSelectionFromContext(jobject AccessibleContext);
    jobject getAccessibleSelectionFromContext(jobject AccessibleContext, int i);
    int getAccessibleSelectionCountFromContext(jobject AccessibleContext);
    BOOL isAccessibleChildSelectedFromContext(jobject AccessibleContext, int i);
    void removeAccessibleSelectionFromContext(jobject AccessibleContext, int i);
    void selectAllAccessibleSelectionFromContext(jobject AccessibleContext);

    // Event handling methods
    BOOL addJavaEventNotification(jlong type);
    BOOL removeJavaEventNotification(jlong type);
    BOOL addAccessibilityEventNotification(jlong type);
    BOOL removeAccessibilityEventNotification(jlong type);

    /**
     * Additional methods for Teton
     */

    /**
     * Gets the AccessibleName for a component based upon the JAWS algorithm. Returns
     * whether successful.
     *
     * Bug ID 4916682 - Implement JAWS AccessibleName policy
     */
    BOOL getVirtualAccessibleName(jobject accessibleContext, wchar_t *name, int len);

    /**
     * Request focus for a component. Returns whether successful;
     *
     * Bug ID 4944757 - requestFocus method needed
     */
    BOOL requestFocus(jobject accessibleContext);

    /**
     * Selects text between two indices.  Selection includes the text at the start index
     * and the text at the end index. Returns whether successful;
     *
     * Bug ID 4944758 - selectTextRange method needed
     */
    BOOL selectTextRange(jobject accessibleContext, int startIndex, int endIndex);

    /**
     * Get text attributes between two indices.  The attribute list includes the text at the
     * start index and the text at the end index. Returns whether successful;
     *
     * Bug ID 4944761 - getTextAttributes between two indices method needed
     */
    BOOL getTextAttributesInRange(jobject accessibleContext, int startIndex, int endIndex,
                                  AccessibleTextAttributesInfo *attributes, short *len);

    /**
     * Gets the number of visible children of a component. Returns -1 on error.
     *
     * Bug ID 4944762- getVisibleChildren for list-like components needed
     */
    int getVisibleChildrenCount(jobject accessibleContext);

    /**
     * Gets the visible children of an AccessibleContext. Returns whether successful;
     *
     * Bug ID 4944762- getVisibleChildren for list-like components needed
     */
    BOOL getVisibleChildren(jobject accessibleContext, int startIndex,
                            VisibleChildrenInfo *visibleChildrenInfo);

    /**
     * Set the caret to a text position. Returns whether successful;
     *
     * Bug ID 4944770 - setCaretPosition method needed
     */
    BOOL setCaretPosition(jobject accessibleContext, int position);

    /**
     * Gets the bounding rectangle for the text caret
     */
    BOOL getCaretLocation(jobject AccessibleContext, AccessibleTextRectInfo *rectInfo, jint index);
};

#endif
