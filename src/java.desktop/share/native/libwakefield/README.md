# Wakefield Protocol
This is a [Weston](https://github.com/wayland-project/weston) protocol extension
created to satisfy the requirements of the
[Wakefield](https://openjdk.java.net/projects/wakefield/) project *testing*.

## Rationale
Many of `java.awt.Robot` capabilities contradict the basic principles of
[Wayland](https://openjdk.java.net/projects/wakefield/). The examples include
obtaining the color of a pixel at specified coordinates by automated tests.
This extension aims to help in implementation of those capabilities for the use
in test environment *only*.

## Prerequisites
On Ubuntu 21.04 (in addition to the usual C development environment):
```bash
$ sudo apt install cmake libwayland-dev wayland-protocols \
   libweston-9-0 libweston-9-dev weston libpixman-1-dev libpixman-1-0
```

## Build

```bash
$ mkdir build && cd build
$ cmake .../src/java.desktop/share/native/libwakefield/
$ make
```
This should build `libwakefield.so` in the current (build) directory.

## Smoke test

```bash
$ PWD=`pwd` && \
  weston --socket=wayland-test --width=800 --height=600 --use-pixman --socket=wayland-42 \
    --modules="$PWD/libwakefield.so"
```
This will create a new Weston compositor instance with the Wayland socket
named `wayland-42`.
Now you can verify that a new global has been published by running this command:
```bash
$ WAYLAND_DISPLAY=wayland-42 weston-info | grep wakefield
interface: 'wakefield', version: 1, name: 21
```

