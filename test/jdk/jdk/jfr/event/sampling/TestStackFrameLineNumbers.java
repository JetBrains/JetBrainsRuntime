/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

package jdk.jfr.event.sampling;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.List;
import java.util.Random;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.test.lib.jfr.EventNames;
import jdk.test.lib.jfr.EventVerifier;
import jdk.test.lib.jfr.Events;

/*
 * @test
 * @summary Tests that the method sampling events contain at least some data with line number information
 * @key jfr
 * @bug 8262121
 * @requires vm.hasJFR
 * @library /test/lib
 * @run main/othervm jdk.jfr.event.sampling.TestStackFrameLineNumbers
 */
public class TestStackFrameLineNumbers {
    public static void main(String[] args) throws Exception {
        Random rnd = new Random();
        FlightRecorder.getFlightRecorder();
        Recording recording = new Recording();
        recording.enable(EventNames.ExecutionSample).withPeriod(Duration.ofMillis(1));
        recording.start();

        long ts = System.nanoTime() + 200_000_000L; // wait for 200ms before continuing
        long accumulator = 0L;
        while (System.nanoTime() < ts) {
            accumulator += number(rnd);
        }
        System.out.println("Accumulated value = " + accumulator);
        recording.stop();

        if (recording.getSize() == 0) {
            throw new Exception("Empty recording");
        }

        List<RecordedEvent> events = Events.fromRecording(recording);

        for (RecordedEvent re : Events.fromRecording(recording)) {
            if (re.getEventType().getName().equals(EventNames.ExecutionSample)) {
                RecordedStackTrace rs = re.getStackTrace();
                for (RecordedFrame frame : rs.getFrames()) {
                    if (frame.getLineNumber() > 0) {
                        return; // hooray, we have line numbers
                    } else {
                      System.out.println("=== Frame: " + frame);
                    }
                }
            }
        }
        recording.close();

        throw new Exception("No line number information found");
    }

    private static long number(Random rnd) {
        long number = rnd.nextInt(13);
        int upperBound = rnd.nextInt(11);
        for (int i = 0; i < upperBound; i++) {
            number += number ^ rnd.nextInt(32761);
        }
        return number;
    }
}
