# Historical library-based chord decision record

Superseded by ../chord-specification.md on 4 November 2026.

**[User addition, 30 October 2026]** Register `midi-chord` as an observer with one or
more sources and no destinations. Display names supplied by an
established chord recognition library; do not implement a custom chord dictionary
or recognition algorithm. Use Tonal 5.1.3 embedded through Rhino 1.8.1.

**[User addition, 4 November 2026]** Accept optional `settings.maxChords`, a
nonnegative integer limiting the ranked chord candidates displayed simultaneously.
Implementation decision: zero or omission retains unlimited display; examples use
8. Apply the cap after ranking, retaining the Notes line. Show the setting only in
module details, not in the workspace widget.

**[User addition, 4 November 2026]** Support major added-fourth labels such as
`Cadd11` ahead of altered interpretations of the same notes. Implementation decision:
register the missing general `1P 3M 4P 5P` chord type through Tonal's supported
`ChordType.add` API, with `add11` as the preferred alias and `add4` as an equivalent
alias. This narrow dictionary extension applies to all roots and inversions; Tonal
continues to perform recognition. Retain its alternative interpretations.

**[User addition, 4 November 2026]** Recognize minor sevenths with an omitted fifth,
such as C–Eb–Bb as `Cm7(no5)`. Register the general `1P 3m 7m` type through the
same Tonal extension API, supporting all roots and inversions without adding notes.
Major sevenths with an omitted fifth are also supported through the general
`1P 3M 7M` type, labeled `maj7(no5)` (for example C–E–B as Cmaj7(no5)).

**[User clarification, 4 November 2026]** Among equally altered chord qualities,
prefer root-position readings over slash alternatives (Bbadd9 before Dm7(no5)/Bb).
Key-signature preference applies only within the same enharmonic root/quality/bass
family. Compare root signature complexity (natural minor for minor qualities, major
otherwise), then bass major-key signature complexity; preserve other interpretations'
positions. Thus /Bb precedes /A# without promoting C11/G over Gm7add11.

```yaml
- name: input-chord
  type: midi-chord
  sources: [monitor-input]
  settings: {}
```

**[Design completion D15]** Held means unmatched positive-velocity note-ons, with
velocity-zero note-ons treated as releases. Maintain boolean held state per MIDI
channel and pitch across all sources: repeated note-ons retrigger that pitch, and
one release clears it regardless of origin or immediate source. Do not accumulate
release counts; a processor such as sostenuto may emit multiple note-ons followed
by a single final note-off. Ignore unmatched releases and preserve the same pitch
held on another channel. Combine all channels and octaves for recognition, passing ascending MIDI
pitches so the library retains the lowest note as bass. Duplicate held pitches
appear once in the recognition input. Sustain/sostenuto controllers do not extend
held-key lifetime. Explicit received CC120/CC123 clears that channel's input state.
Other non-note messages do not affect recognition. No MIDI is emitted.

Display all library candidates in library order, `--` when no notes remain, and
`unrecognized` when the held set has no match. Display a single `Notes` line using
the monitor's musical pitch-class labels in ascending MIDI pitch order. Use both
common enharmonic spellings for accidentals, e.g. `(C#,Db)`, with no octave suffix,
matching the monitor. Show `--` when empty. Do not display numeric held MIDI notes.
Each module instance owns independent state; reload/removal discards it silently.
Publish immutable display snapshots to the UI. The bundled library must work offline
with no Node/npm installation; preserve its license and reproducible build provenance.
