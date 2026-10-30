# MIDI 2.0 from the first release — scope and cost assessment

**Date:** 30 October 2026  
**Status:** Assessment requested by A01; no expansion of release scope is approved.  
**Related specification:** [Iteration 2](SPECIFICATION-v2.md).

## 1. Assessment

Supporting MIDI 2.0 at launch is feasible, but it adds a protocol-wide workstream,
not just another driver. The modern Windows backend is already required for the
MIDI 1.0 release; its baseline cost must not be counted twice when estimating the
incremental cost of MIDI 2.0.

**Planning recommendation:** Design the event and plugin boundaries to accommodate
both MIDI byte messages and UMP now. Deliver the confirmed MIDI 1.0 functionality
first unless native MIDI 2.0 devices are an immediate requirement. If MIDI 2.0 must
ship at launch, choose a clearly bounded scope from Section 3 before implementation.

All effort figures below are **estimates introduced for this assessment**, not
measurements or commitments. They assume one experienced engineer familiar with
Java and native Windows integration, available test hardware, and the required
modern Windows MIDI 1.0 backend already implemented. They exclude the base host,
UI, plugin loader, MIDI 1.0 processing modules, and baseline CI work.

## 2. What MIDI 2.0 support entails

UMP is a packet representation that can carry MIDI 1.0 and MIDI 2.0 messages.
Accepting MIDI 1.0 inside UMP is distinct from understanding native MIDI 2.0
semantics. The [MIDI Association's UMP overview](https://midi.org/universal-midi-packet-ump-and-midi-2-0-protocol-specification)
defines that distinction.

MIDI 2.0 adds capabilities beyond legacy channel messages, including higher-resolution
values and per-note control. MIDI-CI, profiles, and property exchange are additional
capability families with their own implementation requirements; they must not be
assumed complete merely because UMP routing works. See the
[MIDI 2.0 architecture overview](https://midi.org/midi-2-0).

### Current platform constraints

The modern Windows API is UMP-based and supports access to MIDI 1.0 and MIDI 2.0
devices. Microsoft documents OS, API-presence, and service-availability checks before
choosing it. Keep the selectable WinMM path for systems that cannot use the modern
API. See [API integration guidance](https://microsoft.github.io/MIDI/kb/integrating-midi1-and-midi2-apis/).

Microsoft currently documents Windows 11 25H2 or later for the modern API. Pin and
test the exact supported SDK/OS combination; do not infer API availability simply
from the presence of a MIDI device. See
[minimum requirements](https://microsoft.github.io/MIDI/kb/minimum-requirements/).

The current release notes distinguish the API package from tool-local DLLs and set
conditions for distributing preview API components. Do not copy a tools DLL into
the application as an SDK substitute. Recheck the selected release and distribution
terms when packaging; see [official release notes](https://github.com/microsoft/MIDI/releases).

For planning, treat WinMM and the initial Java Sound adapter as legacy MIDI 1.0
boundaries. Cross-platform native UMP backends require separate platform work and
are not included in the Windows-focused estimate below.

## 3. Possible launch scopes

| Scope | Included behavior | Excluded behavior | Honest capability claim |
| --- | --- | --- | --- |
| S0: confirmed release | Three backends; MIDI 1.0 network; supported MIDI 1.0 traffic through the modern API. | Native MIDI 2.0 semantic processing. | MIDI 1.0 application with modern Windows access. |
| S1: UMP-aware routing | Preserve complete UMP packets and group metadata; route/monitor/inject supported raw packets; expose endpoint capabilities. | Broad high-resolution filtering/remapping; general conversion; MIDI-CI feature implementation. | Defined UMP transport/routing subset; not a blanket MIDI 2.0 feature claim. |
| S2: useful native MIDI 2.0 | S1 plus selected MIDI 2.0 channel-voice filtering, display, emission, remapping, per-note fields, and an explicit conversion policy. | Full profiles/property-exchange catalog; all device-specific functionality. | MIDI 2.0 support with a published message/capability matrix. |
| S3: capability-aware platform | S2 plus specified MIDI-CI discovery/negotiation and selected profiles/property-exchange behavior. | Unspecified vendor features and untested profiles. | Exactly the documented and tested feature set. |

S1 is useful infrastructure, but it does not satisfy users who expect high-resolution
CC, per-note expressive processing, or transparent MIDI 1.0/MIDI 2.0 conversion.
Before making a compatibility claim, verify the relevant specification's minimum
requirements and publish precisely what is implemented.

## 4. Additional work for S2

| Work package | Incremental estimate | Deliverable |
| --- | --- | --- |
| Event representation and plugin API | 4–7 engineer-days | Payload variants, protocol/group metadata, capabilities, versioned contracts, no implicit narrowing. |
| Native UMP preservation and modern endpoint mapping | 3–6 days | Complete packet boundaries, group mapping, timestamps, send/receive ownership. |
| MIDI 2.0 codecs and validation | 4–8 days | Selected message families, unsigned high-resolution fields, raw-packet validation. |
| Filter DSL extensions | 3–5 days | Protocol/group selection, wider values, applicable per-note parameters. |
| Monitor and emitter extensions | 3–5 days | UMP detail, readable high-resolution fields, reversible text/raw editing. |
| Remapping, curves, and note-state handling | 5–9 days | Resolution-aware transforms, per-note semantics, stateful release correctness. |
| Conversion policy and implementation | 4–8 days | Explicit MIDI 2.0 to MIDI 1.0 loss/rounding policy and unsupported-feature handling. |
| Interoperability, timing, packaging, and documentation | 5–9 days | Real-device/loopback verification, capability matrix, native package checks. |
| **Subtotal** | **31–57 engineer-days** | Windows S2 increment over S0. |
| **Planning reserve, approximately 25%** | **8–15 days** | Native binding, device, and specification uncertainty. |
| **Planning total** | **39–72 engineer-days** | Approximately 8–15 working weeks for one full-time engineer. |

These packages overlap conceptually but are counted once: event contracts define
representation, codecs define protocol semantics, and native mapping handles the
OS boundary. A three-backend MIDI 1.0 implementation is a separate baseline cost.

For S1 alone, allow approximately **10–20 additional engineer-days before reserve**
for representation, native preservation, raw monitor/emitter integration, and tests.
This estimate is an alternative to S2, not an amount to add on top of it.

For S3, allow **another 15–35 engineer-days before reserve** for a small, explicitly
selected MIDI-CI/profile/property-exchange subset. An unrestricted feature catalog
cannot responsibly be estimated without choosing devices and capabilities.

Monetary cost is `approved engineer-days × loaded daily rate`, plus hardware and
any external certification/validation costs. No rate, currency, device inventory,
or procurement budget has been supplied, so a currency total would be invented.

## 5. Architecture decisions that reduce later cost

**Proposed design decisions, pending approval:**

1. Use a host-owned event envelope with protocol, payload variant, source, receipt
   time, and backend-native timing metadata; do not hard-code a three-byte payload.
2. Preserve complete raw UMP packets where enabled. Use byte arrays for MIDI 1.0
   and a distinct UMP representation; do not force both through `ShortMessage`.
3. Represent optional group separately from channel. Do not silently map every group
   to the same MIDI 1.0 port/channel space.
4. Declare module capabilities. A MIDI-1.0-only module must not receive native MIDI 2.0
   as if it understood it. Reject, bypass explicitly, or use a declared converter.
5. Keep filter numeric domains and curve target resolution explicit. Do not apply
   7-bit clamps to higher-resolution fields.
6. Make lossy conversion visible. Quantization and missing per-note equivalents
   require policy; an OS translation path is not a universal lossless guarantee.
7. Keep an independent compatibility matrix for transport, protocol, message families,
   MIDI-CI features, and application modules.

These decisions reduce redesign risk but still need validation. A credible savings
percentage cannot be derived before the host and native bridge designs exist.

## 6. Decision gate and acceptance

Before committing to launch support:

- Choose S1, S2, or S3 and the exact message families, native architectures, and devices.
- Run a time-boxed **3–5 engineer-day spike** to prove Java/native UMP receive/send,
  full packet retention, timestamp interpretation, and package loading on a clean
  Windows test system. Treat spike effort as part of the work-package estimate, not
  an automatic extra charge.
- Decide whether conversion is required and which losses are acceptable.
- Establish the timing/load acceptance budgets shared with the MIDI 1.0 release.
- Revise estimates using spike findings and real device availability.

Acceptance must demonstrate full-width values and group preservation, selected
per-note message behavior, monitor/emitter round trips, module capability rejection,
explicit lossy conversion, state-correct releases, mixed legacy/native endpoints,
and real timing measurements. Tests must not claim broad MIDI 2.0 support after
only sending MIDI 1.0-formatted traffic through the modern Windows API.
