/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
