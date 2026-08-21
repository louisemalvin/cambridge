#include "gstreamer_runtime.hpp"

#include <gst/gst.h>

#include <array>
#include <mutex>

namespace cambridge {
namespace {

constexpr std::array<const char *, 12> kRequiredFactories = {
    "rtpbin",
    "rtph264depay",
    "h264parse",
    "appsink",
    "udpsrc",
    "udpsink",
    "rtprtxreceive",
    "rtphdrexttwcc",
    "rtprtxsend",
    "rtph264pay",
    "appsrc",
    "rtpgccbwe",
};

constexpr std::array<const char *, 6> kRequiredPlugins = {
    "rtpmanager",
    "rtp",
    "udp",
    "app",
    "rsrtp",
    "videoparsersbad",
};

std::once_flag gstreamer_initialization_once;
bool gstreamer_initialized = false;
std::string gstreamer_initialization_error;

void initialize_once()
{
    GError *initialization_error = nullptr;
    if (!gst_init_check(nullptr, nullptr, &initialization_error)) {
        gstreamer_initialization_error = initialization_error && initialization_error->message
                                              ? initialization_error->message
                                              : "gst_init_check failed";
        if (initialization_error) {
            g_error_free(initialization_error);
        }
        return;
    }
    for (const char *factory_name : kRequiredFactories) {
        GstElementFactory *factory = gst_element_factory_find(factory_name);
        if (!factory) {
            gstreamer_initialization_error = "required GStreamer factory is unavailable: ";
            gstreamer_initialization_error += factory_name;
            return;
        }
        gst_object_unref(factory);
    }
    GstRegistry *registry = gst_registry_get();
    for (const char *plugin_name : kRequiredPlugins) {
        GstPlugin *plugin = gst_registry_find_plugin(registry, plugin_name);
        if (!plugin) {
            gstreamer_initialization_error = "required GStreamer plugin is unavailable: ";
            gstreamer_initialization_error += plugin_name;
            return;
        }
        gst_object_unref(plugin);
    }
    gstreamer_initialized = true;
}

} // namespace

bool initialize_gstreamer(std::string &error)
{
    std::call_once(gstreamer_initialization_once, initialize_once);
    if (!gstreamer_initialized) {
        error = gstreamer_initialization_error.empty()
                    ? "GStreamer initialization failed"
                    : gstreamer_initialization_error;
        return false;
    }
    return true;
}

} // namespace cambridge
