include_guard(GLOBAL)

function(cambridge_read_buildspec)
    set(buildspec_file "${CMAKE_CURRENT_SOURCE_DIR}/buildspec.json")
    file(READ "${buildspec_file}" buildspec_json)
    string(JSON obs_version GET "${buildspec_json}" baseline obsStudio)
    string(JSON ffmpeg_version GET "${buildspec_json}" baseline ffmpeg)
    string(JSON deployment_target GET "${buildspec_json}" baseline macosDeploymentTarget)
    set(CAMBRIDGE_PINNED_OBS_VERSION "${obs_version}" CACHE INTERNAL
        "OBS baseline recorded in buildspec.json")
    set(CAMBRIDGE_PINNED_FFMPEG_VERSION "${ffmpeg_version}" CACHE INTERNAL
        "FFmpeg baseline recorded in buildspec.json")
    set(CAMBRIDGE_MACOS_DEPLOYMENT_TARGET "${deployment_target}" CACHE INTERNAL
        "macOS deployment target recorded in buildspec.json")
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

    if(APPLE AND CAMBRIDGE_BUILD_TESTS)
        find_library(CAMBRIDGE_DNS_SD_TEST_LIBRARY dns_sd REQUIRED)
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
       (APPLE AND CAMBRIDGE_VALIDATE_MACOS_DEPENDENCIES))
        find_package(PkgConfig REQUIRED)
        pkg_check_modules(CAMBRIDGE_OBS REQUIRED libobs)
        pkg_check_modules(CAMBRIDGE_FFMPEG REQUIRED
            libavcodec libavutil libswscale)

        if(APPLE AND CAMBRIDGE_VALIDATE_MACOS_DEPENDENCIES)
            set(expected_obs_version "${CAMBRIDGE_PINNED_OBS_VERSION}")
            set(expected_ffmpeg_version "${CAMBRIDGE_PINNED_FFMPEG_VERSION}")
            if(DEFINED CAMBRIDGE_OBS_VERSION AND
               NOT CAMBRIDGE_OBS_VERSION STREQUAL expected_obs_version)
                message(FATAL_ERROR
                    "OBS dependency is ${CAMBRIDGE_OBS_VERSION}; expected ${expected_obs_version}")
            endif()
            if(DEFINED CAMBRIDGE_FFMPEG_VERSION AND
               NOT CAMBRIDGE_FFMPEG_VERSION STREQUAL expected_ffmpeg_version)
                message(FATAL_ERROR
                    "FFmpeg dependency is ${CAMBRIDGE_FFMPEG_VERSION}; expected ${expected_ffmpeg_version}")
            endif()
            set(CAMBRIDGE_OBS_VERSION "${expected_obs_version}" CACHE STRING
                "Resolved OBS baseline version")
            set(CAMBRIDGE_FFMPEG_VERSION "${expected_ffmpeg_version}" CACHE STRING
                "Resolved FFmpeg baseline version")
            message(STATUS "Resolved pinned OBS ${expected_obs_version}")
            message(STATUS "Resolved pinned FFmpeg ${expected_ffmpeg_version}")
        endif()
    endif()

    if(CAMBRIDGE_BUILD_PLUGIN)
        list(APPEND CAMBRIDGE_PLUGIN_INCLUDE_DIRS
            ${CAMBRIDGE_OBS_INCLUDE_DIRS}
            ${CAMBRIDGE_FFMPEG_INCLUDE_DIRS}
        )
        list(APPEND CAMBRIDGE_PLUGIN_LIBRARY_DIRS
            ${CAMBRIDGE_OBS_LIBRARY_DIRS}
            ${CAMBRIDGE_FFMPEG_LIBRARY_DIRS}
        )
        list(APPEND CAMBRIDGE_PLUGIN_LINK_LIBRARIES
            ${CAMBRIDGE_OBS_LIBRARIES}
            ${CAMBRIDGE_FFMPEG_LIBRARIES}
            ${CAMBRIDGE_JANSSON_LIBRARIES}
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
            find_library(CAMBRIDGE_DNS_SD dns_sd REQUIRED)
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

    set(CAMBRIDGE_PLUGIN_INCLUDE_DIRS "${CAMBRIDGE_PLUGIN_INCLUDE_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_PLUGIN_LIBRARY_DIRS "${CAMBRIDGE_PLUGIN_LIBRARY_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_PLUGIN_LINK_LIBRARIES "${CAMBRIDGE_PLUGIN_LINK_LIBRARIES}" PARENT_SCOPE)
    set(CAMBRIDGE_PLUGIN_DEFINITIONS "${CAMBRIDGE_PLUGIN_DEFINITIONS}" PARENT_SCOPE)
    set(CAMBRIDGE_JSON_INCLUDE_DIRS "${CAMBRIDGE_JSON_INCLUDE_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_JSON_LIBRARY_DIRS "${CAMBRIDGE_JSON_LIBRARY_DIRS}" PARENT_SCOPE)
    set(CAMBRIDGE_JSON_LINK_LIBRARIES "${CAMBRIDGE_JSON_LINK_LIBRARIES}" PARENT_SCOPE)
    set(CAMBRIDGE_DNS_SD_LINK_LIBRARIES "${CAMBRIDGE_DNS_SD_LINK_LIBRARIES}" PARENT_SCOPE)
endfunction()
