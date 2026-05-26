cmake_minimum_required(VERSION 3.22.1)
project("blaze_llama")

set(CMAKE_CXX_STANDARD 17)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-O3 -march=armv8-a+dotprod+fp16 -DGGML_USE_CPU_AARCH64)
endif()

set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama.cpp)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama_build)

add_library(blaze_llama SHARED jni_bridge.cpp)

target_link_libraries(blaze_llama llama ggml android log)

target_include_directories(blaze_llama PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)
