/*
 * Copyright (c) 2023,  JetBrains s.r.o.
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

/**
 * @test
 * @summary Verifies that Unix domain sockets work with a non-default
 *          file system provider that implements
 * @library /test/lib
 * @build TestProvider
 * @run main/othervm -Djava.nio.file.spi.DefaultFileSystemProvider=TestProvider UnixSocketWithCustomProvider
 */

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;

public class UnixSocketWithCustomProvider {
    public static void main(String[] args) {
        FileSystemProvider provider = FileSystems.getDefault().provider();
        if (!provider.getClass().getName().equals("TestProvider")) {
            throw new Error("Test must be run with non-default file system");
        }

        Path socketPath = Path.of("socket");
        UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
        try (ServerSocketChannel serverCh = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            serverCh.bind(socketAddress);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException ignore) {
            }
        }
    }
}
