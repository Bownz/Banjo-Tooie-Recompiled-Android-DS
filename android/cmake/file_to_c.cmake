# CMake-script implementation of RT64's file_to_c tool, used for cross-compiles.
#
# file_to_c turns a binary file into a C array at build time, so it has to run on the
# build machine. Rather than compiling the C++ tool for the host (which on Windows would
# need Visual Studio's compiler), cross builds run this script with the same CMake that
# drives the build. Output matches file_to_c: same symbols, sizes and byte values.
#
# Usage: cmake -P file_to_c.cmake <input file> <array name> <output .c> <output .h>

if (NOT CMAKE_ARGC EQUAL 7)
    message(FATAL_ERROR "Usage: cmake -P file_to_c.cmake [input file] [array name] [output C file] [output C header]")
endif()

set(input_path    "${CMAKE_ARGV3}")
set(array_name    "${CMAKE_ARGV4}")
set(output_c_path "${CMAKE_ARGV5}")
set(output_h_path "${CMAKE_ARGV6}")

if (NOT EXISTS "${input_path}")
    message(FATAL_ERROR "Failed to open file ${input_path}! (Or it's empty)")
endif()

file(READ "${input_path}" contents HEX)
string(LENGTH "${contents}" hex_length)
math(EXPR size "${hex_length} / 2")
if (size EQUAL 0)
    message(FATAL_ERROR "Failed to open file ${input_path}! (Or it's empty)")
endif()

# Each byte becomes "(char)0xNN, ", which has the same value file_to_c writes as a signed
# decimal, without per-byte arithmetic (which would be slow in CMake for large files).
string(REGEX REPLACE "([0-9a-f][0-9a-f])" "(char)0x\\1, " values "${contents}")

get_filename_component(c_dir "${output_c_path}" DIRECTORY)
get_filename_component(h_dir "${output_h_path}" DIRECTORY)
if (c_dir)
    file(MAKE_DIRECTORY "${c_dir}")
endif()
if (h_dir)
    file(MAKE_DIRECTORY "${h_dir}")
endif()

file(WRITE "${output_c_path}"
    "#include <stddef.h>\n"
    "extern const char ${array_name}[${size}];\n"
    "const char ${array_name}[${size}] = {${values}};\n"
    "extern const size_t ${array_name}_size;\n"
    "const size_t ${array_name}_size = sizeof(${array_name}) / sizeof(${array_name}[0]);\n")

file(WRITE "${output_h_path}"
    "#ifdef __cplusplus\n"
    "  extern \"C\" {\n"
    "#endif\n"
    "#include <stddef.h>\n"
    "extern const char ${array_name}[${size}];\n"
    "extern const size_t ${array_name}_size;\n"
    "#ifdef __cplusplus\n"
    "  }\n"
    "#endif\n")
