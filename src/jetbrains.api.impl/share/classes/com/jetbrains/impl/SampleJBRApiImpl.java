package com.jetbrains.impl;

import com.jetbrains.SampleJBRApi;

// Here we just implement latest API version
public class SampleJBRApiImpl implements SampleJBRApi.V2 {

    @Override
    public void someMethod1(SomeDataClass arg) {

    }

    @Override
    public SomeDataClass someMethod2() {
        return new SomeDataClass();
    }

}
