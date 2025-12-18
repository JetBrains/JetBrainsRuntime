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

#include "jawtoplevel.h"
#include "jawutil.h"
#include <glib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void jaw_toplevel_initialize(AtkObject *accessible, gpointer data);
static void jaw_toplevel_object_finalize(GObject *obj);

static const gchar *jaw_toplevel_get_name(AtkObject *obj);
static const gchar *jaw_toplevel_get_description(AtkObject *obj);
static gint jaw_toplevel_get_n_children(AtkObject *obj);
static gint jaw_toplevel_get_index_in_parent(AtkObject *obj);
static AtkRole jaw_toplevel_get_role(AtkObject *obj);
static AtkObject *jaw_toplevel_ref_child(AtkObject *obj, gint i);
static AtkObject *jaw_toplevel_get_parent(AtkObject *obj);

G_DEFINE_TYPE(JawToplevel, jaw_toplevel, ATK_TYPE_OBJECT)

static void jaw_toplevel_class_init(JawToplevelClass *klass) {
    JAW_DEBUG_ALL("%p", klass);

    if (klass == NULL) {
        g_warning("%s: Null argument klass passed to the function", G_STRFUNC);
        return;
    }

    AtkObjectClass *atk_object_class = ATK_OBJECT_CLASS(klass);
    GObjectClass *g_object_class = G_OBJECT_CLASS(klass);

    atk_object_class->initialize = jaw_toplevel_initialize;
    atk_object_class->get_name = jaw_toplevel_get_name;
    atk_object_class->get_description = jaw_toplevel_get_description;
    atk_object_class->get_n_children = jaw_toplevel_get_n_children;
    atk_object_class->get_index_in_parent = jaw_toplevel_get_index_in_parent;
    atk_object_class->get_role = jaw_toplevel_get_role;
    atk_object_class->ref_child = jaw_toplevel_ref_child;
    atk_object_class->get_parent = jaw_toplevel_get_parent;

    g_object_class->finalize = jaw_toplevel_object_finalize;
}

static void jaw_toplevel_init(JawToplevel *toplevel) {
    JAW_DEBUG_ALL("%p", toplevel);

    if (toplevel == NULL) {
        g_warning("%s: Null argument toplevel passed to the function", G_STRFUNC);
        return;
    }

    toplevel->windows = NULL;
}

static void jaw_toplevel_initialize(AtkObject *accessible, gpointer data) {
    JAW_DEBUG_ALL("%p, %p", accessible, data);

    if (accessible == NULL) {
        g_warning("%s: Null argument accessible passed to the function", G_STRFUNC);
        return;
    }

    ATK_OBJECT_CLASS(jaw_toplevel_parent_class)->initialize(accessible, data);
}

static void jaw_toplevel_object_finalize(GObject *obj) {
    JAW_DEBUG_ALL("%p", obj);

    if (obj == NULL) {
        g_warning("%s: Null argument obj passed to the function", G_STRFUNC);
        return;
    }

    JawToplevel *jaw_toplevel = JAW_TOPLEVEL(obj);

    if (jaw_toplevel != NULL && jaw_toplevel->windows != NULL) {
        g_list_free(jaw_toplevel->windows);
        jaw_toplevel->windows = NULL;
    }

    G_OBJECT_CLASS(jaw_toplevel_parent_class)->finalize(obj);
}

/**
 * jaw_toplevel_get_name:
 * @obj: an #AtkObject
 *
 * Gets the accessible name of the obj.
 *
 * Returns: a character string representing the accessible name of the object:
 * the name of the first named child of the object, and if no such child exists,
 * returns 'Java Application'.
 **/
static const gchar *jaw_toplevel_get_name(AtkObject *obj) {
    JAW_DEBUG_C("%p", obj);

    if (obj == NULL) {
        g_warning("%s: Null argument obj passed to the function", G_STRFUNC);
        return NULL;
    }

    gint n_accessible_children = atk_object_get_n_accessible_children(obj);
    for (gint i = 0; i < n_accessible_children; i++) {
        // The caller of the method `atk_object_ref_accessible_child` takes
        // ownership of the returned data, and is responsible for freeing it, so
        // child must be freed.
        AtkObject *child = atk_object_ref_accessible_child(obj, i);
        if (child != NULL) {
            const gchar *name = atk_object_get_name(child);
            if (name && strlen(name) > 0) {
                g_object_unref(G_OBJECT(child));
                return name;
            }
            g_object_unref(G_OBJECT(child));
        }
    }

    return "Java Application";
}

/**
 * jaw_toplevel_get_description:
 * @accessible: an #AtkObject
 *
 * Gets the accessible description of the accessible.
 *
 * Returns: a character string representing the accessible description
 * of the accessible.
 *
 **/
static const gchar *jaw_toplevel_get_description(AtkObject *obj) {
    JAW_DEBUG_C("%p", obj);

    if (obj == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return NULL;
    }

    return "Accessible Java application";
}

/**
 * jaw_toplevel_get_n_children:
 * @obj: an #AtkObject
 *
 * Gets the number of accessible children of the obj.
 *
 * Returns: an integer representing the number of accessible children
 * of the obj.
 **/
static gint jaw_toplevel_get_n_children(AtkObject *obj) {
    JAW_DEBUG_C("%p", obj);

    if (obj == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JawToplevel *jaw_toplevel = JAW_TOPLEVEL(obj);
    JAW_CHECK_NULL(jaw_toplevel, 0);
    JAW_CHECK_NULL(jaw_toplevel->windows, 0);
    gint n = g_list_length(jaw_toplevel->windows);

    return n;
}

/**
 * jaw_toplevel_get_index_in_parent:
 * @obj: an #AtkObject
 *
 * Gets the 0-based index of this obj in its parent; returns -1 if the
 * obj does not have an accessible parent.
 *
 * Returns: an integer which is the index of the obj in its parent
 **/
static gint jaw_toplevel_get_index_in_parent(AtkObject *obj) {
    JAW_DEBUG_C("%p", obj);

    if (obj == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return -1;
    }

    // toplevel object does not have parent
    return -1;
}

/**
 * jaw_toplevel_get_role:
 * @obj: an #AtkObject
 *
 * Gets the role of the accessible.
 *
 * Returns: an #AtkRole which is the role of the obj (`ATK_ROLE_APPLICATION` for
 * toplevel object)
 **/
static AtkRole jaw_toplevel_get_role(AtkObject *obj) {
    JAW_DEBUG_C("%p", obj);

    if (obj == NULL) {
        g_warning("%s: Null argument obj passed to the function", G_STRFUNC);
        return ATK_ROLE_INVALID;
    }

    return ATK_ROLE_APPLICATION;
}

/**
 * atk_object_ref_accessible_child:
 * @obj: an #AtkObject
 * @i: a gint representing the position of the child, starting from 0
 *
 * Gets a reference to the specified accessible child of the object.
 * The accessible children are 0-based so the first accessible child is
 * at index 0, the second at index 1 and so on.
 *
 * Returns: (transfer full): an #AtkObject representing the specified
 * accessible child of the obj.
 **/
static AtkObject *jaw_toplevel_ref_child(AtkObject *obj, gint i) {
    JAW_DEBUG_C("%p, %d", obj, i);

    if (obj == NULL) {
        g_warning("%s: Null argument obj passed to the function", G_STRFUNC);
        return NULL;
    }

    JawToplevel *jaw_toplevel = JAW_TOPLEVEL(obj);
    JAW_CHECK_NULL(jaw_toplevel, NULL);
    JAW_CHECK_NULL(jaw_toplevel->windows, NULL);
    AtkObject *child = (AtkObject *)g_list_nth_data(jaw_toplevel->windows, i);

    // The caller of the `jaw_toplevel_ref_child` will be responsible for
    // freeing the 'child' (because of transfer full annotation)
    if (child != NULL) {
        g_object_ref(G_OBJECT(child));
    }

    return child;
}

/**
 * atk_object_get_parent:
 * @obj: an #AtkObject
 *
 * Gets the accessible parent of the accessible.
 *
 * Returns: (transfer none): an #AtkObject representing the accessible
 * parent of the obj
 **/
static AtkObject *jaw_toplevel_get_parent(AtkObject *obj) {
    JAW_DEBUG_C("%p", obj);

    if (obj == NULL) {
        g_warning("%s: Null argument obj passed to the function", G_STRFUNC);
    }

    return NULL;
}

// JawToplevel methods

gint jaw_toplevel_add_window(JawToplevel *toplevel, AtkObject *child) {
    JAW_DEBUG_C("%p, %p", toplevel, child);

    if (toplevel == NULL || child == NULL) {
        g_warning("%s: invalid argument(s) : toplevel=%p, child=%p", G_STRFUNC, toplevel, child);
        return -1;
    }

    if (toplevel->windows != NULL &&
        g_list_index(toplevel->windows, child) != -1) {
        return -1;
    }

    toplevel->windows = g_list_append(toplevel->windows, child);

    return g_list_index(toplevel->windows, child);
}

gint jaw_toplevel_remove_window(JawToplevel *toplevel, AtkObject *child) {
    JAW_DEBUG_C("%p, %p", toplevel, child);

    if (toplevel == NULL || child == NULL) {
        g_warning("%s: invalid argument(s) : toplevel=%p, child=%p", G_STRFUNC, toplevel, child);
        return -1;
    }

    if (toplevel->windows == NULL) {
        g_warning("%s: Cannot remove window %p: the toplevel window list is NULL "
                          "(not initialized or already cleared)",
                          G_STRFUNC, child);
        return -1;
    }

    gint index = (g_list_index(toplevel->windows, child));
    if (index == -1) {
        g_warning("%s: Cannot remove window %p: it is not present in the "
                          "toplevel window list",
                          G_STRFUNC, child);
        return -1;
    }

    toplevel->windows = g_list_remove(toplevel->windows, child);

    return index;
}

gint jaw_toplevel_get_child_index(JawToplevel *toplevel, AtkObject *child) {
    JAW_DEBUG_C("%p, %p", toplevel, child);

    if (toplevel == NULL || child == NULL) {
        g_warning("%s: invalid argument(s) : toplevel=%p, child=%p", G_STRFUNC, toplevel, child);
        return -1;
    }

    JAW_CHECK_NULL(toplevel->windows, -1);

    gint i = g_list_index(toplevel->windows, child);
    return i;
}
