// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace zew::cloud::lab {
struct Status {
    bool ok = false;
    std::string diagnostic;
};
struct BoardIdentity {
    std::string board_id;
    std::string zephyr_board;
    std::string silicon_id;
    bool silicon_id_verified = false;
};
struct SerialFrame {
    std::uint64_t sequence = 0;
    bool overflow_before = false;
    std::vector<std::uint8_t> data;
};
// Owned, cancellable and lease-bound hardware operations. gRPC handlers must
// authenticate and verify an active lease before calling these interfaces.
class BoardTransport {
public:
    virtual ~BoardTransport() = default;
    virtual BoardIdentity probe_identity() = 0;
    virtual Status flash_verified_artifact(std::string_view artifact_id,
                                           std::string_view expected_sha256) = 0;
    virtual Status start_debug_server(std::string_view target_arch) = 0;
    virtual Status stop_debug_server() noexcept = 0;
    virtual Status open_serial(std::uint32_t baud) = 0;
    virtual Status close_serial() noexcept = 0;
    virtual std::vector<SerialFrame> drain_serial(std::size_t max_bytes) = 0;
};
// Adapter classes: WestRunnerTransport, JLinkTransport, StLinkTransport,
// OpenOcdTransport and SerialPortTransport are future implementations.
// The agent must never run user-supplied shell commands or arbitrary paths.
} // namespace zew::cloud::lab
