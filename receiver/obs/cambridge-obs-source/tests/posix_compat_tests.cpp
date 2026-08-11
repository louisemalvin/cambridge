#include "../src/platform/posix/posix_compat.hpp"

#include <cstdlib>
#include <fcntl.h>
#include <netinet/in.h>
#include <string>
#include <sys/socket.h>
#include <unistd.h>

namespace {

constexpr int kListenBacklog = 1;

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

void test_socket_creation_and_accept_are_close_on_exec()
{
    std::string error;
    const int listener = cambridge::posix::create_cloexec_socket(AF_INET, SOCK_STREAM, IPPROTO_TCP, error);
    require(listener >= 0);
    require((fcntl(listener, F_GETFD) & FD_CLOEXEC) != 0);

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(0);
    require(bind(listener, reinterpret_cast<const sockaddr *>(&address), sizeof(address)) == 0);
    require(listen(listener, kListenBacklog) == 0);

    socklen_t address_length = sizeof(address);
    require(getsockname(listener, reinterpret_cast<sockaddr *>(&address), &address_length) == 0);
    const int client = cambridge::posix::create_cloexec_socket(AF_INET, SOCK_STREAM, IPPROTO_TCP, error);
    require(client >= 0);
    require(connect(client, reinterpret_cast<const sockaddr *>(&address), sizeof(address)) == 0);

    sockaddr_in peer{};
    socklen_t peer_length = sizeof(peer);
    const int accepted = cambridge::posix::accept_cloexec(
        listener, reinterpret_cast<sockaddr *>(&peer), &peer_length, error);
    require(accepted >= 0);
    require((fcntl(accepted, F_GETFD) & FD_CLOEXEC) != 0);

    close(accepted);
    close(client);
    close(listener);
}

void test_send_without_sigpipe_transfers_bytes()
{
    int sockets[2]{};
    require(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    const std::string payload = "cambridge";
    require(cambridge::posix::send_without_sigpipe(
                sockets[0], payload.data(), payload.size(), 0) ==
            static_cast<ssize_t>(payload.size()));
    char received[16]{};
    require(recv(sockets[1], received, sizeof(received), 0) == static_cast<ssize_t>(payload.size()));
    require(std::string(received, payload.size()) == payload);
    close(sockets[0]);
    close(sockets[1]);
}

void test_thread_name_helper_is_callable()
{
    cambridge::posix::set_current_thread_name("cambridge-test");
}

} // namespace

int main()
{
    test_socket_creation_and_accept_are_close_on_exec();
    test_send_without_sigpipe_transfers_bytes();
    test_thread_name_helper_is_callable();
    return 0;
}
