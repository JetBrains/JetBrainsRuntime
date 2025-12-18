/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301 USA
 */

#ifndef _JAW_CACHE_H_
#define _JAW_CACHE_H_

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

extern jclass cachedActionAtkActionClass;
extern jmethodID cachedActionCreateAtkActionMethod;
extern jmethodID cachedActionDoActionMethod;
extern jmethodID cachedActionGetNActionsMethod;
extern jmethodID cachedActionGetDescriptionMethod;
extern jmethodID cachedActionSetDescriptionMethod;
extern jmethodID cachedActionGetLocalizedNameMethod;

extern jclass cachedComponentAtkComponentClass;
extern jclass cachedComponentRectangleClass;
extern jmethodID cachedComponentCreateAtkComponentMethod;
extern jmethodID cachedComponentContainsMethod;
extern jmethodID cachedComponentGetAccessibleAtPointMethod;
extern jmethodID cachedComponentGetExtentsMethod;
extern jmethodID cachedComponentSetExtentsMethod;
extern jmethodID cachedComponentGrabFocusMethod;
extern jmethodID cachedComponentGetLayerMethod;
extern jfieldID cachedComponentRectangleXField;
extern jfieldID cachedComponentRectangleYField;
extern jfieldID cachedComponentRectangleWidthField;
extern jfieldID cachedComponentRectangleHeightField;

extern jclass cachedEditableTextAtkEditableTextClass;
extern jmethodID cachedEditableTextCreateAtkEditableTextMethod;
extern jmethodID cachedEditableTextSetTextContentsMethod;
extern jmethodID cachedEditableTextInsertTextMethod;
extern jmethodID cachedEditableTextCopyTextMethod;
extern jmethodID cachedEditableTextCutTextMethod;
extern jmethodID cachedEditableTextDeleteTextMethod;
extern jmethodID cachedEditableTextPasteTextMethod;
extern jmethodID cachedEditableTextSetRunAttributesMethod;

extern jclass cachedHyperlinkAtkHyperlinkClass;
extern jmethodID cachedHyperlinkGetUriMethod;
extern jmethodID cachedHyperlinkGetObjectMethod;
extern jmethodID cachedHyperlinkGetEndIndexMethod;
extern jmethodID cachedHyperlinkGetStartIndexMethod;
extern jmethodID cachedHyperlinkIsValidMethod;
extern jmethodID cachedHyperlinkGetNAnchorsMethod;

extern jclass cachedHypertextAtkHypertextClass;
extern jmethodID cachedHypertextCreateAtkHypertextMethod;
extern jmethodID cachedHypertextGetLinkMethod;
extern jmethodID cachedHypertextGetNLinksMethod;
extern jmethodID cachedHypertextGetLinkIndexMethod;

extern jclass cachedImageAtkImageClass;
extern jclass cachedImagePointClass;
extern jclass cachedImageDimensionClass;
extern jmethodID cachedImageCreateAtkImageMethod;
extern jmethodID cachedImageGetImagePositionMethod;
extern jmethodID cachedImageGetImageDescriptionMethod;
extern jmethodID cachedImageGetImageSizeMethod;
extern jfieldID cachedImagePointXFieldID;
extern jfieldID cachedImagePointYFieldID;
extern jfieldID cachedImageDimensionWidthFieldID;
extern jfieldID cachedImageDimensionHeightFieldID;

extern jclass cachedObjectAtkObjectClass;
extern jmethodID cachedObjectGetAccessibleParentMethod;
extern jmethodID cachedObjectSetAccessibleParentMethod;
extern jmethodID cachedObjectGetAccessibleNameMethod;
extern jmethodID cachedObjectSetAccessibleNameMethod;
extern jmethodID cachedObjectGetAccessibleDescriptionMethod;
extern jmethodID cachedObjectSetAccessibleDescriptionMethod;
extern jmethodID cachedObjectGetAccessibleChildrenCountMethod;
extern jmethodID cachedObjectGetAccessibleIndexInParentMethod;
extern jmethodID cachedObjectGetArrayAccessibleStateMethod;
extern jmethodID cachedObjectGetLocaleMethod;
extern jmethodID cachedObjectGetArrayAccessibleRelationMethod;
extern jmethodID cachedObjectGetAccessibleChildMethod;

extern jclass cachedSelectionAtkSelectionClass;
extern jmethodID cachedSelectionCreateAtkSelectionMethod;
extern jmethodID cachedSelectionAddSelectionMethod;
extern jmethodID cachedSelectionClearSelectionMethod;
extern jmethodID cachedSelectionRefSelectionMethod;
extern jmethodID cachedSelectionGetSelectionCountMethod;
extern jmethodID cachedSelectionIsChildSelectedMethod;
extern jmethodID cachedSelectionRemoveSelectionMethod;
extern jmethodID cachedSelectionSelectAllSelectionMethod;

extern jclass cachedTableAtkTableClass;
extern jmethodID cachedTableCreateAtkTableMethod;
extern jmethodID cachedTableRefAtMethod;
extern jmethodID cachedTableGetIndexAtMethod;
extern jmethodID cachedTableGetColumnAtIndexMethod;
extern jmethodID cachedTableGetRowAtIndexMethod;
extern jmethodID cachedTableGetNColumnsMethod;
extern jmethodID cachedTableGetNRowsMethod;
extern jmethodID cachedTableGetColumnExtentAtMethod;
extern jmethodID cachedTableGetRowExtentAtMethod;
extern jmethodID cachedTableGetCaptionMethod;
extern jmethodID cachedTableGetColumnDescriptionMethod;
extern jmethodID cachedTableGetRowDescriptionMethod;
extern jmethodID cachedTableGetColumnHeaderMethod;
extern jmethodID cachedTableGetRowHeaderMethod;
extern jmethodID cachedTableGetSummaryMethod;
extern jmethodID cachedTableGetSelectedColumnsMethod;
extern jmethodID cachedTableGetSelectedRowsMethod;
extern jmethodID cachedTableIsColumnSelectedMethod;
extern jmethodID cachedTableIsRowSelectedMethod;
extern jmethodID cachedTableIsSelectedMethod;
extern jmethodID cachedTableSetRowDescriptionMethod;
extern jmethodID cachedTableSetColumnDescriptionMethod;
extern jmethodID cachedTableSetCaptionMethod;
extern jmethodID cachedTableSetSummaryMethod;

extern jclass cachedTableCellAtkTableCellClass;
extern jmethodID cachedTableCellCreateAtkTableCellMethod;
extern jmethodID cachedTableCellGetTableMethod;
extern jmethodID cachedTableCellGetAccessibleColumnHeaderMethod;
extern jmethodID cachedTableCellGetAccessibleRowHeaderMethod;
extern jfieldID cachedTableCellRowFieldID;
extern jfieldID cachedTableCellColumnFieldID;
extern jfieldID cachedTableCellRowSpanFieldID;
extern jfieldID cachedTableCellColumnSpanFieldID;

extern jclass cachedTextAtkTextClass;
extern jclass cachedTextStringSequenceClass;
extern jmethodID cachedTextCreateAtkTextMethod;
extern jmethodID cachedTextGetTextMethod;
extern jmethodID cachedTextGetCharacterAtOffsetMethod;
extern jmethodID cachedTextGetTextAfterOffsetMethod;
extern jmethodID cachedTextGetTextAtOffsetMethod;
extern jmethodID cachedTextGetTextBeforeOffsetMethod;
extern jmethodID cachedTextGetStringAtOffsetMethod;
extern jmethodID cachedTextGetCaretOffsetMethod;
extern jmethodID cachedTextGetCharacterExtentsMethod;
extern jmethodID cachedTextGetCharacterCountMethod;
extern jmethodID cachedTextGetOffsetAtPointMethod;
extern jmethodID cachedTextGetRangeExtentsMethod;
extern jmethodID cachedTextGetNSelectionsMethod;
extern jmethodID cachedTextGetSelectionMethod;
extern jmethodID cachedTextAddSelectionMethod;
extern jmethodID cachedTextRemoveSelectionMethod;
extern jmethodID cachedTextSetSelectionMethod;
extern jmethodID cachedTextSetCaretOffsetMethod;
extern jfieldID cachedTextStrFieldID;
extern jfieldID cachedTextStartOffsetFieldID;
extern jfieldID cachedTextEndOffsetFieldID;

extern jclass cachedValueAtkValueClass;
extern jclass cachedValueByteClass;
extern jclass cachedValueDoubleClass;
extern jclass cachedValueFloatClass;
extern jclass cachedValueIntegerClass;
extern jclass cachedValueLongClass;
extern jclass cachedValueShortClass;
extern jmethodID cachedValueCreateAtkValueMethod;
extern jmethodID cachedValueGetCurrentValueMethod;
extern jmethodID cachedValueSetValueMethod;
extern jmethodID cachedValueGetMinimumValueMethod;
extern jmethodID cachedValueGetMaximumValueMethod;
extern jmethodID cachedValueGetIncrementMethod;
extern jmethodID cachedValueByteValueMethod;
extern jmethodID cachedValueDoubleValueMethod;
extern jmethodID cachedValueFloatValueMethod;
extern jmethodID cachedValueIntValueMethod;
extern jmethodID cachedValueLongValueMethod;
extern jmethodID cachedValueShortValueMethod;
extern jmethodID cachedValueDoubleConstructorMethod;

extern jclass cachedUtilAtkObjectClass;
extern jclass cachedUtilAccessibleRoleClass;
extern jclass cachedUtilAccessibleStateClass;
extern jclass cachedUtilRectangleClass;
extern jmethodID cachedUtilGetTflagFromObjMethod;
extern jmethodID cachedUtilGetAccessibleRoleMethod;
extern jmethodID cachedUtilGetAccessibleParentMethod;
extern jfieldID cachedUtilRectangleXField;
extern jfieldID cachedUtilRectangleYField;
extern jfieldID cachedUtilRectangleWidthField;
extern jfieldID cachedUtilRectangleHeightField;

void jaw_action_cache_cleanup(JNIEnv *jniEnv);
void jaw_component_cache_cleanup(JNIEnv *jniEnv);
void jaw_editable_text_cache_cleanup(JNIEnv *jniEnv);
void jaw_hyperlink_cache_cleanup(JNIEnv *jniEnv);
void jaw_hypertext_cache_cleanup(JNIEnv *jniEnv);
void jaw_image_cache_cleanup(JNIEnv *jniEnv);
void jaw_object_cache_cleanup(JNIEnv *jniEnv);
void jaw_selection_cache_cleanup(JNIEnv *jniEnv);
void jaw_table_cache_cleanup(JNIEnv *jniEnv);
void jaw_tablecell_cache_cleanup(JNIEnv *jniEnv);
void jaw_text_cache_cleanup(JNIEnv *jniEnv);
void jaw_value_cache_cleanup(JNIEnv *jniEnv);
void jaw_util_cache_cleanup(JNIEnv *jniEnv);

static inline void jaw_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        g_warning("%s: Null argument jniEnv passed to the function", G_STRFUNC);
        return;
    }

    jaw_action_cache_cleanup(jniEnv);
    jaw_component_cache_cleanup(jniEnv);
    jaw_editable_text_cache_cleanup(jniEnv);
    jaw_hyperlink_cache_cleanup(jniEnv);
    jaw_hypertext_cache_cleanup(jniEnv);
    jaw_image_cache_cleanup(jniEnv);
    jaw_object_cache_cleanup(jniEnv);
    jaw_selection_cache_cleanup(jniEnv);
    jaw_table_cache_cleanup(jniEnv);
    jaw_tablecell_cache_cleanup(jniEnv);
    jaw_text_cache_cleanup(jniEnv);
    jaw_value_cache_cleanup(jniEnv);
    jaw_util_cache_cleanup(jniEnv);
}

#ifdef __cplusplus
}
#endif

#endif