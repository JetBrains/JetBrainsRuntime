# Welcome to the JDK!

## Wakefield
This is a temporary section created to host information on the
[Wakefield](https://wiki.openjdk.java.net/display/wakefield) project.

### Building
There are two addition `configure` arguments:
```
  --with-wayland          specify prefix directory for the wayland package
                          (expecting the headers under PATH/include)
  --with-wayland-include  specify directory for the wayland include files
```
As usual, there should be no need to specify those explicitly unless you're doing
something tricky.
However, a variant of `libwayland-dev` needs to be installed on the build system.

### Running
Make sure your system is configured such that `libwayland` can find the socket to connect to;
usually this means that the environment variable `WAYLAND_DISPLAY` is set to something
sensible. Then add this argument to `java`
```
-Dawt.toolkit.name=WLToolkit
```

### Testing
Testing that involves `Robot` is done inside a [Weston](https://gitlab.freedesktop.org/wayland/weston/)
instance with a special module loaded called `libwakefield`
that provides the necessary functionality. The Wayland-specific tests are therefore executed with a dedicated test driver
`test/jdk/java/awt/wakefield/WakefieldTestDriver.java`. The driver also provides an easy
way to run the test in several configurations with a different size and even number
of "outputs" (monitors).

To run the Wayland-specific tests, perform these steps:
* Install Weston version 9 (earlier versions are known NOT to work).
* Obtain `libwakefield.so` either by building from source (available under
`src/java.desktop/share/native/libwakefield` and not integrated into the rest of the
build infrastructure; see `README.md` there)
or by fetching the latest pre-built `x64` binary
```
wget https://github.com/mkartashev/wakefield/raw/main/libwakefield.so
```
* Set `LIBWAKEFIELD` environment variable to the full path to `libwakefield.so`
```
export LIBWAKEFIELD=/tmp/wakefield-testing/libwakefield.so
```
* Run `jtreg` like so
```
jtreg -e:XDG_RUNTIME_DIR -e:LIBWAKEFIELD -testjdk:... test/jdk/java/awt/wakefield/
```

This was verified to work in `Ubuntu 21.10`.
This does NOT work in `Ubuntu 21.04` or `Fedora 34`.

## Generic Info (not Wakefield-specific)
For build instructions please see the
[online documentation](https://openjdk.java.net/groups/build/doc/building.html),
or either of these files:

- [doc/building.html](doc/building.html) (html version)
- [doc/building.md](doc/building.md) (markdown version)

See <https://openjdk.java.net/> for more information about
the OpenJDK Community and the JDK.
