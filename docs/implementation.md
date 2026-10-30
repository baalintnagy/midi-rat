# Implementation decisions and release qualification

This is a first implementation, not a qualified stable release. The consolidated
specification remains normative. None of the constants below is a measured latency
guarantee. Native MIDI 2.0 remains deferred; Rust remains a possible later direction.

## Implementation completions

**[Implementation completion I01]** Processing queues hold 4096 envelopes per
instance; output queues hold 4096 ordered submissions per physical handle; monitor
handoff holds up to 2048 envelopes; diagnostics hold 1024 pending entries and 100
recent lines. Incoming processing traffic is rejected on overflow and loss counted.
Output submission rejection and partial transport progress are reported. UI loss
does not remove routing traffic. Remap expansion is limited to 4096 branches.

**[Implementation completion I02]** Maximum complete MIDI/SysEx message size is
1 MiB. Wire assembly expires on the next received chunk after five seconds and
reports incomplete messages at disconnect. WinMM has four 64 KiB input buffers
and at most sixteen driver-owned SysEx output buffers. Drivers refusing cleanup
are reported and their ownership retained to avoid native use-after-free. Output
cleanup never uses `midiOutReset`, which could silently alter instrument state.
WinMM output dispatch waits for SysEx completion before submitting later messages;
its completion timeout allows conservative DIN wire time (3125 bytes/second) plus
five seconds. A hung driver is reported; its buffers remain owned rather than freed.
Its input callback queue reserves four slots for returned SysEx buffers alongside
4096 short-message slots, so short-message overflow cannot strand those buffers.

**[Implementation completion I03]** Monitor history accepts 1–100000 rows, default
2000. The workspace shows the latest twelve rows and optionally expanded details.
Copy uses a host-local paste buffer plus selectable text. `NO_COLOR` and dumb
terminals request a visible plain-color fallback. Emitter edits are session-local.
The semicolon editor separator is a workspace convention, not part of message text.

**[Implementation completion I04]** Latency is captured at callback handoff using
`System.nanoTime`; SysEx retains the receipt of its first handoff chunk. Native
timestamps are separate clocks: WinMM milliseconds since input start and MIDI
Services ticks divided by `MidiClock.TimestampFrequency`. Neither is relabelled
as host nanoseconds. The output worker samples immediately before API submission.
Median/p95/p99 use a rolling 8192-sample window; maximum/count cover the run.
Displayed jitter is p99 minus median. Record machine/backend/device/workload with
results; this instrumentation alone is not a latency certification.

**[Implementation completion I05]** Windows x64 uses Visual Studio 2022, C++20,
Windows SDK 10.0.26100.0, Windows MIDI Services SDK 0.99.92-preview.11, and C++/WinRT
3.0.260520.1. Native archives are SHA-256 pinned. Generated projections and build
output are excluded from maintained-source checks. ARM64 is a native build-script
option but not a qualified app-image target. The modern bridge exposes advertised
function-block/group-terminal-block directions as logical group ports, and disables
automatic reconnect. Unadvertised groups are not invented.

**[Implementation completion I06]** Save serializes YAML nodes, updating only the
selected emitter settings. Unrelated integers may be canonicalized to decimal to
preserve their decoded meaning; comments/layout are not preserved. Writes compare
the loaded bytes before and immediately before atomic replacement. An uncooperative
external writer racing the final filesystem operation cannot be locked out by this
optimistic protocol; resolve file changes before retrying.

## Remaining release qualification

Local implementation checks have covered synthetic routing/configuration/remap/
plugin/persistence tests, deterministic TamboUI rendering and keyboard exit, direct
WinMM enumeration, native bridge compilation, and app-image creation/validation.
The execution console exposes no interactive terminal dimensions; it is rejected
with an actionable error. Initial Windows MIDI Services activation failed with
`Class not registered`; subsequent inspection found that the service was running
but WinRT could not locate the app-local SDK DLL. The backend now explicitly loads
that DLL before activation, and native endpoint/group enumeration succeeds.
These results do not qualify physical transport or a clean-machine launch.

The Services monitor configuration has also been checked against all eight current
stable endpoint/group IDs. Startup opened eight native input connections and zero
outputs, then shut down cleanly when the execution console lacked terminal dimensions.
Actual message reception and latency measurements still require interactive/device testing.

Verified locally on 30 October 2026:

- `mvn --batch-mode --no-transfer-progress clean verify`: passed, 120 tests,
  zero Java source compiler warnings, zero Checkstyle violations, Spotless accepted,
  native x64 bridge built, and Windows app image created with bundled runtime.
- `scripts/verify-style-contract.ps1`: passed rejection/fix/stability/value checks.
- Packaged `MidiRat.exe --validate` and executable JAR `--validate`: both passed
  against the device-free example network. `--ports` enumerated real WinMM devices.
- Noninteractive launch: actionable terminal-dimensions error, followed by cleanup.
- `target/MidiRat-windows.zip`: created from the verified app-image contents.

The final native COM-lifetime adjustment was separately rebuilt and repackaged after
the clean Maven check; no native compiler warnings were reported. GitHub execution,
IDE/JDT diagnostics, physical sends/receives, and latency certification remain untested.

The initial app image omitted `jdk.jfr`, a TamboUI runtime dependency, which caused
`jdk/jfr/Event` failures during UI startup despite successful configuration-only
validation. Packaging now includes that module, checks `jdeps` requirements against
the bundled module list, and renders a TamboUI frame through a temporary launcher
using the bundled JVM. The probe uses no devices and is removed from the distribution.

- Establish and approve latency/jitter, sustained/burst workload, loss, queue-size,
  SysEx-size and timeout budgets using recorded measurements.
- Test physical short MIDI and large/interleaved SysEx on all three backends,
  including device removal, access contention, reconnection, and hung-driver cleanup.
- Test Windows MIDI Services availability, enumeration/group direction, clock
  conversion, and the installed SDK/runtime/service combinations on supported OSs.
- Test terminals, keyboard editing/paste, colors/fallback, resize, and larger networks.
- Launch the app image on a clean Windows x64 machine without a separately installed
  JDK; verify native prerequisites and distribution notices. ARM64, Linux and macOS
  need their own qualification matrix.
- Observe GitHub branch/PR/tag workflows. Hosted CI cannot certify physical latency.

Maven source compilation uses `-Xlint:all`. Spotless currently produces a dependency
runtime warning about its use of `sun.misc.Unsafe`; it is not a Java source warning
and is not suppressed. JDT/IDE diagnostics require separate inspection. Maven may
also report an expired certificate on a transitive plugin metadata repository;
resolved pinned artifacts still come from Maven Central. These are tooling issues,
not evidence of a clean IDE/device/platform check.
