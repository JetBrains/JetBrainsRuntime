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
package util;

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

        final BufferedImage screenShot = robot.createScreenCapture(
                new Rectangle(window.getLocationOnScreen().x, window.getLocationOnScreen().y,
                        window.getWidth(), window.getHeight()));
        return screenShot;
    }

    public static String storeScreenshot(String namePrefix, BufferedImage image) throws IOException {
        final String fileName = String.format("%s-%s.png", namePrefix, UUID.randomUUID());
        File file = new File(fileName);
        ImageIO.write(image, "png", file);
        return file.getAbsolutePath();
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

        System.out.println(coords);

        List<Integer> lefts = new ArrayList<>();
        List<Integer> rights = new ArrayList<>();

        int leftX = -1;
        int rightX = -1;

        for (int x = coords.x1(); x <= coords.x2(); x++) {
            boolean isBackground = true;
            for (int y = coords.y1(); y <= coords.y2(); y++) {
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
        RectCoordinates coords = ScreenShotHelpers.findRectangleTitleBar(image, titleBarHeight);

        Map<Color, Rect> map = new HashMap<>();

        for (int x = coords.x1(); x <= coords.x2(); x++) {
            for (int y = coords.y1(); y <= coords.y2(); y++) {
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
