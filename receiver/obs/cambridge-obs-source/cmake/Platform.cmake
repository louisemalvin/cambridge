include_guard(GLOBAL)

function(cambridge_select_platform_sources output_variable)
    if(NOT CAMBRIDGE_BUILD_PLUGIN)
        set(${output_variable} "" PARENT_SCOPE)
        return()
    endif()

    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        set(platform_sources
            src/platform/linux/native_decoder_linux.cpp
            src/platform/linux/discovery_advertiser_linux.cpp
            src/platform/linux/native_frame_linux.hpp
            src/platform/linux/native_frame_importer_linux.cpp
            src/platform/linux/source_properties_linux.cpp
        )
    elseif(APPLE)
        set(platform_sources
            src/platform/macos/native_decoder_macos.mm
            src/platform/macos/discovery_advertiser_macos.cpp
            src/platform/macos/native_frame_macos.hpp
            src/platform/macos/native_frame_importer_macos.mm
            src/platform/macos/source_properties_macos.cpp
        )
        foreach(platform_source IN LISTS platform_sources)
            if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${platform_source}")
                message(FATAL_ERROR
                    "macOS plugin sources are not available until their platform stage: "
                    "${platform_source}")
            endif()
        endforeach()
    else()
        message(FATAL_ERROR "CamBridge supports only Linux and macOS plugin builds")
    endif()

    set(${output_variable} "${platform_sources}" PARENT_SCOPE)
endfunction()
