#include "../src/diagnostics.hpp"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>

namespace {

constexpr char kDiagnosticsTestPath[] = "cambridge-diagnostics-test.json";

void require(bool condition)
{
    if (!condition) {
        std::abort();
    }
}

void test_diagnostics_serialization_preserves_contract_fields()
{
    cambridge::DiagnosticsSnapshot snapshot;
    snapshot.version = "test";
    snapshot.git_commit = "commit";
    snapshot.requested_decoder_mode = "native_required";
    snapshot.session_media_path = "native";
    snapshot.media_path_locked = true;
    snapshot.native_setup_status = "ready";
    snapshot.native_setup_reason = "quoted \"reason\"";
    snapshot.hardware_cpu_transfers = 0;
    snapshot.gpu_copies = 0;
    snapshot.last_decoded_frame_age_ms = 47;
    snapshot.last_media_path_failure_detail = "line\nsecond";

    std::string error;
    require(cambridge::write_diagnostics(snapshot, kDiagnosticsTestPath, error));
    std::ifstream input(kDiagnosticsTestPath);
    require(input.good());
    const std::string content((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    require(content.find("\"requestedDecoderMode\": \"native_required\"") != std::string::npos);
    require(content.find("\"sessionMediaPath\": \"native\"") != std::string::npos);
    require(content.find("\"mediaPathLocked\": true") != std::string::npos);
    require(content.find("\"hardwareCpuTransfers\": 0") != std::string::npos);
    require(content.find("\"lastDecodedFrameAgeMs\": 47") != std::string::npos);
    require(content.find("quoted \\\"reason\\\"") != std::string::npos);
    require(content.find("line\\nsecond") != std::string::npos);
    std::remove(kDiagnosticsTestPath);
}

} // namespace

int main()
{
    test_diagnostics_serialization_preserves_contract_fields();
    return 0;
}
