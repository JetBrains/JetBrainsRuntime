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
 * @summary Testing Classfile stack maps generator.
 * @bug 8305990
 * @build testdata.*
 * @run junit StackMapsTest
 */

import jdk.internal.classfile.*;
import jdk.internal.classfile.components.ClassPrinter;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static helpers.TestUtil.assertEmpty;
import static jdk.internal.classfile.Classfile.ACC_STATIC;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.lang.reflect.AccessFlag;

/**
 * StackMapsTest
 */
class StackMapsTest {

    private byte[] buildDeadCode() {
        return Classfile.build(
                ClassDesc.of("DeadCodePattern"),
                List.of(Classfile.Option.generateStackmap(false), Classfile.Option.patchDeadCode(false)),
                clb -> clb.withMethodBody(
                                "twoReturns",
                                MethodTypeDesc.of(ConstantDescs.CD_void),
                                0,
                                cob -> cob.return_().return_())
                        .withMethodBody(
                                "deadJumpInExceptionBlocks",
                                MethodTypeDesc.of(ConstantDescs.CD_void),
                                0,
                                cob -> {
                                    var deadEnd = cob.newLabel();
                                    cob.goto_(deadEnd);
                                    var deadStart = cob.newBoundLabel();
                                    cob.return_(); //dead code
                                    cob.labelBinding(deadEnd);
                                    cob.return_();
                                    var handler = cob.newBoundLabel();
                                    cob.athrow();
                                    //exception block before dead code to stay untouched
                                    cob.exceptionCatch(cob.startLabel(), deadStart, handler, ConstantDescs.CD_Throwable);
                                    //exception block after dead code to stay untouched
                                    cob.exceptionCatch(deadEnd, handler, handler, ConstantDescs.CD_Throwable);
                                     //exception block overlapping dead code to cut from right
                                    cob.exceptionCatch(cob.startLabel(), deadEnd, handler, ConstantDescs.CD_Throwable);
                                    //exception block overlapping dead code to from left
                                    cob.exceptionCatch(deadStart, handler, handler, ConstantDescs.CD_Throwable);
                                    //exception block matching dead code to remove
                                    cob.exceptionCatch(deadStart, deadEnd, handler, ConstantDescs.CD_Throwable);
                                    //exception block around dead code to split
                                    cob.exceptionCatch(cob.startLabel(), handler, handler, ConstantDescs.CD_Throwable);
                                }));
    }

    @Test
    void testDeadCodePatternPatch() throws Exception {
        testTransformedStackMaps(buildDeadCode());
    }

    @Test
    void testDeadCodePatternFail() throws Exception {
        var error = assertThrows(IllegalArgumentException.class, () -> testTransformedStackMaps(buildDeadCode(), Classfile.Option.patchDeadCode(false)));
        assertLinesMatch(
            """
            Unable to generate stack map frame for dead code at bytecode offset 1 of method twoReturns()
            >> more lines >>
                0: {opcode: RETURN}
                1: {opcode: RETURN}
            """.lines(),
            error.getMessage().lines(),
            error.getMessage()
        );
    }

    @Test
    void testUnresolvedPermission() throws Exception {
        testTransformedStackMaps("modules/java.base/java/security/UnresolvedPermission.class");
    }

    @Test
    void testURL() throws Exception {
        testTransformedStackMaps("modules/java.base/java/net/URL.class");
    }

    @Test
    void testPattern1() throws Exception {
        testTransformedStackMaps("/testdata/Pattern1.class");
    }

    @Test
    void testPattern2() throws Exception {
        testTransformedStackMaps("/testdata/Pattern2.class");
    }

    @Test
    void testPattern3() throws Exception {
        testTransformedStackMaps("/testdata/Pattern3.class");
    }

    @Test
    void testPattern4() throws Exception {
        testTransformedStackMaps("/testdata/Pattern4.class");
    }

    @Test
    void testPattern5() throws Exception {
        testTransformedStackMaps("/testdata/Pattern5.class");
    }

    @Test
    void testPattern6() throws Exception {
        testTransformedStackMaps("/testdata/Pattern6.class");
    }

    @Test
    void testPattern7() throws Exception {
        testTransformedStackMaps("/testdata/Pattern7.class");
    }

    @Test
    void testPattern8() throws Exception {
        testTransformedStackMaps("/testdata/Pattern8.class");
    }

    @Test
    void testPattern9() throws Exception {
        testTransformedStackMaps("/testdata/Pattern9.class");
    }

    @Test
    void testPattern10() throws Exception {
        testTransformedStackMaps("/testdata/Pattern10.class");
    }

    @Test
    void testFrameOutOfBytecodeRange() {
        var error = assertThrows(IllegalArgumentException.class, () ->
        Classfile.parse(
                Classfile.build(ClassDesc.of("TestClass"), clb ->
                        clb.withMethodBody("frameOutOfRangeMethod", MethodTypeDesc.of(ConstantDescs.CD_void), 0, cob -> {
                            var l = cob.newLabel();
                            cob.goto_(l);//jump to the end of method body triggers invalid frame creation
                            cob.labelBinding(l);
                        }))));
        assertLinesMatch(
            """
            Detected branch target out of bytecode range at bytecode offset 0 of method frameOutOfRangeMethod()
            >> more lines >>
                0: {opcode: GOTO, target: 3}
            """.lines(),
            error.getMessage().lines(),
            error.getMessage()
        );
    }

    @Test
    void testMethodSwitchFromStatic() {
        assertThrows(IllegalArgumentException.class, () ->
        Classfile.build(ClassDesc.of("TestClass"), clb ->
                clb.withMethod("testMethod", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int),
                               ACC_STATIC,
                               mb -> mb.withCode(cob -> {
                                           var t = cob.newLabel();
                                           cob.aload(0).goto_(t).labelBinding(t).areturn();
                                       })
                                       .withFlags())));
    }

    @Test
    void testMethodSwitchToStatic() {
        assertThrows(IllegalArgumentException.class, () ->
        Classfile.build(ClassDesc.of("TestClass"), clb ->
                clb.withMethod("testMethod", MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int),
                               0, mb ->
                                       mb.withCode(cob -> {
                                             var t = cob.newLabel();
                                             cob.iload(0).goto_(t).labelBinding(t).ireturn();
                                         })
                                         .withFlags(AccessFlag.STATIC))));
    }

    @Test
    void testClassVersions() throws Exception {
        var actualVersion = Classfile.parse(StackMapsTest.class.getResourceAsStream("/testdata/Pattern1.class").readAllBytes());

        //test transformation to class version 49 with removal of StackMapTable attributes
        var version49 = Classfile.parse(actualVersion.transform(ClassTransform.transformingMethodBodies(CodeTransform.ACCEPT_ALL)
                .andThen(ClassTransform.endHandler(clb -> clb.withVersion(49, 0)))));
        assertFalse(ClassPrinter.toTree(version49, ClassPrinter.Verbosity.CRITICAL_ATTRIBUTES).walk().anyMatch(n -> n.name().equals("stack map frames")));

        //test transformation to class version 50 with re-generation of StackMapTable attributes
         assertEmpty(Classfile.parse(version49.transform(ClassTransform.transformingMethodBodies(CodeTransform.ACCEPT_ALL)
                .andThen(ClassTransform.endHandler(clb -> clb.withVersion(50, 0))))).verify(null));
    }

    private static final FileSystem JRT = FileSystems.getFileSystem(URI.create("jrt:/"));

    private static void testTransformedStackMaps(String classPath, Classfile.Option... options) throws Exception {
        testTransformedStackMaps(
                classPath.startsWith("/")
                            ? StackMapsTest.class.getResourceAsStream(classPath).readAllBytes()
                            : Files.readAllBytes(JRT.getPath(classPath)),
                options);
    }

    private static void testTransformedStackMaps(byte[] originalBytes, Classfile.Option... options) throws Exception {
        //transform the class model
        var classModel = Classfile.parse(originalBytes, options);
        var transformedBytes = Classfile.build(classModel.thisClass().asSymbol(), List.of(options),
                                               cb -> {
//                                                   classModel.superclass().ifPresent(cb::withSuperclass);
//                                                   cb.withInterfaces(classModel.interfaces());
//                                                   cb.withVersion(classModel.majorVersion(), classModel.minorVersion());
                                                   classModel.forEachElement(cb);
                                               });

        //then verify transformed bytecode
        assertEmpty(Classfile.parse(transformedBytes).verify(null));
    }
}
