/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static java.util.Map.entry;

public class AlphaCompositeTestRunner {
    public static final int W = 3;
    public static final int H = 1;

    // In the case of a partially transparent output onto an opaque destination,
    // there are 2 known behaviors in various graphics impls:
    // 1. Un-premultiply the color to fully opaque.
    // 2. Drop the alpha value while leaving the color unchanged.
    // (1) is conceptually "just making the result opaque",
    // (2) is equivalent to SrcOver over black, which makes the color darker.
    public enum TranslucentOutputOnOpaqueDstPolicy {
        MUST_UNPREMULTIPLY,
        MUST_DROP_ALPHA,
        DONT_CARE,
    }

    // In the case of outputting a fully transparent pixel onto an opaque destination,
    // must the implementation set it to opaque black, or is any color acceptable?
    public enum FullyTransparentOnOpaqueDstPolicy {
        MUST_SET_TO_BLACK,
        DONT_CARE,
    }

    public record RunOptions(
            TranslucentOutputOnOpaqueDstPolicy translucentOutputOnOpaqueDstPolicy,
            FullyTransparentOnOpaqueDstPolicy fullyTransparentOnOpaqueDstPolicy,
            float premultipliedNormalizedTolerance, // tolerance for comparing the *premultiplied* values in the range of [0, 1]
            Predicate<TestCaseDescription> shouldSkipTest
    ) {}

    public enum AATestVariant {
        NO_AA,
        AA_RECTANGLE,
        AA_PATH,
    }

    public record TestCaseDescription(AlphaComposite alphaComposite, Color bgColor, Color fgColor, boolean gradientPaint, AATestVariant aaTestVariant) {}

    public static boolean run(Image image, GraphicsConfiguration config, RunOptions runOptions, String configDescription) {
        int transparency;
        if (image instanceof VolatileImage volatileImage) {
            transparency = volatileImage.getTransparency();
            if (config == null) {
                throw new IllegalArgumentException("config must not be null when a VolatileImage is provided");
            }
        } else if (image instanceof BufferedImage bufferedImage) {
            transparency = bufferedImage.getTransparency();
            if (config != null) {
                throw new IllegalArgumentException("config must be null when a BufferedImage is provided");
            }
        } else {
            throw new IllegalArgumentException("Unsupported image type");
        }
        AlphaCompositeTestRunner runner = new AlphaCompositeTestRunner(runOptions, transparency == Transparency.OPAQUE, configDescription);
        return runner.runTestCases(image, config, TEST_CASES);
    }

    private AlphaCompositeTestRunner(RunOptions runOptions, boolean opaqueDst, String configDescription) {
        this.runOptions = runOptions;
        this.opaqueDst = opaqueDst;
        this.configDescription = configDescription;
    }

    private final boolean opaqueDst;
    private final RunOptions runOptions;
    private final String configDescription;
    private int explicitlySkippedCount = 0;

    private static final int PD_FACTOR_ZERO = 0;
    private static final int PD_FACTOR_ONE = 1;
    private static final int PD_FACTOR_ALPHA = 2;
    private static final int PD_FACTOR_ONE_MINUS_ALPHA = 3;

    static float evaluatePorterDuffFactor(float alpha, int pdFactor) {
        return switch (pdFactor) {
            case PD_FACTOR_ZERO -> 0;
            case PD_FACTOR_ONE -> 1;
            case PD_FACTOR_ALPHA -> alpha;
            case PD_FACTOR_ONE_MINUS_ALPHA -> 1 - alpha;
            default -> throw new IllegalArgumentException("Invalid Porter-Duff factor");
        };
    }

    private record PorterDuffRule(int srcFactor, int dstFactor) {}

    static Map<Integer, PorterDuffRule> PORTER_DUFF_RULES = Map.ofEntries(
            entry(AlphaComposite.CLEAR, new PorterDuffRule(PD_FACTOR_ZERO, PD_FACTOR_ZERO)),
            entry(AlphaComposite.SRC, new PorterDuffRule(PD_FACTOR_ONE, PD_FACTOR_ZERO)),
            entry(AlphaComposite.DST, new PorterDuffRule(PD_FACTOR_ZERO, PD_FACTOR_ONE)),
            entry(AlphaComposite.SRC_OVER, new PorterDuffRule(PD_FACTOR_ONE, PD_FACTOR_ONE_MINUS_ALPHA)),
            entry(AlphaComposite.DST_OVER, new PorterDuffRule(PD_FACTOR_ONE_MINUS_ALPHA, PD_FACTOR_ONE)),
            entry(AlphaComposite.SRC_IN, new PorterDuffRule(PD_FACTOR_ALPHA, PD_FACTOR_ZERO)),
            entry(AlphaComposite.DST_IN, new PorterDuffRule(PD_FACTOR_ZERO, PD_FACTOR_ALPHA)),
            entry(AlphaComposite.SRC_OUT, new PorterDuffRule(PD_FACTOR_ONE_MINUS_ALPHA, PD_FACTOR_ZERO)),
            entry(AlphaComposite.DST_OUT, new PorterDuffRule(PD_FACTOR_ZERO, PD_FACTOR_ONE_MINUS_ALPHA)),
            entry(AlphaComposite.SRC_ATOP, new PorterDuffRule(PD_FACTOR_ALPHA, PD_FACTOR_ONE_MINUS_ALPHA)),
            entry(AlphaComposite.DST_ATOP, new PorterDuffRule(PD_FACTOR_ONE_MINUS_ALPHA, PD_FACTOR_ALPHA)),
            entry(AlphaComposite.XOR, new PorterDuffRule(PD_FACTOR_ONE_MINUS_ALPHA, PD_FACTOR_ONE_MINUS_ALPHA))
    );

    private record PremultipliedArgbColor(float a, float r, float g, float b) {
        static PremultipliedArgbColor fromColor(Color color) {
            float alpha = color.getAlpha() / 255.0f;
            return new PremultipliedArgbColor(alpha, color.getRed() / 255.0f * alpha, color.getGreen() / 255.0f * alpha, color.getBlue() / 255.0f * alpha);
        }
    }

    static PremultipliedArgbColor compositeColors(PremultipliedArgbColor src, PremultipliedArgbColor dst, AlphaComposite composite) {
        float extraAlpha = composite.getAlpha();
        src = new PremultipliedArgbColor(src.a * extraAlpha, src.r * extraAlpha, src.g * extraAlpha, src.b * extraAlpha);

        PorterDuffRule rule = PORTER_DUFF_RULES.get(composite.getRule());
        float Fs = evaluatePorterDuffFactor(dst.a, rule.srcFactor);
        float Fd = evaluatePorterDuffFactor(src.a, rule.dstFactor);
        return new PremultipliedArgbColor(
                Fs * src.a + Fd * dst.a,
                Fs * src.r + Fd * dst.r,
                Fs * src.g + Fd * dst.g,
                Fs * src.b + Fd * dst.b
        );
    }

    static PremultipliedArgbColor interpolateColorsByMask(PremultipliedArgbColor out, PremultipliedArgbColor dst, float mask) {
        return new PremultipliedArgbColor(
                out.a * mask + dst.a * (1 - mask),
                out.r * mask + dst.r * (1 - mask),
                out.g * mask + dst.g * (1 - mask),
                out.b * mask + dst.b * (1 - mask)
        );
    }


    // The shapes are chosen so that in a 3x1 image the true coverage is:
    // * 1 for the left pixel
    // * 2/3 for the middle pixel
    // * 0 for the right pixel
    // Marlin with default settings (256x8 subpixels) also matches the true coverage.
    static Rectangle2D.Float AA_RECTANGLE = new Rectangle2D.Float(0, 0, 1 + 2.0f/3.0f, 1);
    static Path2D.Float AA_PATH = new Path2D.Float();
    static {
        AA_PATH.moveTo(0, 0);
        AA_PATH.lineTo(1 + 5.0f / 12.0f, 0);
        AA_PATH.lineTo(1 + 11.0f / 12.0f, 1);
        AA_PATH.lineTo(0, 1);
        AA_PATH.closePath();
    }
    static final float AA_MIDDLE_PIXEL_COVERAGE = 2.0f / 3.0f;

    static void paintTestCase(Image image, TestCaseDescription testCase) {
        Graphics2D g = (Graphics2D) image.getGraphics();

        g.setComposite(AlphaComposite.Src);
        g.setColor(testCase.bgColor);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.fillRect(0, 0, W, H);

        g.setComposite(testCase.alphaComposite);
        if (testCase.gradientPaint) {
            g.setPaint(new GradientPaint(0, 0, testCase.fgColor, 1, 0, testCase.fgColor));
        } else {
            g.setColor(testCase.fgColor);
        }
        if (testCase.aaTestVariant == AATestVariant.NO_AA) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.fillRect(0, 0, W, H);
        } else {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            switch (testCase.aaTestVariant) {
                case AATestVariant.AA_PATH -> g.fill(AA_PATH);
                case AATestVariant.AA_RECTANGLE -> g.fill(AA_RECTANGLE);
                default -> throw new Error("unhandled aaTestVariant");
            }
        }

        g.dispose();
    }

    void runTestCase(Image image, GraphicsConfiguration config, TestCaseDescription testCase) {
        if (config != null && ((VolatileImage)image).validate(config) == VolatileImage.IMAGE_INCOMPATIBLE) {
            throw new RuntimeException("Image validation failed");
        }

        paintTestCase(image, testCase);

        BufferedImage result;
        if (config != null) {
            if (((VolatileImage)image).contentsLost()) {
                throw new RuntimeException("Image contents lost");
            }
            result = ((VolatileImage)image).getSnapshot();
        } else {
            result = (BufferedImage)image;
        }

        PremultipliedArgbColor fgColor = PremultipliedArgbColor.fromColor(testCase.fgColor);
        PremultipliedArgbColor bgColor = PremultipliedArgbColor.fromColor(testCase.bgColor);
        PremultipliedArgbColor outColor = compositeColors(fgColor, bgColor, testCase.alphaComposite);

        if (testCase.aaTestVariant == AATestVariant.NO_AA) {
            checkColorsAreEqual(outColor, argb(result.getRGB(0, 0)), runOptions.premultipliedNormalizedTolerance, "Colors");
        } else {
            checkColorsAreEqual(outColor, argb(result.getRGB(0, 0)), runOptions.premultipliedNormalizedTolerance, "Full-coverage colors");
            PremultipliedArgbColor expectedMiddleColor = interpolateColorsByMask(outColor, bgColor, AA_MIDDLE_PIXEL_COVERAGE);
            checkColorsAreEqual(expectedMiddleColor, argb(result.getRGB(1, 0)), runOptions.premultipliedNormalizedTolerance, "Partial-coverage colors");
            checkColorsAreEqual(bgColor, argb(result.getRGB(2, 0)), runOptions.premultipliedNormalizedTolerance, "Zero-coverage colors");
        }
    }

    boolean runTestCases(Image image, GraphicsConfiguration config, List<TestCaseDescription> testCases) {
        System.out.println("Testing " + configDescription);

        int successCount = 0;
        int failCount = 0;
        for (var testCase : testCases) {
            // Auto-exclude tests (without increasing the skip counter) with translucent bg colors on opaque destinations,
            // because that can have diverging behaviors even before the 'real' test phase (fg painting)
            if (opaqueDst && testCase.bgColor.getAlpha() != 255) {
                continue;
            }
            if (runOptions.shouldSkipTest.test(testCase)) {
                ++explicitlySkippedCount;
                continue;
            }
            try {
                runTestCase(image, config, testCase);
                ++successCount;
            } catch (Throwable e) {
                ++failCount;
                System.err.println("Failed test case: " + toString(testCase) + " (" + configDescription + ")\n  Reason: " + e);
            }
        }

        System.out.println("  ok=" + successCount + ", failed=" + failCount + ", skipped=" + explicitlySkippedCount);

        return failCount == 0;
    }

    static Color argb(int argb) {
        return new Color(argb, true);
    }

    void checkColorsAreEqual(PremultipliedArgbColor reference, Color checkedColor, double tolerance, String description) {
        if (opaqueDst) {
            if (reference.a == 0.0f) {
                switch (runOptions.fullyTransparentOnOpaqueDstPolicy) {
                    case MUST_SET_TO_BLACK:
                        reference = new PremultipliedArgbColor(1.0f, 0.0f, 0.0f, 0.0f);
                        tolerance = 0.0;
                        break;
                    case DONT_CARE:
                        return;
                }
            } else if (reference.a < 1.0f) {
                switch (runOptions.translucentOutputOnOpaqueDstPolicy) {
                    case MUST_UNPREMULTIPLY:
                        reference = new PremultipliedArgbColor(1.0f, reference.r / reference.a, reference.g / reference.a, reference.b / reference.a);
                        break;
                    case MUST_DROP_ALPHA:
                        reference = new PremultipliedArgbColor(1.0f, reference.r, reference.g, reference.b);
                        break;
                    case DONT_CARE:
                        return;
                }
            }
        }

        PremultipliedArgbColor checkedColorPre = PremultipliedArgbColor.fromColor(checkedColor);
        boolean equal = Math.abs(reference.a - checkedColorPre.a) <= tolerance &&
                Math.abs(reference.r - checkedColorPre.r) <= tolerance &&
                Math.abs(reference.g - checkedColorPre.g) <= tolerance &&
                Math.abs(reference.b - checkedColorPre.b) <= tolerance;
        if (!equal) {
            throw new ComparisonError(description + " are not equal, expected(premultiplied)=" + toString(reference) + ", actual(premultiplied)=" + toString(checkedColorPre));
        }
    }

    static String toString(Color c) {
        return "(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "," + c.getAlpha() + ")";
    }

    static String toString(PremultipliedArgbColor c) {
        return String.format("(%.1f,%.1f,%.1f,%.1f)", 255.0f * c.r, 255.0f * c.g, 255.0f * c.b, 255.0f * c.a);
    }

    static String toString(AlphaComposite alphaComposite) {
        String ruleName = switch (alphaComposite.getRule()) {
            case AlphaComposite.CLEAR -> "CLEAR";
            case AlphaComposite.SRC -> "SRC";
            case AlphaComposite.DST -> "DST";
            case AlphaComposite.SRC_OVER -> "SRC_OVER";
            case AlphaComposite.DST_OVER -> "DST_OVER";
            case AlphaComposite.SRC_IN -> "SRC_IN";
            case AlphaComposite.DST_IN -> "DST_IN";
            case AlphaComposite.SRC_OUT -> "SRC_OUT";
            case AlphaComposite.DST_OUT -> "DST_OUT";
            case AlphaComposite.SRC_ATOP -> "SRC_ATOP";
            case AlphaComposite.DST_ATOP -> "DST_ATOP";
            case AlphaComposite.XOR -> "XOR";
            default -> "rule" + alphaComposite.getRule();
        };
        return ruleName + String.format("+ea=%.1f", alphaComposite.getAlpha());
    }

    static String toString(TestCaseDescription testCase) {
        return "comp=" + toString(testCase.alphaComposite) +
                ",bg=" + toString(testCase.bgColor) +
                ",fg=" + toString(testCase.fgColor) +
                ",grad=" + testCase.gradientPaint +
                ",aa=" + testCase.aaTestVariant;
    }

    static class ComparisonError extends Error {
        @Serial
        private static final long serialVersionUID = 5116118236134877680L;

        public ComparisonError(String message) {
            super(message);
        }
    }

    private static final java.util.List<TestCaseDescription> TEST_CASES = generateTestCases();

    static java.util.List<TestCaseDescription> generateTestCases() {
        final int[] ALPHA_COMPOSITE_RULES = new int[]{
                // All Porter-Duff alpha rules
                AlphaComposite.CLEAR,
                AlphaComposite.SRC,
                AlphaComposite.DST,
                AlphaComposite.SRC_OVER,
                AlphaComposite.DST_OVER,
                AlphaComposite.SRC_IN,
                AlphaComposite.DST_IN,
                AlphaComposite.SRC_OUT,
                AlphaComposite.DST_OUT,
                AlphaComposite.SRC_ATOP,
                AlphaComposite.DST_ATOP,
                AlphaComposite.XOR,
        };
        final float[] ALPHA_COMPOSITE_EXTRA_ALPHAS = new float[]{
                0.0f,  // fully transparent extraAlpha
                0.77f, // extraAlpha
                1.0f   // no extraAlpha
        };
        final Color[] BG_COLORS = new Color[]{
                argb(0xFFDCBA98), // opaque color
                argb(0xD0EDCBA9), // translucent color
                argb(0x00FEDCBA), // fully transparent color
        };
        final Color[] FG_COLORS = new Color[]{
                argb(0xFF89ABCD), // opaque color
                argb(0xC09ABCDE), // translucent color
                argb(0x00ABCDEF), // fully transparent color
        };

        ArrayList<TestCaseDescription> testCases = new ArrayList<>();
        for (var rule : ALPHA_COMPOSITE_RULES) {
            for (var extraAlpha : ALPHA_COMPOSITE_EXTRA_ALPHAS) {
                for (var bgColor : BG_COLORS) {
                    for (var fgColor : FG_COLORS) {
                        for (char gradientPaint = 0; gradientPaint <= 1; ++gradientPaint) {
                            for (var aaTestVariant : AATestVariant.values()) {
                                testCases.add(new TestCaseDescription(
                                        AlphaComposite.getInstance(rule, extraAlpha),
                                        bgColor,
                                        fgColor,
                                        gradientPaint == 1,
                                        aaTestVariant
                                ));
                            }
                        }
                    }
                }
            }
        }
        return testCases;
    }
}
