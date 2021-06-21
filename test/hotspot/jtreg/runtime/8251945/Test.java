/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

/*
 * @test
 * @bug 8251945
 * @summary Ensure no race on installing class loader data
 *
 * @run main/othervm Test
 */
import jdk.jfr.Event;
import java.util.concurrent.BrokenBarrierException;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

public final class Test
{
    private static final int ITERATIONS = Integer.getInteger("iterations", 10000);
    private volatile ClassLoader nextLoader;

    public static void main(final String[] args) {
        new Test().crash();
    }

    public void crash() {
        final byte[] runnableClass = loadBytecode("Test$TestRunnable");
        final byte[] eventClass = loadBytecode("Test$TestRunnable$RunnableEvent");
        final int numberOfThreads = Runtime.getRuntime().availableProcessors();
        if (numberOfThreads < 1) {
            throw new IllegalStateException("requies more than one thread");
        }
        final ExecutorService threadPool = Executors.newFixedThreadPool(numberOfThreads);
        final CyclicBarrier cyclicBarrier = new CyclicBarrier(numberOfThreads, () -> this.nextLoader = new PredefinedClassLoader(runnableClass, eventClass));
        for (int i = 0; i < numberOfThreads; ++i) {
            threadPool.submit(new LoadingRunnable(cyclicBarrier));
        }
        threadPool.shutdown();
    }

    Runnable loadTestRunnable(final ClassLoader classLoader) {
        try {
            return (Runnable)Class.forName("Test$TestRunnable", true, classLoader).asSubclass(Runnable.class).getConstructor((Class<?>[])new Class[0]).newInstance(new Object[0]);
        }
        catch (ReflectiveOperationException e) {
            throw new RuntimeException("could not load runnable", e);
        }
    }

    private static byte[] loadBytecode(final String className) {
        final String resource = toResourceName(className);
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            final InputStream inputStream = Test.class.getClassLoader().getResourceAsStream(resource);
            try {
                inputStream.transferTo(buffer);
                if (inputStream != null) {
                    inputStream.close();
                }
            }
            catch (Throwable t) {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    }
                    catch (Throwable exception) {
                        t.addSuppressed(exception);
                    }
                }
                throw t;
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(className, e);
        }
        return buffer.toByteArray();
    }

    private static String toResourceName(final String className) {
        return className.replace('.', '/') + ".class";
    }

    final class LoadingRunnable implements Runnable
    {
        private final CyclicBarrier barrier;

        LoadingRunnable(final CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public void run() {
            int itr = 0;
            try {
                while (itr++ < ITERATIONS) {
                    this.barrier.await();
                    final Runnable runnable = Test.this.loadTestRunnable(Test.this.nextLoader);
                    runnable.run();
                }
            }
            catch (InterruptedException | BrokenBarrierException ex) {
                final Exception e = ex;
            }
        }
    }

    static final class PredefinedClassLoader extends ClassLoader
    {
        private final byte[] runnableClass;
        private final byte[] eventClass;

        PredefinedClassLoader(final byte[] runnableClass, final byte[] eventClass) {
            super(null);
            this.runnableClass = runnableClass;
            this.eventClass = eventClass;
        }

        @Override
        protected Class<?> loadClass(final String className, final boolean resolve) throws ClassNotFoundException {
            final Class<?> loadedClass = this.findLoadedClass(className);
            if (loadedClass != null) {
                if (resolve) {
                    this.resolveClass(loadedClass);
                }
                return loadedClass;
            }
            if (className.equals("Test$TestRunnable")) {
                return this.loadClassFromByteArray(className, resolve, this.runnableClass);
            }
            if (className.equals("Test$TestRunnable$RunnableEvent")) {
                return this.loadClassFromByteArray(className, resolve, this.eventClass);
            }
            return super.loadClass(className, resolve);
        }

        private Class<?> loadClassFromByteArray(final String className, final boolean resolve, final byte[] byteCode) throws ClassNotFoundException {
            Class<?> clazz;
            try {
                synchronized (getClassLoadingLock(className)) {
                    clazz = this.defineClass(className, byteCode, 0, byteCode.length);
                }
            }
            catch (LinkageError e) {
                clazz = this.findLoadedClass(className);
            }
            if (resolve) {
                this.resolveClass(clazz);
            }
            return clazz;
        }
    }

    public static final class TestRunnable implements Runnable
    {
        @Override
        public void run() {
            final RunnableEvent event = new RunnableEvent();
            event.setRunnableClassName("TestRunnable");
            event.begin();
            event.end();
            event.commit();
        }

        public static class RunnableEvent extends Event
        {
            private String runnableClassName;

            String getRunnableClassName() {
                return this.runnableClassName;
            }

            void setRunnableClassName(final String operationName) {
                this.runnableClassName = operationName;
            }
        }
    }
}
