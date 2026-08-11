#pragma once

extern "C" {
#if defined(__APPLE__)
#include <obs-data.h>
#include <obs-properties.h>
#else
#include <obs/obs-data.h>
#include <obs/obs-properties.h>
#endif
}

namespace cambridge {

struct SourceConfig;

void add_platform_source_properties(obs_properties_t *advanced_properties);
void read_platform_source_settings(obs_data_t *settings, SourceConfig &config);

} // namespace cambridge
