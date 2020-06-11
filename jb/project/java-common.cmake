# common for all OS
set(CMAKE_CXX_STANDARD 98)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D_GNU_SOURCE -D_REENTRANT -DVM_LITTLE_ENDIAN -D_LP64 -DTARGET_ARCH_x86 ")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DINCLUDE_SUFFIX_CPU=_x86 -DAMD64 -DHOTSPOT_LIB_ARCH='amd64' -DCOMPILER1 -DCOMPILER2")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DFT2_BUILD_LIBRARY")

if (${CMAKE_SYSTEM_NAME} MATCHES "Darwin" OR ${CMAKE_SYSTEM_NAME} MATCHES "Linux")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DTARGET_COMPILER_gcc")
endif ()

if (${CMAKE_SYSTEM_NAME} MATCHES "Linux")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DLINUX -DTARGET_OS_FAMILY_linux -DTARGET_COMPILER_gcc -D_GNU_SOURCE")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DLINUX -DTARGET_OS_FAMILY_linux -DTARGET_COMPILER_gcc -D_GNU_SOURCE")
endif ()

if (${CMAKE_SYSTEM_NAME} MATCHES "Darwin")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D_ALLBSD_SOURCE -DTARGET_OS_FAMILY_bsd")
endif ()

if ("${CMAKE_SYSTEM_NAME}" MATCHES "CYGWIN") #not sure about TARGET_COMPILER
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DTARGET_COMPILER_visCPP -DWIN64 -D_WINDOWS -DTARGET_OS_FAMILY_windows")
endif ()

add_custom_target(configure
        COMMAND bash configure --disable-warnings-as-errors
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../)

add_custom_target(configure_debug
        COMMAND bash configure --disable-warnings-as-errors --enable-debug
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../)

add_custom_target(build
        COMMAND make CONF=macosx-x86_64-normal-server-release
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../
        DEPENDS ${SOURCE_FILES})

add_custom_target(build_images
        COMMAND make images CONF=macosx-x86_64-normal-server-release
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../
        DEPENDS ${SOURCE_FILES})

add_custom_target(build_debug
        COMMAND make CONF=macosx-x86_64-normal-server-fastdebug
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../
        DEPENDS ${SOURCE_FILES})

add_custom_target(build_images_debug
        COMMAND make images CONF=macosx-x86_64-normal-server-fastdebug
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../
        DEPENDS ${SOURCE_FILES})

add_custom_target(make_clean
        COMMAND make clean CONF=macosx-x86_64-normal-server-release
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../
        DEPENDS ${SOURCE_FILES})

add_custom_target(make_clean_debug
        COMMAND make clean CONF=macosx-x86_64-normal-server-fastdebug
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}/../../../
        DEPENDS ${SOURCE_FILES})
