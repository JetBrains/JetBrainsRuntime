//
// Created by dmitrii.morskii on 21.06.2023.
//

#ifndef WINDOWS_X86_64_SERVER_SLOWDEBUG_DPISCALING_H
#define WINDOWS_X86_64_SERVER_SLOWDEBUG_DPISCALING_H

#include <Windows.h>

class DPIScaling {
private:
    struct ScalingInfo {
        int maximum;
        int current;
        int recommended;
    };
    struct DISPLAYCONFIG_SOURCE_DPI_SCALE_GET {
        DISPLAYCONFIG_DEVICE_INFO_HEADER header;
        int minScaleRel;
        int curScaleRel;
        int maxScaleRel;
    };
    struct DISPLAYCONFIG_SOURCE_DPI_SCALE_SET {
        DISPLAYCONFIG_DEVICE_INFO_HEADER header;
        int scaleRel;
    };
    static bool setupPathInfo(int screen, LUID &adapterId, int &sourceId);
    static bool getDPIScalingInfo(LUID adapterID, int sourceID, ScalingInfo &scalingInfo);
    static bool setDPIScaling(LUID adapterID, UINT32 sourceID, UINT32 scale);
public:
    static bool setOSScale(int screen, int scale);
    static int getOSScale(int screen);
};

#endif //WINDOWS_X86_64_SERVER_SLOWDEBUG_DPISCALING_H
