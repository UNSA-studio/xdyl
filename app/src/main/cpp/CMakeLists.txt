cmake_minimum_required(VERSION 3.18)
project(echjni C)

set(BS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

if(EXISTS ${BS_DIR}/libssl.so)
    add_library(ssl SHARED IMPORTED)
    set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${BS_DIR}/libssl.so)
    add_library(crypto SHARED IMPORTED)
    set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${BS_DIR}/libcrypto.so)

    add_library(echjni SHARED ech_jni.c)
    target_include_directories(echjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/boringssl/include)
    target_link_libraries(echjni ssl crypto log)
else()
    message(WARNING "No BoringSSL for ${ANDROID_ABI}; building stub")
    add_library(echjni SHARED dummy.c)
    target_link_libraries(echjni log)
endif()
