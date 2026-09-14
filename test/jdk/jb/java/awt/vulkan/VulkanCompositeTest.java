/*
 * Copyright 2024-2025 JetBrains s.r.o.
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

import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.VKGPU;
import sun.java2d.vulkan.VKGraphicsConfig;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/*
 * @test
 * @requires os.family == "linux" | os.family == "windows"
 * @summary Verifies composites in opaque and translucent modes.
 * @modules java.desktop/sun.java2d.vulkan:+open
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=true VulkanCompositeTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=false VulkanCompositeTest
 */


public class VulkanCompositeTest {
    final static int W = 8;
    final static int H = 8;

    private record TestCaseDescription(AlphaComposite alphaComposite, Paint bgPaint, Paint fgPaint, boolean antiAliasing, boolean opaqueDst) {}

    private record ImageTypeDescriptor(boolean hwAccelerated, boolean opaque) {}

    private static final Map<ImageTypeDescriptor, Image> images = new HashMap<>();

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
                0.0f, // fully transparent extraAlpha
                0.77f, // extraAlpha
                1.0f  // no extraAlpha
        };
        final Paint[] BG_PAINTS = new Paint[]{
                argb(0xFF123456), // opaque color
                argb(0xAB234567), // translucent color
                argb(0x00345678), // fully transparent color
                new GradientPaint(0, 0, argb(0xFF456789), W, H, argb(0xFF6789AB)), // opaque gradient
                new GradientPaint(0, 0, argb(0xDD456789), W, H, argb(0x336789AB)), // non-opaque gradient
                new GradientPaint(0, 0, argb(0x00456789), W, H, argb(0x006789AB)), // non-opaque gradient
        };
        final Paint[] FG_PAINTS = new Paint[]{
                argb(0xFFABCDEF), // opaque color
                argb(0x999ABCDE), // translucent color
                argb(0x0089ABCD), // fully transparent color
                new GradientPaint(W, 0, argb(0xFF654321), 0, H, argb(0xFF543210)), // opaque gradient
                new GradientPaint(W, 0, argb(0x55654321), 0, H, argb(0x77543210)), // non-opaque gradient
                new GradientPaint(W, 0, argb(0x00654321), 0, H, argb(0x00543210)), // fully transparent gradient
        };

        ArrayList<TestCaseDescription> testCases = new ArrayList<>();
        for (var rule : ALPHA_COMPOSITE_RULES) {
            for (var extraAlpha : ALPHA_COMPOSITE_EXTRA_ALPHAS) {
                for (var bgPaint : BG_PAINTS) {
                    for (var fgPaint : FG_PAINTS) {
                        for (char antiAliasing = 0; antiAliasing <= 1; ++antiAliasing) {
                            for (char opaqueDst = 0; opaqueDst <= 1; ++opaqueDst) {
                                testCases.add(new TestCaseDescription(AlphaComposite.getInstance(rule, extraAlpha), bgPaint, fgPaint, antiAliasing == 1, opaqueDst == 1));
                            }
                        }
                    }
                }
            }
        }
        return testCases;
    }

    private static final java.util.List<TestCaseDescription> TEST_CASES = generateTestCases();

    static boolean isTestCaseCurrentlyExpectedToFail(TestCaseDescription desc) {
        // IGNORED until JBR-10598 is fixed:
        // DST_IN + srcAlpha=0 + opaqueDst + noAA
        // DST_ATOP + srcAlpha=0 + opaqueDst + noAA
        // XOR + srcAlpha=1 + opaqueDst + noAA
        // DST_OUT + srcAlpha=1 + opaqueDst + noAA

        if (!desc.opaqueDst || desc.antiAliasing) {
            return false;
        }

        // This doesn't actually check the real pixels, so rounding can make this test wrong,
        // but it's good enough to work on the specific test cases.
        boolean fgPaintHasFullyOpaquePixels;
        boolean fgPaintHasFullyTransparentPixels;
        if (desc.fgPaint instanceof Color color) {
            fgPaintHasFullyOpaquePixels = color.getAlpha() == 255;
            fgPaintHasFullyTransparentPixels = color.getAlpha() == 0;
        } else if (desc.fgPaint instanceof GradientPaint gradient) {
            fgPaintHasFullyOpaquePixels = gradient.getColor1().getAlpha() == 255 || gradient.getColor2().getAlpha() == 255;
            fgPaintHasFullyTransparentPixels = gradient.getColor1().getAlpha() == 0 || gradient.getColor2().getAlpha() == 0;
        } else {
            throw new Error("unhandled paint type in isTestCaseIgnored");
        }
        boolean srcHasFullyOpaquePixels = fgPaintHasFullyOpaquePixels && desc.alphaComposite.getAlpha() >= 1.0f;
        boolean srcHasFullyTransparentPixels = fgPaintHasFullyTransparentPixels || desc.alphaComposite.getAlpha() <= 0.0f;

        int rule = desc.alphaComposite.getRule();

        if (rule == AlphaComposite.DST_IN || rule == AlphaComposite.DST_ATOP) {
            return srcHasFullyTransparentPixels;
        }
        if (rule == AlphaComposite.SRC || rule == AlphaComposite.SRC_IN) {
            return srcHasFullyTransparentPixels && !(desc.fgPaint instanceof Color);
        }
        if (rule == AlphaComposite.XOR || rule == AlphaComposite.DST_OUT) {
            return srcHasFullyOpaquePixels;
        }

        return false;
    }

    static Color argb(int argb) {
        return new Color(argb, true);
    }

    static boolean arePremultipliedColorsEqualWithinTolerance(Color c1, Color c2, double tolerance) {
        double alpha1 = c1.getAlpha() / 255.0;
        double alpha2 = c2.getAlpha() / 255.0;
        return Math.abs(c1.getRed() * alpha1 - c2.getRed() * alpha2) <= tolerance &&
                Math.abs(c1.getGreen() * alpha1 - c2.getGreen() * alpha2) <= tolerance &&
                Math.abs(c1.getBlue() * alpha1 - c2.getBlue() * alpha2) <= tolerance &&
                Math.abs(c1.getAlpha() - c2.getAlpha()) <= tolerance;
    }

    static void paint(Image image, TestCaseDescription desc) {
        Graphics2D g = (Graphics2D) image.getGraphics();

        g.setComposite(AlphaComposite.Src);
        g.setPaint(desc.bgPaint);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.fillRect(0, 0, W, H);

        g.setComposite(desc.alphaComposite);
        g.setPaint(desc.fgPaint);
        if (desc.antiAliasing) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final double OVAL_MARGIN = 1.5;
            g.fill(new Ellipse2D.Double(OVAL_MARGIN, OVAL_MARGIN, W - 2 * OVAL_MARGIN, H - 2 * OVAL_MARGIN));
        } else {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.fillRect(0, 0, W, H);
        }

        g.dispose();
    }

    static String toString(Paint paint) {
        if (paint instanceof Color c) {
            return "(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "," + c.getAlpha() + ")";
        } else if (paint instanceof GradientPaint gradient) {
            return "grad[" + toString(gradient.getColor1()) + "," + toString(gradient.getColor2()) + "]";
        } else {
            throw new Error("unhandled paint type in toString");
        }
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
        return ruleName + "+ea=" + alphaComposite.getAlpha();
    }

    static class ComparisonError extends Error {
        public ComparisonError(String message) {
            super(message);
        }
    }

    static void validateByComparison(String testCaseName, BufferedImage referenceImage, VolatileImage checkedImage, double tolerance) throws IOException {
        BufferedImage bufferedCheckedImage = checkedImage.getSnapshot();
        for (int x = 0; x < W; ++x) {
            for (int y = 0; y < H; ++y) {
                Color expected = argb(referenceImage.getRGB(x, y));
                Color actual = argb(bufferedCheckedImage.getRGB(x, y));
                if (!arePremultipliedColorsEqualWithinTolerance(expected, actual, tolerance)) {
                    ImageIO.write(referenceImage, "PNG", new File(testCaseName + ",EXPECTED.png"));
                    ImageIO.write(bufferedCheckedImage, "PNG", new File(testCaseName + ",ACTUAL.png"));
                    throw new ComparisonError("Unexpected color at " + x + "," + y + ": " + toString(actual) + ", expected: " + toString(expected));
                }
            }
        }
    }

    private record FailedCase(String testCaseName, Throwable cause) {}
    static final ArrayList<FailedCase> failedCases = new ArrayList<>();
    static final ArrayList<FailedCase> succeededCasesThatExpectedFailure = new ArrayList<>();

    static final double NON_AA_COLOR_TOLERANCE = 2.0;
    static final double AA_COLOR_TOLERANCE = 3.0;
    static final double NON_AA_GRADIENT_TOLERANCE = 15.0;
    static final double AA_GRADIENT_TOLERANCE = 15.0;

    static void test(VKGraphicsConfig vkgc) {
        GraphicsConfiguration config = (GraphicsConfiguration) vkgc;
        for (char opaque = 0; opaque <= 1; ++opaque) {
            VolatileImage image = config.createCompatibleVolatileImage(W, H, opaque == 1 ? VolatileImage.OPAQUE : VolatileImage.TRANSLUCENT);
            if (image.validate(config) == VolatileImage.IMAGE_INCOMPATIBLE) throw new Error("Image validation failed");
            images.put(new ImageTypeDescriptor(true, opaque == 1), image);
        }

        String prefix = vkgc.descriptorString();

        for (var testCase : TEST_CASES) {
            String testCaseName = prefix +
                    ",comp=" + toString(testCase.alphaComposite) +
                    ",bg=" + toString(testCase.bgPaint) +
                    ",fg=" + toString(testCase.fgPaint) +
                    ",aa=" + testCase.antiAliasing +
                    ",opaqueDst=" + testCase.opaqueDst;

            Throwable throwable = null;
            try {
                BufferedImage swImage = (BufferedImage) images.get(new ImageTypeDescriptor(false, testCase.opaqueDst));
                paint(swImage, testCase);

                VolatileImage hwImage = (VolatileImage) images.get(new ImageTypeDescriptor(true, testCase.opaqueDst));
                if (hwImage.validate(config) != VolatileImage.IMAGE_OK) {
                    throw new Error("Image validation failed");
                }
                paint(hwImage, testCase);
                if (hwImage.contentsLost()) {
                    throw new Error("Image contents lost");
                }
                boolean anyPaintIsGradient = testCase.fgPaint instanceof GradientPaint || testCase.bgPaint instanceof GradientPaint;
                validateByComparison(testCaseName, swImage, hwImage, testCase.antiAliasing ?
                        (anyPaintIsGradient ? AA_GRADIENT_TOLERANCE : AA_COLOR_TOLERANCE) :
                        (anyPaintIsGradient ? NON_AA_GRADIENT_TOLERANCE : NON_AA_COLOR_TOLERANCE));
            } catch (Throwable e) {
                throwable = e;
                failedCases.add(new FailedCase(testCaseName, e));
            }

            boolean expectedToFailWithComparisonError = isTestCaseCurrentlyExpectedToFail(testCase);
            if (throwable != null && (!expectedToFailWithComparisonError || !(throwable instanceof ComparisonError))) {
                failedCases.add(new FailedCase(testCaseName, throwable));
            } else if (throwable == null && expectedToFailWithComparisonError) {
                succeededCasesThatExpectedFailure.add(new FailedCase(testCaseName, throwable));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (!VKEnv.isVulkanEnabled()) {
            throw new Error("Vulkan not enabled");
        }

        for (char opaque = 0; opaque <= 1; ++opaque) {
            images.put(new ImageTypeDescriptor(false, opaque == 1), new BufferedImage(W, H, opaque == 1 ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB_PRE));
        }

        VKEnv.getDevices().flatMap(VKGPU::getOffscreenGraphicsConfigs).forEach(gc -> {
            System.out.println("Testing " + gc);
            test(gc);
        });

        boolean testFailed = false;
        if (!failedCases.isEmpty()) {
            testFailed = true;
            System.out.println("Some cases failed:\n");
            failedCases.forEach(failedCase -> {
                System.out.println("  Test case: " + failedCase.testCaseName);
                System.out.println("     Reason: " + failedCase.cause);
            });
        }
        if (!succeededCasesThatExpectedFailure.isEmpty()) {
            testFailed = true;
            System.out.println("Some cases succeeded but were expected to fail:\n");
            succeededCasesThatExpectedFailure.forEach(failedCase -> {
                System.out.println("  Test case: " + failedCase.testCaseName);
            });
        }

        if (testFailed) {
            throw new Error("Some cases failed, see System.out for details");
        }
    }
}
