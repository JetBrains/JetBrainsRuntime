/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
package test.jb.testhelpers.screenshot;

import com.jetbrains.WindowDecorations;

import test.jb.testhelpers.screenshot.Rect;
import test.jb.testhelpers.screenshot.RectCoordinates;
import test.jb.testhelpers.TitleBar.TestUtils;
import javax.imageio.ImageIO;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class ScreenShotHelpers {

    public static BufferedImage takeScreenshot(Window window) throws AWTException {
        Robot robot = new Robot();
        robot.delay(1000);

        return robot.createScreenCapture(
                new Rectangle(window.getLocationOnScreen().x, window.getLocationOnScreen().y,
                        window.getWidth(), window.getHeight()));
    }

    public static String storeScreenshot(String namePrefix, BufferedImage image) throws IOException {
        final String fileName = String.format("%s-%s.png", namePrefix, UUID.randomUUID());
        File file = new File(fileName);
        ImageIO.write(image, "png", file);
        return file.getAbsolutePath();
    }

    public static List<Rect> findControls(BufferedImage image, Window window, WindowDecorations.CustomTitleBar titleBar) {
        return findControls(image, window, titleBar, false);
    }

    public static List<Rect> findControls(BufferedImage image, Window window, WindowDecorations.CustomTitleBar titleBar, boolean disabledControls) {
        final int windowWidth = window.getWidth();
        final int windowHeight = window.getHeight();
        final int titleBarHeight = (int) titleBar.getHeight();
        final int leftInset = Math.round(titleBar.getLeftInset());
        final int rightInset = Math.round(titleBar.getRightInset());

        System.out.println("Image w = " + image.getWidth() + " height = " + image.getHeight());
        System.out.println("Window w = " + windowWidth + " height = " + window.getHeight());

        // option 1
        List<Rect> controls1 = detectControlsByBackground(image, titleBarHeight, TestUtils.TITLE_BAR_COLOR);
        System.out.println("Option1 for " + window.getName());
        controls1.forEach(System.out::println);

        if (isMatchCondition(window, controls1.size(), disabledControls)) {
            return controls1;
        }

        // option2
        List<Rect> controls2 = detectControlsByBackground2(image, windowWidth, windowHeight, titleBarHeight, leftInset, rightInset, TestUtils.TITLE_BAR_COLOR);
        System.out.println("Option2 for " + window.getName());
        controls2.forEach(System.out::println);

        if (isMatchCondition(window, controls2.size(), disabledControls)) {
            return controls2;
        }

        // option3
        List<Rect> controls3 = detectControls(image, titleBarHeight, leftInset, rightInset);
        System.out.println("Option3 for " + window.getName());
        controls3.forEach(System.out::println);

        if (isMatchCondition(window, controls3.size(), disabledControls)) {
            return controls3;
        }

        // option4
        List<Rect> controls4 = detectControls2(image, windowWidth, windowHeight, titleBarHeight, leftInset, rightInset);
        System.out.println("Option4 for " + window.getName());
        controls4.forEach(System.out::println);

        return controls4;
    }

    private static boolean isMatchCondition(Window window, int size, boolean disabledControls) {
        if (!disabledControls) {
            if (window.getName().equals("Frame") || window.getName().equals("JFrame")) {
                return size == 3;
            }
            if (window.getName().equals("Dialog") || window.getName().equals("JDialog")) {
                final String os = System.getProperty("os.name").toLowerCase();
                if (os.startsWith("windows")) {
                    return size == 1;
                }
                return size == 3;
            }
        }
        return size == 0;
    }

    public static RectCoordinates calculateControlsRect(BufferedImage image, int windowWidth, int windowHeight, int titleBarHeight, int leftInset, int rightInset) {
        final int shiftW = (image.getWidth() - windowWidth) / 2;
        final int shiftH = (image.getHeight() - windowHeight) / 2;
        int x1;
        int y1 = shiftH;
        int x2;
        int y2 = shiftH + titleBarHeight;
        if (leftInset > rightInset) {
            x1 = shiftW;
            x2 = shiftW + leftInset;
        } else {
            x1 = shiftW + windowWidth - rightInset;
            x2 = windowWidth - shiftW;
        }
        return new RectCoordinates(x1, y1, x2, y2);
    }

    public static RectCoordinates findRectangleTitleBar(BufferedImage image, int titleBarHeight) {
        int centerX = image.getWidth() / 2;
        int centerY = titleBarHeight / 2;

        final Color centerColor = adjustColor(new Color(image.getRGB(centerX, centerY)));

        int startY = centerY;
        for (int y = centerY; y >= 0; y--) {
            Color adjustedColor = adjustColor(new Color(image.getRGB(centerX, y)));
            if (!adjustedColor.equals(centerColor)) {
                startY = y + 1;
                break;
            }
        }

        int endY = centerY;
        for (int y = centerY; y <= (int) TestUtils.TITLE_BAR_HEIGHT; y++) {
            Color adjustedColor = adjustColor(new Color(image.getRGB(centerX, y)));
            if (!adjustedColor.equals(centerColor)) {
                endY = y - 1;
                break;
            }
        }

        int startX = centerX;
        for (int x = centerX; x >= 0; x--) {
            Color adjustedColor = adjustColor(new Color(image.getRGB(x, startY)));
            if (!adjustedColor.equals(centerColor)) {
                startX = x + 1;
                break;
            }
        }

        int endX = centerX;
        for (int x = centerX; x < image.getWidth(); x++) {
            Color adjustedColor = adjustColor(new Color(image.getRGB(x, startY)));
            if (!adjustedColor.equals(centerColor)) {
                endX = x - 1;
                break;
            }
        }

        return new RectCoordinates(startX, startY, endX, endY);
    }

    public static List<Rect> detectControlsByBackground(BufferedImage image, int titleBarHeight, Color backgroundColor) {
        RectCoordinates coords = findRectangleTitleBar(image, titleBarHeight);
        List<Rect> result = new ArrayList<>();

        System.out.println("Detect controls by background");
        System.out.println(coords);

        List<Integer> lefts = new ArrayList<>();
        List<Integer> rights = new ArrayList<>();

        int leftX = -1;
        int rightX = -1;

        for (int x = coords.x1(); x < coords.x2(); x++) {
            boolean isBackground = true;
            for (int y = coords.y1(); y < coords.y2(); y++) {
                Color color = adjustColor(new Color(image.getRGB(x, y)));
                if (!color.equals(backgroundColor)) {
                    isBackground = false;
                    break;
                }
            }
            if (!isBackground) {
                if (leftX == -1) {
                    leftX = x;
                }
                rightX = x;
            } else if (leftX != -1) {
                lefts.add(leftX);
                rights.add(rightX);
                leftX = -1;
                rightX = -1;
            }
        }

        for (int i = 0; i < lefts.size(); i++) {
            int lx = lefts.get(i);
            int rx = rights.get(i);

            System.out.println("lx = " + lx + " rx = " + rx);
            result.add(new Rect(lx, coords.y1(), rx, coords.y2(), 0, Color.BLACK));
        }
        return result;
    }

    public static List<Rect> detectControlsByBackground2(BufferedImage image, int windowWidth, int windowHeight, int titleBarHeight, int leftInset, int rightInset, Color backgroundColor) {
        RectCoordinates coords = calculateControlsRect(image, windowWidth, windowHeight, titleBarHeight, leftInset, rightInset);
        List<Rect> result = new ArrayList<>();

        System.out.println("Detect controls by background2");
        System.out.println(coords);

        List<Integer> lefts = new ArrayList<>();
        List<Integer> rights = new ArrayList<>();

        int leftX = -1;
        int rightX = -1;

        for (int x = coords.x1(); x < coords.x2(); x++) {
            boolean isBackground = true;
            for (int y = coords.y1(); y < coords.y2(); y++) {
                Color color = adjustColor(new Color(image.getRGB(x, y)));
                if (!color.equals(backgroundColor)) {
                    isBackground = false;
                    break;
                }
            }
            if (!isBackground) {
                if (leftX == -1) {
                    leftX = x;
                }
                rightX = x;
            } else if (leftX != -1) {
                lefts.add(leftX);
                rights.add(rightX);
                leftX = -1;
                rightX = -1;
            }
        }

        for (int i = 0; i < lefts.size(); i++) {
            int lx = lefts.get(i);
            int rx = rights.get(i);

            System.out.println("lx = " + lx + " rx = " + rx);
            result.add(new Rect(lx, coords.y1(), rx, coords.y2(), 0, Color.BLACK));
        }
        return result;
    }

    public static List<Rect> detectControls(BufferedImage image, int titleBarHeight, int leftInset, int rightInset) {
        RectCoordinates coords = findRectangleTitleBar(image, titleBarHeight);
        System.out.println("Detect controls");
        System.out.println(coords);

        Map<Color, Rect> map = new HashMap<>();

        for (int x = coords.x1(); x < coords.x2(); x++) {
            for (int y = coords.y1(); y < coords.y2(); y++) {
                Color color = new Color(image.getRGB(x, y));
                Color adjustedColor = adjustColor(color);
                Rect rect = map.getOrDefault(adjustedColor, new Rect(adjustedColor));
                rect.addPoint(x, y);
                map.put(adjustedColor, rect);
            }
        }

        int checkedHeight = coords.y2() - coords.y1() + 1;
        int checkedWidth = coords.x2() - coords.x1() + 1;
        int pixels = checkedWidth * checkedHeight;

        int nonCoveredAreaApprox = pixels - (leftInset * checkedHeight + rightInset * checkedHeight);

        List<Rect> rects = map.values().stream().filter(v -> v.getPixelCount() < nonCoveredAreaApprox).toList();

        return groupRects(rects);
    }

    public static List<Rect> detectControls2(BufferedImage image, int windowWidth, int windowHeight, int titleBarHeight, int leftInset, int rightInset) {
        RectCoordinates coords = calculateControlsRect(image, windowWidth, windowHeight, titleBarHeight, leftInset, rightInset);
        System.out.println("Detect controls 2");
        System.out.println(coords);

        Map<Color, Rect> map = new HashMap<>();

        for (int x = coords.x1(); x < coords.x2(); x++) {
            for (int y = coords.y1(); y < coords.y2(); y++) {
                Color color = new Color(image.getRGB(x, y));
                Color adjustedColor = adjustColor(color);
                Rect rect = map.getOrDefault(adjustedColor, new Rect(adjustedColor));
                rect.addPoint(x, y);
                map.put(adjustedColor, rect);
            }
        }

        int checkedHeight = coords.y2() - coords.y1() + 1;
        int checkedWidth = coords.x2() - coords.x1() + 1;
        int pixels = checkedWidth * checkedHeight;

        int nonCoveredAreaApprox = pixels - (leftInset * checkedHeight + rightInset * checkedHeight);

        List<Rect> rects = map.values().stream().filter(v -> v.getPixelCount() < nonCoveredAreaApprox).toList();

        return groupRects(rects);
    }

    private static List<Rect> groupRects(List<Rect> rects) {
        List<Rect> found = new ArrayList<>();

        List<Rect> items = new ArrayList<>(rects);
        while (!items.isEmpty()) {
            AtomicReference<Rect> rect = new AtomicReference<>(items.remove(0));

            List<Rect> restItems = new ArrayList<>();
            items.forEach(item -> {
                Rect intersected = intersect(rect.get(), item);
                if (intersected != null) {
                    rect.set(intersected);
                } else {
                    restItems.add(item);
                }
            });
            found.add(rect.get());
            items = restItems;
        }

        return found;
    }

    private static Color adjustColor(Color color) {
        int r = adjustValue(color.getRed());
        int g = adjustValue(color.getGreen());
        int b = adjustValue(color.getBlue());
        return new Color(r, g, b);
    }

    private static int adjustValue(int value) {
        final int round = 64;
        int div = (value + 1) / round;
        int mod = (value + 1) % round;
        int result = div > 0 ? round * div - 1 : 0;
        if (mod > 32) result += round;
        return result;
    }

    private static Rect intersect(Rect r1, Rect r2) {
        int x1 = -1, x2 = -1, y1 = -1, y2 = -1;
        if (r1.getX1() <= r2.getX1() && r2.getX1() <= r1.getX2()) {
            x1 = r1.getX1();
            x2 = Math.max(r2.getX2(), r1.getX2());
        }
        if (r2.getX1() <= r1.getX1() && r1.getX1() <= r2.getX2()) {
            x1 = r2.getX1();
            x2 = Math.max(r2.getX2(), r1.getX2());
        }

        if (r1.getY1() < r2.getY1() && r2.getY1() <= r2.getY1()) {
            y1 = r1.getY1();
            y2 = Math.max(r1.getY2(), r2.getY2());
        }
        if (r2.getY1() <= r1.getY1() && r1.getY1() <= r2.getY2()) {
            y1 = r2.getY1();
            y2 = Math.max(r1.getY2(), r2.getY2());
        }
        if (x1 == -1 || x2 == -1 || y1 == -1 || y2 == -1) {
            return null;
        }

        Color commonColor = r1.getPixelCount() > r2.getPixelCount() ? r1.getCommonColor() : r2.getCommonColor();

        return new Rect(x1, y1, x2, y2, r1.getPixelCount() + r2.getPixelCount(), commonColor);
    }


}