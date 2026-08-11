#include "../interfaces/source_properties.hpp"

#include "../../cambridge_source.hpp"
#include "../../receiver_constants.hpp"

namespace cambridge {
namespace {

constexpr char kPropertyDrmDevice[] = "drm_device";

} // namespace

void add_platform_source_properties(obs_properties_t *advanced_properties)
{
    obs_properties_add_path(advanced_properties, kPropertyDrmDevice, "DRM render device", OBS_PATH_FILE,
                             "DRM device (*)", receiver::kDefaultDrmDevice);
}

void read_platform_source_settings(obs_data_t *settings, SourceConfig &config)
{
    const char *value = obs_data_get_string(settings, kPropertyDrmDevice);
    if (value && value[0] != '\0') {
        config.drm_device = value;
    }
}

} // namespace cambridge
