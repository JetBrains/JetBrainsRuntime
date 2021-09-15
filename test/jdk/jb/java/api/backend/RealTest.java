/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
 * @modules java.base/com.jetbrains.internal:+open
 * @build com.jetbrains.* com.jetbrains.api.Real com.jetbrains.jbr.Real
 * @run main RealTest
 */

import com.jetbrains.internal.JBRApi;

import java.util.Objects;

import static com.jetbrains.Util.*;
import static com.jetbrains.api.Real.*;
import static com.jetbrains.jbr.Real.*;

public class RealTest {

    public static void main(String[] args) {
        init()
                .service(Service.class.getName(), ServiceImpl.class.getName())
                .twoWayProxy(Api2Way.class.getName(), JBR2Way.class.getName())
                .twoWayProxy(ApiLazyNumber.class.getName(), JBRLazyNumber.class.getName());

        // Get service
        Service service = Objects.requireNonNull(JBRApi.getService(Service.class));

        // Test JBR-side proxy wrapping & unwrapping
        Api2Way stw = Objects.requireNonNull(service.get2Way());
        Api2Way nstw = Objects.requireNonNull(service.passthrough(stw));
        // stw and nstw are different proxy objects, because *real* object is on JBR-side
        if (stw.getClass() != nstw.getClass()) {
            throw new Error("Different classes when passing through the same object");
        }

        // Test client-side proxy wrapping & unwrapping
        TwoWayImpl tw = new TwoWayImpl();
        Api2Way ntw = service.passthrough(tw);
        if (tw != ntw) {
            throw new Error("Client pass through doesn't work, there are probably issues with extracting target object");
        }
        // Service must have set tw.value by calling accept()
        Objects.requireNonNull(tw.value);

        // Passing through null object -> null
        requireNull(service.passthrough(null));

        if (!service.isSelf(service)) {
            throw new Error("service.isSelf(service) == false");
        }

        if (service.sum(() -> 200, () -> 65).get() != 265) {
            throw new Error("Lazy numbers conversion error");
        }
    }

    private static class TwoWayImpl implements Api2Way {
        private Object value;
        @Override
        public void accept(Object o) {
            value = o;
        }
    }
}
