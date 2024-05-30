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
 * @build com.jetbrains.* com.jetbrains.test.api.Real com.jetbrains.test.jbr.Real
 * @run main/othervm -Djetbrains.runtime.api.extendRegistry=true RealTest
 */

import com.jetbrains.Extensions;
import com.jetbrains.internal.JBRApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jetbrains.Util.*;
import static com.jetbrains.test.api.Real.*;

public class RealTest {

    public static void main(String[] args) {
        init("RealTest", Map.of(Extensions.FOO, new Class[] {Proxy.class}, Extensions.BAR, new Class[] {Proxy.class}));

        // Get service
        Service service = Objects.requireNonNull(JBRApi.getService(Service.class));

        // Proxy passthrough
        Proxy p = Objects.requireNonNull(service.getProxy());
        Proxy np = Objects.requireNonNull(service.passthrough(p));
        if (p.getClass() != np.getClass()) {
            throw new Error("Different classes when passing through the same object");
        }

        // Client proxy passthrough
        ClientImpl c = new ClientImpl();
        ClientImpl nc = Objects.requireNonNull(service.passthrough(c));
        if (c.getClass() != nc.getClass()) {
            throw new Error("Different classes when passing through the same object");
        }

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
        requireNull(service.passthrough((Api2Way) null));

        if (service.sum(() -> 200, () -> 65).get() != 265) {
            throw new Error("Lazy numbers conversion error");
        }

        // Check extensions
        if (!JBRApi.isExtensionSupported(Extensions.FOO)) {
            throw new Error("FOO extension must be supported");
        }
        if (JBRApi.isExtensionSupported(Extensions.BAR)) {
            throw new Error("BAR extension must not be supported");
        }
        try {
            service.getProxy().foo();
            throw new Error("FOO extension was disabled but call succeeded");
        } catch (UnsupportedOperationException ignore) {}
        try {
            service.getProxy().bar();
            throw new Error("BAR extension was disabled but call succeeded");
        } catch (UnsupportedOperationException ignore) {}
        // foo() must succeed when enabled
        JBRApi.getService(Service.class, Extensions.FOO).getProxy().foo();
        // Asking for BAR must return null, as it is not supported
        requireNull(JBRApi.getService(Service.class, Extensions.FOO, Extensions.BAR));
        requireNull(JBRApi.getService(Service.class, Extensions.BAR));

        // Test specialized (implicit) List proxy
        List<Api2Way> list = Objects.requireNonNull(service.testList(null));
        Api2Way listItem = new TwoWayImpl();
        list.add(listItem);
        if (list.size() != 1) {
            throw new Error("Unexpected List size");
        }
        if (list.get(0) != listItem) {
            throw new Error("Unexpected List item");
        }
        service.testList(list);
        if (!list.isEmpty()) {
            throw new Error("Unexpected List size");
        }
        list = new ArrayList<>();
        if (list != service.testList(list)) {
            throw new Error("List passthrough mismatch");
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
