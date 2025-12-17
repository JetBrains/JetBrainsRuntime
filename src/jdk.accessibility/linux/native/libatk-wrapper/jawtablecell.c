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

/**
 * (From Atk documentation)
 *
 * AtkTableCell:
 *
 * The ATK interface implemented for a cell inside a two-dimentional #AtkTable
 *
 * Being #AtkTable a component which present elements ordered via rows
 * and columns, an #AtkTableCell is the interface which each of those
 * elements, so "cells" should implement.
 *
 * See [iface@AtkTable]
 */

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
    JAW_GET_OBJ_IFACE(                                                         \
        cell, org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE_CELL,       \
        TableCellData, atk_table_cell, jniEnv, jatk_table_cell, def_ret)

/**
 * AtkTableCellIface:
 * @get_column_span: virtual function that returns the number of
 *   columns occupied by this cell accessible
 * @get_column_header_cells: virtual function that returns the column
 *   headers as an array of cell accessibles
 * @get_position: virtual function that retrieves the tabular position
 *   of this cell
 * @get_row_span: virtual function that returns the number of rows
 *   occupied by this cell
 * @get_row_header_cells: virtual function that returns the row
 *   headers as an array of cell accessibles
 * @get_row_column_span: virtual function that get the row an column
 *   indexes and span of this cell
 * @get_table: virtual function that returns a reference to the
 *   accessible of the containing table
 *
 * AtkTableCell is an interface for cells inside an #AtkTable.
 *
 * Since: 2.12
 */
void jaw_table_cell_interface_init(AtkTableCellIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (iface == NULL) {
        g_warning("%s: Null argument iface passed to the function", G_STRFUNC);
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

    if (ac == NULL) {
        g_warning("%s: Null argument ac passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    if (jniEnv == NULL) {
        g_warning("%s: jniEnv is NULL", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (classTableCell == NULL) {
        g_warning("%s: Failed to find AtkTableCell class", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classTableCell, "create_atk_table_cell",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkTableCell;");
    if (jmid == NULL) {
        g_warning("%s: Failed to get method ID for create_atk_table_cell", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_table_cell =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classTableCell, jmid, ac);
    if (jatk_table_cell == NULL) {
        g_warning("%s: Failed to create jatk_table_cell object", G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    TableCellData *data = g_new0(TableCellData, 1);
    data->atk_table_cell = (*jniEnv)->NewGlobalRef(jniEnv, jatk_table_cell);
    if (data->atk_table_cell == NULL) {
        g_warning("%s: Failed to create global ref for atk_table_cell", G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_table_cell_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    TableCellData *data = (TableCellData *)p;
    if (data == NULL) {
        g_warning("%s: TableCellData is NULL after cast", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize, leaking JNI resources", G_STRFUNC);
    } else {
        if (data->jstrDescription != NULL) {
            if (data->description != NULL) {
                (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                                 data->description);
                data->description = NULL;
            }
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
            data->jstrDescription = NULL;
        }

        if (data->atk_table_cell != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_table_cell);
            data->atk_table_cell = NULL;
        }
    }

    g_free(data);
}

/**
 * jaw_table_cell_get_table:
 * @cell: a GObject instance that implements AtkTableCellIface
 *
 * Returns a reference to the accessible of the containing table.
 *
 * Returns: (transfer full): the atk object for the containing table.
 */
static AtkObject *jaw_table_cell_get_table(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLECELL(cell, NULL); // create global JNI reference `jobject jatk_table_cell`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jatk_table_cell); // deleting ref that was created in
                              // JAW_GET_TABLECELL
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (classAtkTableCell == NULL) {
        g_warning("%s: Failed to find AtkTableCell class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTableCell, "get_table",
                               "()Ljavax/accessibility/AccessibleTable;");
    if (jmid == NULL) {
        g_warning("%s: Failed to get method ID for get_table", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, jatk_table_cell, jmid);
    if (jac == NULL) {
        g_warning("%s: get_table returned NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
    // From the documentation of the `cell_get_table`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full)
    if (jaw_impl != NULL) {
        g_object_ref(G_OBJECT(jaw_impl));
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * getPosition:
 * @jniEnv: JNI environment pointer.
 * @jatk_table_cell: a Java object implementing the AtkTableCell interface.
 * @classAtkTableCell: the Java class of @jatk_table_cell.
 * @row: (out): return location for the zero-based row index of the cell.
 * @column: (out): return location for the zero-based column index of the cell.
 *
 * Retrieves the row and column index of the cell.
 * If the operation succeeds, the values of @row and @column are updated.
 * If it fails, the output arguments are left unchanged.
 *
 * Returns: %TRUE if successful; %FALSE otherwise.
 */
static gboolean getPosition(JNIEnv *jniEnv, jobject jatk_table_cell,
                            jclass classAtkTableCell, gint *row, gint *column) {
    if (jniEnv == NULL || row == NULL || column == NULL) {
        g_warning("%s: Null argument. jniEnv=%p, row=%p, column=%p",
                  G_STRFUNC,
                  (void*)jniEnv,
                  (void*)row,
                  (void*)column);
        return FALSE;
    }

    jfieldID id_row =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "row", "I");
    if (id_row == NULL) {
        g_warning("%s: Failed to get field ID for 'row'", G_STRFUNC);
        return FALSE;
    }

    jfieldID id_column =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "column", "I");
    if (id_column == NULL) {
        g_warning("%s: Failed to get field ID for 'column'", G_STRFUNC);
        return FALSE;
    }

    jint jrow = (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_row);
    if (jrow < 0) {
        g_warning("%s: Invalid row value (%d) retrieved", G_STRFUNC, jrow);
        return FALSE;
    }

    jint jcolumn = (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_column);
    if (jcolumn < 0) {
        g_warning("%s: Invalid column value (%d) retrieved", G_STRFUNC, jcolumn);
        return FALSE;
    }

    (*row) = (gint)jrow;
    (*column) = (gint)jcolumn;
    return TRUE;
}

/**
 * jaw_table_cell_get_position:
 * @cell: an #AtkTableCell.
 * @row: (out): the row of the given cell.
 * @column: (out): the column of the given cell.
 *
 * Retrieves the tabular position of this cell.
 *
 * Returns: %TRUE if successful; %FALSE otherwise.
 *
 * Since: 2.12
 */
static gboolean jaw_table_cell_get_position(AtkTableCell *cell, gint *row,
                                            gint *column) {
    if (cell == NULL || row == NULL || column == NULL) {
        g_warning("%s: Null argument. cell=%p, row=%p, column=%p",
                  G_STRFUNC,
                  (void*)cell,
                  (void*)row,
                  (void*)column);
        return FALSE;
    }

    JAW_DEBUG_C("%p, %p, %p", cell, row, column);
    JAW_GET_TABLECELL(cell, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (!classAtkTableCell) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    if (!getPosition(jniEnv, jatk_table_cell, classAtkTableCell, row, column)) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return TRUE;
}

/**
 * getRowSpan:
 * @jniEnv: JNI environment pointer.
 * @jatk_table_cell: a Java object implementing the AtkTableCell interface.
 * @classAtkTableCell: the Java class of @jatk_table_cell.
 * @row_span: (out): return location for the row-span value.
 *
 * Retrieves the `rowSpan` field from a Java ATK table cell object.
 * On success, the value is stored in @row_span.
 * On failure, @row_span is left unchanged.
 *
 * Returns: %TRUE if the value was successfully retrieved; %FALSE otherwise.
 */
static gboolean getRowSpan(JNIEnv *jniEnv, jobject jatk_table_cell,
                           jclass classAtkTableCell, gint *row_span) {
    if (jniEnv == NULL || row_span == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    jfieldID id_row_span =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "rowSpan", "I");
    if (id_row_span == NULL) {
        g_warning("%s: Failed to get field ID for rowSpan", G_STRFUNC);
        return FALSE;
    }

    jint jrow_span =
        (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_row_span);
    if (jrow_span < 0) {
        g_warning("%s: Invalid row span value (%d) retrieved", G_STRFUNC, jrow_span);
        return FALSE;
    }

    (*row_span) = (gint)jrow_span;
    return TRUE;
}

/**
 * getColumnSpan:
 * @jniEnv: a valid JNI environment pointer.
 * @jatk_table_cell: a Java object implementing the AtkTableCell interface.
 * @classAtkTableCell: the Java class of @jatk_table_cell.
 * @column_span: (out): return location for the column-span value.
 *
 * Retrieves the `columnSpan` field from a Java ATK table cell object.
 * On success, the value is stored in @column_span.
 * On failure, @column_span is left unchanged.
 *
 * Returns: %TRUE if the column span value was successfully retrieved and
 *          stored in @column_span; %FALSE on error.
 */
static gboolean getColumnSpan(JNIEnv *jniEnv, jobject jatk_table_cell,
                              jclass classAtkTableCell, gint *column_span) {
    if (jniEnv == NULL || column_span == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }
    jfieldID id_column_span =
        (*jniEnv)->GetFieldID(jniEnv, classAtkTableCell, "columnSpan", "I");
    if (id_column_span == NULL) {
        g_warning("%s: Failed to get field ID for columnSpan", G_STRFUNC);
        return FALSE;
    }

    jint jcolumn_span =
        (*jniEnv)->GetIntField(jniEnv, jatk_table_cell, id_column_span);
    if (jcolumn_span < 0) {
        g_warning("%s: Invalid column span value (%d) retrieved", G_STRFUNC, jcolumn_span);
        return FALSE;
    }

    (*column_span) = (gint)jcolumn_span;
    return TRUE;
}

/**
 * jaw_table_cell_get_row_column_span:
 * @cell: an #AtkTableCell.
 * @row: (out): the row index of the given cell.
 * @column: (out): the column index of the given cell.
 * @row_span: (out): the number of rows occupied by this cell.
 * @column_span: (out): the number of columns occupied by this cell.
 *
 * Gets the row and column indexes and span of this cell accessible.
 *
 * Note: Even if the function returns %FALSE, some of the output arguments
 *       may have been partially updated before the failure occurred.
 *
 * Returns: %TRUE if successful; %FALSE otherwise.
 *
 * Since: 2.12
 */
static gboolean jaw_table_cell_get_row_column_span(AtkTableCell *cell,
                                                   gint *row, gint *column,
                                                   gint *row_span,
                                                   gint *column_span) {
    JAW_DEBUG_C("%p, %p, %p, %p, %p", cell, row, column, row_span, column_span);

    if (cell == NULL || row == NULL || column == NULL || row_span == NULL || column_span == NULL) {
        g_warning("%s: Null argument. cell=%p, row=%p, column=%p, row_span=%p, column_span=%p",
                  G_STRFUNC,
                  (void*)cell,
                  (void*)row,
                  (void*)column,
                  (void*)row_span,
                  (void*)column_span);
        return FALSE;
    }

    JAW_GET_TABLECELL(cell, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jatk_table_cell); // deleting ref that was created in
                              // JAW_GET_TABLECELL
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (classAtkTableCell == NULL) {
        g_warning("%s: Failed to find AtkTableCell class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    if (!getPosition(jniEnv, jatk_table_cell, classAtkTableCell, row, column)) {
        g_warning("%s: getPosition failed", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    if (!getRowSpan(jniEnv, jatk_table_cell, classAtkTableCell, row_span)) {
        g_warning("%s: getRowSpan failed", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    if (!getColumnSpan(jniEnv, jatk_table_cell, classAtkTableCell, column_span)) {
        g_warning("%s: getColumnSpan failed", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return TRUE;
}

/**
 * jaw_table_cell_get_row_span:
 * @cell: (nullable): an #AtkTableCell instance
 *
 * Returns the number of rows occupied by this cell accessible.
 *
 * Returns: (type gint):
 *     A gint representing the number of rows occupied by this cell, or 0 if the cell does not implement this method.
 *
 * Since: 2.12
 */
static gint jaw_table_cell_get_row_span(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLECELL(cell, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jatk_table_cell); // deleting ref that was created in
                              // JAW_GET_TABLECELL
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    gint row_span = 0;
    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (classAtkTableCell == NULL) {
        g_warning("%s: Failed to find AtkTableCell class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    if (!getRowSpan(jniEnv, jatk_table_cell, classAtkTableCell, &row_span)) {
        g_warning("%s: getRowSpan failed", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
    return row_span;
}

/**
 * jaw_table_cell_get_column_span:
 * @cell: (nullable): an #AtkTableCell instance
 *
 * Returns the number of columns occupied by this table cell.
 *
 * Returns: (type gint):
 *     A gint representing the number of columns occupied by this cell,
 *     or 0 if the cell does not implement this method.
 *
 * Since: 2.12
 */
static gint jaw_table_cell_get_column_span(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("%s: Null argument cell passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLECELL(cell, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jatk_table_cell); // deleting ref that was created in
                              // JAW_GET_TABLECELL
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    gint column_span = 0;
    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (classAtkTableCell == NULL) {
        g_warning("%s: Failed to find AtkTableCell class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    if (!getColumnSpan(jniEnv, jatk_table_cell, classAtkTableCell, &column_span)) {
        g_warning("%s: getColumnSpan failed", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return column_span;
}

/**
 * jaw_table_cell_get_column_header_cells:
 * @cell: a GObject instance that implements AtkTableCellIface
 *
 * Returns the column headers as an array of cell accessibles.
 *
 * Returns: (element-type AtkObject) (transfer full): a GPtrArray of AtkObjects
 * representing the column header cells.
 */
static GPtrArray *jaw_table_cell_get_column_header_cells(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLECELL(cell, NULL); // create global JNI reference `jobject jatk_table_cell`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jatk_table_cell); // deleting ref that was created in
                              // JAW_GET_TABLECELL
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (classAtkTableCell == NULL) {
        g_warning("%s: Failed to find AtkTableCell class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTableCell, "get_accessible_column_header",
        "()[Ljavax/accessibility/AccessibleContext;");
    if (jmid == NULL) {
        g_warning("%s: Failed to get method ID for get_accessible_column_header", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobjectArray columnHeaders = (jobjectArray)(*jniEnv)->CallObjectMethod(
        jniEnv, jatk_table_cell, jmid);
    if (columnHeaders == NULL) {
        g_warning("%s: get_accessible_column_header returned NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jsize length = (*jniEnv)->GetArrayLength(jniEnv, columnHeaders);
    GPtrArray *result = g_ptr_array_sized_new((guint)length);
    if (result == NULL) {
        g_warning("%s: Failed to allocate GPtrArray", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    for (int i = 0; i < length; i++) {
        jobject jac = (*jniEnv)->GetObjectArrayElement(jniEnv, columnHeaders, i);
        JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
        if (jaw_impl != NULL) {
            g_ptr_array_add(result, jaw_impl);

            // FIXME: is it true that the caller is responsible for freeing not
            //  only GPtrArray but all its elements? From the documentation of
            //  the `atk_table_cell_get_column_header_cells`: "The caller of the
            //  method takes ownership of the returned data, and is responsible
            //  for freeing it." (transfer full)
            g_object_ref(G_OBJECT(jaw_impl));
        }

        (*jniEnv)->DeleteLocalRef(jniEnv, jac);
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
}

/**
 * jaw_table_cell_get_row_header_cells:
 * @cell: a GObject instance that implements AtkTableCellIface
 *
 * Returns the row headers as an array of cell accessibles.
 *
 * Returns: (element-type AtkObject) (transfer full): a GPtrArray of AtkObjects
 * representing the row header cells.
 */
static GPtrArray *jaw_table_cell_get_row_header_cells(AtkTableCell *cell) {
    JAW_DEBUG_C("%p", cell);

    if (!cell) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLECELL(cell, NULL); // create global JNI reference `jobject jatk_table_cell`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            jatk_table_cell); // deleting ref that was created in
                              // JAW_GET_TABLECELL
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTableCell =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTableCell");
    if (classAtkTableCell == NULL) {
        g_warning("%s: Failed to find AtkTableCell class", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTableCell, "get_accessible_row_header",
        "()[Ljavax/accessibility/AccessibleContext;");
    if (jmid == NULL) {
        g_warning("%s: Failed to get method ID for get_accessible_row_header", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobjectArray rowHeaders = (jobjectArray)(*jniEnv)->CallObjectMethod(
        jniEnv, jatk_table_cell, jmid);
    if (rowHeaders == NULL) {
        g_warning("%s: get_accessible_row_header returned NULL", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    jsize length = (*jniEnv)->GetArrayLength(jniEnv, rowHeaders);
    GPtrArray *result = g_ptr_array_sized_new((guint)length);
    if (result == NULL) {
        g_warning("%s: Failed to allocate GPtrArray", G_STRFUNC);
        (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    for (int i = 0; i < length; i++) {
        jobject jac = (*jniEnv)->GetObjectArrayElement(jniEnv, rowHeaders, i);
        JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
        if (jaw_impl != NULL) {
            g_ptr_array_add(result, jaw_impl);

            // FIXME: is it true that the caller is responsible for freeing not
            //  only GPtrArray but all its elements? From the documentation of
            //  the `atk_table_cell_get_row_header_cells`: "The caller of the
            //  method takes ownership of the returned data, and is responsible
            //  for freeing it." (transfer full)
            g_object_ref(G_OBJECT(jaw_impl));
        }

        (*jniEnv)->DeleteLocalRef(jniEnv, jac);
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, jatk_table_cell);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return result;
}
