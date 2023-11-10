/*
 * Copyright 2023 JetBrains s.r.o.
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
package sun.awt.wl;

import sun.awt.datatransfer.DataTransferer;
import sun.awt.datatransfer.ToolkitThreadBlockedHandler;
import sun.datatransfer.DataFlavorUtil;
import sun.util.logging.PlatformLogger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

/**
 * Facilitates data conversion between formats for the use with the clipboard.
 * Some terminology:
 * "native" format - the format understood by Wayland; MIME with some deviations
 * "long" format - an arbintrary number assigned to some native format;
 *                 once established, this mapping never changes
 */
public class WLDataTransferer extends DataTransferer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLDataTransferer");

    private static ImageTypeSpecifier defaultImageSpec = null;

    // Maps the "native" format (MIME) to its numeric ID
    private final Map<String, Long> nameToLong = new HashMap<>();

    // Maps the numeric ID of a format to its native (MIME) representation
    private final Map<Long, String> longToName = new HashMap<>();

    private final Map<Long, Boolean> imageFormats = new HashMap<>();
    private final Map<Long, Boolean> textFormats = new HashMap<>();

    private static class HOLDER {
        static WLDataTransferer instance = new WLDataTransferer();
    }

    static WLDataTransferer getInstanceImpl() {
        return HOLDER.instance;
    }

    @Override
    public String getDefaultUnicodeEncoding() {
        return "UTF-8";
    }

    @Override
    public boolean isLocaleDependentTextFormat(long format) {
        return false;
    }

    @Override
    public boolean isFileFormat(long format) {
        String nat = getNativeForFormat(format);
        return "FILE_NAME".equals(nat);
    }

    @Override
    public boolean isImageFormat(long format) {
        synchronized (this) {
            return imageFormats.computeIfAbsent(format, f -> isMimeFormat(f, "image"));
        }
    }

    @Override
    public boolean isTextFormat(long format) {
        synchronized (this) {
            return textFormats.computeIfAbsent(
                    format,
                    f -> super.isTextFormat(format)
                            || isMimeFormat(format, "text"));
        }
    }

    /**
     * @return true if the given format ID corresponds to a MIME format
     *         with the given primary type
     */
    private boolean isMimeFormat(long format, String primaryType) {
        String nat = getNativeForFormat(format);
        if (nat != null) {
            try {
                DataFlavor df = new DataFlavor(nat);
                if (primaryType.equals(df.getPrimaryType())) {
                    return true;
                }
            } catch (Exception ignored) { /* Not MIME */ }
        }
        return false;
    }

    @Override
    protected Long getFormatForNativeAsLong(String formatName) {
        Objects.requireNonNull(formatName);
        synchronized (this) {
            Long thisID = nameToLong.get(formatName);
            if (thisID == null) {
                // Some apps request data in a format that only differs from
                // the advertised in the case of some of the letters.
                // IMO we can ignore the case and find an equivalent.
                var matchingKey = nameToLong.keySet().stream()
                        .filter(formatName::equalsIgnoreCase).findAny()
                        .orElse(null);
                if (matchingKey != null) {
                    thisID = nameToLong.get(matchingKey);
                } else {
                    long nextID = nameToLong.size();
                    thisID = nextID;
                    longToName.put(thisID, formatName);
                }
                nameToLong.put(formatName, thisID);
            }

            return thisID;
        }
    }

    @Override
    protected String getNativeForFormat(long format) {
        synchronized (this) {
            return longToName.get(format);
        }
    }

    @Override
    protected ByteArrayOutputStream convertFileListToBytes(ArrayList<String> fileList) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (int i = 0; i < fileList.size(); i++) {
            byte[] bytes = fileList.get(i).getBytes();
            if (i != 0) bos.write(0);
            bos.write(bytes, 0, bytes.length);
        }
        return bos;
    }

    @Override
    protected String[] dragQueryFile(byte[] bytes) {
        // TODO
        if (log.isLoggable(PlatformLogger.Level.INFO)) {
            log.info("Unimplemented");
        }
        return new String[0];
    }

    @Override
    protected Image platformImageBytesToImage(byte[] bytes, long format) throws IOException {
        DataFlavor df = getImageDataFlavorForFormat(format);
        final String baseType = df.getPrimaryType() + "/" + df.getSubType();
        Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(baseType);
        BufferedImage bi = null;
        if (readers.hasNext()) {
            ImageReader reader = readers.next();
            ImageReadParam param = reader.getDefaultReadParam();
            ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
            ImageInputStream stream = ImageIO.createImageInputStream(byteStream);
            reader.setInput(stream, true, true);
            try (stream) {
                bi = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }

        return bi;
    }

    @Override
    protected byte[] imageToPlatformBytes(Image image, long format) throws IOException {
        int width = image.getWidth(null);
        int height = image.getHeight(null);
        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = bufferedImage.createGraphics();
        g2d.drawImage(image, 0, 0, null);
        g2d.dispose();

        int numBytes = (int)(width * height * bufferedImage.getColorModel().getPixelSize() / 8.0);
        ByteArrayOutputStream out = new ByteArrayOutputStream(numBytes);
        DataFlavor df = getImageDataFlavorForFormat(format);
        ImageIO.write(bufferedImage, df.getSubType(), out);

        return out.toByteArray();
    }

    private DataFlavor getImageDataFlavorForFormat(long format) {
        String nat = getNativeForFormat(format);
        DataFlavor df = null;
        try {
            df = new DataFlavor(nat);
        } catch (Exception ignored) { }

        if (df == null) {
            throw new InternalError("Native image format " + nat + " corresponding to ID "
                    + format + " not recognized as DataFlavor");
        }
        return df;
    }

    @Override
    public ToolkitThreadBlockedHandler getToolkitThreadBlockedHandler() {
        return WLToolkitThreadBlockedHandler.getToolkitThreadBlockedHandler();
    }

    @Override
    public LinkedHashSet<DataFlavor> getPlatformMappingsForNative(String nat) {
        // TODO: much of this has been taken verbatim from XDataTransferer.
        //  Worth refactoring the common stuff out?
        LinkedHashSet<DataFlavor> flavors = new LinkedHashSet<>();

        if (nat == null) {
            return flavors;
        }

        DataFlavor df;
        try {
            df = new DataFlavor(nat);
        } catch (Exception e) {
            // The string doesn't constitute a valid MIME type.
            return flavors;
        }

        DataFlavor value = df;
        final String primaryType = df.getPrimaryType();

        // For text formats we map natives to MIME strings instead of data
        // flavors to enable dynamic text native-to-flavor mapping generation.
        // See SystemFlavorMap.getFlavorsForNative() for details.
        if ("image".equals(primaryType)) {
            final String baseType = primaryType + "/" + df.getSubType();
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(baseType);
            if (readers.hasNext()) {
                flavors.add(DataFlavor.imageFlavor);
            }
        }

        flavors.add(value);

        return flavors;
    }

    private ImageTypeSpecifier getDefaultImageTypeSpecifier() {
        if (defaultImageSpec == null) {
            ColorModel model = ColorModel.getRGBdefault();
            WritableRaster raster =
                    model.createCompatibleWritableRaster(10, 10);

            BufferedImage bufferedImage =
                    new BufferedImage(model, raster, model.isAlphaPremultiplied(),
                            null);

            defaultImageSpec = new ImageTypeSpecifier(bufferedImage);
        }

        return defaultImageSpec;
    }

    @Override
    public LinkedHashSet<String> getPlatformMappingsForFlavor(DataFlavor df) {
        LinkedHashSet<String> natives = new LinkedHashSet<>(1);

        if (df == null) {
            return natives;
        }

        String charset = df.getParameter("charset");
        String baseType = df.getPrimaryType() + "/" + df.getSubType();
        String mimeType = baseType;

        if (charset != null && DataFlavorUtil.isFlavorCharsetTextType(df)) {
            mimeType += ";charset=" + charset;
        }

        // Add a mapping to the MIME native whenever the representation class
        // doesn't require translation.
        if (df.getRepresentationClass() != null &&
                (df.isRepresentationClassInputStream() ||
                        df.isRepresentationClassByteBuffer() ||
                        byte[].class.equals(df.getRepresentationClass()))) {
            natives.add(mimeType);
        }

        if (DataFlavor.imageFlavor.equals(df)) {
            String[] mimeTypes = ImageIO.getWriterMIMETypes();
            if (mimeTypes != null) {
                for (String mime : mimeTypes) {
                    Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(mime);
                    while (writers.hasNext()) {
                        ImageWriter imageWriter = writers.next();
                        ImageWriterSpi writerSpi = imageWriter.getOriginatingProvider();

                        if (writerSpi != null &&
                                writerSpi.canEncodeImage(getDefaultImageTypeSpecifier())) {
                            natives.add(mime);
                            break;
                        }
                    }
                }
            }
        } else if (DataFlavorUtil.isFlavorCharsetTextType(df)) {
            // stringFlavor is semantically equivalent to the standard
            // "text/plain" MIME type.
            if (DataFlavor.stringFlavor.equals(df)) {
                baseType = "text/plain";
            }

            for (String encoding : DataFlavorUtil.standardEncodings()) {
                if (!encoding.equals(charset)) {
                    natives.add(baseType + ";charset=" + encoding);
                }
            }

            // Add a MIME format without specified charset.
            natives.add(baseType);
        }

        return natives;
    }
}