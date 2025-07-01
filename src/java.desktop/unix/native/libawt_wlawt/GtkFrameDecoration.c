/*
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include <stdlib.h>
#include <string.h>
#include <jni.h>
#include <jni_util.h>
#include <Trace.h>
#include <assert.h>
#include <stdbool.h>

#include <gtk/gtk.h>

#include "jni_util.h"
#include "JNIUtilities.h"
#include "WLToolkit.h"

#include "sun_awt_wl_GtkFrameDecoration.h"

enum header_element {
	HEADER_NONE,
	HEADER_FULL, /* entire header bar */
	HEADER_TITLE, /* label */
	HEADER_MIN,
	HEADER_MAX,
	HEADER_CLOSE,
};

typedef struct rectangle_t {
    int x, y;
    int width, height;
} rectangle_t;

typedef struct gtk_frame_decoration_t {
    GtkWidget *window;
    GtkWidget *header;
    jboolean showMinimize;
    jboolean showMaximize;
    jboolean isActive;
    jboolean isMaximized;
    rectangle_t minimizeButtonBounds;
    rectangle_t maximizeButtonBounds;
    rectangle_t closeButtonBounds;
} gtk_frame_decoration_t;

static void draw_title_bar(gtk_frame_decoration_t* decor, cairo_surface_t * surface, cairo_t *cr, int width, int height, int scale, const char *title);

JNIEXPORT jlong JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeCreateDecoration
        (JNIEnv *env, jobject obj, jboolean showMinimize, jboolean showMaximize)
{
    gtk_frame_decoration_t *d = calloc(1, sizeof(gtk_frame_decoration_t));
    CHECK_NULL_THROW_OOME_RETURN(env, d, "Failed to allocate GtkFrameDeocration", 0);

    d->showMinimize = showMinimize;
    d->showMaximize = showMaximize;
	d->window = gtk_offscreen_window_new();
	d->header = gtk_header_bar_new();

	g_object_set(d->header,
		     "title", "Default Title",
		     "has-subtitle", FALSE,
		     "show-close-button", TRUE,
		     NULL);

	GtkStyleContext *context_hdr;
	context_hdr = gtk_widget_get_style_context(d->header);
	gtk_style_context_add_class(context_hdr, GTK_STYLE_CLASS_TITLEBAR);
	gtk_style_context_add_class(context_hdr, "default-decoration");
	gtk_window_set_titlebar(GTK_WINDOW(d->window), d->header);
	gtk_header_bar_set_show_close_button(GTK_HEADER_BAR(d->header), TRUE);
	gtk_window_set_resizable(GTK_WINDOW(d->window), TRUE);

    return ptr_to_jlong(d);
}


JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativePaintTitleBar
        (JNIEnv *env, jobject obj, jlong ptr, jintArray dest, jint width, jint height, jint scale, jstring title)
{
    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);

    unsigned char *buffer = (*env)->GetPrimitiveArrayCritical(env, dest, 0);
    if (!buffer) {
        JNU_ThrowOutOfMemoryError(env, "Could not get image buffer");
        return;
    }

    //gdk_threads_enter();
    cairo_surface_t *surface = cairo_image_surface_create_for_data(
        buffer, CAIRO_FORMAT_ARGB32,
        width, height,
        cairo_format_stride_for_width(CAIRO_FORMAT_ARGB32, width));

	cairo_surface_set_device_scale(surface, scale, scale);

	cairo_t *cr = cairo_create(surface);

    jboolean isCopy = JNI_FALSE;
    const char *title_c_str = JNU_GetStringPlatformChars(env, title, &isCopy);
    if (!title_c_str)
        return;
    draw_title_bar(decor, surface, cr, width, height, scale, title_c_str);

    if (isCopy) {
        JNU_ReleaseStringPlatformChars(env, title, title_c_str);
    }

    // Make sure pixels have been flush into the underlying buffer
    cairo_surface_flush(surface);
    //gdk_threads_leave();
    (*env)->ReleasePrimitiveArrayCritical(env, dest, buffer, 0);

    cairo_destroy(cr);
    cairo_surface_destroy(surface);
}

struct header_element_data {
	const char *name;
	enum header_element type;
	/* pointer to button or NULL if not found*/
	GtkWidget *widget;
	GtkStateFlags state;
};

static void
find_widget_by_name(GtkWidget *widget, void *data)
{
	if (GTK_IS_WIDGET(widget)) {
		char *style_ctx = gtk_style_context_to_string(
					  gtk_widget_get_style_context(widget),
					  GTK_STYLE_CONTEXT_PRINT_SHOW_STYLE);
		if (strstr(style_ctx, ((struct header_element_data *)data)->name)) {
			((struct header_element_data *)data)->widget = widget;
			free(style_ctx);
			return;
		}
		free(style_ctx);
	}

	if (GTK_IS_CONTAINER(widget)) {
		/* recursively traverse container */
		gtk_container_forall(GTK_CONTAINER(widget), &find_widget_by_name, data);
	}
}

static struct header_element_data
find_widget_by_type(GtkWidget *widget, enum header_element type)
{
	char* name = NULL;
	switch (type) {
	case HEADER_FULL:
		name = "headerbar.titlebar:";
		break;
	case HEADER_TITLE:
		name = "label.title:";
		break;
	case HEADER_MIN:
		name = ".minimize";
		break;
	case HEADER_MAX:
		name = ".maximize";
		break;
	case HEADER_CLOSE:
		name = ".close";
		break;
	default:
		break;
	}

	struct header_element_data data = {.name = name, .type = type, .widget = NULL};
	find_widget_by_name(widget, &data);
	return data;
}

static void
draw_header_background(gtk_frame_decoration_t* decor, cairo_t *cr)
{
	GtkAllocation allocation;
	gtk_widget_get_allocation(GTK_WIDGET(decor->header), &allocation);
	GtkStyleContext* style = gtk_widget_get_style_context(decor->header);
	gtk_render_background(style, cr, allocation.x, allocation.y, allocation.width, allocation.height);
}

struct widget_cb_data_t {
    const char *name;
    GtkWidget *widget;
};

static void
widget_by_name_cb(GtkWidget *widget, void *payload)
{
    struct widget_cb_data_t *data = payload;
	if (GTK_IS_WIDGET(widget)) {
		char *style_ctx = gtk_style_context_to_string( gtk_widget_get_style_context(widget), GTK_STYLE_CONTEXT_PRINT_SHOW_STYLE);
		if (strstr(style_ctx, data->name)) {
			data->widget = widget;
			free(style_ctx);
			return;
		}
		free(style_ctx);
	}

	if (GTK_IS_CONTAINER(widget)) {
		gtk_container_forall(GTK_CONTAINER(widget), &widget_by_name_cb, data);
	}
}

static GtkWidget*
widget_by_name(GtkWidget *widget, const char *name)
{
    struct widget_cb_data_t data = { .name = name, .widget = NULL };
    widget_by_name_cb(widget, &data);
    return data.widget;
}

static void
draw_header_title(gtk_frame_decoration_t* decor, cairo_surface_t * surface)
{
    GtkWidget *label = widget_by_name(decor->header, "label.title:");
    GtkAllocation allocation;
    gtk_widget_get_allocation(label, &allocation);
    cairo_surface_t *label_surface = cairo_surface_create_for_rectangle(
        surface,
        allocation.x, allocation.y, allocation.width, allocation.height);
    cairo_t *cr = cairo_create(label_surface);
    gtk_widget_size_allocate(label, &allocation); // TODO: why allocate in the same place?
    gtk_widget_draw(label, cr);
    cairo_destroy(cr);
    cairo_surface_destroy(label_surface);
}

static void
draw_header_button(gtk_frame_decoration_t* decor, cairo_surface_t * surface, cairo_t *cr,
                   enum header_element button_type)
{
	struct header_element_data elem;
	GtkWidget *button;
	GtkStyleContext* button_style;
	GtkStateFlags style_state;

	GtkAllocation allocation;

	gchar *icon_name;
	int scale;
	GtkWidget *icon_widget;
	GtkAllocation allocation_icon;
	GtkIconInfo* icon_info;

	double sx, sy;

	gint icon_width, icon_height;

	GdkPixbuf* icon_pixbuf;
	cairo_surface_t* icon_surface;

	gint width = 0, height = 0;

	gint left = 0, top = 0, right = 0, bottom = 0;
	GtkBorder border;

	GtkBorder padding;

	elem = find_widget_by_type(decor->header, button_type);
	button = elem.widget;
	if (!button)
		return;
	button_style = gtk_widget_get_style_context(button);
	style_state = elem.state;

	/* change style based on window state and focus */
	if (FALSE /*!(window_state & LIBDECOR_WINDOW_STATE_ACTIVE)*/) {
		style_state |= GTK_STATE_FLAG_BACKDROP;
	}
	if (FALSE/*frame_gtk->hdr_focus.widget == button*/) {
		style_state |= GTK_STATE_FLAG_PRELIGHT;
	//	if (frame_gtk->hdr_focus.state & GTK_STATE_FLAG_ACTIVE) {
			style_state |= GTK_STATE_FLAG_ACTIVE;
	//	}
	}

	/* background */
	gtk_widget_get_clip(button, &allocation);

	gtk_style_context_save(button_style);
	gtk_style_context_set_state(button_style, style_state);
	gtk_render_background(button_style, cr,
			      allocation.x, allocation.y,
			      allocation.width, allocation.height);
	gtk_render_frame(button_style, cr,
			 allocation.x, allocation.y,
			 allocation.width, allocation.height);
	gtk_style_context_restore(button_style);

	/* symbol */
	switch (button_type) {
	case HEADER_MIN:
		icon_name = "window-minimize-symbolic";
		break;
	case HEADER_MAX:
		icon_name = decor->isActive ?
				    "window-restore-symbolic" :
				    "window-maximize-symbolic";
		break;
	case HEADER_CLOSE:
		icon_name = "window-close-symbolic";
		break;
	default:
		icon_name = NULL;
		break;
	}
    //fprintf(stderr, "Button icon '%s' @(%d, %d)\n", icon_name, allocation.x, allocation.y);

	/* get scale */
	cairo_surface_get_device_scale(surface, &sx, &sy);
	scale = (sx+sy) / 2.0;

	/* get original icon dimensions */
	icon_widget = gtk_bin_get_child(GTK_BIN(button));
	gtk_widget_get_allocation(icon_widget, &allocation_icon);

	/* icon info */
	if (!gtk_icon_size_lookup(GTK_ICON_SIZE_MENU, &icon_width, &icon_height)) {
		icon_width = 16;
		icon_height = 16;
	}
	icon_info = gtk_icon_theme_lookup_icon_for_scale(
		gtk_icon_theme_get_default(), icon_name,
		icon_width, scale, (GtkIconLookupFlags)0);

	/* icon pixel buffer*/
	gtk_style_context_save(button_style);
	gtk_style_context_set_state(button_style, style_state);
	icon_pixbuf = gtk_icon_info_load_symbolic_for_context(
		icon_info, button_style, NULL, NULL);
	icon_surface = gdk_cairo_surface_create_from_pixbuf(icon_pixbuf, scale, NULL);
	gtk_style_context_restore(button_style);

	/* dimensions and position */
	gtk_style_context_get(button_style, gtk_style_context_get_state(button_style),
			      "min-width", &width, "min-height", &height, NULL);

	if (width < icon_width)
		width = icon_width;
	if (height < icon_height)
		height = icon_height;

	gtk_style_context_get_border(button_style, gtk_style_context_get_state(button_style), &border);
	left += border.left;
	right += border.right;
	top += border.top;
	bottom += border.bottom;

	gtk_style_context_get_padding(button_style, gtk_style_context_get_state(button_style), &padding);
	left += padding.left;
	right += padding.right;
	top += padding.top;
	bottom += padding.bottom;

	width += left + right;
	height += top + bottom;

	gtk_render_icon_surface(gtk_widget_get_style_context(icon_widget),
				cr, icon_surface,
				allocation.x + ((width - icon_width) / 2),
				allocation.y + ((height - icon_height) / 2));
	cairo_paint(cr);
	cairo_surface_destroy(icon_surface);
	g_object_unref(icon_pixbuf);
}

static void
draw_header_buttons(gtk_frame_decoration_t* decor, cairo_surface_t * surface, cairo_t *cr)
{
    if (decor->showMinimize) {
        draw_header_button(decor, surface, cr, HEADER_MIN);
    }

    if (decor->showMaximize) {
        draw_header_button(decor, surface, cr, HEADER_MAX);
    }

    draw_header_button(decor, surface, cr, HEADER_CLOSE);
}

static void
draw_title_bar(gtk_frame_decoration_t* decor, cairo_surface_t * surface, cairo_t *cr,
               int width, int height, int scale, const char *title)
{
	GtkStyleContext *style = gtk_widget_get_style_context(decor->window);

	if (decor->isActive) {
		gtk_widget_set_state_flags(decor->window, GTK_STATE_FLAG_BACKDROP, true);
	} else {
		gtk_widget_unset_state_flags(decor->window, GTK_STATE_FLAG_BACKDROP);
	}

    if (decor->isMaximized) {
        gtk_style_context_add_class(style, "maximized");
    } else {
		gtk_style_context_remove_class(style, "maximized");
    }

	gtk_widget_show_all(decor->window);

	gtk_header_bar_set_title(GTK_HEADER_BAR(decor->header), title);

    // TODO: natural size instead of division by scale
	GtkAllocation allocation = {0, 0, width / scale, height / scale};
	//gtk_widget_get_preferred_height(decor->header, NULL, &allocation.height);
	gtk_widget_size_allocate(decor->header, &allocation);

	draw_header_background(decor, cr);
	draw_header_title(decor, surface);
	//draw_header_buttons(decor, surface, cr);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeSwitchTheme
        (JNIEnv *env, jobject obj)
{
    while((*g_main_context_iteration)(NULL, FALSE));
}

JNIEXPORT jint JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeGetIntProperty
        (JNIEnv *env, jobject obj, jlong ptr, jstring name)
{
    jboolean isCopy = JNI_FALSE;
    const char *name_c_str = JNU_GetStringPlatformChars(env, name, &isCopy);
    if (!name_c_str)
        return 0;

    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);
    jint result;
    g_object_get(gtk_widget_get_settings(decor->window), name_c_str, &result, NULL);
    if (isCopy) {
        JNU_ReleaseStringPlatformChars(env, name, name_c_str);
    }

    return result;
}

JNIEXPORT jint JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeGetTitleBarHeight
        (JNIEnv *env, jobject obj, jlong ptr)
{
    assert (ptr != 0);

    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);
	gtk_widget_show_all(decor->window);
    jint result;
	gtk_widget_get_preferred_height(decor->header, NULL, &result);
    return result;
}

JNIEXPORT jint JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeGetTitleBarMinimumWidth
        (JNIEnv *env, jobject obj, jlong ptr)
{
    assert (ptr != 0);

    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);
	gtk_widget_show_all(decor->window);
    jint result;

    // TODO: maybe
    // gtk_widget_size_allocate(decor->header, &allocation);
    // instead of show_all?
	gtk_widget_get_preferred_width(decor->header, &result, NULL);
    return result;
}

JNIEXPORT jobject JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeGetButtonBounds
        (JNIEnv *env, jobject obj, jlong ptr, jint buttonID)
{
    assert (ptr != 0);

    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);

    /*
	gtk_widget_show_all(decor->window);
	GtkWidget *button;

	GtkAllocation allocation;

	char* name = NULL;
    switch (buttonID) {
        case sun_awt_wl_GtkFrameDecoration_MINIMIZE_BUTTON_ID:
            name = ".minimize";
            break;
        case sun_awt_wl_GtkFrameDecoration_MAXIMIZE_BUTTON_ID:
            name = ".maximize";
            break;
        case sun_awt_wl_GtkFrameDecoration_CLOSE_BUTTON_ID:
            name = ".close";
            break;
        default:
            JNU_ThrowInternalError(env, "Invalid button id");
            return NULL;
    }
	struct header_element_data data = {.name = name, .type = HEADER_NONE, .widget = NULL};
    GtkWidget* widget;
	find_widget_by_name(widget, &data);
	button = data.widget;
	if (!button) {
        JNU_ThrowInternalError(env, "Couldn't find button by id");
        return NULL;
    }
	gtk_widget_get_clip(button, &allocation);
    printf("(%d, %d) %dx%d\n", allocation.x, allocation.y, allocation.width, allocation.height);
    */

    rectangle_t bounds;
    switch (buttonID) {
        case sun_awt_wl_GtkFrameDecoration_MINIMIZE_BUTTON_ID:
            bounds = decor->minimizeButtonBounds;
            break;
        case sun_awt_wl_GtkFrameDecoration_MAXIMIZE_BUTTON_ID:
            bounds = decor->maximizeButtonBounds;
            break;
        case sun_awt_wl_GtkFrameDecoration_CLOSE_BUTTON_ID:
            bounds = decor->closeButtonBounds;
            break;
        default:
            JNU_ThrowInternalError(env, "Invalid button id");
            return NULL;
    }

    jobject recObj = JNU_NewObjectByName(env, "java/awt/Rectangle", "(IIII)V",bounds.x, bounds.y, bounds.width, bounds.height);
    return recObj;
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeNotifyConfigured
        (JNIEnv *env, jobject obj, jlong ptr, jboolean active, jboolean maximized, jboolean fullscreen)
{
    assert (ptr != 0);
    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);

    decor->isActive = active;
    decor->isMaximized = maximized;
}

