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
library's candidate names in order, followed by a single `Notes` line.
`maxChords` is an optional nonnegative integer limiting the number of ranked chord
labels shown at once. Zero or omission means unlimited; a positive value keeps the
first candidates after ranking. The value appears only in the module's details
configuration panel, leaving the workspace widget at two lines. Example networks
use 8. The limit affects display only, not held-note recognition or the Notes line.
Notes use the monitor's pitch-class labels: `C`, `D`, etc., and both enharmonic
spellings for accidentals such as `(C#,Db)`. Labels follow the same order as the
held MIDI pitches; different octaves can therefore show the same label. Numeric MIDI
codes are not displayed. `--` means
no held notes; `unrecognized` means the library found no chord for the current set.
Inversions retain the lowest held pitch as bass.

Positive note-ons add held keys; note-offs and velocity-zero note-ons release them.
Repeated note-ons for the same channel/pitch are retriggers, not additional held
voices. One release from any source clears that channel/pitch. This also handles
processors that retain several releases and emit one final note-off. The same pitch
held on another channel remains active. Unmatched releases are ignored.
All channels contribute to the same chord. Sustain/sostenuto pedals do not prolong
held-key lifetime. Explicit incoming CC120/CC123 clears that channel's held state.
Reload starts empty; the module never emits MIDI or corrective note-offs.

Chord recognition comes from [Tonal](https://github.com/tonaljs/tonal), not a local
replacement chord dictionary. The adapter registers the major added-fourth type
(`1P 3M 4P 5P`, aliases `add11` and `add4`) through Tonal's supported
`ChordType.add` API because the bundled dictionary omits it. This general extension
supports every key and inversion; for example, C–G–C–E–F prefers Cadd11 while keeping
Emb6b9/C as an alternative. [Rhino](https://github.com/mozilla/rhino) runs its bundled JavaScript
inside Java. No network, Node, or npm is required to run or package the application.
See [the bundle provenance](../tools/tonal/README.md) to regenerate the asset.

The same API registers `1P 3m 7m` as `m7(no5)`, another missing general chord type.
C–Eb–Bb therefore displays Cm7(no5); transpositions and inversions are supported
without assuming or adding an unplayed fifth.
Likewise, `1P 3M 7M` is registered as `maj7(no5)`: C–E–B displays Cmaj7(no5),
with the same support for all roots and inversions.

Chord labels include both sharp and flat spellings returned by Tonal, without
duplicates. Candidates with fewer alterations in the chord quality (such as #5 or
b9) come first, then root-position readings before slash alternatives, followed by
fewer accidentals in Tonal's spelled chord tones,
then fewer sharp/flat symbols in the whole label. For example, Db major
precedes C# major, C# minor precedes Db minor, and Fadd9/A precedes Am7#5.
Implementation decision: root and bass accidentals do not count as quality
alterations. Count each sharp or flat once (double accidentals twice), with original
root-position labels preferred on ties, then fewer upper chord tones between slash
labels, then detection order (sharp spellings first). This keeps C/F ahead of
Cadd11/F while preferring Cadd11 when C is the bass.
Within an enharmonic family (same root pitch class, quality, and bass pitch class),
prefer fewer key-signature alterations for the root, then for the bass. Use Tonal's
natural minor key for minor roots and major key otherwise; bass spelling uses major.
This reorders only spellings in their existing positions, not distinct interpretations.
Thus Bbadd9 and A#add9 precede Dm7(no5)/Bb, which precedes Dm7(no5)/A#.
Plain major labels omit M: C, Db, Fadd9/A. Major seventh and related extended
qualities use maj instead, preserving the distinction between Cmaj7 and C7.
Added-fourth qualities consistently display `add11`, including `madd11` and `m7add11`.
Tonal's bare `4` quartal alias is displayed as `m7add11(no5)` instead: for example,
G–C–F–Bb becomes Gm7add11(no5). This preserves the actual notes without implying
the absent fifth D. Both display aliases apply to all roots and inversions.
This is a display preference without key-context inference. Tonal provides the spellings and
chord tones; the host adapter combines and orders the labels because Tonal has no
accidental-ranking option.

The adapter also asks Tonal to recognize the upper chord after excluding the lowest
held pitch class, including its octave doublings, then appends that bass to the
result. Thus F–E–G–C–F can display C/F alongside the full-set interpretations.
Implementation decision: this additional pass requires at least three distinct upper
pitch classes; it removes no other notes. All candidates use the same simplicity
ranking, with full-set detections preferred on otherwise exact ties. This is a display-only
extension using Tonal's chord dictionary, with no change to held-note or MIDI behavior.
