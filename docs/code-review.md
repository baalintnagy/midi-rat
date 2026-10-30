# Code structure review

The review covered Java domain/messages, DSL decoding and persistence, built-in
processing, routing/device dispatch, plugin lifecycle, terminal UI, and native
adapter boundaries. It focused on behavior-preserving changes rather than changing
MIDI semantics or replacing libraries.

## Changes

- Host and UI depend on `EmitterControls` and `MonitorControls`, not concrete
  built-in module classes. Optional capabilities retain unsupported-action behavior.
- `ModuleWidget` owns elastic-height and color preferences. Workspace rendering
  no longer identifies monitors by class, and plugins can provide the same policy.
  The default adapter preserves existing `widgetLines()` implementations.
- `SequenceEditor` owns its edit buffer, format and acceptance/cancellation state.
  The workspace retains focus, keyboard dispatch and terminal scheduling.
- Emitter persistence consumes the emitter capability rather than its implementation.
- Rule validation uses one shared scope predicate, preserving the distinct scope
  and raw/semantic validation diagnostics and their evaluation order.
- Channel-family classification explicitly lists supported kinds rather than relying
  on enum declaration order.

New API methods are defaults: existing plugin providers need no implementation
changes. No configuration schema, MIDI transformation, queue capacity, panic,
reload, or note-state behavior was intentionally changed.

## Checks retained deliberately

YAML node shape checks sit at the third-party parsing boundary. The message equality
check is conventional type-safe equality. Exhaustive switches over sealed message
rules and actions express a closed, typed domain; replacing those with reflection
or a universal nullable object would weaken it.

## Remaining opportunities

The workspace layout and native adapters are still relatively large. Future work
can extract layout calculations and native resource wrappers, but native ownership,
driver-error recovery, and worker shutdown need stronger device-level coverage before
altering their concurrency structure. Emitter content is mutable across UI/host
threads and merits a separate concurrency review. The monitor formatter still exposes
text that the terminal layer splits into fields; a typed presentation model would
remove that coupling, but requires a deliberate compatible widget API evolution.

This review does not claim that all smells are removed or that synthetic tests
establish physical-device or near-real-time equivalence.
