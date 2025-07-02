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

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <jni.h>
#include <jni_util.h>
#include <assert.h>

// TODO: temporary
#include <gtk/gtk.h>

#include "JNIUtilities.h"
#include "WLToolkit.h"

#include "sun_awt_wl_GtkFrameDecoration.h"

typedef struct gtk_frame_decoration_t {
    GtkWidget *window;
    GtkWidget *header;
    jboolean showMinimize;
    jboolean showMaximize;
    jboolean isActive;
    jboolean isMaximized;
} gtk_frame_decoration_t;

struct widget_cb_data_t {
    const char *name;
    GtkWidget *widget;
};

static jfieldID closeButtonBoundsFID;
static jfieldID minButtonBoundsFID;
static jfieldID maxButtonBoundsFID;
static jfieldID titleBarHeightFID;
static jfieldID titleBarMinWidthFID;

static void
draw_header_background
        (gtk_frame_decoration_t* decor, cairo_t *cr)
{
	GtkAllocation allocation;
	gtk_widget_get_allocation(GTK_WIDGET(decor->header), &allocation);
	GtkStyleContext* style = gtk_widget_get_style_context(decor->header);
	gtk_render_background(style, cr, allocation.x, allocation.y, allocation.width, allocation.height);
}

static void
widget_by_name_cb
        (GtkWidget *widget, void *payload)
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
widget_by_name
        (GtkWidget *widget, const char *name)
{
    struct widget_cb_data_t data = {.name = name, .widget = NULL};
    widget_by_name_cb(widget, &data);
    return data.widget;
}

static void
draw_header_title
        (gtk_frame_decoration_t* decor, cairo_surface_t * surface)
{
    GtkWidget *label = widget_by_name(decor->header, "label.title:");
    GtkAllocation allocation;
    gtk_widget_get_allocation(label, &allocation);
    cairo_surface_t *label_surface = cairo_surface_create_for_rectangle(
        surface, allocation.x, allocation.y, allocation.width, allocation.height);
    cairo_t *cr = cairo_create(label_surface);
    //gtk_widget_size_allocate(label, &allocation); // TODO: why allocate in the same place?
    gtk_widget_draw(label, cr);
    cairo_destroy(cr);
    cairo_surface_destroy(label_surface);
}

static void
draw_header_button
        (gtk_frame_decoration_t* decor, cairo_surface_t * surface, cairo_t *cr,
         jboolean hovered, jboolean pressed,
         const char *name, const char *icon_name)
{
	GtkWidget *button = widget_by_name(decor->header, name);
	if (!button)
		return;
    GtkStyleContext* button_style = gtk_widget_get_style_context(button);
	GtkStateFlags style_state = GTK_STATE_FLAG_NORMAL;

	if (!decor->isActive) {
		style_state |= GTK_STATE_FLAG_BACKDROP;
	} else {
        style_state |= GTK_STATE_FLAG_FOCUSED;
    }
	if (hovered) {
		style_state |= GTK_STATE_FLAG_PRELIGHT;
	}
	if (pressed) {
        style_state |= GTK_STATE_FLAG_ACTIVE;
	}

	/* background */
	GtkAllocation allocation;
	gtk_widget_get_clip(button, &allocation);
    printf("button %s clip: %d, %d %dx%d\n", name, allocation.x, allocation.y,
           allocation.width, allocation.height);

	gtk_style_context_save(button_style);
    gtk_style_context_set_state(button_style, style_state);
    gtk_render_background(button_style, cr,
                          allocation.x, allocation.y, allocation.width, allocation.height);
    gtk_render_frame(button_style, cr,
                     allocation.x, allocation.y, allocation.width, allocation.height);
    gtk_style_context_restore(button_style);

	double sx, sy;
	cairo_surface_get_device_scale(surface, &sx, &sy);
	int scale = (sx+sy) / 2.0;

	/* get original icon dimensions */
	GtkWidget *icon_widget = gtk_bin_get_child(GTK_BIN(button));
	GtkAllocation allocation_icon;
	gtk_widget_get_allocation(icon_widget, &allocation_icon);

	/* icon info */
	gint icon_width, icon_height;
	if (!gtk_icon_size_lookup(GTK_ICON_SIZE_MENU, &icon_width, &icon_height)) {
		icon_width = 16;
		icon_height = 16;
	}
	GtkIconInfo* icon_info = gtk_icon_theme_lookup_icon_for_scale(
		gtk_icon_theme_get_default(), icon_name,
		icon_width, scale, (GtkIconLookupFlags)0);

	/* icon pixel buffer*/
	gtk_style_context_save(button_style);
	gtk_style_context_set_state(button_style, style_state);
	GdkPixbuf* icon_pixbuf = gtk_icon_info_load_symbolic_for_context(icon_info, button_style, NULL, NULL);
	cairo_surface_t* icon_surface = gdk_cairo_surface_create_from_pixbuf(icon_pixbuf, scale, NULL);
	gtk_style_context_restore(button_style);

	/* dimensions and position */
	gint width = 0, height = 0;
	gint left = 0, top = 0, right = 0, bottom = 0;
	GtkBorder border;

	GtkBorder padding;

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

    printf("\tbutton %dx%d\n", width, height);
    printf("\tbutton padding %d, %d, %d, %d\n", left, right, top, bottom);
	width += left + right;
	height += top + bottom;

    int offset_x = (width - icon_width) / 2;
    int offset_y = (height - icon_height) / 2;
	gtk_render_icon_surface(gtk_widget_get_style_context(icon_widget),
				cr, icon_surface, allocation.x + offset_x, allocation.y + offset_y);
	cairo_paint(cr);
	cairo_surface_destroy(icon_surface);
	g_object_unref(icon_pixbuf);
}

static void
draw_header_buttons
        (gtk_frame_decoration_t* decor, cairo_surface_t * surface, cairo_t *cr, int buttonsState)
{
    if (decor->showMinimize) {
        jboolean hovered = buttonsState & sun_awt_wl_GtkFrameDecoration_MIN_BUTTON_STATE_HOVERED;
        jboolean pressed = buttonsState & sun_awt_wl_GtkFrameDecoration_MIN_BUTTON_STATE_PRESSED;
        draw_header_button(decor, surface, cr, hovered, pressed,
                           ".minimize", "window-minimize-symbolic");
    }

    if (decor->showMaximize) {
        jboolean hovered = buttonsState & sun_awt_wl_GtkFrameDecoration_MAX_BUTTON_STATE_HOVERED;
        jboolean pressed = buttonsState & sun_awt_wl_GtkFrameDecoration_MAX_BUTTON_STATE_PRESSED;
        draw_header_button(decor, surface, cr, hovered, pressed,
                           ".maximize",
                           decor->isMaximized ? "window-restore-symbolic" : "window-maximize-symbolic");
    }

    jboolean hovered = buttonsState & sun_awt_wl_GtkFrameDecoration_CLOSE_BUTTON_STATE_HOVERED;
    jboolean pressed = buttonsState & sun_awt_wl_GtkFrameDecoration_CLOSE_BUTTON_STATE_PRESSED;
    draw_header_button(decor, surface, cr, hovered, pressed,
                       ".close", "window-close-symbolic");
}

static void
draw_title_bar
        (gtk_frame_decoration_t* decor, cairo_surface_t * surface, cairo_t *cr,
         int width, int height, int scale, const char *title, int buttonsState)
{
	GtkStyleContext *style = gtk_widget_get_style_context(decor->window);

	if (!decor->isActive) {
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

	GtkAllocation allocation = {0, 0, width, height};
	gtk_widget_size_allocate(decor->header, &allocation);

	draw_header_background(decor, cr);
	draw_header_title(decor, surface);
    draw_header_buttons(decor, surface, cr, buttonsState);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_GtkFrameDecoration_initIDs
        (JNIEnv *env, jclass clazz)
{
    CHECK_NULL_THROW_IE(env,
                        closeButtonBoundsFID = (*env)->GetFieldID(env, clazz, "closeButtonBounds", "Ljava/awt/Rectangle;"),
                        "Failed to find field closeButtonBounds");
    CHECK_NULL_THROW_IE(env,
                        minButtonBoundsFID = (*env)->GetFieldID(env, clazz, "minimizeButtonBounds", "Ljava/awt/Rectangle;"),
                        "Failed to find field minimizeButtonBounds");
    CHECK_NULL_THROW_IE(env,
                        maxButtonBoundsFID = (*env)->GetFieldID(env, clazz, "maximizeButtonBounds", "Ljava/awt/Rectangle;"),
                        "Failed to find field maximizeButtonBounds");
    CHECK_NULL_THROW_IE(env,
                        titleBarHeightFID = (*env)->GetFieldID(env, clazz, "titleBarHeight", "I"),
                        "Failed to find field titleBarHeightBounds");
    CHECK_NULL_THROW_IE(env,
                        titleBarMinWidthFID = (*env)->GetFieldID(env, clazz, "titleBarMinWidth", "I"),
                        "Failed to find field titleBarMinWidth");
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_GtkFrameDecoration_nativeCreateDecoration
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


JNIEXPORT void JNICALL
Java_sun_awt_wl_GtkFrameDecoration_nativePaintTitleBar
        (JNIEnv *env, jobject obj, jlong ptr, jintArray dest,
         jint width, jint height, jdouble scale,
         jstring title, jint buttonsState)
{
    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);

    jint pixelWidth = ceil(width * scale);
    jint pixelHeight = ceil(height * scale);

    unsigned char *buffer = (*env)->GetPrimitiveArrayCritical(env, dest, 0);
    if (!buffer) {
        JNU_ThrowOutOfMemoryError(env, "Could not get image buffer");
        return;
    }

    //gdk_threads_enter();
    cairo_surface_t *surface = cairo_image_surface_create_for_data(
        buffer, CAIRO_FORMAT_ARGB32,
        pixelWidth, pixelHeight,
        cairo_format_stride_for_width(CAIRO_FORMAT_ARGB32, pixelWidth));

	cairo_surface_set_device_scale(surface, scale, scale);

	cairo_t *cr = cairo_create(surface);

    jboolean isCopy = JNI_FALSE;
    const char *title_c_str = JNU_GetStringPlatformChars(env, title, &isCopy);
    if (!title_c_str)
        return;
    draw_title_bar(decor, surface, cr, width, height, scale, title_c_str, buttonsState);

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

JNIEXPORT void JNICALL
Java_sun_awt_wl_GtkFrameDecoration_nativePrePaint
        (JNIEnv *env, jobject obj, jlong ptr, jint width)
{
    assert (ptr != 0);
    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);

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

	gtk_header_bar_set_title(GTK_HEADER_BAR(decor->header), "Title");
	gtk_widget_show_all(decor->window);

    jint pref_height;
	gtk_widget_get_preferred_height(decor->header, NULL, &pref_height);
    jint min_width;
	gtk_widget_get_preferred_width(decor->header, &min_width, NULL);

    (*env)->SetIntField(env, obj, titleBarHeightFID, pref_height);
    (*env)->SetIntField(env, obj, titleBarMinWidthFID, min_width);

	GtkAllocation ha = {0, 0, width, pref_height};
	gtk_widget_size_allocate(decor->header, &ha);

	GtkWidget *button = widget_by_name(decor->header, ".close");
	GtkAllocation ba;
    if (button) {
        gtk_widget_get_clip(button, &ba);
        jobject recObj = JNU_NewObjectByName(env, "java/awt/Rectangle", "(IIII)V",
                                             ba.x, ba.y, ba.width, ba.height);
        (*env)->SetObjectField(env, obj, closeButtonBoundsFID, recObj);
    }

	button = widget_by_name(decor->header, ".minimize");
    if (button) {
        gtk_widget_get_clip(button, &ba);
        jobject recObj = JNU_NewObjectByName(env, "java/awt/Rectangle", "(IIII)V",
                                             ba.x, ba.y, ba.width, ba.height);
        (*env)->SetObjectField(env, obj, minButtonBoundsFID, recObj);
    }

	button = widget_by_name(decor->header, ".maximize");
    if (button) {
        gtk_widget_get_clip(button, &ba);
        jobject recObj = JNU_NewObjectByName(env, "java/awt/Rectangle", "(IIII)V",
                                             ba.x, ba.y, ba.width, ba.height);
        (*env)->SetObjectField(env, obj, maxButtonBoundsFID, recObj);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_GtkFrameDecoration_nativeSwitchTheme
        (JNIEnv *env, jobject obj)
{
    while((*g_main_context_iteration)(NULL, FALSE));
}

JNIEXPORT jint JNICALL
Java_sun_awt_wl_GtkFrameDecoration_nativeGetIntProperty
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

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeNotifyConfigured
        (JNIEnv *env, jobject obj, jlong ptr, jboolean active, jboolean maximized, jboolean fullscreen)
{
    assert (ptr != 0);
    gtk_frame_decoration_t* decor = jlong_to_ptr(ptr);

    decor->isActive = active;
    decor->isMaximized = maximized;
}

