/*
 * Copyright (c) 2002, 2024, Oracle and/or its affiliates. All rights reserved.
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

package nsk.jdi.ClassType.invokeMethod;

import nsk.share.*;
import nsk.share.jpda.*;
import nsk.share.jdi.*;

import com.sun.jdi.*;
import com.sun.jdi.request.*;
import com.sun.jdi.event.*;

import java.util.*;
import java.io.*;

/**
 *  Test checks up that NullPointerException will be thrown in
 *  the following cases:                                                        <br>
 *      - invokeMethod(null, method, params,ClassType.INVOKE_SINGLE_THREADED)   <br>
 *      - invokeMethod(thread, null, params,ClassType.INVOKE_SINGLE_THREADED)   <br>
 *      - invokeMethod(thread, method, null,ClassType.INVOKE_SINGLE_THREADED)   <br>
 *  In case                                                     <br>
 *      invokeMethod(thread, method, params,Integer.MAX_VALUE)  <br>
 *  no exception is expected.
 */

public class invokemethod006 {

    private final static String prefix = "nsk.jdi.ClassType.invokeMethod.";
    private final static String debuggerName = prefix + "invokemethod006";
    private final static String debugeeName = debuggerName + "a";

    public final static String SGNL_READY = "ready";
    public final static String SGNL_QUIT = "quit";

    private static int exitStatus;
    private static Log log;
    private static Debugee debugee;
    private static long waitTime;

    private ClassType testedClass;
    private ThreadReference thread;

    class TestRuntimeException extends RuntimeException {
        TestRuntimeException(String msg) {
            super("TestRuntimeException: " + msg);
        }
    }

    public final static String method2Invoke = "justMethod";

    private static void display(String msg) {
        log.display(msg);
    }

    private static void complain(String msg) {
        log.complain("debugger FAILURE> " + msg + "\n");
    }

    public static void main(String argv[]) {
        int result = run(argv,System.out);
        if (result != 0) {
            throw new RuntimeException("TEST FAILED with result " + result);
        }
    }

    public static int run(String argv[], PrintStream out) {

        exitStatus = Consts.TEST_PASSED;

        invokemethod006 thisTest = new invokemethod006();

        ArgumentHandler argHandler = new ArgumentHandler(argv);
        log = new Log(out, argHandler);

        waitTime = argHandler.getWaitTime() * 60000;

        debugee = Debugee.prepareDebugee(argHandler, log, debugeeName);

        thisTest.execTest();
        display("Test finished. exitStatus = " + exitStatus);

        return exitStatus;
    }

    private void execTest() {

        debugee.VM().suspend();
        testedClass = (ClassType )debugee.classByName(debugeeName);

        BreakpointRequest brkp = debugee.setBreakpoint(testedClass,
                                                    invokemethod006a.brkpMethodName,
                                                    invokemethod006a.brkpLineNumber);
        debugee.resume();

        debugee.sendSignal("");
        Event event = null;

        // waiting the breakpoint event
        try {
            event = debugee.waitingEvent(brkp, waitTime);
        } catch (InterruptedException e) {
            throw new TestRuntimeException("unexpected InterruptedException");
        }
        if (!(event instanceof BreakpointEvent )) {
            debugee.resume();
            throw new TestRuntimeException("BreakpointEvent didn't arrive");
        }

        BreakpointEvent brkpEvent = (BreakpointEvent )event;

        thread = brkpEvent.thread();

        display("\nTEST BEGINS");
        display("===========");

        Value retValue;
        List<Value> params = createParams(1);

        Method method = debugee.methodByName(testedClass, method2Invoke);
        display("Method      : " + method);

        display("invokeMethod(null, method, params,"
                    + "ClassType.INVOKE_SINGLE_THREADED)");
        try {
            retValue = testedClass.invokeMethod(null, method, params,
                                                ClassType.INVOKE_SINGLE_THREADED);
            complain("NullPointerException is not thrown");
            exitStatus = Consts.TEST_FAILED;
        } catch(NullPointerException e) {
            display("!!!expected NullPointerException");
        } catch(Exception e) {
            complain("Unexpected " + e);
            exitStatus = Consts.TEST_FAILED;
        }
        display("");

        display("invokeMethod(thread, null, params,"
                    + "ClassType.INVOKE_SINGLE_THREADED)");
        try {
            retValue = testedClass.invokeMethod(thread, null, params,
                                                ClassType.INVOKE_SINGLE_THREADED);
            complain("NullPointerException is not thrown");
            exitStatus = Consts.TEST_FAILED;
        } catch(NullPointerException e) {
            display("!!!expected NullPointerException");
        } catch(Exception e) {
            complain("Unexpected " + e);
            exitStatus = Consts.TEST_FAILED;
        }
        display("");

        display("invokeMethod(thread, method, null,"
                    + "ClassType.INVOKE_SINGLE_THREADED)");
        try {
            retValue = testedClass.invokeMethod(thread, method, null,
                                                ClassType.INVOKE_SINGLE_THREADED);
            complain("NullPointerException is not thrown");
            exitStatus = Consts.TEST_FAILED;
        } catch(NullPointerException e) {
            display("!!!expected NullPointerException");
        } catch(Exception e) {
            complain("Unexpected " + e);
            exitStatus = Consts.TEST_FAILED;
        }
        display("");

        display("invokeMethod(thread, method, params,"
                    + "Integer.MAX_VALUE)");
        try {
            retValue = testedClass.invokeMethod(thread, method, params,
                                                Integer.MAX_VALUE);
            if (((PrimitiveValue)retValue).intValue() != 1) {
                complain("Wrong returned value " + retValue.toString());
                exitStatus = Consts.TEST_FAILED;
            } else {
                display("returned value is " + retValue.toString());
            }
        } catch(Exception e) {
            complain("Unexpected " + e);
            exitStatus = Consts.TEST_FAILED;
        }
        display("");

        display("invokeMethod(thread, method, params,"
                    + "Integer.MIN_VALUE)");
        try {
            retValue = testedClass.invokeMethod(thread, method, params,
                                                Integer.MIN_VALUE);
            if (((PrimitiveValue)retValue).intValue() != 1) {
                complain("Wrong returned value " + retValue.toString());
                exitStatus = Consts.TEST_FAILED;
            } else {
                display("returned value is " + retValue.toString());
            }
        } catch(Exception e) {
            complain("Unexpected " + e);
            exitStatus = Consts.TEST_FAILED;
        }
        display("");
        display("=============");
        display("TEST FINISHES\n");

        debugee.resume();
        debugee.quit();
    }

    private List<Value> createParams(int size) {
        Vector<Value> params = new Vector<Value>();
        for (int i = 0; i < size; i++) {
            params.add(debugee.VM().mirrorOf(i + 1));
        }
        return params;
    }
}
