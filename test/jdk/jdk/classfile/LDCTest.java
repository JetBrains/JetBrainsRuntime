/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 * @test
 * @summary Testing Classfile LDC instructions.
 * @run junit LDCTest
 */
import java.lang.constant.ClassDesc;
import static java.lang.constant.ConstantDescs.*;
import java.lang.constant.MethodTypeDesc;

import jdk.internal.classfile.*;
import jdk.internal.classfile.constantpool.ConstantPoolBuilder;
import jdk.internal.classfile.constantpool.StringEntry;
import java.lang.reflect.AccessFlag;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import static helpers.TestConstants.MTD_VOID;
import static jdk.internal.classfile.Opcode.*;
import static jdk.internal.classfile.TypeKind.VoidType;
import jdk.internal.classfile.instruction.ConstantInstruction;

class LDCTest {
    @Test
    void testLDCisConvertedToLDCW() throws Exception {
        byte[] bytes = Classfile.build(ClassDesc.of("MyClass"), cb -> {
            cb.withFlags(AccessFlag.PUBLIC);
            cb.withVersion(52, 0);
            cb.withMethod("<init>", MethodTypeDesc.of(CD_void), 0, mb -> mb
                      .withCode(codeb -> codeb.loadInstruction(TypeKind.ReferenceType, 0)
                                              .invokeInstruction(INVOKESPECIAL, CD_Object, "<init>", MTD_VOID, false)
                                              .returnInstruction(VoidType)
                      )
              )

              .withMethod("main", MethodTypeDesc.of(CD_void, CD_String.arrayType()),
                          AccessFlags.ofMethod(AccessFlag.PUBLIC, AccessFlag.STATIC).flagsMask(),
                          mb -> mb.withCode(c0 -> {
                                  ConstantPoolBuilder cpb = cb.constantPool();
                                  for (int i = 0; i <= 256/2 + 2; i++) { // two entries per String
                                      StringEntry s = cpb.stringEntry("string" + i);
                                  }
                                  c0.constantInstruction(LDC, "string0")
                                    .constantInstruction(LDC, "string131")
                                    .constantInstruction(LDC, "string50")
                                    .constantInstruction(-0.0f)
                                    .constantInstruction(-0.0d)
                                    //non-LDC test cases
                                    .constantInstruction(0.0f)
                                    .constantInstruction(0.0d)
                                    .returnInstruction(VoidType);
                              }));
        });

        var model = Classfile.parse(bytes);
        var code = model.elementStream()
                .filter(e -> e instanceof MethodModel)
                .map(e -> (MethodModel) e)
                .filter(e -> e.methodName().stringValue().equals("main"))
                .flatMap(MethodModel::elementStream)
                .filter(e -> e instanceof CodeModel)
                .map(e -> (CodeModel) e)
                .findFirst()
                .orElseThrow();
        var opcodes = code.elementList().stream()
                          .filter(e -> e instanceof Instruction)
                          .map(e -> (Instruction)e)
                          .toList();

        assertEquals(opcodes.size(), 8);
        assertEquals(opcodes.get(0).opcode(), LDC);
        assertEquals(opcodes.get(1).opcode(), LDC_W);
        assertEquals(opcodes.get(2).opcode(), LDC);
        assertEquals(
                Float.floatToRawIntBits((float)((ConstantInstruction)opcodes.get(3)).constantValue()),
                Float.floatToRawIntBits(-0.0f));
        assertEquals(
                Double.doubleToRawLongBits((double)((ConstantInstruction)opcodes.get(4)).constantValue()),
                Double.doubleToRawLongBits(-0.0d));
        assertEquals(opcodes.get(5).opcode(), FCONST_0);
        assertEquals(opcodes.get(6).opcode(), DCONST_0);
        assertEquals(opcodes.get(7).opcode(), RETURN);
    }

    // TODO test for explicit LDC_W?
}