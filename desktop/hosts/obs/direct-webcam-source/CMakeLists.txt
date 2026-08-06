cmake_minimum_required(VERSION 3.24)

project(direct-webcam-source VERSION 0.2.0 LANGUAGES CXX)

find_package(PkgConfig REQUIRED)
pkg_check_modules(OBS REQUIRED IMPORTED_TARGET libobs)
pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET libavcodec libavutil libswscale)
pkg_check_modules(VA REQUIRED IMPORTED_TARGET libva libva-drm)
pkg_check_modules(DRM REQUIRED IMPORTED_TARGET libdrm)
pkg_check_modules(JANSSON REQUIRED IMPORTED_TARGET jansson)

set(DIRECT_WEBCAM_GIT_COMMIT "unknown" CACHE STRING "Source Git commit embedded in the module identity")

add_library(direct-webcam-source MODULE
    src/control_protocol.cpp
    src/control_server.cpp
    src/decoder.cpp
    src/direct_webcam_source.cpp
    src/media_receiver.cpp
    src/module.cpp
    src/renderer.cpp
    src/rtp.cpp
)
target_compile_features(direct-webcam-source PRIVATE cxx_std_17)
target_compile_definitions(direct-webcam-source PRIVATE
    DIRECT_WEBCAM_GIT_COMMIT="${DIRECT_WEBCAM_GIT_COMMIT}"
    DIRECT_WEBCAM_VERSION="${PROJECT_VERSION}"
)
target_compile_options(direct-webcam-source PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror)
target_include_directories(direct-webcam-source PRIVATE
    ${OBS_INCLUDE_DIRS}
    ${FFMPEG_INCLUDE_DIRS}
    ${VA_INCLUDE_DIRS}
    ${DRM_INCLUDE_DIRS}
    ${JANSSON_INCLUDE_DIRS}
)
target_link_directories(direct-webcam-source PRIVATE
    ${OBS_LIBRARY_DIRS}
    ${FFMPEG_LIBRARY_DIRS}
    ${VA_LIBRARY_DIRS}
    ${DRM_LIBRARY_DIRS}
    ${JANSSON_LIBRARY_DIRS}
)
target_link_libraries(direct-webcam-source PRIVATE
    ${OBS_LIBRARIES}
    ${FFMPEG_LIBRARIES}
    ${VA_LIBRARIES}
    ${DRM_LIBRARIES}
    ${JANSSON_LIBRARIES}
)
set_target_properties(direct-webcam-source PROPERTIES
    PREFIX ""
    OUTPUT_NAME "direct-webcam-source"
)

include(CTest)
if(BUILD_TESTING)
    add_executable(direct-webcam-source-tests
        tests/rtp_tests.cpp
        src/rtp.cpp
    )
    target_compile_features(direct-webcam-source-tests PRIVATE cxx_std_17)
    target_compile_options(direct-webcam-source-tests PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror)
    add_test(NAME direct-webcam-source-tests COMMAND direct-webcam-source-tests)

    add_executable(direct-webcam-source-contract-tests
        tests/control_tests.cpp
        src/control_protocol.cpp
    )
    target_compile_features(direct-webcam-source-contract-tests PRIVATE cxx_std_17)
    target_compile_options(direct-webcam-source-contract-tests PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Werror
    )
    target_include_directories(direct-webcam-source-contract-tests PRIVATE ${JANSSON_INCLUDE_DIRS})
    target_link_directories(direct-webcam-source-contract-tests PRIVATE ${JANSSON_LIBRARY_DIRS})
    target_link_libraries(direct-webcam-source-contract-tests PRIVATE ${JANSSON_LIBRARIES})
    add_test(NAME direct-webcam-source-contract-tests COMMAND direct-webcam-source-contract-tests)
endif()

install(TARGETS direct-webcam-source
    LIBRARY DESTINATION obs-plugins/direct-webcam-source/bin/64bit
)
