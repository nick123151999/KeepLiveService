cmake_minimum_required(VERSION 3.22.1)

project("fw_native")

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 添加编译选项
add_compile_options(-Wall -Wextra -fvisibility=hidden)

# ==================== Android 16K Page Size Support ====================
# Add linker flags for 16KB page size alignment (Android 15+)
# This ensures the native library works on devices with 16KB page sizes
# https://developer.android.com/guide/practices/page-sizes
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# 创建共享库
add_library(fw_native SHARED
    fw_daemon.cpp
    fw_process.cpp
    fw_socket.cpp
    fw_jni.cpp
)

# 查找系统库
find_library(log-lib log)
find_library(android-lib android)

# 链接库
target_link_libraries(fw_native
    ${log-lib}
    ${android-lib}
)
