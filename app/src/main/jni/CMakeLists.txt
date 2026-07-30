cmake_minimum_required(VERSION 3.22)
project("medgpt_security")

add_library(native_security SHARED
    native_bridge.cpp
)

# Use C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Link OpenSSL for HTTPS + SSL pinning
find_library(ssl_lib ssl)
find_library(crypto_lib crypto)

if(ssl_lib AND crypto_lib)
    message(STATUS "OpenSSL found - SSL pinning enabled")
    target_link_libraries(native_security ${ssl_lib} ${crypto_lib})
    add_definitions(-DHAS_SSL=1)
else()
    message(STATUS "OpenSSL NOT found - SSL pinning via manual socket")
endif()

# Link log library for debugging
find_library(log_lib log)
target_link_libraries(native_security ${log_lib} android)

# Link jnigraphics
find_library(jnigraphics_lib jnigraphics)
if(jnigraphics_lib)
    target_link_libraries(native_security ${jnigraphics_lib})
endif()
