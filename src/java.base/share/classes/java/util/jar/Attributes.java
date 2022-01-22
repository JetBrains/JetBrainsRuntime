/*
 * Copyright (c) 1997, 2021, Oracle and/or its affiliates. All rights reserved.
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

package java.util.jar;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import sun.nio.cs.UTF_8;
import sun.nio.cs.ISO_8859_1;
import sun.util.logging.PlatformLogger;

/**
 * The Attributes class maps Manifest attribute names to associated string
 * values. Valid attribute names are case-insensitive, are restricted to
 * the ASCII characters in the set [0-9a-zA-Z_-], and cannot exceed 70
 * characters in length. There must be a colon and a SPACE after the name;
 * the combined length will not exceed 72 characters.
 * Attribute values can contain any characters and
 * will be UTF8-encoded when written to the output stream.  See the
 * <a href="{@docRoot}/../specs/jar/jar.html">JAR File Specification</a>
 * for more information about valid attribute names and values.
 *
 * <p>This map and its views have a predictable iteration order, namely the
 * order that keys were inserted into the map, as with {@link LinkedHashMap}.
 *
 * @author  David Connelly
 * @see     Manifest
 * @since   1.2
 */
public class Attributes implements Map<Object,Object>, Cloneable {
    /**
     * The attribute name-value mappings.
     */
    protected Map<Object,Object> map;

    /**
     * Constructs a new, empty Attributes object with default size.
     */
    public Attributes() {
        this(11);
    }

    /**
     * Constructs a new, empty Attributes object with the specified
     * initial size.
     *
     * @param size the initial number of attributes
     */
    public Attributes(int size) {
        map = new LinkedHashMap<>(size);
    }

    /**
     * Constructs a new Attributes object with the same attribute name-value
     * mappings as in the specified Attributes.
     *
     * @param attr the specified Attributes
     */
    public Attributes(Attributes attr) {
        map = new LinkedHashMap<>(attr);
    }


    /**
     * Returns the value of the specified attribute name, or null if the
     * attribute name was not found.
     *
     * @param name the attribute name
     * @return the value of the specified attribute name, or null if
     *         not found.
     */
    public Object get(Object name) {
        return map.get(name);
    }

    /**
     * Returns the value of the specified attribute name, specified as
     * a string, or null if the attribute was not found. The attribute
     * name is case-insensitive.
     * <p>
     * This method is defined as:
     * <pre>
     *      return (String)get(new Attributes.Name((String)name));
     * </pre>
     *
     * @param name the attribute name as a string
     * @return the String value of the specified attribute name, or null if
     *         not found.
     * @throws IllegalArgumentException if the attribute name is invalid
     */
    public String getValue(String name) {
        return (String)get(Name.of(name));
    }

    /**
     * Returns the value of the specified Attributes.Name, or null if the
     * attribute was not found.
     * <p>
     * This method is defined as:
     * <pre>
     *     return (String)get(name);
     * </pre>
     *
     * @param name the Attributes.Name object
     * @return the String value of the specified Attribute.Name, or null if
     *         not found.
     */
    public String getValue(Name name) {
        return (String)get(name);
    }

    /**
     * Associates the specified value with the specified attribute name
     * (key) in this Map. If the Map previously contained a mapping for
     * the attribute name, the old value is replaced.
     *
     * @param name the attribute name
     * @param value the attribute value
     * @return the previous value of the attribute, or null if none
     * @exception ClassCastException if the name is not a Attributes.Name
     *            or the value is not a String
     */
    public Object put(Object name, Object value) {
        return map.put((Attributes.Name)name, (String)value);
    }

    /**
     * Associates the specified value with the specified attribute name,
     * specified as a String. The attributes name is case-insensitive.
     * If the Map previously contained a mapping for the attribute name,
     * the old value is replaced.
     * <p>
     * This method is defined as:
     * <pre>
     *      return (String)put(new Attributes.Name(name), value);
     * </pre>
     *
     * @param name the attribute name as a string
     * @param value the attribute value
     * @return the previous value of the attribute, or null if none
     * @exception IllegalArgumentException if the attribute name is invalid
     */
    public String putValue(String name, String value) {
        return (String)put(Name.of(name), value);
    }

    /**
     * Removes the attribute with the specified name (key) from this Map.
     * Returns the previous attribute value, or null if none.
     *
     * @param name attribute name
     * @return the previous value of the attribute, or null if none
     */
    public Object remove(Object name) {
        return map.remove(name);
    }

    /**
     * Returns true if this Map maps one or more attribute names (keys)
     * to the specified value.
     *
     * @param value the attribute value
     * @return true if this Map maps one or more attribute names to
     *         the specified value
     */
    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    /**
     * Returns true if this Map contains the specified attribute name (key).
     *
     * @param name the attribute name
     * @return true if this Map contains the specified attribute name
     */
    public boolean containsKey(Object name) {
        return map.containsKey(name);
    }

    /**
     * Copies all of the attribute name-value mappings from the specified
     * Attributes to this Map. Duplicate mappings will be replaced.
     *
     * @param attr the Attributes to be stored in this map
     * @exception ClassCastException if attr is not an Attributes
     */
    public void putAll(Map<?,?> attr) {
        // ## javac bug?
        if (!Attributes.class.isInstance(attr))
            throw new ClassCastException();
        for (Map.Entry<?,?> me : (attr).entrySet())
            put(me.getKey(), me.getValue());
    }

    /**
     * Removes all attributes from this Map.
     */
    public void clear() {
        map.clear();
    }

    /**
     * Returns the number of attributes in this Map.
     */
    public int size() {
        return map.size();
    }

    /**
     * Returns true if this Map contains no attributes.
     */
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * Returns a Set view of the attribute names (keys) contained in this Map.
     */
    public Set<Object> keySet() {
        return map.keySet();
    }

    /**
     * Returns a Collection view of the attribute values contained in this Map.
     */
    public Collection<Object> values() {
        return map.values();
    }

    /**
     * Returns a Collection view of the attribute name-value mappings
     * contained in this Map.
     */
    public Set<Map.Entry<Object,Object>> entrySet() {
        return map.entrySet();
    }

    /**
     * Compares the specified Attributes object with this Map for equality.
     * Returns true if the given object is also an instance of Attributes
     * and the two Attributes objects represent the same mappings.
     *
     * @param o the Object to be compared
     * @return true if the specified Object is equal to this Map
     */
    public boolean equals(Object o) {
        return map.equals(o);
    }

    /**
     * Returns the hash code value for this Map.
     */
    public int hashCode() {
        return map.hashCode();
    }

    /**
     * Returns a copy of the Attributes, implemented as follows:
     * <pre>
     *     public Object clone() { return new Attributes(this); }
     * </pre>
     * Since the attribute names and values are themselves immutable,
     * the Attributes returned can be safely modified without affecting
     * the original.
     */
    public Object clone() {
        return new Attributes(this);
    }

    /*
     * Writes the current attributes to the specified data output stream.
     * XXX Need to handle UTF8 values and break up lines longer than 72 bytes
     */
     @SuppressWarnings("deprecation")
     void write(DataOutputStream os) throws IOException {
         for (Entry<Object, Object> e : entrySet()) {
             StringBuffer buffer = new StringBuffer(
                                         ((Name) e.getKey()).toString());
             buffer.append(": ");

             String value = (String) e.getValue();
             if (value != null) {
                 byte[] vb = value.getBytes(UTF_8.INSTANCE);
                 value = new String(vb, 0, 0, vb.length);
             }
             buffer.append(value);

             Manifest.make72Safe(buffer);
             buffer.append("\r\n");
             os.writeBytes(buffer.toString());
         }
        os.writeBytes("\r\n");
    }

    /*
     * Writes the current attributes to the specified data output stream,
     * make sure to write out the MANIFEST_VERSION or SIGNATURE_VERSION
     * attributes first.
     *
     * XXX Need to handle UTF8 values and break up lines longer than 72 bytes
     */
    @SuppressWarnings("deprecation")
    void writeMain(DataOutputStream out) throws IOException
    {
        // write out the *-Version header first, if it exists
        String vername = Name.MANIFEST_VERSION.toString();
        String version = getValue(vername);
        if (version == null) {
            vername = Name.SIGNATURE_VERSION.toString();
            version = getValue(vername);
        }

        if (version != null) {
            out.writeBytes(vername+": "+version+"\r\n");
        }

        // write out all attributes except for the version
        // we wrote out earlier
        for (Entry<Object, Object> e : entrySet()) {
            String name = ((Name) e.getKey()).toString();
            if ((version != null) && !(name.equalsIgnoreCase(vername))) {

                StringBuffer buffer = new StringBuffer(name);
                buffer.append(": ");

                String value = (String) e.getValue();
                if (value != null) {
                    byte[] vb = value.getBytes(UTF_8.INSTANCE);
                    value = new String(vb, 0, 0, vb.length);
                }
                buffer.append(value);

                Manifest.make72Safe(buffer);
                buffer.append("\r\n");
                out.writeBytes(buffer.toString());
            }
        }
        out.writeBytes("\r\n");
    }

    /*
     * Reads attributes from the specified input stream.
     * XXX Need to handle UTF8 values.
     */
    @SuppressWarnings("deprecation")
    void read(Manifest.FastInputStream is, byte[] lbuf) throws IOException {
        String name = null, value;
        ByteArrayOutputStream fullLine = new ByteArrayOutputStream();

        int len;
        while ((len = is.readLine(lbuf)) != -1) {
            boolean lineContinued = false;
            byte c = lbuf[--len];
            if (c != '\n' && c != '\r') {
                throw new IOException("line too long");
            }
            if (len > 0 && lbuf[len-1] == '\r') {
                --len;
            }
            if (len == 0) {
                break;
            }
            int i = 0;
            if (lbuf[0] == ' ') {
                // continuation of previous line
                if (name == null) {
                    throw new IOException("misplaced continuation line");
                }
                lineContinued = true;
                fullLine.write(lbuf, 1, len - 1);
                if (is.peek() == ' ') {
                    continue;
                }
                value = fullLine.toString(UTF_8.INSTANCE);
                fullLine.reset();
            } else {
                while (lbuf[i++] != ':') {
                    if (i >= len) {
                        throw new IOException("invalid header field");
                    }
                }
                if (lbuf[i++] != ' ') {
                    throw new IOException("invalid header field");
                }
                name = new String(lbuf, 0, 0, i - 2);
                if (is.peek() == ' ') {
                    fullLine.reset();
                    fullLine.write(lbuf, i, len - i);
                    continue;
                }
                value = new String(lbuf, i, len - i, UTF_8.INSTANCE);
            }
            try {
                if ((putValue(name, value) != null) && (!lineContinued)) {
                    PlatformLogger.getLogger("java.util.jar").warning(
                                     "Duplicate name in Manifest: " + name
                                     + ".\n"
                                     + "Ensure that the manifest does not "
                                     + "have duplicate entries, and\n"
                                     + "that blank lines separate "
                                     + "individual sections in both your\n"
                                     + "manifest and in the META-INF/MANIFEST.MF "
                                     + "entry in the jar file.");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid header field name: " + name);
            }
        }
    }

    /**
     * The Attributes.Name class represents an attribute name stored in
     * this Map. Valid attribute names are case-insensitive, are restricted
     * to the ASCII characters in the set [0-9a-zA-Z_-], and cannot exceed
     * 70 characters in length. Attribute values can contain any characters
     * and will be UTF8-encoded when written to the output stream.  See the
     * <a href="{@docRoot}/../specs/jar/jar.html">JAR File Specification</a>
     * for more information about valid attribute names and values.
     */
    public static class Name {
        private final String name;
        private final int hashCode;

        /**
         * Avoid allocation for common Names
         */
        private static final Map<String, Name> KNOWN_NAMES;

        static final Name of(String name) {
            Name n = KNOWN_NAMES.get(name);
            if (n != null) {
                return n;
            }
            return new Name(name);
        }

        /**
         * Constructs a new attribute name using the given string name.
         *
         * @param name the attribute string name
         * @exception IllegalArgumentException if the attribute name was
         *            invalid
         * @exception NullPointerException if the attribute name was null
         */
        public Name(String name) {
            this.hashCode = hash(name);
            this.name = name.intern();
        }

        // Checks the string is valid
        private final int hash(String name) {
            Objects.requireNonNull(name, "name");
            int len = name.length();
            if (len > 70 || len == 0) {
                throw new IllegalArgumentException(name);
            }
            // Calculate hash code case insensitively
            int h = 0;
            for (int i = 0; i < len; i++) {
                char c = name.charAt(i);
                if (c >= 'a' && c <= 'z') {
                    // hashcode must be identical for upper and lower case
                    h = h * 31 + (c - 0x20);
                } else if ((c >= 'A' && c <= 'Z' ||
                        c >= '0' && c <= '9' ||
                        c == '_' || c == '-')) {
                    h = h * 31 + c;
                } else {
                    throw new IllegalArgumentException(name);
                }
            }
            return h;
        }

        /**
         * Compares this attribute name to another for equality.
         * @param o the object to compare
         * @return true if this attribute name is equal to the
         *         specified attribute object
         */
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof Name) {
                Name other = (Name)o;
                return other.name.equalsIgnoreCase(name);
            } else {
                return false;
            }
        }

        /**
         * Computes the hash value for this attribute name.
         */
        public int hashCode() {
            return hashCode;
        }

        /**
         * Returns the attribute name as a String.
         */
        public String toString() {
            return name;
        }

        /**
         * {@code Name} object for {@code Manifest-Version}
         * manifest attribute. This attribute indicates the version number
         * of the manifest standard to which a JAR file's manifest conforms.
         * @see <a href="{@docRoot}/../specs/jar/jar.html#JAR_Manifest">
         *      Manifest and Signature Specification</a>
         */
        public static final Name MANIFEST_VERSION = new Name("Manifest-Version");

        /**
         * {@code Name} object for {@code Signature-Version}
         * manifest attribute used when signing JAR files.
         * @see <a href="{@docRoot}/../specs/jar/jar.html#JAR_Manifest">
         *      Manifest and Signature Specification</a>
         */
        public static final Name SIGNATURE_VERSION = new Name("Signature-Version");

        /**
         * {@code Name} object for {@code Content-Type}
         * manifest attribute.
         */
        public static final Name CONTENT_TYPE = new Name("Content-Type");

        /**
         * {@code Name} object for {@code Class-Path}
         * manifest attribute.
         * @see <a href="{@docRoot}/../specs/jar/jar.html#classpath">
         *      JAR file specification</a>
         */
        public static final Name CLASS_PATH = new Name("Class-Path");

        /**
         * {@code Name} object for {@code Main-Class} manifest
         * attribute used for launching applications packaged in JAR files.
         * The {@code Main-Class} attribute is used in conjunction
         * with the {@code -jar} command-line option of the
         * {@code java} application launcher.
         */
        public static final Name MAIN_CLASS = new Name("Main-Class");

        /**
         * {@code Name} object for {@code Sealed} manifest attribute
         * used for sealing.
         * @see <a href="{@docRoot}/../specs/jar/jar.html#package-sealing">
         *      Package Sealing</a>
         */
        public static final Name SEALED = new Name("Sealed");

        /**
         * {@code Name} object for {@code Extension-List} manifest attribute
         * used for the extension mechanism that is no longer supported.
         */
        public static final Name EXTENSION_LIST = new Name("Extension-List");

        /**
         * {@code Name} object for {@code Extension-Name} manifest attribute.
         * used for the extension mechanism that is no longer supported.
         */
        public static final Name EXTENSION_NAME = new Name("Extension-Name");

        /**
         * {@code Name} object for {@code Extension-Installation} manifest attribute.
         *
         * @deprecated Extension mechanism is no longer supported.
         */
        @Deprecated
        public static final Name EXTENSION_INSTALLATION = new Name("Extension-Installation");

        /**
         * {@code Name} object for {@code Implementation-Title}
         * manifest attribute used for package versioning.
         */
        public static final Name IMPLEMENTATION_TITLE = new Name("Implementation-Title");

        /**
         * {@code Name} object for {@code Implementation-Version}
         * manifest attribute used for package versioning.
         */
        public static final Name IMPLEMENTATION_VERSION = new Name("Implementation-Version");

        /**
         * {@code Name} object for {@code Implementation-Vendor}
         * manifest attribute used for package versioning.
         */
        public static final Name IMPLEMENTATION_VENDOR = new Name("Implementation-Vendor");

        /**
         * {@code Name} object for {@code Implementation-Vendor-Id}
         * manifest attribute.
         *
         * @deprecated Extension mechanism is no longer supported.
         */
        @Deprecated
        public static final Name IMPLEMENTATION_VENDOR_ID = new Name("Implementation-Vendor-Id");

        /**
         * {@code Name} object for {@code Implementation-URL}
         * manifest attribute.
         *
         * @deprecated Extension mechanism is no longer supported.
         */
        @Deprecated
        public static final Name IMPLEMENTATION_URL = new Name("Implementation-URL");

        /**
         * {@code Name} object for {@code Specification-Title}
         * manifest attribute used for package versioning.
         */
        public static final Name SPECIFICATION_TITLE = new Name("Specification-Title");

        /**
         * {@code Name} object for {@code Specification-Version}
         * manifest attribute used for package versioning.
         */
        public static final Name SPECIFICATION_VERSION = new Name("Specification-Version");

        /**
         * {@code Name} object for {@code Specification-Vendor}
         * manifest attribute used for package versioning.
         */
        public static final Name SPECIFICATION_VENDOR = new Name("Specification-Vendor");

        /**
         * {@code Name} object for {@code Multi-Release}
         * manifest attribute that indicates this is a multi-release JAR file.
         *
         * @since   9
         */
        public static final Name MULTI_RELEASE = new Name("Multi-Release");

        private static void addName(Map<String, Name> names, Name name) {
            names.put(name.name, name);
        }

        static {
            var names = new HashMap<String, Name>(64);
            addName(names, MANIFEST_VERSION);
            addName(names, SIGNATURE_VERSION);
            addName(names, CONTENT_TYPE);
            addName(names, CLASS_PATH);
            addName(names, MAIN_CLASS);
            addName(names, SEALED);
            addName(names, EXTENSION_LIST);
            addName(names, EXTENSION_NAME);
            addName(names, IMPLEMENTATION_TITLE);
            addName(names, IMPLEMENTATION_VERSION);
            addName(names, IMPLEMENTATION_VENDOR);
            addName(names, SPECIFICATION_TITLE);
            addName(names, SPECIFICATION_VERSION);
            addName(names, SPECIFICATION_VENDOR);
            addName(names, MULTI_RELEASE);

            // Common attributes used in MANIFEST.MF et.al; adding these has a
            // small footprint cost, but is likely to be quickly paid for by
            // reducing allocation when reading and parsing typical manifests
            addName(names, new Name("Add-Exports"));
            addName(names, new Name("Add-Opens"));
            addName(names, new Name("Ant-Version"));
            addName(names, new Name("Archiver-Version"));
            addName(names, new Name("Build-Jdk"));
            addName(names, new Name("Built-By"));
            addName(names, new Name("Bnd-LastModified"));
            addName(names, new Name("Bundle-Description"));
            addName(names, new Name("Bundle-DocURL"));
            addName(names, new Name("Bundle-License"));
            addName(names, new Name("Bundle-ManifestVersion"));
            addName(names, new Name("Bundle-Name"));
            addName(names, new Name("Bundle-Vendor"));
            addName(names, new Name("Bundle-Version"));
            addName(names, new Name("Bundle-SymbolicName"));
            addName(names, new Name("Created-By"));
            addName(names, new Name("Export-Package"));
            addName(names, new Name("Import-Package"));
            addName(names, new Name("Name"));
            addName(names, new Name("SHA1-Digest"));
            addName(names, new Name("X-Compile-Source-JDK"));
            addName(names, new Name("X-Compile-Target-JDK"));
            KNOWN_NAMES = names;
        }
    }
}
