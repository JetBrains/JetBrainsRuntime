/*
 * Copyright 2000-2023 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package util;

import java.awt.Color;
import java.util.Objects;

public class Rect {

    private int x1 = -1;
    private int y1 = -1;
    private int x2 = -1;
    private int y2 = -1;
    private int pixelCount = 0;
    private final Color commonColor;

    public Rect(Color commonColor) {
        this.commonColor = commonColor;
    }

    public Rect(int x1, int y1, int x2, int y2, int pixelCount, Color commonColor) {
        this.x1 = x1;
        this.x2 = x2;
        this.y1 = y1;
        this.y2 = y2;
        this.pixelCount = pixelCount;
        this.commonColor = commonColor;
    }

    public int getX1() {
        return x1;
    }

    public int getY1() {
        return y1;
    }

    public int getX2() {
        return x2;
    }

    public int getY2() {
        return y2;
    }

    public void addPoint(int x, int y) {
        pixelCount++;
        if (x1 == -1 || x2 == -1) {
            x1 = x;
            x2 = x;
        }
        if (y1 == -1 || y2 == -1) {
            y1 = y;
            y2 = y;
        }
        if (x < x1) x1 = x;
        if (x > x2) x2 = x;
        if (y < y1) y1 = y;
        if (y > y1) y2 = y;
    }

    public int getPixelCount() {
        return pixelCount;
    }

    public Color getCommonColor() {
        return commonColor;
    }

    @Override
    public String toString() {
        return "Rect{" +
                "x1=" + x1 +
                ", y1=" + y1 +
                ", x2=" + x2 +
                ", y2=" + y2 +
                ", pixelCount=" + pixelCount +
                ", commonColor=" + commonColor +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Rect rect = (Rect) o;
        return x1 == rect.x1 && y1 == rect.y1 && x2 == rect.x2 && y2 == rect.y2;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x1, y1, x2, y2);
    }
}
