/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2015 Magdalen Berns <m.berns@thismagpie.com>
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  021101301  USA
 */

#include "jawimpl.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

static AtkObject *jaw_table_cell_get_table(AtkTableCell *cell);
static GPtrArray *jaw_table_cell_get_column_header_cells(AtkTableCell *cell);
static gboolean jaw_table_cell_get_position(AtkTableCell *cell, gint *row,
                                            gint *column);
static gboolean jaw_table_cell_get_row_column_span(AtkTableCell *cell,
                                                   gint *row, gint *column,
                                                   gint *row_span,
                                                   gint *column_span);
static gint jaw_table_cell_get_row_span(AtkTableCell *cell);
static GPtrArray *jaw_table_cell_get_row_header_cells(AtkTableCell *cell);
static gint jaw_table_cell_get_column_span(AtkTableCell *cell);

typedef struct _TableCellData {
    jobject atk_table_cell;
    gchar *description;
    jstring jstrDescription;
} TableCellData;

#define JAW_GET_TABLECELL(cell, def_ret)                                       \
    JAW_GET_OBJ_IFACE(cell, INTERFACE_TABLE_CELL, TableCellData,               \
                      atk_table_cell, jniEnv, jatk_table_cell, def_ret)

void jaw_table_cell_interface_init(AtkTableCellIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);
    iface->get_column_span = jaw_table_cell_get_column_span;
    iface->get_column_header_cells = jaw_table_cell_get_column_header_cells;
    iface->get_position = jaw_table_cell_get_position;
    iface->get_row_span = jaw_table_cell_get_row_span;
    iface->get_row_header_cells = jaw_table_cell_get_row_header_cells;
    iface->get_row_column_span = jaw_table_cell_get_row_column_span;
    iface->get_table = jaw_table_cell_get_table;
}

gpointer jaw_table_cell_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);
    TableCellData *data = g_new0(TableCellData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    jclass classTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classTableCell, "createAtkTableCell",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkTableCell;");
    jobject jatk_table_cell =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classTableCell, jmid, ac);
    data->atk_table_cell = (*jniEnv)->NewGlobalRef(jniEnv, jatk_table_cell);

    return data;
}

void jaw_table_cell_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);
    TableCellData *data = (TableCellData *)p;
    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (data && data->atk_table_cell) {
        if (data->description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                             data->description);
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
            data->jstrDescription = NULL;
            data->description = NULL;
        }

        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_table_cell);
        data->atk_table_cell = NULL;
    }
}

static AtkObject *jaw_table_cell_get_table(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);
    JAW_GET_TABLECELL(cell, NULL);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTableCell, "getTable",
                               "()Ljavax/accessibility/AccessibleTable;");
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, jatk_table_cell, jmid);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);

    if (!jac)
        return NULL;

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);

    return ATK_OBJECT(jaw_impl);
}

static void getPosition(JNIEnv *jniEnv, jobject jatk_table_cell,
                        jclass classAtkTableCell, gint *row, gint *column) {
    jfieldID id_row =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "row", "I");
    jfieldID id_column =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "column", "I");
    jint jrow = (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_row);
    jint jcolumn = (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_column);
    (*row) = (gint)jrow;
    (*column) = (gint)jcolumn;
}

static gboolean jaw_table_cell_get_position(AtkTableCell *cell, gint *row,
                                            gint *column) {
    JAW_DEBUG_C("%p, %p, %p", cell, row, column);
    JAW_GET_TABLECELL(cell, FALSE);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    getPosition(jniEnv, jatk_table_cell, classAtkTableCell, row, column);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    return TRUE;
}

static void getRowSpan(JNIEnv *jniEnv, jobject jatk_table_cell,
                       jclass classAtkTableCell, gint *row_span) {
    jfieldID id_row_span =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "rowSpan", "I");
    jint jrow_span =
        (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_row_span);
    (*row_span) = (gint)jrow_span;
}

static void getColumnSpan(JNIEnv *jniEnv, jobject jatk_table_cell,
                          jclass classAtkTableCell, gint *column_span) {
    jfieldID id_column_span =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "columnSpan", "I");
    jint jcolumn_span =
        (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_column_span);
    (*column_span) = (gint)jcolumn_span;
}

static gboolean jaw_table_cell_get_row_column_span(AtkTableCell *cell,
                                                   gint *row, gint *column,
                                                   gint *row_span,
                                                   gint *column_span) {
    JAW_DEBUG_C("%p, %p, %p, %p, %p", cell, row, column, row_span, column_span);
    JAW_GET_TABLECELL(cell, FALSE);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    getPosition(jniEnv, jatk_table_cell, classAtkTableCell, row, column);
    getRowSpan(jniEnv, jatk_table_cell, classAtkTableCell, row_span);
    getColumnSpan(jniEnv, jatk_table_cell, classAtkTableCell, column_span);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    return TRUE;
}

static gint jaw_table_cell_get_row_span(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);
    JAW_GET_TABLECELL(cell, 0);

    gint row_span = -1;
    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    getRowSpan(jniEnv, jatk_table_cell, classAtkTableCell, &row_span);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    return row_span;
}

static gint jaw_table_cell_get_column_span(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);
    JAW_GET_TABLECELL(cell, 0);

    gint column_span = -1;
    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    getColumnSpan(jniEnv, jatk_table_cell, classAtkTableCell, &column_span);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    return column_span;
}

static GPtrArray *jaw_table_cell_get_column_header_cells(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);
    JAW_GET_TABLECELL(cell, NULL);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTableCell, "getAccessibleColumnHeader",
        "()[Ljavax/accessibility/AccessibleContext;");
    jobjectArray ja_ac = (jobjectArray)(*jniEnv)->CallObjectMethod(
        jniEnv, jatk_table_cell, jmid);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    if (!ja_ac)
        return NULL;
    jsize length = (*jniEnv)->GetArrayLength(jniEnv, ja_ac);
    GPtrArray *result = g_ptr_array_sized_new((guint)length);
    for (int i = 0; i < length; i++) {
        jobject jac = (*jniEnv)->GetObjectArrayElement(jniEnv, ja_ac, i);
        JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);
        g_ptr_array_add(result, jaw_impl);
    }
    return result;
}

static GPtrArray *jaw_table_cell_get_row_header_cells(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);
    JAW_GET_TABLECELL(cell, NULL);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTableCell, "getAccessibleRowHeader",
        "()[Ljavax/accessibility/AccessibleContext;");
    jobjectArray ja_ac = (jobjectArray)(*jniEnv)->CallObjectMethod(
        jniEnv, jatk_table_cell, jmid);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    if (!ja_ac)
        return NULL;
    jsize length = (*jniEnv)->GetArrayLength(jniEnv, ja_ac);
    GPtrArray *result = g_ptr_array_sized_new((guint)length);
    for (int i = 0; i < length; i++) {
        jobject jac = (*jniEnv)->GetObjectArrayElement(jniEnv, ja_ac, i);
        JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);
        g_ptr_array_add(result, jaw_impl);
    }
    return result;
}
