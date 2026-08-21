#include "gstreamer_sender.hpp"

#include "protocol_contract.generated.hpp"

#include <jni.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>

namespace {

class NativeTransport {
public:
    NativeTransport(JNIEnv *environment, jobject transport)
        : environment_vm_(nullptr), transport_(environment->NewGlobalRef(transport)),
          sender_(GStreamerSender::Callbacks{
              [this](std::uint32_t bitrate) { invoke_bitrate_callback(bitrate); },
              [this] { invoke_keyframe_callback(); },
              [this](const std::string &message) { invoke_error_callback(message); },
          })
    {
        environment->GetJavaVM(&environment_vm_);
    }

    ~NativeTransport()
    {
        sender_.stop();
        std::lock_guard<std::mutex> lock(callback_mutex_);
        destroying_ = true;
        AttachedEnvironment environment = attach_current_thread();
        if (environment.environment && transport_) {
            environment.environment->DeleteGlobalRef(transport_);
        }
        transport_ = nullptr;
    }

    NativeTransport(const NativeTransport &) = delete;
    NativeTransport &operator=(const NativeTransport &) = delete;

    GStreamerSender &sender() { return sender_; }

    void report_start_error(const std::string &message)
    {
        invoke_error_callback(message);
    }

private:
    struct AttachedEnvironment {
        JavaVM *vm = nullptr;
        JNIEnv *environment = nullptr;
        bool attached = false;

        AttachedEnvironment() = default;
        AttachedEnvironment(const AttachedEnvironment &) = delete;
        AttachedEnvironment &operator=(const AttachedEnvironment &) = delete;

        AttachedEnvironment(AttachedEnvironment &&other) noexcept
            : vm(other.vm), environment(other.environment), attached(other.attached)
        {
            other.vm = nullptr;
            other.environment = nullptr;
            other.attached = false;
        }

        AttachedEnvironment &operator=(AttachedEnvironment &&other) noexcept
        {
            if (this != &other) {
                if (attached && vm) {
                    vm->DetachCurrentThread();
                }
                vm = other.vm;
                environment = other.environment;
                attached = other.attached;
                other.vm = nullptr;
                other.environment = nullptr;
                other.attached = false;
            }
            return *this;
        }

        ~AttachedEnvironment()
        {
            if (attached && vm) {
                vm->DetachCurrentThread();
            }
        }
    };

    AttachedEnvironment attach_current_thread()
    {
        AttachedEnvironment result;
        result.vm = environment_vm_;
        if (!environment_vm_) {
            return result;
        }
        JNIEnv *environment = nullptr;
        const jint get_environment_result = environment_vm_->GetEnv(
            reinterpret_cast<void **>(&environment), JNI_VERSION_1_6);
        if (get_environment_result == JNI_OK) {
            result.environment = environment;
            return result;
        }
        if (get_environment_result != JNI_EDETACHED) {
            return result;
        }
#if defined(__ANDROID__)
        const jint attach_result = environment_vm_->AttachCurrentThread(&environment, nullptr);
#else
        const jint attach_result = environment_vm_->AttachCurrentThread(
            reinterpret_cast<void **>(&environment), nullptr);
#endif
        if (attach_result != JNI_OK) {
            return result;
        }
        result.environment = environment;
        result.attached = true;
        return result;
    }

    template <typename Callback>
    void invoke_callback(Callback callback)
    {
        std::lock_guard<std::mutex> lock(callback_mutex_);
        if (destroying_ || !transport_) {
            return;
        }
        AttachedEnvironment environment = attach_current_thread();
        if (environment.environment) {
            callback(environment.environment, transport_);
        }
    }

    void invoke_bitrate_callback(std::uint32_t bitrate)
    {
        invoke_callback([bitrate](JNIEnv *environment, jobject transport) {
            jclass clazz = environment->GetObjectClass(transport);
            if (!clazz) {
                return;
            }
            const jmethodID method = environment->GetMethodID(
                clazz, "onNativeEstimatedBitrateChanged", "(I)V");
            if (method) {
                environment->CallVoidMethod(transport, method, static_cast<jint>(bitrate));
                if (environment->ExceptionCheck()) {
                    environment->ExceptionClear();
                }
            }
            environment->DeleteLocalRef(clazz);
        });
    }

    void invoke_keyframe_callback()
    {
        invoke_callback([](JNIEnv *environment, jobject transport) {
            jclass clazz = environment->GetObjectClass(transport);
            if (!clazz) {
                return;
            }
            const jmethodID method = environment->GetMethodID(
                clazz, "onNativeKeyframeRequested", "()V");
            if (method) {
                environment->CallVoidMethod(transport, method);
                if (environment->ExceptionCheck()) {
                    environment->ExceptionClear();
                }
            }
            environment->DeleteLocalRef(clazz);
        });
    }

    void invoke_error_callback(const std::string &message)
    {
        invoke_callback([&message](JNIEnv *environment, jobject transport) {
            jclass clazz = environment->GetObjectClass(transport);
            if (!clazz) {
                return;
            }
            const jmethodID method = environment->GetMethodID(
                clazz, "onNativeTransportError", "(Ljava/lang/String;)V");
            if (method) {
                jstring java_message = environment->NewStringUTF(message.c_str());
                if (java_message) {
                    environment->CallVoidMethod(transport, method, java_message);
                    environment->DeleteLocalRef(java_message);
                }
                if (environment->ExceptionCheck()) {
                    environment->ExceptionClear();
                }
            }
            environment->DeleteLocalRef(clazz);
        });
    }

    JavaVM *environment_vm_;
    jobject transport_;
    std::mutex callback_mutex_;
    bool destroying_ = false;
    GStreamerSender sender_;
};

NativeTransport *from_handle(jlong handle)
{
    return reinterpret_cast<NativeTransport *>(static_cast<std::intptr_t>(handle));
}

bool valid_java_port(jint port)
{
    return port >= static_cast<jint>(cambridge::contract::kMinimumPort) &&
           port <= static_cast<jint>(cambridge::contract::kMaximumPort);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cambridge_sender_media_streaming_cambridge_GStreamerTransport_nativeCreate(
    JNIEnv *environment, jobject transport)
{
    auto *native_transport = new NativeTransport(environment, transport);
    return static_cast<jlong>(reinterpret_cast<std::intptr_t>(native_transport));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cambridge_sender_media_streaming_cambridge_GStreamerTransport_nativeStart(
    JNIEnv *environment, jobject, jlong handle, jstring remote_host, jint remote_rtp_port,
    jint remote_rtcp_port, jint local_rtcp_port, jint target_bitrate_bps, jint mtu_bytes)
{
    NativeTransport *native_transport = from_handle(handle);
    if (!native_transport || !remote_host || !valid_java_port(remote_rtp_port) ||
        !valid_java_port(remote_rtcp_port) || !valid_java_port(local_rtcp_port) ||
        target_bitrate_bps <= 0 || mtu_bytes <= 0) {
        return JNI_FALSE;
    }
    const char *host_chars = environment->GetStringUTFChars(remote_host, nullptr);
    if (!host_chars) {
        return JNI_FALSE;
    }
    GStreamerSender::Config config;
    config.remote_host = host_chars;
    config.remote_rtp_port = static_cast<std::uint16_t>(remote_rtp_port);
    config.remote_rtcp_port = static_cast<std::uint16_t>(remote_rtcp_port);
    config.local_rtcp_port = static_cast<std::uint16_t>(local_rtcp_port);
    config.target_bitrate_bps = static_cast<std::uint32_t>(target_bitrate_bps);
    config.mtu_bytes = static_cast<std::uint32_t>(mtu_bytes);
    environment->ReleaseStringUTFChars(remote_host, host_chars);

    std::string error;
    if (!native_transport->sender().start(config, error)) {
        native_transport->report_start_error(error.empty() ? "GStreamer sender startup failed" : error);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_cambridge_sender_media_streaming_cambridge_GStreamerTransport_nativePushAccessUnit(
    JNIEnv *environment, jobject, jlong handle, jbyteArray bytes, jlong presentation_time_us,
    jboolean key_frame)
{
    NativeTransport *native_transport = from_handle(handle);
    if (!native_transport || !bytes) {
        return JNI_FALSE;
    }
    const jsize size = environment->GetArrayLength(bytes);
    if (size <= 0) {
        return JNI_FALSE;
    }
    jbyte *data = environment->GetByteArrayElements(bytes, nullptr);
    if (!data) {
        return JNI_FALSE;
    }
    const bool accepted = native_transport->sender().push_access_unit(
        reinterpret_cast<const std::uint8_t *>(data), static_cast<std::size_t>(size),
        static_cast<std::int64_t>(presentation_time_us), key_frame == JNI_TRUE);
    environment->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cambridge_sender_media_streaming_cambridge_GStreamerTransport_nativeStop(
    JNIEnv *, jobject, jlong handle)
{
    NativeTransport *native_transport = from_handle(handle);
    if (native_transport) {
        native_transport->sender().stop();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cambridge_sender_media_streaming_cambridge_GStreamerTransport_nativeDestroy(
    JNIEnv *, jobject, jlong handle)
{
    delete from_handle(handle);
}
