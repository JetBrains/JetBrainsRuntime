/*
 * Copyright 2017 JetBrains s.r.o.
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
package quality.util.osx;

import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;


public class MacUtil {
    public static ID findWindowFromJavaWindow(final Window w) {
        ID windowId;
        try {
            @SuppressWarnings("deprecation")
            Class<?> cWindowPeerClass = w.getPeer().getClass();

            @SuppressWarnings("JavaReflectionMemberAccess")
            Method getPlatformWindowMethod = cWindowPeerClass.getDeclaredMethod("getPlatformWindow");

            @SuppressWarnings("deprecation")
            Object cPlatformWindow = getPlatformWindowMethod.invoke(w.getPeer());

            Class<?> cPlatformWindowClass = cPlatformWindow.getClass();
            Method getNSWindowPtrMethod = cPlatformWindowClass.getDeclaredMethod("getNSWindowPtr");
            windowId = new ID((Long) getNSWindowPtrMethod.invoke(cPlatformWindow));
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return windowId;
    }
}
