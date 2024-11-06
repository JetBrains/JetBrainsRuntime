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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <glib.h>
#include "jawtoplevel.h"
#include "jawutil.h"

static void jaw_toplevel_initialize(AtkObject *accessible, gpointer data);
static void jaw_toplevel_object_finalize(GObject *obj);

/* override AtkObject function */
static const gchar* jaw_toplevel_get_name(AtkObject *obj);
static const gchar* jaw_toplevel_get_description(AtkObject *obj);
static gint jaw_toplevel_get_n_children(AtkObject *obj);
static gint jaw_toplevel_get_index_in_parent(AtkObject *obj);
static AtkRole jaw_toplevel_get_role(AtkObject *obj);
static AtkObject* jaw_toplevel_ref_child(AtkObject *obj, gint i);
static AtkObject* jaw_toplevel_get_parent(AtkObject *obj);

G_DEFINE_TYPE (JawToplevel, jaw_toplevel, ATK_TYPE_OBJECT)

static void
jaw_toplevel_class_init (JawToplevelClass *klass)
{
  JAW_DEBUG_ALL("%p", klass);
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

static void
jaw_toplevel_init (JawToplevel *toplevel)
{
  JAW_DEBUG_ALL("%p", toplevel);
  toplevel->windows = NULL;
}

static void
jaw_toplevel_initialize(AtkObject *accessible, gpointer data)
{
  JAW_DEBUG_ALL("%p, %p", accessible, data);
 ATK_OBJECT_CLASS (jaw_toplevel_parent_class)->initialize(accessible, data);
}

static void
jaw_toplevel_object_finalize (GObject *obj)
{
  JAW_DEBUG_ALL("%p", obj);
  JawToplevel *jaw_toplevel = JAW_TOPLEVEL(obj);
  g_list_free(jaw_toplevel->windows);

  JawToplevelClass *klass = JAW_TOPLEVEL_GET_CLASS(obj);
  G_OBJECT_CLASS(klass)->finalize(obj);
}

static const gchar*
jaw_toplevel_get_name (AtkObject *obj)
{
  JAW_DEBUG_C("%p", obj);
  gint i;
  for(i = 0; i < atk_object_get_n_accessible_children(obj); i++)
  {
    AtkObject* child = atk_object_ref_accessible_child(obj, i);
    const gchar* name = atk_object_get_name(child);
    if (name && strlen(name) > 0)
    {
      g_object_unref(G_OBJECT(child));
      return name;
    }
    g_object_unref(G_OBJECT(child));
  }

  return "Java Application";
}

static const gchar*
jaw_toplevel_get_description (AtkObject *obj)
{
  JAW_DEBUG_C("%p", obj);
  return "Accessible Java application";
}

static gint
jaw_toplevel_get_n_children (AtkObject *obj)
{
  JAW_DEBUG_C("%p", obj);
  JawToplevel* jaw_toplevel = JAW_TOPLEVEL(obj);
  gint n = g_list_length(jaw_toplevel->windows);

  return n;
}

static gint
jaw_toplevel_get_index_in_parent (AtkObject *obj)
{
  JAW_DEBUG_C("%p", obj);
  JawToplevel *jaw_toplevel = JAW_TOPLEVEL(obj);
  gint i = g_list_index(jaw_toplevel->windows, obj);

  return i;
}

static AtkRole
jaw_toplevel_get_role (AtkObject *obj)
{
  JAW_DEBUG_C("%p", obj);
  return ATK_ROLE_APPLICATION;
}

static AtkObject*
jaw_toplevel_ref_child (AtkObject *obj, gint i)
{
  JAW_DEBUG_C("%p, %d", obj, i);
  JawToplevel *jaw_toplevel = JAW_TOPLEVEL(obj);
  AtkObject* child = (AtkObject*)g_list_nth_data(jaw_toplevel->windows, i);

  if (G_OBJECT(child) != NULL)
    g_object_ref(G_OBJECT(child));

  return child;
}

static AtkObject*
jaw_toplevel_get_parent (AtkObject *obj)
{
  JAW_DEBUG_C("%p", obj);
  return NULL;
}

gint
jaw_toplevel_add_window (JawToplevel *toplevel, AtkObject *child)
{
  JAW_DEBUG_C("%p, %p", toplevel, child);
  if (toplevel == NULL)
  {
    return -1;
  }

  if (g_list_index(toplevel->windows, child) != -1)
  {
    return -1;
  }

  toplevel->windows = g_list_append(toplevel->windows, child);

  return g_list_index(toplevel->windows, child);
}

gint jaw_toplevel_remove_window(JawToplevel *toplevel, AtkObject *child)
{
  JAW_DEBUG_C("%p, %p", toplevel, child);
  gint index = -1;

  if (toplevel == NULL)
  {
    return index;
  }

  if ((index = g_list_index(toplevel->windows, child)) == -1)
  {
    return index;
  }

  toplevel->windows = g_list_remove(toplevel->windows, child);

  return index;
}

gint jaw_toplevel_get_child_index(JawToplevel *toplevel, AtkObject *child)
{
  JAW_DEBUG_C("%p, %p", toplevel, child);
  gint i = g_list_index(toplevel->windows, child);
  return i;
}
