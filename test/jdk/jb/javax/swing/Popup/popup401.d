/*
 * Copyright 2000-2017 JetBrains s.r.o.
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

int c;
int a[long long];

BEGIN {
    c=0;
}

objc$target:AWTWindow_Panel:-initWithDelegate*:entry {
    c++;
    a[arg0] = 1;
}

objc$target:NSObject:-dealloc:entry {
    c -= a[arg0];
    a[arg0] = 0;
}

END /c > 0/ {
    printf("AWTWindow_Panel leaks: %d", c);
    exit(9);
}

END /c == 0/ {
    printf("AWTWindow_Panel leaks: %d", c);
    exit(0);
}