# MIDI Rat

JDK 25 MIDI utility with a TamboUI workspace, YAML networks, internal broadcast
buses, filters, monitors, manual emitters, remappers, and hot-loaded Java modules.
The requirements are in [SPECIFICATION-final.md](SPECIFICATION-final.md).

## Build and run

Install JDK 25 and Maven 3.9 or newer. Windows packaging also requires PowerShell 7, Visual
Studio 2022 C++ tools, CMake 3.25 or newer, and Windows SDK 10.0.26100.0. The build
downloads checksum-pinned Windows MIDI Services and C++/WinRT packages.
The GitHub Actions build uses `windows-2022` to match the required VS 2022 toolchain.

```powershell
mvn spotless:apply
mvn spotless:check checkstyle:check
mvn --batch-mode --no-transfer-progress clean verify
./target/jpackage-out/MidiRat/MidiRat.exe
```

`verify` produces the executable portable JAR and, on Windows x64, the console app
image with its bundled runtime, native bridge, configuration, modules, and logs.
CI archives that image as `MidiRat-windows.zip`. For Java-only development on
Windows, use `mvn '-P!windows-package' verify`. Other operating systems build the
JAR and require their own JDK 25; portable/device certification remains pending.

```powershell
java --enable-native-access=ALL-UNNAMED -jar target/midi-rat-0.1.0-SNAPSHOT.jar --validate
java --enable-native-access=ALL-UNNAMED -jar target/midi-rat-0.1.0-SNAPSHOT.jar --ports
java --enable-native-access=ALL-UNNAMED -jar target/midi-rat-0.1.0-SNAPSHOT.jar
```

Use `--config path/to/wmm-application.yaml` to choose application settings. The example
network is device-free: its emitter sends to a local bus and monitor only. Bind
physical devices in `config/wmm-application.yaml` after inspecting `--ports`. Exact
names are case-sensitive. WinMM and Java Sound enumeration indices are never
persistent IDs. Select `{id: ...}` only for Windows MIDI Services endpoint/group IDs.

Application examples use `wmm-` for WinMM and `wms-` for Windows MIDI Services,
for example `wmm-application-monitor.yaml` and `wms-application-monitor.yaml`.
Backend-independent network files are shared. The default is `wmm-application.yaml`.

The Windows MIDI Services backend needs the matching bridge and SDK DLLs in
`jna.library.path`/`java.library.path`, and an installed, available Windows MIDI
Services stack. The app image includes DLLs but does not install the service.
Missing prerequisites produce an error; there is no backend fallback.

For the current machine's monitor-only setup (all eight inputs except `postSSA`
and `postSSB`), see [terminal and VS Code instructions](docs/monitoring.md).

## Workspace

Tab/j/k changes focused module. `P` or **Ctrl+P** invokes host Panic; Ctrl+P also
works while editing. `r` reloads the persisted network; `R` reconnects devices;
`q` or Ctrl+C exits. No reload, reconnect, removal, or exit sends implicit cleanup.

Monitor keys: `p` pauses/resumes, `c` clears, `d` expands raw/decoded details, and
`y` copies the latest displayed message into the workspace copy buffer. Focus an
emitter and press `v` to paste that readable message. Copy text is also displayed
for terminal selection. History and UI handoff are bounded independently of routing.

Emitter keys: `e` edits; semicolons separate complete messages; Ctrl+T switches
between hex and message-text; Enter accepts validated edits; Esc cancels. Enter
outside the editor sends the entire validated sequence; `s` explicitly saves its
format/messages. Save refuses external changes. Sending never writes the network.

## Architecture and verification

See [module API](docs/module-api.md), [implementation decisions and qualification](docs/implementation.md),
[style enforcement](docs/source-style.md), and [third-party notices](THIRD-PARTY-NOTICES.md).
Java tests use synthetic endpoints and do not establish real-device latency or
clean-machine compatibility. Stable-release qualification requires the measurements
and platform/device tests listed in the specification and implementation report.
