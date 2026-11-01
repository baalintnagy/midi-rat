# MIDI Rat

MIDI Rat is a terminal application for routing, inspecting, filtering, and transforming
MIDI messages. Connect physical ports through named buses, observe traffic and chords,
emulate sustain/sostenuto pedals, and send messages manually. Configuration and wiring
use YAML.

The terminal workspace provides live counters, colored message logs, adaptive module
columns, and full-screen details. Modules run independently on bounded queues; terminal
rendering does not run on the MIDI processing workers.

## Quick start

On Windows, extract the portable distribution and open an interactive terminal in its
`MidiRat` directory. The distribution includes its Java runtime and the sostenuto plugin.

```powershell
.\MidiRat.exe --help
.\MidiRat.exe --validate
.\MidiRat.exe
```

The default configuration needs no MIDI hardware. It contains a manual emitter and a
monitor connected through a local bus. Focus `manual` with Tab and press Enter to send
a C note-on followed by a note-off. The monitor displays both messages.

List available device names and IDs before creating physical bindings:

```powershell
.\MidiRat.exe --ports
```

## Modules and routing

| Module type | Purpose |
| --- | --- |
| `midi-message-filter` | Accept or reject messages using whitelist and blacklist rules. |
| `midi-monitor` | Display MIDI fields, timing, original device, immediate source, and note labels. |
| `midi-chord` | Recognize chords from all held notes reaching the instance. |
| `midi-emitter` | Edit, send, and explicitly save a sequence of MIDI messages. |
| `midi-remap` | Apply ordered channel, note, controller, value-curve, or substitution rules. |
| `sostenuto` | Emulate sustain and sostenuto before forwarding messages to an instrument. |

Application configuration selects the backend, physical port bindings, module directory,
and network file. Network configuration declares buses, module instances, subscriptions,
and destinations. Paths in the application YAML are relative to that file.

A module's `sources` are physical input aliases or buses. Its `destinations` are output
aliases or buses; each result is sent to every destination. Multiple modules can subscribe
to the same source. Instance names identify widgets; they are distinct from endpoint
aliases. Routing cycles are rejected.

## Connect a keyboard and synthesizer

The device names below are placeholders. Replace them with exact names from `--ports`.
Save this as `config/application-example.yaml`:

```yaml
schemaVersion: 1
application:
  backend: winmm
  moduleDirectory: ../modules
  networkFile: ./network-example.yaml
  ports:
    inputs:
      keyboard: "Example MIDI Keyboard"
    outputs:
      synth: "Example MIDI Synthesizer"
```

Save the following as `config/network-example.yaml`. The filter removes MIDI clock and
active-sensing messages. Everything else reaches the synthesizer, monitor, and chord
observer. The emitter provides a separate manual test path to the synthesizer.

```yaml
schemaVersion: 1
network:
  buses: [musical]
  modules:
    - name: filter
      type: midi-message-filter
      sources: [keyboard]
      destinations: [musical, synth]
      settings:
        whitelist: []
        blacklist:
          - type: CLOCK
          - type: ACTIVE_SENSING
    - name: monitor
      type: midi-monitor
      sources: [musical]
      settings:
        historyLimit: 2000
        colors: true
    - name: chords
      type: midi-chord
      sources: [musical]
      settings: {}
    - name: test-output
      type: midi-emitter
      destinations: [synth]
      settings:
        format: hex
        messages:
          - "90 3C 64"
          - "80 3C 00"
```

Validate the YAML and module wiring without opening MIDI devices, then run it:

```powershell
.\MidiRat.exe --config .\config\application-example.yaml --validate
.\MidiRat.exe --config .\config\application-example.yaml
```

An empty whitelist accepts all messages unless a blacklist rule matches. With a nonempty
whitelist, a message must match at least one whitelist rule and no blacklist rule.
Rules can select message types, channels, note/controller numbers, value ranges, and
immediate sources. For example, `{type: NOTE, channels: [1], notes: [60, 64, 67]}` selects
those notes on MIDI channel 1. `NOTE` matches both note-on and note-off, including a
velocity-zero note-on interpreted as a release; original bytes are preserved.

The monitor retains receipt time across buses and shows the original hardware name
separately from the immediate source alias. Note labels include both common enharmonic
spellings, such as `(C#,Db)`.

The chord observer combines its inputs across origins, sources, and MIDI channels.
It displays recognized candidates and a `Notes` line; `--` means no held notes and
`unrecognized` means the current set has no match. Recognition uses bundled Tonal
JavaScript through Rhino, with no Node.js installation or network connection required.

## Choose a MIDI backend

| `backend` value | Port binding | Runtime requirements |
| --- | --- | --- |
| `winmm` | Exact device name | Windows; direct WinMM integration through JNA. |
| `windows-midi-services` | Exact name or stable endpoint/group ID | Available Windows MIDI Services stack and the bundled native bridge. |
| `java-sound` | Exact device name | A JDK/runtime with Java Sound MIDI support. |

Names are case-sensitive. WinMM and Java Sound enumeration indices are not persistent
identifiers. For Windows MIDI Services, copy an endpoint/group ID from `--ports` and use
an ID binding, for example:

```yaml
schemaVersion: 1
application:
  backend: windows-midi-services
  moduleDirectory: ../modules
  networkFile: ./network-example.yaml
  ports:
    inputs:
      keyboard: {id: "replace-with-input-endpoint-group-id"}
    outputs:
      synth: {id: "replace-with-output-endpoint-group-id"}
```

This application file uses the same network as the keyboard/synth example. The portable
Windows distribution includes bridge DLLs, but does not install Windows MIDI Services.
A missing backend prerequisite produces an error rather than selecting another backend.

## Emulate sustain and sostenuto

Keep the keyboard/synth application file and replace `network-example.yaml` with this
network. The sostenuto processor receives the keyboard's notes and pedal messages,
then forwards its output to both the synthesizer and the observers. The chord display
therefore follows the processed notes, including retained releases.

```yaml
schemaVersion: 1
network:
  buses: [processed]
  modules:
    - name: pedals
      type: sostenuto
      sources: [keyboard]
      destinations: [processed, synth]
      settings: {}
    - name: monitor
      type: midi-monitor
      sources: [processed]
      settings: {historyLimit: 2000, colors: true}
    - name: chords
      type: midi-chord
      sources: [processed]
      settings: {}
```

CC64 controls sustain and CC66 controls sostenuto. Sustain holds releases; sostenuto
captures notes already held when it engages. The processor consumes these pedal
messages while forwarding other ordinary MIDI. CC22 is an additional default sostenuto
trigger. The trigger can instead use a configured CC or note, with momentary or toggle
behavior. Each instance has independent state.

Sostenuto is an external module. Its `sostenuto.jar` must be present in the configured
`moduleDirectory`; the portable distribution and root Maven build install it there.

## Transpose notes

This alternative `network-example.yaml` raises notes by one octave before sending them
to the synthesizer and monitor. Matching `NOTE` applies the same transformation to
presses and releases. Other message types pass through unchanged; resulting note
numbers are clamped to the MIDI range, 0–127.

```yaml
schemaVersion: 1
network:
  buses: [transposed]
  modules:
    - name: octave-up
      type: midi-remap
      sources: [keyboard]
      destinations: [transposed, synth]
      settings:
        rules:
          - match: {type: NOTE}
            action: {op: note-map, fallbackTranspose: 12}
            flow: stop
    - name: monitor
      type: midi-monitor
      sources: [transposed]
      settings: {historyLimit: 2000, colors: true}
```

Remap rules run in order. `flow: stop` finishes processing that matched branch;
`flow: continue` allows subsequent rules to process its result. Always validate edited
configuration before running it.

## Use the workspace

Tab or `j` advances focus; `k` moves to the previous widget. Compact modules share
columns when they fit, and monitors retain full-width rows. Connections / Activity is
also focusable and summarizes the backend, outputs, and module health.

| Key | Action |
| --- | --- |
| `d` | Open full-screen details for the focused widget. |
| Esc / `d` | Return from details to the workspace. |
| `m` / Tab in details | Cycle the available pages. |
| `[` / `]` | Scroll a rules, configuration, or status page. |
| `<` / `>` | Pan wide message-log columns. |
| `p` / `c` / `y` | Pause/resume, clear, or copy from the selected monitor/log. |
| `x` in log details | Toggle raw/decoded payload details. |
| `h` | Open host details directly. |
| `r` / `R` | Reload the saved network / reconnect physical devices. |
| `P` / Ctrl+P | Explicit host Panic on all buses and physical outputs. |
| `q` / Ctrl+C | Exit. |

A filter widget shows a compact summary such as `○ 2w | 1b | Passed: 125 | Blocked: 8`.
Its LED lights for 10 ms after a blocked message; passed messages do not light it.
In filter details, `m` cycles **Rules → Passed → Blocked** in one panel. The two logs
keep separate bounded histories and report capture losses. Pausing or clearing a log
does not change filtering or reset the lifetime decision counters.

Host details cycle **Status → Configuration → Activity**. Configuration includes loaded
application YAML, the active network snapshot, resolved paths, exact port bindings,
module settings and wiring, provider/API versions, and queue capacities.

For an emitter, `e` opens the editor. Separate complete messages with semicolons;
Ctrl+T changes between hex and readable message text. Enter accepts validated edits;
Esc cancels. Outside the editor, Enter sends the complete sequence and `s` explicitly
saves it to the network file. `y` copies a monitor message to the workspace buffer;
focus an emitter and press `v` to use it. Saving refuses conflicting external edits,
and sending never saves automatically.

Backend and application binding changes require a restart. Network reload and module
replacement do not migrate pedal/chord state. Reload, reconnect, module removal, and
exit do not send automatic note cleanup; Panic is an explicit action. MIDI processing,
message capture, and diagnostic queues are bounded, and their loss counts remain
visible. Terminal rendering and synthetic tests do not establish hardware latency.

## Build from source

Use JDK 25 and Maven 3.9.6 or newer. Windows app-image packaging also needs PowerShell 7,
Visual Studio 2026 C++ tools (v145), CMake 4.2 or newer, and Windows SDK 10.0.26100.0.
Visual Studio Build Tools is sufficient; the IDE is optional. Set `JAVA_HOME` to your
JDK 25 directory and make Maven available on `PATH`.

From the repository root:

```powershell
mvn spotless:apply
mvn spotless:check checkstyle:check
mvn --batch-mode --no-transfer-progress clean verify
```

The build downloads checksum-pinned native SDK dependencies. On Windows x64, `verify`
builds and checks the host, the external sostenuto JAR, and the portable application.
For Java-only development without Windows packaging:

```powershell
mvn --batch-mode --no-transfer-progress '-P!windows-package' verify
```

| Output | Location |
| --- | --- |
| Executable host JAR | `target/midi-rat-0.2.0.jar` |
| External sostenuto JAR | `extensions/sostenuto/target/sostenuto.jar` |
| Development plugin copy | `modules/sostenuto.jar` |
| Windows application and runtime | `target/jpackage-out/MidiRat/` |

Run the source build with an installed JDK 25:

```powershell
java --enable-native-access=ALL-UNNAMED -jar .\target\midi-rat-0.2.0.jar --validate
java --enable-native-access=ALL-UNNAMED -jar .\target\midi-rat-0.2.0.jar
```

Create a distributable ZIP after a successful Windows build:

```powershell
Compress-Archive -Path .\target\jpackage-out\MidiRat\* -DestinationPath .\target\MidiRat-windows.zip -Force
```

The Maven reactor contains `application` for the host, `extensions/sostenuto` for the
plugin, and `distribution` for packaging. The host remains built from `src`. VS Code
includes Java launch configurations and tasks for Java builds, the native bridge,
and the Windows distribution; adjust configured JDK paths to your installation.

## External modules

External modules are trusted, in-process JARs loaded from `moduleDirectory`. Compile
against the host API with JDK 25, keeping the host dependency provided rather than
bundling a second copy of its classes. Implement `io.midirat.api.ModuleProvider` and
register the provider class in `META-INF/services/io.midirat.api.ModuleProvider`.
Provider API versions must match the host; module type IDs must be unique.

The host watches installed JARs, validates stable shadow copies, and retains running
instances if an update fails. Unchanged providers keep their instances; removing a
plugin disables its affected routes while retaining configuration. Providers can expose
widgets and full-screen detail pages through the module API. The host owns physical
ports, routing, UI scheduling, and lifecycle management.

MIDI Rat uses TamboUI for terminal rendering, SnakeYAML for configuration, and JNA for
native integration. Bundled third-party components retain their license and notice files.
