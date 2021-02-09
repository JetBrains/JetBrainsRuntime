/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

#ifndef HB_JDK_H
#define HB_JDK_H

#include "hb.h"
#include <jni.h>
#include <sunfontids.h>

# ifdef __cplusplus
extern "C" {
#endif

typedef struct JDKFontInfo_Struct {
    JNIEnv* env;
    jobject font2D;
    jobject fontStrike;
    long nativeFont;
    float matrix[4];
    float ptSize;
    float xPtSize;
    float yPtSize;
    float devScale; // How much applying the full glyph tx scales x distance.
    jboolean aat;
} JDKFontInfo;


// Use 16.16 for better precision than 26.6
#define HBFloatToFixedScale ((float)(1 << 16))
#define HBFloatToFixed(f) ((unsigned int)((f) * HBFloatToFixedScale))

/*
 * Note:
 *
 * Set face size on ft-face before creating hb-font from it.
 * Otherwise hb-ft would NOT pick up the font size correctly.
 */

hb_face_t *
hb_jdk_face_create(JDKFontInfo*   jdkFontInfo,
                   hb_destroy_func_t destroy);
hb_font_t *
hb_jdk_font_create(hb_face_t* hbFace,
                   JDKFontInfo*   jdkFontInfo,
                   hb_destroy_func_t destroy);


/* Makes an hb_font_t use JDK internally to implement font functions. */
void
hb_jdk_font_set_funcs(hb_font_t *font);

typedef hb_buffer_t* (*p_hb_buffer_create_type) ();
typedef void (*p_hb_buffer_set_script_type) (hb_buffer_t *buffer,
                                             hb_script_t script);

typedef void (*p_hb_buffer_set_language_type) (hb_buffer_t *buffer,
                                               hb_language_t language);

typedef void (*p_hb_buffer_set_direction_type) (hb_buffer_t *buffer,
                                                hb_direction_t direction);

typedef void (*p_hb_buffer_set_cluster_level_type) (hb_buffer_t *buffer,
                                                    hb_buffer_cluster_level_t  cluster_level);

typedef void (*p_hb_buffer_add_utf16_type) (hb_buffer_t *buffer,
                                            const uint16_t *text,
                                            int text_length,
                                            unsigned int item_offset,
                                            int item_length);

typedef hb_bool_t (*p_hb_feature_from_string_type) (const char *str,
                                                    int len,
                                                    hb_feature_t *feature);

typedef unsigned int (*p_hb_buffer_get_length_type) (hb_buffer_t *buffer);

typedef hb_glyph_info_t* (*p_hb_buffer_get_glyph_infos_type) (hb_buffer_t  *buffer,
                                                              unsigned int *length);

typedef hb_glyph_position_t* (*p_hb_buffer_get_glyph_positions_type) (hb_buffer_t  *buffer,
                                                                      unsigned int *length);

typedef void (*p_hb_buffer_destroy_type) (hb_buffer_t *buffer);

typedef void (*p_hb_font_destroy_type) (hb_font_t *font);

typedef hb_font_funcs_t* (*p_hb_font_funcs_create_type) ();

typedef void (*p_hb_font_funcs_set_nominal_glyphs_func_type) (hb_font_funcs_t *ffuncs,
                                       hb_font_get_nominal_glyphs_func_t func,
                                       void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_variation_glyph_func_type) (hb_font_funcs_t *ffuncs,
                                        hb_font_get_variation_glyph_func_t func,
                                        void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_h_advance_func_type) (hb_font_funcs_t *ffuncs,
                                        hb_font_get_glyph_h_advance_func_t func,
                                        void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_v_advance_func_type) (hb_font_funcs_t *ffuncs,
                                        hb_font_get_glyph_v_advance_func_t func,
                                        void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_h_origin_func_type) (hb_font_funcs_t *ffuncs,
                                       hb_font_get_glyph_h_origin_func_t func,
                                       void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_v_origin_func_type) (hb_font_funcs_t *ffuncs,
                                       hb_font_get_glyph_v_origin_func_t func,
                                       void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_h_kerning_func_type) (hb_font_funcs_t *ffuncs,
                                        hb_font_get_glyph_h_kerning_func_t func,
                                        void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_v_kerning_func_type) (hb_font_funcs_t *ffuncs,
                                        hb_font_get_glyph_v_kerning_func_t func,
                                        void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_extents_func_type) (hb_font_funcs_t *ffuncs,
                                      hb_font_get_glyph_extents_func_t func,
                                      void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_contour_point_func_type) (hb_font_funcs_t *ffuncs,
                                            hb_font_get_glyph_contour_point_func_t func,
                                            void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_name_func_type) (hb_font_funcs_t *ffuncs,
                                   hb_font_get_glyph_name_func_t func,
                                   void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_set_glyph_from_name_func_type) (hb_font_funcs_t *ffuncs,
                                        hb_font_get_glyph_from_name_func_t func,
                                        void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_font_funcs_make_immutable_type) (hb_font_funcs_t *ffuncs);

typedef hb_blob_t * (*p_hb_blob_create_type) (const char        *data,
                unsigned int       length,
                hb_memory_mode_t   mode,
                void              *user_data,
                hb_destroy_func_t  destroy);

typedef hb_face_t *
(*p_hb_face_create_for_tables_type) (hb_reference_table_func_t  reference_table_func,
                           void                      *user_data,
                           hb_destroy_func_t          destroy);

typedef hb_font_t * (*p_hb_font_create_type) (hb_face_t *face);

typedef void (*p_hb_font_set_funcs_type) (hb_font_t         *font,
                   hb_font_funcs_t   *klass,
                   void              *font_data,
                   hb_destroy_func_t  destroy);

typedef void (*p_hb_font_set_scale_type) (hb_font_t *font,
                   int x_scale,
                   int y_scale);

typedef hb_bool_t (*p_hb_shape_full_type) (hb_font_t          *font,
               hb_buffer_t        *buffer,
               const hb_feature_t *features,
               unsigned int        num_features,
               const char * const *shaper_list);

typedef void (*p_hb_font_funcs_set_nominal_glyph_func_type) (hb_font_funcs_t *ffuncs,
                                      hb_font_get_nominal_glyph_func_t func,
                                      void *user_data, hb_destroy_func_t destroy);

typedef void (*p_hb_face_destroy_type) (hb_face_t *face);

typedef hb_language_t (*p_hb_ot_tag_to_language_type) (hb_tag_t tag);

extern p_hb_buffer_create_type p_hb_buffer_create;
extern p_hb_buffer_set_script_type p_hb_buffer_set_script;
extern p_hb_buffer_set_language_type p_hb_buffer_set_language;
extern p_hb_buffer_set_direction_type p_hb_buffer_set_direction;
extern p_hb_buffer_set_cluster_level_type p_hb_buffer_set_cluster_level;
extern p_hb_buffer_add_utf16_type p_hb_buffer_add_utf16;
extern p_hb_feature_from_string_type p_hb_feature_from_string;
extern p_hb_buffer_get_length_type p_hb_buffer_get_length;
extern p_hb_buffer_get_glyph_infos_type p_hb_buffer_get_glyph_infos;
extern p_hb_buffer_get_glyph_positions_type p_hb_buffer_get_glyph_positions;
extern p_hb_buffer_destroy_type p_hb_buffer_destroy;
extern p_hb_font_destroy_type p_hb_font_destroy;
extern p_hb_font_funcs_create_type p_hb_font_funcs_create;
extern p_hb_font_funcs_set_nominal_glyphs_func_type p_hb_font_funcs_set_nominal_glyphs_func;
extern p_hb_font_funcs_set_variation_glyph_func_type p_hb_font_funcs_set_variation_glyph_func;
extern p_hb_font_funcs_set_glyph_h_advance_func_type p_hb_font_funcs_set_glyph_h_advance_func;
extern p_hb_font_funcs_set_glyph_v_advance_func_type p_hb_font_funcs_set_glyph_v_advance_func;
extern p_hb_font_funcs_set_glyph_h_origin_func_type p_hb_font_funcs_set_glyph_h_origin_func;
extern p_hb_font_funcs_set_glyph_v_origin_func_type p_hb_font_funcs_set_glyph_v_origin_func;
extern p_hb_font_funcs_set_glyph_h_kerning_func_type p_hb_font_funcs_set_glyph_h_kerning_func;
extern p_hb_font_funcs_set_glyph_v_kerning_func_type p_hb_font_funcs_set_glyph_v_kerning_func;
extern p_hb_font_funcs_set_glyph_extents_func_type p_hb_font_funcs_set_glyph_extents_func;
extern p_hb_font_funcs_set_glyph_contour_point_func_type p_hb_font_funcs_set_glyph_contour_point_func;
extern p_hb_font_funcs_set_glyph_name_func_type p_hb_font_funcs_set_glyph_name_func;
extern p_hb_font_funcs_set_glyph_from_name_func_type p_hb_font_funcs_set_glyph_from_name_func;
extern p_hb_font_funcs_make_immutable_type p_hb_font_funcs_make_immutable;
extern p_hb_blob_create_type p_hb_blob_create;
extern p_hb_face_create_for_tables_type p_hb_face_create_for_tables;
extern p_hb_font_create_type p_hb_font_create;
extern p_hb_font_set_funcs_type p_hb_font_set_funcs;
extern p_hb_font_set_scale_type p_hb_font_set_scale;
extern p_hb_shape_full_type p_hb_shape_full;
extern p_hb_font_funcs_set_nominal_glyph_func_type p_hb_font_funcs_set_nominal_glyph_func;
extern p_hb_face_destroy_type p_hb_face_destroy;
extern p_hb_ot_tag_to_language_type p_hb_ot_tag_to_language;

void initHBAPI();

# ifdef __cplusplus
}
#endif

#endif /* HB_JDK_H */
