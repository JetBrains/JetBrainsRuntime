/*
 * Copyright (c) 2005, 2023, Oracle and/or its affiliates. All rights reserved.
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
package sun.tools.attach;

import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.spi.AttachProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/*
 * Windows implementation of HotSpotVirtualMachine
 */
@SuppressWarnings("restricted")
public class VirtualMachineImpl extends HotSpotVirtualMachine {

    // the enqueue code stub (copied into each target VM)
    private static byte[] stub;

    private volatile long hProcess;     // handle to the process

    VirtualMachineImpl(AttachProvider provider, String id)
        throws AttachNotSupportedException, IOException
    {
        super(provider, id);

        int pid = Integer.parseInt(id);
        hProcess = openProcess(pid);

        // The target VM might be a pre-6.0 VM so we enqueue a "null" command
        // which minimally tests that the enqueue function exists in the target
        // VM.
        try {
            enqueue(hProcess, stub, null, null);
        } catch (IOException x) {
            throw new AttachNotSupportedException(x.getMessage());
        }
    }

    public void detach() throws IOException {
        synchronized (this) {
            if (hProcess != -1) {
                closeProcess(hProcess);
                hProcess = -1;
            }
        }
    }

    InputStream execute(String cmd, Object ... args)
        throws AgentLoadException, IOException
    {
        assert args.length <= 3;        // includes null
        checkNulls(args);

        // create a pipe using a random name
        Random rnd = new Random();
        int r = rnd.nextInt();
        String pipeprefix = "\\\\.\\pipe\\javatool";
        String pipename = pipeprefix + r;
        long hPipe;
        try {
            hPipe = createPipe(pipename);
        } catch (IOException ce) {
            // Retry with another random pipe name.
            r = rnd.nextInt();
            pipename = pipeprefix + r;
            hPipe = createPipe(pipename);
        }

        // check if we are detached - in theory it's possible that detach is invoked
        // after this check but before we enqueue the command.
        if (hProcess == -1) {
            closePipe(hPipe);
            throw new IOException("Detached from target VM");
        }

        try {
            // enqueue the command to the process
            enqueue(hProcess, stub, cmd, pipename, args);

            // wait for command to complete - process will connect with the
            // completion status
            connectPipe(hPipe);

            // create an input stream for the pipe
            SocketInputStreamImpl in = new SocketInputStreamImpl(hPipe);

            // Process the command completion status
            processCompletionStatus(null, cmd, in);

            // return the input stream
            return in;

        } catch (IOException ioe) {
            closePipe(hPipe);
            throw ioe;
        }
    }

    // An InputStream based on a pipe to the target VM
    private static class SocketInputStreamImpl extends SocketInputStream {
        public SocketInputStreamImpl(long fd) {
            super(fd);
        }

        @Override
        protected int read(long fd, byte[] bs, int off, int len) throws IOException {
            return VirtualMachineImpl.readPipe(fd, bs, off, len);
        }

        @Override
        protected void close(long fd) throws IOException {
            VirtualMachineImpl.closePipe(fd);
        }
    }


    //-- native methods

    static native void init();

    static native byte[] generateStub();

    static native long openProcess(int pid) throws IOException;

    static native void closeProcess(long hProcess) throws IOException;

    static native long createPipe(String name) throws IOException;

    static native void closePipe(long hPipe) throws IOException;

    static native void connectPipe(long hPipe) throws IOException;

    static native int readPipe(long hPipe, byte buf[], int off, int buflen) throws IOException;

    static native void enqueue(long hProcess, byte[] stub,
        String cmd, String pipename, Object ... args) throws IOException;

    static {
        System.loadLibrary("attach");
        init();                                 // native initialization
        stub = generateStub();                  // generate stub to copy into target process
    }
}
