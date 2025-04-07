/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301 USA
 */

#include "jawimpl.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib.h>

/**
 * (From Atk documentation)
 *
 * AtkTable:
 *
 * The ATK interface implemented for UI components which contain tabular or
 * row/column information.
 *
 * #AtkTable should be implemented by components which present
 * elements ordered via rows and columns.  It may also be used to
 * present tree-structured information if the nodes of the trees can
 * be said to contain multiple "columns".  Individual elements of an
 * #AtkTable are typically referred to as "cells". Those cells should
 * implement the interface #AtkTableCell, but #Atk doesn't require
 * them to be direct children of the current #AtkTable. They can be
 * grand-children, grand-grand-children etc. #AtkTable provides the
 * API needed to get a individual cell based on the row and column
 * numbers.
 *
 * Children of #AtkTable are frequently "lightweight" objects, that
 * is, they may not have backing widgets in the host UI toolkit.  They
 * are therefore often transient.
 *
 * Since tables are often very complex, #AtkTable includes provision
 * for offering simplified summary information, as well as row and
 * column headers and captions.  Headers and captions are #AtkObjects
 * which may implement other interfaces (#AtkText, #AtkImage, etc.) as
 * appropriate.  #AtkTable summaries may themselves be (simplified)
 * #AtkTables, etc.
 *
 * Note for implementors: in the past, #AtkTable required that all the
 * cells should be direct children of #AtkTable, and provided some
 * index based methods to request the cells. The practice showed that
 * that forcing made #AtkTable implementation complex, and hard to
 * expose other kind of children, like rows or captions. Right now,
 * index-based methods are deprecated.
 */

static AtkObject *jaw_table_ref_at(AtkTable *table, gint row, gint column);
static gint jaw_table_get_index_at(AtkTable *table, gint row, gint column);
static gint jaw_table_get_column_at_index(AtkTable *table, gint index);
static gint jaw_table_get_row_at_index(AtkTable *table, gint index);
static gint jaw_table_get_n_columns(AtkTable *table);
static gint jaw_table_get_n_rows(AtkTable *table);
static gint jaw_table_get_column_extent_at(AtkTable *table, gint row,
                                           gint column);
static gint jaw_table_get_row_extent_at(AtkTable *table, gint row, gint column);
static AtkObject *jaw_table_get_caption(AtkTable *table);
static const gchar *jaw_table_get_column_description(AtkTable *table,
                                                     gint column);
static const gchar *jaw_table_get_row_description(AtkTable *table, gint row);
static AtkObject *jaw_table_get_column_header(AtkTable *table, gint column);
static AtkObject *jaw_table_get_row_header(AtkTable *table, gint row);
static AtkObject *jaw_table_get_summary(AtkTable *table);
static gint jaw_table_get_selected_columns(AtkTable *table, gint **selected);
static gint jaw_table_get_selected_rows(AtkTable *table, gint **selected);
static gboolean jaw_table_is_column_selected(AtkTable *table, gint column);
static gboolean jaw_table_is_row_selected(AtkTable *table, gint row);
static gboolean jaw_table_is_selected(AtkTable *table, gint row, gint column);

static void jaw_table_set_row_description(AtkTable *table, gint row,
                                          const gchar *description);
static void jaw_table_set_column_description(AtkTable *table, gint column,
                                             const gchar *description);

static void jaw_table_set_caption(AtkTable *table, AtkObject *caption);
static void jaw_table_set_summary(AtkTable *table, AtkObject *summary);

typedef struct _TableData {
    jobject atk_table;
    gchar *description;
    jstring jstrDescription;
} TableData;

#define JAW_GET_TABLE(table, def_ret)                                          \
    JAW_GET_OBJ_IFACE(table, INTERFACE_TABLE, TableData, atk_table, jniEnv,    \
                      atk_table, def_ret)

void jaw_table_interface_init(AtkTableIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (!iface) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    iface->ref_at = jaw_table_ref_at;
    iface->get_index_at = jaw_table_get_index_at;
    iface->get_column_at_index = jaw_table_get_column_at_index;
    iface->get_row_at_index = jaw_table_get_row_at_index;
    iface->get_n_columns = jaw_table_get_n_columns;
    iface->get_n_rows = jaw_table_get_n_rows;
    iface->get_column_extent_at = jaw_table_get_column_extent_at;
    iface->get_row_extent_at = jaw_table_get_row_extent_at;
    iface->get_caption = jaw_table_get_caption;
    iface->get_column_description = jaw_table_get_column_description;
    iface->get_column_header = jaw_table_get_column_header;
    iface->get_row_description = jaw_table_get_row_description;
    iface->get_row_header = jaw_table_get_row_header;
    iface->get_summary = jaw_table_get_summary;
    iface->set_caption = jaw_table_set_caption;
    iface->set_column_description = jaw_table_set_column_description;
    iface->set_column_header =
        NULL; // impossible to do a on AccessibleTable Java Object
    iface->set_row_description = jaw_table_set_row_description;
    iface->set_row_header =
        NULL; // impossible to do a on AccessibleTable Java Object
    iface->set_summary = jaw_table_set_summary;
    iface->get_selected_columns = jaw_table_get_selected_columns;
    iface->get_selected_rows = jaw_table_get_selected_rows;
    iface->is_column_selected = jaw_table_is_column_selected;
    iface->is_row_selected = jaw_table_is_row_selected;
    iface->is_selected = jaw_table_is_selected;
    iface->add_row_selection = NULL;
    iface->remove_row_selection = NULL;
    iface->add_column_selection =
        NULL; // impossible to do a on AccessibleTable Java Object
    iface->remove_column_selection =
        NULL; // impossible to do a on AccessibleTable Java Object
}

gpointer jaw_table_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    TableData *data = g_new0(TableData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classTable) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classTable, "create_atk_table",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkTable;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_table =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classTable, jmid, ac);
    if (!jatk_table) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    data->atk_table = (*jniEnv)->NewGlobalRef(jniEnv, jatk_table);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_table_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    TableData *data = (TableData *)p;
    JAW_CHECK_NULL(data, );
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if (data->jstrDescription != NULL) {
        if (data->description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                             data->description);
            data->description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
        data->jstrDescription = NULL;
    }
    if (data->atk_table) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_table);
        data->atk_table = NULL;
    }
}

/**
 * jaw_table_ref_at:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in @table
 * @column: a #gint representing a column in @table
 *
 * Get a reference to the table cell at @row, @column. This cell
 * should implement the interface #AtkTableCell
 *
 * Returns: (transfer full): an #AtkObject representing the referred
 * to accessible
 **/
static AtkObject *jaw_table_ref_at(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JawObject *jaw_obj = JAW_OBJECT(table);
    if (!jaw_obj) {
        JAW_DEBUG_I("jaw_obj == NULL");
        return NULL;
    }
    TableData *data = jaw_object_get_interface_data(jaw_obj, INTERFACE_TABLE);
    JAW_CHECK_NULL(data, NULL);
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);
    jobject atk_table = (*jniEnv)->NewGlobalRef(jniEnv, data->atk_table);
    if (!atk_table) {
        JAW_DEBUG_I("atk_table == NULL");
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "ref_at",
                               "(II)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid,
                                              (jint)row, (jint)column);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);

    // From the documentation of the `ref_at`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full)
    if (jaw_impl != NULL) {
        g_object_ref(G_OBJECT(jaw_impl));
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * jaw_table_get_index_at:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in @table
 * @column: a #gint representing a column in @table
 *
 * Gets a #gint representing the index at the specified @row and
 * @column.
 *
 * Deprecated in atk: Since 2.12. Use atk_table_ref_at() in order to get the
 * accessible that represents the cell at (@row, @column)
 *
 * Returns: a #gint representing the index at specified position.
 * The value -1 is returned if the object at row,column is not a child
 * of table or table does not implement this interface.
 **/
static gint jaw_table_get_index_at(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_index_at", "(II)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jindex = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)row,
                                           (jint)column);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jindex;
}

/**
 * jaw_table_get_column_at_index:
 * @table: a GObject instance that implements AtkTableInterface
 * @index_: a #gint representing an index in @table
 *
 * Gets a #gint representing the column at the specified @index_.
 *
 * Deprecated in atk: Since 2.12.
 *
 * Returns: a gint representing the column at the specified index,
 * or -1 if the table does not implement this method.
 **/
static gint jaw_table_get_column_at_index(AtkTable *table, gint index) {
    JAW_DEBUG_C("%p, %d", table, index);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_column_at_index");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_column_at_index", "(I)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jcolumn =
        (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)index);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jcolumn;
}

/**
 * atk_table_get_row_at_index:
 * @table: a GObject instance that implements AtkTableInterface
 * @index_: a #gint representing an index in @table
 *
 * Gets a #gint representing the row at the specified @index_.
 *
 * Deprecated in atk: since 2.12.
 *
 * Returns: a gint representing the row at the specified index,
 * or -1 if the table does not implement this method.
 **/
static gint jaw_table_get_row_at_index(AtkTable *table, gint index) {
    JAW_DEBUG_C("%p, %d", table, index);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_row_at_index");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_row_at_index", "(I)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jrow = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)index);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jrow;
}

/**
 * atk_table_get_n_columns:
 * @table: a GObject instance that implements AtkTableIface
 *
 * Gets the number of columns in the table.
 *
 * Returns: a gint representing the number of columns, or 0
 * if value does not implement this interface.
 **/
static gint jaw_table_get_n_columns(AtkTable *table) {
    JAW_DEBUG_C("%p", table);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_n_columns", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jcolumns = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jcolumns;
}

/**
 * jaw_table_get_n_rows:
 * @table: a GObject instance that implements AtkTableIface
 *
 * Gets the number of rows in the table.
 *
 * Returns: a gint representing the number of rows, or 0
 * if value does not implement this interface.
 **/
static gint jaw_table_get_n_rows(AtkTable *table) {
    JAW_DEBUG_C("%p", table);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_n_rows", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jrows = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jrows;
}

/**
 * jaw_table_get_column_extent_at:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in @table
 * @column: a #gint representing a column in @table
 *
 * Gets the number of columns occupied by the accessible object
 * at the specified @row and @column in the @table.
 *
 * Returns: a gint representing the column extent at specified position, or 0
 * if value does not implement this interface.
 **/
static gint jaw_table_get_column_extent_at(AtkTable *table, gint row,
                                           gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_column_extent_at");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_column_extent_at", "(II)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jextent = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)row,
                                            (jint)column);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jextent;
}

/**
 * jaw_table_get_row_extent_at:
 * @table: a GObject instance that implements AtkTableIface
 * @column: a #gint representing a column in @table
 *
 * Gets the description text of the specified @column in the table
 *
 * Returns: a gchar* representing the column description, or %NULL
 * if value does not implement this interface.
 **/
static gint jaw_table_get_row_extent_at(AtkTable *table, gint row,
                                        gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_row_extent_at");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_row_extent_at", "(II)I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jextent = (*jniEnv)->CallIntMethod(jniEnv, atk_table, jmid, (jint)row,
                                            (jint)column);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jextent;
}

/**
 * jaw_table_get_caption:
 * @table: a GObject instance that implements AtkTableInterface
 *
 * Gets the caption for the @table.
 *
 * Returns: (nullable) (transfer none): a AtkObject* representing the
 * table caption, or %NULL if value does not implement this interface.
 **/
static AtkObject *jaw_table_get_caption(AtkTable *table) {
    JAW_DEBUG_C("%p", table);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_caption",
                               "()Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);

    // From documentation of the `get_caption` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the obj before returning it.

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * jaw_table_get_column_description:
 * @table: a GObject instance that implements AtkTableIface
 * @column: a #gint representing a column in @table
 * @description: a #gchar representing the description text
 * to set for the specified @column of the @table
 *
 * Sets the description text for the specified @column of the @table.
 **/
static const gchar *jaw_table_get_column_description(AtkTable *table,
                                                     gint column) {
    JAW_DEBUG_C("%p, %d", table, column);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_column_description",
                               "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid, (jint)column);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->description != NULL && data->jstrDescription != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                         data->description);
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
    }

    data->jstrDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->description = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrDescription, NULL);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->description;
}

/**
 * jaw_table_get_row_description:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in @table
 *
 * Gets the description text of the specified row in the table
 *
 * Returns: (nullable): a gchar* representing the row description, or
 * %NULL if value does not implement this interface.
 **/
static const gchar *jaw_table_get_row_description(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_row_description");
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTable, "get_row_description", "(I)Ljava/lang/String;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jstring jstr =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid, (jint)row);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->description != NULL && data->jstrDescription != NULL) {
        (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                         data->description);
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
    }

    data->jstrDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->description = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrDescription, NULL);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data->description;
}

/**
 * jaw_table_get_column_header:
 * @table: a GObject instance that implements AtkTableIface
 * @column: a #gint representing a column in the table
 *
 * Gets the column header of a specified column in an accessible table.
 *
 * Returns: (nullable) (transfer none): a AtkObject* representing the
 * specified column header, or %NULL if value does not implement this
 * interface.
 **/
static AtkObject *jaw_table_get_column_header(AtkTable *table, gint column) {
    JAW_DEBUG_C("%p, %d", table, column);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_column_header");
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_column_header",
                               "(I)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid, (jint)column);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
    // From documentation of the `atk_table_get_column_header` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the obj before returning it.

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * jaw_table_get_row_header:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in the table
 *
 * Gets the row header of a specified row in an accessible table.
 *
 * Returns: (nullable) (transfer none): a AtkObject* representing the
 * specified row header, or %NULL if value does not implement this
 * interface.
 **/
static AtkObject *jaw_table_get_row_header(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_row_header",
                               "(I)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid, (jint)row);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
    // From documentation of the `atk_table_get_row_header` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the jaw_impl before returning it.

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * jaw_table_get_summary:
 * @table: a GObject instance that implements AtkTableIface
 *
 * Gets the summary description of the table.
 *
 * Returns: (transfer full): a AtkObject* representing a summary description
 * of the table, or zero if value does not implement this interface.
 **/
static AtkObject *jaw_table_get_summary(AtkTable *table) {
    JAW_DEBUG_C("%p", table);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLE(table, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "get_summary",
                               "()Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid);
    if (!jac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
    // From the documentation of the `get_summary`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full)
    if (jaw_impl) {
        g_object_ref(G_OBJECT(jaw_impl));
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * jaw_table_get_selected_columns:
 * @table: a GObject instance that implements AtkTableIface
 * @selected: a #gint** that is to contain the selected columns numbers
 *
 * Gets the selected columns of the table by initializing **selected with
 * the selected column numbers. This array should be freed by the caller.
 *
 * Returns: a gint representing the number of selected columns,
 * or %0 if value does not implement this interface.
 **/
static gint jaw_table_get_selected_columns(AtkTable *table, gint **selected) {
    JAW_DEBUG_C("%p, %p", table, selected);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_get_selected_columns");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_selected_columns", "()[I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jintArray jcolumnArray =
        (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid);
    if (!jcolumnArray) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jsize length = (*jniEnv)->GetArrayLength(jniEnv, jcolumnArray);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return length;
}

/**
 * jaw_table_get_selected_rows:
 * @table: a GObject instance that implements AtkTableIface
 * @selected: a #gint** that is to contain the selected row numbers
 *
 * Gets the selected rows of the table by initializing **selected with
 * the selected row numbers. This array should be freed by the caller.
 *
 * Returns: a gint representing the number of selected rows,
 * or zero if value does not implement this interface.
 **/
static gint jaw_table_get_selected_rows(AtkTable *table, gint **selected) {
    JAW_DEBUG_C("%p, %p", table, selected);

    if (!table || !selected) {
        g_warning(
            "Null argument passed to function jaw_table_get_selected_rows");
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "get_selected_rows", "()[I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jintArray jrowArray = (*jniEnv)->CallObjectMethod(jniEnv, atk_table, jmid);
    if (!jrowArray) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jsize length = (*jniEnv)->GetArrayLength(jniEnv, jrowArray);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return length;
}

/**
 * jaw_table_is_column_selected:
 * @table: a GObject instance that implements AtkTableIface
 * @column: a #gint representing a column in @table
 *
 * Gets a boolean value indicating whether the specified @column
 * is selected
 *
 * Returns: a gboolean representing if the column is selected, or 0
 * if value does not implement this interface.
 **/
static gboolean jaw_table_is_column_selected(AtkTable *table, gint column) {
    JAW_DEBUG_C("%p, %d", table, column);

    if (!table) {
        g_warning(
            "Null argument passed to function jaw_table_is_column_selected");
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "is_column_selected", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jboolean jselected =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_table, jmid, (jint)column);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jselected;
}

/**
 * jaw_table_is_row_selected:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in @table
 *
 * Gets a boolean value indicating whether the specified @row
 * is selected
 *
 * Returns: a gboolean representing if the row is selected, or 0
 * if value does not implement this interface.
 **/
static gboolean jaw_table_is_row_selected(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkTable,
                                            "is_row_selected", "(I)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jselected =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_table, jmid, (jint)row);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jselected;
}

/**
 * jaw_table_is_selected:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in @table
 * @column: a #gint representing a column in @table
 *
 * Gets a boolean value indicating whether the accessible object
 * at the specified @row and @column is selected
 *
 * Returns: a gboolean representing if the cell is selected, or 0
 * if value does not implement this interface.
 **/
static gboolean jaw_table_is_selected(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);

    if (!table) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "is_selected", "(II)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jselected = (*jniEnv)->CallBooleanMethod(jniEnv, atk_table, jmid,
                                                      (jint)row, (jint)column);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jselected;
}

/**
 * jaw_table_set_row_description:
 * @table: a GObject instance that implements AtkTableIface
 * @row: a #gint representing a row in @table
 * @description: a #gchar representing the description text
 * to set for the specified @row of @table
 *
 * Sets the description text for the specified @row of @table.
 **/
static void jaw_table_set_row_description(AtkTable *table, gint row,
                                          const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", table, row, description);

    if (!table || !description) {
        g_warning(
            "Null argument passed to function jaw_table_set_row_description");
        return;
    }

    JAW_GET_TABLE(table, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkTable, "set_row_description", "(ILjava/lang/String;)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, description);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, jmid, (jint)row, jstr);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_table_set_column_description:
 * @table: a GObject instance that implements AtkTableIface
 * @column: a #gint representing a column in @table
 * @description: a #gchar representing the description text
 * to set for the specified @column of the @table
 *
 * Sets the description text for the specified @column of the @table.
 **/
static void jaw_table_set_column_description(AtkTable *table, gint column,
                                             const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", table, column, description);

    if (!table || !description) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_TABLE(table, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "set_column_description",
                               "(ILjava/lang/String;)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, description);
    if (!jstr) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, jmid, (jint)column, jstr);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_table_set_caption:
 * @table: a GObject instance that implements AtkTableIface
 * @caption: a #AtkObject representing the caption to set for @table
 *
 * Sets the caption for the table.
 **/
static void jaw_table_set_caption(AtkTable *table, AtkObject *caption) {
    JAW_DEBUG_C("%p, %p", table, caption);

    if (!table || !caption) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_TABLE(table, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    JawObject *jcaption = JAW_OBJECT(caption);
    if (!jcaption) {
        JAW_DEBUG_I("jcaption == NULL");
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass accessible =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/Accessible");
    if (!(*jniEnv)->IsInstanceOf(jniEnv, jcaption->acc_context, accessible)) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject obj = (*jniEnv)->NewGlobalRef(jniEnv, jcaption->acc_context);
    if (!obj) {
        JAW_DEBUG_I("jcaption obj == NULL");
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "set_caption",
                               "(Ljavax/accessibility/Accessible;)V");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, jmid, obj);
    (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_table_set_summary:
 * @table: a GObject instance that implements AtkTableIface
 * @accessible: an #AtkObject representing the summary description
 * to set for @table
 *
 * Sets the summary description of the table.
 **/
static void jaw_table_set_summary(AtkTable *table, AtkObject *summary) {
    JAW_DEBUG_C("%p, %p", table, summary);

    if (!table || !summary) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return;
    }

    JAW_GET_TABLE(table, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_table); // deleting ref that was created in JAW_GET_TABLE
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    JawObject *jsummary = JAW_OBJECT(summary);
    if (!jsummary) {
        JAW_DEBUG_I("jsummary == NULL");
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass accessible =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/Accessible");
    if (!(*jniEnv)->IsInstanceOf(jniEnv, jsummary->acc_context, accessible)) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject obj = (*jniEnv)->NewGlobalRef(jniEnv, jsummary->acc_context);
    if (!obj) {
        JAW_DEBUG_I("jsummary obj == NULL");
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    jclass classAtkTable =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkTable, "set_summary",
                               "(Ljavax/accessibility/Accessible;)V");
    if (!classAtkTable) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, jmid, obj);
    (*jniEnv)->DeleteGlobalRef(jniEnv, obj);
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}
