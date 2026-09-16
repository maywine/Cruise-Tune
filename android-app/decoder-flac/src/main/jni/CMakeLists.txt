cmake_minimum_required(VERSION 3.22)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
project(cruise_flac C CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(flac
    URL https://codeload.github.com/xiph/flac/tar.gz/refs/tags/1.5.0
    TIMEOUT 120
    INACTIVITY_TIMEOUT 30
    URL_HASH SHA256=aea54ed186ad07a34750399cb27fc216a2b62d0ffcd6dc2e3064a3518c3146f8)
FetchContent_MakeAvailable(flac)

add_library(flacJNI SHARED flac_jni.cc flac_parser.cc)
target_include_directories(flacJNI PRIVATE "${flac_SOURCE_DIR}/include")
target_link_libraries(flacJNI PRIVATE FLAC)

if(ANDROID)
    find_library(android_log_lib log REQUIRED)
    target_link_libraries(flacJNI PRIVATE ${android_log_lib})
else()
    # Host checks run the identical decoder/JNI code; only Android logging is replaced.
    find_package(JNI REQUIRED)
    target_include_directories(flacJNI PRIVATE ${JNI_INCLUDE_DIRS} "${CMAKE_CURRENT_SOURCE_DIR}/../../test/native")
endif()
