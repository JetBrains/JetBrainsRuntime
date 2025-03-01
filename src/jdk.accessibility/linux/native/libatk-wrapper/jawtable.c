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
static gboolean jaw_table_add_row_selection(AtkTable *table, gint row);
static gboolean jaw_table_remove_row_selection(AtkTable *table, gint row);
static gboolean jaw_table_add_column_selection(AtkTable *table, gint column);
static gboolean jaw_table_remove_column_selection(AtkTable *table, gint column);
static void jaw_table_set_row_description(AtkTable *table, gint row,
                                          const gchar *description);
static void jaw_table_set_column_description(AtkTable *table, gint column,
                                             const gchar *description);
static void jaw_table_set_row_header(AtkTable *table, gint row,
                                     AtkObject *header);
static void jaw_table_set_column_header(AtkTable *table, gint column,
                                        AtkObject *header);
static void jaw_table_set_caption(AtkTable *table, AtkObject *caption);
static void jaw_table_set_summary(AtkTable *table, AtkObject *summary);

typedef struct _TableData {
    jobject atk_table;
    gchar *description;
    jstring jstrDescription;
} TableData;

#define JAW_GET_TABLE(table, def_ret)                                          \
    JAW_GET_OBJ_IFACE(table, INTERFACE_TABLE, TableData, atk_table, env,       \
                      atk_table, def_ret)

void jaw_table_interface_init(AtkTableIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", iface, data);
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
    iface->set_column_header = jaw_table_set_column_header;
    iface->set_row_description = jaw_table_set_row_description;
    iface->set_row_header = jaw_table_set_row_header;
    iface->set_summary = jaw_table_set_summary;
    iface->get_selected_columns = jaw_table_get_selected_columns;
    iface->get_selected_rows = jaw_table_get_selected_rows;
    iface->is_column_selected = jaw_table_is_column_selected;
    iface->is_row_selected = jaw_table_is_row_selected;
    iface->is_selected = jaw_table_is_selected;
    iface->add_row_selection = jaw_table_add_row_selection;
    iface->remove_row_selection = jaw_table_remove_row_selection;
    iface->add_column_selection = jaw_table_add_column_selection;
    iface->remove_column_selection = jaw_table_remove_column_selection;
}

gpointer jaw_table_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);
    TableData *data = g_new0(TableData, 1);

    JNIEnv *env = jaw_util_get_jni_env();
    jclass classTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid = (*env)->GetStaticMethodID(
        env, classTable, "createAtkTable",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkTable;");

    jobject jatk_table =
        (*env)->CallStaticObjectMethod(env, classTable, jmid, ac);
    data->atk_table = (*env)->NewGlobalRef(env, jatk_table);

    return data;
}

void jaw_table_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);
    TableData *data = (TableData *)p;
    JNIEnv *env = jaw_util_get_jni_env();

    if (data && data->atk_table) {
        if (data->description != NULL) {
            (*env)->ReleaseStringUTFChars(env, data->jstrDescription,
                                          data->description);
            (*env)->DeleteGlobalRef(env, data->jstrDescription);
            data->jstrDescription = NULL;
            data->description = NULL;
        }

        (*env)->DeleteGlobalRef(env, data->atk_table);
        data->atk_table = NULL;
    }
}

static AtkObject *jaw_table_ref_at(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);
    JawObject *jaw_obj = JAW_OBJECT(table);
    if (!jaw_obj) {
        JAW_DEBUG_I("jaw_obj == NULL");
        return NULL;
    }
    TableData *data = jaw_object_get_interface_data(jaw_obj, INTERFACE_TABLE);
    JNIEnv *env = jaw_util_get_jni_env();
    jobject atk_table = (*env)->NewGlobalRef(env, data->atk_table);
    if (!atk_table) {
        JAW_DEBUG_I("atk_table == NULL");
        return NULL;
    }

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "ref_at",
                            "(II)Ljavax/accessibility/AccessibleContext;");
    jobject jac =
        (*env)->CallObjectMethod(env, atk_table, jmid, (jint)row, (jint)column);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (!jac)
        return NULL;

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(env, jac);

    if (G_OBJECT(jaw_impl) != NULL)
        g_object_ref(G_OBJECT(jaw_impl));

    return ATK_OBJECT(jaw_impl);
}

static gint jaw_table_get_index_at(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_index_at", "(II)I");
    jint jindex =
        (*env)->CallIntMethod(env, atk_table, jmid, (jint)row, (jint)column);
    (*env)->DeleteGlobalRef(env, atk_table);

    return (gint)jindex;
}

static gint jaw_table_get_column_at_index(AtkTable *table, gint index) {
    JAW_DEBUG_C("%p, %d", table, index);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_column_at_index", "(I)I");
    jint jcolumn = (*env)->CallIntMethod(env, atk_table, jmid, (jint)index);
    (*env)->DeleteGlobalRef(env, atk_table);

    return (gint)jcolumn;
}

static gint jaw_table_get_row_at_index(AtkTable *table, gint index) {
    JAW_DEBUG_C("%p, %d", table, index);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_row_at_index", "(I)I");
    jint jrow = (*env)->CallIntMethod(env, atk_table, jmid, (jint)index);
    (*env)->DeleteGlobalRef(env, atk_table);

    return (gint)jrow;
}

static gint jaw_table_get_n_columns(AtkTable *table) {
    JAW_DEBUG_C("%p", table);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_n_columns", "()I");
    jint jcolumns = (*env)->CallIntMethod(env, atk_table, jmid);
    (*env)->DeleteGlobalRef(env, atk_table);

    return (gint)jcolumns;
}

static gint jaw_table_get_n_rows(AtkTable *table) {
    JAW_DEBUG_C("%p", table);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_n_rows", "()I");
    jint jrows = (*env)->CallIntMethod(env, atk_table, jmid);
    (*env)->DeleteGlobalRef(env, atk_table);

    return (gint)jrows;
}

static gint jaw_table_get_column_extent_at(AtkTable *table, gint row,
                                           gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid = (*env)->GetMethodID(env, classAtkTable,
                                         "get_column_extent_at", "(II)I");
    jint jextent =
        (*env)->CallIntMethod(env, atk_table, jmid, (jint)row, (jint)column);
    (*env)->DeleteGlobalRef(env, atk_table);

    return (gint)jextent;
}

static gint jaw_table_get_row_extent_at(AtkTable *table, gint row,
                                        gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_row_extent_at", "(II)I");
    jint jextent =
        (*env)->CallIntMethod(env, atk_table, jmid, (jint)row, (jint)column);
    (*env)->DeleteGlobalRef(env, atk_table);

    return (gint)jextent;
}

static AtkObject *jaw_table_get_caption(AtkTable *table) {
    JAW_DEBUG_C("%p", table);
    JAW_GET_TABLE(table, NULL);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_caption",
                            "()Ljavax/accessibility/AccessibleContext;");

    jobject jac = (*env)->CallObjectMethod(env, atk_table, jmid);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (!jac)
        return NULL;

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(env, jac);

    return ATK_OBJECT(jaw_impl);
}

static const gchar *jaw_table_get_column_description(AtkTable *table,
                                                     gint column) {
    JAW_DEBUG_C("%p, %d", table, column);
    JAW_GET_TABLE(table, NULL);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid = (*env)->GetMethodID(
        env, classAtkTable, "get_column_description", "(I)Ljava/lang/String;");
    jstring jstr = (*env)->CallObjectMethod(env, atk_table, jmid, (jint)column);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (data->description != NULL) {
        (*env)->ReleaseStringUTFChars(env, data->jstrDescription,
                                      data->description);
        (*env)->DeleteGlobalRef(env, data->jstrDescription);
    }

    data->jstrDescription = (*env)->NewGlobalRef(env, jstr);
    data->description =
        (gchar *)(*env)->GetStringUTFChars(env, data->jstrDescription, NULL);

    return data->description;
}

static const gchar *jaw_table_get_row_description(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);
    JAW_GET_TABLE(table, NULL);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid = (*env)->GetMethodID(
        env, classAtkTable, "get_row_description", "(I)Ljava/lang/String;");
    jstring jstr = (*env)->CallObjectMethod(env, atk_table, jmid, (jint)row);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (data->description != NULL) {
        (*env)->ReleaseStringUTFChars(env, data->jstrDescription,
                                      data->description);
        (*env)->DeleteGlobalRef(env, data->jstrDescription);
    }

    data->jstrDescription = (*env)->NewGlobalRef(env, jstr);
    data->description =
        (gchar *)(*env)->GetStringUTFChars(env, data->jstrDescription, NULL);

    return data->description;
}

static AtkObject *jaw_table_get_column_header(AtkTable *table, gint column) {
    JAW_DEBUG_C("%p, %d", table, column);
    JAW_GET_TABLE(table, NULL);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_column_header",
                            "(I)Ljavax/accessibility/AccessibleContext;");
    jobject jac = (*env)->CallObjectMethod(env, atk_table, jmid, (jint)column);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (!jac)
        return NULL;

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(env, jac);

    return ATK_OBJECT(jaw_impl);
}

static AtkObject *jaw_table_get_row_header(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);
    JAW_GET_TABLE(table, NULL);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_row_header",
                            "(I)Ljavax/accessibility/AccessibleContext;");
    jobject jac = (*env)->CallObjectMethod(env, atk_table, jmid, (jint)row);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (!jac)
        return NULL;

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(env, jac);

    return ATK_OBJECT(jaw_impl);
}

static AtkObject *jaw_table_get_summary(AtkTable *table) {
    JAW_DEBUG_C("%p", table);
    JAW_GET_TABLE(table, NULL);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_summary",
                            "()Ljavax/accessibility/AccessibleContext;");
    jobject jac = (*env)->CallObjectMethod(env, atk_table, jmid);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (!jac)
        return NULL;

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(env, jac);

    return ATK_OBJECT(jaw_impl);
}

static gint jaw_table_get_selected_columns(AtkTable *table, gint **selected) {
    JAW_DEBUG_C("%p, %p", table, selected);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_selected_columns", "()[I");
    jintArray jcolumnArray = (*env)->CallObjectMethod(env, atk_table, jmid);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (!jcolumnArray)
        return 0;

    jsize length = (*env)->GetArrayLength(env, jcolumnArray);
    jint *jcolumns = (*env)->GetIntArrayElements(env, jcolumnArray, NULL);
    gint *columns = g_new(gint, length);

    gint i;
    for (i = 0; i < length; i++) {
        columns[i] = (gint)jcolumns[i];
    }

    (*env)->ReleaseIntArrayElements(env, jcolumnArray, jcolumns, JNI_ABORT);

    return (gint)length;
}

static gint jaw_table_get_selected_rows(AtkTable *table, gint **selected) {
    JAW_DEBUG_C("%p, %p", table, selected);
    JAW_GET_TABLE(table, 0);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "get_selected_rows", "()[I");
    jintArray jrowArray = (*env)->CallObjectMethod(env, atk_table, jmid);
    (*env)->DeleteGlobalRef(env, atk_table);

    if (!jrowArray)
        return 0;

    jsize length = (*env)->GetArrayLength(env, jrowArray);
    jint *jrows = (*env)->GetIntArrayElements(env, jrowArray, NULL);
    gint *rows = g_new(gint, length);

    gint i;
    for (i = 0; i < length; i++) {
        rows[i] = (gint)jrows[i];
    }

    (*env)->ReleaseIntArrayElements(env, jrowArray, jrows, JNI_ABORT);

    return (gint)length;
}

static gboolean jaw_table_is_column_selected(AtkTable *table, gint column) {
    JAW_DEBUG_C("%p, %d", table, column);
    JAW_GET_TABLE(table, FALSE);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "is_column_selected", "(I)Z");
    jboolean jselected =
        (*env)->CallBooleanMethod(env, atk_table, jmid, (jint)column);
    (*env)->DeleteGlobalRef(env, atk_table);
    return jselected;
}

static gboolean jaw_table_is_row_selected(AtkTable *table, gint row) {
    JAW_DEBUG_C("%p, %d", table, row);
    JAW_GET_TABLE(table, FALSE);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "is_row_selected", "(I)Z");
    jboolean jselected =
        (*env)->CallBooleanMethod(env, atk_table, jmid, (jint)row);
    (*env)->DeleteGlobalRef(env, atk_table);
    return jselected;
}

static gboolean jaw_table_is_selected(AtkTable *table, gint row, gint column) {
    JAW_DEBUG_C("%p, %d, %d", table, row, column);
    JAW_GET_TABLE(table, FALSE);

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid =
        (*env)->GetMethodID(env, classAtkTable, "is_selected", "(II)Z");
    jboolean jselected = (*env)->CallBooleanMethod(env, atk_table, jmid,
                                                   (jint)row, (jint)column);
    (*env)->DeleteGlobalRef(env, atk_table);

    return jselected;
}

static gboolean jaw_table_add_row_selection(AtkTable *table, gint row) {
    g_warning(
        "It is impossible to add row selection on AccessibleTable Java Object");
    return FALSE;
}

static gboolean jaw_table_remove_row_selection(AtkTable *table, gint row) {
    g_warning("It is impossible to remove row selection on AccessibleTable "
              "Java Object");
    return FALSE;
}

static gboolean jaw_table_add_column_selection(AtkTable *table, gint column) {
    g_warning("It is impossible to add column selection on AccessibleTable "
              "Java Object");
    return FALSE;
}

static gboolean jaw_table_remove_column_selection(AtkTable *table,
                                                  gint column) {
    g_warning("It is impossible to remove column selection on AccessibleTable "
              "Java Object");
    return FALSE;
}

static void jaw_table_set_row_description(AtkTable *table, gint row,
                                          const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", table, row, description);
    JAW_GET_TABLE(table, );

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid = (*env)->GetMethodID(
        env, classAtkTable, "setRowDescription", "(ILjava/lang/String;)V");
    jstring jstr = (*env)->NewStringUTF(env, description);
    (*env)->CallVoidMethod(env, atk_table, jmid, (jint)row, jstr);
    (*env)->DeleteGlobalRef(env, atk_table);
}

static void jaw_table_set_column_description(AtkTable *table, gint column,
                                             const gchar *description) {
    JAW_DEBUG_C("%p, %d, %s", table, column, description);
    JAW_GET_TABLE(table, );

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid = (*env)->GetMethodID(
        env, classAtkTable, "setColumnDescription", "(ILjava/lang/String;)V");
    jstring jstr = (*env)->NewStringUTF(env, description);
    (*env)->CallVoidMethod(env, atk_table, jmid, (jint)column, jstr);
    (*env)->DeleteGlobalRef(env, atk_table);
}

static void jaw_table_set_row_header(AtkTable *table, gint row,
                                     AtkObject *header) {
    g_warning("It is impossible to set a single row header on AccessibleTable "
              "Java Object");
}

static void jaw_table_set_column_header(AtkTable *table, gint column,
                                        AtkObject *header) {
    g_warning("It is impossible to set a single column header on "
              "AccessibleTable Java Object");
}

static void jaw_table_set_caption(AtkTable *table, AtkObject *caption) {
    JAW_DEBUG_C("%p, %p", table, caption);
    JAW_GET_TABLE(table, );

    JawObject *jcaption = JAW_OBJECT(caption);
    if (!jcaption) {
        JAW_DEBUG_I("jcaption == NULL");
        (*env)->DeleteGlobalRef(env, atk_table);
        return;
    }
    jclass accessible =
        (*env)->FindClass(env, "javax/accessibility/Accessible");
    if (!((*env)->IsInstanceOf(env, jcaption->acc_context, accessible))) {
        (*env)->DeleteGlobalRef(env, atk_table);
        return;
    }
    jobject obj = (*env)->NewGlobalRef(env, jcaption->acc_context);
    if (!obj) {
        JAW_DEBUG_I("jcaption obj == NULL");
        (*env)->DeleteGlobalRef(env, atk_table);
        return;
    }
    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid = (*env)->GetMethodID(env, classAtkTable, "setCaption",
                                         "(Ljavax/accessibility/Accessible;)V");
    (*env)->CallVoidMethod(env, atk_table, jmid, obj);
    (*env)->DeleteGlobalRef(env, obj);
    (*env)->DeleteGlobalRef(env, atk_table);
}

static void jaw_table_set_summary(AtkTable *table, AtkObject *summary) {
    JAW_DEBUG_C("%p, %p", table, summary);
    JAW_GET_TABLE(table, );

    JawObject *jsummary = JAW_OBJECT(summary);
    if (!jsummary) {
        JAW_DEBUG_I("jsummary == NULL");
        (*env)->DeleteGlobalRef(env, atk_table);
        return;
    }
    jclass accessible =
        (*env)->FindClass(env, "javax/accessibility/Accessible");
    if (!((*env)->IsInstanceOf(env, jsummary->acc_context, accessible))) {
        (*env)->DeleteGlobalRef(env, atk_table);
        return;
    }
    jobject obj = (*env)->NewGlobalRef(env, jsummary->acc_context);
    if (!obj) {
        JAW_DEBUG_I("jsummary obj == NULL");
        (*env)->DeleteGlobalRef(env, atk_table);
        return;
    }

    jclass classAtkTable =
        (*env)->FindClass(env, "org/GNOME/Accessibility/AtkTable");
    jmethodID jmid = (*env)->GetMethodID(env, classAtkTable, "setSummary",
                                         "(Ljavax/accessibility/Accessible;)V");
    (*env)->CallVoidMethod(env, atk_table, jmid, obj);
    (*env)->DeleteGlobalRef(env, obj);
    (*env)->DeleteGlobalRef(env, atk_table);
}
