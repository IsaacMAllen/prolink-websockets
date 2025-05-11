cmake_minimum_required(VERSION 3.10)
project(waveform_renderer)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)

# Locate the Java JNI headers
find_package(JNI REQUIRED)

include_directories(
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR} # for local headers if needed
)

add_library(waveform SHARED
    waveform_renderer.cpp
)

# Debug or Release flags
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")
else()
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -g -Wall -fPIC -DDEBUG")
endif()

# Ensure proper output naming for macOS
set_target_properties(waveform PROPERTIES
    OUTPUT_NAME "waveform"
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib
)
