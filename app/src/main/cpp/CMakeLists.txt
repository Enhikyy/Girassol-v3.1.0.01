cmake_minimum_required(VERSION 3.22.1)
project(girassol_core C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------- llama.cpp (opcional)
# O Gradle baixa e verifica (SHA-256) o código-fonte do llama.cpp e passa o caminho em
# GIRASSOL_LLAMA_DIR. Sem ele o app compila em modo econômico (somente simbólico).
#
# v3.4: o motor é compartilhado — UMA libllama.so + libggml.so + libggml-base.so — e o
# backend de CPU é carregado dinamicamente (GGML_BACKEND_DL). No arm64 o ggml compila as
# variantes Android oficiais (GGML_CPU_ALL_VARIANTS); empacotamos apenas três:
#   libggml-cpu-android_armv8.0_1.so  NEON (qualquer arm64)
#   libggml-cpu-android_armv8.2_2.so  dotprod + fp16 + KleidiAI
#   libggml-cpu-android_armv8.6_1.so  dotprod + fp16 + i8mm + KleidiAI
# Antes (v3.3) cada variante embutia llama+ggml inteiros (3× ~4 MB).
set(GIRASSOL_LLAMA_DIR "" CACHE PATH "Código-fonte do llama.cpp")
set(GIRASSOL_KLEIDIAI_DIR "" CACHE PATH "Código-fonte do KleidiAI (v1.32.0)")
set(GIRASSOL_HAS_LLAMA OFF)
set(GIRASSOL_CPU_VARIANTS "")
# páginas de 16 KB (Android 15+) também nas bibliotecas do llama.cpp
string(APPEND CMAKE_SHARED_LINKER_FLAGS " -Wl,-z,max-page-size=16384")
string(APPEND CMAKE_MODULE_LINKER_FLAGS " -Wl,-z,max-page-size=16384")
if(GIRASSOL_LLAMA_DIR AND EXISTS "${GIRASSOL_LLAMA_DIR}/CMakeLists.txt")
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)      # binário portátil
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # threadpool próprio do ggml
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
        if(GIRASSOL_KLEIDIAI_DIR AND EXISTS "${GIRASSOL_KLEIDIAI_DIR}/CMakeLists.txt")
            set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
            set(FETCHCONTENT_SOURCE_DIR_KLEIDIAI "${GIRASSOL_KLEIDIAI_DIR}" CACHE PATH "" FORCE)
            set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "" FORCE)
        else()
            set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
            message(WARNING "Girassol: KleidiAI ausente — backends arm64 sem micro-kernels KleidiAI")
        endif()
        set(GIRASSOL_CPU_VARIANTS ggml-cpu-android_armv8.0_1 ggml-cpu-android_armv8.2_2 ggml-cpu-android_armv8.6_1)
    else()
        # x86_64 (emulador do CI): um único backend de CPU dinâmico (libggml-cpu.so).
        set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
        set(GIRASSOL_CPU_VARIANTS ggml-cpu)
    endif()
    set(CMAKE_C_VISIBILITY_PRESET hidden)          # só a API marcada LLAMA_API/GGML_API sai
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    add_subdirectory("${GIRASSOL_LLAMA_DIR}" llama-build EXCLUDE_FROM_ALL)
    unset(CMAKE_C_VISIBILITY_PRESET)
    unset(CMAKE_CXX_VISIBILITY_PRESET)
    # O ggml depende de TODAS as variantes Android (armv8.2_1, armv9.x com SVE/SME…). As que
    # não usamos são compiladas fora do diretório empacotado pelo Gradle e nunca entram no APK.
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        foreach(_unused android_armv8.2_1 android_armv9.0_1 android_armv9.2_1 android_armv9.2_2)
            if(TARGET ggml-cpu-${_unused})
                set_target_properties(ggml-cpu-${_unused} PROPERTIES
                    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/unpackaged-backends")
            endif()
        endforeach()
    endif()
    # Os backends dinâmicos não são dependências de link (são abertos com dlopen pelo
    # ggml_backend_load), então o AGP não os empacota sozinho. Cada um é copiado após o
    # build para GIRASSOL_BACKEND_OUT/<ABI>, que o Gradle registra como jniLibs do build type.
    foreach(_v ${GIRASSOL_CPU_VARIANTS})
        if(NOT TARGET ${_v})
            message(FATAL_ERROR "Girassol: backend ${_v} não foi gerado pelo ggml")
        endif()
    endforeach()
    if(GIRASSOL_BACKEND_OUT)
        set(_copy_cmds COMMAND ${CMAKE_COMMAND} -E make_directory "${GIRASSOL_BACKEND_OUT}/${ANDROID_ABI}")
        foreach(_v ${GIRASSOL_CPU_VARIANTS})
            list(APPEND _copy_cmds COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:${_v}>" "${GIRASSOL_BACKEND_OUT}/${ANDROID_ABI}/")
        endforeach()
        # Alvo utilitário: roda a cada build (mesmo com os backends em dia) e garante a cópia.
        add_custom_target(girassol_ggml_backends ${_copy_cmds} VERBATIM)
        add_dependencies(girassol_ggml_backends ${GIRASSOL_CPU_VARIANTS})
    endif()
    set(GIRASSOL_HAS_LLAMA ON)
    message(STATUS "Girassol: llama.cpp compartilhado (${GIRASSOL_LLAMA_DIR}); backends: ${GIRASSOL_CPU_VARIANTS}")
else()
    message(WARNING "Girassol: compilando SEM llama.cpp (modo econômico simbólico)")
endif()

# ---------------------------------------------------------------- flags comuns
set(GIRASSOL_EXPORTS_MAP "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
set(GIRASSOL_HARDEN_COMPILE -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
set(GIRASSOL_HARDEN_LINK
    "-Wl,-z,max-page-size=16384"          # páginas de 16 KB (Android 15+)
    "-Wl,--gc-sections"
    "-Wl,--exclude-libs,ALL"              # nada de bibliotecas estáticas vaza na tabela dinâmica
    "-Wl,--version-script=${GIRASSOL_EXPORTS_MAP}")  # exporta só Java_* e JNI_OnLoad

# ---------------------------------------------------------------- núcleo determinístico
add_library(girassol_core SHARED
    jni/girassol_gea_jni.cpp
    jni/world_tick_jni.cpp
    world/world_tick.cpp
    hardware/soc_hardware_probe.cpp
    audio/aaudio_engine.cpp)

target_include_directories(girassol_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# -ffp-contract=off: sem FMA — aritmética float idêntica à da JVM (paridade bit a bit).
target_compile_options(girassol_core PRIVATE -Wall -Wextra -O2 -ffp-contract=off -fno-fast-math ${GIRASSOL_HARDEN_COMPILE})
target_link_options(girassol_core PRIVATE ${GIRASSOL_HARDEN_LINK})
set_target_properties(girassol_core PROPERTIES LINK_DEPENDS "${GIRASSOL_EXPORTS_MAP}")
# OpenCL/Vulkan são sondados via dlopen: nunca ligados estaticamente.
target_link_libraries(girassol_core PRIVATE log dl android aaudio)

# ---------------------------------------------------------------- motor LLM (único)
# libgirassol_llm.so liga dinamicamente a libllama.so; o Kotlin escolhe (HWCAP) qual
# libggml-cpu-*.so carregar via ggml_backend_load() antes do primeiro modelo.
add_library(girassol_llm SHARED llm/llm_jni.cpp llm/llm_engine.cpp llm/lora_ondevice.cpp llm/llg_sampler.cpp)
target_include_directories(girassol_llm PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(girassol_llm PRIVATE -Wall -Wextra -O2 ${GIRASSOL_HARDEN_COMPILE})
target_link_options(girassol_llm PRIVATE ${GIRASSOL_HARDEN_LINK})
set_target_properties(girassol_llm PROPERTIES LINK_DEPENDS "${GIRASSOL_EXPORTS_MAP}")
target_link_libraries(girassol_llm PRIVATE log dl android m)
if(GIRASSOL_HAS_LLAMA)
    target_compile_definitions(girassol_llm PRIVATE GIRASSOL_HAS_LLAMA=1 GIRASSOL_GGML_BACKEND_DL=1)
    target_link_libraries(girassol_llm PRIVATE llama ggml ggml-base)
    add_dependencies(girassol_llm ${GIRASSOL_CPU_VARIANTS})
    if(TARGET girassol_ggml_backends)
        add_dependencies(girassol_llm girassol_ggml_backends)
    endif()
    # ------------------------------------------------------------ llguidance (opcional)
    # Equivalente Girassol de -DLLAMA_LLGUIDANCE=ON (a lib `common` do llama.cpp fica
    # desligada). O Gradle (tarefa buildLlguidance, -Pgirassol.llguidance=auto|on|off) compila
    # o crate Rust para aarch64-linux-android com o rustup do CI e passa o diretório aqui.
    # Sem o crate (ou se o cargo falhar em modo auto) o motor usa a GBNF estrita.
    set(GIRASSOL_LLGUIDANCE_DIR "" CACHE PATH "Diretório com libllguidance.a + llguidance.h")
    if(GIRASSOL_LLGUIDANCE_DIR AND EXISTS "${GIRASSOL_LLGUIDANCE_DIR}/libllguidance.a" AND EXISTS "${GIRASSOL_LLGUIDANCE_DIR}/llguidance.h")
        add_library(girassol_llguidance STATIC IMPORTED)
        set_target_properties(girassol_llguidance PROPERTIES IMPORTED_LOCATION "${GIRASSOL_LLGUIDANCE_DIR}/libllguidance.a")
        target_include_directories(girassol_llm PRIVATE "${GIRASSOL_LLGUIDANCE_DIR}")
        target_compile_definitions(girassol_llm PRIVATE GIRASSOL_USE_LLGUIDANCE=1)
        target_link_libraries(girassol_llm PRIVATE girassol_llguidance)
        message(STATUS "Girassol: llguidance ativo (${GIRASSOL_LLGUIDANCE_DIR})")
    else()
        message(STATUS "Girassol: llguidance ausente — gramática GBNF estrita (fallback)")
    endif()
endif()
