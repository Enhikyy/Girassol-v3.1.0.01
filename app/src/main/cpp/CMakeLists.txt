cmake_minimum_required(VERSION 3.22.1)
project(girassol_core C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------- llama.cpp (opcional)
# O Gradle baixa e verifica (SHA-256) o código-fonte do llama.cpp e passa o caminho em
# GIRASSOL_LLAMA_DIR. Sem ele o app compila em modo econômico (somente simbólico).
set(GIRASSOL_LLAMA_DIR "" CACHE PATH "Código-fonte do llama.cpp")
set(GIRASSOL_HAS_LLAMA OFF)
if(GIRASSOL_LLAMA_DIR AND EXISTS "${GIRASSOL_LLAMA_DIR}/CMakeLists.txt")
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)      # binário portátil (Cortex-A53 em diante)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # threadpool próprio do ggml
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)  # evita download em tempo de build
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    add_subdirectory("${GIRASSOL_LLAMA_DIR}" llama-build EXCLUDE_FROM_ALL)
    set(GIRASSOL_HAS_LLAMA ON)
    message(STATUS "Girassol: llama.cpp integrado de ${GIRASSOL_LLAMA_DIR}")
else()
    message(WARNING "Girassol: compilando SEM llama.cpp (modo econômico simbólico)")
endif()

add_library(girassol_core SHARED
    jni/girassol_gea_jni.cpp
    hardware/soc_hardware_probe.cpp
    audio/aaudio_engine.cpp
    llm/llm_engine.cpp)

target_include_directories(girassol_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# -ffp-contract=off: sem FMA — aritmética float idêntica à da JVM (paridade bit a bit).
target_compile_options(girassol_core PRIVATE -Wall -Wextra -fvisibility=hidden -O2 -ffp-contract=off -fno-fast-math)
# Páginas de 16 KB (Android 15+)
target_link_options(girassol_core PRIVATE "-Wl,-z,max-page-size=16384")
# OpenCL/Vulkan são sondados via dlopen: nunca ligados estaticamente.
target_link_libraries(girassol_core PRIVATE log dl android aaudio)
if(GIRASSOL_HAS_LLAMA)
    target_compile_definitions(girassol_core PRIVATE GIRASSOL_HAS_LLAMA=1)
    target_link_libraries(girassol_core PRIVATE llama)
endif()
