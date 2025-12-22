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

#ifndef _JAW_HYPERLINK_H_
#define _JAW_HYPERLINK_H_

#include <atk/atk.h>
#include <jni.h>

G_BEGIN_DECLS

#define JAW_TYPE_HYPERLINK (jaw_hyperlink_get_type())
#define JAW_HYPERLINK(obj)                                                     \
    (G_TYPE_CHECK_INSTANCE_CAST((obj), JAW_TYPE_HYPERLINK, JawHyperlink))
#define JAW_HYPERLINK_CLASS(klass)                                             \
    (G_TYPE_CHECK_CLASS_CAST((klass), JAW_TYPE_HYPERLINK, JawHyperlinkClass))
#define JAW_IS_HYPERLINK(obj)                                                  \
    (G_TYPE_CHECK_INSTANCE_TYPE((obj), JAW_TYPE_HYPERLINK))
#define JAW_IS_HYPERLINK_CLASS(klass)                                          \
    (G_TYPE_CHECK_CLASS_TYPE((klass), JAW_TYPE_HYPERLINK))
#define JAW_HYPERLINK_GET_CLASS(obj)                                           \
    (G_TYPE_INSTANCE_GET_CLASS((obj), JAW_TYPE_HYPERLINK, JawHyperlinkClass))

typedef struct _JawHyperlink JawHyperlink;
typedef struct _JawHyperlinkClass JawHyperlinkClass;

struct _JawHyperlink {
    AtkHyperlink parent;
    jobject jhyperlink;

    jstring jstrUri;
    gchar *uri;
    GMutex mutex;
};

GType jaw_hyperlink_get_type(void);

struct _JawHyperlinkClass {
    AtkHyperlinkClass parent_class;
};

JawHyperlink *jaw_hyperlink_new(jobject jhyperlink);

G_END_DECLS

#endif
