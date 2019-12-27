// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "awt_Util.h"
#include "awt_Component.h"
#include "awt_Win32GraphicsDevice.h"
#include "awt_Win32GraphicsConfig.h"

AwtWin32GraphicsDevice* UGetDeviceByBounds(const URectBounds& bounds, AwtComponent* comp)
{
    URectBounds _bounds(bounds);
    if (comp != NULL && !comp->IsTopLevel()) {
        // use the ancestor window to match the device
        _bounds = UGetWindowRectBounds(::GetAncestor(comp->GetHWnd(), GA_ROOT));
    }
    Devices::InstanceAccess devices;
    POINT center = {_bounds.x + _bounds.width / 2, _bounds.y + _bounds.height / 2};
    for (int i = 0; i < devices->GetNumDevices(); i++) {
        RECT rect = AwtWin32GraphicsConfig::getMonitorBounds(i, _bounds.space);
        if (::PtInRect(&rect, center)) {
            return devices->GetDevice(i);
        }
    }
    // default to the hwnd's device
    return comp != NULL ? devices->GetDevice(AwtWin32GraphicsDevice::DeviceIndexForWindow(comp->GetHWnd())) : NULL;
}

URectBounds UGetWindowRectBounds(HWND hwnd)
{
    RECT r;
    ::GetWindowRect(hwnd, &r);
    return URectBounds(r.left, r.top, r.right - r.left, r.bottom - r.top, DEVICE_SPACE);
}
