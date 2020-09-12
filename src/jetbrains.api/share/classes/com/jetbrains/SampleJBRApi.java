package com.jetbrains;

// SampleJBRApi is a base of our feature API, it's just a marker interface extending JBRService
public interface SampleJBRApi extends JBRService {


    // And actual API goes from V1
    interface V1 extends SampleJBRApi {

        void someMethod1(SomeDataClass arg);

        class SomeDataClass {
            public SomeDataClass() {}
        }

    }


    // When we need to add something to our API, we create interface for a new version, extending previous one
    interface V2 extends V1 {

        SomeDataClass someMethod2();

    }


}
