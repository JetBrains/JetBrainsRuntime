cmake_minimum_required(VERSION 3.8)
project(jdk)

include_directories(
    _CMAKE_INCLUDE_DIRS_LIST_
)

add_custom_target(
        configure
        COMMAND bash configure
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
)

add_custom_target(
        build_images
        COMMAND make COMPILER_WARNINGS_FATAL=false images
        WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
        DEPENDS ${SOURCE_FILES}
)

set(
    SOURCE_FILES
    _CMAKE_SOURCE_FILES_LIST_
)

add_executable(jdk ${SOURCE_FILES})

# TODO:
# This CMakeLists.txt has only one goal - code navigation via CLion.
# Is it necessary/usefull to set compiler flags and target_link_libraries ?
