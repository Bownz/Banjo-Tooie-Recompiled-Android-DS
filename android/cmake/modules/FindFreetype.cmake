# find_package(Freetype) for the Android build.
#
# FreeType is built from source by android/cmake/android.cmake, which defines the
# Freetype::Freetype target and puts this folder first on CMAKE_MODULE_PATH, so RmlUi's
# find_package(Freetype) resolves to that build instead of searching the NDK's sysroot.

if (NOT TARGET Freetype::Freetype)
    set(FREETYPE_FOUND FALSE)
    set(Freetype_FOUND FALSE)
    set(Freetype_NOT_FOUND_MESSAGE "Freetype::Freetype must be defined (by android/cmake/android.cmake) before find_package(Freetype).")
    return()
endif()

set(FREETYPE_FOUND TRUE)
set(Freetype_FOUND TRUE)
set(FREETYPE_INCLUDE_DIRS "$<TARGET_PROPERTY:freetype,INTERFACE_INCLUDE_DIRECTORIES>")
set(FREETYPE_LIBRARIES Freetype::Freetype)
set(FREETYPE_LIBRARY Freetype::Freetype)
