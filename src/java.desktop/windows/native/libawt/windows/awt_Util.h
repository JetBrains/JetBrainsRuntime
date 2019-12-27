// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#ifndef NATIVE_AWT_UTIL_H
#define NATIVE_AWT_UTIL_H

#include <windows.h>

typedef enum {
    DEVICE_SPACE,
    USER_SPACE
} UCoordSpace;

typedef enum {
    RELATIVE_COORD,
    ABSOLUTE_COORD
} UCoordRelativity;

#define RELATIVITY_FOR_COMP_XY(compPtr) compPtr->IsTopLevel() ? ABSOLUTE_COORD : RELATIVE_COORD

typedef struct URectBounds {
    int x;
    int y;
    int width;
    int height;
    UCoordSpace space;

    URectBounds(int _x, int _y, int _w, int _h, const UCoordSpace& _space) : x(_x), y(_y), width(_w), height(_h), space(_space) {}

    URectBounds(const URectBounds& b) : x(b.x), y(b.y), width(b.width), height(b.height), space(b.space) {}

    URectBounds& operator=(const URectBounds& b) {
        x = b.x;
        y = b.y;
        width = b.width;
        height = b.height;
        space = b.space;
        return *this;
    }
} URectBounds;

class AwtComponent;
class AwtWin32GraphicsDevice;

URectBounds UGetWindowRectBounds(HWND hwnd);
AwtWin32GraphicsDevice* UGetDeviceByBounds(const URectBounds& bounds, AwtComponent* comp = NULL);

#endif //NATIVE_AWT_UTIL_H
