cmake_minimum_required(VERSION 3.24)

set(release_version_file "${CMAKE_CURRENT_SOURCE_DIR}/../../../VERSION")
file(READ "${release_version_file}" cambridge_release_version)
string(STRIP "${cambridge_release_version}" cambridge_release_version)
project(cambridge-obs-plugin VERSION "${cambridge_release_version}" LANGUAGES CXX)

find_package(PkgConfig REQUIRED)
pkg_check_modules(OBS REQUIRED IMPORTED_TARGET libobs)
pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET libavcodec libavutil libswscale)
pkg_check_modules(VA REQUIRED IMPORTED_TARGET libva libva-drm)
pkg_check_modules(DRM REQUIRED IMPORTED_TARGET libdrm)
pkg_check_modules(JANSSON REQUIRED IMPORTED_TARGET jansson)
pkg_check_modules(AVAHI QUIET IMPORTED_TARGET avahi-client)

option(CAMBRIDGE_REQUIRE_AVAHI "Require Avahi mDNS support when configuring the plugin" OFF)

if(CAMBRIDGE_REQUIRE_AVAHI AND NOT AVAHI_FOUND)
    message(FATAL_ERROR "Avahi development files are required for this build")
endif()

if(AVAHI_FOUND)
    message(STATUS "mDNS advertisement enabled through Avahi")
else()
    message(STATUS "mDNS advertisement disabled: avahi-client development files were not found")
endif()

set(CAMBRIDGE_GIT_COMMIT "unknown" CACHE STRING "Source Git commit embedded in the module identity")

add_library(cambridge-obs-plugin MODULE
    src/control_protocol.cpp
    src/control_server.cpp
    src/decoder.cpp
    src/discovery_advertiser.cpp
    src/cambridge_source.cpp
    src/media_receiver.cpp
    src/media_path.cpp
    src/module.cpp
    src/network_address_candidates.cpp
    src/linux_native_frame_temporary.hpp
    src/platform/posix/posix_compat.cpp
    src/protocol_contract.generated.hpp
    src/receiver_constants.hpp
    src/renderer.cpp
    src/rtp.cpp
)
target_compile_features(cambridge-obs-plugin PRIVATE cxx_std_17)
target_compile_definitions(cambridge-obs-plugin PRIVATE
    CAMBRIDGE_GIT_COMMIT="${CAMBRIDGE_GIT_COMMIT}"
    CAMBRIDGE_VERSION="${PROJECT_VERSION}"
)
if(AVAHI_FOUND)
    target_compile_definitions(cambridge-obs-plugin PRIVATE CAMBRIDGE_HAS_AVAHI=1)
endif()
target_compile_options(cambridge-obs-plugin PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror)
target_include_directories(cambridge-obs-plugin SYSTEM PRIVATE
    ${OBS_INCLUDE_DIRS}
    ${FFMPEG_INCLUDE_DIRS}
    ${VA_INCLUDE_DIRS}
    ${DRM_INCLUDE_DIRS}
    ${JANSSON_INCLUDE_DIRS}
)
target_link_directories(cambridge-obs-plugin PRIVATE
    ${OBS_LIBRARY_DIRS}
    ${FFMPEG_LIBRARY_DIRS}
    ${VA_LIBRARY_DIRS}
    ${DRM_LIBRARY_DIRS}
    ${JANSSON_LIBRARY_DIRS}
)
target_link_libraries(cambridge-obs-plugin PRIVATE
    ${OBS_LIBRARIES}
    ${FFMPEG_LIBRARIES}
    ${VA_LIBRARIES}
    ${DRM_LIBRARIES}
    ${JANSSON_LIBRARIES}
)
if(AVAHI_FOUND)
    target_link_libraries(cambridge-obs-plugin PRIVATE PkgConfig::AVAHI)
endif()
set_target_properties(cambridge-obs-plugin PROPERTIES
    PREFIX ""
    OUTPUT_NAME "cambridge-obs-plugin"
)

include(CTest)
if(BUILD_TESTING)
    add_executable(cambridge-obs-plugin-tests
        tests/rtp_tests.cpp
        src/rtp.cpp
    )
    target_compile_features(cambridge-obs-plugin-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-tests PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror)
    add_test(NAME cambridge-obs-plugin-tests COMMAND cambridge-obs-plugin-tests)

    add_executable(cambridge-obs-plugin-contract-tests
        tests/control_tests.cpp
        src/control_protocol.cpp
    )
    target_compile_features(cambridge-obs-plugin-contract-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-contract-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    target_include_directories(cambridge-obs-plugin-contract-tests PRIVATE ${JANSSON_INCLUDE_DIRS})
    target_link_directories(cambridge-obs-plugin-contract-tests PRIVATE ${JANSSON_LIBRARY_DIRS})
    target_link_libraries(cambridge-obs-plugin-contract-tests PRIVATE ${JANSSON_LIBRARIES})
    add_test(NAME cambridge-obs-plugin-contract-tests COMMAND cambridge-obs-plugin-contract-tests)

    add_executable(cambridge-obs-plugin-address-tests
        tests/network_address_candidates_tests.cpp
        src/network_address_candidates.cpp
    )
    target_compile_features(cambridge-obs-plugin-address-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-address-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    add_test(NAME cambridge-obs-plugin-address-tests COMMAND cambridge-obs-plugin-address-tests)

    add_executable(cambridge-obs-plugin-mailbox-tests
        tests/latest_frame_mailbox_tests.cpp
    )
    target_compile_features(cambridge-obs-plugin-mailbox-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-mailbox-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    add_test(NAME cambridge-obs-plugin-mailbox-tests COMMAND cambridge-obs-plugin-mailbox-tests)

    add_executable(cambridge-obs-plugin-media-path-tests
        tests/media_path_tests.cpp
        src/media_path.cpp
    )
    target_compile_features(cambridge-obs-plugin-media-path-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-media-path-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    add_test(NAME cambridge-obs-plugin-media-path-tests COMMAND cambridge-obs-plugin-media-path-tests)

    add_executable(cambridge-obs-plugin-decoder-policy-tests
        tests/decoder_policy_tests.cpp
        src/media_path.cpp
    )
    target_compile_features(cambridge-obs-plugin-decoder-policy-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-decoder-policy-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    add_test(NAME cambridge-obs-plugin-decoder-policy-tests COMMAND cambridge-obs-plugin-decoder-policy-tests)

    add_executable(cambridge-obs-plugin-renderer-policy-tests
        tests/renderer_policy_tests.cpp
        src/media_path.cpp
    )
    target_compile_features(cambridge-obs-plugin-renderer-policy-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-renderer-policy-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    add_test(NAME cambridge-obs-plugin-renderer-policy-tests COMMAND cambridge-obs-plugin-renderer-policy-tests)

    add_executable(cambridge-obs-plugin-posix-compat-tests
        tests/posix_compat_tests.cpp
        src/platform/posix/posix_compat.cpp
    )
    target_compile_features(cambridge-obs-plugin-posix-compat-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-posix-compat-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    add_test(NAME cambridge-obs-plugin-posix-compat-tests COMMAND cambridge-obs-plugin-posix-compat-tests)

    add_executable(cambridge-obs-plugin-frame-lifetime-tests
        tests/frame_lifetime_tests.cpp
    )
    target_compile_features(cambridge-obs-plugin-frame-lifetime-tests PRIVATE cxx_std_17)
    target_compile_options(cambridge-obs-plugin-frame-lifetime-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    add_test(NAME cambridge-obs-plugin-frame-lifetime-tests COMMAND cambridge-obs-plugin-frame-lifetime-tests)
endif()

install(TARGETS cambridge-obs-plugin
    LIBRARY DESTINATION obs-plugins/cambridge-obs-plugin/bin/64bit
)
