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

#ifndef _JAW_OBJECT_H_
#define _JAW_OBJECT_H_

#include <atk/atk.h>
#include <jni.h>

G_BEGIN_DECLS

#define JAW_TYPE_OBJECT (jaw_object_get_type())
#define JAW_OBJECT(obj)                                                        \
    (G_TYPE_CHECK_INSTANCE_CAST((obj), JAW_TYPE_OBJECT, JawObject))
#define JAW_OBJECT_CLASS(klass)                                                \
    (G_TYPE_CHECK_CLASS_CAST((klass), JAW_TYPE_OBJECT, JawObjectClass))
#define JAW_IS_OBJECT(obj) (G_TYPE_CHECK_INSTANCE_TYPE((obj), JAW_TYPE_OBJECT))
#define JAW_IS_OBJECT_CLASS(klass)                                             \
    (G_TYPE_CHECK_CLASS_TYPE((klass), JAW_TYPE_OBJECT))
#define JAW_OBJECT_GET_CLASS(obj)                                              \
    (G_TYPE_INSTANCE_GET_CLASS((obj), JAW_TYPE_OBJECT, JawObjectClass))

typedef struct _JawObject JawObject;
typedef struct _JawObjectClass JawObjectClass;

/**
 * JawObject:
 * A base structure wrapping an AtkObject.
 **/
struct _JawObject {
    AtkObject parent;

    jobject acc_context;
    jstring jstrName;
    jstring jstrDescription;
    jstring jstrLocale;
    const gchar *locale;
    AtkStateSet *state_set;

    GHashTable *storedData;
    GMutex mutex;
};

GType jaw_object_get_type(void);

struct _JawObjectClass {
    AtkObjectClass parent_class;

    gpointer (*get_interface_data)(JawObject *, guint);
};

gpointer jaw_object_get_interface_data(JawObject *, guint);

G_END_DECLS

#endif
