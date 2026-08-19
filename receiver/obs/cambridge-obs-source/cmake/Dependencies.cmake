include_guard(GLOBAL)

function(cambridge_read_buildspec)
    set(buildspec_file "${CMAKE_CURRENT_SOURCE_DIR}/buildspec.json")
    file(READ "${buildspec_file}" buildspec_json)
    string(JSON obs_version GET "${buildspec_json}" baseline obsStudio)
    string(JSON ffmpeg_version GET "${buildspec_json}" baseline ffmpeg)
    string(JSON deployment_target GET "${buildspec_json}" baseline macosDeploymentTarget)
    string(JSON linux_obs_version GET "${buildspec_json}"
        linuxCompatibility minimumObsStudio)
    string(JSON linux_avcodec_abi GET "${buildspec_json}"
        linuxCompatibility minimumFfmpegAbis libavcodec)
    string(JSON linux_avutil_abi GET "${buildspec_json}"
        linuxCompatibility minimumFfmpegAbis libavutil)
    string(JSON linux_swscale_abi GET "${buildspec_json}"
        linuxCompatibility minimumFfmpegAbis libswscale)
    set(CAMBRIDGE_PINNED_OBS_VERSION "${obs_version}" CACHE INTERNAL
        "OBS baseline recorded in buildspec.json")
    set(CAMBRIDGE_PINNED_FFMPEG_VERSION "${ffmpeg_version}" CACHE INTERNAL
        "FFmpeg baseline recorded in buildspec.json")
    set(CAMBRIDGE_MACOS_DEPLOYMENT_TARGET "${deployment_target}" CACHE INTERNAL
        "macOS deployment target recorded in buildspec.json")
    set(CAMBRIDGE_LINUX_MIN_OBS_VERSION "${linux_obs_version}" CACHE INTERNAL
        "Minimum Linux OBS version recorded in buildspec.json")
    set(CAMBRIDGE_LINUX_MIN_AVCODEC_ABI "${linux_avcodec_abi}" CACHE INTERNAL
        "Minimum Linux libavcodec ABI recorded in buildspec.json")
    set(CAMBRIDGE_LINUX_MIN_AVUTIL_ABI "${linux_avutil_abi}" CACHE INTERNAL
        "Minimum Linux libavutil ABI recorded in buildspec.json")
    set(CAMBRIDGE_LINUX_MIN_SWSCALE_ABI "${linux_swscale_abi}" CACHE INTERNAL
        "Minimum Linux libswscale ABI recorded in buildspec.json")
endfunction()

function(cambridge_configure_dependencies)
    cambridge_read_buildspec()

    set(CAMBRIDGE_PLUGIN_INCLUDE_DIRS)
    set(CAMBRIDGE_PLUGIN_LIBRARY_DIRS)
    set(CAMBRIDGE_PLUGIN_LINK_LIBRARIES)
    set(CAMBRIDGE_PLUGIN_DEFINITIONS)
    set(CAMBRIDGE_JSON_INCLUDE_DIRS)
    set(CAMBRIDGE_JSON_LIBRARY_DIRS)
    set(CAMBRIDGE_JSON_LINK_LIBRARIES)
    set(CAMBRIDGE_DNS_SD_LINK_LIBRARIES)
    set(CAMBRIDGE_NATIVE_DECODER_LINK_LIBRARIES)
    set(CAMBRIDGE_DNS_SD_LIBRARY_NAMES dns_services dns_sd)

    if(APPLE AND CAMBRIDGE_BUILD_TESTS)
        find_library(CAMBRIDGE_DNS_SD_TEST_LIBRARY
            NAMES ${CAMBRIDGE_DNS_SD_LIBRARY_NAMES} REQUIRED)
        list(APPEND CAMBRIDGE_DNS_SD_LINK_LIBRARIES ${CAMBRIDGE_DNS_SD_TEST_LIBRARY})
    endif()

    if(CAMBRIDGE_BUILD_TESTS OR CAMBRIDGE_BUILD_PLUGIN)
        find_package(PkgConfig REQUIRED)
        pkg_check_modules(CAMBRIDGE_JANSSON REQUIRED jansson)
        list(APPEND CAMBRIDGE_JSON_INCLUDE_DIRS ${CAMBRIDGE_JANSSON_INCLUDE_DIRS})
        list(APPEND CAMBRIDGE_JSON_LIBRARY_DIRS ${CAMBRIDGE_JANSSON_LIBRARY_DIRS})
        list(APPEND CAMBRIDGE_JSON_LINK_LIBRARIES ${CAMBRIDGE_JANSSON_LIBRARIES})
    endif()

    if(CAMBRIDGE_BUILD_PLUGIN OR
       (APPLE AND CAMBRIDGE_BUILD_TESTS) OR
       (APPLE AND CAMBRIDGE_VALIDATE_MACOS_DEPENDENCIES))
        find_package(PkgConfig REQUIRED)
        if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
            set(cambridge_obs_requirement
                "libobs>=${CAMBRIDGE_LINUX_MIN_OBS_VERSION}")
            set(cambridge_ffmpeg_requirements
                "libavcodec>=${CAMBRIDGE_LINUX_MIN_AVCODEC_ABI}"
                "libavutil>=${CAMBRIDGE_LINUX_MIN_AVUTIL_ABI}"
                "libswscale>=${CAMBRIDGE_LINUX_MIN_SWSCALE_ABI}")
            pkg_check_modules(CAMBRIDGE_OBS REQUIRED ${cambridge_obs_requirement})
            pkg_check_modules(CAMBRIDGE_FFMPEG REQUIRED ${cambridge_ffmpeg_requirements})
            message(STATUS
                "Using Linux OBS ${CAMBRIDGE_OBS_VERSION} and FFmpeg ABIs "
                "${CAMBRIDGE_FFMPEG_libavcodec_VERSION}/"
                "${CAMBRIDGE_FFMPEG_libavutil_VERSION}/"
                "${CAMBRIDGE_FFMPEG_libswscale_VERSION}")
        else()
            pkg_check_modules(CAMBRIDGE_OBS REQUIRED libobs)
            pkg_check_modules(CAMBRIDGE_FFMPEG REQUIRED
                libavcodec libavutil libswscale)
        endif()

        if(APPLE AND DEFINED ENV{CAMBRIDGE_OBS_PREFIX})
            list(APPEND CAMBRIDGE_OBS_INCLUDE_DIRS
                "$ENV{CAMBRIDGE_OBS_PREFIX}/Frameworks/libobs.framework/Headers"
            )
        endif()

        if(APPLE AND CAMBRIDGE_VALIDATE_MACOS_DEPENDENCIES)
            set(expected_obs_version "${CAMBRIDGE_PINNED_OBS_VERSION}")
            set(expected_ffmpeg_version "${CAMBRIDGE_PINNED_FFMPEG_VERSION}")
            if(DEFINED CAMBRIDGE_OBS_VERSION AND
               NOT "${CAMBRIDGE_OBS_VERSION}" STREQUAL "${expected_obs_version}")
                message(FATAL_ERROR
                    "OBS dependency is ${CAMBRIDGE_OBS_VERSION}; expected ${expected_obs_version}")
            endif()

            set(resolved_ffmpeg_version "${CAMBRIDGE_FFMPEG_VERSION}")
            if(NOT resolved_ffmpeg_version)
                set(ffmpeg_version_header)
                foreach(ffmpeg_include_dir IN LISTS CAMBRIDGE_FFMPEG_INCLUDE_DIRS)
                    set(candidate_ffmpeg_version_header
                        "${ffmpeg_include_dir}/libavutil/ffversion.h")
                    if(EXISTS "${candidate_ffmpeg_version_header}")
                        set(ffmpeg_version_header "${candidate_ffmpeg_version_header}")
                        break()
                    endif()
                endforeach()
                if(NOT ffmpeg_version_header)
                    message(FATAL_ERROR
                        "FFmpeg version header was not found in the selected include paths")
                endif()
                file(READ "${ffmpeg_version_header}" ffmpeg_version_header_contents)
                string(REGEX MATCH
                    "#define[ \t]+FFMPEG_VERSION[ \t]+\\\"([^\\\"]+)\\\""
                    ffmpeg_version_match "${ffmpeg_version_header_contents}")
                if(NOT ffmpeg_version_match)
                    message(FATAL_ERROR
                        "FFmpeg version header does not declare FFMPEG_VERSION: "
                        "${ffmpeg_version_header}")
                endif()
                set(resolved_ffmpeg_version "${CMAKE_MATCH_1}")
            endif()
            if(NOT "${resolved_ffmpeg_version}" STREQUAL "${expected_ffmpeg_version}")
                message(FATAL_ERROR
                    "FFmpeg dependency is ${resolved_ffmpeg_version}; "
                    "expected ${expected_ffmpeg_version}")
            endif()
            set(CAMBRIDGE_OBS_VERSION "${expected_obs_version}" CACHE STRING
                "Resolved OBS baseline version")
            set(CAMBRIDGE_FFMPEG_VERSION "${resolved_ffmpeg_version}" CACHE STRING
                "Resolved FFmpeg baseline version")
            message(STATUS "Resolved pinned OBS ${expected_obs_version}")
            message(STATUS "Resolved pinned FFmpeg ${resolved_ffmpeg_version}")
        endif()
    endif()

    if(CAMBRIDGE_BUILD_PLUGIN)
        list(APPEND CAMBRIDGE_PLUGIN_INCLUDE_DIRS
            ${CAMBRIDGE_OBS_INCLUDE_DIRS}
            ${CAMBRIDGE_FFMPEG_INCLUDE_DIRS}
            ${CAMBRIDGE_JSON_INCLUDE_DIRS}
        )
        list(APPEND CAMBRIDGE_PLUGIN_LIBRARY_DIRS
            ${CAMBRIDGE_OBS_LIBRARY_DIRS}
            ${CAMBRIDGE_FFMPEG_LIBRARY_DIRS}
            ${CAMBRIDGE_JSON_LIBRARY_DIRS}
        )
        if(APPLE AND DEFINED ENV{CAMBRIDGE_OBS_PREFIX})
            set(cambridge_obs_framework_binary
                "$ENV{CAMBRIDGE_OBS_PREFIX}/Frameworks/libobs.framework/Versions/A/libobs")
            if(NOT EXISTS "${cambridge_obs_framework_binary}")
                message(FATAL_ERROR
                    "Pinned OBS framework binary was not found: ${cambridge_obs_framework_binary}")
            endif()
            list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES
                "${cambridge_obs_framework_binary}")
        else()
            list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES ${CAMBRIDGE_OBS_LIBRARIES})
        endif()
        if(APPLE AND DEFINED ENV{CAMBRIDGE_JANSSON_PREFIX})
            set(cambridge_jansson_static_library
                "$ENV{CAMBRIDGE_JANSSON_PREFIX}/lib/libjansson.a")
            if(NOT EXISTS "${cambridge_jansson_static_library}")
                message(FATAL_ERROR
                    "Pinned static Jansson library was not found: "
                    "${cambridge_jansson_static_library}")
            endif()
            list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES
                "${cambridge_jansson_static_library}")
        else()
            list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES ${CAMBRIDGE_JANSSON_LIBRARIES})
        endif()
        list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES
            ${CAMBRIDGE_FFMPEG_LIBRARIES}
        )

        if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
            pkg_check_modules(CAMBRIDGE_VA REQUIRED libva libva-drm)
            pkg_check_modules(CAMBRIDGE_DRM REQUIRED libdrm)
            pkg_check_modules(CAMBRIDGE_AVAHI QUIET avahi-client)
            if(CAMBRIDGE_REQUIRE_AVAHI AND NOT CAMBRIDGE_AVAHI_FOUND)
                message(FATAL_ERROR "Avahi development files are required for this build")
            endif()
            if(CAMBRIDGE_AVAHI_FOUND)
                list(APPEND CAMBRIDGE_PLUGIN_DEFINITIONS CAMBRIDGE_HAS_AVAHI=1)
                list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES ${CAMBRIDGE_AVAHI_LIBRARIES})
                list(APPEND CAMBRIDGE_PLUGIN_INCLUDE_DIRS ${CAMBRIDGE_AVAHI_INCLUDE_DIRS})
                list(APPEND CAMBRIDGE_PLUGIN_LIBRARY_DIRS ${CAMBRIDGE_AVAHI_LIBRARY_DIRS})
                message(STATUS "mDNS advertisement enabled through Avahi")
            else()
                message(STATUS
                    "mDNS advertisement disabled: avahi-client development files were not found")
            endif()
            list(APPEND CAMBRIDGE_PLUGIN_INCLUDE_DIRS
                ${CAMBRIDGE_VA_INCLUDE_DIRS}
                ${CAMBRIDGE_DRM_INCLUDE_DIRS}
            )
            list(APPEND CAMBRIDGE_PLUGIN_LIBRARY_DIRS
                ${CAMBRIDGE_VA_LIBRARY_DIRS}
                ${CAMBRIDGE_DRM_LIBRARY_DIRS}
            )
            list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES
                ${CAMBRIDGE_VA_LIBRARIES}
                ${CAMBRIDGE_DRM_LIBRARIES}
            )
        elseif(APPLE)
            find_library(CAMBRIDGE_VIDEOTOOLBOX VideoToolbox REQUIRED)
            find_library(CAMBRIDGE_COREVIDEO CoreVideo REQUIRED)
            find_library(CAMBRIDGE_IOSURFACE IOSurface REQUIRED)
            find_library(CAMBRIDGE_METAL Metal REQUIRED)
            find_library(CAMBRIDGE_FOUNDATION Foundation REQUIRED)
            find_library(CAMBRIDGE_DNS_SD
                NAMES ${CAMBRIDGE_DNS_SD_LIBRARY_NAMES} REQUIRED)
            list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES
                ${CAMBRIDGE_VIDEOTOOLBOX}
                ${CAMBRIDGE_COREVIDEO}
                ${CAMBRIDGE_IOSURFACE}
                ${CAMBRIDGE_METAL}
                ${CAMBRIDGE_FOUNDATION}
                ${CAMBRIDGE_DNS_SD}
            )
        else()
            message(FATAL_ERROR "CamBridge supports only Linux and macOS plugin builds")
        endif()
    endif()

    if(APPLE AND CAMBRIDGE_BUILD_TESTS)
        find_library(CAMBRIDGE_NATIVE_VIDEOTOOLBOX VideoToolbox REQUIRED)
        find_library(CAMBRIDGE_NATIVE_COREVIDEO CoreVideo REQUIRED)
        find_library(CAMBRIDGE_NATIVE_IOSURFACE IOSurface REQUIRED)
        find_library(CAMBRIDGE_NATIVE_FOUNDATION Foundation REQUIRED)
        list(APPEND CAMBRIDGE_NATIVE_DECODER_LINK_LIBRARIES
            ${CAMBRIDGE_FFMPEG_LIBRARIES}
            ${CAMBRIDGE_NATIVE_VIDEOTOOLBOX}
            ${CAMBRIDGE_NATIVE_COREVIDEO}
            ${CAMBRIDGE_NATIVE_IOSURFACE}
            ${CAMBRIDGE_NATIVE_FOUNDATION}
        )
    endif()

    set(CAMBRIDGE_PLUGIN_INCLUDE_DIRS "${CAMBRIDGE_PLUGIN_INCLUDE_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_PLUGIN_LIBRARY_DIRS "${CAMBRIDGE_PLUGIN_LIBRARY_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_PLUGIN_LINK_LIBRARIES "${CAMBRIDGE_PLUGIN_LINK_LIBRARIES}" PARENT_SCOPE)
    set(CAMBRIDGE_PLUGIN_DEFINITIONS "${CAMBRIDGE_PLUGIN_DEFINITIONS}" PARENT_SCOPE)
    set(CAMBRIDGE_JSON_INCLUDE_DIRS "${CAMBRIDGE_JSON_INCLUDE_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_JSON_LIBRARY_DIRS "${CAMBRIDGE_JSON_LIBRARY_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_JSON_LINK_LIBRARIES "${CAMBRIDGE_JSON_LINK_LIBRARIES}" PARENT_SCOPE)
    set(CAMBRIDGE_DNS_SD_LINK_LIBRARIES "${CAMBRIDGE_DNS_SD_LINK_LIBRARIES}" PARENT_SCOPE)
    set(CAMBRIDGE_NATIVE_DECODER_LINK_LIBRARIES
        "${CAMBRIDGE_NATIVE_DECODER_LINK_LIBRARIES}" PARENT_SCOPE)
    set(CAMBRIDGE_OBS_INCLUDE_DIRS "${CAMBRIDGE_OBS_INCLUDE_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_FFMPEG_INCLUDE_DIRS "${CAMBRIDGE_FFMPEG_INCLUDE_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_FFMPEG_LIBRARY_DIRS "${CAMBRIDGE_FFMPEG_LIBRARY_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_FFMPEG_LIBRARIES "${CAMBRIDGE_FFMPEG_LIBRARIES}" PARENT_SCOPE)
endfunction()
