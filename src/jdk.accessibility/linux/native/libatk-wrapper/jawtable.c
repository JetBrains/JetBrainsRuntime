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

#include "jawcache.h"
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

jclass cachedTableAtkTableClass = NULL;
jmethodID cachedTableCreateAtkTableMethod = NULL;
jmethodID cachedTableRefAtMethod = NULL;
jmethodID cachedTableGetIndexAtMethod = NULL;
jmethodID cachedTableGetColumnAtIndexMethod = NULL;
jmethodID cachedTableGetRowAtIndexMethod = NULL;
jmethodID cachedTableGetNColumnsMethod = NULL;
jmethodID cachedTableGetNRowsMethod = NULL;
jmethodID cachedTableGetColumnExtentAtMethod = NULL;
jmethodID cachedTableGetRowExtentAtMethod = NULL;
jmethodID cachedTableGetCaptionMethod = NULL;
jmethodID cachedTableGetColumnDescriptionMethod = NULL;
jmethodID cachedTableGetRowDescriptionMethod = NULL;
jmethodID cachedTableGetColumnHeaderMethod = NULL;
jmethodID cachedTableGetRowHeaderMethod = NULL;
jmethodID cachedTableGetSummaryMethod = NULL;
jmethodID cachedTableGetSelectedColumnsMethod = NULL;
jmethodID cachedTableGetSelectedRowsMethod = NULL;
jmethodID cachedTableIsColumnSelectedMethod = NULL;
jmethodID cachedTableIsRowSelectedMethod = NULL;
jmethodID cachedTableIsSelectedMethod = NULL;
jmethodID cachedTableSetRowDescriptionMethod = NULL;
jmethodID cachedTableSetColumnDescriptionMethod = NULL;
jmethodID cachedTableSetCaptionMethod = NULL;
jmethodID cachedTableSetSummaryMethod = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_table_init_jni_cache(JNIEnv *jniEnv);

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
    JAW_GET_OBJ_IFACE(table,                                                   \
                      org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE,    \
                      TableData, atk_table, jniEnv, atk_table, def_ret)

void jaw_table_interface_init(AtkTableIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);

    if (iface == NULL) {
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

    if (ac == NULL) {
        g_warning("%s: Null argument ac passed to the function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if (!jaw_table_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jatk_table = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedTableAtkTableClass, cachedTableCreateAtkTableMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_table == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning(
            "%s: Failed to create jatk_table using create_atk_table method",
            G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    TableData *data = g_new0(TableData, 1);
    data->atk_table = (*jniEnv)->NewGlobalRef(jniEnv, jatk_table);
    if (data->atk_table == NULL) {
        g_warning("%s: Failed to create global ref for atk_table", G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_table_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_warning("%s: Null argument p passed to the function", G_STRFUNC);
        return;
    }

    TableData *data = (TableData *)p;
    if (data == NULL) {
        g_warning("%s: data is NULL in finalize", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
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
        if (data->atk_table != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_table);
            data->atk_table = NULL;
        }
    }

    g_free(data);
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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return NULL;
    }

    JawObject *jaw_obj = JAW_OBJECT(table);
    if (jaw_obj == NULL) {
        g_warning("%s: jaw_obj is NULL", G_STRFUNC);
        JAW_DEBUG_I("jaw_obj == NULL");
        return NULL;
    }
    TableData *data = jaw_object_get_interface_data(
        jaw_obj, org_GNOME_Accessibility_AtkInterface_INTERFACE_TABLE);
    JAW_CHECK_NULL(data, NULL);
    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);
    jobject atk_table = (*jniEnv)->NewGlobalRef(jniEnv, data->atk_table);
    if (atk_table == NULL) {
        g_warning("%s: atk_table is NULL", G_STRFUNC);
        JAW_DEBUG_I("atk_table == NULL");
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jac = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_table, cachedTableRefAtMethod, (jint)row, (jint)column);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jac using ref_at method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return -1;
    }

    JAW_GET_TABLE(table, -1); // create local JNI reference `jobject atk_table`

    jint jindex =
        (*jniEnv)->CallIntMethod(jniEnv, atk_table, cachedTableGetIndexAtMethod,
                                 (jint)row, (jint)column);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_index_at method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return -1;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return -1;
    }

    JAW_GET_TABLE(table, 0); // create local JNI reference `jobject atk_table`

    jint jcolumn = (*jniEnv)->CallIntMethod(
        jniEnv, atk_table, cachedTableGetColumnAtIndexMethod, (jint)index);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_column_at_index method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return -1;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return -1;
    }

    JAW_GET_TABLE(table, -1); // create local JNI reference `jobject atk_table`

    jint jrow = (*jniEnv)->CallIntMethod(
        jniEnv, atk_table, cachedTableGetRowAtIndexMethod, (jint)index);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_row_at_index method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return -1;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, 0); // create local JNI reference `jobject atk_table`

    jint jcolumns = (*jniEnv)->CallIntMethod(jniEnv, atk_table,
                                             cachedTableGetNColumnsMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_n_columns method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return 0;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, 0); // create local JNI reference `jobject atk_table`

    jint jrows =
        (*jniEnv)->CallIntMethod(jniEnv, atk_table, cachedTableGetNRowsMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_n_rows method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return 0;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, 0); // create local JNI reference `jobject atk_table`

    jint jextent = (*jniEnv)->CallIntMethod(jniEnv, atk_table,
                                            cachedTableGetColumnExtentAtMethod,
                                            (jint)row, (jint)column);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_column_extent_at method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return 0;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("Null argument table passed to function "
                  "jaw_table_get_row_extent_at");
        return 0;
    }

    JAW_GET_TABLE(table, 0); // create local JNI reference `jobject atk_table`

    jint jextent = (*jniEnv)->CallIntMethod(jniEnv, atk_table,
                                            cachedTableGetRowExtentAtMethod,
                                            (jint)row, (jint)column);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_row_extent_at method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return 0;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLE(table,
                  NULL); // create local JNI reference `jobject atk_table`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, atk_table,
                                              cachedTableGetCaptionMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_caption method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);

    // From documentation of the `get_caption` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the obj before returning it.

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLE(table,
                  NULL); // create local JNI reference `jobject atk_table`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_table, cachedTableGetColumnDescriptionMethod, (jint)column);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_column_description method",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->jstrDescription != NULL) {
        if (data->description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                             data->description);
            data->description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
        data->jstrDescription = NULL;
    }

    data->jstrDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->description = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrDescription, NULL);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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

    if (table == NULL) {
        g_warning("Null argument passed table to function "
                  "jaw_table_get_row_description");
        return NULL;
    }

    JAW_GET_TABLE(table,
                  NULL); // create local JNI reference `jobject atk_table`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jstring jstr = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_table, cachedTableGetRowDescriptionMethod, (jint)row);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jstr == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_row_description method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    if (data->jstrDescription != NULL) {
        if (data->description != NULL) {
            (*jniEnv)->ReleaseStringUTFChars(jniEnv, data->jstrDescription,
                                             data->description);
            data->description = NULL;
        }
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->jstrDescription);
        data->jstrDescription = NULL;
    }

    data->jstrDescription = (*jniEnv)->NewGlobalRef(jniEnv, jstr);
    data->description = (gchar *)(*jniEnv)->GetStringUTFChars(
        jniEnv, data->jstrDescription, NULL);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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

    if (table == NULL) {
        g_warning("Null argument table passed to function "
                  "jaw_table_get_column_header");
        return NULL;
    }

    JAW_GET_TABLE(table,
                  NULL); // create local JNI reference `jobject atk_table`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jac = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_table, cachedTableGetColumnHeaderMethod, (jint)column);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_column_header method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
    // From documentation of the `atk_table_get_column_header` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the obj before returning it.

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLE(table,
                  NULL); // create local JNI reference `jobject atk_table`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jac = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_table, cachedTableGetRowHeaderMethod, (jint)row);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_row_header method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
    // From documentation of the `atk_table_get_row_header` in AtkObject:
    // The returned data is owned by the instance (transfer none), so we don't
    // ref the jaw_impl before returning it.

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return NULL;
    }

    JAW_GET_TABLE(table,
                  NULL); // create local JNI reference `jobject atk_table`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jac = (*jniEnv)->CallObjectMethod(jniEnv, atk_table,
                                              cachedTableGetSummaryMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_summary method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, jac);
    // From the documentation of the `get_summary`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full)
    if (jaw_impl != NULL) {
        g_object_ref(G_OBJECT(jaw_impl));
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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

    if (selected == NULL) {
        g_warning("%s: Null argument passed selected to function", G_STRFUNC);
        return 0;
    }
    *selected = NULL;

    if (table == NULL) {
        g_warning("%s: Null argument passed table to function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jintArray jcolumnArray = (jintArray)((*jniEnv)->CallObjectMethod(
        jniEnv, atk_table, cachedTableGetSelectedColumnsMethod));
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jcolumnArray == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_selected_columns method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jsize length = (*jniEnv)->GetArrayLength(jniEnv, jcolumnArray);
    if (length <= 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jint *tmp = (*jniEnv)->GetIntArrayElements(jniEnv, jcolumnArray, NULL);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || tmp == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to read selected columns array", G_STRFUNC);

        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    *selected = g_new(gint, (gsize)length);

    for (jsize i = 0; i < length; i++) {
        (*selected)[i] = (gint)tmp[i];
    }

    (*jniEnv)->ReleaseIntArrayElements(jniEnv, jcolumnArray, tmp, JNI_ABORT);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)length;
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

    if (selected == NULL) {
        g_warning("%s: Null argument passed selected to function", G_STRFUNC);
        return 0;
    }
    *selected = NULL;

    if (table == NULL) {
        g_warning("%s: Null argument passed table to function", G_STRFUNC);
        return 0;
    }

    JAW_GET_TABLE(table, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jintArray jrowArray = (jintArray)((*jniEnv)->CallObjectMethod(
        jniEnv, atk_table, cachedTableGetSelectedRowsMethod));

    if ((*jniEnv)->ExceptionCheck(jniEnv) || jrowArray == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_selected_rows method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jsize length = (*jniEnv)->GetArrayLength(jniEnv, jrowArray);
    if (length <= 0) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    jint *tmp = (*jniEnv)->GetIntArrayElements(jniEnv, jrowArray, NULL);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || tmp == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to read selected rows array", G_STRFUNC);

        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }

    *selected = g_new(gint, (gsize)length);
    for (jsize i = 0; i < length; i++) {
        (*selected)[i] = (gint)tmp[i];
    }

    (*jniEnv)->ReleaseIntArrayElements(jniEnv, jrowArray, tmp, JNI_ABORT);

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (gint)length;
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

    if (table == NULL) {
        g_warning("Null argument table passed to function "
                  "jaw_table_is_column_selected");
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    jboolean jselected = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_table, cachedTableIsColumnSelectedMethod, (jint)column);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call is_column_selected method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    jboolean jselected = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_table, cachedTableIsRowSelectedMethod, (jint)row);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call is_row_selected method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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

    if (table == NULL) {
        g_warning("%s: Null argument table passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_TABLE(table, FALSE);

    jboolean jselected = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_table, cachedTableIsSelectedMethod, (jint)row,
        (jint)column);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call is_selected method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);

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
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, description);
    if (jstr == NULL) {
        g_warning("%s: Failed to create jstr from description", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(
        jniEnv, atk_table, cachedTableSetRowDescriptionMethod, (jint)row, jstr);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call set_row_description method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jstring jstr = (*jniEnv)->NewStringUTF(jniEnv, description);
    if (jstr == NULL) {
        g_warning("%s: Failed to create jstr from description", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_table,
                              cachedTableSetColumnDescriptionMethod,
                              (jint)column, jstr);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call set_column_description method",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    JawObject *jcaption = JAW_OBJECT(caption);
    if (jcaption == NULL) {
        JAW_DEBUG_I("jcaption == NULL");
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass accessible =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/Accessible");
    if (accessible == NULL) {
        g_warning("%s: Failed to find Accessible class", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    if (!(*jniEnv)->IsInstanceOf(jniEnv, jcaption->acc_context, accessible)) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject obj = (*jniEnv)->NewLocalRef(jniEnv, jcaption->acc_context);
    if (obj == NULL) {
        JAW_DEBUG_I("jcaption obj == NULL");
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, cachedTableSetCaptionMethod,
                              obj);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call set_caption method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
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
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    JawObject *jsummary = JAW_OBJECT(summary);
    if (jsummary == NULL) {
        JAW_DEBUG_I("jsummary == NULL");
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jclass accessible =
        (*jniEnv)->FindClass(jniEnv, "javax/accessibility/Accessible");
    if (accessible == NULL) {
        g_warning("%s: Failed to find Accessible class", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    if (!(*jniEnv)->IsInstanceOf(jniEnv, jsummary->acc_context, accessible)) {
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject obj = (*jniEnv)->NewLocalRef(jniEnv, jsummary->acc_context);
    if (obj == NULL) {
        JAW_DEBUG_I("jsummary obj == NULL");
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->CallVoidMethod(jniEnv, atk_table, cachedTableSetSummaryMethod,
                              obj);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call set_summary method", G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_table);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

static gboolean jaw_table_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClass =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkTable");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkTable class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedTableAtkTableClass = (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if (cachedTableAtkTableClass == NULL) {
        g_warning("%s: Failed to create global reference for AtkTable class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedTableCreateAtkTableMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedTableAtkTableClass, "create_atk_table",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkTable;");

    cachedTableRefAtMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedTableAtkTableClass, "ref_at",
                               "(II)Ljavax/accessibility/AccessibleContext;");

    cachedTableGetIndexAtMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_index_at", "(II)I");

    cachedTableGetColumnAtIndexMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_column_at_index", "(I)I");

    cachedTableGetRowAtIndexMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_row_at_index", "(I)I");

    cachedTableGetNColumnsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_n_columns", "()I");

    cachedTableGetNRowsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_n_rows", "()I");

    cachedTableGetColumnExtentAtMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_column_extent_at", "(II)I");

    cachedTableGetRowExtentAtMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_row_extent_at", "(II)I");

    cachedTableGetCaptionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedTableAtkTableClass, "get_caption",
                               "()Ljavax/accessibility/AccessibleContext;");

    cachedTableGetColumnDescriptionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_column_description",
        "(I)Ljava/lang/String;");

    cachedTableGetRowDescriptionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedTableAtkTableClass,
                               "get_row_description", "(I)Ljava/lang/String;");

    cachedTableGetColumnHeaderMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_column_header",
        "(I)Ljavax/accessibility/AccessibleContext;");

    cachedTableGetRowHeaderMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_row_header",
        "(I)Ljavax/accessibility/AccessibleContext;");

    cachedTableGetSummaryMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedTableAtkTableClass, "get_summary",
                               "()Ljavax/accessibility/AccessibleContext;");

    cachedTableGetSelectedColumnsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_selected_columns", "()[I");

    cachedTableGetSelectedRowsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "get_selected_rows", "()[I");

    cachedTableIsColumnSelectedMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "is_column_selected", "(I)Z");

    cachedTableIsRowSelectedMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "is_row_selected", "(I)Z");

    cachedTableIsSelectedMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "is_selected", "(II)Z");

    cachedTableSetRowDescriptionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedTableAtkTableClass,
                               "set_row_description", "(ILjava/lang/String;)V");

    cachedTableSetColumnDescriptionMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedTableAtkTableClass, "set_column_description",
        "(ILjava/lang/String;)V");

    cachedTableSetCaptionMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedTableAtkTableClass, "set_caption",
                               "(Ljavax/accessibility/Accessible;)V");

    cachedTableSetSummaryMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedTableAtkTableClass, "set_summary",
                               "(Ljavax/accessibility/Accessible;)V");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedTableCreateAtkTableMethod == NULL ||
        cachedTableRefAtMethod == NULL || cachedTableGetIndexAtMethod == NULL ||
        cachedTableGetColumnAtIndexMethod == NULL ||
        cachedTableGetRowAtIndexMethod == NULL ||
        cachedTableGetNColumnsMethod == NULL ||
        cachedTableGetNRowsMethod == NULL ||
        cachedTableGetColumnExtentAtMethod == NULL ||
        cachedTableGetRowExtentAtMethod == NULL ||
        cachedTableGetCaptionMethod == NULL ||
        cachedTableGetColumnDescriptionMethod == NULL ||
        cachedTableGetRowDescriptionMethod == NULL ||
        cachedTableGetColumnHeaderMethod == NULL ||
        cachedTableGetRowHeaderMethod == NULL ||
        cachedTableGetSummaryMethod == NULL ||
        cachedTableGetSelectedColumnsMethod == NULL ||
        cachedTableGetSelectedRowsMethod == NULL ||
        cachedTableIsColumnSelectedMethod == NULL ||
        cachedTableIsRowSelectedMethod == NULL ||
        cachedTableIsSelectedMethod == NULL ||
        cachedTableSetRowDescriptionMethod == NULL ||
        cachedTableSetColumnDescriptionMethod == NULL ||
        cachedTableSetCaptionMethod == NULL ||
        cachedTableSetSummaryMethod == NULL) {

        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkTable method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);
    return TRUE;

cleanup_and_fail:
    if (cachedTableAtkTableClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedTableAtkTableClass);
        cachedTableAtkTableClass = NULL;
    }
    cachedTableCreateAtkTableMethod = NULL;
    cachedTableRefAtMethod = NULL;
    cachedTableGetIndexAtMethod = NULL;
    cachedTableGetColumnAtIndexMethod = NULL;
    cachedTableGetRowAtIndexMethod = NULL;
    cachedTableGetNColumnsMethod = NULL;
    cachedTableGetNRowsMethod = NULL;
    cachedTableGetColumnExtentAtMethod = NULL;
    cachedTableGetRowExtentAtMethod = NULL;
    cachedTableGetCaptionMethod = NULL;
    cachedTableGetColumnDescriptionMethod = NULL;
    cachedTableGetRowDescriptionMethod = NULL;
    cachedTableGetColumnHeaderMethod = NULL;
    cachedTableGetRowHeaderMethod = NULL;
    cachedTableGetSummaryMethod = NULL;
    cachedTableGetSelectedColumnsMethod = NULL;
    cachedTableGetSelectedRowsMethod = NULL;
    cachedTableIsColumnSelectedMethod = NULL;
    cachedTableIsRowSelectedMethod = NULL;
    cachedTableIsSelectedMethod = NULL;
    cachedTableSetRowDescriptionMethod = NULL;
    cachedTableSetColumnDescriptionMethod = NULL;
    cachedTableSetCaptionMethod = NULL;
    cachedTableSetSummaryMethod = NULL;
    g_mutex_unlock(&cache_mutex);
    return FALSE;
}

void jaw_table_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedTableAtkTableClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedTableAtkTableClass);
        cachedTableAtkTableClass = NULL;
    }
    cachedTableCreateAtkTableMethod = NULL;
    cachedTableRefAtMethod = NULL;
    cachedTableGetIndexAtMethod = NULL;
    cachedTableGetColumnAtIndexMethod = NULL;
    cachedTableGetRowAtIndexMethod = NULL;
    cachedTableGetNColumnsMethod = NULL;
    cachedTableGetNRowsMethod = NULL;
    cachedTableGetColumnExtentAtMethod = NULL;
    cachedTableGetRowExtentAtMethod = NULL;
    cachedTableGetCaptionMethod = NULL;
    cachedTableGetColumnDescriptionMethod = NULL;
    cachedTableGetRowDescriptionMethod = NULL;
    cachedTableGetColumnHeaderMethod = NULL;
    cachedTableGetRowHeaderMethod = NULL;
    cachedTableGetSummaryMethod = NULL;
    cachedTableGetSelectedColumnsMethod = NULL;
    cachedTableGetSelectedRowsMethod = NULL;
    cachedTableIsColumnSelectedMethod = NULL;
    cachedTableIsRowSelectedMethod = NULL;
    cachedTableIsSelectedMethod = NULL;
    cachedTableSetRowDescriptionMethod = NULL;
    cachedTableSetColumnDescriptionMethod = NULL;
    cachedTableSetCaptionMethod = NULL;
    cachedTableSetSummaryMethod = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}
