[![official JetBrains project](http://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

# Welcome to JetBrains Runtime!

JetBrains Runtime is a fork of [OpenJDK](https://github.com/openjdk/jdk) available for Windows, Mac OS X, and Linux.
It includes a number of enhancements in font rendering, ligatures, HiDPI support, windowing/focus subsystems, performance improvements, and bugfixes.

## Releases
Download the latest releases of JetBrains Runtime to use with JetBrains IDEs. The full list
can be found on the [releases page](https://github.com/JetBrains/JetBrainsRuntime/releases).

| IDE Version | Latest JBR                                                                                              | Date Released |
|-------------|---------------------------------------------------------------------------------------------------------|---------------|
| 2023.3      | [17.0.9b1087.7](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.9b1087.7)   | 20-Nov-2023   |
| 2023.2      | [17.0.9b1000.46](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.9b1000.46) | 01-Nov-2023   | 
| 2023.1      | [17.0.6-b829.1](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.6b829.1)    | 14-Feb-2023   |
| 2022.3      | [17.0.5-b653.25](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.5b653.25)  | 10-Jan-2023   |
| 2022.2      | [17.0.5-b469.71](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.5b469.71)  | 14-Nov-2022   |


## Contents
- [Welcome to JetBrains Runtime](#welcome-to-jetbrains-runtime)
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
* [Toolbox App](https://www.jetbrains.com/toolbox-app/). JetBrains IDE manager.
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
Please refer to [OpenJDK build docs](https://openjdk.java.net/groups/build/doc/building.html) for in-depth
coverage of all the details.

> **_TIP:_**  To get a preliminary report of what's missing, run `./configure` and check its output. 
> It would usually have a meaningful advice on how to solve the problem.

### Linux (Docker)
Download an image from [Docker Hub](https://hub.docker.com/repository/docker/jetbrains/runtime/general) related to your architecture:
```
$ docker pull jetbrains/runtime:oraclelinux8_aarch64
```
or
```
$ docker pull jetbrains/runtime:oraclelinux8_x64
```
Create and run a new container from the downloaded image
```
$ docker run -v $JetBrainsRuntime:/JetBrainsRuntime -it jetbrains/runtime:oraclelinux8_[arch]
```
where `$JetBrainsRuntime` is a full path to the directory where the repository was cloned to.

Run these commands in the container:
```
# cd /JetBrainsRuntime
# git checkout jbr17
# sh ./configure
# make images CONF=linux-x86_64-normal-server-release
```

### Ubuntu Linux
Install the necessary tools, libraries, and headers with:
```
$ sudo apt-get install autoconf make build-essential libx11-dev libxext-dev libxrender-dev libxtst-dev \
       libxt-dev libxrandr-dev libcups2-dev libfontconfig1-dev libasound2-dev libspeechd-dev \
       java-16-amazon-corretto-jdk
```
Then run the following:
```
$ cd JetBrainsRuntime
$ git checkout jbr17
$ sh ./configure
$ make images
```
This will build the release configuration under `./build/linux-x86_64-server-release/`.

### Windows
<a name="build-windows"></a>
Install the following:
* [Cygwin x64](http://www.cygwin.com/).
  Required packages: `autoconf`, `binutils`, `cpio`, `diffutils`, `file`, `gawk`, `gcc-core`, `make`, `m4`, `unzip`, `zip`.  
  Install those together with Cygwin.
* [Visual Studio compiler toolset](https://visualstudio.microsoft.com/downloads/).
  Install with the desktop development kit, which includes Windows SDK and compilers.
  Visual Studio 2019 is supported by default.
* Java 16 (for instance, from [AdoptOpenJDK](https://adoptopenjdk.net/installation.html?variant=openjdk16&jvmVariant=hotspot#)).
  If you have problems while configuring, read [Java tips on Cygwin](http://horstmann.com/articles/cygwin-tips.html).

From the command line: 
```
"C:\Program Files (x86)\Microsoft Visual Studio\2019\Community\VC\Auxiliary\Build\vcvarsall.bat" amd64
"c:\Program_Files\cygwin64\bin\mintty.exe" /bin/bash -l
```
The first command sets up environment variables, the second starts a Cygwin shell with the proper environment.  

In the Cygwin shell: 
```
$ cd JetBrainsRuntime
$ git checkout jbr17
$ bash configure --with-toolchain-version=2019
$ make images
```
This will build the release configuration under `./build/windows-x86_64-server-release/`.

#### Enable optional NVDA screen reader support
If you want to add support of a11y announcing via [NVDA screen reader](https://www.nvaccess.org/about-nvda/),
you will need to bundle the NVDA Controller Client library.
You can do it with the following steps:
1. Download the NVDA Controller Client library. You can find the link in its official README [here](https://github.com/nvaccess/nvda/blob/master/extras/controllerClient/readme.md)
2. Pass the path to the unpacked package to `configure` via an additional flag `--with-nvdacontrollerclient=<path>`.
   The build system will search the required library files under `<path>/<target-arch>`.

#### Disable optional JAWS screen reader support
JBR is built with built-in support of JAWS screen reader.
If you want to disable it, run `configure` with the additional flag `--disable-jaws-client`.

### macOS
Install the following:
* Xcode command line developer tools and `autoconf` via [Homebrew](https://brew.sh/).
* Java 16 (for instance, from [AdoptOpenJDK](https://adoptopenjdk.net/installation.html?variant=openjdk16&jvmVariant=hotspot#)).

From the command line:
```
$ cd JetBrainsRuntime
$ git checkout jbr17
$ sh ./configure
$ make images
```
This will build the release configuration under `./build/macosx-x86_64-server-release/`.

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
in the git root to generate project files (add `--help` for options). If you have multiple
configurations (for example, `release` and `fastdebug`), supply the `--conf <conf_name>` argument.
Then open the git root directory as a project in IDEA.

## Contributing
We are happy to receive your pull requests! 
Before you submit one, please sign our [Contributor License Agreement (CLA)](https://www.jetbrains.com/agreements/cla/).

## Resources
* [JetBrains Runtime on GitHub](https://github.com/JetBrains/JetBrainsRuntime).
* [OpenJDK build instructions](https://openjdk.java.net/groups/build/doc/building.html).
* [OpenJDK test instructions](https://htmlpreview.github.io/?https://raw.githubusercontent.com/openjdk/jdk/master/doc/building.html#running-tests).
* [How to develop OpenJDK with CLion](https://blog.jetbrains.com/clion/2020/03/openjdk-with-clion/).
