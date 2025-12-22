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

#ifndef _JAW_TOPLEVEL_H_
#define _JAW_TOPLEVEL_H_

#include <atk/atk.h>

G_BEGIN_DECLS

#define JAW_TYPE_TOPLEVEL (jaw_toplevel_get_type())
#define JAW_TOPLEVEL(obj)                                                      \
    (G_TYPE_CHECK_INSTANCE_CAST((obj), JAW_TYPE_TOPLEVEL, JawToplevel))
#define JAW_TOPLEVEL_CLASS(klass)                                              \
    (G_TYPE_CHECK_CLASS_CAST((klass), JAW_TYPE_TOPLEVEL, JawToplevelClass))
#define JAW_IS_TOPLEVEL(obj)                                                   \
    (G_TYPE_CHECK_INSTANCE_TYPE((obj), JAW_TYPE_TOPLEVEL))
#define JAW_IS_TOPLEVEL_CLASS(klass)                                           \
    (G_TYPE_CHECK_CLASS_TYPE((klass), JAW_TYPE_TOPLEVEL))
#define JAW_TOPLEVEL_GET_CLASS(obj)                                            \
    (G_TYPE_INSTANCE_GET_CLASS((obj), JAW_TYPE_TOPLEVEL, JawToplevelClass))

typedef struct _JawToplevel JawToplevel;
typedef struct _JawToplevelClass JawToplevelClass;

struct _JawToplevel {
    AtkObject parent;
    GList *windows;
    GMutex mutex;
};

GType jaw_toplevel_get_type(void);

struct _JawToplevelClass {
    AtkObjectClass parent_class;
};

gint jaw_toplevel_add_window(JawToplevel *, AtkObject *);
gint jaw_toplevel_remove_window(JawToplevel *, AtkObject *);
gint jaw_toplevel_get_child_index(JawToplevel *, AtkObject *);

G_END_DECLS

#endif
