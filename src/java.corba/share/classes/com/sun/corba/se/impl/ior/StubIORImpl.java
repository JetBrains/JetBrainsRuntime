/*
 * Copyright (c) 2003, 2018, Oracle and/or its affiliates. All rights reserved.
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
/*
 * Licensed Materials - Property of IBM
 * RMI-IIOP v1.0
 * Copyright IBM Corp. 1998 1999  All Rights Reserved
 *
 */

package com.sun.corba.se.impl.ior;

import java.io.ObjectInputFilter ;
import java.io.ObjectInputStream ;
import java.io.ObjectOutputStream ;
import java.io.InvalidClassException ;
import java.io.IOException ;
import java.util.Objects;

import org.omg.CORBA.ORB ;

import org.omg.CORBA.portable.Delegate ;
import org.omg.CORBA.portable.InputStream ;
import org.omg.CORBA.portable.OutputStream ;

// Be very careful: com.sun.corba imports must not depend on
// PEORB internal classes in ways that prevent portability to
// other vendor's ORBs.
import com.sun.corba.se.spi.presentation.rmi.StubAdapter ;
import com.sun.corba.se.impl.orbutil.HexOutputStream ;

/**
 * This class implements a very simply IOR representation
 * which must be completely ORBImpl free so that this class
 * can be used in the implementation of a portable StubDelegateImpl.
 */
public class StubIORImpl
{
    // cached hash code
    private int hashCode;

    // IOR components
    private byte[] typeData;
    private int[] profileTags;
    private byte[][] profileData;

    public StubIORImpl()
    {
        hashCode = 0 ;
        typeData = null ;
        profileTags = null ;
        profileData = null ;
    }

    public String getRepositoryId()
    {
        if (typeData == null)
            return null ;

        return new String( typeData ) ;
    }

    public StubIORImpl( org.omg.CORBA.Object obj )
    {
        // write the IOR to an OutputStream and get an InputStream
        OutputStream ostr = StubAdapter.getORB( obj ).create_output_stream();
        ostr.write_Object(obj);
        InputStream istr = ostr.create_input_stream();

        // read the IOR components back from the stream
        int typeLength = istr.read_long();
        typeData = new byte[typeLength];
        istr.read_octet_array(typeData, 0, typeLength);
        int numProfiles = istr.read_long();
        profileTags = new int[numProfiles];
        profileData = new byte[numProfiles][];
        for (int i = 0; i < numProfiles; i++) {
            profileTags[i] = istr.read_long();
            profileData[i] = new byte[istr.read_long()];
            istr.read_octet_array(profileData[i], 0, profileData[i].length);
        }
    }

    public Delegate getDelegate( ORB orb )
    {
        // write the IOR components to an org.omg.CORBA.portable.OutputStream
        OutputStream ostr = orb.create_output_stream();
        ostr.write_long(typeData.length);
        ostr.write_octet_array(typeData, 0, typeData.length);
        ostr.write_long(profileTags.length);
        for (int i = 0; i < profileTags.length; i++) {
            ostr.write_long(profileTags[i]);
            ostr.write_long(profileData[i].length);
            ostr.write_octet_array(profileData[i], 0, profileData[i].length);
        }

        InputStream istr = ostr.create_input_stream() ;

        // read the IOR back from the stream
        org.omg.CORBA.Object obj = (org.omg.CORBA.Object)istr.read_Object();
        return StubAdapter.getDelegate( obj ) ;
    }

    public  void doRead( java.io.ObjectInputStream stream )
        throws IOException, ClassNotFoundException
    {
        // read the IOR from the ObjectInputStream
        int typeLength = stream.readInt();
        checkArray(stream, byte[].class, typeLength);
        typeData = new byte[typeLength];
        stream.readFully(typeData);

        int numProfiles = stream.readInt();
        checkArray(stream, int[].class, numProfiles);
        checkArray(stream, byte[].class, numProfiles);
        profileTags = new int[numProfiles];
        profileData = new byte[numProfiles][];
        for (int i = 0; i < numProfiles; i++) {
            profileTags[i] = stream.readInt();
            int dataSize = stream.readInt();
            checkArray(stream, byte[].class, dataSize);
            profileData[i] = new byte[dataSize];
            stream.readFully(profileData[i]);
        }
    }

    public  void doWrite( ObjectOutputStream stream )
        throws IOException
    {
        // write the IOR to the ObjectOutputStream
        stream.writeInt(typeData.length);
        stream.write(typeData);
        stream.writeInt(profileTags.length);
        for (int i = 0; i < profileTags.length; i++) {
            stream.writeInt(profileTags[i]);
            stream.writeInt(profileData[i].length);
            stream.write(profileData[i]);
        }
    }

    /**
     * Returns a hash code value for the object which is the same for all stubs
     * that represent the same remote object.
     * @return the hash code value.
     */
    public synchronized int hashCode()
    {
        if (hashCode == 0) {

            // compute the hash code
            for (int i = 0; i < typeData.length; i++) {
                hashCode = hashCode * 37 + typeData[i];
            }

            for (int i = 0; i < profileTags.length; i++) {
                hashCode = hashCode * 37 + profileTags[i];
                for (int j = 0; j < profileData[i].length; j++) {
                    hashCode = hashCode * 37 + profileData[i][j];
                }
            }
        }

        return hashCode;
    }

    private boolean equalArrays( int[] data1, int[] data2 )
    {
        if (data1.length != data2.length)
            return false ;

        for (int ctr=0; ctr<data1.length; ctr++) {
            if (data1[ctr] != data2[ctr])
                return false ;
        }

        return true ;
    }

    private boolean equalArrays( byte[] data1, byte[] data2 )
    {
        if (data1.length != data2.length)
            return false ;

        for (int ctr=0; ctr<data1.length; ctr++) {
            if (data1[ctr] != data2[ctr])
                return false ;
        }

        return true ;
    }

    private boolean equalArrays( byte[][] data1, byte[][] data2 )
    {
        if (data1.length != data2.length)
            return false ;

        for (int ctr=0; ctr<data1.length; ctr++) {
            if (!equalArrays( data1[ctr], data2[ctr] ))
                return false ;
        }

        return true ;
    }

    public boolean equals(java.lang.Object obj)
    {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof StubIORImpl)) {
            return false;
        }

        StubIORImpl other = (StubIORImpl) obj;
        if (other.hashCode() != this.hashCode()) {
            return false;
        }

        return equalArrays( typeData, other.typeData ) &&
            equalArrays( profileTags, other.profileTags ) &&
            equalArrays( profileData, other.profileData ) ;
    }

    private void appendByteArray( StringBuffer result, byte[] data )
    {
        for ( int ctr=0; ctr<data.length; ctr++ ) {
            result.append( Integer.toHexString( data[ctr] ) ) ;
        }
    }

    /**
     * Checks the given array type and length to ensure that creation of such
     * an array is permitted by the ObjectInputStream. The arrayType argument
     * must represent an actual array type.
     *
     * @param stream the ObjectInputStream
     * @param arrayType the array type
     * @param arrayLength the array length
     * @throws InvalidClassException if the filter rejects creation
     */
    private void checkArray(ObjectInputStream stream,
                            Class<?> arrayType, int arrayLength) throws
            InvalidClassException {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(arrayType, "arrayType");
        ObjectInputFilter filter = stream.getObjectInputFilter();
        if (filter != null) {
            RuntimeException ex = null;
            ObjectInputFilter.Status status;
            try {
                status = filter.checkInput(new FilterValues(arrayType, arrayLength,
                        0, 0, 0));    // actual info not available
            } catch (RuntimeException e) {
                // Prevent interception of an exception
                status = ObjectInputFilter.Status.REJECTED;
                ex = e;
            }
            if (status == null || status == ObjectInputFilter.Status.REJECTED) {
                // Log filter checks that fail; then throw InvalidClassException
                System.Logger filterLog = System.getLogger("java.io.serialization");
                filterLog.log(System.Logger.Level.DEBUG,
                        "ObjectInputFilter {0}: {1}, array length: {2}, nRefs: {3}, depth: {4}, " +
                                "bytes: {5}, ex: {6}",
                        status, arrayType, arrayLength, 0, -0, 0,
                        Objects.toString(ex, "n/a"));

                InvalidClassException ice = new InvalidClassException("filter status: " + status);
                ice.initCause(ex);
                throw ice;
            }
        }
    }

    /**
     * Returns a string representation of this stub. Returns the same string
     * for all stubs that represent the same remote object.
     * {@code "SimpleIORImpl[<typeName>,[<profileID>]data, ...]"}
     * @return a string representation of this stub.
     */
    public String toString()
    {
        StringBuffer result = new StringBuffer() ;
        result.append( "SimpleIORImpl[" ) ;
        String repositoryId = new String( typeData ) ;
        result.append( repositoryId ) ;
        for (int ctr=0; ctr<profileTags.length; ctr++) {
            result.append( ",(" ) ;
            result.append( profileTags[ctr] ) ;
            result.append( ")" ) ;
            appendByteArray( result,  profileData[ctr] ) ;
        }

        result.append( "]" ) ;
        return result.toString() ;
    }

    /**
     * Hold a snapshot of values to be passed to an ObjectInputFilter.
     */
    static class FilterValues implements ObjectInputFilter.FilterInfo {
        final Class<?> clazz;
        final long arrayLength;
        final long totalObjectRefs;
        final long depth;
        final long streamBytes;

        public FilterValues(Class<?> clazz, long arrayLength, long totalObjectRefs,
                            long depth, long streamBytes) {
            this.clazz = clazz;
            this.arrayLength = arrayLength;
            this.totalObjectRefs = totalObjectRefs;
            this.depth = depth;
            this.streamBytes = streamBytes;
        }

        @Override
        public Class<?> serialClass() {
            return clazz;
        }

        @Override
        public long arrayLength() {
            return arrayLength;
        }

        @Override
        public long references() {
            return totalObjectRefs;
        }

        @Override
        public long depth() {
            return depth;
        }

        @Override
        public long streamBytes() {
            return streamBytes;
        }
    }
}
