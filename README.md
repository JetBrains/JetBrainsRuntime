[![official JetBrains project](http://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

# How JetBrains Runtime is organised
## Workspaces

[github.com/JetBrains/JetBrainsRuntime](https://github.com/JetBrains/JetBrainsRuntime)  

## Getting sources
__macOS, Linux:__
```
git config --global core.autocrlf input
git clone git@github.com:JetBrains/JetBrainsRuntime.git
```

__Windows:__
```
git config --global core.autocrlf false
git clone git@github.com:JetBrains/JetBrainsRuntime.git
```

# Configure local build environment
[OpenJDK build docs](http://hg.openjdk.java.net/jdk/jdk11/raw-file/tip/doc/building.html)  
Tip for all platforms: run `./configure` and check output.  
Usually, it has meaningful advice how to solve your problem.

## Linux (Docker)
```
$ cd jb/project/docker
$ docker build .
...
Successfully built 942ea9900054

$ docker run -v `pwd`../../../../:/JetBrainsRuntime -it 942ea9900054

# cd /JetBrainsRuntime
# sh ./configure
# make images CONF=linux-x86_64-normal-server-release
```

## Linux (Ubuntu 18.10 desktop)
```
$ sudo apt-get install autoconf make build-essential libx11-dev libxext-dev libxrender-dev libxtst-dev libxt-dev libxrandr-dev libcups2-dev libfontconfig1-dev libasound2-dev 

$ cd JetBrainsRuntime
$ sh ./configure --disable-warnings-as-errors
$ make images
```

## Windows
Install:
* [Cygwin x64](http://www.cygwin.com/)  
  Required packages: autoconf, binutils, cpio, diffutils, file, gawk, gcc-core, make, m4, unzip, zip.  
  **Install them while installing Cygwin**.
* Visual Studio compiler toolset [Download](https://visualstudio.microsoft.com/downloads/)  
  Visual Studio 2015 has support by default.  
  **Install with desktop development kit, it includes Windows SDK and compilers**.
* [Java 11](http://www.oracle.com/technetwork/java/javase/downloads/index.html)  
  If you have problems while configuring [read Java tips on Cygwin](http://horstmann.com/articles/cygwin-tips.html)

From command line: 
```
"c:\Program Files (x86)\Microsoft Visual Studio 14.0\VC\vcvarsall.bat" amd64
"c:\Program_Files\cygwin64\bin\mintty.exe" /bin/bash -l
```
First command will set env vars, the second will run Cygwin shell with proper environment.  

In Cygwin shell: 
```    
cd JetBrainsRuntime
bash configure --enable-option-checking=fatal --with-toolchain-version=2015 --with-boot-jdk="/cygdrive/c/Program Files/Java/jdk-11.0.5" --disable-warnings-as-errors
make images
```

## macOS
Install Xcode command line developer tools, autoconf (via Homebrew).

Run:
```
sh ./configure --prefix=$(pwd)/build  --disable-warnings-as-errors
make images
```

## Contribution
We will be happy to receive your pull requests. Before you submit one, please sign our Contributor License Agreement (CLA)  https://www.jetbrains.com/agreements/cla/ 
