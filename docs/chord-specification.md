# Native MIDI chord module specification

Status: implemented contract, 4 November 2026. This document supersedes all previous
library-specific chord recognition and labeling decisions. JDK 25 and SnakeYAML are
required; chord recognition uses no external music library, JS engine, network, or npm.

## 1. Musical sources and chosen dialect

There is no single universal chord-symbol standard. This implementation uses common
jazz/pop lead-sheet notation, with ASCII defaults suitable for the terminal.

References consulted on 4 November 2026:

- [Open Music Theory, Chord Symbols, Megan Lavengood](https://viva.pressbooks.pub/openmusictheory/chapter/chord-symbols/)
- [Music Theory for the 21st-Century Classroom, Lead-Sheet Symbols, Robert Hutchinson](https://musictheory.pugetsound.edu/mt21c/LeadSheetSymbols.html)

Source-supported conventions: the root precedes quality and extensions; major triads
need no quality suffix. Minor, diminished, augmented, and half-diminished have multiple
accepted symbols. A slash and note name specify a bass. A sixth is ordinarily written
6; a sixth plus ninth may be written 6/9. Added notes do not imply intervening extensions.
Suspensions replace the third. Numbered extensions imply lower stacked extensions,
although practical jazz voicings often omit notes. These conventions follow Open Music
Theory; the classroom text corroborates the basic quality and inversion notation.

Everything below specifying an algorithm, exact spelling, default, bound, or ranking
is an **implementation decision**, not a claim of universal musical standardization.
The recognizer names observed pitch-class sets, not harmonic function or key context.
Unusual configurable formulas are explicit interpretations, not canonical jazz usage.

## 2. Observer and input contract

- One or more sources, no destinations. Never emit MIDI or corrective note-offs.
- Boolean held state per channel/pitch, merging origins and immediate sources.
  Repeated positive note-ons retrigger; a single release clears that channel/pitch.
  Note-off and velocity-zero note-on release; unmatched releases do nothing.
- Other channels holding the pitch survive releases. Incoming CC120/123 clears
  only its channel. Pedals do not extend held state. Reload/removal starts empty.
- Recognition receives MIDI integers 0..127; reject invalid pitches, accept any order.
  Deduplicate pitch classes and ignore octave doublings; the lowest MIDI pitch is bass.
  An empty set has no candidates. No note durations, source identity, or tonal history
  participate in recognition. Chord-local held state remains the only observer state.
- Display two lines: ranked chords and ascending musical Notes as before. Empty is
  `--`; a nonempty unmatched set is `unrecognized`. `maxChords` caps display only;
  zero means unlimited. Snapshots are immutable.

## 3. Exact, composable vocabulary

Default bases are data in `src/main/resources/chord-defaults.yaml`.
Degrees are strings relative to the root's major scale; b/# move a degree a semitone.
Supported degree numbers: 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, optionally b, bb, #, or ##.
A compound degree and its simple equivalent share a pitch class but retain their
spelling identity. Formulas must have a root and at least three distinct classes.

| Family | Default suffix | Degrees |
| --- | --- | --- |
| Major / minor | empty / m | 1 3 5 / 1 b3 5 |
| Diminished / augmented | dim / aug | 1 b3 b5 / 1 3 #5 |
| Suspended | sus2 / sus4 | 1 2 5 / 1 4 5 |
| Major / minor sixth | 6 / m6 | 1 3 5 6 / 1 b3 5 6 |
| Flat sixth | (b6) / m(b6) | 1 3 5 b6 / 1 b3 5 b6 |
| Diminished sixth variants | dim6 / dim(b6) | 1 b3 b5 6 / 1 b3 b5 b6 |
| Augmented triad plus sixth | aug6 | 1 3 #5 6 |
| Dominant / major seventh | 7 / maj7 | 1 3 5 b7 / 1 3 5 7 |
| Minor / minor-major seventh | m7 / m(maj7) | 1 b3 5 b7 / 1 b3 5 7 |
| Fully / half diminished | dim7 / m7b5 | 1 b3 b5 bb7 / 1 b3 b5 b7 |
| Diminished-major seventh | dim(maj7) | 1 b3 b5 7 |
| Augmented seventh variants | aug7 / aug(maj7) | 1 3 #5 b7 / 1 3 #5 7 |
| Suspended seventh variants | 7sus4 / maj7sus4 | 1 4 5 b7 / 1 4 5 7 |

`aug6` here is an augmented triad with an added major sixth, not a classical
augmented-sixth chord. Such uncommon interpretations can be disabled by base id.
No aug(b6) base: b6 duplicates #5. dim6 and dim7 remain distinct spelled formulas.

Defaults permit up to three added tones selected from b9, 9, #9, 11, #11, b13, 13.
Enumerate all subsets up to the configured limit on **every** base. Never just add
one tone at a time to selected qualities. Do not silently fill gaps or invent notes.
Skip a subset if an added pitch class duplicates a base or another selected tone.
Thus adding 13 to m6 produces no redundant alias; m6 already contains that class.

For bases declaring `extensionPrefix`, consecutive natural added 9, 11, 13 compact
into numbered extensions: 7 + 9 -> 9, maj7 + 9 -> maj9, m7 + 9 + 11 -> m11,
and so on for diminished, half-diminished, augmented, minor-major and suspended bases.
The base seventh interval is retained. For example dim9 retains bb7, not b7.
The highest compact extension requires every lower extension in the observed set.
Other additions remain explicit: m7 + 11 -> m7(add11), not m11.

Remaining additions are sorted by degree then alteration and rendered `(add9,11)`
or, with `additionStyle: repeated`, `add9add11`. Altered additions use the same explicit
syntax, e.g. `7(addb9)`; the extra `add` makes exact-set intent clear. Major/minor
sixths plus natural 9 compact to 6/9 or m6/9 when enabled. Remaining additions follow
that symbol: m6/9(add11). Exactly one canonical rendering per spelled formula;
no separate aliases for m9 versus m7add9. 6/9 is not an inversion slash.

Optional omitted-fifth matching removes only an explicitly natural 5 from a formula
of at least four classes, appending `(no5)`. Default off. Do not infer missing roots,
thirds, altered fifths, or sevenths. Explicit exact-set matching is intentionally
stricter than jazz performance conventions; further voicing profiles are future work.

## 4. Recognition, spelling and ranking

Compile immutable pitch-class-mask formulas at configuration load, not per event.
For each possible root, rotate the held mask and look up exact formulas. The root
must be present. Add slash bass if the lowest pitch class differs from the root.
With `upperStructures: true`, also remove every occurrence of the bass pitch class,
require at least three remaining classes, match the whole upper set, and append bass.
No arbitrary subset searches, ignored extra notes, probabilistic matches, or fuzzy scores.
Return an immutable, deduplicated list. Runtime recognition is stateless and repeatable.

`spelling` chooses both, sharps, or flats. Roots/basses use the twelve common natural
or single-accidental names; avoid B#, Cb, E#, Fb and double-accidental roots. Compute
chord-tone spelling cost from actual diatonic degrees, allowing implied double
accidentals in the cost. No external key tracker or guessed key.

Default lexicographic ranking criteria, in this order:

1. `alterations`: count b/# characters in the rendered quality (exclude root/bass).
2. `slash`: root position before slash candidates.
3. `spelling`: total accidental magnitude in diatonically spelled chord tones.
4. `symbols`: b/# count in the entire displayed label.
5. `complexity`: length of the rendered suffix; compact symbols win ties.
6. `order`: YAML base order then subset enumeration order, stable across runs.

Configuration may reorder or select these criteria. Do not turn them into magic
weighted sums. After ranking, reorder spellings only within the existing slots of
an identical root-class/suffix/bass-class family: fewer root key-signature alterations
(natural minor when base `minor: true`, major otherwise), then fewer major bass-key
alterations, then stable order. This preserves the prior local enharmonic preference
without promoting distinct interpretations. Sharp spelling precedes flat on exact ties.

The data file groups simple bases before sixths/sevenths. Subsets are generated by
bounded combinatorics rather than a growing hand-maintained catalog of special cases.
The default maximum is 24 bases times 64 subsets before duplicate filtering. Bounds:
at most seven configured added degrees, `maxAddedTones` 0..7, at most twelve base
pitch classes. More custom bases increase load time linearly; they do not change
musical rules. Configuration is compiled once per module instance.

## 5. Configuration schema

All settings are YAML. No scripting, reflection, JSON, or executable predicates.
Existing networks with `settings: {maxChords: 8}` continue to work.

```yaml
- name: chord
  type: midi-chord
  sources: [keyboard]
  settings:
    maxChords: 8
    recognition:
      spelling: both
      upperStructures: true
      omittedFifth: false
      sixNine: true
      additionStyle: parenthesized
      additions: [b9, '9', '#9', '11', '#11', b13, '13']
      maxAddedTones: 3
      ranking: [alterations, slash, spelling, symbols, complexity, order]
      symbols:
        minor: m
        major: maj
        diminished: dim
        augmented: aug
        halfDiminished: m7b5
      disabledBases: [diminished6, augmented6]
      bases:
        - id: custom-quartal
          suffix: quartal
          degrees: ['1', '4', 'b7']
          minor: false
```

`recognition` overlays the packaged defaults. `bases` adds or replaces by id;
`disabledBases` is applied afterward. Lists other than bases replace defaults.
`symbols` merges by key. Base replacements retain their original ordering slots. An empty additions list or maxAddedTones: 0 disables additions.
Default values are shown above except disabledBases/bases, which are absent by default.
Each base requires id, suffix (empty allowed), degrees; minor defaults false.
Optional extensionPrefix and extensionSuffix control compact numbered names: an
empty prefix produces 9/11/13; `{minor}` produces m9/m11/m13; trailing b5 produces
m9b5, etc. An absent prefix disables compaction. Templates may contain symbol
placeholders `{minor}`, `{major}`, `{diminished}`, `{augmented}`, `{halfDiminished}`.

Reject unknown keys at every level, duplicate YAML keys, duplicate base ids within
an override, unknown/repeated disabled ids, invalid booleans/types/ranges/choices,
invalid degrees, duplicate degree pitch classes, or a base without root 1. Require
at least one unique supported ranking criterion. Symbols and suffixes must be
single-line text without slash; slash is reserved for generated bass and 6/9 notation.
Errors identify YAML paths/locations. Lists and maps are defensively copied.
Changes take effect through the existing network reload, silently discarding held state.

## 6. Acceptance and tests

Retire the old library snapshot expectations first. New active tests must assert
musical contracts independent of implementation-generated dictionaries:

- Independent fixtures for every base and representative 9/11/13 and altered additions.
- All twelve transpositions and inversions, octave doublings, unordered input.
- Exact-set negative cases; no absent-tone invention; explicit no5 option.
- G/D/Bb/C/F: Gm7(add11) before Bb(add9)/G.
- A/C/E/F#/B: Am6/9. Adding D: Am6/9(add11), without implying G.
- E/G/B/C: Em(b6) and Cmaj7/E as alternative readings.
- Db major before C# major, C# minor before Db minor; no duplicate labels.
- Vocabulary overlays, disabled bases, custom symbols, spelling, slash toggle,
  ranking, addition bounds/styles, invalid configuration and MIDI ranges.
- Held-key merging/retriggers/channel release, empty/unrecognized display, cap,
  note-line ordering and zero MIDI emission remain covered by observer tests.
- Packaged JVM probe loads the default YAML vocabulary and recognizes Am6/9.
- Build artifacts must contain neither Rhino classes nor the retired bundled JS.

Run root Maven formatting, Checkstyle and verify. Report synthetic results separately
from physical MIDI, terminal interaction, clean-machine packaging and latency work.
No physical-device or latency qualification is implied by passing Java tests.

## 7. Deferred decisions

No universal single best chord name can be inferred from MIDI pitch classes alone.
Contextual harmonic analysis, rootless jazz voicings, polychords, key signatures supplied
by users, alternate temperament, MIDI 2.0 per-note expression and localized note names
are future work, not hidden inference. Custom formulas provide an explicit extension
point without claiming these features. Rust remains a possible later implementation;
this implementation remains Java 25.
