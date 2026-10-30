package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.midirat.api.EmitterControls;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MidiMessage;
import io.midirat.module.EmitterModule;
import org.junit.jupiter.api.Test;

class EmitterSnapshotTest {
    @Test
    void snapshotsAreImmutableAndInvalidEditsPreserveTheLastValidSequence() {
        var messages = new ArrayList<>(List.of("FA"));
        var emitter = new EmitterModule("hex", messages);
        var original = emitter.snapshot();
        messages.clear();
        assertEquals(List.of("FA"), original.messages());
        assertThrows(UnsupportedOperationException.class, () -> original.messages()
            .clear());
        assertThrows(IllegalArgumentException.class, () -> emitter.edit("hex", List.of("broken")));
        assertEquals(original, emitter.snapshot());
        emitter.edit("hex", List.of("FC"));
        assertEquals(List.of("FA"), original.messages());
        assertEquals(List.of(MidiMessage.shortMessage(0xFC)), emitter.compile());
    }

    @Test
    void concurrentEditsCompileAndRenderWholeSequences() throws Exception {
        var message = MidiMessage.shortMessage(0x90, 60, 100);
        var expected = List.of(message);
        var hex = List.of(message.hex());
        var text = List.of(MessageCodec.readable(message));
        var emitter = new EmitterModule("hex", hex);
        var ready = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 2000; i++) {
                    emitter.edit("message-text", text);
                    emitter.edit("hex", hex);
                }
                return null;
            });
            var reader = executor.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 2000; i++) {
                    assertEquals(expected, emitter.compile());
                    var sequence = emitter.snapshot();
                    assertEquals(expected,
                        MessageCodec.sequence(sequence.format(), sequence.messages()));
                    var rows = emitter.widgetLines();
                    String format = rows.getFirst()
                        .contains("(hex)") ? "hex" : "message-text";
                    assertEquals(expected,
                        MessageCodec.sequence(format, rows.subList(1, rows.size())));
                }
                return null;
            });
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void existingEmitterCapabilitiesInheritTheDefaultSnapshotAdapter() {
        var messages = new ArrayList<>(List.of("FA"));
        EmitterControls legacy = new LegacyEmitter("hex", messages);
        var snapshot = legacy.snapshot();
        messages.clear();
        assertEquals("hex", snapshot.format());
        assertEquals(List.of("FA"), snapshot.messages());
    }

    private record LegacyEmitter(String format, List<String> messages) implements EmitterControls {
        @Override
        public void edit(String format, List<String> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<MidiMessage> compile() {
            return MessageCodec.sequence(format, messages);
        }
    }
}
