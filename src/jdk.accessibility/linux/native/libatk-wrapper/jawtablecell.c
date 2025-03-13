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

    if (!iface) {
        g_warning("Null argument passed to function");
        return;
    }

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

    if (!ac) {
      g_warning("Null argument passed to function");
      return NULL;
    }

    TableCellData *data = g_new0(TableCellData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, NULL);
    jclass classTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    CHECK_NULL(classTableCell, NULL);
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classTableCell, "create_atk_table_cell",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkTableCell;");
    CHECK_NULL(jmid, NULL);
    jobject jatk_table_cell =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classTableCell, jmid, ac);
    CHECK_NULL(jatk_table_cell, NULL);
    data->atk_table_cell = (*jniEnv)->NewGlobalRef(jniEnv, jatk_table_cell);

    return data;
}

void jaw_table_cell_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("Null argument passed to function");
        return;
    }

    TableCellData *data = (TableCellData *)p;
    CHECK_NULL(data, );

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, );

    if (data->description != NULL) {
            if (data->jstrDescription != NULL) {
                (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                                 data->description);
                (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
                data->jstrDescription = NULL;
            }
            data->description = NULL;
        }

    if ( data->atk_table_cell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_table_cell);
        data->atk_table_cell = NULL;
    }
}

static AtkObject *jaw_table_cell_get_table(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_TABLECELL(cell, NULL);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (!classAtkTableCell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTableCell, "get_table",
                               "()Ljavax/accessibility/AccessibleTable;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return NULL;
    }
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, jatk_table_cell, jmid);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    CHECK_NULL(jac, NULL);

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);

    return ATK_OBJECT(jaw_impl);
}

static void getPosition(JNIEnv *jniEnv, jobject jatk_table_cell,
                        jclass classAtkTableCell, gint *row, gint *column) {
    if (!jniEnv || !row || !column) {
        g_warning("Null argument passed to function");
        return;
    }

    jfieldID id_row =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "row", "I");
    CHECK_NULL(id_row, );
    jfieldID id_column =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "column", "I");
    CHECK_NULL(id_column, );
    jint jrow = (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_row);
    CHECK_NULL(jrow, );
    jint jcolumn = (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_column);
    CHECK_NULL(jcolumn, );
    (*row) = (gint)jrow;
    (*column) = (gint)jcolumn;
}

static gboolean jaw_table_cell_get_position(AtkTableCell *cell, gint *row,
                                            gint *column) {
    if (!cell || !row || !column) {
        g_warning("Null argument passed to function");
        return FALSE;
    }

    JAW_DEBUG_C("%p, %p, %p", cell, row, column);
    JAW_GET_TABLECELL(cell, FALSE);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (!classAtkTableCell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return FALSE;
    }
    getPosition(jniEnv, jatk_table_cell, classAtkTableCell, row, column);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    return TRUE;
}

static void getRowSpan(JNIEnv *jniEnv, jobject jatk_table_cell,
                       jclass classAtkTableCell, gint *row_span) {
    if (!jniEnv || !row_span) {
        g_warning("Null argument passed to function");
        return;
    }

    jfieldID id_row_span =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "rowSpan", "I");
    CHECK_NULL(id_row_span, );
    jint jrow_span =
        (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_row_span);
    CHECK_NULL(jrow_span, );
    (*row_span) = (gint)jrow_span;
}

static void getColumnSpan(JNIEnv *jniEnv, jobject jatk_table_cell,
                          jclass classAtkTableCell, gint *column_span) {
    jfieldID id_column_span =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "columnSpan", "I");
    CHECK_NULL(id_column_span, );
    jint jcolumn_span =
        (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_column_span);
    CHECK_NULL(jcolumn_span, );
    (*column_span) = (gint)jcolumn_span;
}

static gboolean jaw_table_cell_get_row_column_span(AtkTableCell *cell,
                                                   gint *row, gint *column,
                                                   gint *row_span,
                                                   gint *column_span) {
    JAW_DEBUG_C("%p, %p, %p, %p, %p", cell, row, column, row_span, column_span);

    if (!cell || !row || !column || !row_span || !column_span) {
        g_warning("Null argument passed to function");
        return FALSE;
    }

    JAW_GET_TABLECELL(cell, FALSE);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (!classAtkTableCell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return FALSE;
    }
    getPosition(jniEnv, jatk_table_cell, classAtkTableCell, row, column);
    getRowSpan(jniEnv, jatk_table_cell, classAtkTableCell, row_span);
    getColumnSpan(jniEnv, jatk_table_cell, classAtkTableCell, column_span);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    return TRUE;
}

static gint jaw_table_cell_get_row_span(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("Null argument passed to function");
        return 0;
    }

    JAW_GET_TABLECELL(cell, 0);

    gint row_span = -1;
    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (!classAtkTableCell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return 0;
    }
    getRowSpan(jniEnv, jatk_table_cell, classAtkTableCell, &row_span);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    return row_span;
}

static gint jaw_table_cell_get_column_span(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("Null argument passed to function");
        return 0;
    }

    JAW_GET_TABLECELL(cell, 0);

    gint column_span = -1;
    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (!classAtkTableCell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return 0;
    }
    getColumnSpan(jniEnv, jatk_table_cell, classAtkTableCell, &column_span);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    return column_span;
}

static GPtrArray *jaw_table_cell_get_column_header_cells(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_TABLECELL(cell, NULL);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (!classAtkTableCell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTableCell, "get_accessible_column_header",
        "()[Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return NULL;
    }
    jobjectArray ja_ac = (jobjectArray)(*jniEnv)->CallObjectMethod(
        jniEnv, jatk_table_cell, jmid);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    CHECK_NULL(ja_ac, NULL);
    jsize length = (*jniEnv)->GetArrayLength(jniEnv, ja_ac);
    GPtrArray *result = g_ptr_array_sized_new((guint)length);
    for (int i = 0; i < length; i++) {
        jobject jac = (*jniEnv)->GetObjectArrayElement(jniEnv, ja_ac, i);
        JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);
        if (!jaw_impl) {
            g_ptr_array_add(result, jaw_impl);
        }
    }
    return result;
}

static GPtrArray *jaw_table_cell_get_row_header_cells(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_TABLECELL(cell, NULL);

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (!classAtkTableCell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTableCell, "get_accessible_row_header",
        "()[Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        return NULL;
    }
    jobjectArray ja_ac = (jobjectArray)(*jniEnv)->CallObjectMethod(
        jniEnv, jatk_table_cell, jmid);
    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    CHECK_NULL(ja_ac, NULL);
    jsize length = (*jniEnv)->GetArrayLength(jniEnv, ja_ac);
    GPtrArray *result = g_ptr_array_sized_new((guint)length);
    CHECK_NULL(result, NULL);
    for (int i = 0; i < length; i++) {
        jobject jac = (*jniEnv)->GetObjectArrayElement(jniEnv, ja_ac, i);
        JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, jac);
        if (!jaw_impl) {
            g_ptr_array_add(result, jaw_impl);
        }
    }
    return result;
}
