#include <array>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <vector>
#include <windows.h>
#include <winrt/Windows.Devices.Midi2.Enumeration.h>
#include <winrt/Windows.Devices.Midi2.h>
#include <winrt/Windows.Foundation.Collections.h>

// ABI 1 uses cdecl, UTF-8 strings, fixed width words, and opaque handles.
// All exceptions are contained at the C boundary. Callback storage is borrowed for the call.
using namespace winrt::Windows::Devices::Midi2;
using namespace winrt::Windows::Devices::Midi2::Enumeration;
using Receive = void(__cdecl *)(const uint32_t *, int32_t, uint64_t);
struct Port {
    std::string id;
    std::string name;
    int32_t input;
};
struct Connection {
    MidiSession session{nullptr};
    MidiEndpointConnection endpoint{nullptr};
    winrt::event_token messages{};
    winrt::event_token disconnected{};
    std::shared_mutex gate;
    Receive receive{};
    bool closed{};
};
static void close_connection(std::shared_ptr<Connection> const &state) {
    {
        std::unique_lock lock(state->gate);
        state->closed = true;
    }
    if (state->endpoint) {
        if (state->messages) {
            state->endpoint.MessageReceived(state->messages);
            state->messages = {};
        }
        if (state->disconnected) {
            state->endpoint.EndpointDeviceDisconnected(state->disconnected);
            state->disconnected = {};
        }
        state->session.DisconnectEndpointConnection(state->endpoint.ConnectionId());
        state->endpoint = nullptr;
    }
    if (state->session) {
        state->session.Close();
        state->session = nullptr;
    }
}
static std::vector<Port> ports;
static std::mutex enumeration_gate;
static thread_local std::string last_error;
struct Apartment {
    Apartment() {
        winrt::init_apartment(winrt::apartment_type::multi_threaded);
    }
    ~Apartment() {
        winrt::clear_factory_cache();
        winrt::uninit_apartment();
    }
};
template <class F> int32_t protect(F action) noexcept {
    try {
        static thread_local Apartment apartment;
        action();
        last_error.clear();
        return 0;
    } catch (winrt::hresult_error const &error) {
        last_error = winrt::to_string(error.message());
        return -1;
    } catch (std::exception const &error) {
        last_error = error.what();
        return -1;
    } catch (...) {
        last_error = "Unknown native MIDI failure";
        return -1;
    }
}
#define API extern "C" __declspec(dllexport)
API int32_t __cdecl mr_abi_version() noexcept {
    return 1;
}
API const char *__cdecl mr_error() noexcept {
    return last_error.c_str();
}
API int32_t __cdecl mr_available() noexcept {
    return protect([] {
        if (!MidiApi::EnsureServiceAvailable()) {
            throw std::runtime_error(
                "Windows MIDI Services is unavailable; install/update the service explicitly");
        }
    });
}
API int32_t __cdecl mr_enumerate(int32_t *count) noexcept {
    return protect([&] {
        std::lock_guard lock(enumeration_gate);
        ports.clear();
        for (auto const &endpoint : MidiEndpointDeviceInformation::FindAll()) {
            std::array<bool, 16> inputs{};
            std::array<bool, 16> outputs{};
            // Directions are relative to the endpoint: BlockOutput is host input.
            for (auto const &block : endpoint.GetGroupTerminalBlocks()) {
                auto direction = block.Direction();
                for (unsigned g = block.FirstGroup().Index();
                     g < unsigned(block.FirstGroup().Index()) + block.GroupCount() && g < 16; ++g) {
                    inputs[g] =
                        inputs[g] || direction != MidiGroupTerminalBlockDirection::BlockInput;
                    outputs[g] =
                        outputs[g] || direction != MidiGroupTerminalBlockDirection::BlockOutput;
                }
            }
            for (auto const &block : endpoint.GetDeclaredFunctionBlocks()) {
                if (!block.IsActive()) {
                    continue;
                }
                auto direction = block.Direction();
                for (unsigned g = block.FirstGroup().Index();
                     g < unsigned(block.FirstGroup().Index()) + block.GroupCount() && g < 16; ++g) {
                    inputs[g] = inputs[g] || direction == MidiFunctionBlockDirection::BlockOutput ||
                                direction == MidiFunctionBlockDirection::Bidirectional;
                    outputs[g] = outputs[g] ||
                                 direction == MidiFunctionBlockDirection::BlockInput ||
                                 direction == MidiFunctionBlockDirection::Bidirectional;
                }
            }
            for (unsigned g = 0; g < 16; ++g) {
                std::string suffix = "#group=" + std::to_string(g);
                std::string name =
                    winrt::to_string(endpoint.Name()) + " [group " + std::to_string(g + 1) + "]";
                std::string id = winrt::to_string(endpoint.EndpointDeviceId()) + suffix;
                if (inputs[g]) {
                    ports.push_back({id, name, 1});
                }
                if (outputs[g]) {
                    ports.push_back({id, name, 0});
                }
            }
        }
        *count = static_cast<int32_t>(ports.size());
    });
}
API const char *__cdecl mr_port_id(int32_t index) noexcept {
    return index >= 0 && size_t(index) < ports.size() ? ports[index].id.c_str() : nullptr;
}
API const char *__cdecl mr_port_name(int32_t index) noexcept {
    return index >= 0 && size_t(index) < ports.size() ? ports[index].name.c_str() : nullptr;
}
API int32_t __cdecl mr_port_input(int32_t index) noexcept {
    return index >= 0 && size_t(index) < ports.size() ? ports[index].input : -1;
}
API uint64_t __cdecl mr_frequency() noexcept {
    uint64_t result{};
    if (protect([&] { result = MidiClock::TimestampFrequency(); }) != 0) {
        return 0;
    }
    return result;
}
API int32_t __cdecl mr_open(const char *id, Receive receive, void **result) noexcept {
    return protect([&] {
        auto state = std::make_shared<Connection>();
        try {
            std::string key(id);
            auto separator = key.rfind("#group=");
            if (separator == std::string::npos) {
                throw std::runtime_error("Missing group in endpoint ID");
            }
            key.resize(separator);
            state->session = MidiSession::Create(L"MIDI Rat");
            MidiEndpointConnectionSettings settings(false, false);
            state->endpoint =
                state->session.CreateEndpointConnection(winrt::to_hstring(key), settings);
            if (!state->endpoint) {
                throw std::runtime_error("Cannot create MIDI endpoint connection");
            }
            state->receive = receive;
            if (receive) {
                state->messages = state->endpoint.MessageReceived(
                    [state](auto const &, auto const &args) noexcept {
                        std::shared_lock lock(state->gate);
                        if (state->closed) {
                            return;
                        }
                        uint32_t words[4]{};
                        auto count = args.FillWords(words[0], words[1], words[2], words[3]);
                        state->receive(words, count, args.Timestamp());
                    });
                state->disconnected = state->endpoint.EndpointDeviceDisconnected(
                    [state](auto const &, auto const &) noexcept {
                        std::shared_lock lock(state->gate);
                        if (!state->closed) {
                            state->receive(nullptr, -1, 0);
                        }
                    });
            }
            if (!state->endpoint.Open()) {
                throw std::runtime_error("Cannot open endpoint; check service/device/access");
            }
            *result = new std::shared_ptr<Connection>(state);
        } catch (...) {
            close_connection(state);
            throw;
        }
    });
}
API int32_t __cdecl mr_send(void *handle, const uint32_t *words, int32_t count) noexcept {
    return protect([&] {
        auto &state = **static_cast<std::shared_ptr<Connection> *>(handle);
        MidiSendMessageResults status{};
        if (count == 1) {
            status = state.endpoint.SendSingleMessageWords(0, words[0]);
        } else if (count == 2) {
            status = state.endpoint.SendSingleMessageWords(0, words[0], words[1]);
        } else {
            throw std::runtime_error("Bridge accepts MIDI 1.0 32/64-bit UMP only");
        }
        if (!MidiEndpointConnection::SendMessageSucceeded(status)) {
            throw std::runtime_error("MIDI Services send failed: " +
                                     std::to_string(uint32_t(status)));
        }
    });
}
API int32_t __cdecl mr_close(void *handle) noexcept {
    return protect([&] {
        std::unique_ptr<std::shared_ptr<Connection>> owner(
            static_cast<std::shared_ptr<Connection> *>(handle));
        close_connection(*owner);
    });
}
