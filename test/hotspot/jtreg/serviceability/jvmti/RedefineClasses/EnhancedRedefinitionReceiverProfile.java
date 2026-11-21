// SPDX-License-Identifier: GPL-2.0-only

/*
 * @test
 * @summary C2 must not use obsolete receiver profiles after enhanced class redefinition
 * @requires vm.jvmti & vm.compiler2.enabled
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.instrument
 *          jdk.jartool/sun.tools.jar
 * @run main RedefineClassHelper
 * @run main/othervm/timeout=180 -javaagent:redefineagent.jar
 *      -XX:+AllowEnhancedClassRedefinition
 *      -XX:CompileCommand=dontinline,EnhancedRedefinitionReceiverProfile::call
 *      EnhancedRedefinitionReceiverProfile
 */

import java.io.InputStream;
import java.lang.instrument.ClassDefinition;

abstract class ProfileBase {
    int value() {
        return number() + 1;
    }

    abstract int number();
}

class ProfileMiddle extends ProfileBase {
    int number() {
        return 2;
    }
}

class ProfileLeaf extends ProfileMiddle {
}

class ProfileOther extends ProfileBase {
    int value() {
        return 5;
    }

    int number() {
        return 3;
    }
}

public class EnhancedRedefinitionReceiverProfile {
    static int call(ProfileBase receiver) {
        return receiver.value();
    }

    private static void warm(ProfileBase receiver) {
        int sum = 0;
        for (int i = 0; i < 200_000; i++) {
            sum += call(receiver);
        }
        if (sum != 600_000) {
            throw new AssertionError("incorrect result: " + sum);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes;
        try (InputStream input = ProfileMiddle.class.getResourceAsStream("ProfileMiddle.class")) {
            if (input == null) {
                throw new AssertionError("missing ProfileMiddle.class");
            }
            bytes = input.readAllBytes();
        }
        // Make another implementation available so C2 uses the receiver profile.
        new ProfileOther();
        ProfileBase receiver = new ProfileLeaf();
        warm(receiver);
        for (int i = 0; i < 20; i++) {
            RedefineClassHelper.instrumentation.redefineClasses(
                    new ClassDefinition(ProfileMiddle.class, bytes));
            warm(receiver);
        }
    }
}
