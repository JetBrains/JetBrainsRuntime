// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComponentAccessibility.h"

@interface JavaTabGroupAccessibility : JavaComponentAccessibility {
    NSInteger _numTabs;
}

- (id)currentTabWithEnv:(JNIEnv *)env withAxContext:(jobject)axContext;
- (NSArray *)tabButtonsWithEnv:(JNIEnv *)env withTabGroupAxContext:(jobject)axContext withTabCode:(NSInteger)whichTabs allowIgnored:(BOOL)allowIgnored;
- (NSArray *)contentsWithEnv:(JNIEnv *)env withTabGroupAxContext:(jobject)axContext withTabCode:(NSInteger)whichTabs allowIgnored:(BOOL)allowIgnored;

@property(readonly) NSInteger numTabs;

@end
