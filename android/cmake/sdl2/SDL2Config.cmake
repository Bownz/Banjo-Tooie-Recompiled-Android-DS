# Package config shim for the in-tree SDL2 used by the Android build.
#
# RT64 (and RmlUi's optional backends) call find_package(SDL2) on every non-Windows
# platform. On Android, SDL2 is built from source by android/cmake/android.cmake, which
# points SDL2_DIR here so those calls resolve to that build instead of failing.

if (NOT TARGET SDL2::SDL2)
    set(SDL2_FOUND FALSE)
    set(SDL2_NOT_FOUND_MESSAGE "SDL2::SDL2 must be defined (by android/cmake/android.cmake) before find_package(SDL2).")
    return()
endif()

set(SDL2_FOUND TRUE)
set(SDL2_INCLUDE_DIRS "$<TARGET_PROPERTY:SDL2,INTERFACE_INCLUDE_DIRECTORIES>")
set(SDL2_INCLUDE_DIR "${SDL2_INCLUDE_DIRS}")
set(SDL2_LIBRARIES SDL2::SDL2)
set(SDL2_LIBRARY SDL2::SDL2)
