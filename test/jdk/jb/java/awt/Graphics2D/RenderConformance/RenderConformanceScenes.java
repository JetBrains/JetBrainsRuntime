/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.awt.AlphaComposite;
import java.awt.CompositeContext;
import java.awt.Image;
import java.awt.PaintContext;
import java.awt.Transparency;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The scenes of the render conformance test: what each one draws and how closely a backend has to match its golden.
 * A scene is a 64x64 scene (a paint, a shape, a composite, a transform, a clip, antialiasing) drawn over a
 * light field with a dark band, so that alpha, XOR and Clear show. Scenes are grouped into sheets; a scene's
 * name is its key in the golden index, so scenes can be added without moving the others' goldens. The
 * translucent sheets draw on a translucent surface, over a half-transparent field with a transparent stripe.
 */
final class RenderConformanceScenes {
    static final int SCENE_SIZE = 64;
    static final int COLUMNS = 16;

    enum Paint { SOLID, SOLID_A50, GRADIENT, GRADIENT_CYCLIC, LIN3_NOCYCLE, LIN3_REFLECT, LIN3_REPEAT, LIN3_PARTIAL,
                 LIN_ALPHA, RADIAL, RADIAL_FOCUS, RADIAL_REPEAT, TEXTURE, LIN_MANY_STOPS, LIN3_LINEAR_RGB,
                 GRADIENT_DEGENERATE, TEXTURE_BILINEAR, CUSTOM }
    enum Shape { FILL_RECT, DRAW_RECT, DRAW_LINE, POLYLINE, FILL_OVAL, DRAW_OVAL, FILL_RRECT, DRAW_RRECT_W3, STROKE_W5,
                 DASHED, FILL_PATH, TEXT_GRAY, TEXT_LCD, TEXT_NOAA, IMAGE, IMAGE_SCALED, IMAGE_ROT, COPY_AREA, CLEAR_RECT,
                 STROKE_RECT_W4, TEXT_LCD_HBGR, TEXT_LCD_VRGB, TEXT_LCD_VBGR, TEXT_LCD_FRACTIONAL, TEXT_GRAY_FRACTIONAL,
                 TEXT_LARGE, TEXT_COLOR, IMAGE_FORMATS, IMAGE_BACKGROUND, IMAGE_SCALED_BILINEAR, IMAGE_ROT_BILINEAR,
                 IMAGE_ROT_BICUBIC, IMAGE_VOLATILE }
    enum Composite { SRC_OVER, ALPHA50, SRC, XOR, CLEAR, SRC_IN,
                     DST, DST_OVER, SRC_OUT, DST_IN, DST_OUT, SRC_ATOP, DST_ATOP, XOR_RULE, SRC_ALPHA50, CUSTOM }
    enum Transform { ID, TRANSLATE_HALF, SCALE_2, SCALE_1_5, ROTATE_30, SHEAR }
    enum Clip { NONE, RECT, ELLIPSE, TWO_RECTS }

    /**
     * The sheet a scene's golden is on. Text sheets are per platform (glyph rasterization differs); the color text
     * sheets draw a system font (no color font ships with the runtime), so their goldens are the software loops'
     * output of the same run instead of files.
     */
    enum Sheet {
        PAINTS, STROKES, COMPOSITES, TRANSFORMS, CLIPS, TEXT, TRANSLUCENT, TRANSLUCENT_TEXT, COLOR_TEXT,
        TRANSLUCENT_COLOR_TEXT;

        String fileName() {
            return name().toLowerCase();
        }

        boolean perPlatform() {
            return this == TEXT || this == TRANSLUCENT_TEXT;
        }

        /** Whether the golden is rendered by the software loops in the same run (no golden file). */
        boolean live() {
            return this == COLOR_TEXT || this == TRANSLUCENT_COLOR_TEXT;
        }

        /** Whether the sheet is drawn on a translucent surface (an opaque one otherwise). */
        boolean translucent() {
            return this == TRANSLUCENT || this == TRANSLUCENT_TEXT || this == TRANSLUCENT_COLOR_TEXT;
        }
    }

    record Scene(String name, Sheet sheet, Paint paint, Shape shape, Composite composite, Transform transform,
                Clip clip, boolean antialiased, boolean pureStroke) {

        boolean isText() {
            return shape.name().startsWith("TEXT_");
        }

        boolean isImage() {
            return shape.name().startsWith("IMAGE");
        }
    }

    /**
     * How closely a backend has to match a scene's golden. The allowance is per pixel and derived from the
     * golden: pixels on an edge (a 3x3 neighborhood with contrast in the golden) get {@code edge} levels per
     * channel, the others {@code interior}. Up to {@code budget} pixels may exceed their allowance, and with
     * {@code neighborSample}, a pixel also passes where it matches a 4-neighbor's golden pixel (a sampling
     * position that went to the adjacent texel).
     */
    record Tolerance(String name, int interior, int edge, int budget, boolean neighborSample) {
        static final Tolerance EXACT = new Tolerance("exact", 0, 0, 0, false);
    }

    private static final Color FIELD = new Color(0xDDDDDD);
    private static final Color BAND = new Color(0x404040);
    private static final Color FRAME = new Color(0x808080);
    private static final Color CLEARED = new Color(0x40A0A0);
    private static final Color TRANSLUCENT_FIELD = new Color(0xDD, 0xDD, 0xDD, 0x80);
    private static final Color TRANSLUCENT_BAND = new Color(0x40, 0x40, 0x40, 0xC0);
    private static final int TRANSPARENT_TOP = 18;
    private static final int TRANSPARENT_HEIGHT = 6;
    private static final int BAND_TOP = 36;
    private static final int BAND_HEIGHT = 12;

    static final BufferedImage SOURCE_IMAGE = sourceImage();
    private static final int MANY_STOPS = 13;
    private static final int FORMAT_TILE = 14;
    private static final List<BufferedImage> FORMAT_IMAGES = formatImages();
    private final Font textFont;
    private final Font boldTextFont;
    private final Font colorFont;
    private final Image volatileSource;

    /**
     * The scenes with the text font, a font file shipped with the runtime and loaded with Font.createFont, so
     * that text renders through the same scaler on every platform (null fonts: the text scenes cannot run here),
     * the system's color font for the color text sheets (null: they cannot run), and SOURCE_IMAGE in the
     * backend's volatile image (a buffered image for the software loops).
     */
    RenderConformanceScenes(Font textFont, Font boldTextFont, Font colorFont, Image volatileSource) {
        this.textFont = textFont;
        this.boldTextFont = boldTextFont;
        this.colorFont = colorFont;
        this.volatileSource = volatileSource;
    }

    /** The runtime's bundled JetBrains Mono at `size` points, or null where the runtime does not ship it. */
    static Font bundledFont(String fileName, float size) {
        File file = new File(System.getProperty("java.home"), "lib" + File.separator + "fonts" + File.separator + fileName);
        if (!file.isFile()) {
            return null;
        }
        try {
            return Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(size);
        } catch (FontFormatException | IOException e) {
            throw new RuntimeException("cannot load " + file, e);
        }
    }

    static List<Scene> scenes() {
        List<Scene> scenes = new ArrayList<>();
        // every paint x every shape, AA off and on, with the default stroke control (normalization); the shapes
        // that do not draw with the paint once
        for (Paint paint : Paint.values()) {
            for (Shape shape : Shape.values()) {
                if (!drawsWithPaint(shape) && paint != Paint.SOLID) {
                    continue;
                }
                for (boolean antialiased : new boolean[] {false, true}) {
                    add(scenes, paint + "/" + shape + (antialiased ? "/AA" : ""), Sheet.PAINTS, paint, shape,
                        Composite.SRC_OVER, Transform.ID, Clip.NONE, antialiased, false);
                }
            }
        }
        // the AA shapes with pure strokes: geometry as specified, unnormalized
        for (Paint paint : new Paint[] {Paint.SOLID, Paint.SOLID_A50}) {
            for (Shape shape : Shape.values()) {
                if (antialiasingApplies(shape)) {
                    add(scenes, "pure:" + paint + "/" + shape + "/AA", Sheet.STROKES, paint, shape, Composite.SRC_OVER,
                        Transform.ID, Clip.NONE, true, true);
                }
            }
        }
        // composites x shapes (translucent solid), AA off and on
        for (Composite composite : Composite.values()) {
            if (composite == Composite.SRC_OVER) {
                continue;
            }
            for (Shape shape : Shape.values()) {
                for (boolean antialiased : new boolean[] {false, true}) {
                    add(scenes, "comp:" + composite + "/" + shape + (antialiased ? "/AA" : ""), Sheet.COMPOSITES,
                        Paint.SOLID_A50, shape, composite, Transform.ID, Clip.NONE, antialiased, false);
                }
            }
        }
        // composites x paints (an AA oval)
        for (Composite composite : Composite.values()) {
            for (Paint paint : Paint.values()) {
                add(scenes, "comp:" + composite + "/" + paint + "/FILL_OVAL/AA", Sheet.COMPOSITES, paint,
                    Shape.FILL_OVAL, composite, Transform.ID, Clip.NONE, true, false);
            }
        }
        // on a translucent surface: composites x shapes (translucent solid), AA off and on, and x paints (an AA oval)
        for (Composite composite : Composite.values()) {
            for (Shape shape : Shape.values()) {
                for (boolean antialiased : new boolean[] {false, true}) {
                    add(scenes, "translucent:" + composite + "/" + shape + (antialiased ? "/AA" : ""), Sheet.TRANSLUCENT,
                        Paint.SOLID_A50, shape, composite, Transform.ID, Clip.NONE, antialiased, false);
                }
            }
            for (Paint paint : Paint.values()) {
                add(scenes, "translucent:" + composite + "/" + paint + "/FILL_OVAL/AA", Sheet.TRANSLUCENT, paint,
                    Shape.FILL_OVAL, composite, Transform.ID, Clip.NONE, true, false);
            }
        }
        // transforms x shapes (solid), and x paints (a filled rect)
        for (Transform transform : Transform.values()) {
            if (transform == Transform.ID) {
                continue;
            }
            for (Shape shape : Shape.values()) {
                if (shape == Shape.COPY_AREA && transform != Transform.TRANSLATE_HALF) {
                    continue;   // Java2D: "transformed copyArea not implemented"
                }
                for (boolean antialiased : new boolean[] {false, true}) {
                    add(scenes, "xf:" + transform + "/" + shape + (antialiased ? "/AA" : ""), Sheet.TRANSFORMS,
                        Paint.SOLID, shape, Composite.SRC_OVER, transform, Clip.NONE, antialiased, false);
                }
            }
            for (Paint paint : Paint.values()) {
                add(scenes, "xf:" + transform + "/" + paint + "/FILL_RECT", Sheet.TRANSFORMS, paint, Shape.FILL_RECT,
                    Composite.SRC_OVER, transform, Clip.NONE, false, false);
            }
        }
        // clips x shapes (solid), and x paints (a filled rect)
        for (Clip clip : Clip.values()) {
            if (clip == Clip.NONE) {
                continue;
            }
            for (Shape shape : Shape.values()) {
                for (boolean antialiased : new boolean[] {false, true}) {
                    add(scenes, "clip:" + clip + "/" + shape + (antialiased ? "/AA" : ""), Sheet.CLIPS, Paint.SOLID,
                        shape, Composite.SRC_OVER, Transform.ID, clip, antialiased, false);
                }
            }
            for (Paint paint : Paint.values()) {
                add(scenes, "clip:" + clip + "/" + paint + "/FILL_RECT", Sheet.CLIPS, paint, Shape.FILL_RECT,
                    Composite.SRC_OVER, Transform.ID, clip, false, false);
            }
        }
        return scenes;
    }

    /** Whether the antialiasing hint changes what the shape draws (text has its own hint, images and copies none). */
    private static boolean antialiasingApplies(Shape shape) {
        return !shape.name().startsWith("TEXT_") && !shape.name().startsWith("IMAGE") && shape != Shape.COPY_AREA
               && shape != Shape.CLEAR_RECT;
    }

    /** Whether the shape draws with the paint (images draw their pixels; color text an opaque color, see paintScene). */
    private static boolean drawsWithPaint(Shape shape) {
        return !shape.name().startsWith("IMAGE") && shape != Shape.TEXT_COLOR;
    }

    private static void add(List<Scene> scenes, String name, Sheet sheet, Paint paint, Shape shape, Composite composite,
                            Transform transform, Clip clip, boolean antialiased, boolean pureStroke) {
        if (antialiased && !pureStroke && !antialiasingApplies(shape)) {
            return;   // the same pixels as the scene without the hint
        }
        Scene scene = new Scene(name, sheet, paint, shape, composite, transform, clip, antialiased, pureStroke);
        // text has sheets of its own: its goldens are per platform, or rendered in the run for color text
        Sheet textSheet = shape == Shape.TEXT_COLOR
                          ? sheet.translucent() ? Sheet.TRANSLUCENT_COLOR_TEXT : Sheet.COLOR_TEXT
                          : sheet.translucent() ? Sheet.TRANSLUCENT_TEXT : Sheet.TEXT;
        scenes.add(scene.isText()
                  ? new Scene(name, textSheet, paint, shape, composite, transform, clip, antialiased, pureStroke)
                  : scene);
    }

    // The liberties a backend may take, from the Skia and Direct3D measurements of 2026-11-24 with some margin:
    // the budget covers the whole pixels a liberty moves, the allowances the levels it changes.
    /** Opaque geometry and colors: a level for translucent colors near a level's midpoint. */
    private static final Tolerance BASELINE = new Tolerance("baseline", 1, 1, 0, false);
    /** AA coverage: Marlin's tiles vs analytic parallelograms, the corners of AA rectangle outlines. */
    private static final Tolerance AA_EDGE = new Tolerance("aa-edge", 8, 64, 0, false);
    /** Text: the LCD blend and glyph coverage at the glyphs' edges. */
    private static final Tolerance TEXT = new Tolerance("text", 8, 64, 32, false);
    /** 1-pixel lines: where a line passes half-way between two pixel centers, the rasterizer picks one. */
    private static final Tolerance LINE_TIES = new Tolerance("line-ties", 1, 64, 32, false);
    /** Nearest-neighbor ties in images and textures: the adjacent texel, in the largest textured scenes too. */
    private static final Tolerance NEAREST_TIE = new Tolerance("nearest-tie", 16, 64, 64, true);
    /** Non-AA shapes: the loops' two scan conversion routes (opaque color vs paint, translucency, shape clip). */
    private static final Tolerance RASTER_ROUTE = new Tolerance("raster-route", 4, 64, 192, false);
    /** REPEAT gradients: the pixel at a cycle's seam. */
    private static final Tolerance REPEAT_SEAMS = new Tolerance("repeat-seams", 4, 64, 192, false);
    /** Translucent gradient stops: interpolated premultiplied (the GPU pipelines) or straight (the loops). */
    private static final Tolerance TRANSLUCENT_STOPS = new Tolerance("translucent-stops", 40, 64, 192, false);
    /**
     * Partially covered pixels on an opaque destination under a rule whose result is not opaque (Clear, SrcOut, Src
     * with extra alpha): the premultiplied result, as every accelerated pipeline stores it, where the loops store
     * its straight color (AlphaComposite on a destination without alpha), which leaves such pixels unchanged.
     */
    private static final Tolerance PARTIAL_COVERAGE = new Tolerance("partial-coverage", 32, 64, 320, false);
    /**
     * The software loops' LCD glyphs shifted by a third of a pixel read up to two bytes past each row's coverage,
     * into the next row's padding, and past the image on its last row (LoopMacros.h, rowBytesOffset): a glyph's
     * last pixel varies between runs. The software run's only allowance.
     */
    private static final Tolerance LCD_SHIFT_OVERREAD = new Tolerance("lcd-shift-overread", 0, 255, 4, false);

    /** The tolerance the software loops get for `scene`, against goldens of their own output. */
    static Tolerance softwareTolerance(Scene scene) {
        return scene.shape == Shape.TEXT_LCD_FRACTIONAL ? LCD_SHIFT_OVERREAD : Tolerance.EXACT;
    }

    /** The tolerance a backend gets for `scene`. */
    static Tolerance tolerance(Scene scene) {
        boolean image = scene.isImage();
        if (scene.isText()) {
            // textured glyphs: the texture's ties within the strokes, glyph edges within the edge allowance
            return scene.paint == Paint.TEXTURE || scene.paint == Paint.TEXTURE_BILINEAR ? NEAREST_TIE : TEXT;
        }
        if (scene.antialiased && (scene.composite == Composite.CLEAR || !scene.sheet.translucent()
                                  && (scene.composite == Composite.SRC_OUT || scene.composite == Composite.SRC_ALPHA50))) {
            return PARTIAL_COVERAGE;   // before the paint's classes: these rules' edges differ whatever the paint
        }
        if (scene.shape == Shape.IMAGE_SCALED || scene.shape == Shape.IMAGE_ROT || scene.shape == Shape.IMAGE_VOLATILE
                || scene.shape == Shape.IMAGE_BACKGROUND || scene.shape == Shape.IMAGE_SCALED_BILINEAR
                || scene.shape == Shape.IMAGE_ROT_BILINEAR || scene.shape == Shape.IMAGE_ROT_BICUBIC
                || image && scene.transform != Transform.ID && scene.transform != Transform.TRANSLATE_HALF
                || scene.paint == Paint.TEXTURE || scene.paint == Paint.TEXTURE_BILINEAR) {
            return NEAREST_TIE;
        }
        if (scene.paint == Paint.LIN3_REPEAT || scene.paint == Paint.RADIAL_REPEAT) {
            return REPEAT_SEAMS;
        }
        if (scene.paint == Paint.LIN_ALPHA) {
            return TRANSLUCENT_STOPS;
        }
        if (scene.antialiased) {
            return AA_EDGE;
        }
        if (scene.paint != Paint.SOLID || scene.clip == Clip.ELLIPSE || scene.clip == Clip.TWO_RECTS
                || scene.transform != Transform.ID || scene.composite != Composite.SRC_OVER) {
            return RASTER_ROUTE;
        }
        if (scene.shape == Shape.DRAW_LINE || scene.shape == Shape.POLYLINE || scene.shape == Shape.DRAW_OVAL) {
            return LINE_TIES;
        }
        return BASELINE;
    }

    /**
     * Why `backend` is known to miss the golden of `scene`, or null where it is held to the scene's tolerance: a
     * shortcut of the software loops, whose output the goldens are, where the backend draws Java2D's general
     * rule, or a defect of the backend; not a liberty. The scene still runs and is reported, but does not fail
     * the run, so the backend's variant guards all other scenes.
     */
    static String knownDeviation(String backend, Scene scene) {
        if (backend.equals("software")) {
            return null;
        }
        boolean antialiasedText = scene.isText() && scene.shape != Shape.TEXT_NOAA && scene.shape != Shape.TEXT_COLOR;
        boolean copiesColor = scene.composite == Composite.SRC || scene.composite == Composite.SRC_ALPHA50
                              || scene.composite == Composite.CLEAR;
        if (antialiasedText && scene.sheet.translucent() && copiesColor) {
            return "the software glyph loops draw antialiased glyphs on a translucent surface as SrcOver of the color "
                   + "under Src and not at all under Clear, not by the coverage as Java2D's general loops do";
        }
        boolean shapeClip = scene.clip == Clip.ELLIPSE || scene.clip == Clip.TWO_RECTS;
        if (scene.shape == Shape.TEXT_COLOR && (scene.composite == Composite.ALPHA50 || scene.composite == Composite.CLEAR
                                                || scene.composite == Composite.XOR || shapeClip
                                                || scene.composite == Composite.SRC_ALPHA50 && scene.sheet.translucent())) {
            return "the software loops draw color glyphs as SrcOver whatever the composite and its extra alpha, and none "
                   + "under SrcOver's extra alpha or a shape clip";
        }
        if ((scene.shape == Shape.TEXT_LCD_FRACTIONAL || scene.shape == Shape.TEXT_GRAY_FRACTIONAL)
                && (scene.composite == Composite.ALPHA50 || shapeClip)) {
            return "the software loops' text route for extra alpha and shape clips (TextRenderer) ignores the glyphs' "
                   + "sub-pixel positions, which their glyph loops honor";
        }
        if (!backend.equals("d3d")) {
            return null;
        }
        if (scene.shape == Shape.TEXT_COLOR) {
            return "D3D draws color glyphs as coverage in the text color";
        }
        if ((scene.paint == Paint.LIN_MANY_STOPS || scene.paint == Paint.CUSTOM) && scene.isText()) {
            return "D3D drops the paint context's text tiles once a glyph larger than 32 x 32 grew the glyph list's mask "
                   + "array (BufferedMaskBlit)";
        }
        if (scene.shape == Shape.TEXT_GRAY_FRACTIONAL || scene.shape == Shape.TEXT_LCD_FRACTIONAL
                && (scene.transform == Transform.ROTATE_30 || scene.transform == Transform.SHEAR)) {
            return "D3D's grayscale glyphs ignore their sub-pixel positions (LCD glyphs turn grayscale when rotated)";
        }
        if (scene.shape == Shape.COPY_AREA && (scene.composite != Composite.SRC_OVER || scene.sheet.translucent())) {
            return "D3D's copyArea copies the pixels, ignoring the composite";
        }
        boolean opaque = !scene.sheet.translucent();
        copiesColor = scene.composite == Composite.SRC || scene.composite == Composite.SRC_IN;
        if (scene.isText() && opaque && (copiesColor || scene.composite == Composite.CLEAR
                                         || scene.composite == Composite.SRC_OUT)) {
            return "D3D's text does not follow Src, SrcIn, SrcOut and Clear";
        }
        if (scene.isText() && !opaque && (scene.composite == Composite.CLEAR || scene.shape == Shape.TEXT_NOAA
                                          && (scene.composite == Composite.SRC || scene.composite == Composite.SRC_ALPHA50))) {
            return "D3D's text on a translucent surface does not follow Clear, nor Src for bilevel glyphs";
        }
        if (copiesColor && opaque) {
            return "D3D stores a translucent source's premultiplied color under Src and SrcIn on an opaque surface";
        }
        if (opaque && (scene.composite == Composite.DST_IN || scene.composite == Composite.DST_OUT
                       || scene.composite == Composite.DST_ATOP || scene.composite == Composite.XOR_RULE
                       || scene.composite == Composite.SRC_ALPHA50)) {
            return "D3D composites DstIn, DstOut, DstAtop, Xor and Src with extra alpha into an opaque surface's alpha, "
                   + "not as onto a destination without alpha";
        }
        return null;
    }

    /** Draws `scenes` onto a sheet, COLUMNS scenes per row, SCENE_SIZE pixels each. */
    void paintSheet(Graphics2D sheet, List<Scene> scenes) {
        for (int index = 0; index < scenes.size(); index++) {
            Graphics2D graphics = (Graphics2D) sheet.create((index % COLUMNS) * SCENE_SIZE, (index / COLUMNS) * SCENE_SIZE, SCENE_SIZE, SCENE_SIZE);
            try {
                paintScene(graphics, scenes.get(index));
            } finally {
                graphics.dispose();
            }
        }
    }

    static int sheetHeight(int sceneCount) {
        return (sceneCount + COLUMNS - 1) / COLUMNS * SCENE_SIZE;
    }

    private void paintScene(Graphics2D graphics, Scene scene) {
        boolean translucent = scene.sheet.translucent();
        if (translucent) {
            // Src: the field replaces what the surface held before (a new volatile image's contents are undefined)
            graphics.setComposite(AlphaComposite.Src);
        }
        graphics.setColor(translucent ? TRANSLUCENT_FIELD : FIELD);
        graphics.fillRect(0, 0, SCENE_SIZE, SCENE_SIZE);
        // clearRect fills with the background, whose default differs between buffered and volatile images
        graphics.setBackground(CLEARED);
        graphics.setColor(translucent ? TRANSLUCENT_BAND : BAND);
        graphics.fillRect(0, BAND_TOP, SCENE_SIZE, BAND_HEIGHT);
        if (translucent) {
            graphics.setColor(new Color(0, true));
            graphics.fillRect(0, TRANSPARENT_TOP, SCENE_SIZE, TRANSPARENT_HEIGHT);
        }
        graphics.setColor(FRAME);
        graphics.fillRect(0, 0, SCENE_SIZE, 1);
        graphics.fillRect(0, 0, 1, SCENE_SIZE);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                  scene.antialiased ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        if (scene.pureStroke) {
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        }
        applyClip(graphics, scene.clip);
        applyTransform(graphics, scene.transform);
        applyComposite(graphics, scene.composite);
        if (scene.shape == Shape.TEXT_COLOR) {
            graphics.setColor(new Color(0x2050E0));   // the software loops draw color glyphs only with an opaque color
        } else if (scene.composite == Composite.XOR && (scene.paint == Paint.SOLID || scene.paint == Paint.SOLID_A50)) {
            graphics.setColor(new Color(0xC03030));   // XOR with an opaque color; with the other paints, XOR with the paint
        } else {
            graphics.setPaint(paint(scene.paint));
        }
        if (scene.paint == Paint.TEXTURE_BILINEAR) {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        drawShape(graphics, scene.shape);
    }

    private static BufferedImage sourceImage() {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setPaint(new GradientPaint(0, 0, new Color(255, 200, 0, 255), 40, 40, new Color(0, 120, 255, 64)));
        graphics.fillRect(0, 0, 40, 40);
        graphics.setColor(Color.BLACK);
        graphics.drawRect(0, 0, 39, 39);
        graphics.fillOval(14, 14, 12, 12);
        graphics.dispose();
        return image;
    }

    private static java.awt.Paint paint(Paint paint) {
        Color a = new Color(0xE05020), b = new Color(0x2050E0), c = new Color(0x20C040);
        float[] thirds = {0f, 0.5f, 1f};
        Color[] three = {a, c, b};
        return switch (paint) {
            case SOLID -> new Color(0xC03030);
            case SOLID_A50 -> new Color(0x30, 0x60, 0xC0, 128);
            case GRADIENT -> new GradientPaint(8, 8, a, 56, 56, b);
            case GRADIENT_CYCLIC -> new GradientPaint(16, 16, a, 32, 24, b, true);
            case LIN3_NOCYCLE -> new LinearGradientPaint(8, 0, 56, 0, thirds, three);
            case LIN3_REFLECT -> new LinearGradientPaint(20, 0, 36, 0, thirds, three, MultipleGradientPaint.CycleMethod.REFLECT);
            case LIN3_REPEAT -> new LinearGradientPaint(20, 0, 36, 0, thirds, three, MultipleGradientPaint.CycleMethod.REPEAT);
            case LIN3_PARTIAL -> new LinearGradientPaint(8, 0, 56, 0, new float[] {0.2f, 0.4f, 0.9f}, three);
            case LIN_ALPHA -> new LinearGradientPaint(8, 0, 56, 0, new float[] {0f, 1f},
                                                      new Color[] {new Color(0xE0, 0x50, 0x20, 0), new Color(0xE0, 0x50, 0x20, 255)});
            case RADIAL -> new RadialGradientPaint(32, 32, 24, thirds, new Color[] {Color.WHITE, a, b});
            case RADIAL_FOCUS -> new RadialGradientPaint(new Point2D.Float(32, 32), 24, new Point2D.Float(20, 24),
                                                         new float[] {0f, 1f}, new Color[] {Color.YELLOW, b},
                                                         MultipleGradientPaint.CycleMethod.NO_CYCLE);
            case RADIAL_REPEAT -> new RadialGradientPaint(32, 32, 10, new float[] {0f, 1f}, new Color[] {a, b},
                                                          MultipleGradientPaint.CycleMethod.REPEAT);
            case TEXTURE, TEXTURE_BILINEAR -> new TexturePaint(SOURCE_IMAGE, new Rectangle(4, 4, 16, 16));
            case LIN_MANY_STOPS -> {
                // more stops than the pipelines' gradient shaders take: the paint context's tiles
                float[] fractions = new float[MANY_STOPS];
                Color[] colors = new Color[MANY_STOPS];
                for (int stop = 0; stop < MANY_STOPS; stop++) {
                    fractions[stop] = stop / (MANY_STOPS - 1f);
                    colors[stop] = three[stop % three.length];
                }
                yield new LinearGradientPaint(8, 8, 56, 40, fractions, colors);
            }
            case LIN3_LINEAR_RGB -> new LinearGradientPaint(new Point2D.Float(8, 0), new Point2D.Float(56, 0), thirds, three,
                                                            MultipleGradientPaint.CycleMethod.NO_CYCLE,
                                                            MultipleGradientPaint.ColorSpaceType.LINEAR_RGB,
                                                            new AffineTransform());
            case GRADIENT_DEGENERATE -> new GradientPaint(32, 32, a, 32, 32, b);
            case CUSTOM -> new CheckerPaint();
        };
    }

    private static void applyComposite(Graphics2D graphics, Composite composite) {
        switch (composite) {
            case SRC_OVER -> { }
            case ALPHA50 -> graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            case SRC -> graphics.setComposite(AlphaComposite.Src);
            case XOR -> graphics.setXORMode(new Color(0x00FF80));
            case CLEAR -> graphics.setComposite(AlphaComposite.Clear);
            case SRC_IN -> graphics.setComposite(AlphaComposite.SrcIn);
            case DST -> graphics.setComposite(AlphaComposite.Dst);
            case DST_OVER -> graphics.setComposite(AlphaComposite.DstOver);
            case SRC_OUT -> graphics.setComposite(AlphaComposite.SrcOut);
            case DST_IN -> graphics.setComposite(AlphaComposite.DstIn);
            case DST_OUT -> graphics.setComposite(AlphaComposite.DstOut);
            case SRC_ATOP -> graphics.setComposite(AlphaComposite.SrcAtop);
            case DST_ATOP -> graphics.setComposite(AlphaComposite.DstAtop);
            case XOR_RULE -> graphics.setComposite(AlphaComposite.Xor);
            case SRC_ALPHA50 -> graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC, 0.5f));
            case CUSTOM -> graphics.setComposite(new DarkenComposite());
        }
    }

    private static void applyTransform(Graphics2D graphics, Transform transform) {
        switch (transform) {
            case ID -> { }
            case TRANSLATE_HALF -> graphics.translate(0.5, 0.5);
            case SCALE_2 -> {
                graphics.scale(2, 2);
                graphics.translate(-16, -16);
            }
            case SCALE_1_5 -> {
                graphics.scale(1.5, 1.5);
                graphics.translate(-10, -10);
            }
            case ROTATE_30 -> graphics.rotate(Math.toRadians(30), 32, 32);
            case SHEAR -> {
                graphics.shear(0.3, 0);
                graphics.translate(-8, 0);
            }
        }
    }

    private static void applyClip(Graphics2D graphics, Clip clip) {
        switch (clip) {
            case NONE -> { }
            case RECT -> graphics.clipRect(10, 6, 40, 50);
            case ELLIPSE -> graphics.clip(new Ellipse2D.Float(6, 6, 52, 52));
            case TWO_RECTS -> {
                Area area = new Area(new Rectangle(4, 4, 24, 56));
                area.add(new Area(new Rectangle(36, 4, 24, 56)));
                graphics.clip(area);
            }
        }
    }

    private void drawShape(Graphics2D graphics, Shape shape) {
        switch (shape) {
            case FILL_RECT -> graphics.fillRect(10, 10, 44, 44);
            case DRAW_RECT -> graphics.drawRect(10, 10, 43, 43);
            case DRAW_LINE -> {
                graphics.drawLine(6, 58, 58, 10);
                graphics.drawLine(6, 6, 58, 20);
            }
            case POLYLINE -> graphics.drawPolyline(new int[] {6, 20, 34, 48, 58}, new int[] {50, 14, 46, 10, 40}, 5);
            case FILL_OVAL -> graphics.fillOval(8, 8, 48, 40);
            case DRAW_OVAL -> graphics.drawOval(8, 8, 47, 39);
            case FILL_RRECT -> graphics.fill(new RoundRectangle2D.Float(8, 8, 48, 48, 16, 16));
            case DRAW_RRECT_W3 -> {
                graphics.setStroke(new BasicStroke(3f));
                graphics.draw(new RoundRectangle2D.Float(8, 8, 48, 48, 16, 16));
            }
            case STROKE_W5 -> {
                graphics.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                graphics.draw(new Line2D.Float(8, 56, 56, 8));
                graphics.draw(new Line2D.Float(8, 8, 30, 56));
            }
            case DASHED -> {
                graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6f, 4f}, 0f));
                graphics.draw(new Ellipse2D.Float(8, 8, 48, 48));
            }
            case FILL_PATH -> {
                Path2D.Float path = new Path2D.Float();
                path.moveTo(8, 56);
                path.curveTo(8, 0, 56, 0, 56, 56);
                path.lineTo(32, 36);
                path.closePath();
                graphics.fill(path);
            }
            case TEXT_GRAY -> drawText(graphics, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, boldTextFont);
            case TEXT_LCD -> drawText(graphics, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, textFont);
            case TEXT_NOAA -> drawText(graphics, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF, textFont);
            case IMAGE -> graphics.drawImage(SOURCE_IMAGE, 12, 12, null);
            case IMAGE_SCALED -> graphics.drawImage(SOURCE_IMAGE, 6, 6, 52, 52, null);
            case IMAGE_ROT -> {
                AffineTransform transform = AffineTransform.getRotateInstance(Math.toRadians(25), 32, 32);
                transform.translate(16, 16);
                graphics.drawImage(SOURCE_IMAGE, transform, null);
            }
            case COPY_AREA -> {
                graphics.fillRect(6, 6, 24, 24);
                graphics.copyArea(6, 6, 24, 24, 26, 26);
                graphics.copyArea(6, 6, 24, 24, 26, 0);
            }
            case CLEAR_RECT -> {
                graphics.fillRect(6, 6, 52, 52);
                graphics.clearRect(16, 16, 32, 32);
            }
            case STROKE_RECT_W4 -> {
                // miter-joined wide outlines: one with a hole, one too small for a hole
                graphics.setStroke(new BasicStroke(4f));
                graphics.drawRect(8, 8, 47, 31);
                graphics.drawRect(26, 46, 3, 3);
            }
            case TEXT_LCD_HBGR -> drawText(graphics, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR, textFont);
            case TEXT_LCD_VRGB -> drawText(graphics, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB, textFont);
            case TEXT_LCD_VBGR -> drawText(graphics, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VBGR, textFont);
            case TEXT_LCD_FRACTIONAL -> {
                // sub-pixel positions: the glyphs' coverage shifted by thirds of a pixel
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                drawText(graphics, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, textFont);
            }
            case TEXT_GRAY_FRACTIONAL -> {
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                drawText(graphics, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, boldTextFont);
            }
            case TEXT_LARGE -> {
                // a glyph for the largest cache cells, and one too wide for any (drawn uncached)
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setFont(boldTextFont.deriveFont(72f));
                graphics.drawString("W", 2, 58);
                graphics.setFont(boldTextFont.deriveFont(60f).deriveFont(AffineTransform.getScaleInstance(3, 1)));
                graphics.drawString("M", -40, 40);
            }
            case TEXT_COLOR -> {
                if (colorFont != null) {
                    graphics.setFont(colorFont);
                    Object[] modes = {RenderingHints.VALUE_TEXT_ANTIALIAS_OFF, RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                                      RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB};
                    for (int line = 0; line < modes.length; line++) {
                        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, modes[line]);
                        graphics.drawString("\uD83D\uDE00\uD83C\uDF89", 2, 20 + 20 * line);   // color glyphs only
                    }
                }
            }
            case IMAGE_FORMATS -> {
                for (int index = 0; index < FORMAT_IMAGES.size(); index++) {
                    graphics.drawImage(FORMAT_IMAGES.get(index), 4 + index % 4 * 15, 4 + index / 4 * 15, null);
                }
            }
            case IMAGE_BACKGROUND -> {
                Color background = new Color(0x80C080);
                graphics.drawImage(SOURCE_IMAGE, 2, 2, background, null);
                graphics.drawImage(SOURCE_IMAGE, 34, 34, 26, 26, background, null);
            }
            case IMAGE_SCALED_BILINEAR -> {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(SOURCE_IMAGE, 6, 6, 52, 52, null);
            }
            case IMAGE_ROT_BILINEAR -> drawRotatedImage(graphics, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            case IMAGE_ROT_BICUBIC -> drawRotatedImage(graphics, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            case IMAGE_VOLATILE -> {
                graphics.drawImage(volatileSource, 2, 2, null);
                graphics.drawImage(volatileSource, 34, 30, 28, 32, null);
            }
        }
    }

    private static void drawRotatedImage(Graphics2D graphics, Object interpolation) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        AffineTransform transform = AffineTransform.getRotateInstance(Math.toRadians(25), 32, 32);
        transform.translate(16, 16);
        graphics.drawImage(SOURCE_IMAGE, transform, null);
    }

    /**
     * SOURCE_IMAGE scaled to a tile in each image format the pipelines blit differently: their own int formats,
     * the formats converted in Java first, a bitmask, an indexed palette, and an image whose raster was taken
     * (never cached as a texture).
     */
    private static List<BufferedImage> formatImages() {
        List<BufferedImage> images = new ArrayList<>();
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_BGR,
                                   BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR,
                                   BufferedImage.TYPE_4BYTE_ABGR_PRE, BufferedImage.TYPE_BYTE_GRAY,
                                   BufferedImage.TYPE_USHORT_GRAY, BufferedImage.TYPE_USHORT_565_RGB,
                                   BufferedImage.TYPE_BYTE_INDEXED}) {
            images.add(formatImage(new BufferedImage(FORMAT_TILE, FORMAT_TILE, type)));
        }
        DirectColorModel bitmask = new DirectColorModel(25, 0xFF0000, 0xFF00, 0xFF, 0x1000000);
        images.add(formatImage(new BufferedImage(bitmask, bitmask.createCompatibleWritableRaster(FORMAT_TILE, FORMAT_TILE),
                                                 false, null)));
        BufferedImage untracked = formatImage(new BufferedImage(FORMAT_TILE, FORMAT_TILE, BufferedImage.TYPE_INT_ARGB));
        ((DataBufferInt) untracked.getRaster().getDataBuffer()).getData();   // the raster taken: never cached
        images.add(untracked);
        return images;
    }

    /** The image holding SOURCE_IMAGE scaled to a tile, stored through its color model (canonical pixels). */
    private static BufferedImage formatImage(BufferedImage image) {
        BufferedImage scaled = new BufferedImage(FORMAT_TILE, FORMAT_TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.drawImage(SOURCE_IMAGE, 0, 0, FORMAT_TILE, FORMAT_TILE, null);
        graphics.dispose();
        image.setRGB(0, 0, FORMAT_TILE, FORMAT_TILE, scaled.getRGB(0, 0, FORMAT_TILE, FORMAT_TILE, null, 0, FORMAT_TILE), 0,
                     FORMAT_TILE);
        return image;
    }

    /** A paint the pipelines cannot draw natively: a checkerboard of two translucent colors, by a paint context. */
    private static final class CheckerPaint implements java.awt.Paint {
        @Override
        public PaintContext createContext(ColorModel colorModel, Rectangle deviceBounds, Rectangle2D userBounds,
                                          AffineTransform transform, RenderingHints hints) {
            return new PaintContext() {
                @Override
                public void dispose() {
                }

                @Override
                public ColorModel getColorModel() {
                    return ColorModel.getRGBdefault();
                }

                @Override
                public Raster getRaster(int x, int y, int width, int height) {
                    WritableRaster raster = getColorModel().createCompatibleWritableRaster(width, height);
                    int[] pixels = new int[width];
                    for (int row = 0; row < height; row++) {
                        for (int column = 0; column < width; column++) {
                            pixels[column] = ((x + column) / 4 + (y + row) / 4) % 2 == 0 ? 0xC0E05020 : 0x602050E0;
                        }
                        raster.setDataElements(0, row, width, 1, pixels);
                    }
                    return raster;
                }
            };
        }

        @Override
        public int getTransparency() {
            return Transparency.TRANSLUCENT;
        }
    }

    /**
     * A composite the pipelines cannot draw natively: per channel the darker of the straight source and
     * destination colors, wherever the source is not transparent.
     */
    private static final class DarkenComposite implements java.awt.Composite {
        @Override
        public CompositeContext createContext(ColorModel sourceModel, ColorModel destinationModel, RenderingHints hints) {
            return new CompositeContext() {
                @Override
                public void dispose() {
                }

                @Override
                public void compose(Raster source, Raster destinationIn, WritableRaster destinationOut) {
                    int width = Math.min(source.getWidth(), destinationIn.getWidth());
                    int height = Math.min(source.getHeight(), destinationIn.getHeight());
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int sourcePixel = sourceModel.getRGB(source.getDataElements(source.getMinX() + x,
                                                                                         source.getMinY() + y, null));
                            int destinationPixel = destinationModel.getRGB(destinationIn.getDataElements(
                                    destinationIn.getMinX() + x, destinationIn.getMinY() + y, null));
                            int result = destinationPixel;
                            if (sourcePixel >>> 24 != 0) {
                                result = Math.max(sourcePixel >>> 24, destinationPixel >>> 24) << 24;
                                for (int shift = 0; shift <= 16; shift += 8) {
                                    result |= Math.min((sourcePixel >> shift) & 0xFF, (destinationPixel >> shift) & 0xFF) << shift;
                                }
                            }
                            destinationOut.setDataElements(destinationOut.getMinX() + x, destinationOut.getMinY() + y,
                                                           destinationModel.getDataElements(result, null));
                        }
                    }
                }
            };
        }
    }

    private void drawText(Graphics2D graphics, Object antialiasing, Font secondLineFont) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, antialiasing);
        graphics.setFont(textFont);
        graphics.drawString("Agile 123", 4, 26);
        graphics.setFont(secondLineFont);
        graphics.drawString("Wxyz iIl|", 4, 48);
    }
}
