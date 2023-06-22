/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
 *
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

#include "DPIScaling.h"

#include <vector>
#include <memory>

static const std::vector<UINT32> scales = {100, 125, 150, 175, 200, 225, 250, 300, 350, 400, 450, 500};
static const int DISPLAYCONFIG_DEVICE_INFO_GET_DPI_SCALE = -3;
static const int DISPLAYCONFIG_DEVICE_INFO_SET_DPI_SCALE = -4;

bool DPIScaling::setupPathInfo(int screen, LUID &adapterId, int &sourceId) {
    UINT32 numPaths = 0, numModes = 0;
    if (ERROR_SUCCESS != GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &numPaths, &numModes)) {
        return false;
    }

    std::unique_ptr<DISPLAYCONFIG_PATH_INFO[]> paths(new DISPLAYCONFIG_PATH_INFO[numPaths]);
    std::unique_ptr<DISPLAYCONFIG_MODE_INFO[]> modes(new DISPLAYCONFIG_MODE_INFO[numModes]);
    if (ERROR_SUCCESS != QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &numPaths, paths.get(), &numModes, modes.get(), nullptr)) {
        return false;
    }

    adapterId = paths[screen].targetInfo.adapterId;
    sourceId = paths[screen].sourceInfo.id;
    return true;
}

bool DPIScaling::getDPIScalingInfo(LUID adapterID, int sourceID, ScalingInfo &scalingInfo) {
    DISPLAYCONFIG_SOURCE_DPI_SCALE_GET requestPacket = {};
    requestPacket.header.type = (DISPLAYCONFIG_DEVICE_INFO_TYPE)DISPLAYCONFIG_DEVICE_INFO_GET_DPI_SCALE;
    requestPacket.header.size = sizeof(requestPacket);
    requestPacket.header.adapterId = adapterID;
    requestPacket.header.id = sourceID;

    if (ERROR_SUCCESS != DisplayConfigGetDeviceInfo(&requestPacket.header)) {
        return false;
    }

    int recommended = abs(requestPacket.minScaleRel);
    scalingInfo.current = recommended + requestPacket.curScaleRel;
    scalingInfo.recommended = recommended;
    scalingInfo.maximum = (int)min(scales.size() - 1, recommended + requestPacket.maxScaleRel);
    return true;
}

bool DPIScaling::setDPIScaling(LUID adapterID, UINT32 sourceID, UINT32 scale) {
    ScalingInfo scalingInfo;
    if (!getDPIScalingInfo(adapterID, sourceID, scalingInfo)) {
        return false;
    }

    if (scale == scalingInfo.current) {
        return true;
    }

    scale = max(scale, scales[0]);
    scale = min(scale, scales[scalingInfo.maximum]);

    auto it = find(scales.begin(), scales.end(), scale);
    if (it == scales.end()) {
        return false;
    }

    UINT32 scaleToSet = (it - scales.begin()) - scalingInfo.recommended;

    DISPLAYCONFIG_SOURCE_DPI_SCALE_SET setPacket = {};
    setPacket.header.adapterId = adapterID;
    setPacket.header.id = sourceID;
    setPacket.header.size = sizeof(setPacket);
    setPacket.header.type = (DISPLAYCONFIG_DEVICE_INFO_TYPE)DISPLAYCONFIG_DEVICE_INFO_SET_DPI_SCALE;
    setPacket.scaleRel = scaleToSet;

    return (ERROR_SUCCESS == DisplayConfigSetDeviceInfo(&setPacket.header));
}

bool DPIScaling::setOSScale(int screen, int scale) {
    LUID adapterId;
    int sourceId;
    if (!setupPathInfo(screen, adapterId, sourceId)) {
        return false;
    }

    return setDPIScaling(adapterId, sourceId, scale);
}

int DPIScaling::getOSScale(int screen) {
    LUID adapterId;
    int sourceId;
    if (!setupPathInfo(screen, adapterId, sourceId)) {
        return 0;
    }

    ScalingInfo scalingInfo;
    if (!getDPIScalingInfo(adapterId, sourceId, scalingInfo)) {
        return 0;
    }

    return scales[scalingInfo.current];
}