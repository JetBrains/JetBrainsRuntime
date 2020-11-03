/*
 * Copyright (c) 2020 SAP SE. All rights reserved.
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

package com.sun.jndi.ldap;

import java.util.Hashtable;

import javax.naming.NamingException;

/**
 * This interface is a helper for providing access to the service mechanism of
 * package {@code com.sun.jndi.ldap.spi} from module {@code jdk.naming.ldap} to
 * module {@link java.naming}.
 * {@code LdapDnsProviderServiceInternal} is known to both, module
 * {@link java.naming} and module {@code jdk.naming.ldap}. It is implemented by
 * class {@code com.sun.jndi.ldap.dns.LdapDnsProviderService} of
 * module {@code jdk.naming.ldap} and a singleton instance is registered in
 * module {@link java.naming}'s {@code com.sun.jndi.ldap.LdapCtxFactory} at class
 * initialization time.
 */
public interface LdapDnsProviderServiceInternal {

    /**
     * Equivalent of {@code com.sun.jndi.ldap.spi.LdapDnsProvider::lookupEndpoints}
     *
     * @throws NamingException if the {@code url} is not valid or an error
     *                         occurred while performing the lookup.
     */
    LdapDnsProviderResultInternal lookupEndpointsInternal(String url, Hashtable<?,?> env)
        throws NamingException;
}
