cmake_minimum_required(VERSION 3.16)
project(zjuprobe_bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(zjuprobe_bridge SHARED napi_init.cpp)

target_link_libraries(zjuprobe_bridge PUBLIC
    libace_napi.z.so
)
