/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package java.lang.classfile.instruction;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.PseudoInstruction;
import java.lang.classfile.attribute.LineNumberTableAttribute;

import jdk.internal.classfile.impl.LineNumberImpl;
import jdk.internal.javac.PreviewFeature;

/**
 * A pseudo-instruction which models a single entry in the
 * {@link LineNumberTableAttribute}.  Delivered as a {@link CodeElement}
 * during traversal of the elements of a {@link CodeModel}, according to
 * the setting of the {@link ClassFile.LineNumbersOption} option.
 *
 * @see PseudoInstruction
 *
 * @since 22
 */
@PreviewFeature(feature = PreviewFeature.Feature.CLASSFILE_API)
public sealed interface LineNumber extends PseudoInstruction
        permits LineNumberImpl {

    /**
     * {@return the line number}
     */
    int line();

    /**
     * {@return a line number pseudo-instruction}
     *
     * @param line the line number
     */
    static LineNumber of(int line) {
        return LineNumberImpl.of(line);
    }
}
