#!/bin/bash

set -e

mkdir -p dump_output && cd dump_output && rm -rf *
libs=(
    /lib64/ld-linux-x86-64.so.2
    /lib64/libasound.so.2
    /lib64/libatk-1.0.so.0
    /lib64/libatk-bridge-2.0.so.0
    /lib64/libatspi.so.0
    /lib64/libc.so.6
    /lib64/libcairo.so.2
    /lib64/libcups.so.2
    /lib64/libdbus-1.so.3
    /lib64/libdl.so.2
    /lib64/libdrm.so.2
    /lib64/libexpat.so.1
    /lib64/libfreetype.so.6
    /lib64/libgbm.so.1
    /lib64/libgcc_s.so.1
    /lib64/libgio-2.0.so.0
    /lib64/libglib-2.0.so.0
    /lib64/libgobject-2.0.so.0
    /lib64/libm.so.6
    /lib64/libnspr4.so
    /lib64/libnss3.so
    /lib64/libnssutil3.so
    /lib64/libpango-1.0.so.0
    /lib64/libpthread.so.0
    /lib64/librt.so.1
    /lib64/libsmime3.so
    /lib64/libX11.so
    /lib64/libxcb.so.1
    /lib64/libXcomposite.so.1
    /lib64/libXcursor.so.1
    /lib64/libXdamage.so.1
    /lib64/libXext.so.6
    /lib64/libXfixes.so.3
    /lib64/libXi.so.6
    /lib64/libxkbcommon.so.0
    /lib64/libXrandr.so.2
    /lib64/libXrender.so.1
    /lib64/libXtst.so.6
    /lib64/libXxf86vm.so.1
    /lib64/libz.so.1
    /lib64/libwayland-cursor.so.0
)

for lib_path in "${libs[@]}"
do
    if ! test -f "$lib_path"; then
        echo "Failed: $lib_path not found"
	      exit 1
    fi

    lib_name=$(basename -- "$lib_path")
    lib_package=$(rpm -q --whatprovides "$lib_path") 
    echo "Processing $lib_name from $lib_package..."
    echo "Library: $lib_path" >> "$lib_name.txt"
    echo "Package: $lib_package" >> "$lib_name.txt"
    echo "" >> "$lib_name.txt"

    readelf --wide --dyn-syms "$lib_path" >> "$lib_name.txt"
done
