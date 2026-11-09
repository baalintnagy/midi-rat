# MIDI Rat — Final Consolidated Specification

**Date:** 30 October 2026

**Status:** Functional baseline for the first stable implementation on JDK 25.
The application is not yet implemented.

## 1. Purpose, scope, and authority

MIDI Rat is a modular, near-real-time MIDI utility with a terminal user interface.
It connects physical MIDI ports and internal buses through modules that inspect,
filter, generate, and transform MIDI messages.

**[User long-term direction, 7 November 2026]** Aim for a mathematical design in
the sense of small, precisely defined primitives and their composition, rather
than formula-heavy presentation. Richer behavior should be constructible from
those primitives. This direction does not itself select a routing decomposition
or authorize changes to the existing remap module; those designs remain open.

This specification consolidates the original requirements, subsequent user decisions,
and the accepted filter DSL refinements. It supersedes earlier specification text
where they conflict. It contains the application and network schemas, all current
module DSLs, examples, lifecycle behavior, packaging, and acceptance requirements.
No separate decision, filter, MIDI 2.0 assessment, or packaging document is needed
to interpret this specification. Retained style guidance and tool configurations
remain applicable.

**Must** identifies a requirement, **should** a preference, and **may** an option.
**[Design completion Dxx]** marks syntax, defaults, or behavior supplied during this
consolidation to resolve previously unspecified details. These are explicit baseline
design choices, not claims that the user previously specified those exact details.
Section 15 collects them and identifies release measurements still to be established.

### 1.1 Current capabilities

| Capability | Role |
| --- | --- |
| MIDI-Bus | Named, application-local broadcast endpoint. |
| MIDI-Message-Filter | Stateless whitelist/blacklist processing and pass-through wiring. |
| MIDI-Monitor | Observe messages in a terminal widget. |
| MIDI-Chord | Display chord names for held notes combined across subscribed inputs. |
| Sostenuto | Emulate sustain/sostenuto pedal ownership and optional retrigger protection. |
| MIDI-Emitter | Send user-entered MIDI messages and sequences. |
| MIDI-Remap | Channel, note, controller, curve, cross-type, substitution, and CC-trigger transformations. |
| Host panic | Explicit panic on all internal buses and physical outputs. |

MIDI 1.0 is required now. Native MIDI 2.0 processing is deferred. Future processing
modules are outside this release and need no DSL here. A possible later Rust version
does not change the requirement to deliver the first stable implementation on JDK 25.

### 1.2 No note tracking or automatic safety

The host and remapping modules must not maintain note-on/note-off pairing ledgers,
remember previously emitted note mappings, allocate microtonal channels dynamically,
or implement FIFO/LIFO pairing and automatic corrective note release.

Each note message is transformed using the configuration active when that message
is processed. Reload, plugin replacement/removal, disconnection, overflow, and
shutdown must not silently add note-offs, pedal resets, pitch-bend resets, or panic.
Resource cleanup, bounded buffering, error reporting, and explicit user-triggered
panic remain required.

The CC-trigger operation retains the minimal active/inactive state required for
threshold transitions and duplicate-trigger suppression. This is not a general
emitted-note ledger. Reload discards that state without generating corrective messages.

**[User addition, 30 October 2026]** The `midi-chord` observer maintains local held
input-note state solely for chord naming. Origin and immediate source do not partition
this state. The host/remap prohibition and absence of automatic corrective output remain.

**[User addition, 30 October 2026]** The `sostenuto` processor retains the bounded
input-note and pedal ownership state required for its explicit emulation behavior.
This exception does not permit general host/remap ledgers or lifecycle MIDI cleanup.

## 2. Technology and system structure

| Area | Required choice |
| --- | --- |
| Runtime/language | JDK 25; finalized language features. |
| Terminal UI | TamboUI. |
| Configuration | YAML through SnakeYAML, decoded as data into typed objects. |
| Build | Maven; Spotless and Checkstyle bound to `verify`. |
| Plugins | Hot-loadable JARs behind a versioned module API. |
| Legacy Windows MIDI | WinMM through JNA. |
| Modern Windows MIDI | Windows MIDI Services through a small native bridge with a stable C ABI, accessed through JNA. |
| Portable MIDI | `javax.sound.midi` behind the same host-owned backend interface. |

Separate device adapters, routing, configuration, module lifecycle, and UI services.
Processing modules must not depend on backend implementation classes. Physical
connections are host-owned and shared among instances that use the same endpoint.

One implementation can serve multiple independently configured module instances.
Instance names must be unique across the network. Input aliases, output aliases,
and bus names must be unique within one shared endpoint namespace across the loaded
application/network pair. Module instance names occupy a separate namespace.
Every module can declare input/bus subscriptions, output/bus
destinations, and zero or more widgets according to its role.

The host must mediate subscriptions and publication. Reject undeclared routes and
internal routing cycles during startup, reload, and plugin activation. External
physical cabling is outside internal graph validation.

### 2.1 Java object design

Use small immutable configuration/domain objects and typed message-family rules.
Do not build universal objects containing nullable fields for every message type,
backend, or transformation. Use sealed interfaces and type-specific records where
appropriate; compose genuinely shared constraints rather than duplicating them.

YAML shape differences must be normalized during decoding. Runtime rule fields
must have fixed types. Dispatch must not parse YAML, resolve configuration names,
inspect untyped configuration maps, or repeat configuration validation.

Follow clean-code and SOLID principles. Prefer established third-party libraries
and supported framework extension points over independent replacements. Preserve
third-party notices. Follow [AGENTS.md](AGENTS.md),
[source-style guidance](docs/source-style.md), `.editorconfig`,
`eclipse/java-style.properties`, and `checkstyle/checkstyle.xml` for maintained code.

## 3. Application configuration and MIDI backends

Use two independently versioned YAML documents:

- Application YAML: backend choice, host paths/settings, physical port bindings,
  and the selected network file.
- `network.yaml`: buses and configured module instances.

The network references logical port aliases and remains portable across machines
whose application files bind those aliases to different devices.

**[User naming decision, 30 October 2026]** Prefix backend-specific application
examples with `wmm-` for WinMM and `wms-` for Windows MIDI Services. Remove the
former `-services` suffix. For example, use `wmm-application-monitor.yaml` and
`wms-application-monitor.yaml`. Shared network files remain backend-independent.
The default application file is `config/wmm-application.yaml`, including in the
packaged image; update launch configurations and runnable documentation accordingly.

### 3.1 Application DSL

```yaml
schemaVersion: 1
application:
  backend: winmm
  moduleDirectory: ../modules
  networkFile: ./network.yaml
  ports:
    inputs:
      keyboard: "Example Keyboard Input"
      pedals: "Example Pedal Input"
    outputs:
      synth: "Example Synth Output"
```

| Attribute | Meaning |
| --- | --- |
| `schemaVersion` | Schema version; initially 1. |
| `application.backend` | Exactly one of `winmm`, `windows-midi-services`, `java-sound`. |
| `moduleDirectory` | Watched plugin directory; defaults to `./modules` relative to this file. |
| `networkFile` | Selected network document. |
| `ports.inputs`, `ports.outputs` | Mappings from logical aliases to physical endpoint bindings; not lists of single-entry mappings. |
| `ports.inputs.<alias>`, `ports.outputs.<alias>` as strings | Exact, case-sensitive discovered endpoint name. |
| `ports.inputs.<alias>.id`, `ports.outputs.<alias>.id` | Stable backend-native endpoint ID, where supported. |

Each binding is either a nonblank string selecting a physical name, or a mapping
containing exactly one `id` key with a nonblank string value. Stable-ID example:

```yaml
application:
  ports:
    outputs:
      synth:
        id: "backend-specific-stable-id"
```

This fragment replaces the `synth` binding in a complete application document.
Name and ID bindings may coexist under the same input/output mapping. There is
no `name` or `match` wrapper, no list-valued binding, and no implicit ID detection
based on the string's contents. Reject nulls, non-string names/IDs, empty strings,
unknown mapping keys, and duplicate aliases.

Decode both YAML forms into a homogeneous `Map<String, PortBinding>` with a typed
name-or-ID selector. YAML node-shape handling belongs only in configuration decoding;
the backend receives typed selectors, not arbitrary strings or maps to interpret.

**[Design completion D01]** Preserve explicit name-versus-ID selection and validation.
Prefer `id` when a stable backend identifier is available. Native enumeration indices
are not persistent IDs. Resolve to exactly one endpoint with the requested direction;
zero or multiple matches are errors. Reject `id` if the selected backend cannot
provide stable IDs. No substring matching, case folding, or arbitrary first match.

Endpoint uniqueness applies to logical aliases and bus names, not hardware display
names. For example, input alias `keyboard`, output alias `synth`, and bus name
`performance` are distinct. Declaring both an input and bus named `keyboard` is a
configuration error, even if no module currently references them.

Select one backend per run. There is no implicit fallback and no simultaneous
multi-backend operation. Show the selected backend and actual endpoint capabilities.
An unavailable service, native binding, or requested device must produce an
actionable error. Backend changes require restart; network reload does not switch
backends. Reconnection is explicit through the host rather than silent reassignment.

Cross-application port sharing depends on the backend, driver, and active OS MIDI
stack; neither input nor output access must be assumed universally shareable or
exclusive. Report failures to open a port, including access held by another
application, as actionable backend errors.

Conflicting notes, controllers, pitch bends, or other MIDI messages sent by different
applications to a shared output are outside MIDI Rat's scope. No cross-application
conflict detection, prevention, coordination, arbitration, or corrective processing
is required. Host-owned handle sharing among MIDI Rat instances does not imply
ownership or isolation of the receiving instrument's state.

All relative paths resolve against the YAML file declaring them, never the current
working directory. In a packaged `config/wmm-application.yaml`, `../modules` refers
to the distribution's top-level plugin directory.

### 3.2 Backend requirements

WinMM must use native callbacks and output handles through JNA. Include native
result checking, complete SysEx buffer handling, callback/native-memory ownership,
and reliable cleanup. A short-message-only implementation is insufficient. Input
callbacks perform bounded handoff work; device operations, rendering, and disk
logging must not occur inside them.

The modern Windows adapter must implement endpoint enumeration, service/API
availability checks, send/receive, timing conversion, and cleanup behind the native
C ABI bridge. Pin and package the chosen native SDK/binding for supported architectures.
The bundled JDK does not install the Windows service or guarantee its availability.

The Java Sound adapter must provide the same MIDI 1.0 host contract, including
SysEx, on supported portable platforms. Certification is per OS/JVM/device combination.

**[Design completion D02]** For the modern adapter's initial MIDI 1.0 contract,
expose each supported endpoint/group pair as a distinct logical discoverable port.
Keep its group metadata in the adapter. Group-specific IDs distinguish otherwise
identical names. Preserve supported MIDI 1.0 traffic; report unsupported native
MIDI 2.0 packets rather than silently narrowing or truncating them.

### 3.3 Near-real-time operation

Near-real-time behavior is mandatory. Routing must not wait for terminal rendering,
monitor history, logging, file watching, or plugin installation. Processing and UI
queues must be bounded. Overflow and transport errors must be visible and counted.

Preserve sequential order from one source; ordering between concurrent sources is
unspecified. Messages carry immediate-source identity and original host receipt
time outside the payload. Forwarding updates immediate source but retains original
receipt time. Prevent one subscriber from modifying another's payload.

Measure host receipt to output API submission: median, p95, p99, maximum, jitter,
loss count, and queue depth. Record backend, machine, device, and workload. Test
external loopback separately where available. Convert native timestamps explicitly;
nanosecond labels do not imply nanosecond measurement accuracy.

**[Design completion D03]** On processing-queue exhaustion, reject the incoming
message, increment the affected route's loss counter, and report overflow outside
the MIDI callback. Do not synthesize corrective messages. Dropping UI history must
not drop routing traffic. Numeric capacity/load/latency budgets are release gates
in Section 15; unlimited buffering is prohibited.

## 4. Shared network DSL and validation

```yaml
schemaVersion: 1
network:
  buses:
    - performance
  modules:
    - name: input-routing
      type: midi-message-filter
      sources: [keyboard]
      destinations: [performance]
      settings:
        whitelist: []
        blacklist: []
```

| Module field | Contract |
| --- | --- |
| `name` | Unique nonblank instance name. |
| `type` | Registered implementation type identifier. |
| `sources` | Nonempty string list of input aliases and/or bus names. |
| `destinations` | Nonempty string list of output aliases and/or bus names. |
| `settings` | Type-specific settings decoded by the implementation. |

Resolve each string to one endpoint in the shared namespace during configuration
loading. An input may be a source but never a destination; an output may be a
destination but never a source. A bus may be either. Unknown names, wrong-direction
references, blank/non-string entries, and mapping-valued references are errors.
Normalize names to immutable typed endpoint identities before runtime dispatch.

Rule-level filter/remap `sources` is also a string list, restricted to that module's
declared subscriptions. A monitor has sources and no destinations; an emitter
has destinations and no sources. Filters and remappers require both. Buses are
declared as a string list in `network.buses`, not as duplicate entries in `modules`.

**[Design completion D04]** Reject duplicate subscriptions/destinations within an
instance; they must not produce accidental duplicate traffic. Names/identifiers
are case-sensitive throughout configuration. Lists whose order determines output
must retain YAML order.

Validate the complete application configuration, network, and active plugin set
before activation. Reject unknown fields, duplicate YAML keys/names, explicit nulls
where a value is required, invalid values, unresolved references, unsupported
settings, and routing cycles. Diagnostics identify the configuration path and
available YAML line/column. Parse YAML as data with bounded parser resource use;
arbitrary Java object construction is prohibited.

Startup or an explicit reload activates configuration. Invalid reloads retain the
last valid configuration. Failed activation attempts rollback and reports the
actual state. Successful activation may interrupt affected flows. No note ledger
or automatic note/pedal/bend cleanup is added during a transition.

### 4.1 Integer notation and reusable selectors

Every integer configuration value must accept decimal and `0x` hexadecimal notation,
including scalar values, list entries, interval bounds, schema versions, and numeric
transformation settings. Hexadecimal digits may use either case; mixed notation is
valid. Signed integer settings such as transposition also accept a leading minus
sign, for example `-12` or `-0x0C`.

Notation changes representation only: `64`, `0x40`, and `0x0040` are equivalent.
Reject overflow before narrowing to Java types. Integer fields reject quoted numbers,
booleans, fractional values, and malformed literals. Decimal-versus-hexadecimal
support for numeric YAML fields is separate from raw hex message text in Section 9.

| Selector | YAML shape | Normalized object |
| --- | --- | --- |
| Numeric value | Integer or `{ min: integer, max: integer }`. | One immutable inclusive `IntRange`; scalar `n` becomes `[n, n]`. |
| `channels`, `programs`, `songs`, `statuses` | Nonempty integer list. | Immutable integer selection. |
| `notes`, `ccs` in match predicates | Nonempty integer list or `{ min, max }`. | One immutable integer-selection abstraction. |
| Rule `sources` | Nonempty list of nonblank strings. | Immutable resolved endpoint identities. |

Module `sources` and `destinations` likewise use nonempty lists of nonblank strings,
with the endpoint-direction checks in this section. The string-list syntax does
not remove endpoint kinds from the Java model; kind resolution happens at load time.

Intervals require exactly both bounds, `min <= max`, and valid field domains.
They do not wrap. Mixed lists such as `[64, { min: 66, max: 68 }]` are unsupported.
Omitting an optional predicate means unrestricted; an explicit empty predicate list
is invalid. Empty top-level whitelist/blacklist lists remain valid.

Channels are 1–16 in either notation. MIDI 1.0 note/controller/data-byte/program/song
values are 0–127. Pitch bend and song position are unsigned 14-bit values, 0–16383;
pitch bend center is 8192. Program numbering is wire numbering 0–127, not display
numbering 1–128. Raw statuses use their full 8-bit status values.

## 5. MIDI-Bus DSL

A bus is a named, host-managed, entirely application-local loopback endpoint.
It accepts multiple writers and broadcasts unchanged messages to all subscribers.
It must not create any OS-visible MIDI or Windows loopback endpoint. A widget or
separate processing instance is not required.

`network.buses` is a list of nonblank strings, each declaring one bus by name.
Block form (`- performance`) and flow form (`buses: [performance]`) are equivalent.
There is no `name` wrapper or other per-bus configuration in this release. Reject
non-string entries, blank names, duplicate names, and collisions with input/output
aliases in the shared endpoint namespace. Omitting `buses` or using `buses: []`
declares no internal buses. Resolve declarations to typed bus identities at load time.

```yaml
schemaVersion: 1
network:
  buses:
    - raw-keyboard
    - performance
  modules:
    - name: input-to-bus
      type: midi-message-filter
      sources: [keyboard]
      destinations: [raw-keyboard]
      settings: { whitelist: [], blacklist: [] }
    - name: bus-to-output
      type: midi-message-filter
      sources: [performance]
      destinations: [synth]
      settings: { whitelist: [], blacklist: [] }
```

`raw-keyboard` and `performance` are independent. This fragment demonstrates two
pass-through routes; another module must explicitly connect them if desired. A
separate physical-to-bus alias table is unnecessary: logical physical aliases live
in application configuration and modules provide the routes.

## 6. MIDI-Message-Filter DSL

A filter receives from one or more inputs/buses and forwards accepted payloads
unchanged to every configured destination. Blacklist rules override whitelist rules.
The filter is stateless and does not repair note-state consequences of its policy.
Its optional widget displays the effective settings.

```text
accept = (whitelist is absent/empty OR any whitelist rule matches)
         AND no blacklist rule matches
```

Within a rule, predicates are ANDed. Alternatives in a list predicate and separate
rules in a whitelist/blacklist are ORed. Each rule has exactly one uppercase scalar
`type`. There is no `types` attribute and `type` cannot be a list. To select multiple
types, create separate entries. `NOTE` is a named umbrella for the shared note-on
and note-off parameter family.

### 6.1 Fields and applicability

| Type | Meaning | Applicable semantic predicates |
| --- | --- | --- |
| `NOTE` | Note-on or note-off. | `channels`, `notes`, `velocity`. |
| `NOTE_ON` | Note-on with nonzero velocity. | `channels`, `notes`, `velocity`. |
| `NOTE_OFF` | Note-off, including note-on with zero velocity for matching. | `channels`, `notes`, `velocity`. |
| `CC` | Control change, including channel-mode CC. | `channels`, `ccs`, `value`. |
| `P_BEND` | Pitch bend. | `channels`, `value` (combined 14-bit). |
| `CH_PR` | Channel pressure. | `channels`, `pressure`. |
| `POLY_AT` | Polyphonic key pressure. | `channels`, `notes`, `pressure`. |
| `PC` | Program change. | `channels`, `programs`. |
| `TIME_CODE` | Time Code quarter-frame. | `data1` (encoded quarter-frame byte). |
| `SONG_POSITION` | Song position pointer. | `position` (combined 14-bit). |
| `SONG_SELECT` | Song select. | `songs`. |
| `TUNE_REQUEST` | Tune request. | No semantic data predicates. |
| `CLOCK` | Timing clock. | No semantic data predicates. |
| `START`, `CONTINUE`, `STOP` | Transport commands; use separate rules. | No semantic data predicates. |
| `ACTIVE_SENSING` | Active sensing. | No semantic data predicates. |
| `SYSTEM_RESET` | System reset. | No semantic data predicates. |
| `SYSEX` | Complete opaque SysEx message. | No payload-semantic predicates. |
| `SYSEX_START`, `SYSEX_END` | Raw framing categories only if an adapter exposes them. | No semantic data predicates. |
| `OTHER` | Exposed undefined/reserved statuses. | No invented semantic predicates. |

`velocity`, `value`, `pressure`, `position`, `data1`, and `data2` use scalar-or-range
syntax from Section 4.1. Use meaningful field names: generic `range`, `noteRange`,
and `ccRange` aliases are unsupported. Channel qualification on a channel-less system
message is an error, including `CLOCK` with channels.

All types can be constrained by `sources` and `statuses`. A raw `statuses` entry
contains the complete status byte including channel bits. Contradictory constraints,
such as channel 1 and status `0x91`, must fail validation.

Short messages may additionally use `data1` and `data2` where those bytes actually
exist for every message in the selected category. They constrain raw bytes, not
combined semantic values. They intersect with semantic constraints. For example,
PC has no `data2`; a padded display zero does not create a second payload byte.

### 6.2 Source selection

Rule `sources` is a plain string list, for example `[keyboard, performance]`.
Resolve each name against the containing module's resolved subscriptions at load time.
Any listed source may match. Omission means all of that module's subscribed sources.

A source means the immediate input port or internal bus that delivered the message.
It is not an additional connection object and does not trace the original hardware
through intervening buses. Reject unknown names and names not among the module's
subscriptions. Endpoint-name collisions are already rejected globally, so a rule
never needs an input/bus discriminator or a kind-precedence policy.

### 6.3 Note and system-message details

Normalize a note-on with velocity zero to NOTE_OFF for matching only. Preserve
the received payload representation when forwarding and displaying raw data.

Assemble SysEx to complete framed messages, separating interleaved system real-time
messages before filtering. Match `SYSEX` as an opaque category; manufacturer-specific
payload parsing is not required. Do not also deliver framing bytes as duplicate
standalone messages when already part of a complete SysEx message. Report malformed,
oversize, or incomplete assembly according to the adapter's bounded-buffer policy.

RPN/NRPN, bank selection, and paired 14-bit CC are matched as constituent CC messages.
Stateful transaction assembly is outside this filter DSL. Time Code quarter-frame
matching does not imply assembling a complete timecode.

### 6.4 Full instance example

```yaml
name: keyboard-filter
type: midi-message-filter
sources: [keyboard, pedals, performance]
destinations: [synth]
settings:
  whitelist:
    - { type: NOTE, channels: [0x01], notes: { min: 0x30, max: 84 } }
    - { type: CC, channels: [6], ccs: [0x40, 66], value: { min: 10, max: 0x7F } }
    - { type: P_BEND, channels: [7, 8], value: { min: 0x1C00, max: 0x2400 } }
  blacklist:
    - { type: CC, ccs: [64], sources: [pedals] }
    - { type: SYSEX }
    - { type: CLOCK }
```

CC64 from `pedals` is rejected even when a whitelist entry admits it. Matching CC64
from other subscribed sources is unaffected by that blacklist rule. The NOTE rule
has no velocity restriction so both attack and release velocities are admitted.

### 6.5 Additional filter examples

Each fragment below is a complete `settings` mapping for a filter with the required
module subscriptions/destinations supplied by its enclosing instance.

Suppress clock and active sensing while retaining other messages:

```yaml
settings:
  whitelist: []
  blacklist:
    - { type: CLOCK }
    - { type: ACTIVE_SENSING }
```

Velocity-limited attacks and unrestricted releases for a keyboard zone:

```yaml
settings:
  whitelist:
    - { type: NOTE_ON, channels: [1, 2], notes: { min: 48, max: 84 }, velocity: { min: 20, max: 127 } }
    - { type: NOTE_OFF, channels: [1, 2], notes: { min: 48, max: 84 } }
    - { type: CC, channels: [1, 2], ccs: [64, 66] }
  blacklist: []
```

This is stateless: it may forward a release whose attack was filtered. It does not
keep an accepted-note ledger.

Pressure and polyphonic key pressure:

```yaml
settings:
  whitelist:
    - { type: CH_PR, channels: [1], pressure: 0x40 }
    - { type: POLY_AT, channels: [1], notes: [60, 64, 67], pressure: { min: 20, max: 100 } }
```

Program selection and channel-mode CC, with blacklist precedence:

```yaml
settings:
  whitelist:
    - { type: PC, channels: [3], programs: [0, 0x10, 32] }
    - { type: CC, channels: [3], ccs: { min: 120, max: 0x7F } }
  blacklist:
    - { type: CC, channels: [3], ccs: [122] }
```

Transport, song selection/position, and quarter-frame data:

```yaml
settings:
  whitelist:
    - { type: CLOCK }
    - { type: START }
    - { type: CONTINUE }
    - { type: STOP }
    - { type: SONG_POSITION, position: { min: 0, max: 0x1000 } }
    - { type: SONG_SELECT, songs: [0, 1, 2] }
    - { type: TIME_CODE, data1: { min: 0, max: 127 } }
```

Retain opaque SysEx but reject reset and undefined statuses:

```yaml
settings:
  whitelist: []
  blacklist:
    - { type: SYSTEM_RESET }
    - { type: OTHER, statuses: [0xF4, 0xF5, 0xF9, 0xFD] }
```

Raw byte predicates equivalent to CC64 with value 64–127:

```yaml
settings:
  whitelist:
    - { type: CC, channels: [1], data1: 0x40, data2: { min: 64, max: 0x7F } }
```

### 6.6 Filter validation and Java decoding

| Invalid form | Diagnostic |
| --- | --- |
| `types` or list-valued `type` | One scalar type per rule; use separate entries. |
| `range`, `noteRange`, `ccRange` | Use the applicable explicit parameter/selection name. |
| `value: {10, 20}` | Require explicit min/max. |
| CC value 128 or `0x80` | Outside 0–127. |
| Pitch bend 16384 or `0x4000` | Outside 0–16383. |
| `channels: [0]` | Channels are 1–16. |
| `value: "0x40"` or a float | Integer required. |
| Reversed/missing bounds or unknown interval keys | Invalid interval. |
| `ccs: [64, { min: 66, max: 68 }]` | Mixed list shapes unsupported. |
| PC with `data2`, CC with `notes`/`velocity`, SYSEX with `ccs` | Parameter inapplicable to type. |
| Rule `sources: pedals`, typed mappings, empty list, or non-string entries | Nonempty string list required. |
| Unknown/unsubscribed rule source | Resolve to one of the module's subscribed endpoints. |

Use a sealed `MessageRule` family: note, CC, pitch bend, channel pressure, polyphonic
pressure, program change, and appropriately shaped system-message records. A note
record cannot contain CC as its kind. A parameterless system record must not contain
optional channel or data fields. Compose shared source/status constraints through
a small `RuleScope`; keep raw-byte matchers in applicable families.

The family decoder registry is keyed by scalar `type`. Decode integer scalars or
interval mappings into one validated `IntRange`; decode identifier lists/ranges
into immutable integer selections. Resolve source strings once into typed endpoint
identities. Reject unknown keys and unsupported shapes during loading, preserving
source locations. The runtime filter evaluates the resulting typed rules and list
precedence without dynamic configuration-shape checks.

## 7. MIDI-Monitor DSL

A monitor observes one or more inputs/buses and owns a terminal widget. It never
forwards messages. UI work and history retention must not block MIDI routing.

**[Design completion D05]** The following module settings define the initial monitor
configuration. Defaults are 2000 history rows, colors enabled when the terminal
supports them, and raw/decoded details available. History must be positive and bounded.
`colors: false` forces plain output. Unsupported color terminals fall back visibly.

```yaml
name: performance-monitor
type: midi-monitor
sources: [keyboard, performance]
settings:
  historyLimit: 2000
  colors: true
```

The widget must provide pause/resume, clear history, expanded raw/decoded details,
and copy-message text suitable for the emitter. Pause freezes display/history;
resume continues with current traffic rather than replaying an unbounded backlog.

| Main row field | Required information |
| --- | --- |
| `CH` | Channel 1–16 or `--` for system messages. |
| Type | Explicit channel/system type names from Section 6.1. |
| `D1`, `D2` | Decimal raw data-byte fields. |
| `Status` | Full decimal status byte, including channel bits. |
| Origin / Immediate | Unlabeled hardware-origin name then immediate input/bus alias, without wrapping brackets. |
| Time | Grouped-digit monotonic host receipt time labelled `ns`. |

```text
CH:  1 | NOTE_ON          | D1:  60 | D2: 100 | Status: 144 | [keyboard] | 1 234 567 890 ns
CH: -- | CLOCK            | D1:   0 | D2:   0 | Status: 248 | [performance] | 1 234 568 000 ns
```

Preserve color-coded message categories and field accents in the colored view.
Absent data bytes display as zero for layout continuity, but details must show
actual message length and raw hexadecimal bytes. Never transmit display padding.
Expanded fields decode note/controller/pressure/program/bend/system values where
applicable. SysEx details show actual length and raw bytes, with explicitly marked
truncated previews; truncating the view must not truncate routed payloads.

**[User decision D14]** Preserve the original input endpoint alongside the immediate
source when forwarding through buses and processing modules. Show the original input
in the first source column using its discovered hardware display name, and show the
immediate input/bus alias in the next column. These columns have no repeated text
labels. Expanded details also retain `via`. Subscription and rule `sources`
matching still uses only the immediate
source. Newly generated emitter, host, or asynchronous plugin traffic starts with
its first published bus as origin. No message-pairing or note ledger is involved.

Source column widths are fixed at startup from host-resolved device and network
names, rather than growing as messages arrive. Reload recomputes them. Pad shorter
names and ellipsize unexpected longer names. Display the 13 least significant
nanosecond digits, zero-padded and grouped with spaces, retaining full timestamps
internally. Use an 11-character type column with these monitor-only abbreviations:
`CONTINUE` → `CONT`, `TIME_CODE` → `TC`, `SONG_POSITION` → `SONG_POS`,
`SONG_SELECT` → `SONG_SEL`, `TUNE_REQUEST` → `TUNE_REQ`,
`ACTIVE_SENSING` → `ACT_SEN`, and `SYSTEM_RESET` → `SYS_RES`.
Configuration type names remain unchanged.

**[Design completion D06]** Display host receipt time relative to process start.
Keep the original receipt timestamp when a message traverses internal buses, and
show immediate source separately in expanded details. Render-time timestamps must not replace receipt
time. Copy-message strips source/time/colors and emits the syntax in Section 9.

### 7.1 MIDI-Chord observer

**[User replacement, 4 November 2026]** Implement `midi-chord` recognition in modern
Java 25 without third-party chord libraries or a JavaScript engine. The separate
[Chord module specification](docs/chord-specification.md) is authoritative for chord
recognition, labeling, configuration, ranking, and tests. It supersedes previous
library and naming decisions; the historical section is preserved in
[the decision archive](docs/history/chord-library-specification.md). Preserve the
observer-local held-note contract and MIDI isolation defined in the new specification.

## 8. MIDI-Emitter DSL

An emitter has one or more configured output/bus destinations and a widget for
editing/sending any valid MIDI 1.0 message or ordered sequence. It has no input
subscriptions. Support both raw hex and the shared readable message format.

**[Design completion D07]** `settings.format` is `hex` or `message-text`, defaulting
to `hex`. `messages` is a list of strings, defaulting to empty. Each entry encodes
one complete message. It provides initial widget content, not automatic startup
emission. Sending an empty sequence is an input error. Structured controls for
common messages should populate the same editors.

Hex example:

```yaml
name: manual-emitter
type: midi-emitter
destinations: [performance, synth]
settings:
  format: hex
  messages:
    - "90 3C 64"
    - "B0 40 7F"
    - "80 3C 00"
```

Readable equivalent with a system and SysEx message:

```yaml
name: text-emitter
type: midi-emitter
destinations: [performance]
settings:
  format: message-text
  messages:
    - "CH:1 | NOTE_ON | D1:60 | D2:100 | Status:144"
    - "CH:1 | CC | D1:64 | D2:127 | Status:176"
    - "CH:1 | NOTE_OFF | D1:60 | D2:0 | Status:128"
    - "CH:-- | CLOCK | D1:0 | D2:0 | Status:248"
    - "SYSEX | Bytes: F0 7D 01 F7"
```

The examples are independent sequences; the second has additional system messages.
On explicit Send, validate the entire sequence first, then send each message to
each destination in configured order. Per destination, message order is preserved.
Input errors must send nothing. Transport errors after dispatch may cause partial
delivery and must be reported with destination and progress. There is no scheduled
emission, timed playback, or repetition in this release.

**[Design completion D08]** Widget edits are session-local until explicit Save.
Save updates only that instance's `format` and `messages` in `network.yaml`, preserving
other settings and refusing to overwrite externally changed content without user
resolution. Reload reads the persisted initial content. No file write occurs on Send.

## 9. Shared MIDI message text and framing

This format is shared by monitor copy, emitter, and remap substitution. Parser output
must preserve actual MIDI payload length and validate all redundant fields.

### 9.1 Readable short messages

**[Design completion D09]** A readable short message has exactly these fields in
this order, separated by `|`:

```text
CH:<channel-or--> | <TYPE> | D1:<integer> | D2:<integer> | Status:<integer>
```

Channel/status/data integers accept decimal or `0x` hexadecimal. Ignore insignificant
whitespace around tokens/separators; do not merge separate numeric tokens. Type/label
spelling is exact and case-sensitive. The named type, channel, status, legal data
values, and payload length must agree.

For channel messages, CH is 1–16 and the status's low nibble must agree. For system
messages CH is `--`. Absent data fields use zero placeholders in this display syntax;
validate zero but do not emit the placeholder byte. A PC or CH_PR message therefore
has one data byte even when D2 is displayed. Zero-velocity NOTE_ON remains emit-capable
with its original status; only matching treats it as NOTE_OFF.

`NOTE` is a filter umbrella and is not emit-capable. OTHER may represent a raw
undefined single-byte status; it cannot invent data bytes. SYSEX_START/SYSEX_END
are monitoring/matching framing categories, not a substitute for a complete SysEx
entry in these editors.

An optional final `[source name]` annotation may be accepted when pasting text; it
is ignored for routing and must not select destinations. Timestamp fields are not
accepted message input. Monitor copy omits source/time and ANSI color codes.

```text
CH:0x01|NOTE_ON|D1:0x3C|D2:100|Status:0x90
CH:3 | PC | D1:16 | D2:0 | Status:194
CH:-- | START | D1:0 | D2:0 | Status:250
```

### 9.2 Hex and SysEx

In raw hex mode, each string/line contains one complete MIDI message as byte tokens,
such as `90 3C 64`. Byte tokens use exactly two hexadecimal digits, case-insensitive,
without `0x`; whitespace separates bytes. Omit running status: each message must
contain its status. Newlines or YAML list entries separate messages, not timing.

Readable SysEx uses `SYSEX | Bytes: <hex byte tokens>`. Require F0 at the start,
F7 at the end, and valid 7-bit payload bytes. Support backend buffer limits explicitly.
Manufacturer-specific parsing is not required.

**[Design completion D10]** Editor entries must not embed interleaved system real-time
bytes inside another message. Express a real-time message as its own entry. This
keeps entry framing unambiguous; receiving adapters must still separate legal
real-time interleaving from incoming wire traffic. Unterminated SysEx and partial
short messages are validation errors and never trigger partial input sends.

## 10. MIDI-Remap DSL

A remapper receives one or more inputs/buses and sends transformed messages to one
or more outputs/buses. Required capabilities are channel fan-out, transposition,
note lookup with optional pitch bend, multi-controller mapping, arbitrary configured
curves, CC/bend conversion, fixed message substitution, and CC-triggered notes.
Conditional suppression belongs to the filter; remap has no general drop action.

### 10.1 Rule processing

**[Design completion D11]** The concrete action vocabulary and YAML schema in this
section complete the previously unspecified remap DSL. A rule has one `match`, one
typed `action`, and `flow: stop` or `flow: continue`. Default flow is `stop`.
`match` uses exactly the single-rule filter vocabulary from Section 6, including
one scalar `type` and optional string-list `sources`; it is not a whitelist/blacklist.

Rules run in YAML order. Each incoming message starts one processing branch:

1. A nonmatching rule leaves that branch unchanged for the next rule.
2. A matching action replaces the branch with its ordered result messages.
3. `stop` finalizes those results; `continue` offers each result to subsequent rules.
4. Subsequent matches examine transformed payloads. Immediate-source metadata stays
   the original subscription source until the module publishes its results.
5. Branches reaching the end pass unchanged in their current transformed form.

Fan-out preserves configured order. A trigger action may produce no output when no
threshold transition occurs; this is its defined generation behavior, not a generic
suppression operation. Match/action incompatibility must fail validation before use.

### 10.2 Curves

Define named curves under `settings.curves` and reference them by name. Each curve
has at least two `{ input, output }` points, strictly increasing input coordinates,
and piecewise-linear interpolation. Output need not be monotonic: inversion and
arbitrary non-monotonic shapes are supported.

Clamp input outside the curve domain to the nearest endpoint. Round interpolated
output to nearest integer, halves upward. Validate output coordinates against the
target field's domain. Curves shared by multiple actions must be valid for each
target; reject undefined curve names. No analytic-function language or scripting
engine is required.

### 10.3 Actions

| `action.op` | Applicable match | Fields and result |
| --- | --- | --- |
| `channel-map` | Channel-message type. | `channels`: nonempty output channel list; emit one copy per channel. |
| `note-map` | NOTE / NOTE_ON / NOTE_OFF. | `entries`: note lookup; `fallbackTranspose`: signed integer, default 0; optional `bendRangeSemitones` when entries include bend. |
| `value-curve` | Type with the selected numeric field. | `field` and `curve`; replace that field using the curve. |
| `cc-map` | CC. | `controllers`: nonempty output CC list; optional `curve`; emit each CC with mapped or unchanged input value. |
| `cc-to-pitch-bend` | CC. | Required `curve`; emit P_BEND on the current channel. |
| `pitch-bend-to-cc` | P_BEND. | Required `curve` and nonempty `controllers`; emit each CC on the current channel. |
| `substitute` | Any supported match type. | `format` and nonempty `messages` using Section 9; emit the fixed sequence. |
| `cc-note-trigger` | CC. | `onThreshold`, `offThreshold`, `note`, `velocity`, `offVelocity`, `channels`, `passOriginal`; transition-driven note output. |

`value-curve.field` is one of `velocity`, `value`, `pressure`, `position`, valid only
for applicable matched types. It never means raw status or channel. `controllers`
and output `channels` are explicit integer lists, not match predicates. Output CCs,
notes, channels, velocities, and bends must stay in their MIDI domains. Transposition
clamps note results to 0–127; no out-of-range drop mode is defined.

Each note lookup entry is `{ from: integer, to: { note: integer, pitchBend: integer } }`;
`pitchBend` is optional, `note` is required. Duplicate `from` values are invalid.
Lookup entries override fallback transposition. An empty/omitted entries list with
a nonzero fallback is pure transposition. Unmapped notes otherwise pass unchanged.
Preserve the received note-on/off representation and velocity unless a separate
curve rule changes velocity.

Pitch bend is absolute 0–16383, center 8192. Set `bendRangeSemitones` explicitly when
using lookup bends and configure the receiving instrument to that range. The module
does not send automatic RPN bend-range setup. Emit the configured bend immediately
before a positive-velocity note-on. Do not emit a centering/reset bend at note-off.

Pitch bend affects an entire MIDI 1.0 channel. There is no automatic voice/channel
allocation, conflict detection between sounding notes, or note-mapping ledger.
The latest bend on a channel affects notes already sounding there. Independent
tuning requires explicit channel routing and appropriately configured instruments.
If mappings change while keys are held, subsequent note-offs use the new mapping;
the application must not attempt to recover the old one.

For CC triggers, maintain an initially inactive boolean per rule and subscribed
source/input-channel/controller tuple. Require `0 <= offThreshold < onThreshold <= 127`.
An inactive value at/above the on threshold emits NOTE_ON; an active value at/below
the off threshold emits NOTE_OFF. Intermediate/repeated values emit no note event.
Apply each transition to every configured output channel, in order. Require positive
note-on velocity and valid off velocity. `passOriginal` explicitly controls whether
the original CC precedes generated notes. Toggle behavior is out of scope.
No additional note ledger, output-ownership logic, or synthetic reload release exists.

### 10.4 Channel fan-out, lookup, and transposition example

```yaml
name: keyboard-remap
type: midi-remap
sources: [keyboard]
destinations: [performance]
settings:
  rules:
    - match: { type: NOTE, channels: [1] }
      action:
        op: channel-map
        channels: [2, 3]
      flow: continue
    - match: { type: NOTE, channels: [2] }
      action:
        op: note-map
        entries:
          - { from: 60, to: { note: 64 } }
          - { from: 62, to: { note: 67 } }
        fallbackTranspose: 12
      flow: stop
    - match: { type: NOTE, channels: [3] }
      action:
        op: note-map
        fallbackTranspose: -0x0C
      flow: stop
```

An input channel-1 note 60 generates channel-2 note 64 and channel-3 note 48.
Its later release is independently processed by the then-current configuration.

### 10.5 Microtonal lookup example

```yaml
name: fixed-channel-tuning
type: midi-remap
sources: [keyboard]
destinations: [synth]
settings:
  rules:
    - match: { type: NOTE, channels: [4] }
      action:
        op: note-map
        bendRangeSemitones: 2
        entries:
          - { from: 60, to: { note: 60, pitchBend: 0x2000 } }
          - { from: 61, to: { note: 61, pitchBend: 0x2800 } }
        fallbackTranspose: 0
      flow: stop
```

With a matching ±2-semitone receiver range, note 61 sends bend 10240 before its
note-on; channel 4 is not allocated or reserved automatically. Playing another
bend-mapped note on that channel changes the bend for all its sounding notes.

### 10.6 Multi-CC mapping and arbitrary curve example

```yaml
name: controller-curves
type: midi-remap
sources: [keyboard]
destinations: [synth]
settings:
  curves:
    inverted:
      points:
        - { input: 0, output: 127 }
        - { input: 127, output: 0 }
    shaped-velocity:
      points:
        - { input: 0, output: 0 }
        - { input: 32, output: 70 }
        - { input: 80, output: 50 }
        - { input: 127, output: 127 }
  rules:
    - match: { type: CC, ccs: [1] }
      action:
        op: cc-map
        controllers: [11, 74]
        curve: inverted
      flow: stop
    - match: { type: NOTE_ON }
      action:
        op: value-curve
        field: velocity
        curve: shaped-velocity
      flow: stop
```

CC1 value 0 becomes CC11 value 127 followed by CC74 value 127. The velocity curve
demonstrates a non-monotonic shape; it is not limited to range scaling.

### 10.7 CC and pitch-bend conversion examples

```yaml
name: expression-conversion
type: midi-remap
sources: [keyboard]
destinations: [synth]
settings:
  curves:
    cc-to-bend:
      points:
        - { input: 0, output: 0 }
        - { input: 64, output: 0x2000 }
        - { input: 127, output: 0x3FFF }
    bend-to-cc:
      points:
        - { input: 0, output: 0 }
        - { input: 0x2000, output: 64 }
        - { input: 0x3FFF, output: 127 }
  rules:
    - match: { type: CC, channels: [1], ccs: [2] }
      action: { op: cc-to-pitch-bend, curve: cc-to-bend }
      flow: stop
    - match: { type: P_BEND, channels: [2] }
      action:
        op: pitch-bend-to-cc
        controllers: [11, 74]
        curve: bend-to-cc
      flow: stop
```

The CC-to-bend curve explicitly maps CC64 to bend center rather than relying on
an assumed symmetric integer scaling. The reverse conversion can target multiple CCs.

### 10.8 Substitution and CC-triggered note example

```yaml
name: pedal-actions
type: midi-remap
sources: [pedals]
destinations: [performance]
settings:
  rules:
    - match: { type: CC, channels: [6], ccs: [20], value: 127 }
      action:
        op: substitute
        format: message-text
        messages:
          - "CH:3 | CC | D1:11 | D2:100 | Status:178"
          - "CH:3 | CC | D1:74 | D2:80 | Status:178"
      flow: stop
    - match: { type: CC, channels: [6], ccs: [22] }
      action:
        op: cc-note-trigger
        onThreshold: 64
        offThreshold: 32
        note: 60
        velocity: 100
        offVelocity: 0
        channels: [3, 4]
        passOriginal: false
      flow: stop
```

CC22 crossing upward to 64 emits note-on 60 on channels 3 and 4. Repeated high
values emit nothing. Crossing downward to 32 emits the corresponding configured
note-off messages. No toggle or held-note recovery across reload is implemented.

### 10.9 Remap object model and validation

Decode actions into a sealed transformation hierarchy: channel map, note map,
value curve, controller map, CC/bend conversions, substitution, and CC trigger.
Do not combine all their fields into a universal action record. Reuse the filter's
typed match objects, integer decoders, source resolution, and message-text codec.

Reject invalid action/type combinations, unknown operations/fields, missing required
settings, empty output lists, invalid/undefined curves, repeated lookup keys,
invalid thresholds, impossible output values, or malformed substitution messages.
Compile/validate configured message sequences and curve lookups at load time, not
while processing incoming MIDI.

### 10.10 Sostenuto processor

**[User packaging decision, 30 October 2026]** Sostenuto is an external module JAR,
registered through the standard `ModuleProvider` service descriptor and loaded from
`moduleDirectory`. Keep its implementation out of the host JAR. Build it as a
separate Maven project against the provided host API; the root reactor builds the
host, module, then distribution. Install the development JAR at `modules/sostenuto.jar`
and bundle it in the Windows image's `modules` directory. Preserve the existing
type ID, settings, example wiring, lifecycle, and processing behavior.

**[User addition, 30 October 2026]** Register `sostenuto` as a processor with sources,
destinations, and YAML settings. Maintain independent state per MIDI channel and
pitch, combining origins and subscribed sources after control-input selection.
Pass ordinary MIDI unchanged. Consume emulated sustain CC64 and sostenuto CC66.
Sustain engages at 64; sostenuto engages at the configured activation threshold
and releases at or below `max(0, activationThreshold - hysteresis)`.

On sostenuto engagement, latch currently pressed notes and notes already retained
by sustain. Later notes are not latched by repeated engagement values. Defer note-offs
while either pedal owns the note; hand retained releases between the two pedals
without releasing them. Forward unretained releases unchanged, including velocity-zero
note-ons. Pedal releases generate explicit note-offs with velocity zero, in pitch order,
only for deferred notes no longer pressed or owned. Pressed state is boolean per
channel/pitch, matching piano-key behavior; overlapping presses are not paired FIFO/LIFO.

**[Design completion D16]** The MIDI Rat settings are:

```yaml
- name: SOS
  type: sostenuto
  sources: [keys, pedals, performance]
  destinations: [processed]
  settings:
    controlSources: [pedals]
    trigger: {cc: 22, toggle: false, invert: false}
    activationThreshold: 10
    hysteresis: 2
    umbrella: {enabled: false, delayMs: 1, tailMs: 1}
```

All fields are optional; `settings: {}` uses the shown defaults, with no dedicated
control sources. `controlSources`, when present, is a nonempty unique list of the
module's subscriptions. Only matching triggers from these sources are processed;
other messages from those sources are consumed. Other subscriptions remain primary
inputs, including ordinary native CC66. Without a control selector, any source may
supply the configured trigger. Select `trigger.cc` or `trigger.note`, never both;
CC defaults to 22. Numbers are 0..127. `toggle` defaults false. `invert` defaults
false and applies only to CC input (`127 - value` before thresholding).

A momentary note trigger consumes positive note-on as CC66=127, and note-off or
velocity-zero note-on as CC66=0. Toggle mode changes once per press edge; releases
re-arm it, and repeated press values do not toggle again. CC toggle edges use the
same activation/release thresholds. Trigger state is independent per channel.
`activationThreshold` is 1..127 and `hysteresis` is 0..127. Unknown fields and invalid
settings reject preparation. Use existing filter/remap modules for channel selection,
output-channel mapping, and other transformations.

When enabled, umbrella mode protects a note-on whose pitch is retained or latched
on that channel: send CC64=127, delay its note-on by `delayMs`, then send CC64=0
after `tailMs`. Overlapping pulses share per-channel depth so only the first sends
pedal-down and the last sends pedal-up. Both timing values are 0..60000 milliseconds;
they are scheduler requests, not hard real-time guarantees. Bound outstanding timers
to 4096. On saturation, report a diagnostic and pass the retrigger without scheduling
new protection. Serialize pulse publications and preserve input origin, source
metadata, and receipt time through the host's delayed-forwarding API. If a release
would precede its scheduled note-on, cancel that pending retrigger before forwarding
the release, ending its pulse when no overlapping protection remains.

**Deliberate adaptation:** Incoming CC120/CC123 clears that channel's emulation and
trigger state and is forwarded unchanged; CC121 releases only that channel's deferred
unpressed notes, resets its pedals/trigger state, preserves pressed keys, and is
forwarded. These explicit messages cancel its pending umbrella work and end an
active pulse. They do not invoke global panic. To receive explicit host Panic, include
an internal bus among sources; the supplied demos use `musical-input` for this.
Reload, removal, and shutdown cancel scheduled work and discard local state silently,
without pedal resets, note-offs, or implicit panic. Delayed publications from an
inactive/removed route must be rejected by the host.

**[User example addition, 30 October 2026]** Supply both a Hammer keyboard/CC22
momentary-control example and a Hammer keyboard/Launchpad note-78 toggle-control
example, each with WinMM and Windows MIDI Services application configurations.
The Launchpad example uses `launchpad-main` as its dedicated control origin and
`trigger: {note: 78, toggle: true, invert: false}`. In both networks all eight
configured inputs (excluding loopback inputs `postSSA` and `postSSB`) feed one
musical-message filter. Whitelist NOTE, CC, P_BEND, CH_PR, POLY_AT, and PC; exclude
CLOCK, SysEx, transport, and other system messages. Both monitor and sostenuto
subscribe to its `musical-input` bus. The chord observer subscribes directly to
`sostenuto-output`; the processor also sends its output to physical `postSSB`.
Bind this output by exact name for WinMM and discovered stable endpoint/group ID
for Windows MIDI Services.

**[User example addition, 30 October 2026]** Both sostenuto example networks also
include a `midi-emitter` named `EMT`, with `destinations: [VirtOut]` (output alias for physical `postSSB`),
`format: hex`, and an initially empty `messages` list. It sends only when invoked
by the user and connects directly to the physical output. The same shared networks
serve all four WinMM/Windows MIDI Services application examples.

**[User instance naming revision, 1 November 2026]** Name these example instances
`M-IN` (musical filter), `MON` (input monitor), `SOS` (sostenuto), `CHRD` (chord
observer), and `EMT` (manual emitter). Keep bus and physical port aliases unchanged.

**[Design completion D17]** To retain dedicated controller identity through a shared
filtered bus, support `controlOrigins: [launchpad-main]` (or `[hammer-midi-3]` in
the CC22 example). Select either `controlOrigins` or `controlSources`, never both.
Origin selectors compare original logical endpoint aliases, not hardware display
names or the immediate bus. Require a nonempty unique list; at startup validate
each alias against `ModuleContext.originEndpoints()` before activation. Other messages
from a selected control origin are consumed, matching dedicated-source behavior.
Existing rule-level source matching remains based on the immediate source.

## 11. Host panic

The terminal host must expose an explicit Panic action covering every internal bus.
It is available independently of which widget is focused. Panic is a host control,
not a future plugin and not an automatic shutdown/reload behavior.

**[Design completion D12]** On Panic, publish the following messages on every internal
bus, for every MIDI channel 1–16, in this order:

1. CC64 value 0: release sustain.
2. CC66 value 0: release sostenuto.
3. CC123 value 0: All Notes Off.
4. CC120 value 0: All Sound Off.

Also send this sequence directly to every currently open bound physical output,
once per physical handle, bypassing processing rules. This makes the physical
panic independent of filters/remappers that may reject or transform bus traffic.
Ordinary bus subscribers still observe/process bus panic messages; routed copies
may therefore reach an output in addition to its direct sequence. No note ledger
is consulted. Do not implicitly reset pitch bend, reset all controllers, or emit
SYSTEM_RESET as part of this defined sequence.

Panic must be serialized through output dispatch, preserve each sequence's order,
and report unavailable targets, queue rejection, and send failures. It is best-effort;
it must not claim to silence disconnected devices. It does not infer/reset plugin
note history, and no such general history is maintained. Trigger booleans retain
their current state until a defined threshold transition or instance recreation.

Example channel-1 panic sequence, also usable as explicit emitter content:

```yaml
name: explicit-panic-sequence
type: midi-emitter
destinations: [performance]
settings:
  format: hex
  messages:
    - "B0 40 00"
    - "B0 42 00"
    - "B0 7B 00"
    - "B0 78 00"
```

This emitter example affects channel 1 on one configured bus. The host Panic action
always expands to all channels on all buses and directly opened physical outputs.

## 12. Plugin discovery, lifecycle, and configuration persistence

Watch the configured module directory for additions, changes, and removals. Built-in
capabilities are available without a separate install and follow the same instance
contract where applicable. Module code is trusted, in-process code; class-loader
separation is not a sandbox or process-level fault isolation.

Each package declares stable type ID, implementation version, compatible host API
version, and entry point. The API provides configuration validation, instance
creation, start/stop/cleanup, subscriptions/publication, optional widgets, and
structured errors. Reject conflicting types and incompatible API versions.

**[Design completion D13]** Use Java `ServiceLoader` discovery of the versioned module
provider interface inside isolated plugin class loaders. Provider metadata supplies
the type/version/API contract before instances activate. The exact Java package and
public interface signatures are implementation details, to be documented alongside
the API when code is introduced.

Debounce filesystem events and validate stable candidate files before activation.
Validate all affected instance settings in isolation before replacing active code.
On activation failure, attempt restoration and report actual active/unavailable
states. Removal stops affected instances while retaining their configuration;
unrelated instances continue.

Dispose of subscriptions, widgets, device references, worker resources, and old
class-loader references. Report non-cooperative cleanup. Temporary interruption of
affected flows is permitted; seamless state transfer and zero-loss replacement are
not required. Native callbacks must not reference disposed instances. No corrective
note release, note ledger, or implicit panic is part of replacement or removal.

Writes must preserve unrelated configuration and detect external changes. Use
validate-before-activation and avoid partial file writes. Exact YAML comment/layout
preservation is a presentation concern; semantic preservation is mandatory.

## 13. Terminal operation and reliability

The TamboUI host must provide a keyboard-navigable workspace, port/bus/instance
overview, multiple module widgets, backend/connection/lifecycle status, diagnostics,
explicit reload/reconnect/Panic/exit controls, and emitter Send/Save actions.
Widgets use host-owned UI scheduling and release resources when their instance stops.

**[User presentation addition, 30 October 2026; design completion D18]** Group the
application title and keyboard hints with clear spacing.
**[User revision, 1 November 2026]** Replace the top title/help panel with a single-line
focusable title, with a single-line dark-gray separator beneath it and initial focus on
the title. The separator keeps its gray color when the title is focused.
Include it in workspace focus navigation; `d` opens full-screen keyboard
help, Esc/`d` returns, and `[`/`]` scrolls help. Preserve active emitter editor input
above the widgets. Present module widgets inside
rounded borders. Present connection status in a focusable bordered host widget below
the modules. Keep active editor input visible above the widgets. Highlight the focused widget
with a cyan border and a textual focus marker; show its position, name, and type
in the title. Apply presentation-only token colors to module data (labels, numeric
values, on/off states, and separators), retaining the monitor's existing event palette
and exact fixed-column text. Honor `NO_COLOR`, dumb terminals, and widget color
preferences. Share available content rows equally between elastic monitors after
accounting for borders and spacing. On small terminals show the focused widget;
continue draining hidden monitors so their retained history remains current.

**[User presentation addition, 30 October 2026; design completion D19]** Adaptively
pack adjacent non-elastic module widgets into columns when their titles and current
content fit the terminal width. Preserve instance order and focus navigation, keep
elastic monitors on full-width rows, and return to stacked rows as the terminal
narrows. Measure terminal display cells, including borders and spacing; use the
tallest widget to determine each shared row's height. Module data and behavior stay
unchanged.

**[User presentation revision, 7 November 2026; automatic paging]** Pack module
rows into automatic workspace pages based on the current terminal dimensions,
preserving instance order and adaptive columns. Show the current page, total pages,
and module range when more than one page exists. Left/Right arrows select the previous/next
page with wrapping and focus its first module. Tab/`j`/`k` focus navigation follows
modules across page boundaries. Repack on resize while keeping the focused module
visible; preserve the current page when the title or host is focused. Keep the title,
active editor, and compact host status visible on normal pages. Continue draining
hidden monitors and keep all modules active. Retain focused-widget fallback when
a page cannot fit its content.
**[Design decision, 7 November 2026]** Reserve four monitor content rows when packing
pages; distribute remaining content rows equally among the visible elastic monitors.
Reserve one page-indicator line in the packing budget to avoid unstable page counts.

**[User chord-width revision, 1 November 2026; design completion]** Give the chord
widget a wider preferred minimum of 64 terminal cells, including borders and padding.
Honor widget width preferences through the module API; retain adaptive layout and
constrain widths to the available terminal width. Other widgets keep the 24-cell default.

**[User presentation revision, 1 November 2026; design completion D20]** Structure
the compact host widget as full-width Backend, Output, and Modules summary rows only.
Wrap long values using terminal display cells and allocate their wrapped height.
Show individual modules, timing, endpoint aliases, and activity in full-screen details.
Do not change module processing or other widget content.

**[User presentation revision, 1 November 2026; design completion D21]** Widget details
open a separate full-screen view, including host details; they do not expand inline.
Use `d` for the focused widget, `h` as a host shortcut, and Esc or the same key to return.
Keep hidden captures draining. Add a default module-details API adapter so existing
external providers remain compatible; rich details expose information and content
through capability interfaces, without concrete module checks in the UI.

The filter widget displays `6w | 0b`, an activity LED, and `Passed:` / `Blocked:`
lifetime decision counters. Its details use one panel with Rules, Passed, and Blocked
pages, initially Rules, cycling in that order with `m`/Tab. Show validated YAML on
the Rules page. The activity LED lights only on blocked messages; passed messages
do not light or extend it. **[User timing revision]** Use a 10 ms blocked-message
pulse, separate 2000-message bounded histories with nonblocking capture and visible
UI-drop counts, `[`/`]` to scroll rules, and `<`/`>` to pan
fixed-column message rows. Reuse the monitor's format, colors, pause/clear/copy,
and raw/decoded payload display (`x`). Pausing, clearing, or overflowing a log must
not change filtering or decision counters. Capture is in memory, not disk logging.

**[User presentation revision, 1 November 2026; design completion D22]** Include the
Connections / Activity host widget after the modules in the focus order and widget
position count. Apply the same focus border/marker and `d` detail action. Preserve
safe host selection when there are no modules; module-specific controls must not
act on a different module while the host is selected.

Host details cycle Status, Configuration, and Activity with `m`/Tab, using `[`/`]`
to scroll. Configuration describes resolved paths, exact bindings, loaded application
and active network YAML, instance settings/wiring, provider versions/API roles, queue
capacities, timing semantics, and explicit runtime controls. Use in-memory loaded
snapshots so unapplied file edits do not masquerade as active configuration. Do not
perform filesystem reads on the UI render path. Keep emitter editing/copy feedback
outside the compact host panel.

Recoverable instance errors stop or disable that instance and report an actionable
diagnostic; unrelated instances continue where possible. Report unrecoverable host
failures. Device loss marks affected routes unavailable without silent reassignment.
Explicit reconnect revalidates the selected binding before resuming traffic.

Orderly shutdown stops workers/instances, unsubscribes, disposes widgets, and closes
physical ports. It does not synthesize MIDI cleanup messages. Users can explicitly
Panic before exit. Required native resource cleanup remains mandatory.

Logs record configuration/plugin lifecycle, backend/device changes, routing errors,
queue loss, and panic outcomes. Full MIDI payload logging is opt-in. Diagnostics
must not corrupt terminal output or block dispatch.

## 14. Build, distribution, CI, and verification

Use a repeatable Maven build targeting JDK 25. Pin dependency/plugin/native SDK
versions. Integrate Spotless and Checkstyle into `verify` for maintained production
and test sources; exclude generated/vendored/build output. Apply the retained style
profiles and document checks that remain manual review requirements.

```shell
mvn spotless:apply
mvn spotless:check checkstyle:check
mvn --batch-mode --no-transfer-progress clean verify
```

These are the required commands once the Java build is implemented; a documentation
check does not establish that they ran or that application behavior was tested.

Package a Windows console app image with `jpackage`, a bundled Java runtime, required
native components, example configuration, plugin directory, and logs location.
Also produce an executable development/portable JAR with native prerequisites
documented. Preserve console interaction for TamboUI and enable native access required
by the binding. Select and validate the native bridge toolchain per architecture.

```text
MidiRat/
  MidiRat.exe
  app/
  runtime/
  config/
    wmm-application.yaml
    network.yaml
  modules/
  logs/
```

GitHub CI must cover branch pushes, pull requests, manual runs, and `v*` tags, using
Windows and JDK 25 for the packaged Windows artifact. Branch/PR jobs have read-only
repository permissions. Only the tag-release job receives publication permission.

The build contract is:

1. Validate the current specification and retained style/configuration files without
   depending on removable decision/companion documents.
2. Run Maven `clean verify`, including configured style/tests and packaging.
3. Require `target/jpackage-out/MidiRat/MidiRat.exe` and its bundled runtime.
4. Archive the app-image contents to `target/MidiRat-windows.zip`.
5. Upload the artifact; fail on missing package output.
6. For a version tag, publish the verified ZIP attached to that existing tag.

Until a root `pom.xml` exists, the existing workflow must identify branch/PR/manual
runs as specification/configuration-only checks. Version tags without a build must
fail rather than publish an empty application release. Once the descriptor exists,
Java/package gates are mandatory. The workflow has not yet been observed running
on GitHub as part of this specification work.

Tests must cover deterministic synthetic routing, cycle rejection, filter/DSL
validation, byte/text round trips, curve evaluation, remap processing order/fan-out,
trigger transitions, no-note-tracking behavior, panic broadcast, plugin lifecycle,
and backend contracts. Separately validate physical MIDI/SysEx, terminal behavior,
native loading, device loss/reconnect, timing/load, and clean-machine package launch
on every supported platform/architecture. Do not treat hosted CI as a real-device
latency measurement.

### 14.1 Acceptance criteria

| ID | Scenario | Required result |
| --- | --- | --- |
| AC-01 | Launch packaged application. | JDK 25 runtime, TamboUI workspace, configured capabilities; no separately installed JDK required by the Windows package. |
| AC-02 | Select each backend explicitly. | Correct adapter used; missing prerequisites reported without silent fallback. |
| AC-02a | Access a port also used by another application. | Shared access proceeds where supported; access failures are reported. Cross-application message conflicts require no detection or coordination. |
| AC-03 | Bind case-different, ambiguous, or missing endpoints. | Exact case-sensitive resolution or actionable error; no arbitrary device choice. |
| AC-04 | Rebind devices with the same network. | Application aliases change; network wiring retains its names. |
| AC-05 | Use a bus with several writers/readers. | Unchanged broadcast payloads, per-source order, independent subscriber ownership, no OS-visible bus. |
| AC-06 | Configure a cycle or duplicate route. | Activation rejected before traffic starts. |
| AC-06a | Reuse an input/output alias or bus name across endpoint kinds. | Shared-namespace collision rejected before activation, even when unreferenced. |
| AC-06b | Use an output as a source or an input as a destination. | Wrong-direction reference rejected; buses remain valid in either position. |
| AC-06c | Declare buses using string names. | Block/flow lists are equivalent; mapping entries, blank/duplicate names, and endpoint collisions are rejected. |
| AC-07 | Match whitelist and blacklist. | Blacklist wins; empty whitelist allows otherwise-unblocked traffic. |
| AC-08 | Load scalar/range and decimal/hex equivalents. | Identical typed numeric meaning and match results, with bounds checked. |
| AC-09 | Use plural types, inapplicable predicates, or bad rule sources. | Load-time error at the affected rule/path. |
| AC-10 | Receive note-on velocity zero. | Matches NOTE_OFF/NOTE while retaining original bytes. |
| AC-11 | Monitor channel/system/SysEx messages. | All required row/detail fields, bounded history, pause/clear/copy, no routing blockage. |
| AC-12 | Emit equivalent hex and readable messages. | Identical actual bytes and correct lengths; whitespace differences harmless. |
| AC-13 | Enter invalid sequence or contradictory fields. | Whole-input validation sends nothing; runtime transport failures report partial progress. |
| AC-14 | Receive/emit SysEx on each backend. | Complete framed payload within documented limits; truncation only in marked UI preview. |
| AC-15 | Apply channel/controller fan-out and continue/stop. | Ordered outputs and matching against transformed payloads follow Section 10. |
| AC-16 | Evaluate custom curves and CC/bend conversions. | Correct interpolation, endpoint clamping, rounding, target domains, and center mapping. |
| AC-17 | Use lookup, transposition, and microtonal bend. | Lookup wins, fallback applies, bend precedes positive note-on; no automatic allocation or note ledger. |
| AC-18 | Change mapping while a key is held. | Subsequent release uses current mapping; no historical pairing or synthetic correction. |
| AC-19 | Exercise CC trigger around thresholds. | One transition-driven event per configured output channel; hysteresis and duplicate suppression; no toggle. |
| AC-20 | Invoke host Panic. | Defined sequence on every bus/channel and direct open outputs; diagnostics for failures; no implicit panic elsewhere. |
| AC-21 | Reload/remove/replace/stop. | Resource cleanup and reported states; rollback attempts where applicable; no synthetic note/pedal/bend recovery. |
| AC-22 | Stress dispatch with UI/logging and bursts. | Bounded queues, visible loss policy, agreed latency/jitter/load budgets. |
| AC-23 | Run CI and package checks. | Required style/tests/artifact gates after build exists; no dependence on removable companion documents. |

## 15. Consolidation decisions and release gates

### GitHub Pages network viewer

User requirement: provide a static GitHub Pages site that interprets browsed or
dragged-and-dropped network YAML and renders a diagram. Use plain HTML/CSS/native
JavaScript modules and ELK.js with SVG, matching the requested existing technology.
Use established third-party libraries: `yaml` for parsing, ELK.js for layout, and
Panzoom for interaction. Pin dependencies and retain their licenses in vendored assets.

Implementation decisions: parse entirely in the browser without uploads or a backend.
Accept optional application YAML to resolve backend and physical device bindings;
infer endpoint roles when only a network is provided. Show buses, modules, ports,
fan-out, node settings, and connections. Support pan/zoom, both layout directions,
generic examples, editable YAML, and standalone SVG export. The diagram panel has
Diagram and YAML editor tabs; use Ace for YAML syntax highlighting. Ctrl+scroll zooms
around the pointer; ordinary scrolling keeps page scrolling. A zoom slider stays in
sync with the diagram scale. Preserve the anchor point during zooming out and at
clamped scale limits. Provide a separate 10–32 px font-size slider for the YAML editor.
Use the heading "MIDI-Rat Network viewer" without the
introductory slogan, description, or local-file badge. Validate YAML, structure,
endpoint references, and routing cycles; full module-settings validation remains the
host's `--validate` responsibility. Reject aliases, custom tags, duplicate keys, and
oversized inputs, consistent with the host's restricted configuration approach.
Publish an allowlist of runtime assets through a separate checked Pages workflow;
do not include personal device configurations in the site artifact.

The user confirmed the earlier architecture, backend bridge, application/network
split, timing instrumentation, explicit backend selection, monitor/text improvements,
curves, lookup precedence, trigger hysteresis, and Maven/package approach. Device
matching is case-sensitive. Ordered remap processing is approved. Note pairing and
automatic safety were explicitly rejected. Panic is explicit and covers all buses.
The accepted filter review resolves the former N07 syntax discussion: one type,
typed families, meaningful numeric fields, scalar/range normalization, decimal/hex
integers, and rule-level string-list sources. The subsequent YAML simplification
uses `application.backend`, alias-to-name port mappings with explicit `{ id: ... }`
alternatives, and string lists for bus declarations and module sources/destinations,
with unique endpoint names and direction validation.

The following additions resolve details not previously specified exactly:

| Marker | Design completion |
| --- | --- |
| D01 | Typed name-or-ID selector and unsupported-ID validation; accepted YAML forms are alias-to-name strings or explicit `{ id: ... }` bindings. |
| D02 | Modern MIDI 1.0 adapter endpoint/group port exposure. |
| D03 | Reject incoming message on queue exhaustion, report loss, no corrective MIDI. |
| D04 | Duplicate route rejection and case-sensitive configuration identifiers. |
| D05 | Monitor settings/default history limit. |
| D06 | Monitor receipt-time origin at process start. |
| D07 | Emitter initial format/messages schema and defaults. |
| D08 | Explicit emitter Save scope and conflict behavior. |
| D09 | Fixed readable short-message grammar and field validation. |
| D10 | Complete-message editor framing without embedded real-time bytes. |
| D11 | Concrete remap actions/schema and default stop flow. |
| D12 | Exact all-channel panic sequence and direct physical-output path. |
| D13 | ServiceLoader plugin discovery. |
| D14 | User-requested origin display: preserve original input across buses; keep rule source matching immediate. |

Before declaring a stable release, establish measured latency/jitter targets,
sustained/burst workloads, queue capacities, maximum SysEx size/assembly timeout,
and supported OS/architecture/terminal/device matrix. Pin and test the selected
native SDK/toolchain and dependencies, publish the module API signatures, and
record actual platform/package test results. These are implementation/release
qualification tasks; no invented measurements are claimed here.
