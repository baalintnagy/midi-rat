# Chord observer

`midi-chord` displays chord names for all held notes reaching the module, combining
all subscribed sources, origins, and MIDI channels. The current monitor demo already
includes it; both WinMM and Windows MIDI Services launch configurations use it.

```yaml
- name: input-chord
  type: midi-chord
  sources: [monitor-input]
  settings: {maxChords: 8}
```

No destinations are allowed. Each instance has independent state and displays the
ranked candidate names, followed by a single `Notes` line.
`maxChords` is an optional nonnegative integer limiting the number of ranked chord
labels shown at once. Zero or omission means unlimited; a positive value keeps the
first candidates after ranking. The value appears only in the module's details
configuration panel, leaving the workspace widget at two lines. Supplied recognition
overrides also appear in that panel. Example networks
use 8. The limit affects display only, not held-note recognition or the Notes line.
Notes use the monitor's pitch-class labels: `C`, `D`, etc., and both enharmonic
spellings for accidentals such as `(C#,Db)`. Labels follow the same order as the
held MIDI pitches; different octaves can therefore show the same label. Numeric MIDI
codes are not displayed. `--` means
no held notes; `unrecognized` means the recognizer found no chord for the current set.
Inversions retain the lowest held pitch as bass.

Positive note-ons add held keys; note-offs and velocity-zero note-ons release them.
Repeated note-ons for the same channel/pitch are retriggers, not additional held
voices. One release from any source clears that channel/pitch. This also handles
processors that retain several releases and emit one final note-off. The same pitch
held on another channel remains active. Unmatched releases are ignored.
All channels contribute to the same chord. Sustain/sostenuto pedals do not prolong
held-key lifetime. Explicit incoming CC120/CC123 clears that channel's held state.
Reload starts empty; the module never emits MIDI or corrective note-offs.

Recognition is implemented in Java 25 with a YAML-defined vocabulary and configurable
labeling and ranking. See [the chord specification](chord-specification.md) for the
complete contract, source references, settings, and examples. No JavaScript engine
or third-party chord library is required.
