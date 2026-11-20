cmake_minimum_required(VERSION 3.22.1)
project(girassol_core CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(girassol_core SHARED
    jni/girassol_gea_jni.cpp
    hardware/soc_hardware_probe.cpp)

target_include_directories(girassol_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(girassol_core PRIVATE -Wall -Wextra -fvisibility=hidden -O2)
# Páginas de 16 KB (Android 15+)
target_link_options(girassol_core PRIVATE "-Wl,-z,max-page-size=16384")
# OpenCL/Vulkan são sondados via dlopen: nunca ligados estaticamente.
target_link_libraries(girassol_core PRIVATE log dl android)
