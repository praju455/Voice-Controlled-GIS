cmake_minimum_required(VERSION 3.22.1)

project(tacticalmap_native)

add_library(
    tacticalmap_native
    SHARED
    native_spatialite_bridge.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    tacticalmap_native
    ${log-lib}
)
