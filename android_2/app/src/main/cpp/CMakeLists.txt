cmake_minimum_required(VERSION 3.18.1)
project("native-lib")

add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)
find_library(mediandk-lib mediandk)
find_library(android-lib android)

target_link_libraries(native-lib 
    ${log-lib} 
    ${mediandk-lib} 
    ${android-lib}
)
