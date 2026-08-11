#pragma once

#include <cstddef>
#include <string>
#include <string_view>

#include <sys/socket.h>
#include <sys/types.h>

namespace cambridge::posix {

int create_cloexec_socket(int domain, int type, int protocol, std::string &error);

int accept_cloexec(int listener, sockaddr *address, socklen_t *address_length, std::string &error);

ssize_t send_without_sigpipe(int socket, const void *data, std::size_t size, int flags);

void set_current_thread_name(std::string_view name);

} // namespace cambridge::posix
