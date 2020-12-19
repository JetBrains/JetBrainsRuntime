###Tips for compiling test libraries and preparing env for manual test running


## Linux
```shell script
# step into the test directory
cd <JetBrainsRuntime>/test/jdk/jb/java/awt/event/TouchScreenEvent

# complile native lib
gcc -shared -fPIC -I<path_to_jbr>/include/linux -I<path_to_jbr>/include touchscreen_device.c -o libtouchscreen_device.so

# step into the runtime workspace directory
cd <JetBrainsRuntime>

# run the test with jtreg
jtreg -v -testjdk:<path_to_jbr> -e:BUPWD=<SUDO_PWD> -nativepath:'<JetBrainsRuntime>/test/jdk/jb/java/awt/event/TouchScreenEvent' test/jdk/jb/java/awt/event/TouchScreenEvent/TouchScreenEventsTestLinux.sh

```

SUDO_PWD above is the sudo password which is needed to allow current user to work with /dev/uinput: please see [group permissions](https://github.com/tuomasjjrasanen/python-uinput/issues/6#issuecomment-538710069) link.

## Windows
```shell script
# run cmd and call vcvarsall.bat with the target arch argument
"<path_to_vcvarsall>\vcvarsall.bat" amd64

# step into the test directory
cd <JetBrainsRuntime>\test\jdk\jb\java\awt\event\TouchScreenEvent

# complile native lib
cl -I<path_to_jbr>\include\win32 -I<path_to_jbr>\include -MD -LD windows_touch_robot.c "<path_to_user32lib>\user32.lib" -Fewindows_touch_robot.dll

# step into the runtime workspace directory
cd <JetBrainsRuntime>

# run bash
bash

# run the test with jtreg
jtreg -v -testjdk:<path_to_jbr> -nativepath:'<JetBrainsRuntime>/test/jdk/jb/java/awt/event/TouchScreenEvent' test/jdk/jb/java/awt/event/TouchScreenEvent/TouchScreenEventsTest.java

```

vcvarsall.bat above may be found in Microsoft Visual Studio, for example,
"C:\Program Files (x86)\Microsoft Visual Studio 14.0\VC\vcvarsall.bat"

user32.lib above is needed for WinUser.h (touch injection stuff), 
it may be found, for example, in 
"C:\Program Files (x86)\Windows Kits\10\Lib\10.0.18362.0\um\x64\user32.lib"
