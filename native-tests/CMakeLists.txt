cmake_minimum_required(VERSION 3.18)
project(girassol_native_tests C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)

set(GIRASSOL_LLAMA_DIR "" CACHE PATH "Código-fonte do llama.cpp (opcional)")
set(SAN_FLAGS -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)

add_executable(girassol_native_tests native_tests.cpp
    ${SRC}/hardware/soc_hardware_probe.cpp
    ${SRC}/llm/llm_engine.cpp
    ${SRC}/llm/lora_ondevice.cpp
    ${SRC}/llm/llg_sampler.cpp)
target_include_directories(girassol_native_tests PRIVATE ${SRC})
target_compile_options(girassol_native_tests PRIVATE -Wall -Wextra -O1 -g -ffp-contract=off ${SAN_FLAGS})
target_link_options(girassol_native_tests PRIVATE -fsanitize=address,undefined)
target_link_libraries(girassol_native_tests PRIVATE dl pthread)

if(GIRASSOL_LLAMA_DIR AND EXISTS "${GIRASSOL_LLAMA_DIR}/CMakeLists.txt")
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    add_subdirectory("${GIRASSOL_LLAMA_DIR}" llama-build EXCLUDE_FROM_ALL)
    target_compile_definitions(girassol_native_tests PRIVATE GIRASSOL_HAS_LLAMA=1)
    target_link_libraries(girassol_native_tests PRIVATE llama)
    # llguidance (crate Rust compilado para o host): -DGIRASSOL_LLGUIDANCE_DIR=<dir com .a + .h>
    set(GIRASSOL_LLGUIDANCE_DIR "" CACHE PATH "libllguidance.a + llguidance.h (opcional)")
    if(GIRASSOL_LLGUIDANCE_DIR AND EXISTS "${GIRASSOL_LLGUIDANCE_DIR}/libllguidance.a")
        target_include_directories(girassol_native_tests PRIVATE "${GIRASSOL_LLGUIDANCE_DIR}")
        target_compile_definitions(girassol_native_tests PRIVATE GIRASSOL_USE_LLGUIDANCE=1)
        target_link_libraries(girassol_native_tests PRIVATE "${GIRASSOL_LLGUIDANCE_DIR}/libllguidance.a" m)
    endif()
endif()

enable_testing()
add_test(NAME girassol_native_tests COMMAND girassol_native_tests)
