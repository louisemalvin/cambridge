#include "posix_compat.hpp"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <pthread.h>
#include <unistd.h>

namespace cambridge::posix {
namespace {

void set_error(std::string &error)
{
    error = std::strerror(errno);
}

#if defined(__linux__)

#elif defined(__APPLE__)

bool set_close_on_exec(int descriptor, std::string &error)
{
    const int descriptor_flags = fcntl(descriptor, F_GETFD);
    if (descriptor_flags < 0 || fcntl(descriptor, F_SETFD, descriptor_flags | FD_CLOEXEC) < 0) {
        set_error(error);
        close(descriptor);
        return false;
    }
    return true;
}

#else
#error "CamBridge supports only Linux and macOS POSIX compatibility implementations"
#endif

} // namespace

int create_cloexec_socket(int domain, int type, int protocol, std::string &error)
{
#if defined(__linux__)
    const int descriptor = socket(domain, type | SOCK_CLOEXEC, protocol);
    if (descriptor < 0) {
        set_error(error);
    }
    return descriptor;
#elif defined(__APPLE__)
    const int descriptor = socket(domain, type, protocol);
    if (descriptor < 0) {
        set_error(error);
        return descriptor;
    }
    if (!set_close_on_exec(descriptor, error)) {
        return -1;
    }
    return descriptor;
#endif
}

int accept_cloexec(int listener, sockaddr *address, socklen_t *address_length, std::string &error)
{
#if defined(__linux__)
    const int descriptor = accept4(listener, address, address_length, SOCK_CLOEXEC);
    if (descriptor < 0) {
        set_error(error);
    }
    return descriptor;
#elif defined(__APPLE__)
    const int descriptor = accept(listener, address, address_length);
    if (descriptor < 0) {
        set_error(error);
        return descriptor;
    }
    if (!set_close_on_exec(descriptor, error)) {
        return -1;
    }
    return descriptor;
#endif
}

ssize_t send_without_sigpipe(int socket_descriptor, const void *data, std::size_t size, int flags)
{
#if defined(__linux__)
    return send(socket_descriptor, data, size, flags | MSG_NOSIGNAL);
#elif defined(__APPLE__)
    constexpr int kSigpipeDisabled = 1;
    if (setsockopt(socket_descriptor, SOL_SOCKET, SO_NOSIGPIPE, &kSigpipeDisabled,
                   sizeof(kSigpipeDisabled)) != 0) {
        return -1;
    }
    return send(socket_descriptor, data, size, flags);
#endif
}

void set_current_thread_name(std::string_view name)
{
#if defined(__linux__)
    pthread_setname_np(pthread_self(), std::string(name).c_str());
#elif defined(__APPLE__)
    pthread_setname_np(std::string(name).c_str());
#endif
}

} // namespace cambridge::posix
