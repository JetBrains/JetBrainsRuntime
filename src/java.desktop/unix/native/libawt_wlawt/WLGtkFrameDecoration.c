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

#include "JNIUtilities.h"
#include "WLToolkit.h"

enum header_element {
	HEADER_NONE,
	HEADER_FULL, /* entire header bar */
	HEADER_TITLE, /* label */
	HEADER_MIN,
	HEADER_MAX,
	HEADER_CLOSE,
};

enum libdecor_window_state {
	LIBDECOR_WINDOW_STATE_NONE = 0,
	LIBDECOR_WINDOW_STATE_ACTIVE = 1 << 0,
	LIBDECOR_WINDOW_STATE_MAXIMIZED = 1 << 1,
	LIBDECOR_WINDOW_STATE_FULLSCREEN = 1 << 2,
	LIBDECOR_WINDOW_STATE_TILED_LEFT = 1 << 3,
	LIBDECOR_WINDOW_STATE_TILED_RIGHT = 1 << 4,
	LIBDECOR_WINDOW_STATE_TILED_TOP = 1 << 5,
	LIBDECOR_WINDOW_STATE_TILED_BOTTOM = 1 << 6,
	LIBDECOR_WINDOW_STATE_SUSPENDED = 1 << 7,
	LIBDECOR_WINDOW_STATE_RESIZING = 1 << 8,
};


static GtkWidget *window;
static GtkWidget *header;
static cairo_surface_t *surface = NULL;
static cairo_t *cr = NULL;

static void draw_title_bar(int width, int height, int scale);

static void init_containers(void) {
	window = gtk_offscreen_window_new();
	header = gtk_header_bar_new();

	g_object_set(header,
		     "title", "Default Title",
		     "has-subtitle", FALSE,
		     "show-close-button", TRUE,
		     NULL);

	GtkStyleContext *context_hdr;
	context_hdr = gtk_widget_get_style_context(header);
	gtk_style_context_add_class(context_hdr, GTK_STYLE_CLASS_TITLEBAR);
	gtk_style_context_add_class(context_hdr, "default-decoration");

	gtk_window_set_titlebar(GTK_WINDOW(window), header);
	gtk_header_bar_set_show_close_button(GTK_HEADER_BAR(header), TRUE);

	gtk_window_set_resizable(GTK_WINDOW(window), TRUE);
}

/*
 * Ensure everything is ready for drawing an element of the specified width
 * and height.
*/
static void gtk3_init_painting(JNIEnv *env, gint width, gint height, gint scale)
{
    if (cr) {
        cairo_destroy(cr);
    }

    gint data_size = width * height * 4 * scale;
    unsigned char * data = calloc(1, data_size);

    surface = cairo_image_surface_create_for_data(
        data, CAIRO_FORMAT_ARGB32,
        width, height,
        cairo_format_stride_for_width(CAIRO_FORMAT_ARGB32, width)
    );

	cr = cairo_create(surface);

	cairo_surface_set_device_scale(surface, scale, scale);
    init_containers();

}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLGtkFrameDecoration_nativeFinishPainting
        (JNIEnv *env, jobject obj, jintArray dest, jint width, jint height, jint scale)
{
    gtk3_init_painting(env, width, height, scale);

    draw_title_bar(width, height, scale);

    gint *buffer = (gint*) (*env)->GetPrimitiveArrayCritical(env, dest, 0);
    if (buffer == 0) {
        (*env)->ExceptionClear(env);
        JNU_ThrowOutOfMemoryError(env, "Could not get image buffer");
        return;
    }
    //gdk_threads_enter();
    gint i, j, r, g, b;
    guchar *data;
    gint stride, padding;

    cairo_surface_flush(surface);
    data = cairo_image_surface_get_data(surface);
    stride = cairo_image_surface_get_stride(surface);
    padding = stride - width * 4;
    if (stride > 0 && padding >= 0) {
        for (i = 0; i < height; i++) {
            for (j = 0; j < width; j++) {
                int r = *data++;
                int g = *data++;
                int b = *data++;
                int a = *data++;
                *buffer++ = (a << 24 | b << 16 | g << 8 | r);
            }
            data += padding;
        }
    }
    //gdk_threads_leave();
    (*env)->ReleasePrimitiveArrayCritical(env, dest, buffer, 0);
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
draw_header_background(void)
{
	/* background */
	GtkAllocation allocation;
	GtkStyleContext* style;

	gtk_widget_get_allocation(GTK_WIDGET(header), &allocation);
	style = gtk_widget_get_style_context(header);
	gtk_render_background(style, cr, allocation.x, allocation.y, allocation.width, allocation.height);
}

static void
draw_header_title(void)
{
	/* title */
	GtkWidget *label;
	GtkAllocation allocation;
	cairo_surface_t *label_surface = NULL;
	cairo_t *cr;

	label = find_widget_by_type(header, HEADER_TITLE).widget;
	gtk_widget_get_allocation(label, &allocation);
    fprintf(stderr, "Title location: @(%d, %d) %dx%d\n",
            allocation.x, allocation.y, allocation.width, allocation.height);

	/* create subsection in which to draw label */
	label_surface = cairo_surface_create_for_rectangle(
				surface,
				allocation.x, allocation.y,
				allocation.width, allocation.height);
	cr = cairo_create(label_surface);
	gtk_widget_size_allocate(label, &allocation);
	gtk_widget_draw(label, cr);
	cairo_destroy(cr);
	cairo_surface_destroy(label_surface);
}

static void
draw_header_button(enum header_element button_type,
		   enum libdecor_window_state window_state)
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

	elem = find_widget_by_type(header, button_type);
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
		icon_name = (window_state & LIBDECOR_WINDOW_STATE_MAXIMIZED) ?
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
    fprintf(stderr, "Button icon '%s' @(%d, %d)\n", icon_name, allocation.x, allocation.y);

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
draw_header_buttons(void)
{
	/* buttons */
	enum libdecor_window_state window_state;
	enum header_element *buttons = NULL;
	size_t nbuttons = 0;

	window_state = LIBDECOR_WINDOW_STATE_ACTIVE;

    draw_header_button(HEADER_MIN, window_state);
    draw_header_button(HEADER_MAX, window_state);
    draw_header_button(HEADER_CLOSE, window_state);
    free(buttons);
}

static void
draw_header(void)
{
	draw_header_background();
	draw_header_title();
	draw_header_buttons();
}

static void
draw_title_bar(int width, int height, int scale)
{
	GtkAllocation allocation = {0, 0, width / scale, 0 /*height / scale*/};
	enum libdecor_window_state state;
	GtkStyleContext *style;
	int pref_width;
	int current_min_w, current_min_h, current_max_w, current_max_h, W, H;

	state = LIBDECOR_WINDOW_STATE_ACTIVE;
	style = gtk_widget_get_style_context(window);

	if (!(state & LIBDECOR_WINDOW_STATE_ACTIVE)) {
		gtk_widget_set_state_flags(window, GTK_STATE_FLAG_BACKDROP, true);
	} else {
		gtk_widget_unset_state_flags(window, GTK_STATE_FLAG_BACKDROP);
	}

	//if (libdecor_frame_is_floating(&frame_gtk->frame)) {
		gtk_style_context_remove_class(style, "maximized");
	//} else {
	//	gtk_style_context_add_class(style, "maximized");
	//}

	gtk_widget_show_all(window);

	/* set default width, using an empty title to estimate its smallest admissible value */
	gtk_header_bar_set_title(GTK_HEADER_BAR(header), "");
	gtk_widget_get_preferred_width(header, NULL, &pref_width);
	gtk_header_bar_set_title(GTK_HEADER_BAR(header), "My Title");
	gtk_widget_get_preferred_height(header, NULL, &allocation.height);

	gtk_widget_size_allocate(header, &allocation);
    fprintf(stderr, "draw_title_bar allocation @%d, %d %dx%d\n", allocation.x, allocation.y, allocation.width, allocation.height);
    fprintf(stderr, "\tpreferred width=%d\n", pref_width);

	draw_header();
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLGtkFrameDecoration_nativeSwitchTheme
        (JNIEnv *env, jobject obj)
{
    while((*g_main_context_iteration)(NULL, FALSE));
}

