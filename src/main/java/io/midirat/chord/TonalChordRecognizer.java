package io.midirat.chord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

/** Uses the pinned Tonal engine with display ranking and supported chord-type extensions. */
public final class TonalChordRecognizer implements ChordRecognizer {
    private final Scriptable scope;
    private final Function detect;

    public TonalChordRecognizer() {
        try (Context context = openContext();
            InputStream stream = TonalChordRecognizer.class.getResourceAsStream(
                "/vendor/tonal/tonal-5.1.3.es5.js")) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled Tonal library");
            }
            scope = context.initSafeStandardObjects();
            context.evaluateReader(scope, new InputStreamReader(stream, StandardCharsets.UTF_8),
                "tonal-5.1.3.es5.js", 1, null);
            context.evaluateString(scope,
                """
                Tonal.ChordType.add(["1P", "3M", "4P", "5P"],
                    ["add4", "add11"], "major added fourth");
                Tonal.ChordType.add(["1P", "3m", "7m"],
                    ["m7(no5)"], "minor seventh without fifth");
                Tonal.ChordType.add(["1P", "3M", "7M"],
                    ["maj7(no5)"], "major seventh without fifth");
                """
                ,
                "midi-rat-chord-types", 1, null);
            detect = (Function) context.evaluateString(scope,
                """
                (function(pitches) {
                    pitches = pitches.slice().sort(function(left, right) {
                        return left - right;
                    });
                    function names(notes, sharps) {
                        return Tonal.Chord.detect(notes.map(function(pitch) {
                            return Tonal.Midi.midiToNoteName(pitch, { sharps: sharps });
                        }));
                    }
                    function slashNames(sharps) {
                        var bass = pitches[0] % 12;
                        var upper = pitches.filter(function(pitch) {
                            return pitch % 12 !== bass;
                        });
                        var classes = upper.map(function(pitch) { return pitch % 12; })
                            .filter(function(pitch, index, all) {
                                return all.indexOf(pitch) === index;
                            });
                        if (classes.length < 3) {
                            return [];
                        }
                        var bassName = Tonal.Midi.midiToNoteName(pitches[0],
                            { sharps: sharps, pitchClass: true });
                        return names(upper, sharps).map(function(label) {
                            return label.split(/[/](?=[A-G])/)[0] + "/" + bassName;
                        });
                    }
                    function alterations(label) {
                        var notes = Tonal.Chord.get(label.split(/[/](?=[A-G])/)[0]).notes;
                        return notes.reduce(function(total, note) {
                            return total + Math.abs(Tonal.Note.get(note).alt);
                        }, 0);
                    }
                    return names(pitches, true).concat(names(pitches, false),
                        slashNames(true), slashNames(false)).filter(function(label, index, all) {
                        return all.indexOf(label) === index;
                    }).map(function(label, index) {
                        var quality = label.replace(/^[A-G][#b]*/, "")
                            .split(/[/](?=[A-G])/)[0];
                        return { label: label, index: index, alterations: alterations(label),
                            quality: (quality.match(/[#b]/g) || []).length,
                            slash: /[/][A-G]/.test(label) ? 1 : 0,
                            size: Tonal.Chord.get(label.split(/[/](?=[A-G])/)[0]).notes.length,
                            symbols: (label.match(/[#b]/g) || []).length };
                    }).sort(function(left, right) {
                        return left.quality - right.quality
                            || left.alterations - right.alterations
                            || left.symbols - right.symbols || left.slash - right.slash
                            || (left.slash && right.slash ? left.size - right.size : 0)
                            || left.index - right.index;
                    }).map(function(candidate) {
                        return candidate.label.replace(/^([A-G][#b]*)4(?=$|[/])/, "$1m7add4(no5)")
                            .replace(/^([A-G][#b]*)m7add11(?=$|[/])/, "$1m7add4")
                            .replace(/^([A-G][#b]*)M(?=$|[/]|add)/, "$1")
                            .replace(/^([A-G][#b]*)M/, "$1maj");
                    });
                })
                """
                ,
                "midi-rat-chord-adapter", 1, null);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load bundled Tonal library", exception);
        }
    }

    @Override
    public List<String> recognize(List<Integer> pitches) {
        if (pitches.isEmpty()) {
            return List.of();
        }
        try (Context context = openContext()) {
            Scriptable input = context.newArray(scope, pitches.toArray());
            Scriptable result = (Scriptable) detect.call(context, scope, scope,
                new Object[] { input });
            return Arrays.stream(context.getElements(result))
                .map(Context::toString)
                .toList();
        }
    }

    private static Context openContext() {
        Context context = Context.enter();
        context.setLanguageVersion(Context.VERSION_ES6);
        context.setClassShutter(_ -> false);
        return context;
    }
}
