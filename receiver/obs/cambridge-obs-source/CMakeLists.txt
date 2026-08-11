cmake_minimum_required(VERSION 3.24)

set(release_version_file "${CMAKE_CURRENT_SOURCE_DIR}/../../../VERSION")
file(READ "${release_version_file}" cambridge_release_version)
string(STRIP "${cambridge_release_version}" cambridge_release_version)
project(cambridge-obs-plugin VERSION "${cambridge_release_version}" LANGUAGES CXX)

set(protocol_contract_file "${CMAKE_CURRENT_SOURCE_DIR}/../../../protocol/cambridge-stream-contract.json")
file(READ "${protocol_contract_file}" protocol_contract_json)
string(JSON CAMBRIDGE_DISCOVERY_SERVICE_TYPE GET "${protocol_contract_json}"
       discovery serviceType)

option(CAMBRIDGE_BUILD_PLUGIN "Build the OBS source module" ON)
option(CAMBRIDGE_BUILD_TESTS "Build CamBridge tests" ON)
option(CAMBRIDGE_BUILD_SHARED_TESTS_ONLY
       "Build only shared tests that do not require a platform plugin" OFF)
option(CAMBRIDGE_VALIDATE_MACOS_DEPENDENCIES
       "Resolve the pinned macOS OBS and FFmpeg dependency baseline" OFF)
option(CAMBRIDGE_REQUIRE_AVAHI "Require Avahi mDNS support when configuring the plugin" OFF)

set(CAMBRIDGE_GIT_COMMIT "unknown" CACHE STRING
    "Source Git commit embedded in the module identity")

include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/CompilerWarnings.cmake")
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/Dependencies.cmake")
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/Platform.cmake")

cambridge_configure_dependencies()
cambridge_select_platform_sources(CAMBRIDGE_PLATFORM_SOURCES)

if(APPLE AND (CAMBRIDGE_BUILD_PLUGIN OR CAMBRIDGE_BUILD_TESTS))
    enable_language(OBJCXX)
endif()

set(CAMBRIDGE_SHARED_SOURCES
    src/control_protocol.cpp
    src/control_server.cpp
    src/decoder.cpp
    src/diagnostics.cpp
    src/diagnostics.hpp
    src/discovery_metadata.cpp
    src/cambridge_source.cpp
    src/media_receiver.cpp
    src/media_path.cpp
    src/module.cpp
    src/network_address_candidates.cpp
    src/platform/interfaces/native_decoder_adapter.hpp
    src/platform/interfaces/native_frame.hpp
    src/platform/interfaces/native_frame_importer.hpp
    src/platform/interfaces/discovery_advertiser.hpp
    src/platform/interfaces/source_properties.hpp
    src/platform/posix/posix_compat.cpp
    src/protocol_contract.generated.hpp
    src/receiver_constants.hpp
    src/renderer.cpp
    src/rtp.cpp
)

if(CAMBRIDGE_BUILD_PLUGIN)
    add_library(cambridge-obs-plugin MODULE
        ${CAMBRIDGE_SHARED_SOURCES}
        ${CAMBRIDGE_PLATFORM_SOURCES}
    )
    target_compile_features(cambridge-obs-plugin PRIVATE cxx_std_17)
    target_compile_definitions(cambridge-obs-plugin PRIVATE
        CAMBRIDGE_GIT_COMMIT="${CAMBRIDGE_GIT_COMMIT}"
        CAMBRIDGE_VERSION="${PROJECT_VERSION}"
        ${CAMBRIDGE_PLUGIN_DEFINITIONS}
    )
    cambridge_enable_warnings(cambridge-obs-plugin)
    target_include_directories(cambridge-obs-plugin SYSTEM PRIVATE
        ${CAMBRIDGE_PLUGIN_INCLUDE_DIRS}
    )
    target_link_directories(cambridge-obs-plugin PRIVATE
        ${CAMBRIDGE_PLUGIN_LIBRARY_DIRS}
    )
    target_link_libraries(cambridge-obs-plugin PRIVATE
        ${CAMBRIDGE_PLUGIN_LINK_LIBRARIES}
    )
    set_target_properties(cambridge-obs-plugin PROPERTIES
        PREFIX ""
        OUTPUT_NAME "cambridge-obs-plugin"
    )
    if(APPLE)
        set(CAMBRIDGE_VERSION "${PROJECT_VERSION}")
        configure_file(
            "${CMAKE_CURRENT_SOURCE_DIR}/cmake/macos/Info.plist.in"
            "${CMAKE_CURRENT_BINARY_DIR}/cambridge-obs-plugin-Info.plist"
            @ONLY
        )
        set_target_properties(cambridge-obs-plugin PROPERTIES
            MACOSX_BUNDLE_INFO_PLIST
                "${CMAKE_CURRENT_BINARY_DIR}/cambridge-obs-plugin-Info.plist"
            XCODE_ATTRIBUTE_GENERATE_INFOPLIST_FILE NO
        )
    endif()

    install(TARGETS cambridge-obs-plugin
        LIBRARY DESTINATION obs-plugins/cambridge-obs-plugin/bin/64bit
    )
endif()

if(CAMBRIDGE_BUILD_TESTS)
    include(CTest)
endif()

if(CAMBRIDGE_BUILD_TESTS AND BUILD_TESTING)
    function(cambridge_add_test target)
        add_executable(${target} ${ARGN})
        target_compile_features(${target} PRIVATE cxx_std_17)
        cambridge_enable_warnings(${target})
        add_test(NAME ${target} COMMAND ${target})
    endfunction()

    cambridge_add_test(cambridge-obs-plugin-tests
        tests/rtp_tests.cpp
        src/rtp.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-contract-tests
        tests/control_tests.cpp
        src/control_protocol.cpp
    )
    target_include_directories(cambridge-obs-plugin-contract-tests SYSTEM PRIVATE
        ${CAMBRIDGE_JSON_INCLUDE_DIRS}
    )
    target_link_directories(cambridge-obs-plugin-contract-tests PRIVATE
        ${CAMBRIDGE_JSON_LIBRARY_DIRS}
    )
    target_link_libraries(cambridge-obs-plugin-contract-tests PRIVATE
        ${CAMBRIDGE_JSON_LINK_LIBRARIES}
    )

    cambridge_add_test(cambridge-obs-plugin-address-tests
        tests/network_address_candidates_tests.cpp
        src/network_address_candidates.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-mailbox-tests
        tests/latest_frame_mailbox_tests.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-media-path-tests
        tests/media_path_tests.cpp
        src/media_path.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-decoder-policy-tests
        tests/decoder_policy_tests.cpp
        src/media_path.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-renderer-policy-tests
        tests/renderer_policy_tests.cpp
        src/media_path.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-posix-compat-tests
        tests/posix_compat_tests.cpp
        src/platform/posix/posix_compat.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-frame-lifetime-tests
        tests/frame_lifetime_tests.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-discovery-metadata-tests
        tests/discovery_metadata_tests.cpp
        src/discovery_metadata.cpp
        src/network_address_candidates.cpp
    )

    cambridge_add_test(cambridge-obs-plugin-diagnostics-tests
        tests/diagnostics_tests.cpp
        src/diagnostics.cpp
    )

    if(NOT CAMBRIDGE_BUILD_SHARED_TESTS_ONLY)
        cambridge_add_test(cambridge-obs-plugin-native-importer-policy-tests
            tests/native_importer_policy_tests.cpp
        )
    endif()

    if(APPLE)
        cambridge_add_test(cambridge-obs-plugin-bonjour-tests
            tests/bonjour_lifecycle_tests.cpp
            src/platform/macos/discovery_advertiser_macos.cpp
            src/discovery_metadata.cpp
            src/network_address_candidates.cpp
        )
        target_link_libraries(cambridge-obs-plugin-bonjour-tests PRIVATE
            ${CAMBRIDGE_DNS_SD_LINK_LIBRARIES}
        )

        find_program(CAMBRIDGE_FFMPEG_EXECUTABLE NAMES ffmpeg REQUIRED)
        set(CAMBRIDGE_NATIVE_TEST_WIDTH 16)
        set(CAMBRIDGE_NATIVE_TEST_HEIGHT 16)
        set(CAMBRIDGE_NATIVE_TEST_FPS 1)
        set(CAMBRIDGE_NATIVE_TEST_SAMPLE
            "${CMAKE_CURRENT_BINARY_DIR}/cambridge-native-decoder-sample.h264")
        add_custom_command(
            OUTPUT "${CAMBRIDGE_NATIVE_TEST_SAMPLE}"
            COMMAND "${CAMBRIDGE_FFMPEG_EXECUTABLE}"
                -hide_banner
                -loglevel error
                -nostdin
                -f lavfi
                -i "testsrc2=size=${CAMBRIDGE_NATIVE_TEST_WIDTH}x${CAMBRIDGE_NATIVE_TEST_HEIGHT}:rate=${CAMBRIDGE_NATIVE_TEST_FPS}"
                -frames:v 1
                -an
                -c:v libx264
                -pix_fmt yuv420p
                -preset ultrafast
                -tune zerolatency
                -x264-params repeat-headers=1
                -f h264
                "${CAMBRIDGE_NATIVE_TEST_SAMPLE}"
            VERBATIM
        )
        add_custom_target(cambridge-native-decoder-sample
            DEPENDS "${CAMBRIDGE_NATIVE_TEST_SAMPLE}"
        )

        cambridge_add_test(cambridge-obs-plugin-native-decoder-tests
            tests/native_decoder_macos_tests.mm
            src/platform/macos/native_decoder_macos.mm
        )
        add_dependencies(cambridge-obs-plugin-native-decoder-tests
            cambridge-native-decoder-sample
        )
        target_compile_definitions(cambridge-obs-plugin-native-decoder-tests PRIVATE
            CAMBRIDGE_NATIVE_TEST_SAMPLE_PATH="${CAMBRIDGE_NATIVE_TEST_SAMPLE}"
            CAMBRIDGE_NATIVE_TEST_WIDTH=${CAMBRIDGE_NATIVE_TEST_WIDTH}
            CAMBRIDGE_NATIVE_TEST_HEIGHT=${CAMBRIDGE_NATIVE_TEST_HEIGHT}
        )
        target_include_directories(cambridge-obs-plugin-native-decoder-tests SYSTEM PRIVATE
            ${CAMBRIDGE_FFMPEG_INCLUDE_DIRS}
        )
        target_link_directories(cambridge-obs-plugin-native-decoder-tests PRIVATE
            ${CAMBRIDGE_FFMPEG_LIBRARY_DIRS}
        )
        target_link_libraries(cambridge-obs-plugin-native-decoder-tests PRIVATE
            ${CAMBRIDGE_NATIVE_DECODER_LINK_LIBRARIES}
        )
    endif()
endif()
