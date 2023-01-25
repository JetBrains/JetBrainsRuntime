/*
 * Copyright (c) 2000-2023 JetBrains s.r.o.
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

package com.apple.eawt.event;

public class JBRForceTouchAdapter {
    private static class Event {
        private boolean consumed = false;
        final private double pressure;
        final private int stage;

        Event(double pressure, int stage) {
            this.pressure = pressure;
            this.stage = stage;
        }

        public boolean isConsumed() {
            return consumed;
        }

        public void consume() {
            consumed = true;
        }

        public double getPressure() {
            return pressure;
        }

        public int getStage() {
            return stage;
        }
    }

    private interface Listener extends java.util.EventListener {
        void forceTouch(Event event);
    }

    private static void addForceTouchListener(javax.swing.JComponent component, Listener listener) {
        GestureUtilities.addGestureListenerTo(component, new PressureListener() {
            @Override
            public void pressure(PressureEvent e) {
                if (e.getStage() != 2) {
                    return;
                }

                listener.forceTouch(new Event(e.getPressure(), (int)e.getStage()));
            }
        });
    }
}
