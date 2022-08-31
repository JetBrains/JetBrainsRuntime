/*
 * Copyright (c) 1999, 2022, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.loops;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RenderCache {

    private final int MAX_ENTRIES;
    private final Map<Key, Object> mruCache;

    record Key(SurfaceType src, CompositeType comp, SurfaceType dst) {
        @Override
        public boolean equals(Object o) {
            if (o instanceof Key other) {
                // bug 4725045: using equals() causes different SurfaceType
                // objects with the same strings to match in the cache, which is
                // not the behavior we want. Constrain the match to succeed only
                // on object matches instead.
                return ((this.src  == other.src) &&
                        (this.comp == other.comp) &&
                        (this.dst  == other.dst));
            }
            return false;
        }
    }

    public RenderCache(int size) {
        MAX_ENTRIES = size;
        mruCache = new LinkedHashMap<>(size + 1) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Object> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }

    public synchronized Object get(SurfaceType src,
                      CompositeType comp,
                      SurfaceType dst)
    {
        return mruCache.get(new Key(src, comp, dst));
    }

    public synchronized void put(SurfaceType src,
                    CompositeType comp,
                    SurfaceType dst,
                    Object value)
    {
        mruCache.put(new Key(src, comp, dst), value);
    }
}
