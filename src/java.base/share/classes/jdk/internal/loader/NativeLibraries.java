/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.loader;

import com.jetbrains.internal.IoOverNio;
import jdk.internal.misc.VM;
import jdk.internal.ref.CleanerFactory;
import jdk.internal.util.StaticProperty;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Native libraries are loaded via {@link System#loadLibrary(String)},
 * {@link System#load(String)}, {@link Runtime#loadLibrary(String)} and
 * {@link Runtime#load(String)}.  They are caller-sensitive.
 *
 * Each class loader has a NativeLibraries instance to register all of its
 * loaded native libraries.  System::loadLibrary (and other APIs) only
 * allows a native library to be loaded by one class loader, i.e. one
 * NativeLibraries instance.  Any attempt to load a native library that
 * has already been loaded by a class loader with another class loader
 * will fail.
 */
public final class NativeLibraries {
    private static final boolean loadLibraryOnlyIfPresent = ClassLoaderHelper.loadLibraryOnlyIfPresent();
    private final Map<String, NativeLibraryImpl> libraries = new ConcurrentHashMap<>();
    private final ClassLoader loader;
    // caller, if non-null, is the fromClass parameter for NativeLibraries::loadLibrary
    // unless specified
    private final Class<?> caller;      // may be null
    private final boolean searchJavaLibraryPath;

    /**
     * Creates a NativeLibraries instance for loading JNI native libraries
     * via for System::loadLibrary use.
     *
     * 1. Support of auto-unloading.  The loaded native libraries are unloaded
     *    when the class loader is reclaimed.
     * 2. Support of linking of native method.  See JNI spec.
     * 3. Restriction on a native library that can only be loaded by one class loader.
     *    Each class loader manages its own set of native libraries.
     *    The same JNI native library cannot be loaded into more than one class loader.
     *
     * This static factory method is intended only for System::loadLibrary use.
     *
     * @see <a href="${docroot}/specs/jni/invocation.html##library-and-version-management">
     *     JNI Specification: Library and Version Management</a>
     */
    public static NativeLibraries newInstance(ClassLoader loader) {
        return new NativeLibraries(loader, loader != null ? null : NativeLibraries.class, loader != null);
    }

    private NativeLibraries(ClassLoader loader, Class<?> caller, boolean searchJavaLibraryPath) {
        this.loader = loader;
        this.caller = caller;
        this.searchJavaLibraryPath = searchJavaLibraryPath;
    }

    /*
     * Find the address of the given symbol name from the native libraries
     * loaded in this NativeLibraries instance.
     */
    public long find(String name) {
        if (libraries.isEmpty())
            return 0;

        // the native libraries map may be updated in another thread
        // when a native library is being loaded.  No symbol will be
        // searched from it yet.
        for (NativeLibrary lib : libraries.values()) {
            long entry = lib.find(name);
            if (entry != 0) return entry;
        }
        return 0;
    }

    /*
     * Load a native library from the given file.  Returns null if the given
     * library is determined to be non-loadable, which is system-dependent.
     *
     * @param fromClass the caller class calling System::loadLibrary
     * @param file the path of the native library
     * @throws UnsatisfiedLinkError if any error in loading the native library
     */
    @SuppressWarnings("try")
    public NativeLibrary loadLibrary(Class<?> fromClass, File file) {
        // Check to see if we're attempting to access a static library
        String name = findBuiltinLib(file.getName());
        boolean isBuiltin = (name != null);
        if (!isBuiltin) {
            try {
                if (loadLibraryOnlyIfPresent && !file.exists()) {
                    return null;
                }
                name = file.getCanonicalPath();
            } catch (IOException e) {
                return null;
            }
        }
        return loadLibrary(fromClass, name, isBuiltin);
    }

    /**
     * Returns a NativeLibrary of the given name.
     *
     * @param fromClass the caller class calling System::loadLibrary
     * @param name      library name
     * @param isBuiltin built-in library
     * @throws UnsatisfiedLinkError if the native library has already been loaded
     *      and registered in another NativeLibraries
     */
    private NativeLibrary loadLibrary(Class<?> fromClass, String name, boolean isBuiltin) {
        ClassLoader loader = (fromClass == null) ? null : fromClass.getClassLoader();
        if (this.loader != loader) {
            throw new InternalError(fromClass.getName() + " not allowed to load library");
        }

        acquireNativeLibraryLock(name);
        try {
            // find if this library has already been loaded and registered in this NativeLibraries
            NativeLibrary cached = libraries.get(name);
            if (cached != null) {
                return cached;
            }

            // cannot be loaded by other class loaders
            if (loadedLibraryNames.contains(name)) {
                throw new UnsatisfiedLinkError("Native Library " + name +
                        " already loaded in another classloader");
            }

            /*
             * When a library is being loaded, JNI_OnLoad function can cause
             * another loadLibrary invocation that should succeed.
             *
             * Each thread maintains its own stack to hold the list of
             * libraries it is loading.
             *
             * If there is a pending load operation for the library, we
             * immediately return success; if the pending load is from
             * a different class loader, we raise UnsatisfiedLinkError.
             */
            for (NativeLibraryImpl lib : NativeLibraryContext.current()) {
                if (name.equals(lib.name())) {
                    if (loader == lib.fromClass.getClassLoader()) {
                        return lib;
                    } else {
                        throw new UnsatisfiedLinkError("Native Library " +
                                name + " is being loaded in another classloader");
                    }
                }
            }

            NativeLibraryImpl lib = new NativeLibraryImpl(fromClass, name, isBuiltin);
            // load the native library
            NativeLibraryContext.push(lib);
            try {
                if (!lib.open()) {
                    return null;    // fail to open the native library
                }
                // auto unloading is only supported for JNI native libraries
                // loaded by custom class loaders that can be unloaded.
                // built-in class loaders are never unloaded.
                boolean autoUnload = !VM.isSystemDomainLoader(loader) && loader != ClassLoaders.appClassLoader();
                if (autoUnload) {
                    // register the loaded native library for auto unloading
                    // when the class loader is reclaimed, all native libraries
                    // loaded that class loader will be unloaded.
                    // The entries in the libraries map are not removed since
                    // the entire map will be reclaimed altogether.
                    CleanerFactory.cleaner().register(loader, lib.unloader());
                }
            } finally {
                NativeLibraryContext.pop();
            }
            // register the loaded native library
            loadedLibraryNames.add(name);
            libraries.put(name, lib);
            return lib;
        } finally {
            releaseNativeLibraryLock(name);
        }
    }

    /**
     * Loads a native library from the system library path and java library path.
     *
     * @param name library name
     *
     * @throws UnsatisfiedLinkError if the native library has already been loaded
     *      and registered in another NativeLibraries
     */
    public NativeLibrary loadLibrary(String name) {
        assert name.indexOf(File.separatorChar) < 0;
        return loadLibrary(caller, name);
    }

    /**
     * Loads a native library from the system library path and java library path.
     *
     * @param name library name
     * @param fromClass the caller class calling System::loadLibrary
     *
     * @throws UnsatisfiedLinkError if the native library has already been loaded
     *      and registered in another NativeLibraries
     */
    public NativeLibrary loadLibrary(Class<?> fromClass, String name) {
        assert name.indexOf(File.separatorChar) < 0;

        NativeLibrary lib = findFromPaths(LibraryPaths.SYS_PATHS, fromClass, name);
        if (lib == null && searchJavaLibraryPath) {
            lib = findFromPaths(LibraryPaths.USER_PATHS, fromClass, name);
        }
        return lib;
    }

    private NativeLibrary findFromPaths(String[] paths, Class<?> fromClass, String name) {
        for (String path : paths) {
            File libfile = new File(path, System.mapLibraryName(name));
            NativeLibrary nl = loadLibrary(fromClass, libfile);
            if (nl != null) {
                return nl;
            }
            libfile = ClassLoaderHelper.mapAlternativeName(libfile);
            if (libfile != null) {
                nl = loadLibrary(fromClass, libfile);
                if (nl != null) {
                    return nl;
                }
            }
        }
        return null;
    }

    /**
     * NativeLibraryImpl denotes a loaded native library instance.
     * Each NativeLibraries contains a map of loaded native libraries in the
     * private field {@code libraries}.
     *
     * Every native library requires a particular version of JNI. This is
     * denoted by the private {@code jniVersion} field.  This field is set by
     * the VM when it loads the library, and used by the VM to pass the correct
     * version of JNI to the native methods.
     */
    static class NativeLibraryImpl extends NativeLibrary {
        // the class from which the library is loaded, also indicates
        // the loader this native library belongs.
        final Class<?> fromClass;
        // the canonicalized name of the native library.
        // or static library name
        final String name;
        // Indicates if the native library is linked into the VM
        final boolean isBuiltin;

        // opaque handle to native library, used in native code.
        long handle;
        // the version of JNI environment the native library requires.
        int jniVersion;

        NativeLibraryImpl(Class<?> fromClass, String name, boolean isBuiltin) {
            this.fromClass = fromClass;
            this.name = name;
            this.isBuiltin = isBuiltin;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long find(String name) {
            return findEntry0(handle, name);
        }

        /*
         * Unloader::run method is invoked to unload the native library
         * when this class loader becomes phantom reachable.
         */
        private Runnable unloader() {
            return new Unloader(name, handle, isBuiltin);
        }

        /*
         * Loads the named native library
         */
        boolean open() {
            if (handle != 0) {
                throw new InternalError("Native library " + name + " has been loaded");
            }

            return load(this, name, isBuiltin, throwExceptionIfFail());
        }

        @SuppressWarnings("try")
        private boolean throwExceptionIfFail() {
            if (loadLibraryOnlyIfPresent) return true;

            // If the file exists but fails to load, UnsatisfiedLinkException thrown by the VM
            // will include the error message from dlopen to provide diagnostic information
            try (var ignored = IoOverNio.disableInThisThread()) {
                File file = new File(name);
                return file.exists();
            }
        }

        /*
         * Close this native library.
         */
        void close() {
            unload(name, isBuiltin, handle);
        }
    }

    /*
     * The run() method will be invoked when this class loader becomes
     * phantom reachable to unload the native library.
     */
    static class Unloader implements Runnable {
        // This represents the context when a native library is unloaded
        // and getFromClass() will return null,
        static final NativeLibraryImpl UNLOADER =
                new NativeLibraryImpl(null, "dummy", false);

        final String name;
        final long handle;
        final boolean isBuiltin;

        Unloader(String name, long handle, boolean isBuiltin) {
            if (handle == 0) {
                throw new IllegalArgumentException(
                        "Invalid handle for native library " + name);
            }

            this.name = name;
            this.handle = handle;
            this.isBuiltin = isBuiltin;
        }

        @Override
        public void run() {
            acquireNativeLibraryLock(name);
            try {
                /* remove the native library name */
                if (!loadedLibraryNames.remove(name)) {
                    throw new IllegalStateException(name + " has already been unloaded");
                }
                NativeLibraryContext.push(UNLOADER);
                try {
                    unload(name, isBuiltin, handle);
                } finally {
                    NativeLibraryContext.pop();
                }
            } finally {
                releaseNativeLibraryLock(name);
            }
        }
    }

    /*
     * Holds system and user library paths derived from the
     * {@code java.library.path} and {@code sun.boot.library.path} system
     * properties. The system properties are eagerly read at bootstrap, then
     * lazily parsed on first use to avoid initialization ordering issues.
     */
    static class LibraryPaths {
        // The paths searched for libraries
        static final String[] SYS_PATHS = ClassLoaderHelper.parsePath(StaticProperty.sunBootLibraryPath());
        static final String[] USER_PATHS = ClassLoaderHelper.parsePath(StaticProperty.javaLibraryPath());
    }

    // All native libraries we've loaded.
    private static final Set<String> loadedLibraryNames =
            ConcurrentHashMap.newKeySet();

    // reentrant lock class that allows exact counting (with external synchronization)
    @SuppressWarnings("serial")
    private static final class CountedLock extends ReentrantLock {

        private int counter = 0;

        public void increment() {
            if (counter == Integer.MAX_VALUE) {
                // prevent overflow
                throw new Error("Maximum lock count exceeded");
            }
            ++counter;
        }

        public void decrement() {
            --counter;
        }

        public int getCounter() {
            return counter;
        }
    }

    // Maps native library name to the corresponding lock object
    private static final Map<String, CountedLock> nativeLibraryLockMap =
            new ConcurrentHashMap<>();

    private static void acquireNativeLibraryLock(String libraryName) {
        nativeLibraryLockMap.compute(libraryName,
            new BiFunction<>() {
                public CountedLock apply(String name, CountedLock currentLock) {
                    if (currentLock == null) {
                        currentLock = new CountedLock();
                    }
                    // safe as compute BiFunction<> is executed atomically
                    currentLock.increment();
                    return currentLock;
                }
            }
        ).lock();
    }

    private static void releaseNativeLibraryLock(String libraryName) {
        CountedLock lock = nativeLibraryLockMap.computeIfPresent(libraryName,
            new BiFunction<>() {
                public CountedLock apply(String name, CountedLock currentLock) {
                    if (currentLock.getCounter() == 1) {
                        // unlock and release the object if no other threads are queued
                        currentLock.unlock();
                        // remove the element
                        return null;
                    } else {
                        currentLock.decrement();
                        return currentLock;
                    }
                }
            }
        );
        if (lock != null) {
            lock.unlock();
        }
    }

    // native libraries being loaded
    private static final class NativeLibraryContext {

        // Maps thread object to the native library context stack, maintained by each thread
        private static Map<Thread, Deque<NativeLibraryImpl>> nativeLibraryThreadContext =
                new ConcurrentHashMap<>();

        // returns a context associated with the current thread
        private static Deque<NativeLibraryImpl> current() {
            return nativeLibraryThreadContext.computeIfAbsent(
                    Thread.currentThread(),
                    new Function<>() {
                        public Deque<NativeLibraryImpl> apply(Thread t) {
                            return new ArrayDeque<>(8);
                        }
                    });
        }

        private static NativeLibraryImpl peek() {
            return current().peek();
        }

        private static void push(NativeLibraryImpl lib) {
            current().push(lib);
        }

        private static void pop() {
            // this does not require synchronization since each
            // thread has its own context
            Deque<NativeLibraryImpl> libs = current();
            libs.pop();
            if (libs.isEmpty()) {
                // context can be safely removed once empty
                nativeLibraryThreadContext.remove(Thread.currentThread());
            }
        }

        private static boolean isEmpty() {
            Deque<NativeLibraryImpl> context =
                    nativeLibraryThreadContext.get(Thread.currentThread());
            return (context == null || context.isEmpty());
        }
    }

    // Invoked in the VM to determine the context class in JNI_OnLoad
    // and JNI_OnUnload
    private static Class<?> getFromClass() {
        if (NativeLibraryContext.isEmpty()) { // only default library
            return Object.class;
        }
        return NativeLibraryContext.peek().fromClass;
    }

    /*
     * Return true if the given library is successfully loaded.
     * If the given library cannot be loaded for any reason,
     * if throwExceptionIfFail is false, then this method returns false;
     * otherwise, UnsatisfiedLinkError will be thrown.
     *
     * JNI FindClass expects the caller class if invoked from JNI_OnLoad
     * and JNI_OnUnload is NativeLibrary class.
     */
    private static native boolean load(NativeLibraryImpl impl, String name,
                                       boolean isBuiltin,
                                       boolean throwExceptionIfFail);
    /*
     * Unload the named library.  JNI_OnUnload, if present, will be invoked
     * before the native library is unloaded.
     */
    private static native void unload(String name, boolean isBuiltin, long handle);
    private static native String findBuiltinLib(String name);
}
