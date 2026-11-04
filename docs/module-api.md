# Module API version 1

Plugins are trusted in-process JARs. Class-loader isolation is not a sandbox.
Compile against the MIDI Rat JAR with JDK 25. Include a UTF-8 service descriptor
`META-INF/services/io.midirat.api.ModuleProvider` containing provider class names.
Install the JAR in the application's configured module directory.

The bundled sostenuto processor is an external provider in `extensions/sostenuto`.
Its Maven dependency on the host is `provided`: the plugin JAR contains only its
implementation and service descriptor, without a second copy of the API or host.
The root reactor builds and installs it into `modules/sostenuto.jar`; the Windows
distribution copies it to its own `modules` directory. Other built-in modules remain
inside the host. Build both with `mvn package`, or run the complete checks with
`mvn verify` from the repository root.

`ModuleProvider` declares `type()`, `version()`, `apiVersion()` (1), `role()`
(`PROCESSOR`, `MONITOR`, `EMITTER`), and
`prepare(Value settings, List<Endpoint> subscriptions)` returning a `ModuleInstance`.
Preparation validates all settings and resolves names before dispatch. `Value`
preserves configuration paths/line/column and provides strict map/list/string/integer
decoders. Preparation must not activate devices, start workers, or publish messages.

`ModuleInstance.start(ModuleContext)` runs before activation. `process(Envelope)`
receives immutable payloads and metadata and returns an ordered message list for
all declared destinations. Calls are serialized per instance on a bounded host
worker. Return an empty list for observer modules. Runtime processing must not
decode YAML or perform filesystem/UI work.

`ModuleContext.publish(Endpoint, List<MidiMessage>)` permits asynchronous generation
only after start and activation and only to the instance's declared destinations.
Synchronous transformations should return results from `process` to retain receipt
time. `ModuleContext.forward(Envelope, List<MidiMessage>)` supports delayed processing
results: it fans out to declared destinations in message order, retaining the input's
origin/display name and receipt time. The input source must be subscribed and the
route must still be active; removed routes cannot publish late work. Its default
implementation throws for compatibility with older custom contexts.
`ModuleContext.report(String)` queues diagnostics; `report(ModuleError)` also
accepts a diagnostic code and immutable context attributes. `widgetLines()` supplies
text to host rendering; use bounded nonblocking handoff. `close()` releases owned
resources without generating MIDI cleanup or blocking indefinitely.

The host owns subscription registration, physical handles, publication, queues,
and terminal scheduling. Plugins must not open host backend implementations.
`Envelope.source()` carries the immediate source used by subscriptions and rule
matching; `origin()` preserves the initial input endpoint across processing/buses.
`originName()` preserves its discovered hardware display name for presentation.
Receipt time remains relative to host start. The three-argument constructor initializes
origin to source (and its display name to that alias), and `forward()` preserves both.
Newly generated bus traffic starts
with that bus as origin; plugins forwarding input should return processing results
or use `context.forward` for delayed results to retain provenance.
`MidiMessage` defensively copies bytes and carries actual length, never display padding.

Optional `emitterControls()` and `monitorControls()` expose narrow capabilities;
the host/UI do not inspect concrete module implementations.
`ModuleContext.sourceNames()` supplies immutable host-resolved origin display names
and the instance's immediate source aliases at startup for presentation sizing.
`ModuleContext.originEndpoints()` supplies immutable logical input/bus endpoints
known to the network, for validating origin selectors before activation.
`widget()` returns a
`ModuleWidget` with rendering, color preference, and elastic-height policy. Its
default adapter still calls legacy `widgetLines()`, so existing providers retain
their fixed-height behavior without implementing the new optional methods.

`ModuleWidget.preferredWidth()` supplies a preferred minimum in terminal cells,
including borders and padding (default 24). Content and titles can require more;
the host constrains the result to the terminal width. The chord widget requests 94
cells and sostenuto requests 42, reserving roughly two-thirds and one-third of a
shared row at typical wide terminal sizes, even when their current content is short.
These are preferred cell widths rather than fixed percentages; narrow layouts stack.
Spare columns in a shared row go to its widest widget (the first on ties), keeping
compact neighbors at their content/title minimum instead of expanding them equally.

`details()` returns `ModuleDetails` for a dedicated full-screen view. Its default
adapter presents the ordinary widget and monitor controls, preserving existing
providers. Override it to supply an `informationTitle()` and `information()` rows
(for example validated filter YAML), a `content()` widget, optional log `mode()` /
`nextMode()`, and optional monitor `controls()`. The host owns navigation, scrolling,
layout, and colors; these callbacks run on the UI thread. Captures must use bounded,
nonblocking handoffs. This is an additive API-version-1 extension with defaults.

`EmitterControls.snapshot()` returns an immutable `Sequence(format, messages)`.
The built-in emitter validates and publishes an entire sequence atomically; compile,
render, edit initialization, and Save each consume one snapshot. Existing emitter
capabilities inherit an adapter over `format()` and `messages()`. Implementations
that edit concurrently should override `snapshot()` to return coherent content.

The watcher debounces events, hashes installed JARs, validates a stable shadow copy,
and discovers providers through separate URL class loaders. Unchanged packages
retain instances and loaders. Changed instances are prepared/started before their
old routes stop. Failed preparation/start retains the previous active routes.
Removal disables affected instances while retaining their configuration. Reinstall
restores them from persisted settings. No general note state is migrated or released.

Errors are actionable diagnostics. A runtime instance failure disables that route;
unrelated routes continue. Incompatible API versions and duplicate type IDs reject
the candidate generation. Class loaders and shadow files are released when their
generation is no longer referenced.
