# Biblioteca de host do WorldTick nativo para os testes JVM (:core:test, :app:testDebugUnitTest).
# Mesmas fontes C++20 que entram na libgirassol_core.so do APK.
cmake_minimum_required(VERSION 3.24)
project(girassol_worldtick_host CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(JNI REQUIRED COMPONENTS JVM)
set(SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")
add_library(girassol_worldtick_host SHARED "${SRC}/world/world_tick.cpp" "${SRC}/jni/world_tick_jni.cpp"
    "${SRC}/world/cognition.cpp" "${SRC}/jni/cognition_jni.cpp")
target_include_directories(girassol_worldtick_host PRIVATE ${JAVA_INCLUDE_PATH} ${JAVA_INCLUDE_PATH2} "${SRC}")
target_compile_options(girassol_worldtick_host PRIVATE -Wall -Wextra -O2 -ffp-contract=off -fno-fast-math)
