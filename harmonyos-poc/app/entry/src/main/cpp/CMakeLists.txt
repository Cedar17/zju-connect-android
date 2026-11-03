cmake_minimum_required(VERSION 3.16)
project(zjuprobe_bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(zjuprobe_bridge SHARED napi_init.cpp)

target_link_libraries(zjuprobe_bridge PUBLIC
    libace_napi.z.so
    libdl.so
)

# Run the copy on every build; otherwise an updated Go core could be omitted
# when the N-API C++ code itself has not changed.
set(GO_PROBE_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a/libzju_go_probe.so")
if(EXISTS "${GO_PROBE_LIB}")
    add_custom_target(package_go_probe ALL
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different
            "${GO_PROBE_LIB}" "$<TARGET_FILE_DIR:zjuprobe_bridge>/libzju_go_probe.so"
        DEPENDS zjuprobe_bridge
        COMMENT "Copy Go runtime probe into HAP native library directory"
    )
else()
    message(STATUS "Go probe library missing. Build it with scripts/build-go-probe.ps1 before running the app.")
endif()
