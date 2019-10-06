[![official JetBrains project](http://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

# Downloads

|Windows-x64  |macOS        |Linux-x64    |
|-------------|-------------|-------------|
|<a href="https://bintray.com/jetbrains/intellij-jdk/openjdk9-windows-x64/_latestVersion"> <img src="https://api.bintray.com/packages/jetbrains/intellij-jdk/openjdk9-windows-x64/images/download.svg"/></a>|<a href="https://bintray.com/jetbrains/intellij-jdk/openjdk9-osx-x64/_latestVersion"> <img src="https://api.bintray.com/packages/jetbrains/intellij-jdk/openjdk9-osx-x64/images/download.svg"/></a>|<a href="https://bintray.com/jetbrains/intellij-jdk/openjdk9-linux-x64/_latestVersion"><img src="https://api.bintray.com/packages/jetbrains/intellij-jdk/openjdk9-linux-x64/images/download.svg"/></a>|


# How JetBrains Runtime is organised
## Workspaces

[github.com/JetBrains/JetBrainsRuntime](https://github.com/JetBrains/JetBrainsRuntime)  

## Getting sources
__OSX, Linux:__
```
git config --global core.autocrlf input
git clone git@github.com:JetBrains/JetBrainsRuntime.git
```

__Windows:__
```
git config --global core.autocrlf false
git clone git@github.com:JetBrains/JetBrainsRuntime.git
```

# Configure Local Build Environment
## Linux (docker)
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
#### TBD

## OSX

install Xcode console tools, autoconf (via homebrew)

run

```
sh ./configure --prefix=$(pwd)/build  --disable-warnings-as-errors
make images
```

## Contribution
We will be happy to receive your pull requests. Before you submit one, please sign our Contributor License Agreement (CLA)  https://www.jetbrains.com/agreements/cla/ 
