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

#include "jlong.h"
#include "sun_font_SunLayoutEngine.h"

#include "hb.h"
#include "hb-ot.h"
#include "hb-jdk.h"
#include <stdlib.h>
#if !defined(_WIN32) && !defined(__APPLE__)
#include <dlfcn.h>
#endif
#include "jvm_md.h"
#include "jni_util.h"

#define JHARFBUZZ_DLL JNI_LIB_NAME("jharfbuzz")
#define HARFBUZZ_DLL JNI_LIB_NAME("harfbuzz")

#if defined(__GNUC__) &&  __GNUC__ >= 4
#define HB_UNUSED       __attribute__((unused))
#else
#define HB_UNUSED
#endif

p_hb_buffer_create_type p_hb_buffer_create;
p_hb_buffer_set_script_type p_hb_buffer_set_script;
p_hb_buffer_set_language_type p_hb_buffer_set_language;
p_hb_buffer_set_direction_type p_hb_buffer_set_direction;
p_hb_buffer_set_cluster_level_type p_hb_buffer_set_cluster_level;
p_hb_buffer_add_utf16_type p_hb_buffer_add_utf16;
p_hb_feature_from_string_type p_hb_feature_from_string;
p_hb_buffer_get_length_type p_hb_buffer_get_length;
p_hb_buffer_get_glyph_infos_type p_hb_buffer_get_glyph_infos;
p_hb_buffer_get_glyph_positions_type p_hb_buffer_get_glyph_positions;
p_hb_buffer_destroy_type p_hb_buffer_destroy;
p_hb_font_destroy_type p_hb_font_destroy;
p_hb_font_funcs_create_type p_hb_font_funcs_create;
p_hb_font_funcs_set_nominal_glyphs_func_type p_hb_font_funcs_set_nominal_glyphs_func;
p_hb_font_funcs_set_variation_glyph_func_type p_hb_font_funcs_set_variation_glyph_func;
p_hb_font_funcs_set_glyph_h_advance_func_type p_hb_font_funcs_set_glyph_h_advance_func;
p_hb_font_funcs_set_glyph_v_advance_func_type p_hb_font_funcs_set_glyph_v_advance_func;
p_hb_font_funcs_set_glyph_h_origin_func_type p_hb_font_funcs_set_glyph_h_origin_func;
p_hb_font_funcs_set_glyph_v_origin_func_type p_hb_font_funcs_set_glyph_v_origin_func;
p_hb_font_funcs_set_glyph_h_kerning_func_type p_hb_font_funcs_set_glyph_h_kerning_func;
p_hb_font_funcs_set_glyph_v_kerning_func_type p_hb_font_funcs_set_glyph_v_kerning_func;
p_hb_font_funcs_set_glyph_extents_func_type p_hb_font_funcs_set_glyph_extents_func;
p_hb_font_funcs_set_glyph_contour_point_func_type p_hb_font_funcs_set_glyph_contour_point_func;
p_hb_font_funcs_set_glyph_name_func_type p_hb_font_funcs_set_glyph_name_func;
p_hb_font_funcs_set_glyph_from_name_func_type p_hb_font_funcs_set_glyph_from_name_func;
p_hb_font_funcs_make_immutable_type p_hb_font_funcs_make_immutable;
p_hb_blob_create_type p_hb_blob_create;
p_hb_face_create_for_tables_type p_hb_face_create_for_tables;
p_hb_font_create_type p_hb_font_create;
p_hb_font_set_funcs_type p_hb_font_set_funcs;
p_hb_font_set_scale_type p_hb_font_set_scale;
p_hb_shape_full_type p_hb_shape_full;
p_hb_font_funcs_set_nominal_glyph_func_type p_hb_font_funcs_set_nominal_glyph_func;
p_hb_face_destroy_type p_hb_face_destroy;
p_hb_ot_tag_to_language_type p_hb_ot_tag_to_language;

static int initialisedHBAPI = 0;
static int initialisationFailed = 0;

int initHBAPI() {
    if (initialisedHBAPI) {
        return initialisedHBAPI;
    }

    if (initialisationFailed) {
        return 0;
    }

#if !defined(_WIN32) && !defined(__APPLE__)
    void* libharfbuzz = NULL;
    libharfbuzz = dlopen(JHARFBUZZ_DLL, RTLD_LOCAL | RTLD_LAZY);
    if (libharfbuzz == NULL) {
        initialisationFailed = 1;
        CHECK_NULL_RETURN(libharfbuzz = dlopen(HARFBUZZ_DLL, RTLD_LOCAL | RTLD_LAZY), 0);
        initialisationFailed = 0;
    }

    p_hb_buffer_create = (p_hb_buffer_create_type)dlsym(libharfbuzz, "hb_buffer_create");
    p_hb_buffer_set_script = (p_hb_buffer_set_script_type)dlsym(libharfbuzz, "hb_buffer_set_script");
    p_hb_buffer_set_language = (p_hb_buffer_set_language_type)dlsym(libharfbuzz, "hb_buffer_set_language");
    p_hb_buffer_set_direction = (p_hb_buffer_set_direction_type)dlsym(libharfbuzz, "hb_buffer_set_direction");
    p_hb_buffer_set_cluster_level = (p_hb_buffer_set_cluster_level_type)dlsym(libharfbuzz, "hb_buffer_set_cluster_level");
    p_hb_buffer_add_utf16 = (p_hb_buffer_add_utf16_type)dlsym(libharfbuzz, "hb_buffer_add_utf16");
    p_hb_feature_from_string = (p_hb_feature_from_string_type)dlsym(libharfbuzz, "hb_feature_from_string");
    p_hb_buffer_get_length = (p_hb_buffer_get_length_type)dlsym(libharfbuzz, "hb_buffer_get_length");
    p_hb_buffer_get_glyph_infos = (p_hb_buffer_get_glyph_infos_type)dlsym(libharfbuzz, "hb_buffer_get_glyph_infos");
    p_hb_buffer_get_glyph_positions = (p_hb_buffer_get_glyph_positions_type)dlsym(libharfbuzz, "hb_buffer_get_glyph_positions");
    p_hb_buffer_destroy = (p_hb_buffer_destroy_type)dlsym(libharfbuzz, "hb_buffer_destroy");
    p_hb_font_destroy = (p_hb_font_destroy_type)dlsym(libharfbuzz, "hb_font_destroy");
    p_hb_font_funcs_create = (p_hb_font_funcs_create_type)dlsym(libharfbuzz, "hb_font_funcs_create");
    p_hb_font_funcs_set_nominal_glyphs_func =
            (p_hb_font_funcs_set_nominal_glyphs_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_nominal_glyphs_func");
    p_hb_font_funcs_set_nominal_glyph_func = (p_hb_font_funcs_set_nominal_glyph_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_nominal_glyph_func");
    p_hb_font_funcs_set_variation_glyph_func =
            (p_hb_font_funcs_set_variation_glyph_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_variation_glyph_func");
    p_hb_font_funcs_set_glyph_h_advance_func =
            (p_hb_font_funcs_set_glyph_h_advance_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_h_advance_func");
    p_hb_font_funcs_set_glyph_v_advance_func =
            (p_hb_font_funcs_set_glyph_v_advance_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_v_advance_func");
    p_hb_font_funcs_set_glyph_h_origin_func =
            (p_hb_font_funcs_set_glyph_h_origin_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_h_origin_func");
    p_hb_font_funcs_set_glyph_v_origin_func =
            (p_hb_font_funcs_set_glyph_v_origin_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_v_origin_func");
    p_hb_font_funcs_set_glyph_h_kerning_func =
            (p_hb_font_funcs_set_glyph_h_kerning_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_h_kerning_func");
    p_hb_font_funcs_set_glyph_v_kerning_func =
            (p_hb_font_funcs_set_glyph_v_kerning_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_v_kerning_func");
    p_hb_font_funcs_set_glyph_extents_func =
            (p_hb_font_funcs_set_glyph_extents_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_extents_func");
    p_hb_font_funcs_set_glyph_contour_point_func =
            (p_hb_font_funcs_set_glyph_contour_point_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_contour_point_func");
    p_hb_font_funcs_set_glyph_name_func =
            (p_hb_font_funcs_set_glyph_name_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_name_func");
    p_hb_font_funcs_set_glyph_from_name_func =
            (p_hb_font_funcs_set_glyph_from_name_func_type)dlsym(
                    libharfbuzz, "hb_font_funcs_set_glyph_from_name_func");
    p_hb_font_funcs_make_immutable =
            (p_hb_font_funcs_make_immutable_type)dlsym(
                    libharfbuzz, "hb_font_funcs_make_immutable");
    p_hb_blob_create =
            (p_hb_blob_create_type)dlsym(libharfbuzz, "hb_blob_create");
    p_hb_face_create_for_tables =
            (p_hb_face_create_for_tables_type)dlsym(libharfbuzz, "hb_face_create_for_tables");
    p_hb_font_create =
            (p_hb_font_create_type)dlsym(libharfbuzz, "hb_font_create");
    p_hb_font_set_funcs =
            (p_hb_font_set_funcs_type)dlsym(libharfbuzz, "hb_font_set_funcs");
    p_hb_font_set_scale =
            (p_hb_font_set_scale_type)dlsym(libharfbuzz, "hb_font_set_scale");
    p_hb_shape_full = (p_hb_shape_full_type)dlsym(libharfbuzz, "hb_shape_full");
    p_hb_ot_tag_to_language = (p_hb_ot_tag_to_language_type)dlsym(libharfbuzz, "hb_ot_tag_to_language");
#else
    p_hb_buffer_create = hb_buffer_create;
    p_hb_buffer_set_script = hb_buffer_set_script;
    p_hb_buffer_set_language = hb_buffer_set_language;
    p_hb_buffer_set_direction = hb_buffer_set_direction;
    p_hb_buffer_set_cluster_level = hb_buffer_set_cluster_level;
    p_hb_buffer_add_utf16 = hb_buffer_add_utf16;
    p_hb_feature_from_string = hb_feature_from_string;
    p_hb_buffer_get_length = hb_buffer_get_length;
    p_hb_buffer_get_glyph_infos = hb_buffer_get_glyph_infos;
    p_hb_buffer_get_glyph_positions = hb_buffer_get_glyph_positions;
    p_hb_buffer_destroy = hb_buffer_destroy;
    p_hb_font_destroy = hb_font_destroy;
    p_hb_font_funcs_create = hb_font_funcs_create;
    p_hb_font_funcs_set_nominal_glyph_func = hb_font_funcs_set_nominal_glyph_func;
    p_hb_font_funcs_set_nominal_glyphs_func = hb_font_funcs_set_nominal_glyphs_func;
    p_hb_font_funcs_set_variation_glyph_func = hb_font_funcs_set_variation_glyph_func;
    p_hb_font_funcs_set_glyph_h_advance_func = hb_font_funcs_set_glyph_h_advance_func;
    p_hb_font_funcs_set_glyph_v_advance_func = hb_font_funcs_set_glyph_v_advance_func;
    p_hb_font_funcs_set_glyph_h_origin_func = hb_font_funcs_set_glyph_h_origin_func;
    p_hb_font_funcs_set_glyph_v_origin_func = hb_font_funcs_set_glyph_v_origin_func;
    p_hb_font_funcs_set_glyph_h_kerning_func = hb_font_funcs_set_glyph_h_kerning_func;
    p_hb_font_funcs_set_glyph_v_kerning_func = hb_font_funcs_set_glyph_v_kerning_func;
    p_hb_font_funcs_set_glyph_extents_func = hb_font_funcs_set_glyph_extents_func;
    p_hb_font_funcs_set_glyph_contour_point_func = hb_font_funcs_set_glyph_contour_point_func;
    p_hb_font_funcs_set_glyph_name_func = hb_font_funcs_set_glyph_name_func;
    p_hb_font_funcs_set_glyph_from_name_func = hb_font_funcs_set_glyph_from_name_func;
    p_hb_font_funcs_make_immutable = hb_font_funcs_make_immutable;
    p_hb_blob_create = hb_blob_create;
    p_hb_face_create_for_tables = hb_face_create_for_tables;
    p_hb_font_create = hb_font_create;
    p_hb_font_set_funcs = hb_font_set_funcs;
    p_hb_font_set_scale = hb_font_set_scale;
    p_hb_shape_full = hb_shape_full;
    p_hb_ot_tag_to_language = hb_ot_tag_to_language;
#endif

    initialisedHBAPI = 1;
    return initialisedHBAPI;
}

static hb_bool_t
hb_jdk_get_nominal_glyph (hb_font_t *font HB_UNUSED,
		          void *font_data,
		          hb_codepoint_t unicode,
		          hb_codepoint_t *glyph,
		          void *user_data HB_UNUSED)
{

    JDKFontInfo *jdkFontInfo = (JDKFontInfo*)font_data;
    JNIEnv* env = jdkFontInfo->env;
    jobject font2D = jdkFontInfo->font2D;
    *glyph = (hb_codepoint_t)env->CallIntMethod(
              font2D, sunFontIDs.f2dCharToGlyphMID, unicode);
    if (env->ExceptionOccurred())
    {
        env->ExceptionClear();
    }
    if ((int)*glyph < 0) {
        *glyph = 0;
    }
    return (*glyph != 0);
}

static hb_bool_t
hb_jdk_get_variation_glyph (hb_font_t *font HB_UNUSED,
		 void *font_data,
		 hb_codepoint_t unicode,
		 hb_codepoint_t variation_selector,
		 hb_codepoint_t *glyph,
		 void *user_data HB_UNUSED)
{

    JDKFontInfo *jdkFontInfo = (JDKFontInfo*)font_data;
    JNIEnv* env = jdkFontInfo->env;
    jobject font2D = jdkFontInfo->font2D;
    *glyph = (hb_codepoint_t)env->CallIntMethod(
              font2D, sunFontIDs.f2dCharToVariationGlyphMID, 
              unicode, variation_selector);
    if (env->ExceptionOccurred())
    {
        env->ExceptionClear();
    }
    if ((int)*glyph < 0) {
        *glyph = 0;
    }
    return (*glyph != 0);
}

static hb_position_t
hb_jdk_get_glyph_h_advance (hb_font_t *font HB_UNUSED,
			   void *font_data,
			   hb_codepoint_t glyph,
			   void *user_data HB_UNUSED)
{

    float fadv = 0.0f;
    if ((glyph & 0xfffe) == 0xfffe) {
        return 0; // JDK uses this glyph code.
    }

    JDKFontInfo *jdkFontInfo = (JDKFontInfo*)font_data;
    JNIEnv* env = jdkFontInfo->env;
    jobject fontStrike = jdkFontInfo->fontStrike;
    jobject pt = env->CallObjectMethod(fontStrike,
                                       sunFontIDs.getGlyphMetricsMID, glyph);

    if (pt == NULL) {
        return 0;
    }
    fadv = env->GetFloatField(pt, sunFontIDs.xFID);
    fadv *= jdkFontInfo->devScale;
    env->DeleteLocalRef(pt);

    return HBFloatToFixed(fadv);
}

static hb_position_t
hb_jdk_get_glyph_v_advance (hb_font_t *font HB_UNUSED,
			   void *font_data,
			   hb_codepoint_t glyph,
			   void *user_data HB_UNUSED)
{

    float fadv = 0.0f;
    if ((glyph & 0xfffe) == 0xfffe) {
        return 0; // JDK uses this glyph code.
    }

    JDKFontInfo *jdkFontInfo = (JDKFontInfo*)font_data;
    JNIEnv* env = jdkFontInfo->env;
    jobject fontStrike = jdkFontInfo->fontStrike;
    jobject pt = env->CallObjectMethod(fontStrike,
                                       sunFontIDs.getGlyphMetricsMID, glyph);

    if (pt == NULL) {
        return 0;
    }
    fadv = env->GetFloatField(pt, sunFontIDs.yFID);
    env->DeleteLocalRef(pt);

    return HBFloatToFixed(fadv);

}

static hb_bool_t
hb_jdk_get_glyph_h_origin (hb_font_t *font HB_UNUSED,
			  void *font_data HB_UNUSED,
			  hb_codepoint_t glyph HB_UNUSED,
			  hb_position_t *x HB_UNUSED,
			  hb_position_t *y HB_UNUSED,
			  void *user_data HB_UNUSED)
{
  /* We always work in the horizontal coordinates. */
  return true;
}

static hb_bool_t
hb_jdk_get_glyph_v_origin (hb_font_t *font HB_UNUSED,
			  void *font_data,
			  hb_codepoint_t glyph,
			  hb_position_t *x,
			  hb_position_t *y,
			  void *user_data HB_UNUSED)
{
  return false;
}

static hb_position_t
hb_jdk_get_glyph_h_kerning (hb_font_t *font,
			   void *font_data,
			   hb_codepoint_t lejdk_glyph,
			   hb_codepoint_t right_glyph,
			   void *user_data HB_UNUSED)
{
  /* Not implemented. This seems to be in the HB API
   * as a way to fall back to Freetype's kerning support
   * which could be based on some on-the fly glyph analysis.
   * But more likely it reads the kern table. That is easy
   * enough code to add if we find a need to fall back
   * to that instead of using gpos. It seems like if
   * there is a gpos table at all, the practice is to
   * use that and ignore kern, no matter that gpos does
   * not implement the kern feature.
   */
  return 0;
}

static hb_position_t
hb_jdk_get_glyph_v_kerning (hb_font_t *font HB_UNUSED,
			   void *font_data HB_UNUSED,
			   hb_codepoint_t top_glyph HB_UNUSED,
			   hb_codepoint_t bottom_glyph HB_UNUSED,
			   void *user_data HB_UNUSED)
{
  /* OpenType doesn't have vertical-kerning other than GPOS. */
  return 0;
}

static hb_bool_t
hb_jdk_get_glyph_extents (hb_font_t *font HB_UNUSED,
			 void *font_data,
			 hb_codepoint_t glyph,
			 hb_glyph_extents_t *extents,
			 void *user_data HB_UNUSED)
{
  /* TODO */
  return false;
}

static hb_bool_t
hb_jdk_get_glyph_contour_point (hb_font_t *font HB_UNUSED,
			       void *font_data,
			       hb_codepoint_t glyph,
			       unsigned int point_index,
			       hb_position_t *x,
			       hb_position_t *y,
			       void *user_data HB_UNUSED)
{
    if ((glyph & 0xfffe) == 0xfffe) {
        *x = 0; *y = 0;
        return true;
    }

    JDKFontInfo *jdkFontInfo = (JDKFontInfo*)font_data;
    JNIEnv* env = jdkFontInfo->env;
    jobject fontStrike = jdkFontInfo->fontStrike;
    jobject pt = env->CallObjectMethod(fontStrike,
                                       sunFontIDs.getGlyphPointMID,
                                       glyph, point_index);

    if (pt == NULL) {
        *x = 0; *y = 0;
        return true;
    }
    *x = HBFloatToFixed(env->GetFloatField(pt, sunFontIDs.xFID));
    *y = HBFloatToFixed(env->GetFloatField(pt, sunFontIDs.yFID));
    env->DeleteLocalRef(pt);

  return true;
}

static hb_bool_t
hb_jdk_get_glyph_name (hb_font_t *font HB_UNUSED,
		      void *font_data,
		      hb_codepoint_t glyph,
		      char *name, unsigned int size,
		      void *user_data HB_UNUSED)
{
  return false;
}

static hb_bool_t
hb_jdk_get_glyph_from_name (hb_font_t *font HB_UNUSED,
			   void *font_data,
			   const char *name, int len,
			   hb_codepoint_t *glyph,
			   void *user_data HB_UNUSED)
{
  return false;
}

// remind : can we initialise this from the code we call
// from the class static method in Java to make it
// completely thread safe.
static hb_font_funcs_t *
_hb_jdk_get_font_funcs (void)
{
  static hb_font_funcs_t *jdk_ffuncs = NULL;
  hb_font_funcs_t *ff;

  if (!jdk_ffuncs) {
      ff = p_hb_font_funcs_create();

      p_hb_font_funcs_set_nominal_glyph_func(ff, hb_jdk_get_nominal_glyph, NULL, NULL);
      p_hb_font_funcs_set_variation_glyph_func(ff, hb_jdk_get_variation_glyph, NULL, NULL);
      p_hb_font_funcs_set_glyph_h_advance_func(ff,
                    hb_jdk_get_glyph_h_advance, NULL, NULL);
      p_hb_font_funcs_set_glyph_v_advance_func(ff,
                    hb_jdk_get_glyph_v_advance, NULL, NULL);
      p_hb_font_funcs_set_glyph_h_origin_func(ff,
                    hb_jdk_get_glyph_h_origin, NULL, NULL);
      p_hb_font_funcs_set_glyph_v_origin_func(ff,
                    hb_jdk_get_glyph_v_origin, NULL, NULL);
      p_hb_font_funcs_set_glyph_h_kerning_func(ff,
                    hb_jdk_get_glyph_h_kerning, NULL, NULL);
      p_hb_font_funcs_set_glyph_v_kerning_func(ff,
                    hb_jdk_get_glyph_v_kerning, NULL, NULL);
      p_hb_font_funcs_set_glyph_extents_func(ff,
                    hb_jdk_get_glyph_extents, NULL, NULL);
      p_hb_font_funcs_set_glyph_contour_point_func(ff,
                    hb_jdk_get_glyph_contour_point, NULL, NULL);
      p_hb_font_funcs_set_glyph_name_func(ff,
                    hb_jdk_get_glyph_name, NULL, NULL);
      p_hb_font_funcs_set_glyph_from_name_func(ff,
                    hb_jdk_get_glyph_from_name, NULL, NULL);
      p_hb_font_funcs_make_immutable(ff); // done setting functions.
      jdk_ffuncs = ff;
  }
  return jdk_ffuncs;
}

static void _do_nothing(void) {
}

static void _free_nothing(void*) {
}

struct Font2DPtr {
    JavaVM* vmPtr;
    jweak font2DRef;
};

static void cleanupFontInfo(void* data) {
  Font2DPtr* fontInfo;
  JNIEnv* env;

  fontInfo = (Font2DPtr*) data;
  fontInfo->vmPtr->GetEnv((void**)&env, JNI_VERSION_1_1);
  env->DeleteWeakGlobalRef(fontInfo->font2DRef);
  free(data);
}

static hb_blob_t *
reference_table(hb_face_t *face HB_UNUSED, hb_tag_t tag, void *user_data) {

  Font2DPtr *fontInfo;
  JNIEnv* env;
  jobject font2D;
  jsize length;
  void* buffer;

  // HB_TAG_NONE is 0 and is used to get the whole font file.
  // It is not expected to be needed for JDK.
  if (tag == 0) {
      return NULL;
  }

  fontInfo = (Font2DPtr*)user_data;
  fontInfo->vmPtr->GetEnv((void**)&env, JNI_VERSION_1_1);
  if (env == NULL) {
    return NULL;
  }
  font2D = fontInfo->font2DRef;

  jbyteArray tableBytes = (jbyteArray)
     env->CallObjectMethod(font2D, sunFontIDs.getTableBytesMID, tag);
  if (tableBytes == NULL) {
      return NULL;
  }
  length = env->GetArrayLength(tableBytes);
  buffer = calloc(length, sizeof(jbyte));
  if (buffer == NULL) {
      return NULL;
  }
  env->GetByteArrayRegion(tableBytes, 0, length, (jbyte*)buffer);

  return p_hb_blob_create((const char *)buffer, length,
                         HB_MEMORY_MODE_WRITABLE,
                         buffer, free);
}

extern "C" {

/*
 * Class:     sun_font_SunLayoutEngine
 * Method:    createFace
 * Signature: (Lsun/font/Font2D;ZJJ)J
 */
JNIEXPORT jlong JNICALL Java_sun_font_SunLayoutEngine_createFace(JNIEnv *env,
                         jclass cls,
                         jobject font2D,
                         jboolean aat,
                         jlong platformFontPtr) {
    Font2DPtr *fi = (Font2DPtr*)malloc(sizeof(Font2DPtr));
    if (!fi) {
        return 0;
    }
    JavaVM* vmPtr;
    env->GetJavaVM(&vmPtr);
    fi->vmPtr = vmPtr;
    fi->font2DRef = env->NewWeakGlobalRef(font2D);
    if (!fi->font2DRef) {
        free(fi);
        return 0;
    }
    hb_face_t *face = p_hb_face_create_for_tables(reference_table, fi,
                                                cleanupFontInfo);
    return ptr_to_jlong(face);
}

/*
 * Class:     sun_font_SunLayoutEngine
 * Method:    disposeFace
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_font_SunLayoutEngine_disposeFace(JNIEnv *env,
                        jclass cls,
                        jlong ptr) {
    hb_face_t* face = (hb_face_t*) jlong_to_ptr(ptr);
    p_hb_face_destroy(face);
}

} // extern "C"

static hb_font_t* _hb_jdk_font_create(hb_face_t* face,
                                      JDKFontInfo *jdkFontInfo,
                                      hb_destroy_func_t destroy) {

    hb_font_t *font;

    font = p_hb_font_create(face);
    p_hb_font_set_funcs (font,
                       _hb_jdk_get_font_funcs (),
                       jdkFontInfo, (hb_destroy_func_t) _do_nothing);
    p_hb_font_set_scale (font,
                      HBFloatToFixed(jdkFontInfo->ptSize*jdkFontInfo->devScale),
                      HBFloatToFixed(jdkFontInfo->ptSize*jdkFontInfo->devScale));
  return font;
}

hb_font_t* hb_jdk_font_create(hb_face_t* hbFace,
                             JDKFontInfo *jdkFontInfo,
                             hb_destroy_func_t destroy) {
    return _hb_jdk_font_create(hbFace, jdkFontInfo, destroy);
}
