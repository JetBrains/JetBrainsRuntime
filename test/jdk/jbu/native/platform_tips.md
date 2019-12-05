###Tips for compiling libraries and preparing env for test running


## Linux

prepare env for work with uinput: [group permissions](https://github.com/tuomasjjrasanen/python-uinput/issues/6#issuecomment-538710069)

```shell script
cd JetBrainsRuntime/test/jdk/jbu/native
# complile lib
gcc -shared -fPIC -I<path_to_jdk>/JetBrainsRuntime/build/linux-x86_64-normal-server-release/images/jdk/include/linux -I<path_to_jdk>/JetBrainsRuntime/build/linux-x86_64-normal-server-release/images/jdk/include touchscreen_device.c -o libtouchscreen_device.so
```

In IDEA run configurations set envvar to find libtouchscreen_device.so
```
LD_LIBRARY_PATH=<path_to_jdk>\JetBrainsRuntime\test\jdk\jbu\native
```

## Windows

prepare windows env: look for vcvarsall.bat [here](https://github.com/JetBrains/JetBrainsRuntime#windows)
```
cd JetBrainsRuntime\test\jdk\jbu\native
cl -I<path_to_jdk>\JetBrainsRuntime\build\windows-x86_64-normal-server-release\jdk\include\win32 -I<path_to_jdk>\JetBrainsRuntime\build\windows-x86_64-normal-server-release\jdk\include -MD -LD windows_touch_robot.c "<path_to_user32lib>\user32.lib" -Fewindows_touch_robot.dll
```

user32.lib is needed for WinUser.h (touch injection stuff)

In IDEA run configurations set envvar to find windows_touch_robot.dll
```
PATH="<path_to_jdk>\JetBrainsRuntime\test\jdk\jbu\native"
```

my paths are  
```
user32: "C:\Program Files (x86)\Windows Kits\10\Lib\10.0.18362.0\um\x64\user32.lib"
path_to_jdk: "C:\Program_Files\cygwin64\home\Denis.Konoplev"
```  
