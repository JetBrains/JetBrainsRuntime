[![official JetBrains project](http://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

# Welcome to JetBrains Runtime!

JetBrains Runtime is a fork of [OpenJDK](https://github.com/openjdk/jdk) available for Windows, Mac OS X, and Linux.
It includes a number enhancements in font rendering, HiDPI support, ligatures, performance improvements, and bugfixes.

## Releases
Download the latest releases of JetBrains Runtime to use with JetBrains IDEs. The full list
can be found on the [releases page](https://github.com/JetBrains/JetBrainsRuntime/releases).

| IDE Version | Latest JBR | Date Released |
|  ---        | ---        | ---           |
| 2021.3      | [11_0_12-b1751.11](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr11_0_12b1751.11) | 21-Oct-2021 |
| 2021.2      | [11_0_12-b1504.40](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jb11_0_12-b1504.40) | 28-Sep-2021 |
| 2021.1      | [11.0.11+9-b1341.60](https://confluence.jetbrains.com/pages/viewpage.action?pageId=218857477) | 15-Jun-2021 |
| 2020.3      | [11_0_11-b1145.115](https://confluence.jetbrains.com/pages/viewpage.action?pageId=219349001) | 21-Jun-2021 |

## Contents
- [Welcome to JetBrains Runtime](#jetbrains-runtime)
  - [Products Built on JetBrains Runtime](#products-built-on-jetbrains-runtime)
  - [Getting Sources](#getting-sources)
    - [macOS, Linux](#macos-linux)
    - [Windows](#sources-windows)
  - [Configuring the Build Environment](#configuring-the-build-environment)
    - [Linux (Docker)](#linux-docker)
    - [Ubuntu Linux](#ubuntu-linux)
    - [Windows](#build-windows)
    - [macOS](#macos)
  - [Developing](#developing)
  - [Contributing](#contributing)
  - [Resources](#resources)

## Products Built on JetBrains Runtime
* [Android Studio](https://developer.android.com/studio). The official IDE for Google's Android operating system.
* [CLion](https://www.jetbrains.com/clion/). A cross-platform IDE for C and C++ from JetBrains.
* [DataGrip](https://www.jetbrains.com/datagrip/). The IDE for Databases and SQL from JetBrains.
* [GoLand](https://www.jetbrains.com/go/). The cross-platform Go IDE from JetBrains.
* [IntelliJ IDEA](https://www.jetbrains.com/idea/). The IDE for JVM from JetBrains.
* [JProfiler](https://www.ej-technologies.com/products/jprofiler/overview.html). The Java profiler.
* [PhpStorm](https://www.jetbrains.com/phpstorm/). The PHP IDE from JetBrains.
* [PyCharm](https://www.jetbrains.com/pycharm/). The Python IDE from JetBrains.
* [Rider](https://www.jetbrains.com/rider/). The cross-platform .NET IDE from JetBrains.
* [RubyMine](https://www.jetbrains.com/ruby/). The Ruby and Rails IDE from JetBrains.
* [WebStorm](https://www.jetbrains.com/webstorm/). The JavaScript IDE from JetBrains.
* [YourKit](https://www.yourkit.com/). Java and .NET profilers.

## Getting Sources
### macOS, Linux
```
git config --global core.autocrlf input
git clone git@github.com:JetBrains/JetBrainsRuntime.git
```

### Windows
<a name="sources-windows"></a>
```
git config --global core.autocrlf false
git clone git@github.com:JetBrains/JetBrainsRuntime.git
```

## Configuring the Build Environment
Here are quick per-platform instructions for those who can't wait to get started. 
Please refer to [OpenJDK build docs](http://hg.openjdk.java.net/jdk/jdk11/raw-file/tip/doc/building.html) for in-depth
coverage of all the details.

> **_TIP:_**  To get a preliminary report of what's missing, run `./configure` and check its output. 
> It would usually have a meaningful advice on how to solve the problem.

### Linux (Docker)
Create a container:
```
$ cd jb/project/docker
$ docker build .
...
Successfully built 942ea9900054
```
Run these commands in the new container:
```
$ docker run -v `pwd`../../../../:/JetBrainsRuntime -it 942ea9900054
# cd /JetBrainsRuntime
# sh ./configure
# make images CONF=linux-x86_64-normal-server-release
```

### Ubuntu Linux
Install the necessary tools, libraries, and headers with:
```
$ sudo apt-get install autoconf make build-essential libx11-dev libxext-dev libxrender-dev libxtst-dev \
       libxt-dev libxrandr-dev libcups2-dev libfontconfig1-dev libasound2-dev \
       openjdk-11-jdk
```
Then run the following:
```
$ cd JetBrainsRuntime
$ sh ./configure --disable-warnings-as-errors
$ make images
```

### Windows
<a name="build-windows"></a>
Install the following:
* [Cygwin x64](http://www.cygwin.com/).
  Required packages: `autoconf`, `binutils`, `cpio`, `diffutils`, `file`, `gawk`, `gcc-core`, `make`, `m4`, `unzip`, `zip`.  
  Install those together with Cygwin.
* [Visual Studio compiler toolset](https://visualstudio.microsoft.com/downloads/).
  Install with the desktop development kit, which includes Windows SDK and compilers.
  Visual Studio 2015 is supported by default.
* [Java 11](https://docs.aws.amazon.com/corretto/latest/corretto-11-ug/downloads-list.html). 
  If you have problems while configuring, read [Java tips on Cygwin](http://horstmann.com/articles/cygwin-tips.html).

From the command line: 
```
"c:\Program Files (x86)\Microsoft Visual Studio 15.0\VC\vcvarsall.bat" amd64
c:\cygwin64\bin\mintty.exe /usr/bin/bash -l
```
The first command sets up environment variables, the second starts a Cygwin shell with the proper environment.  

In the Cygwin shell: 
```
$ cd JetBrainsRuntime
$ bash configure --enable-option-checking=fatal --with-toolchain-version=2015 \
                 --with-boot-jdk="/cygdrive/c/Program Files/Java/jdk-11.0.5" --disable-warnings-as-errors
$ make images
```

### macOS
Install the following:
* Xcode command line developer tools and `autoconf` via [Homebrew](getDpiInfo).
* [Java 11](https://docs.aws.amazon.com/corretto/latest/corretto-11-ug/downloads-list.html).

From the command line:
```
$ cd JetBrainsRuntime
$ sh ./configure --prefix=$(pwd)/build  --disable-warnings-as-errors
$ make images
```

## Developing
You can use  [CLion](https://www.jetbrains.com/clion/) to develop native parts of the JetBrains Runtime and
[IntelliJ IDEA](https://www.jetbrains.com/idea/) for the parts written in Java.
Both require projects to be created.

### CLion
Run
```
$ make compile-commands
```
in the git root and open the resulting `build/.../compile_commands.json` file as a project.
Then use `Tools | Compilation Database | Change Project Root` to point to git root of this repository.

See also this detailed step-by-step tutorial for all platforms:
[How to develop OpenJDK with CLion](https://blog.jetbrains.com/clion/2020/03/openjdk-with-clion/).

### IDEA
Run
```
$ sh ./bin/idea.sh
```
in the git root to generate project files (add `--help` for options). Then open the git root directory
as a project in IDEA.

## Contributing
We are happy to receive your pull requests! 
Before you submit one, please sign our [Contributor License Agreement (CLA)](https://www.jetbrains.com/agreements/cla/).

## Resources
* [JetBrains Runtime on github](https://github.com/JetBrains/JetBrainsRuntime).
* [OpenJDK build instructions](http://hg.openjdk.java.net/jdk/jdk11/raw-file/tip/doc/building.html).
* [OpenJDK test instructions](http://hg.openjdk.java.net/jdk/jdk11/raw-file/tip/doc/building.html#running-tests).
* [How to develop OpenJDK with CLion](https://blog.jetbrains.com/clion/2020/03/openjdk-with-clion/).
