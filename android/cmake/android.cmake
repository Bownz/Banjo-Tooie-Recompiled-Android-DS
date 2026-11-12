# Android-specific build configuration for BanjoTooieRecompiled.
#
# Included from the top-level CMakeLists.txt when it is configured by the Android
# Gradle plugin (see app/build.gradle). Desktop builds never include this file.
#
# What it does:
#   * builds SDL2 from source as libSDL2.so (its Java glue is compiled by Gradle
#     from the same source tree, so the two always match);
#   * builds FreeType from source for RmlUi (desktop Linux uses the system's);
#   * replaces nativefiledialog-extended (no Android backend) with a stub that
#     opens Android's system file picker over JNI;
#   * turns the game target into libmain.so, which SDLActivity loads and runs.

if (NOT DEFINED BT_ANDROID_SDL2_DIR OR NOT EXISTS "${BT_ANDROID_SDL2_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "BT_ANDROID_SDL2_DIR does not point to an SDL2 source tree. "
        "Build through Gradle (it downloads SDL2 into android/third_party), "
        "or pass -DBT_ANDROID_SDL2_DIR=<path to SDL2 source>.")
endif()

# ---------------------------------------------------------------------------
# file_to_c for the build machine
# ---------------------------------------------------------------------------
# RT64's file_to_c runs during the build, so a copy compiled for Android can't be used.
# Unless a host binary is supplied, run the equivalent CMake script (file_to_c.cmake)
# through a small wrapper, so no host C++ compiler is needed (notably on Windows).
if (NOT DEFINED RT64_HOST_FILE_TO_C)
    set(_ftc_script "${CMAKE_CURRENT_LIST_DIR}/file_to_c.cmake")
    if (CMAKE_HOST_WIN32)
        set(RT64_HOST_FILE_TO_C "${CMAKE_BINARY_DIR}/host-tools/file_to_c.cmd")
        file(TO_NATIVE_PATH "${CMAKE_COMMAND}" _ftc_cmake)
        file(TO_NATIVE_PATH "${_ftc_script}" _ftc_script_native)
        file(WRITE "${RT64_HOST_FILE_TO_C}"
            "@\"${_ftc_cmake}\" -P \"${_ftc_script_native}\" %*\r\n@exit /b %ERRORLEVEL%\r\n")
    else()
        set(RT64_HOST_FILE_TO_C "${CMAKE_BINARY_DIR}/host-tools/file_to_c")
        file(WRITE "${RT64_HOST_FILE_TO_C}"
            "#!/bin/sh\nexec \"${CMAKE_COMMAND}\" -P \"${_ftc_script}\" \"$@\"\n")
        file(CHMOD "${RT64_HOST_FILE_TO_C}" PERMISSIONS
            OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
    endif()
elseif (NOT EXISTS "${RT64_HOST_FILE_TO_C}")
    message(FATAL_ERROR "RT64_HOST_FILE_TO_C is set but does not exist: ${RT64_HOST_FILE_TO_C}")
endif()

# ---------------------------------------------------------------------------
# SDL2
# ---------------------------------------------------------------------------
# SDL compiles the NDK's cpufeatures sources via ${ANDROID_NDK}. The NDK's toolchain file
# sets that; CMake's built-in Android support only sets CMAKE_ANDROID_NDK.
if (NOT ANDROID_NDK AND CMAKE_ANDROID_NDK)
    set(ANDROID_NDK "${CMAKE_ANDROID_NDK}")
endif()

set(SDL_SHARED ON  CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST   OFF CACHE BOOL "" FORCE)
add_subdirectory("${BT_ANDROID_SDL2_DIR}" "${CMAKE_BINARY_DIR}/SDL2" EXCLUDE_FROM_ALL)

if (NOT TARGET SDL2::SDL2)
    add_library(SDL2::SDL2 ALIAS SDL2)
endif()

# RT64, plume, ultramodern, recompui and recompinput all expect SDL headers on the
# include path (desktop builds get them from the system or vcpkg), some as <SDL.h> and
# some as <SDL2/SDL_video.h>. SDL's own target exports directories for both styles, plus
# the config header generated for this build, so use exactly those.
set(SDL2_INCLUDE_DIRS "$<TARGET_PROPERTY:SDL2,INTERFACE_INCLUDE_DIRECTORIES>")
include_directories(${SDL2_INCLUDE_DIRS})

# RT64 calls find_package(SDL2 REQUIRED) on every non-Windows platform. Answer it with
# the in-tree build above. (An explicit <Pkg>_DIR bypasses the NDK's find-root rules.)
set(SDL2_DIR "${CMAKE_SOURCE_DIR}/android/cmake/sdl2" CACHE PATH "SDL2 config shim for Android" FORCE)

# ---------------------------------------------------------------------------
# FreeType (RmlUi's font engine), from source
# ---------------------------------------------------------------------------
# Desktop Linux uses the system FreeType and Windows a prebuilt one. Android has neither, so
# it's built here as a static library, without its optional codecs (fonts are plain TTFs).
# RmlUi's find_package(Freetype) is answered by android/cmake/modules/FindFreetype.cmake.
if (NOT DEFINED BT_ANDROID_FREETYPE_DIR OR NOT EXISTS "${BT_ANDROID_FREETYPE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "BT_ANDROID_FREETYPE_DIR does not point to a FreeType source tree. "
        "Build through Gradle (it downloads FreeType into android/third_party), "
        "or pass -DBT_ANDROID_FREETYPE_DIR=<path to FreeType source>.")
endif()
set(FT_DISABLE_ZLIB     ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2    ON CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG      ON CACHE BOOL "" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI   ON CACHE BOOL "" FORCE)
set(_bt_shared_libs ${BUILD_SHARED_LIBS})
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory("${BT_ANDROID_FREETYPE_DIR}" "${CMAKE_BINARY_DIR}/freetype" EXCLUDE_FROM_ALL)
set(BUILD_SHARED_LIBS ${_bt_shared_libs})
if (NOT TARGET Freetype::Freetype)
    add_library(Freetype::Freetype ALIAS freetype)
endif()
list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/modules")

# ---------------------------------------------------------------------------
# Every static library (RT64, librecomp, RmlUi, the recompiled code, ...) is linked
# into libmain.so, so all of it must be position-independent.
# ---------------------------------------------------------------------------
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Keeps RT64 away from its X11 code paths. On Android, RT64/plume still build the
# Vulkan surface from the window's ANativeWindow (see android/patches).
add_compile_definitions(RT64_SDL_WINDOW_VULKAN)

# ---------------------------------------------------------------------------
# zstd (bundled with RT64)
# ---------------------------------------------------------------------------
# zstd's dictionary builder calls qsort_r, which Android's libc only provides from API 36.
# RT64 only compresses/decompresses and never builds dictionaries, so leave it out.
set(ZSTD_BUILD_DICTBUILDER OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# nfd replacement (must exist before lib/rt64 is added; the rt64 patch checks for it)
# ---------------------------------------------------------------------------
add_library(nfd STATIC "${CMAKE_SOURCE_DIR}/android/jni/nfd_android.cpp")
target_include_directories(nfd PUBLIC
    "${CMAKE_SOURCE_DIR}/lib/rt64/src/contrib/nativefiledialog-extended/src/include")
target_link_libraries(nfd PRIVATE SDL2::SDL2 log)

# ---------------------------------------------------------------------------
# libadrenotools: optional custom Vulkan driver (e.g. Turnip) chosen by the player
# ---------------------------------------------------------------------------
if (DEFINED BT_ANDROID_ADRENOTOOLS_DIR AND EXISTS "${BT_ANDROID_ADRENOTOOLS_DIR}/CMakeLists.txt")
    # The library itself is linked statically into libmain.so; its hooks are separate
    # shared libraries that Gradle packages next to it.
    set(_bt_shared_libs ${BUILD_SHARED_LIBS})
    set(BUILD_SHARED_LIBS OFF)
    add_subdirectory("${BT_ANDROID_ADRENOTOOLS_DIR}" "${CMAKE_BINARY_DIR}/adrenotools")
    set(BUILD_SHARED_LIBS ${_bt_shared_libs})
    set(BT_ANDROID_HAVE_ADRENOTOOLS ON)
else()
    message(WARNING "libadrenotools not found (BT_ANDROID_ADRENOTOOLS_DIR); custom GPU drivers disabled.")
    set(BT_ANDROID_HAVE_ADRENOTOOLS OFF)
endif()

# ---------------------------------------------------------------------------
# Game target -> libmain.so
# ---------------------------------------------------------------------------
function(bt_configure_android_target target)
    # SDLActivity loads libSDL2.so and libmain.so, then calls SDL_main() from libmain.
    set_target_properties(${target} PROPERTIES OUTPUT_NAME "main")

    # The desktop entry point is a plain main() (SDL_MAIN_HANDLED). Rename it so the
    # Android shim can set up paths first and then call it.
    set_source_files_properties("${CMAKE_SOURCE_DIR}/src/main.cpp"
        PROPERTIES COMPILE_DEFINITIONS "main=bt_main")
    target_sources(${target} PRIVATE
        "${CMAKE_SOURCE_DIR}/src/android_config.cpp"
        "${CMAKE_SOURCE_DIR}/android/jni/android_main.cpp"
        "${CMAKE_SOURCE_DIR}/android/jni/gpu_driver.cpp"
        "${CMAKE_SOURCE_DIR}/android/jni/touch_controller.cpp"
        "${CMAKE_SOURCE_DIR}/android/jni/game_state.cpp"
        "${CMAKE_SOURCE_DIR}/android/jni/map_render.cpp"
        "${CMAKE_SOURCE_DIR}/android/jni/perf_monitor.cpp")
    if (BT_ANDROID_HAVE_ADRENOTOOLS)
        target_link_libraries(${target} PRIVATE adrenotools)
        target_compile_definitions(${target} PRIVATE BT_HAVE_ADRENOTOOLS=1)
        add_dependencies(${target} main_hook file_redirect_hook gsl_alloc_hook)
    endif()

    target_link_libraries(${target} PRIVATE SDL2::SDL2 android log z)

    # Fail at link time rather than at System.loadLibrary() time on the device.
    target_link_options(${target} PRIVATE "-Wl,--no-undefined")
endfunction()
