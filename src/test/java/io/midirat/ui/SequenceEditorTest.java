package io.midirat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import io.midirat.module.EmitterModule;
import org.junit.jupiter.api.Test;

class SequenceEditorTest {
    @Test
    void invalidAcceptKeepsEditsAndCancelNeverChangesTheEmitter() {
        var emitter = new EmitterModule("hex", List.of("F8"));
        var editor = new SequenceEditor();
        editor.begin(emitter);
        editor.accept(8, Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> editor.accept(13, Optional.of(emitter)));
        assertTrue(editor.active());
        assertEquals("F", editor.text());
        assertEquals(List.of("F8"), emitter.messages());
        editor.accept('A', Optional.empty());
        editor.accept(13, Optional.of(emitter));
        assertFalse(editor.active());
        assertEquals(List.of("FA"), emitter.messages());
        editor.begin(emitter);
        editor.accept(8, Optional.empty());
        editor.accept(27, Optional.empty());
        assertFalse(editor.active());
        assertEquals(List.of("FA"), emitter.messages());
    }
}
