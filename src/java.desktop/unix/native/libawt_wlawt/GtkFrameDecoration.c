/*
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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

#include "sun_awt_wl_GtkFrameDecoration.h"
#include "JNIUtilities.h"
#include "WLToolkit.h"

#include <jni_util.h>

#include <assert.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

typedef void* gpointer;
typedef char gchar;
typedef int gint;
typedef unsigned int guint;
typedef gint gboolean;
typedef signed short gint16;
typedef double gdouble;
typedef void* GtkWidget;
typedef void* GtkStyleContext;
typedef void* GtkContainer;
typedef void* GtkBin;
typedef void* GtkWindow;
typedef void* GtkHeaderBar;
typedef void* GtkIconInfo;
typedef void* GtkIconTheme;
typedef void* GtkSettings;
typedef void* GdkPixbuf;
typedef void* GdkWindow;
typedef void* GError;
typedef unsigned long GType;
typedef void (*GtkCallback)(GtkWidget *widget, gpointer data);
typedef struct _GValue GValue;
typedef struct _GObject GObject;
typedef struct _GTypeInstance GTypeInstance;
typedef struct _GMainContext GMainContext;

typedef enum {
    GTK_ICON_SIZE_INVALID,
    GTK_ICON_SIZE_MENU,
    GTK_ICON_SIZE_SMALL_TOOLBAR,
    GTK_ICON_SIZE_LARGE_TOOLBAR,
    GTK_ICON_SIZE_BUTTON,
    GTK_ICON_SIZE_DND,
    GTK_ICON_SIZE_DIALOG
} GtkIconSize;

typedef enum {
    GTK_ICON_LOOKUP_NO_SVG = 1 << 0,
    GTK_ICON_LOOKUP_FORCE_SVG = 1 << 1,
    GTK_ICON_LOOKUP_USE_BUILTIN = 1 << 2,
    GTK_ICON_LOOKUP_GENERIC_FALLBACK = 1 << 3,
    GTK_ICON_LOOKUP_FORCE_SIZE = 1 << 4,
    GTK_ICON_LOOKUP_FORCE_REGULAR = 1 << 5,
    GTK_ICON_LOOKUP_FORCE_SYMBOLIC = 1 << 6,
    GTK_ICON_LOOKUP_DIR_LTR = 1 << 7,
    GTK_ICON_LOOKUP_DIR_RTL = 1 << 8
} GtkIconLookupFlags;

typedef enum {
    GTK_STATE_FLAG_NORMAL = 0,
    GTK_STATE_FLAG_ACTIVE = 1 << 0,
    GTK_STATE_FLAG_PRELIGHT = 1 << 1,
    GTK_STATE_FLAG_SELECTED = 1 << 2,
    GTK_STATE_FLAG_INSENSITIVE = 1 << 3,
    GTK_STATE_FLAG_INCONSISTENT = 1 << 4,
    GTK_STATE_FLAG_FOCUSED = 1 << 5,
    GTK_STATE_FLAG_BACKDROP = 1 << 6
} GtkStateFlags;

typedef enum {
    GTK_STYLE_CONTEXT_PRINT_NONE = 0,
    GTK_STYLE_CONTEXT_PRINT_RECURSE = 1 << 0,
    GTK_STYLE_CONTEXT_PRINT_SHOW_STYLE = 1 << 1
} GtkStyleContextPrintFlags;

typedef struct {
    gint16 left;
    gint16 right;
    gint16 top;
    gint16 bottom;
} GtkBorder;

typedef struct {
    int x;
    int y;
    int width;
    int height;
} GtkAllocation;

#define GTK_STYLE_CLASS_TITLEBAR "titlebar"
#define GTK_TYPE_WIDGET (p_gtk_widget_get_type())
#define GTK_TYPE_CONTAINER (p_gtk_container_get_type())
#define GTK_IS_CONTAINER(obj) p_g_type_check_instance_is_a((obj), GTK_TYPE_CONTAINER)
#define GTK_IS_WIDGET(obj) p_g_type_check_instance_is_a((obj), GTK_TYPE_WIDGET)
#define GTK_CONTAINER(obj) ((GtkContainer)(obj))
#define GTK_BIN(obj) ((GtkBin)(obj))
#define GTK_WINDOW(obj) ((GtkWindow)(obj))
#define GTK_WIDGET(obj) ((GtkWidget)(obj))
#define GTK_HEADER_BAR(obj) ((GtkHeaderBar)(obj))

typedef enum {
    CAIRO_FORMAT_ARGB32 = 0
} cairo_format_t;

typedef struct _cairo cairo_t;
typedef struct _cairo_surface cairo_surface_t;

typedef char* (*gtk_style_context_to_string_t)(GtkStyleContext*, GtkStyleContextPrintFlags);
typedef gboolean (*gtk_icon_size_lookup_t)(GtkIconSize, gint*, gint*);
typedef GdkPixbuf* (*gtk_icon_info_load_symbolic_for_context_t)(GtkIconInfo*, GtkStyleContext*, gboolean*, GError**);
typedef GtkIconInfo* (*gtk_icon_theme_lookup_icon_for_scale_t)(GtkIconTheme*, const gchar*, gint, gint, GtkIconLookupFlags);
typedef GtkIconTheme* (*gtk_icon_theme_get_default_t)(void);
typedef GtkSettings* (*gtk_settings_get_default_t)(void);
typedef GtkSettings* (*gtk_widget_get_settings_t)(GtkWidget*);
typedef GtkStateFlags (*gtk_style_context_get_state_t)(GtkStyleContext *context);
typedef GtkStyleContext* (*gtk_widget_get_style_context_t)(GtkWidget*);
typedef GtkWidget* (*gtk_bin_get_child_t)(GtkBin*);
typedef GtkWidget* (*gtk_header_bar_new_t)(void);
typedef GtkWidget* (*gtk_offscreen_window_new_t)(void);
typedef GType (*gtk_container_get_type_t)(void);
typedef GType (*gtk_widget_get_type_t)(void);
typedef void (*gtk_container_forall_t)(GtkContainer*, GtkCallback, gpointer);
typedef void (*gtk_header_bar_set_show_close_button_t)(GtkHeaderBar*, gboolean);
typedef void (*gtk_header_bar_set_title_t)(GtkHeaderBar*, const gchar*);
typedef void (*gtk_render_background_t)(GtkStyleContext*, cairo_t*, gdouble, gdouble, gdouble, gdouble);
typedef void (*gtk_render_frame_t)(GtkStyleContext*, cairo_t*, gdouble, gdouble, gdouble, gdouble);
typedef void (*gtk_render_icon_surface_t)(GtkStyleContext*, cairo_t*, cairo_surface_t*, gdouble, gdouble);
typedef void (*gtk_style_context_add_class_t)(GtkStyleContext*, const gchar*);
typedef void (*gtk_style_context_get_border_t)(GtkStyleContext*, GtkStateFlags, GtkBorder*);
typedef void (*gtk_style_context_get_padding_t)(GtkStyleContext*, GtkStateFlags, GtkBorder*);
typedef void (*gtk_style_context_get_t)(GtkStyleContext*, GtkStateFlags, const gchar*, ...);
typedef void (*gtk_style_context_remove_class_t)(GtkStyleContext*, const gchar*);
typedef void (*gtk_style_context_restore_t)(GtkStyleContext*);
typedef void (*gtk_style_context_save_t)(GtkStyleContext*);
typedef void (*gtk_style_context_set_state_t)(GtkStyleContext*, GtkStateFlags);
typedef void (*gtk_widget_destroy_t)(GtkWidget*);
typedef void (*gtk_widget_draw_t)(GtkWidget*, cairo_t*);
typedef void (*gtk_widget_get_allocation_t)(GtkWidget*, GtkAllocation*);
typedef void (*gtk_widget_get_clip_t)(GtkWidget*, GtkAllocation*);
typedef void (*gtk_widget_get_preferred_height_t)(GtkWidget*, gint*, gint*);
typedef void (*gtk_widget_get_preferred_width_t)(GtkWidget*, gint*, gint*);
typedef void (*gtk_widget_set_state_flags_t)(GtkWidget*, GtkStateFlags, gboolean);
typedef void (*gtk_widget_show_all_t)(GtkWidget*);
typedef void (*gtk_widget_size_allocate_t)(GtkWidget*, GtkAllocation*);
typedef void (*gtk_widget_unset_state_flags_t)(GtkWidget*, GtkStateFlags);
typedef void (*gtk_window_set_resizable_t)(GtkWindow*, gboolean);
typedef void (*gtk_window_set_titlebar_t)(GtkWindow*, GtkWidget*);

typedef cairo_surface_t* (*gdk_cairo_surface_create_from_pixbuf_t)(const GdkPixbuf*, int, GdkWindow*);
typedef void (*gdk_threads_enter_t)(void);
typedef void (*gdk_threads_leave_t)(void);

typedef cairo_surface_t* (*cairo_image_surface_create_for_data_t)(unsigned char*, cairo_format_t, int, int, int);
typedef cairo_surface_t* (*cairo_surface_create_for_rectangle_t)(cairo_surface_t*, double, double, double, double);
typedef cairo_t* (*cairo_create_t)(cairo_surface_t*);
typedef int (*cairo_format_stride_for_width_t)(cairo_format_t, int);
typedef void (*cairo_destroy_t)(cairo_t*);
typedef void (*cairo_paint_t)(cairo_t*);
typedef void (*cairo_surface_destroy_t)(cairo_surface_t*);
typedef void (*cairo_surface_flush_t)(cairo_surface_t *);
typedef void (*cairo_surface_set_device_scale_t)(cairo_surface_t *, double, double);

typedef void (*g_object_unref_t)(gpointer);
typedef void (*g_object_get_t)(gpointer, const gchar*, ...);
typedef void (*g_object_set_t)(gpointer, const gchar*, ...);
typedef void (*g_object_get_property_t)(GObject*, const gchar*, GValue*);
typedef gboolean (*g_type_check_instance_is_a_t)(void**, GType);

typedef struct GtkFrameDecorationDescr {
    GtkWidget *window;
    GtkWidget *titlebar;
    jboolean   show_minimize;
    jboolean   show_maximize;
    jboolean   is_active;
    jboolean   is_maximized;
} GtkFrameDecorationDescr;

struct WidgetCallbackData {
    const char *name;
    GtkWidget  *widget;
};

static jfieldID CloseButtonBoundsFID;
static jfieldID MinButtonBoundsFID;
static jfieldID MaxButtonBoundsFID;
static jfieldID TitleBarHeightFID;
static jfieldID TitleBarMinWidthFID;

static gtk_bin_get_child_t p_gtk_bin_get_child;
static gtk_container_forall_t p_gtk_container_forall;
static gtk_container_get_type_t p_gtk_container_get_type;
static gtk_header_bar_new_t p_gtk_header_bar_new;
static gtk_header_bar_set_show_close_button_t p_gtk_header_bar_set_show_close_button;
static gtk_header_bar_set_title_t p_gtk_header_bar_set_title;
static gtk_icon_info_load_symbolic_for_context_t p_gtk_icon_info_load_symbolic_for_context;
static gtk_icon_size_lookup_t p_gtk_icon_size_lookup;
static gtk_icon_theme_get_default_t p_gtk_icon_theme_get_default;
static gtk_icon_theme_lookup_icon_for_scale_t p_gtk_icon_theme_lookup_icon_for_scale;
static gtk_offscreen_window_new_t p_gtk_offscreen_window_new;
static gtk_render_background_t p_gtk_render_background;
static gtk_render_frame_t p_gtk_render_frame;
static gtk_render_icon_surface_t p_gtk_render_icon_surface;
static gtk_settings_get_default_t p_gtk_settings_get_default;
static gtk_style_context_add_class_t p_gtk_style_context_add_class;
static gtk_style_context_get_border_t p_gtk_style_context_get_border;
static gtk_style_context_get_padding_t p_gtk_style_context_get_padding;
static gtk_style_context_get_state_t p_gtk_style_context_get_state;
static gtk_style_context_get_t p_gtk_style_context_get;
static gtk_style_context_remove_class_t p_gtk_style_context_remove_class;
static gtk_style_context_restore_t p_gtk_style_context_restore;
static gtk_style_context_save_t p_gtk_style_context_save;
static gtk_style_context_set_state_t p_gtk_style_context_set_state;
static gtk_style_context_to_string_t p_gtk_style_context_to_string;
static gtk_widget_destroy_t p_gtk_widget_destroy;
static gtk_widget_draw_t p_gtk_widget_draw;
static gtk_widget_get_allocation_t p_gtk_widget_get_allocation;
static gtk_widget_get_clip_t p_gtk_widget_get_clip;
static gtk_widget_get_preferred_height_t p_gtk_widget_get_preferred_height;
static gtk_widget_get_preferred_width_t p_gtk_widget_get_preferred_width;
static gtk_widget_get_settings_t p_gtk_widget_get_settings;
static gtk_widget_get_style_context_t p_gtk_widget_get_style_context;
static gtk_widget_get_type_t p_gtk_widget_get_type;
static gtk_widget_set_state_flags_t p_gtk_widget_set_state_flags;
static gtk_widget_show_all_t p_gtk_widget_show_all;
static gtk_widget_size_allocate_t p_gtk_widget_size_allocate;
static gtk_widget_unset_state_flags_t p_gtk_widget_unset_state_flags;
static gtk_window_set_resizable_t p_gtk_window_set_resizable;
static gtk_window_set_titlebar_t p_gtk_window_set_titlebar;

static gdk_cairo_surface_create_from_pixbuf_t p_gdk_cairo_surface_create_from_pixbuf;
static gdk_threads_enter_t p_gdk_threads_enter;
static gdk_threads_leave_t p_gdk_threads_leave;

static cairo_create_t p_cairo_create;
static cairo_destroy_t p_cairo_destroy;
static cairo_format_stride_for_width_t p_cairo_format_stride_for_width;
static cairo_image_surface_create_for_data_t p_cairo_image_surface_create_for_data;
static cairo_paint_t p_cairo_paint;
static cairo_surface_create_for_rectangle_t p_cairo_surface_create_for_rectangle;
static cairo_surface_destroy_t p_cairo_surface_destroy;
static cairo_surface_flush_t p_cairo_surface_flush;
static cairo_surface_set_device_scale_t p_cairo_surface_set_device_scale;

static g_object_unref_t p_g_object_unref;
static g_object_get_t p_g_object_get;
static g_object_set_t p_g_object_set;
static g_object_get_property_t p_g_object_get_property;
static g_type_check_instance_is_a_t p_g_type_check_instance_is_a;

static void* gtk_handle;
static void* gdk_handle;

static void* find_func(JNIEnv* env, void* lib_handle, const char* name) {
    void* f = dlsym(lib_handle, name);
    if (!f) {
        int len = strlen(name) + 128;
        char *msg = malloc(len);
        if (msg) {
            snprintf(msg, len, "Can't find function '%s'", name);
            JNU_ThrowByName(env, "java/lang/UnsatisfiedLinkError", msg);
            free(msg);
        } else {
            JNU_ThrowByName(env, "java/lang/UnsatisfiedLinkError", "Can't find some function; also ran out of memory.");
        }
    }
    return f;
}

static jboolean load_gtk(JNIEnv *env) {
    gtk_handle = dlopen(VERSIONED_JNI_LIB_NAME("gtk-3", "0"), RTLD_LAZY | RTLD_LOCAL);
    if (!gtk_handle) {
        gtk_handle = dlopen(JNI_LIB_NAME("gtk-3"), RTLD_LAZY | RTLD_LOCAL);
    }

    if (!gtk_handle) {
        return JNI_FALSE;
    }

    gdk_handle = dlopen(VERSIONED_JNI_LIB_NAME("gdk-3", "0"), RTLD_LAZY | RTLD_LOCAL);
    if (!gdk_handle) {
        gdk_handle = dlopen(JNI_LIB_NAME("gdk-3"), RTLD_LAZY | RTLD_LOCAL);
    }

    if (!gdk_handle) {
        dlclose(gtk_handle);
        return JNI_FALSE;
    }

    p_gtk_bin_get_child = find_func(env, gtk_handle, "gtk_bin_get_child");
    p_gtk_container_forall = find_func(env, gtk_handle, "gtk_container_forall");
    p_gtk_container_get_type = find_func(env, gtk_handle, "gtk_container_get_type");
    p_gtk_header_bar_new = find_func(env, gtk_handle, "gtk_header_bar_new");
    p_gtk_header_bar_set_show_close_button = find_func(env, gtk_handle, "gtk_header_bar_set_show_close_button");
    p_gtk_header_bar_set_title = find_func(env, gtk_handle, "gtk_header_bar_set_title");
    p_gtk_icon_info_load_symbolic_for_context = find_func(env, gtk_handle, "gtk_icon_info_load_symbolic_for_context");
    p_gtk_icon_size_lookup = find_func(env, gtk_handle, "gtk_icon_size_lookup");
    p_gtk_icon_theme_get_default = find_func(env, gtk_handle, "gtk_icon_theme_get_default");
    p_gtk_icon_theme_lookup_icon_for_scale = find_func(env, gtk_handle, "gtk_icon_theme_lookup_icon_for_scale");
    p_gtk_offscreen_window_new = find_func(env, gtk_handle, "gtk_offscreen_window_new");
    p_gtk_widget_destroy = find_func(env, gtk_handle, "gtk_widget_destroy");
    p_gtk_render_background = find_func(env, gtk_handle, "gtk_render_background");
    p_gtk_render_frame = find_func(env, gtk_handle, "gtk_render_frame");
    p_gtk_render_icon_surface = find_func(env, gtk_handle, "gtk_render_icon_surface");
    p_gtk_settings_get_default = find_func(env, gtk_handle, "gtk_settings_get_default");
    p_gtk_style_context_add_class = find_func(env, gtk_handle, "gtk_style_context_add_class");
    p_gtk_style_context_get_border = find_func(env, gtk_handle, "gtk_style_context_get_border");
    p_gtk_style_context_get = find_func(env, gtk_handle, "gtk_style_context_get");
    p_gtk_style_context_get_padding = find_func(env, gtk_handle, "gtk_style_context_get_padding");
    p_gtk_style_context_get_state = find_func(env, gtk_handle, "gtk_style_context_get_state");
    p_gtk_style_context_remove_class = find_func(env, gtk_handle, "gtk_style_context_remove_class");
    p_gtk_style_context_restore = find_func(env, gtk_handle, "gtk_style_context_restore");
    p_gtk_style_context_save = find_func(env, gtk_handle, "gtk_style_context_save");
    p_gtk_style_context_set_state = find_func(env, gtk_handle, "gtk_style_context_set_state");
    p_gtk_style_context_to_string = find_func(env, gtk_handle, "gtk_style_context_to_string");
    p_gtk_widget_draw = find_func(env, gtk_handle, "gtk_widget_draw");
    p_gtk_widget_get_allocation = find_func(env, gtk_handle, "gtk_widget_get_allocation");
    p_gtk_widget_get_clip = find_func(env, gtk_handle, "gtk_widget_get_clip");
    p_gtk_widget_get_preferred_height = find_func(env, gtk_handle, "gtk_widget_get_preferred_height");
    p_gtk_widget_get_preferred_width = find_func(env, gtk_handle, "gtk_widget_get_preferred_width");
    p_gtk_widget_get_settings = find_func(env, gtk_handle, "gtk_widget_get_settings");
    p_gtk_widget_get_style_context = find_func(env, gtk_handle, "gtk_widget_get_style_context");
    p_gtk_widget_get_type = find_func(env, gtk_handle, "gtk_widget_get_type");
    p_gtk_widget_set_state_flags = find_func(env, gtk_handle, "gtk_widget_set_state_flags");
    p_gtk_widget_show_all = find_func(env, gtk_handle, "gtk_widget_show_all");
    p_gtk_widget_size_allocate = find_func(env, gtk_handle, "gtk_widget_size_allocate");
    p_gtk_widget_unset_state_flags = find_func(env, gtk_handle, "gtk_widget_unset_state_flags");
    p_gtk_window_set_resizable = find_func(env, gtk_handle, "gtk_window_set_resizable");
    p_gtk_window_set_titlebar = find_func(env, gtk_handle, "gtk_window_set_titlebar");

    p_gdk_cairo_surface_create_from_pixbuf = find_func(env, gdk_handle, "gdk_cairo_surface_create_from_pixbuf");
    p_gdk_threads_enter = find_func(env, gdk_handle, "gdk_threads_enter");
    p_gdk_threads_leave = find_func(env, gdk_handle, "gdk_threads_leave");

    p_cairo_surface_create_for_rectangle = find_func(env, gtk_handle, "cairo_surface_create_for_rectangle");
    p_cairo_create = find_func(env, gtk_handle, "cairo_create");
    p_cairo_destroy = find_func(env, gtk_handle, "cairo_destroy");
    p_cairo_surface_destroy = find_func(env, gtk_handle, "cairo_surface_destroy");
    p_cairo_paint = find_func(env, gtk_handle, "cairo_paint");
    p_cairo_image_surface_create_for_data = find_func(env, gtk_handle, "cairo_image_surface_create_for_data");
    p_cairo_surface_set_device_scale = find_func(env, gtk_handle, "cairo_surface_set_device_scale");
    p_cairo_format_stride_for_width = find_func(env, gtk_handle, "cairo_format_stride_for_width");
    p_cairo_surface_flush = find_func(env, gtk_handle, "cairo_surface_flush");

    p_g_object_unref = find_func(env, gtk_handle, "g_object_unref");
    p_g_object_get = find_func(env, gtk_handle, "g_object_get");
    p_g_object_set = find_func(env, gtk_handle, "g_object_set");
    p_g_object_get_property = find_func(env, gtk_handle, "g_object_get_property");
    p_g_type_check_instance_is_a = find_func(env, gtk_handle, "g_type_check_instance_is_a");

    // NB: An error had been thrown in case some function was missing
    return JNI_TRUE;
}

static void widget_by_name_cb(GtkWidget *widget, void *payload) {
    struct WidgetCallbackData *data = payload;
    if (GTK_IS_WIDGET(widget)) {
        char *style_ctx = p_gtk_style_context_to_string(
            p_gtk_widget_get_style_context(widget),
            GTK_STYLE_CONTEXT_PRINT_SHOW_STYLE);
        if (strstr(style_ctx, data->name)) {
            data->widget = widget;
            free(style_ctx);
            return;
        }
        free(style_ctx);
    }

    if (GTK_IS_CONTAINER(widget)) {
        p_gtk_container_forall(GTK_CONTAINER(widget), &widget_by_name_cb, data);
    }
}

static GtkWidget* widget_by_name(GtkWidget *widget, const char *name) {
    struct WidgetCallbackData data = {.name = name, .widget = NULL};
    widget_by_name_cb(widget, &data);
    return data.widget;
}

static void draw_titlebar_background(GtkFrameDecorationDescr* decor, cairo_t *cr) {
	GtkAllocation allocation;
	p_gtk_widget_get_allocation(GTK_WIDGET(decor->titlebar), &allocation);
	GtkStyleContext* style = p_gtk_widget_get_style_context(decor->titlebar);
	p_gtk_render_background(style, cr, allocation.x, allocation.y, allocation.width, allocation.height);
}

static void draw_titlebar_title(GtkFrameDecorationDescr* decor, cairo_surface_t * surface) {
    GtkWidget *label = widget_by_name(decor->titlebar, "label.title:");
    GtkAllocation allocation;
    p_gtk_widget_get_allocation(label, &allocation);
    cairo_surface_t *label_surface = p_cairo_surface_create_for_rectangle(
        surface, allocation.x, allocation.y, allocation.width, allocation.height);
    cairo_t *cr = p_cairo_create(label_surface);
    p_gtk_widget_draw(label, cr);
    p_cairo_destroy(cr);
    p_cairo_surface_destroy(label_surface);
}

static void draw_titlebar_button(GtkFrameDecorationDescr* decor, cairo_surface_t * surface, cairo_t *cr,
                                 double scale,
                                 jboolean hovered, jboolean pressed,
                                 const char *name, const char *icon_name) {
    GtkWidget *button = widget_by_name(decor->titlebar, name);
    if (!button)
        return;

    GtkStateFlags style_state = GTK_STATE_FLAG_NORMAL;
    if (!decor->is_active) {
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

    GtkAllocation allocation;
    p_gtk_widget_get_clip(button, &allocation);

    GtkStyleContext* button_style = p_gtk_widget_get_style_context(button);
    p_gtk_style_context_save(button_style);
    p_gtk_style_context_set_state(button_style, style_state);
    p_gtk_render_background(button_style, cr,
                            allocation.x, allocation.y, allocation.width, allocation.height);
    p_gtk_render_frame(button_style, cr,
                       allocation.x, allocation.y, allocation.width, allocation.height);
    p_gtk_style_context_restore(button_style);

    int icon_width;
    int icon_height;
    if (!p_gtk_icon_size_lookup(GTK_ICON_SIZE_MENU, &icon_width, &icon_height)) {
        icon_width = 16;
        icon_height = 16;
    }
    GtkIconInfo* icon_info = p_gtk_icon_theme_lookup_icon_for_scale(
        p_gtk_icon_theme_get_default(), icon_name, icon_width, (int) scale, 0);
    p_gtk_style_context_save(button_style);
    p_gtk_style_context_set_state(button_style, style_state);
    GdkPixbuf* icon_pixbuf = p_gtk_icon_info_load_symbolic_for_context(icon_info, button_style, NULL, NULL);
    cairo_surface_t* icon_surface = p_gdk_cairo_surface_create_from_pixbuf(icon_pixbuf, (int) scale, NULL);
    p_gtk_style_context_restore(button_style);

    int width = 0;
    int height = 0;
    p_gtk_style_context_get(button_style, p_gtk_style_context_get_state(button_style),
                            "min-width", &width, "min-height", &height, NULL);

    if (width < icon_width)
        width = icon_width;
    if (height < icon_height)
        height = icon_height;

    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
    GtkBorder border;
    GtkBorder padding;

    p_gtk_style_context_get_border(button_style, p_gtk_style_context_get_state(button_style), &border);
    left += border.left;
    right += border.right;
    top += border.top;
    bottom += border.bottom;

    p_gtk_style_context_get_padding(button_style, p_gtk_style_context_get_state(button_style), &padding);
    left += padding.left;
    right += padding.right;
    top += padding.top;
    bottom += padding.bottom;

    width += left + right;
    height += top + bottom;

    int offset_x = (width - icon_width + 0.5) / 2;
    int offset_y = (height - icon_height + 0.5) / 2;
    GtkWidget *icon_widget = p_gtk_bin_get_child(GTK_BIN(button));
    p_gtk_render_icon_surface(p_gtk_widget_get_style_context(icon_widget),
                              cr, icon_surface, allocation.x + offset_x, allocation.y + offset_y);
    p_cairo_paint(cr);
    p_cairo_surface_destroy(icon_surface);
    p_g_object_unref(icon_pixbuf);
}

static void draw_titlebar_buttons(GtkFrameDecorationDescr* decor, cairo_surface_t * surface, cairo_t *cr,
                                  double scale, int buttonsState) {
    if (decor->show_minimize) {
        jboolean hovered = buttonsState & sun_awt_wl_GtkFrameDecoration_MIN_BUTTON_STATE_HOVERED;
        jboolean pressed = buttonsState & sun_awt_wl_GtkFrameDecoration_MIN_BUTTON_STATE_PRESSED;
        draw_titlebar_button(decor, surface, cr, scale, hovered, pressed,
                             ".minimize", "window-minimize-symbolic");
    }

    if (decor->show_maximize) {
        jboolean hovered = buttonsState & sun_awt_wl_GtkFrameDecoration_MAX_BUTTON_STATE_HOVERED;
        jboolean pressed = buttonsState & sun_awt_wl_GtkFrameDecoration_MAX_BUTTON_STATE_PRESSED;
        draw_titlebar_button(decor, surface, cr, scale, hovered, pressed,
                             ".maximize",
                             decor->is_maximized ? "window-restore-symbolic" : "window-maximize-symbolic");
    }

    jboolean hovered = buttonsState & sun_awt_wl_GtkFrameDecoration_CLOSE_BUTTON_STATE_HOVERED;
    jboolean pressed = buttonsState & sun_awt_wl_GtkFrameDecoration_CLOSE_BUTTON_STATE_PRESSED;
    draw_titlebar_button(decor, surface, cr, scale, hovered, pressed,
                         ".close", "window-close-symbolic");
}

static void draw_title_bar(GtkFrameDecorationDescr* decor, cairo_surface_t * surface, cairo_t *cr,
                           int width, int height, double scale, const char *title, int buttonsState) {
    GtkStyleContext *style = p_gtk_widget_get_style_context(decor->window);

    if (!decor->is_active) {
        p_gtk_widget_set_state_flags(decor->window, GTK_STATE_FLAG_BACKDROP, true);
    } else {
        p_gtk_widget_unset_state_flags(decor->window, GTK_STATE_FLAG_BACKDROP);
    }

    if (decor->is_maximized) {
        p_gtk_style_context_add_class(style, "maximized");
    } else {
        p_gtk_style_context_remove_class(style, "maximized");
    }

    p_gtk_widget_show_all(decor->window);

    p_gtk_header_bar_set_title(GTK_HEADER_BAR(decor->titlebar), title);
    GtkAllocation allocation = {0, 0, width, height};
    p_gtk_widget_size_allocate(decor->titlebar, &allocation);

    draw_titlebar_background(decor, cr);
    draw_titlebar_title(decor, surface);
    draw_titlebar_buttons(decor, surface, cr, scale, buttonsState);
}

static void set_theme(jboolean is_dark_theme) {
    p_g_object_set(p_gtk_settings_get_default(),
                   "gtk-application-prefer-dark-theme",
                   is_dark_theme,
                   NULL);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_initIDs(JNIEnv *env, jclass clazz) {
    CHECK_NULL_THROW_IE(env,
                        CloseButtonBoundsFID = (*env)->GetFieldID(env, clazz, "closeButtonBounds", "Ljava/awt/Rectangle;"),
                        "Failed to find field closeButtonBounds");
    CHECK_NULL_THROW_IE(env,
                        MinButtonBoundsFID = (*env)->GetFieldID(env, clazz, "minimizeButtonBounds", "Ljava/awt/Rectangle;"),
                        "Failed to find field minimizeButtonBounds");
    CHECK_NULL_THROW_IE(env,
                        MaxButtonBoundsFID = (*env)->GetFieldID(env, clazz, "maximizeButtonBounds", "Ljava/awt/Rectangle;"),
                        "Failed to find field maximizeButtonBounds");
    CHECK_NULL_THROW_IE(env,
                        TitleBarHeightFID = (*env)->GetFieldID(env, clazz, "titleBarHeight", "I"),
                        "Failed to find field titleBarHeightBounds");
    CHECK_NULL_THROW_IE(env,
                        TitleBarMinWidthFID = (*env)->GetFieldID(env, clazz, "titleBarMinWidth", "I"),
                        "Failed to find field titleBarMinWidth");
}

JNIEXPORT jboolean JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeLoadGTK(JNIEnv *env, jclass clazz) {
    return load_gtk(env);
}

JNIEXPORT jlong JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeCreateDecoration
(JNIEnv *env, jobject obj, jboolean show_minimize, jboolean show_maximize, jboolean is_dark_theme) {
    GtkFrameDecorationDescr *d = calloc(1, sizeof(GtkFrameDecorationDescr));
    CHECK_NULL_THROW_OOME_RETURN(env, d, "Failed to allocate GtkFrameDeocration", 0);

    p_gdk_threads_enter();

    set_theme(is_dark_theme);

    d->show_minimize = show_minimize;
    d->show_maximize = show_maximize;
    d->window = p_gtk_offscreen_window_new();
    d->titlebar = p_gtk_header_bar_new();
    p_g_object_set(d->titlebar,
                   "title", "Default Title",
                   "has-subtitle", false,
                   "show-close-button", true,
                   NULL);
    GtkStyleContext *context_hdr = p_gtk_widget_get_style_context(d->titlebar);
    p_gtk_style_context_add_class(context_hdr, GTK_STYLE_CLASS_TITLEBAR);
    p_gtk_style_context_add_class(context_hdr, "default-decoration");
    p_gtk_window_set_titlebar(GTK_WINDOW(d->window), d->titlebar);
    p_gtk_header_bar_set_show_close_button(GTK_HEADER_BAR(d->titlebar), true);
    p_gtk_window_set_resizable(GTK_WINDOW(d->window), true);

    p_gdk_threads_leave();

    return ptr_to_jlong(d);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeDestroyDecoration
        (JNIEnv *env, jobject obj, jlong ptr) {
    assert (ptr != 0);
    GtkFrameDecorationDescr* decor = jlong_to_ptr(ptr);

    p_gdk_threads_enter();

    p_gtk_widget_destroy(decor->titlebar);
    p_gtk_widget_destroy(decor->window);

    p_gdk_threads_leave();

    free(decor);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativePaintTitleBar
        (JNIEnv *env, jobject obj, jlong ptr, jintArray dest,
         jint width, jint height, jdouble scale, jstring title, jint buttonsState) {
    GtkFrameDecorationDescr* decor = jlong_to_ptr(ptr);

    jint pixel_width = ceil(width * scale);
    jint pixel_height = ceil(height * scale);

    unsigned char *buffer = (*env)->GetPrimitiveArrayCritical(env, dest, 0);
    if (!buffer) {
        JNU_ThrowOutOfMemoryError(env, "Could not get image buffer");
        return;
    }

    p_gdk_threads_enter();

    cairo_surface_t *surface = p_cairo_image_surface_create_for_data(
        buffer, CAIRO_FORMAT_ARGB32,
        pixel_width, pixel_height,
        p_cairo_format_stride_for_width(CAIRO_FORMAT_ARGB32, pixel_width));

    p_cairo_surface_set_device_scale(surface, scale, scale);

    cairo_t *cr = p_cairo_create(surface);

    jboolean is_copy = JNI_FALSE;
    const char *title_c_str = "";
    if (title) {
        title_c_str = JNU_GetStringPlatformChars(env, title, &is_copy);
        if (!title_c_str)
            return;
    }
    draw_title_bar(decor, surface, cr, width, height, scale, title_c_str, buttonsState);

    if (is_copy) {
        JNU_ReleaseStringPlatformChars(env, title, title_c_str);
    }

    // Make sure pixels have been flush into the underlying buffer
    p_cairo_surface_flush(surface);

    p_gdk_threads_leave();

    (*env)->ReleasePrimitiveArrayCritical(env, dest, buffer, 0);
    p_cairo_destroy(cr);
    p_cairo_surface_destroy(surface);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativePrePaint(JNIEnv *env, jobject obj,
                                                                         jlong ptr, jint width, jint height) {
    assert (ptr != 0);
    GtkFrameDecorationDescr* decor = jlong_to_ptr(ptr);

    p_gdk_threads_enter();

    if (decor->is_active) {
        p_gtk_widget_set_state_flags(decor->window, GTK_STATE_FLAG_BACKDROP, true);
    } else {
        p_gtk_widget_unset_state_flags(decor->window, GTK_STATE_FLAG_BACKDROP);
    }

    GtkStyleContext *style = p_gtk_widget_get_style_context(decor->window);
    if (decor->is_maximized) {
        p_gtk_style_context_add_class(style, "maximized");
    } else {
        p_gtk_style_context_remove_class(style, "maximized");
    }

    p_gtk_header_bar_set_title(GTK_HEADER_BAR(decor->titlebar), "Title");
    p_gtk_widget_show_all(decor->window);

    jint pref_height;
    p_gtk_widget_get_preferred_height(decor->titlebar, NULL, &pref_height);
    jint min_width;
    p_gtk_widget_get_preferred_width(decor->titlebar, &min_width, NULL);

    (*env)->SetIntField(env, obj, TitleBarHeightFID, pref_height);
    (*env)->SetIntField(env, obj, TitleBarMinWidthFID, min_width);

    if (width < min_width || height < pref_height) {
        // Avoid gtk warnings in case of insufficient space
        p_gdk_threads_leave();
        return;
    }

    GtkAllocation ha = {0, 0, width, pref_height};
    p_gtk_widget_size_allocate(decor->titlebar, &ha);

    GtkWidget *button = widget_by_name(decor->titlebar, ".close");
    GtkAllocation ba;
    if (button) {
        p_gtk_widget_get_clip(button, &ba);
        jobject recObj = JNU_NewObjectByName(env, "java/awt/Rectangle", "(IIII)V",
                                             ba.x, ba.y, ba.width, ba.height);
        (*env)->SetObjectField(env, obj, CloseButtonBoundsFID, recObj);
    }

    button = widget_by_name(decor->titlebar, ".minimize");
    if (button) {
        p_gtk_widget_get_clip(button, &ba);
        jobject recObj = JNU_NewObjectByName(env, "java/awt/Rectangle", "(IIII)V",
                                             ba.x, ba.y, ba.width, ba.height);
        (*env)->SetObjectField(env, obj, MinButtonBoundsFID, recObj);
    }

    button = widget_by_name(decor->titlebar, ".maximize");
    if (button) {
        p_gtk_widget_get_clip(button, &ba);
        jobject recObj = JNU_NewObjectByName(env, "java/awt/Rectangle", "(IIII)V",
                                             ba.x, ba.y, ba.width, ba.height);
        (*env)->SetObjectField(env, obj, MaxButtonBoundsFID, recObj);
    }

    p_gdk_threads_leave();
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeSwitchTheme(JNIEnv *env, jobject obj, jboolean is_dark_theme) {
    p_gdk_threads_enter();
    set_theme(is_dark_theme);
    p_gdk_threads_leave();
}

JNIEXPORT jint JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeGetIntProperty
        (JNIEnv *env, jobject obj, jlong ptr, jstring name) {
    assert (ptr != 0);
    GtkFrameDecorationDescr* decor = jlong_to_ptr(ptr);

    jboolean is_copy = JNI_FALSE;
    const char *name_c_str = JNU_GetStringPlatformChars(env, name, &is_copy);
    if (!name_c_str)
        return 0;

    jint result;
    p_gdk_threads_enter();

    p_g_object_get(p_gtk_widget_get_settings(decor->window), name_c_str, &result, NULL);

    p_gdk_threads_leave();
    if (is_copy) {
        JNU_ReleaseStringPlatformChars(env, name, name_c_str);
    }

    return result;
}

JNIEXPORT void JNICALL Java_sun_awt_wl_GtkFrameDecoration_nativeNotifyConfigured
        (JNIEnv *env, jobject obj, jlong ptr, jboolean active, jboolean maximized, jboolean fullscreen) {
    assert (ptr != 0);
    GtkFrameDecorationDescr* decor = jlong_to_ptr(ptr);

    decor->is_active = active;
    decor->is_maximized = maximized;
}

