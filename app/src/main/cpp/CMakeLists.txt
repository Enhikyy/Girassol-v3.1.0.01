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
    # Símbolos internos do llama.cpp/ggml ficam ocultos (só JNI é exportado).
    set(CMAKE_C_VISIBILITY_PRESET hidden)
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    # Variante base: sem -march extra (armv8-a no arm64), roda em qualquer aparelho.
    add_subdirectory("${GIRASSOL_LLAMA_DIR}" llama-build EXCLUDE_FROM_ALL)
    unset(CMAKE_C_VISIBILITY_PRESET)
    unset(CMAKE_CXX_VISIBILITY_PRESET)
    set(GIRASSOL_HAS_LLAMA ON)
    message(STATUS "Girassol: llama.cpp integrado de ${GIRASSOL_LLAMA_DIR}")
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
    hardware/soc_hardware_probe.cpp
    audio/aaudio_engine.cpp)

target_include_directories(girassol_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# -ffp-contract=off: sem FMA — aritmética float idêntica à da JVM (paridade bit a bit).
target_compile_options(girassol_core PRIVATE -Wall -Wextra -O2 -ffp-contract=off -fno-fast-math ${GIRASSOL_HARDEN_COMPILE})
target_link_options(girassol_core PRIVATE ${GIRASSOL_HARDEN_LINK})
set_target_properties(girassol_core PROPERTIES LINK_DEPENDS "${GIRASSOL_EXPORTS_MAP}")
# OpenCL/Vulkan são sondados via dlopen: nunca ligados estaticamente.
target_link_libraries(girassol_core PRIVATE log dl android aaudio)

# ---------------------------------------------------------------- motor LLM (variantes)
# Três bibliotecas JNI independentes com o mesmo código e flags de CPU diferentes. O Kotlin
# lê o HWCAP via libgirassol_core e carrega UMA delas — nunca há instrução i8mm num
# Cortex-A78 (Snapdragon 695) nem dotprod num Cortex-A53 antigo.
#   girassol_llm_base     armv8-a (todas as ABIs)
#   girassol_llm_dotprod  armv8.2-a+dotprod+fp16      + KleidiAI (arm64)
#   girassol_llm_i8mm     armv8.2-a+dotprod+fp16+i8mm + KleidiAI (arm64)
function(girassol_llm_target name variant march)
    add_library(${name} SHARED llm/llm_jni.cpp llm/llm_engine.cpp)
    target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(${name} PRIVATE -Wall -Wextra -O2 ${GIRASSOL_HARDEN_COMPILE})
    target_compile_definitions(${name} PRIVATE GIRASSOL_LLM_VARIANT="${variant}" GIRASSOL_LLM_MARCH="${march}")
    target_link_options(${name} PRIVATE ${GIRASSOL_HARDEN_LINK})
    set_target_properties(${name} PROPERTIES LINK_DEPENDS "${GIRASSOL_EXPORTS_MAP}")
    target_link_libraries(${name} PRIVATE log dl android m)
endfunction()

girassol_llm_target(girassol_llm_base "base" "armv8-a")
if(GIRASSOL_HAS_LLAMA)
    target_compile_definitions(girassol_llm_base PRIVATE GIRASSOL_HAS_LLAMA=1)
    target_link_libraries(girassol_llm_base PRIVATE llama)
endif()

set(GIRASSOL_KLEIDIAI_DIR "" CACHE PATH "Código-fonte do KleidiAI (v1.32.0)")
if(GIRASSOL_HAS_LLAMA AND ANDROID_ABI STREQUAL "arm64-v8a")
    include(ExternalProject)
    set(_kleidi OFF)
    if(GIRASSOL_KLEIDIAI_DIR AND EXISTS "${GIRASSOL_KLEIDIAI_DIR}/CMakeLists.txt")
        set(_kleidi ON)
    else()
        message(WARNING "Girassol: KleidiAI ausente — variantes arm64 sem micro-kernels KleidiAI")
    endif()
    get_target_property(_llama_inc llama INTERFACE_INCLUDE_DIRECTORIES)
    get_target_property(_ggml_inc ggml INTERFACE_INCLUDE_DIRECTORIES)

    foreach(_v dotprod i8mm)
        if(_v STREQUAL "dotprod")
            set(_march "armv8.2-a+dotprod+fp16")
        else()
            set(_march "armv8.2-a+dotprod+fp16+i8mm")
        endif()
        set(_bin "${CMAKE_CURRENT_BINARY_DIR}/ggml-cpu-${_v}")
        set(_cpu_lib "${_bin}/ggml/src/libggml-cpu.a")
        set(_byproducts "${_cpu_lib}")
        set(_targets --target ggml-cpu)
        set(_extra_libs "")
        if(_kleidi)
            set(_kl_lib "${_bin}/_deps/kleidiai-build/libkleidiai.a")
            list(APPEND _byproducts "${_kl_lib}")
            list(APPEND _targets --target kleidiai)
            set(_extra_libs "${_kl_lib}")
        endif()
        # Só o backend ggml-cpu é recompilado com a -march da variante; llama e ggml-base
        # (independentes de SIMD) são reaproveitados da variante base.
        ExternalProject_Add(ggml_cpu_${_v}
            SOURCE_DIR "${GIRASSOL_LLAMA_DIR}"
            BINARY_DIR "${_bin}"
            CMAKE_ARGS
                -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
                -DANDROID_ABI=${ANDROID_ABI}
                -DANDROID_PLATFORM=${ANDROID_PLATFORM}
                -DANDROID_STL=${ANDROID_STL}
                -DANDROID_NDK=${ANDROID_NDK}
                -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
                -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
                -DCMAKE_POSITION_INDEPENDENT_CODE=ON
                -DCMAKE_C_VISIBILITY_PRESET=hidden
                -DCMAKE_CXX_VISIBILITY_PRESET=hidden
                -DCMAKE_VISIBILITY_INLINES_HIDDEN=ON
                -DBUILD_SHARED_LIBS=OFF
                -DGGML_NATIVE=OFF
                -DGGML_OPENMP=OFF
                -DGGML_VULKAN=OFF
                -DGGML_OPENCL=OFF
                -DGGML_CPU_ARM_ARCH=${_march}
                -DGGML_CPU_KLEIDIAI=${_kleidi}
                -DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=${GIRASSOL_KLEIDIAI_DIR}
                -DFETCHCONTENT_FULLY_DISCONNECTED=ON
                -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF
                -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF
                -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF
            BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> ${_targets}
            BUILD_BYPRODUCTS ${_byproducts}
            INSTALL_COMMAND ""
            STEP_TARGETS build
            EXCLUDE_FROM_ALL TRUE)

        set(_name girassol_llm_${_v})
        set(_desc "${_march}")
        if(_kleidi)
            set(_desc "${_march}+kleidiai")
        endif()
        girassol_llm_target(${_name} "${_v}" "${_desc}")
        add_dependencies(${_name} ggml_cpu_${_v} llama ggml ggml-base)
        target_compile_definitions(${_name} PRIVATE GIRASSOL_HAS_LLAMA=1)
        target_include_directories(${_name} PRIVATE ${_llama_inc} ${_ggml_inc})
        # Arquivos estáticos explícitos (sem o ggml-cpu base que viria via alvo "llama").
        target_link_libraries(${_name} PRIVATE
            "-Wl,--start-group"
            "$<TARGET_FILE:llama>" "$<TARGET_FILE:ggml>" "${_cpu_lib}" ${_extra_libs} "$<TARGET_FILE:ggml-base>"
            "-Wl,--end-group")
    endforeach()
endif()
if(NOT TARGET girassol_llm_dotprod)
    # Outras ABIs (x86_64 do emulador) ou build sem llama.cpp: stubs sem motor, jamais
    # escolhidos pelo seletor (que só recomenda dotprod/i8mm em arm64 com HWCAP).
    girassol_llm_target(girassol_llm_dotprod "dotprod-stub" "none")
    girassol_llm_target(girassol_llm_i8mm "i8mm-stub" "none")
endif()

