#include "direct_webcam_source.hpp"
#include "protocol_contract.hpp"

extern "C" {
#include <obs/obs-module.h>
}

OBS_DECLARE_MODULE()
OBS_MODULE_AUTHOR("Mobile Webcam contributors")

namespace {

#ifndef DIRECT_WEBCAM_VERSION
#define DIRECT_WEBCAM_VERSION "unknown"
#endif
#ifndef DIRECT_WEBCAM_GIT_COMMIT
#define DIRECT_WEBCAM_GIT_COMMIT "unknown"
#endif

constexpr char kSourceId[] = "direct_android_rtp_webcam";
constexpr char kSourceName[] = "Phone Webcam";

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
    info.create = direct_webcam::source_create;
    info.destroy = direct_webcam::source_destroy;
    info.get_width = direct_webcam::source_get_width;
    info.get_height = direct_webcam::source_get_height;
    info.get_defaults = direct_webcam::source_get_defaults;
    info.get_properties = direct_webcam::source_get_properties;
    info.update = direct_webcam::source_update;
    info.video_render = direct_webcam::source_video_render;
    info.video_tick = direct_webcam::source_video_tick;
    obs_register_source(&info);
    blog(LOG_INFO, "[direct-webcam] loaded module=direct-webcam-source version=%s commit=%s protocol=%u",
         DIRECT_WEBCAM_VERSION, DIRECT_WEBCAM_GIT_COMMIT,
         static_cast<unsigned int>(direct_webcam::contract::kProtocolVersion));
    return true;
}

void obs_module_unload(void)
{
    blog(LOG_INFO, "[direct-webcam] unloaded");
}
