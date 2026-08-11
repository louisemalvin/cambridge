#include "cambridge_source.hpp"
#include "protocol_contract.generated.hpp"

extern "C" {
#include <obs/obs-module.h>
}

OBS_DECLARE_MODULE()
OBS_MODULE_AUTHOR("Louise Tanaka")

namespace {

#ifndef CAMBRIDGE_VERSION
#define CAMBRIDGE_VERSION "unknown"
#endif
#ifndef CAMBRIDGE_GIT_COMMIT
#define CAMBRIDGE_GIT_COMMIT "unknown"
#endif

constexpr char kSourceId[] = "cambridge_android_source";
constexpr char kLegacySourceId[] = "direct_android_rtp_webcam";
constexpr char kSourceName[] = "CamBridge";

const char *source_name(void *)
{
    return kSourceName;
}

} // namespace

bool obs_module_load(void)
{
    obs_source_info info{};
    info.id = kSourceId;
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_DO_NOT_DUPLICATE;
    info.get_name = source_name;
    info.create = cambridge::source_create;
    info.destroy = cambridge::source_destroy;
    info.get_width = cambridge::source_get_width;
    info.get_height = cambridge::source_get_height;
    info.get_defaults = cambridge::source_get_defaults;
    info.get_properties = cambridge::source_get_properties;
    info.update = cambridge::source_update;
    info.video_render = cambridge::source_video_render;
    info.video_tick = cambridge::source_video_tick;
    obs_register_source(&info);

    obs_source_info legacy_info = info;
    legacy_info.id = kLegacySourceId;
    legacy_info.output_flags |= OBS_SOURCE_DEPRECATED;
    obs_register_source(&legacy_info);

    blog(LOG_INFO, "[cambridge-obs] loaded module=cambridge-obs-plugin version=%s commit=%s protocol=%u",
         CAMBRIDGE_VERSION, CAMBRIDGE_GIT_COMMIT,
         static_cast<unsigned int>(cambridge::contract::kProtocolVersion));
    return true;
}

void obs_module_unload(void)
{
    blog(LOG_INFO, "[cambridge-obs] unloaded");
}
