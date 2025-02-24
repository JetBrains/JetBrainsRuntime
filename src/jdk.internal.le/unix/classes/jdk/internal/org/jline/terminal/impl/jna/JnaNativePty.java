/*
 * Copyright (c) 2002-2020, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package jdk.internal.org.jline.terminal.impl.jna;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;

import jdk.internal.org.jline.terminal.Attributes;
import jdk.internal.org.jline.terminal.Size;
import jdk.internal.org.jline.terminal.impl.AbstractPty;
//import jdk.internal.org.jline.terminal.impl.jna.freebsd.FreeBsdNativePty;
//import jdk.internal.org.jline.terminal.impl.jna.linux.LinuxNativePty;
//import jdk.internal.org.jline.terminal.impl.jna.osx.OsXNativePty;
//import jdk.internal.org.jline.terminal.impl.jna.solaris.SolarisNativePty;
import jdk.internal.org.jline.terminal.spi.Pty;
import jdk.internal.org.jline.terminal.spi.SystemStream;
import jdk.internal.org.jline.terminal.spi.TerminalProvider;

//import com.sun.jna.Platform;

public abstract class JnaNativePty extends AbstractPty implements Pty {

    private final int master;
    private final int slave;
    private final int slaveOut;
    private final String name;
    private final FileDescriptor masterFD;
    private final FileDescriptor slaveFD;
    private final FileDescriptor slaveOutFD;

    public static JnaNativePty current(TerminalProvider provider, SystemStream systemStream) throws IOException {
//        if (Platform.isMac()) {
//            if (Platform.is64Bit() && Platform.isARM()) {
//                throw new UnsupportedOperationException();
//            }
//            return OsXNativePty.current(provider, systemStream);
//        } else if (Platform.isLinux()) {
//            return LinuxNativePty.current(provider, systemStream);
//        } else if (Platform.isSolaris()) {
//            return SolarisNativePty.current(provider, systemStream);
//        } else if (Platform.isFreeBSD()) {
//            return FreeBsdNativePty.current(provider, systemStream);
//        } else {
//            throw new UnsupportedOperationException();
//        }
        return JDKNativePty.current(provider, systemStream);
    }

    public static JnaNativePty open(TerminalProvider provider, Attributes attr, Size size) throws IOException {
//        if (Platform.isMac()) {
//            if (Platform.is64Bit() && Platform.isARM()) {
//                throw new UnsupportedOperationException();
//            }
//            return OsXNativePty.open(provider, attr, size);
//        } else if (Platform.isLinux()) {
//            return LinuxNativePty.open(provider, attr, size);
//        } else if (Platform.isSolaris()) {
//            return SolarisNativePty.open(provider, attr, size);
//        } else if (Platform.isFreeBSD()) {
//            return FreeBsdNativePty.open(provider, attr, size);
//        } else {
//            throw new UnsupportedOperationException();
//        }
        return JDKNativePty.open(provider, attr, size);
    }

    protected JnaNativePty(
            TerminalProvider provider,
            SystemStream systemStream,
            int master,
            FileDescriptor masterFD,
            int slave,
            FileDescriptor slaveFD,
            String name) {
        this(provider, systemStream, master, masterFD, slave, slaveFD, slave, slaveFD, name);
    }

    protected JnaNativePty(
            TerminalProvider provider,
            SystemStream systemStream,
            int master,
            FileDescriptor masterFD,
            int slave,
            FileDescriptor slaveFD,
            int slaveOut,
            FileDescriptor slaveOutFD,
            String name) {
        super(provider, systemStream);
        this.master = master;
        this.slave = slave;
        this.slaveOut = slaveOut;
        this.name = name;
        this.masterFD = masterFD;
        this.slaveFD = slaveFD;
        this.slaveOutFD = slaveOutFD;
    }

    @Override
    public void close() throws IOException {
        if (master > 0) {
            getMasterInput().close();
        }
        if (slave > 0) {
            getSlaveInput().close();
        }
    }

    public int getMaster() {
        return master;
    }

    public int getSlave() {
        return slave;
    }

    public int getSlaveOut() {
        return slaveOut;
    }

    public String getName() {
        return name;
    }

    public FileDescriptor getMasterFD() {
        return masterFD;
    }

    public FileDescriptor getSlaveFD() {
        return slaveFD;
    }

    public FileDescriptor getSlaveOutFD() {
        return slaveOutFD;
    }

    public InputStream getMasterInput() {
        return new FileInputStream(getMasterFD());
    }

    public OutputStream getMasterOutput() {
        return new FileOutputStream(getMasterFD());
    }

    protected InputStream doGetSlaveInput() {
        return new FileInputStream(getSlaveFD());
    }

    public OutputStream getSlaveOutput() {
        return new FileOutputStream(getSlaveOutFD());
    }

    @Override
    public String toString() {
        return "JnaNativePty[" + getName() + "]";
    }

    public static boolean isPosixSystemStream(SystemStream stream) {
        switch (stream) {
            case Input:
                return isatty(0);
            case Output:
                return isatty(1);
            case Error:
                return isatty(2);
            default:
                return false;
        }
    }

    public static String posixSystemStreamName(SystemStream stream) {
        switch (stream) {
            case Input:
                return ttyname(0);
            case Output:
                return ttyname(1);
            case Error:
                return ttyname(2);
            default:
                return null;
        }
    }

    private static boolean isatty(int fd) {
//        if (Platform.isMac()) {
//            if (Platform.is64Bit() && Platform.isARM()) {
//                throw new UnsupportedOperationException("Unsupported platform mac-aarch64");
//            }
//            return OsXNativePty.isatty(fd) == 1;
//        } else if (Platform.isLinux()) {
//            return LinuxNativePty.isatty(fd) == 1;
//        } else if (Platform.isSolaris()) {
//            return SolarisNativePty.isatty(fd) == 1;
//        } else if (Platform.isFreeBSD()) {
//            return FreeBsdNativePty.isatty(fd) == 1;
//        } else {
//            return false;
//        }
        return JDKNativePty.isatty(fd) == 1;
    }

    private static String ttyname(int fd) {
//        if (Platform.isMac()) {
//            if (Platform.is64Bit() && Platform.isARM()) {
//                throw new UnsupportedOperationException("Unsupported platform mac-aarch64");
//            }
//            return OsXNativePty.ttyname(fd);
//        } else if (Platform.isLinux()) {
//            return LinuxNativePty.ttyname(fd);
//        } else if (Platform.isSolaris()) {
//            return SolarisNativePty.ttyname(fd);
//        } else if (Platform.isFreeBSD()) {
//            return FreeBsdNativePty.ttyname(fd);
//        } else {
//            return null;
//        }
        return JDKNativePty.ttyname(fd);
    }
}
