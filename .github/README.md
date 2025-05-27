[![official JetBrains project](http://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

# Welcome to JetBrains Runtime!

JetBrains Runtime is a fork of [OpenJDK](https://github.com/openjdk/jdk) available for Windows, Mac OS X, and Linux.
It supports enhanced class redefinition ([DCEVM](https://ssw.jku.at/dcevm/)),
features optional [JCEF](https://github.com/JetBrains/jcef), a framework for embedding Chromium-based browsers,
includes a number of improvements in font rendering, keyboards support,
windowing/focus subsystems, HiDPI, accessibility, and performance, provides better desktop integration
and bugfixes not yet present in OpenJDK.

> **_NOTE_**: This is a **development** branch that is periodically synchronized with
> the [OpenJDK master](https://github.com/openjdk/jdk/tree/master) branch.
>
 Release builds are based on these branches:
 * [jbr11](https://github.com/JetBrains/JetBrainsRuntime/tree/jbr11) (JDK 11)
 * [jbr17](https://github.com/JetBrains/JetBrainsRuntime/tree/jbr17) (JDK 17)
 * [jbr21](https://github.com/JetBrains/JetBrainsRuntime/tree/jbr21) (JDK 21)

Download the latest releases of JetBrains Runtime to use with JetBrains IDEs. The full list
can be found on the [releases page](https://github.com/JetBrains/JetBrainsRuntime/releases).

## Releases based on JDK 21

| IDE Version | Latest JBR                                                                                              | Date Released |
|-------------|---------------------------------------------------------------------------------------------------------|---------------|
| 2025.2      | [21.0.7-b1020.35](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-21.0.7b1020.35)| 26-May-2025   |
| 2025.1      | [21.0.7-b895.130](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-21.0.7b895.130)| 15-May-2025   |
| 2024.3      | [21.0.6-b631.52](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-21.0.7b631.52)  | 15-May-2025   |
| 2024.2      | [21.0.4-b509.40](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-21.0.7b509.40)  | 15-May-2025   |
| 2024.1      | [21.0.2-b346.3](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-21.0.2b346.3)    | 30-Jan-2024   |

## Releases based on JDK 17

| IDE Version | Latest JBR                                                                                             | Date Released |
|-------------|--------------------------------------------------------------------------------------------------------|---------------|
| 2024.2      | [17.0.11-b1312.2](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.11b1312.2)   | 18-Jun-2024|
| 2024.1      | [17.0.12-b1207.37](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.12b1207.37) | 15-Oct-2024|
| 2023.3      | [17.0.12-b1087.25](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.12b1087.25) | 02-Sep-2024|
| 2023.2      | [17.0.12-b1000.54](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.12b1000.54) | 02-Sep-2024|
| 2023.1      | [17.0.10-b829.27](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.10b829.27) | 21-Mar-2024  |
| 2022.3      | [17.0.6-b653.34](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.6b653.34) | 28-Feb-2023   |
| 2022.2      | [17.0.6-b469.82](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-17.0.6b469.82) | 06-Mar-2023   |

## Releases based on JDK 11

| IDE Version | Latest JBR                                                                                            | Date Released |
|-------------|-------------------------------------------------------------------------------------------------------|---------------|
| 2022.1      | [11_0_16-b2043.64](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr11_0_16b2043.64)     | 10-Nov-2022   |
| 2021.3      | [11_0_14_1-b1751.46](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr11_0_14_1b1751.46) | 21-Feb-2022   |
| 2021.2      | [11_0_13-b1504.49](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jb11_0_13-b1504.49)     | 15-Nov-2021   |
| 2021.1      | [11.0.11+9-b1341.60](https://github.com/JetBrains/JetBrainsRuntime/issues/171#issuecomment-1248891540)| 15-Jun-2021   |
| 2020.3      | [11_0_10-b1145.115](https://github.com/JetBrains/JetBrainsRuntime/issues/171#issuecomment-1249243977) | 21-Jun-2021   |

## Release Flavours

There are many kinds of JBR bundles available on the [Releases page](https://github.com/JetBrains/JetBrainsRuntime/releases):

| Flavour       | Description                                                                                                   |
|---------------|---------------------------------------------------------------------------------------------------------------|
| JBR           | Contains the Java Runtime Environment (JRE) suitable to _run_ JVM-based programs.                             |
| JBRSDK        | Contains the Software Developmet Kit (SDK) suitable to _develop_ and _run_ JVM-based programs.                |
| JBR with JCEF | Contains both JBR and JCEF; this flavour is bundled by default with all IntelliJ IDEs.                        |
| vanilla       | Contains just JBR.                                                                                            |
| fastdebug     | The native binaries in this bundle are less optimized and are easier to debug. They also run much slower.     |
| FreeType      | The bundle includes the freetype library built from sources; normally, the library is provided by the system. |
| Vulkan        | The bundle includes experimental Vulkan support.                                                              |                                                              |
| debug symbols | In addition to the usual contents of the bundle the debug information is also included.                       |

## Contents
- [Welcome to JetBrains Runtime](#welcome-to-jetbrains-runtime)
  - [Why Use JetBrains Runtime?](#why-use-jetbrains-runtime)
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

## Why Use JetBrains Runtime?
* **Embedded browser**: JetBrains Runtime includes the Java Chromium Embedded Framework ([JCEF](https://github.com/JetBrains/jcef)), which
  enables you to embed a Chromium-based browsers in your JVM-based application.
 To use it, [download a build with JCEF](https://github.com/JetBrains/JetBrainsRuntime/releases).
* **Enhanced class re-definition** with the [DCEVM](https://ssw.jku.at/dcevm/) technology that makes it easier to reload
  changed code without restarting JVM; this feature needs to be explicitly enabled with `-XX:+AllowEnhancedClassRedefinition`.
* **Better FPS performance** for graphics-intensive applications.
* **Improved font rendering**, **keyboard input** (such as shortcuts and multinational keyboards),
  **HiDPI** and **accessibility** support.
* **Robust desktop experience**: GUI-related fixes often reach JetBrains Runtime much earlier than the corresponding version of OpenJDK.
* Additional capabilities that are made available to applications through
  [JBR API](https://github.com/JetBrains/JetBrainsRuntimeApi) services such as, for example, 
  the ability to wrap a native graphics texture into `java.awt.Image`.

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
> It would usually have meaningful advice on how to solve the problem.

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
# sh ./configure
# make images
```

### Ubuntu Linux
Install the necessary tools, libraries, and headers with:
```
$ sudo apt-get install autoconf make build-essential libx11-dev libxext-dev libxrender-dev libxtst-dev \
       libxt-dev libxrandr-dev libcups2-dev libfontconfig1-dev libasound2-dev libspeechd-dev libwayland-dev \
       wayland-protocols libxkbcommon-x11-0 libdbus-1-dev
```
Get Java 23 (for instance, [Azul Zulu Builds of OpenJDK 23](https://www.azul.com/downloads/?version=java-23&os=linux&package=jdk#zulu)).

Then run the following:
```
$ cd JetBrainsRuntime
$ git checkout main
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
* Java 21 (for instance, [Azul Zulu Builds of OpenJDK 21](https://www.azul.com/downloads/?version=java-21-lts&os=windows&package=jdk#zulu)).
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
$ git checkout main
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
* Java 21 (for instance, [Azul Zulu Builds of OpenJDK 21](https://www.azul.com/downloads/?version=java-21-lts&os=macos&package=jdk#zulu)).

From the command line:
```
$ cd JetBrainsRuntime
$ git checkout main
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
