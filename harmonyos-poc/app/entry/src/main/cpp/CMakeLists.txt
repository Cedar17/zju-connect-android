cmake_minimum_required(VERSION 3.16)
project(zjuprobe_bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(GO_PROBE_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a/libzju_go_probe.so")
if(NOT EXISTS "${GO_PROBE_LIB}")
    message(FATAL_ERROR "Go probe library is missing. Run harmonyos-poc/scripts/build-go-probe.ps1 before building the HAP.")
endif()

add_library(zjuprobe_bridge SHARED napi_init.cpp)
target_link_libraries(zjuprobe_bridge PUBLIC
    libace_napi.z.so
    libdl.so
)

# Always refresh the packaged Go library, even if the C++ bridge is unchanged.
add_custom_target(package_go_probe ALL
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${GO_PROBE_LIB}" "$<TARGET_FILE_DIR:zjuprobe_bridge>/libzju_go_probe.so"
    DEPENDS zjuprobe_bridge
    COMMENT "Package OpenHarmony Go runtime probe"
)
