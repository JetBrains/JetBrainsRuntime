/*
 * Copyright (c) 2002-2020, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package jdk.internal.org.jline.terminal.impl.jna;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.function.Function;

import jdk.internal.org.jline.terminal.Attributes;
import jdk.internal.org.jline.terminal.Size;
import jdk.internal.org.jline.terminal.Terminal;
import jdk.internal.org.jline.terminal.TerminalBuilder;
//import jdk.internal.org.jline.terminal.impl.PosixPtyTerminal;
//import jdk.internal.org.jline.terminal.impl.PosixSysTerminal;
import jdk.internal.org.jline.terminal.impl.jna.win.JnaWinSysTerminal;
import jdk.internal.org.jline.terminal.spi.Pty;
import jdk.internal.org.jline.terminal.spi.SystemStream;
import jdk.internal.org.jline.terminal.spi.TerminalProvider;
import jdk.internal.org.jline.utils.OSUtils;

public class JnaTerminalProvider implements TerminalProvider {

    public JnaTerminalProvider() {
        checkSystemStream(SystemStream.Output);
    }

    @Override
    public String name() {
        return TerminalBuilder.PROP_PROVIDER_JNA;
    }

//    public Pty current(SystemStream systemStream) throws IOException {
//        return JnaNativePty.current(this, systemStream);
//    }
//
//    public Pty open(Attributes attributes, Size size) throws IOException {
//        return JnaNativePty.open(this, attributes, size);
//    }

    @Override
    public Terminal sysTerminal(
            String name,
            String type,
            boolean ansiPassThrough,
            Charset encoding,
            boolean nativeSignals,
            Terminal.SignalHandler signalHandler,
            boolean paused,
            SystemStream systemStream,
            Function<InputStream, InputStream> inputStreamWrapper)
            throws IOException {
        if (OSUtils.IS_WINDOWS) {
            return winSysTerminal(name, type, ansiPassThrough, encoding, nativeSignals, signalHandler, paused, systemStream, inputStreamWrapper );
        } else {
            return null;
        }
    }

    public Terminal winSysTerminal(
            String name,
            String type,
            boolean ansiPassThrough,
            Charset encoding,
            boolean nativeSignals,
            Terminal.SignalHandler signalHandler,
            boolean paused,
            SystemStream systemStream,
            Function<InputStream, InputStream> inputStreamWrapper)
            throws IOException {
        return JnaWinSysTerminal.createTerminal(
                this, systemStream, name, type, ansiPassThrough, encoding, nativeSignals, signalHandler, paused, inputStreamWrapper);
    }

//    public Terminal posixSysTerminal(
//            String name,
//            String type,
//            boolean ansiPassThrough,
//            Charset encoding,
//            boolean nativeSignals,
//            Terminal.SignalHandler signalHandler,
//            boolean paused,
//            SystemStream systemStream)
//            throws IOException {
//        Pty pty = current(systemStream);
//        return new PosixSysTerminal(name, type, pty, encoding, nativeSignals, signalHandler);
//    }

    @Override
    public Terminal newTerminal(
            String name,
            String type,
            InputStream in,
            OutputStream out,
            Charset encoding,
            Terminal.SignalHandler signalHandler,
            boolean paused,
            Attributes attributes,
            Size size)
            throws IOException {
//        Pty pty = open(attributes, size);
//        return new PosixPtyTerminal(name, type, pty, in, out, encoding, signalHandler, paused);
        return null;
    }

    @Override
    public boolean isSystemStream(SystemStream stream) {
        try {
            return checkSystemStream(stream);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean checkSystemStream(SystemStream stream) {
        if (OSUtils.IS_WINDOWS) {
            return JnaWinSysTerminal.isWindowsSystemStream(stream);
        } else {
//            return JnaNativePty.isPosixSystemStream(stream);
            return false;
        }
    }

    @Override
    public String systemStreamName(SystemStream stream) {
//        if (OSUtils.IS_WINDOWS) {
            return null;
//        } else {
//            return JnaNativePty.posixSystemStreamName(stream);
//        }
    }

//    @Override
//    public int systemStreamWidth(SystemStream stream) {
//        try (Pty pty = current(stream)) {
//            return pty.getSize().getColumns();
//        } catch (Throwable t) {
//            return -1;
//        }
//    }

    @Override
    public String toString() {
        return "TerminalProvider[" + name() + "]";
    }
}
