/*
 * @test /nodynamiccopyright/
 * @bug 8236210
 * @summary Annotations on var
 * @compile/fail/ref=T8236210.out -XDrawDiagnostics T8236210.java
 */

import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.TYPE_USE;

import java.lang.annotation.Target;

@Target({TYPE_USE, LOCAL_VARIABLE})
@interface Both {}

@Target(LOCAL_VARIABLE)
@interface Decl {}

@Target(TYPE_USE)
@interface Type {}

class T8236210 {
    {
        @Both var a = 1;
        @Decl var b = 2;
        @Type var c = 3;
    }
}
