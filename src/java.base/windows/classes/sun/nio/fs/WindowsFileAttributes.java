/*
 * Copyright (c) 2008, 2019, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.fs;

import java.nio.file.attribute.*;
import java.util.concurrent.TimeUnit;
import jdk.internal.misc.Unsafe;
import sun.security.action.GetPropertyAction;

import static sun.nio.fs.WindowsNativeDispatcher.*;
import static sun.nio.fs.WindowsConstants.*;

/**
 * Windows implementation of DosFileAttributes/BasicFileAttributes
 */

class WindowsFileAttributes
    implements DosFileAttributes
{
    private static final Unsafe unsafe = Unsafe.getUnsafe();

    /*
     * typedef struct _BY_HANDLE_FILE_INFORMATION {
     *     DWORD    dwFileAttributes;
     *     FILETIME ftCreationTime;
     *     FILETIME ftLastAccessTime;
     *     FILETIME ftLastWriteTime;
     *     DWORD    dwVolumeSerialNumber;
     *     DWORD    nFileSizeHigh;
     *     DWORD    nFileSizeLow;
     *     DWORD    nNumberOfLinks;
     *     DWORD    nFileIndexHigh;
     *     DWORD    nFileIndexLow;
     * } BY_HANDLE_FILE_INFORMATION;
     */
    private static final short SIZEOF_FILE_INFORMATION  = 52;
    private static final short OFFSETOF_FILE_INFORMATION_ATTRIBUTES      = 0;
    private static final short OFFSETOF_FILE_INFORMATION_CREATETIME      = 4;
    private static final short OFFSETOF_FILE_INFORMATION_LASTACCESSTIME  = 12;
    private static final short OFFSETOF_FILE_INFORMATION_LASTWRITETIME   = 20;
    private static final short OFFSETOF_FILE_INFORMATION_VOLSERIALNUM    = 28;
    private static final short OFFSETOF_FILE_INFORMATION_SIZEHIGH        = 32;
    private static final short OFFSETOF_FILE_INFORMATION_SIZELOW         = 36;
    private static final short OFFSETOF_FILE_INFORMATION_INDEXHIGH       = 44;
    private static final short OFFSETOF_FILE_INFORMATION_INDEXLOW        = 48;

    /*
     * typedef struct _WIN32_FILE_ATTRIBUTE_DATA {
     *   DWORD dwFileAttributes;
     *   FILETIME ftCreationTime;
     *   FILETIME ftLastAccessTime;
     *   FILETIME ftLastWriteTime;
     *   DWORD nFileSizeHigh;
     *   DWORD nFileSizeLow;
     * } WIN32_FILE_ATTRIBUTE_DATA;
     */
    private static final short SIZEOF_FILE_ATTRIBUTE_DATA = 36;
    private static final short OFFSETOF_FILE_ATTRIBUTE_DATA_ATTRIBUTES      = 0;
    private static final short OFFSETOF_FILE_ATTRIBUTE_DATA_CREATETIME      = 4;
    private static final short OFFSETOF_FILE_ATTRIBUTE_DATA_LASTACCESSTIME  = 12;
    private static final short OFFSETOF_FILE_ATTRIBUTE_DATA_LASTWRITETIME   = 20;
    private static final short OFFSETOF_FILE_ATTRIBUTE_DATA_SIZEHIGH        = 28;
    private static final short OFFSETOF_FILE_ATTRIBUTE_DATA_SIZELOW         = 32;

    /**
     * typedef struct _WIN32_FIND_DATA {
     *   DWORD dwFileAttributes;
     *   FILETIME ftCreationTime;
     *   FILETIME ftLastAccessTime;
     *   FILETIME ftLastWriteTime;
     *   DWORD nFileSizeHigh;
     *   DWORD nFileSizeLow;
     *   DWORD dwReserved0;
     *   DWORD dwReserved1;
     *   TCHAR cFileName[MAX_PATH];
     *   TCHAR cAlternateFileName[14];
     * } WIN32_FIND_DATA;
     */
    private static final short SIZEOF_FIND_DATA = 592;
    private static final short OFFSETOF_FIND_DATA_ATTRIBUTES = 0;
    private static final short OFFSETOF_FIND_DATA_CREATETIME = 4;
    private static final short OFFSETOF_FIND_DATA_LASTACCESSTIME = 12;
    private static final short OFFSETOF_FIND_DATA_LASTWRITETIME = 20;
    private static final short OFFSETOF_FIND_DATA_SIZEHIGH = 28;
    private static final short OFFSETOF_FIND_DATA_SIZELOW = 32;
    private static final short OFFSETOF_FIND_DATA_RESERVED0 = 36;


    // used to adjust values between Windows and java epochs
    private static final long WINDOWS_EPOCH_IN_MICROS = -11644473600000000L;
    private static final long WINDOWS_EPOCH_IN_100NS  = -116444736000000000L;

    /**
     * typedef struct _FILE_ID_FULL_DIR_INFORMATION {
     *  ULONG         NextEntryOffset;  // offset = 0
     *  ULONG         FileIndex;        // offset = 4
     *  LARGE_INTEGER CreationTime;     // offset = 8
     *  LARGE_INTEGER LastAccessTime;   // offset = 16
     *  LARGE_INTEGER LastWriteTime;    // offset = 24
     *  LARGE_INTEGER ChangeTime;       // offset = 32
     *  LARGE_INTEGER EndOfFile;        // offset = 40
     *  LARGE_INTEGER AllocationSize;   // offset = 48
     *  ULONG         FileAttributes;   // offset = 56
     *  ULONG         FileNameLength;   // offset = 60
     *  ULONG         EaSize;           // offset = 64
     *  LARGE_INTEGER FileId;           // offset = 72
     *  WCHAR         FileName[1];      // offset = 80
     * } FILE_ID_FULL_DIR_INFORMATION, *PFILE_ID_FULL_DIR_INFORMATION;
     */
    private static final int OFFSETOF_FULL_DIR_INFO_NEXT_ENTRY_OFFSET = 0;
    private static final int OFFSETOF_FULL_DIR_INFO_CREATION_TIME = 8;
    private static final int OFFSETOF_FULL_DIR_INFO_LAST_ACCESS_TIME = 16;
    private static final int OFFSETOF_FULL_DIR_INFO_LAST_WRITE_TIME = 24;
    private static final int OFFSETOF_FULL_DIR_INFO_END_OF_FILE = 40;
    private static final int OFFSETOF_FULL_DIR_INFO_FILE_ATTRIBUTES = 56;
    private static final int OFFSETOF_FULL_DIR_INFO_FILENAME_LENGTH = 60;
    private static final int OFFSETOF_FULL_DIR_INFO_EA_SIZE = 64;
    private static final int OFFSETOF_FULL_DIR_INFO_FILE_ID = 72;
    private static final int OFFSETOF_FULL_DIR_INFO_FILENAME = 80;

    /**
     * struct _FILE_DIRECTORY_INFORMATION {
     *   ULONG         NextEntryOffset;  // offset = 0
     *   ULONG         FileIndex;        // offset = 4
     *   LARGE_INTEGER CreationTime;     // offset = 8
     *   LARGE_INTEGER LastAccessTime;   // offset = 16
     *   LARGE_INTEGER LastWriteTime;    // offset = 24
     *   LARGE_INTEGER ChangeTime;       // offset = 32
     *   LARGE_INTEGER EndOfFile;        // offset = 40
     *   LARGE_INTEGER AllocationSize;   // offset = 48
     *   ULONG         FileAttributes;   // offset = 56
     *   ULONG         FileNameLength;   // offset = 60
     *   WCHAR         FileName[1];      // offset = 64
     * }
     */
    private static final int OFFSETOF_DIR_INFO_NEXT_ENTRY_OFFSET = 0;
    private static final int OFFSETOF_DIR_INFO_CREATION_TIME = 8;
    private static final int OFFSETOF_DIR_INFO_LAST_ACCESS_TIME = 16;
    private static final int OFFSETOF_DIR_INFO_LAST_WRITE_TIME = 24;
    private static final int OFFSETOF_DIR_INFO_END_OF_FILE = 40;
    private static final int OFFSETOF_DIR_INFO_FILE_ATTRIBUTES = 56;
    private static final int OFFSETOF_DIR_INFO_FILENAME_LENGTH = 60;
    private static final int OFFSETOF_DIR_INFO_FILENAME = 64;

    // used to adjust values between Windows and java epoch
    private static final long WINDOWS_EPOCH_IN_MICROSECONDS = -11644473600000000L;

    // indicates if accurate metadata is required (interesting on NTFS only)
    private static final boolean ensureAccurateMetadata;
    static {
        String propValue = GetPropertyAction.privilegedGetProperty(
            "sun.nio.fs.ensureAccurateMetadata", "false");
        ensureAccurateMetadata = propValue.isEmpty() ? true : Boolean.parseBoolean(propValue);
    }

    // attributes
    private final int fileAttrs;
    private final long creationTime;
    private final long lastAccessTime;
    private final long lastWriteTime;
    private final long size;
    private final int reparseTag;

    // additional attributes when using GetFileInformationByHandle
    private final int volSerialNumber;
    private final int fileIndexHigh;
    private final int fileIndexLow;

    // created lazily
    private volatile WindowsFileKey key;

    /**
     * Convert 64-bit value representing the number of 100-nanosecond intervals
     * since January 1, 1601 to a FileTime.
     */
    static FileTime toFileTime(long time) {
        try {
            long adjusted = Math.addExact(time, WINDOWS_EPOCH_IN_100NS);
            long nanos = Math.multiplyExact(adjusted, 100L);
            return FileTime.from(nanos, TimeUnit.NANOSECONDS);
        } catch (ArithmeticException e) {
            long micros = Math.addExact(time/10L, WINDOWS_EPOCH_IN_MICROS);
            return FileTime.from(micros, TimeUnit.MICROSECONDS);
        }
    }

    /**
     * Convert FileTime to 64-bit value representing the number of
     * 100-nanosecond intervals since January 1, 1601.
     */
    static long toWindowsTime(FileTime time) {
        long adjusted = time.to(TimeUnit.NANOSECONDS)/100L;
        return adjusted - WINDOWS_EPOCH_IN_100NS;
    }

    /**
     * Initialize a new instance of this class
     */
    private WindowsFileAttributes(int fileAttrs,
                                  long creationTime,
                                  long lastAccessTime,
                                  long lastWriteTime,
                                  long size,
                                  int reparseTag,
                                  int volSerialNumber,
                                  int fileIndexHigh,
                                  int fileIndexLow)
    {
        this.fileAttrs = fileAttrs;
        this.creationTime = creationTime;
        this.lastAccessTime = lastAccessTime;
        this.lastWriteTime = lastWriteTime;
        this.size = size;
        this.reparseTag = reparseTag;
        this.volSerialNumber = volSerialNumber;
        this.fileIndexHigh = fileIndexHigh;
        this.fileIndexLow = fileIndexLow;
    }

    /**
     * Create a WindowsFileAttributes from a BY_HANDLE_FILE_INFORMATION structure
     */
    private static WindowsFileAttributes fromFileInformation(long address, int reparseTag) {
        int fileAttrs = unsafe.getInt(address + OFFSETOF_FILE_INFORMATION_ATTRIBUTES);
        long creationTime = unsafe.getLong(address + OFFSETOF_FILE_INFORMATION_CREATETIME);
        long lastAccessTime = unsafe.getLong(address + OFFSETOF_FILE_INFORMATION_LASTACCESSTIME);
        long lastWriteTime = unsafe.getLong(address + OFFSETOF_FILE_INFORMATION_LASTWRITETIME);
        long size = ((long)(unsafe.getInt(address + OFFSETOF_FILE_INFORMATION_SIZEHIGH)) << 32)
            + (unsafe.getInt(address + OFFSETOF_FILE_INFORMATION_SIZELOW) & 0xFFFFFFFFL);
        int volSerialNumber = unsafe.getInt(address + OFFSETOF_FILE_INFORMATION_VOLSERIALNUM);
        int fileIndexHigh = unsafe.getInt(address + OFFSETOF_FILE_INFORMATION_INDEXHIGH);
        int fileIndexLow = unsafe.getInt(address + OFFSETOF_FILE_INFORMATION_INDEXLOW);
        return new WindowsFileAttributes(fileAttrs,
                                         creationTime,
                                         lastAccessTime,
                                         lastWriteTime,
                                         size,
                                         reparseTag,
                                         volSerialNumber,
                                         fileIndexHigh,
                                         fileIndexLow);
    }

    /**
     * Create a WindowsFileAttributes from a WIN32_FILE_ATTRIBUTE_DATA structure
     */
    private static WindowsFileAttributes fromFileAttributeData(long address, int reparseTag) {
        int fileAttrs = unsafe.getInt(address + OFFSETOF_FILE_ATTRIBUTE_DATA_ATTRIBUTES);
        long creationTime = unsafe.getLong(address + OFFSETOF_FILE_ATTRIBUTE_DATA_CREATETIME);
        long lastAccessTime = unsafe.getLong(address + OFFSETOF_FILE_ATTRIBUTE_DATA_LASTACCESSTIME);
        long lastWriteTime = unsafe.getLong(address + OFFSETOF_FILE_ATTRIBUTE_DATA_LASTWRITETIME);
        long size = ((long)(unsafe.getInt(address + OFFSETOF_FILE_ATTRIBUTE_DATA_SIZEHIGH)) << 32)
            + (unsafe.getInt(address + OFFSETOF_FILE_ATTRIBUTE_DATA_SIZELOW) & 0xFFFFFFFFL);
        return new WindowsFileAttributes(fileAttrs,
                                         creationTime,
                                         lastAccessTime,
                                         lastWriteTime,
                                         size,
                                         reparseTag,
                                         0,  // volSerialNumber
                                         0,  // fileIndexHigh
                                         0); // fileIndexLow
    }


    /**
     * Allocates a native buffer for a WIN32_FIND_DATA structure
     */
    static NativeBuffer getBufferForFindData() {
        return NativeBuffers.getNativeBuffer(SIZEOF_FIND_DATA);
    }

    /**
     * Create a WindowsFileAttributes from a WIN32_FIND_DATA structure
     */
    static WindowsFileAttributes fromFindData(long address) {
        int fileAttrs = unsafe.getInt(address + OFFSETOF_FIND_DATA_ATTRIBUTES);
        long creationTime = unsafe.getLong(address + OFFSETOF_FIND_DATA_CREATETIME);
        long lastAccessTime = unsafe.getLong(address + OFFSETOF_FIND_DATA_LASTACCESSTIME);
        long lastWriteTime = unsafe.getLong(address + OFFSETOF_FIND_DATA_LASTWRITETIME);
        long size = ((long)(unsafe.getInt(address + OFFSETOF_FIND_DATA_SIZEHIGH)) << 32)
            + (unsafe.getInt(address + OFFSETOF_FIND_DATA_SIZELOW) & 0xFFFFFFFFL);
        int reparseTag = isReparsePoint(fileAttrs) ?
            unsafe.getInt(address + OFFSETOF_FIND_DATA_RESERVED0) : 0;
        return new WindowsFileAttributes(fileAttrs,
                                         creationTime,
                                         lastAccessTime,
                                         lastWriteTime,
                                         size,
                                         reparseTag,
                                         0,  // volSerialNumber
                                         0,  // fileIndexHigh
                                         0); // fileIndexLow
    }

    /**
     * Create a WindowsFileAttributes from either a FILE_ID_FULL_DIR_INFORMATION
     * or FILE_DIRECTORY_INFORMATION structure depending on the value of
     * QueryDirectoryInformation.supportsFullIdInfo().
     */
    static WindowsFileAttributes fromFileDirInformation(QueryDirectoryInformation info, long address) {
        if (info.supportsFullIdInfo()) { // address points to struct FILE_ID_FULL_DIR_INFORMATION
            int fileAttrs = unsafe.getInt(address + OFFSETOF_FULL_DIR_INFO_FILE_ATTRIBUTES);
            long creationTime = unsafe.getLong(address + OFFSETOF_FULL_DIR_INFO_CREATION_TIME);
            long lastAccessTime = unsafe.getLong(address + OFFSETOF_FULL_DIR_INFO_LAST_ACCESS_TIME);
            long lastWriteTime = unsafe.getLong(address + OFFSETOF_FULL_DIR_INFO_LAST_WRITE_TIME);
            long size = unsafe.getLong(address + OFFSETOF_FULL_DIR_INFO_END_OF_FILE);
            int reparseTag = isReparsePoint(fileAttrs) ?
                    unsafe.getInt(address + OFFSETOF_FULL_DIR_INFO_EA_SIZE) : 0;
            int volSerialNumber = info.volSerialNumber();
            int fileIndexLow = unsafe.getInt(address + OFFSETOF_FULL_DIR_INFO_FILE_ID);
            int fileIndexHigh = unsafe.getInt(address + OFFSETOF_FULL_DIR_INFO_FILE_ID + 4);

            return new WindowsFileAttributes(fileAttrs,
                    creationTime,
                    lastAccessTime,
                    lastWriteTime,
                    size,
                    reparseTag,
                    volSerialNumber,
                    fileIndexHigh,  // fileIndexHigh
                    fileIndexLow); // fileIndexLow
        } else { // address points to FILE_DIRECTORY_INFORMATION
            int fileAttrs = unsafe.getInt(address + OFFSETOF_DIR_INFO_FILE_ATTRIBUTES);
            long creationTime = unsafe.getLong(address + OFFSETOF_DIR_INFO_CREATION_TIME);
            long lastAccessTime = unsafe.getLong(address + OFFSETOF_DIR_INFO_LAST_ACCESS_TIME);
            long lastWriteTime = unsafe.getLong(address + OFFSETOF_DIR_INFO_LAST_WRITE_TIME);
            long size = unsafe.getLong(address + OFFSETOF_DIR_INFO_END_OF_FILE);
            int reparseTag = 0;
            // Don't provide the real serial number  as the reset of the code assumes that presence of
            // the volume serial means that the file id is also valid, which isn't the case here.
            // This will make comparing Path's for equality slower as the file id serves as
            // a unique file key (@see fileKey()).
            int volSerialNumber = 0;
            int fileIndexLow = 0;
            int fileIndexHigh = 0;

            return new WindowsFileAttributes(fileAttrs,
                    creationTime,
                    lastAccessTime,
                    lastWriteTime,
                    size,
                    reparseTag,
                    volSerialNumber,
                    fileIndexHigh,
                    fileIndexLow);
        }
    }

    static int getNextOffsetFromFileDirInformation(QueryDirectoryInformation info, long address) {
        return unsafe.getInt(address
                + (info.supportsFullIdInfo() ? OFFSETOF_FULL_DIR_INFO_NEXT_ENTRY_OFFSET
                                             : OFFSETOF_DIR_INFO_NEXT_ENTRY_OFFSET));
    }

    static String getFileNameFromFileDirInformation(QueryDirectoryInformation info, long address) {
        // copy the name
        int nameLengthInBytes = unsafe.getInt(address
                + (info.supportsFullIdInfo() ? OFFSETOF_FULL_DIR_INFO_FILENAME_LENGTH
                                             : OFFSETOF_DIR_INFO_FILENAME_LENGTH));
        if ((nameLengthInBytes % 2) != 0) {
            throw new AssertionError("FileNameLength is not a multiple of 2");
        }
        char[] nameAsArray = new char[nameLengthInBytes/2];
        unsafe.copyMemory(null,
                address + (info.supportsFullIdInfo() ? OFFSETOF_FULL_DIR_INFO_FILENAME
                                                     : OFFSETOF_DIR_INFO_FILENAME),
                nameAsArray,
                Unsafe.ARRAY_CHAR_BASE_OFFSET, nameLengthInBytes);
        return new String(nameAsArray);
    }

    /**
     * Reads the attributes of an open file
     */
    static WindowsFileAttributes readAttributes(long handle)
        throws WindowsException
    {
        NativeBuffer buffer = NativeBuffers
            .getNativeBuffer(SIZEOF_FILE_INFORMATION);
        try {
            long address = buffer.address();
            GetFileInformationByHandle(handle, address);

            // if file is a reparse point then read the tag
            int reparseTag = 0;
            int fileAttrs = unsafe
                .getInt(address + OFFSETOF_FILE_INFORMATION_ATTRIBUTES);
            if (isReparsePoint(fileAttrs)) {
                int size = MAXIMUM_REPARSE_DATA_BUFFER_SIZE;
                NativeBuffer reparseBuffer = NativeBuffers.getNativeBuffer(size);
                try {
                    DeviceIoControlGetReparsePoint(handle, reparseBuffer.address(), size);
                    reparseTag = (int)unsafe.getLong(reparseBuffer.address());
                } finally {
                    reparseBuffer.release();
                }
            }

            return fromFileInformation(address, reparseTag);
        } finally {
            buffer.release();
        }
    }

    /**
     * Returns attributes of given file.
     */
    static WindowsFileAttributes get(WindowsPath path, boolean followLinks)
        throws WindowsException
    {
        if (!ensureAccurateMetadata) {
            WindowsException firstException = null;

            // GetFileAttributesEx is the fastest way to read the attributes
            NativeBuffer buffer =
                NativeBuffers.getNativeBuffer(SIZEOF_FILE_ATTRIBUTE_DATA);
            try {
                long address = buffer.address();
                GetFileAttributesEx(path.getPathForWin32Calls(), address);
                // if reparse point then file may be a sym link; otherwise
                // just return the attributes
                int fileAttrs = unsafe
                    .getInt(address + OFFSETOF_FILE_ATTRIBUTE_DATA_ATTRIBUTES);
                if (!isReparsePoint(fileAttrs))
                    return fromFileAttributeData(address, 0);
            } catch (WindowsException x) {
                if (x.lastError() != ERROR_SHARING_VIOLATION)
                    throw x;
                firstException = x;
            } finally {
                buffer.release();
            }

            // For sharing violations, fallback to FindFirstFile if the file
            // is not a root directory.
            if (firstException != null) {
                String search = path.getPathForWin32Calls();
                char last = search.charAt(search.length() -1);
                if (last == ':' || last == '\\')
                    throw firstException;
                buffer = getBufferForFindData();
                try {
                    long handle = FindFirstFile(search, buffer.address());
                    FindClose(handle);
                    WindowsFileAttributes attrs = fromFindData(buffer.address());
                    // FindFirstFile does not follow sym links. Even if
                    // followLinks is false, there isn't sufficient information
                    // in the WIN32_FIND_DATA structure to know if the reparse
                    // point is a sym link.
                    if (attrs.isReparsePoint())
                        throw firstException;
                    return attrs;
                } catch (WindowsException ignore) {
                    throw firstException;
                } finally {
                    buffer.release();
                }
            }
        }

        // file is reparse point so need to open file to get attributes
        return getWithFileKey(path, followLinks);
    }

    /**
     * Returns attributes of given file.
     */
    static WindowsFileAttributes getWithFileKey(WindowsPath path, boolean followLinks)
        throws WindowsException
    {
        long handle = path.openForReadAttributeAccess(followLinks);
        try {
            return readAttributes(handle);
        } finally {
            CloseHandle(handle);
        }
    }

    /**
     * Returns true if the attributes are of the same file - both files must
     * be open.
     */
    static boolean isSameFile(WindowsFileAttributes attrs1,
                              WindowsFileAttributes attrs2)
    {
        // volume serial number and file index must be the same
        return (attrs1.volSerialNumber == attrs2.volSerialNumber) &&
               (attrs1.fileIndexHigh == attrs2.fileIndexHigh) &&
               (attrs1.fileIndexLow == attrs2.fileIndexLow);
    }

    /**
     * Returns true if the attributes are of a file with a reparse point.
     */
    static boolean isReparsePoint(int attributes) {
        return (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
    }

    // package-private
    int attributes() {
        return fileAttrs;
    }

    int volSerialNumber() {
        return volSerialNumber;
    }

    int fileIndexHigh() {
        return fileIndexHigh;
    }

    int fileIndexLow() {
        return fileIndexLow;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public FileTime lastModifiedTime() {
        return toFileTime(lastWriteTime);
    }

    @Override
    public FileTime lastAccessTime() {
        return toFileTime(lastAccessTime);
    }

    @Override
    public FileTime creationTime() {
        return toFileTime(creationTime);
    }

    @Override
    public Object fileKey() {
        if (volSerialNumber == 0) {
            return null;
        }
        if (key == null) {
            synchronized (this) {
                if (key == null) {
                    key = new WindowsFileKey(volSerialNumber, ((long)fileIndexHigh << 32) + fileIndexLow);
                }
            }
        }
        return key;
    }

    // package private
    boolean isReparsePoint() {
        return isReparsePoint(fileAttrs);
    }

    boolean isDirectoryLink() {
        return isSymbolicLink() && ((fileAttrs & FILE_ATTRIBUTE_DIRECTORY) != 0);
    }

    @Override
    public boolean isSymbolicLink() {
        return reparseTag == IO_REPARSE_TAG_SYMLINK;
    }

    boolean isUnixDomainSocket() {
        return reparseTag == IO_REPARSE_TAG_AF_UNIX;
    }

    @Override
    public boolean isDirectory() {
        // ignore FILE_ATTRIBUTE_DIRECTORY attribute if file is a sym link
        if (isSymbolicLink())
            return false;
        return ((fileAttrs & FILE_ATTRIBUTE_DIRECTORY) != 0);
    }

    @Override
    public boolean isOther() {
        if (isSymbolicLink())
            return false;
        // return true if device or reparse point
        return ((fileAttrs & (FILE_ATTRIBUTE_DEVICE | FILE_ATTRIBUTE_REPARSE_POINT)) != 0);
    }

    @Override
    public boolean isRegularFile() {
        return !isSymbolicLink() && !isDirectory() && !isOther();
    }

    @Override
    public boolean isReadOnly() {
        return (fileAttrs & FILE_ATTRIBUTE_READONLY) != 0;
    }

    @Override
    public boolean isHidden() {
        return (fileAttrs & FILE_ATTRIBUTE_HIDDEN) != 0;
    }

    @Override
    public boolean isArchive() {
        return (fileAttrs & FILE_ATTRIBUTE_ARCHIVE) != 0;
    }

    @Override
    public boolean isSystem() {
        return (fileAttrs & FILE_ATTRIBUTE_SYSTEM) != 0;
    }
}
