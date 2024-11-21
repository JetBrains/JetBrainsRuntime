/*
 * Copyright (c) 2022, 2024, Oracle and/or its affiliates. All rights reserved.
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
import java.lang.classfile.Label;
import java.lang.classfile.PseudoInstruction;
import java.lang.classfile.attribute.LocalVariableTableAttribute;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;

import jdk.internal.classfile.impl.AbstractPseudoInstruction;
import jdk.internal.classfile.impl.BoundLocalVariable;
import jdk.internal.classfile.impl.TemporaryConstantPool;
import jdk.internal.classfile.impl.Util;
import jdk.internal.javac.PreviewFeature;

/**
 * A pseudo-instruction which models a single entry in the
 * {@link LocalVariableTableAttribute}.  Delivered as a {@link CodeElement}
 * during traversal of the elements of a {@link CodeModel}, according to
 * the setting of the {@link ClassFile.DebugElementsOption} option.
 *
 * @see PseudoInstruction
 *
 * @since 22
 */
@PreviewFeature(feature = PreviewFeature.Feature.CLASSFILE_API)
public sealed interface LocalVariable extends PseudoInstruction
        permits AbstractPseudoInstruction.UnboundLocalVariable, BoundLocalVariable {
    /**
     * {@return the local variable slot}
     */
    int slot();

    /**
     * {@return the local variable name}
     */
    Utf8Entry name();

    /**
     * {@return the local variable field descriptor}
     */
    Utf8Entry type();

    /**
     * {@return the local variable type, as a symbolic descriptor}
     */
    default ClassDesc typeSymbol() {
        return Util.fieldTypeSymbol(type());
    }

    /**
     * {@return the start range of the local variable scope}
     */
    Label startScope();

    /**
     * {@return the end range of the local variable scope}
     */
    Label endScope();

    /**
     * {@return a local variable pseudo-instruction}
     *
     * @param slot the local variable slot
     * @param nameEntry the local variable name
     * @param descriptorEntry the local variable descriptor
     * @param startScope the start range of the local variable scope
     * @param endScope the end range of the local variable scope
     * @throws IllegalArgumentException if {@code slot} is out of range
     */
    static LocalVariable of(int slot, Utf8Entry nameEntry, Utf8Entry descriptorEntry, Label startScope, Label endScope) {
        return new AbstractPseudoInstruction.UnboundLocalVariable(slot, nameEntry, descriptorEntry,
                                                                  startScope, endScope);
    }

    /**
     * {@return a local variable pseudo-instruction}
     *
     * @param slot the local variable slot
     * @param name the local variable name
     * @param descriptor the local variable descriptor
     * @param startScope the start range of the local variable scope
     * @param endScope the end range of the local variable scope
     * @throws IllegalArgumentException if {@code slot} is out of range
     */
    static LocalVariable of(int slot, String name, ClassDesc descriptor, Label startScope, Label endScope) {
        return of(slot,
                  TemporaryConstantPool.INSTANCE.utf8Entry(name),
                  TemporaryConstantPool.INSTANCE.utf8Entry(descriptor),
                  startScope, endScope);
    }
}
