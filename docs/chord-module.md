# Chord observer

`midi-chord` displays chord names for all held notes reaching the module, combining
all subscribed sources, origins, and MIDI channels. The current monitor demo already
includes it; both WinMM and Windows MIDI Services launch configurations use it.

```yaml
- name: input-chord
  type: midi-chord
  sources: [monitor-input]
  settings: {}
```

No destinations are allowed. Each instance has independent state and displays the
library's candidate names in order, followed by the held MIDI pitches. `--` means
no held notes; `unrecognized` means the library found no chord for the current set.
Inversions retain the lowest held pitch as bass.

Positive note-ons add held keys; note-offs and velocity-zero note-ons release them.
Overlapping presses of the same channel/pitch are counted across sources, so one
release does not remove a pitch still held by another press. A release from any
source can decrement that channel/pitch count. Unmatched releases are ignored.
All channels contribute to the same chord. Sustain/sostenuto pedals do not prolong
held-key lifetime. Explicit incoming CC120/CC123 clears that channel's held state.
Reload starts empty; the module never emits MIDI or corrective note-offs.

Chord recognition comes from [Tonal](https://github.com/tonaljs/tonal), not a local
chord dictionary. [Rhino](https://github.com/mozilla/rhino) runs its bundled JavaScript
inside Java. No network, Node, or npm is required to run or package the application.
See [the bundle provenance](../tools/tonal/README.md) to regenerate the asset.
