/*
 * Copyright (c) 2010, 2021, Oracle and/or its affiliates. All rights reserved.
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

package sun.font;

import java.io.*;
import java.util.*;

import sun.awt.*;
import sun.java2d.xr.*;

/**
 * Glyph cache used by the XRender pipeline.
 *
 * @author Clemens Eisserer
 */

public class XRGlyphCache implements GlyphDisposedListener {
    /**
     * BGRA glyphs are rendered as images
     * and therefore don't belong to any glyph set
     */
    public static final int BGRA_GLYPH_SET = -1;

    XRBackend con;
    XRCompositeManager maskBuffer;
    HashMap<MutableInteger, XRGlyphCacheEntry> cacheMap = new HashMap<MutableInteger, XRGlyphCacheEntry>(256);

    MutableInteger tmp = new MutableInteger(0);

    int grayGlyphSet;
    int lcdGlyphSet;
    final EnumMap<XRGlyphCacheEntry.Type, Integer> glyphSetsByType;

    int time = 0;
    int cachedPixels = 0;
    static final int MAX_CACHED_PIXELS = 100000;

    final GlyphIDAllocator glyphIDAllocator = new GlyphIDAllocator();

    static final boolean batchGlyphUpload = true; // Boolean.parseBoolean(System.getProperty("sun.java2d.xrender.batchGlyphUpload"));

    public XRGlyphCache(XRCompositeManager maskBuf) {
        this.con = maskBuf.getBackend();
        this.maskBuffer = maskBuf;

        grayGlyphSet = con.XRenderCreateGlyphSet(XRUtils.PictStandardA8);
        lcdGlyphSet = con.XRenderCreateGlyphSet(XRUtils.PictStandardARGB32);

        glyphSetsByType = new EnumMap<>(XRGlyphCacheEntry.Type.class);
        glyphSetsByType.put(XRGlyphCacheEntry.Type.GRAYSCALE, grayGlyphSet);
        glyphSetsByType.put(XRGlyphCacheEntry.Type.LCD, lcdGlyphSet);
        glyphSetsByType.put(XRGlyphCacheEntry.Type.BGRA, BGRA_GLYPH_SET);

        StrikeCache.addGlyphDisposedListener(this);
    }

    public void glyphDisposed(ArrayList<Long> glyphPtrList) {
        try {
            SunToolkit.awtLock();

            GrowableIntArray glyphIDList = new GrowableIntArray(1, glyphPtrList.size());
            for (long glyphPtr : glyphPtrList) {
                int glyphID = XRGlyphCacheEntry.getGlyphID(glyphPtr);

                //Check if glyph hasn't been freed already
                if (glyphID != 0) {
                   glyphIDList.addInt(glyphID);
                }
            }
            freeGlyphs(glyphIDList);
        } finally {
            SunToolkit.awtUnlock();
        }
    }

    protected XRGlyphCacheEntry getEntryForPointer(long imgPtr) {
        int id = XRGlyphCacheEntry.getGlyphID(imgPtr);

        if (id == 0) {
            return null;
        }

        tmp.setValue(id);
        return cacheMap.get(tmp);
    }

    public XRGlyphCacheEntry[] cacheGlyphs(GlyphList glyphList, int parentXid) {
        time++;

        XRGlyphCacheEntry[] entries = new XRGlyphCacheEntry[glyphList.getNumGlyphs()];
        long[] imgPtrs = glyphList.getImages();
        ArrayList<XRGlyphCacheEntry> uncachedGlyphs = null;

        for (int i = 0; i < glyphList.getNumGlyphs(); i++) {
            XRGlyphCacheEntry glyph;

            if (imgPtrs[i] == 0L ||
                imgPtrs[i] == StrikeCache.invisibleGlyphPtr) {
                continue;
            }
            // Find uncached glyphs and queue them for upload
            if ((glyph = getEntryForPointer(imgPtrs[i])) == null) {
                glyph = new XRGlyphCacheEntry(imgPtrs[i], glyphList);
                glyph.setGlyphID(glyphIDAllocator.allocateID(
                        glyph.getSubpixelResolutionX() *
                        glyph.getSubpixelResolutionY()));
                cacheMap.put(new MutableInteger(glyph.getGlyphID()), glyph);

                if (uncachedGlyphs == null) {
                    uncachedGlyphs = new ArrayList<>();
                }
                uncachedGlyphs.add(glyph);
            }
            glyph.setLastUsed(time);
            entries[i] = glyph;
        }

        // Add glyphs to cache
        if (uncachedGlyphs != null) {
            uploadGlyphs(entries, uncachedGlyphs, glyphList, parentXid);
        }

        return entries;
    }

    protected void uploadGlyphs(XRGlyphCacheEntry[] glyphs,
                                ArrayList<XRGlyphCacheEntry> uncachedGlyphs,
                                GlyphList gl, int parentXid) {
        for (XRGlyphCacheEntry glyph : uncachedGlyphs) {
            cachedPixels += glyph.getPixelCnt();
        }

        if (cachedPixels > MAX_CACHED_PIXELS) {
            clearCache(glyphs);
        }

        EnumMap<XRGlyphCacheEntry.Type, List<XRGlyphCacheEntry>>
                glyphListsByType = separateGlyphTypes(uncachedGlyphs);

        uploadGlyphs(grayGlyphSet, gl,
                     glyphListsByType.get(XRGlyphCacheEntry.Type.GRAYSCALE));
        uploadGlyphs(lcdGlyphSet, gl,
                     glyphListsByType.get(XRGlyphCacheEntry.Type.LCD));
        List<XRGlyphCacheEntry> bgraGlyphs = glyphListsByType.getOrDefault(
                XRGlyphCacheEntry.Type.BGRA, List.of());
        if (!bgraGlyphs.isEmpty()) {
            con.addBGRAGlyphImages(parentXid, bgraGlyphs);
        }
    }

    private void uploadGlyphs(int glyphSet, GlyphList glyphList,
                              List<XRGlyphCacheEntry> cacheEntries) {
        if (cacheEntries == null || cacheEntries.isEmpty()) {
            return;
        }
        /*
         * Some XServers crash when uploading multiple glyphs at once. TODO:
         * Implement build-switch in local case for distributors who know their
         * XServer is fixed
         */
        if (batchGlyphUpload) {
            con.XRenderAddGlyphs(glyphSet, glyphList, cacheEntries,
                    generateGlyphImageStream(cacheEntries));
        } else {
            ArrayList<XRGlyphCacheEntry> tmpList = new ArrayList<>(1);
            tmpList.add(null);
            for (XRGlyphCacheEntry entry : cacheEntries) {
                tmpList.set(0, entry);
                con.XRenderAddGlyphs(glyphSet, glyphList, tmpList,
                                     generateGlyphImageStream(tmpList));
            }
        }
    }

    /**
     * Separates bgra, lcd and grayscale glyphs queued for upload, and sets the
     * appropriate glyph set for the cache entries.
     */
    protected EnumMap<XRGlyphCacheEntry.Type, List<XRGlyphCacheEntry>>
            separateGlyphTypes(List<XRGlyphCacheEntry> glyphList) {
        EnumMap<XRGlyphCacheEntry.Type, List<XRGlyphCacheEntry>> glyphLists =
                new EnumMap<>(XRGlyphCacheEntry.Type.class);
        for (XRGlyphCacheEntry cacheEntry : glyphList) {
            XRGlyphCacheEntry.Type cacheEntryType = cacheEntry.getType();
            cacheEntry.setGlyphSet(glyphSetsByType.get(cacheEntryType));
            glyphLists.computeIfAbsent(cacheEntryType, ignore ->
                    new ArrayList<>(glyphList.size())).add(cacheEntry);
        }
        return glyphLists;
    }

    /**
     * Copies the glyph-images into a continuous buffer, required for uploading.
     */
    protected byte[] generateGlyphImageStream(List<XRGlyphCacheEntry> glyphList) {
        boolean isLCDGlyph = glyphList.get(0).getGlyphSet() == lcdGlyphSet;

        ByteArrayOutputStream stream = new ByteArrayOutputStream((isLCDGlyph ? 4 : 1) * 48 * glyphList.size());
        for (XRGlyphCacheEntry cacheEntry : glyphList) {
            cacheEntry.writePixelData(stream, isLCDGlyph);
        }

        return stream.toByteArray();
    }

    protected void clearCache(XRGlyphCacheEntry[] glyphs) {
        /*
         * Glyph uploading is so slow anyway, we can afford some inefficiency
         * here, as the cache should usually be quite small. TODO: Implement
         * something not that stupid ;)
         */
        ArrayList<XRGlyphCacheEntry> cacheList = new ArrayList<XRGlyphCacheEntry>(cacheMap.values());
        cacheList.sort(new Comparator<XRGlyphCacheEntry>() {
            public int compare(XRGlyphCacheEntry e1, XRGlyphCacheEntry e2) {
                return e2.getLastUsed() - e1.getLastUsed();
            }
        });

        for (XRGlyphCacheEntry glyph : glyphs) {
            if (glyph != null) {
                glyph.setPinned();
            }
        }

        GrowableIntArray deleteGlyphList = new GrowableIntArray(1, 10);
        int pixelsToRelease = cachedPixels - MAX_CACHED_PIXELS;

        for (int i = cacheList.size() - 1; i >= 0 && pixelsToRelease > 0; i--) {
            XRGlyphCacheEntry entry = cacheList.get(i);

            if (!entry.isPinned()) {
                pixelsToRelease -= entry.getPixelCnt();
                deleteGlyphList.addInt(entry.getGlyphID());
            }
        }

        for (XRGlyphCacheEntry glyph : glyphs) {
            if (glyph != null) {
                glyph.setUnpinned();
            }
        }

        freeGlyphs(deleteGlyphList);
    }

    private void freeGlyphs(GrowableIntArray glyphIdList) {
        GrowableIntArray removedLCDGlyphs = new GrowableIntArray(1, 10);
        GrowableIntArray removedGrayscaleGlyphs = new GrowableIntArray(1, 10);
        long[] removedBGRAGlyphPtrs = null;
        int removedBGRAGlyphPtrsCount = 0;

        for (int i=0; i < glyphIdList.getSize(); i++) {
            int glyphId = glyphIdList.getInt(i);

            tmp.setValue(glyphId);
            XRGlyphCacheEntry entry = cacheMap.get(tmp);
            int subglyphs = entry.getSubpixelResolutionX() * entry.getSubpixelResolutionY();
            glyphIDAllocator.freeID(glyphId, subglyphs);
            cachedPixels -= entry.getPixelCnt();
            cacheMap.remove(tmp);

            if (entry.getGlyphSet() == grayGlyphSet) {
                for (int j = 0; j < subglyphs; j++) {
                    removedGrayscaleGlyphs.addInt(glyphId + j);
                }
            } else if (entry.getGlyphSet() == lcdGlyphSet) {
                removedLCDGlyphs.addInt(glyphId);
            } else if (entry.getGlyphSet() == BGRA_GLYPH_SET) {
                if (removedBGRAGlyphPtrs == null) {
                    removedBGRAGlyphPtrs = new long[10];
                } else if (removedBGRAGlyphPtrsCount >= removedBGRAGlyphPtrs.length) {
                    long[] n = new long[removedBGRAGlyphPtrs.length * 2];
                    System.arraycopy(removedBGRAGlyphPtrs, 0, n, 0,
                                     removedBGRAGlyphPtrs.length);
                    removedBGRAGlyphPtrs = n;
                }
                removedBGRAGlyphPtrs[removedBGRAGlyphPtrsCount++] =
                        entry.getBgraGlyphInfoPtr();
            }

            entry.setGlyphID(0);
        }

        if (removedGrayscaleGlyphs.getSize() > 0) {
            con.XRenderFreeGlyphs(grayGlyphSet, removedGrayscaleGlyphs.getSizedArray());
        }

        if (removedLCDGlyphs.getSize() > 0) {
            con.XRenderFreeGlyphs(lcdGlyphSet, removedLCDGlyphs.getSizedArray());
        }

        if (removedBGRAGlyphPtrsCount > 0) {
            con.freeBGRAGlyphImages(removedBGRAGlyphPtrs,
                                    removedBGRAGlyphPtrsCount);
        }
    }

    /**
     * Each XR glyph image needs its own ID, which are allocated using
     * this class. When using supplementary subpixel glyphs
     * ({@code -Djava2d.font.subpixelResolution=4x2}), its XRGlyphCacheEntry
     * may correspond to few images and therefore may need a range of IDs
     * instead of a single one.
     * @implNote This allocator uses a simple strategy for reusing IDs with
     * a single pool per capacity (e.g. one pool containing only individual
     * IDs and second pool containing ranges each with 8 sequential IDs).
     * When pool is empty, new range of IDs is allocated by incrementing
     * {@code nextID} counter.
     */
    private static class GlyphIDAllocator {

        @SuppressWarnings({"unchecked", "rawtypes"})
        private List<Integer>[] freeIDsByCapacity = new List[]{
                new ArrayList<>(255)
        };
        private int nextID = 1;

        private int allocateID(int count) {
            if (count <= freeIDsByCapacity.length) {
                List<Integer> pool = freeIDsByCapacity[count - 1];
                if (pool != null && !pool.isEmpty()) {
                    return pool.remove(pool.size() - 1);
                }
            }
            int id = nextID;
            nextID += count;
            return id;
        }

        private void freeID(int id, int count) {
            if (count > freeIDsByCapacity.length) {
                freeIDsByCapacity = Arrays.copyOf(freeIDsByCapacity, count);
            }
            if (freeIDsByCapacity[count - 1] == null) {
                freeIDsByCapacity[count - 1] = new ArrayList<>(255);
            }
            freeIDsByCapacity[count - 1].add(id);
        }
    }
}
