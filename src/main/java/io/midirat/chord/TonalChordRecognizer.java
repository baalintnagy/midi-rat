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

/** Executes the pinned Tonal distribution transpiled for Rhino; no chord rules live here. */
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
            detect = (Function) context.evaluateString(scope,
                """
                (function(pitches) {
                    return Tonal.Chord.detect(pitches.map(function(pitch) {
                        return Tonal.Midi.midiToNoteName(pitch, { sharps: true });
                    }));
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
