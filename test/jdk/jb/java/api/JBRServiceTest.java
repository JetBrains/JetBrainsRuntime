/*
 * Copyright 2000-2020 JetBrains s.r.o.
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

import com.jetbrains.JBRService;
import com.jetbrains.SampleJBRApi;

import java.util.Objects;

/*
 * @test
 * @summary JBR API service loading & usage example
 */

public class JBRServiceTest {


    /**
     * All API members are organized into some kind of 'versioned namespaces', here's the example:
     * <ul>
     *     <li>SampleJBRApi - root of our feature API.</li>
     *     <li>SampleJBRApi.V1 - first version of our API. It contains all relevant methods, classes, etc.</li>
     *     <li>SampleJBRApi.V1.SomeDataClass - data class, added by first version of API.</li>
     *     <li>SampleJBRApi.V2 - second version of our API. It extends V1, adding some more things to API.</li>
     * </ul>
     *
     * Actual interface hierarchy for our API looks like this:
     * <ul>
     *     <li>JBRService: marker interface, root for every feature API</li>
     *     <ul>
     *         <li>SampleJBRApi: marker interface, root for that specific feature API</li>
     *         <ul>
     *             <li>SampleJBRApi.V1: first version of feature API</li>
     *             <ul>
     *                 <li>SampleJBRApi.V2: second version of feature API</li>
     *             </ul>
     *         </ul>
     *     </ul>
     * </ul>
     *
     * General rule when using JBR API is to avoid loading classes unless you make sure they're available at runtime.
     * To check for service availability you can try loading it with {@link JBRService#load}.
     * Also avoid using 'instanceof' to test JBR service implementation against specific interface, because using
     * it in 'instanceof' statement will cause loading this interface, which may in turn cause NoClassDefFoundError.
     */
    public static void main(String[] args) {
        // Declaring variables is safe
        SampleJBRApi.V1 service;
        // We pass lambda instead of plain class. This allows JBRService#load to deal with NoClassDefFoundErrors
        service = JBRService.load(() -> SampleJBRApi.V1.class);
        // Null-checking variables is safe too
        Objects.requireNonNull(service);
        // When we ensured that SampleJBRApi.V1 is available, we can use anything that's inside
        service.someMethod1(null);
        // We can also create SampleJBRApi.V1.SomeDataClass instances, as we know they're supported with V1
        service.someMethod1(new SampleJBRApi.V1.SomeDataClass());

        // But don't try doing 'service instanceof SampleJBRApi.V2' as it may cause NoClassDefFoundErrors!
        SampleJBRApi.V2 service2 = Objects.requireNonNull(JBRService.load(() -> SampleJBRApi.V2.class));
        // Versioned service interfaces are inherited, so V2 gives you access to both V2 and V1 API
        service2.someMethod1(service2.someMethod2());
    }


}
