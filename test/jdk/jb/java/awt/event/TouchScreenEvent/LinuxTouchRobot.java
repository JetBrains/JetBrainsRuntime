/*
 * Copyright 2000-2020 JetBrains s.r.o.
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

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.io.IOException;

public class LinuxTouchRobot extends TouchRobot {
    private LinuxTouchScreenDevice device;

    public LinuxTouchRobot() throws AWTException, IOException {
        super();
        device = new LinuxTouchScreenDevice();
    }

    public LinuxTouchRobot(GraphicsDevice screen) throws AWTException, IOException {
        super(screen);
        device = new LinuxTouchScreenDevice();
    }

    public void touchClick(int fingerId, int x, int y) throws IOException {
        device.click(fingerId, x, y);
    }

    public void touchMove(int fingerId, int fromX, int fromY, int toX, int toY) throws IOException {
        device.move(fingerId, fromX, fromY, toX, toY);
    }
}
