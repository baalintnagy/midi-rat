package io.midirat.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.midirat.api.ModuleContext;
import io.midirat.api.SourceNames;
import io.midirat.config.Value;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;
import io.midirat.rule.IntSelection;
import io.midirat.rule.MessageRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilterModuleTest {
    private final Endpoint input = new Endpoint("keys", Endpoint.Kind.INPUT);
    @TempDir
    Path directory;

    @Test
    void countersAndLedObserveBothOutcomesWithoutChangingFiltering() {
        var clock = new AtomicLong(1);
        var rule = new MessageRule.SystemSignal(MessageType.CLOCK,
            new MessageRule.Scope(Set.of(), new IntSelection.Range(0, 255)));
        var filter = new FilterModule(List.of(), List.of(rule), List.of("blacklist: CLOCK"),
            clock::get);
        assertEquals("○ 0w | 1b | Passed: 0 | Blocked: 0", filter.widgetLines()
            .getFirst());
        var note = envelope("90 3C 64");
        assertEquals(List.of(note.message()), filter.process(note));
        assertEquals("○ 0w | 1b | Passed: 1 | Blocked: 0", filter.widgetLines()
            .getFirst());
        assertEquals(List.of(), filter.process(envelope("F8")));
        assertEquals("● 0w | 1b | Passed: 1 | Blocked: 1", filter.widgetLines()
            .getFirst());
        clock.addAndGet(5_000_000);
        filter.process(note);
        clock.addAndGet(5_000_001);
        assertEquals("○ 0w | 1b | Passed: 2 | Blocked: 1", filter.widgetLines()
            .getFirst());
    }

    @Test
    void separateLogsUseTheMonitorFormatAndControlsOnlyAffectTheSelectedLog() {
        var rule = new MessageRule.SystemSignal(MessageType.CLOCK,
            new MessageRule.Scope(Set.of(), new IntSelection.Range(0, 255)));
        var filter = new FilterModule(List.of(), List.of(rule));
        var reference = new MonitorModule(2000, true);
        ModuleContext context = new ModuleContext() {
            @Override
            public SourceNames sourceNames() {
                return new SourceNames(List.of("Hardware keyboard"), List.of("keys"));
            }

            @Override
            public void publish(Endpoint target, List<MidiMessage> messages) {
            }

            @Override
            public void report(String diagnostic) {
            }
        };
        filter.start(context);
        reference.start(context);
        var passed = envelope("90 3D 64");
        filter.process(passed);
        reference.process(passed);
        filter.process(envelope("F8"));
        filter.widgetLines();
        assertEquals("Rules", filter.mode());
        assertTrue(filter.controls()
            .isEmpty());
        filter.nextMode();
        assertEquals("Passed", filter.mode());
        assertEquals(reference.lines(5)
            .getLast(),
            filter.content()
                .lines(5)
                .getLast());
        filter.nextMode();
        assertEquals("Blocked", filter.mode());
        assertTrue(filter.content()
            .lines(5)
            .getLast()
            .contains("CLOCK"));
        filter.controls()
            .orElseThrow()
            .clear();
        assertEquals(1, filter.content()
            .lines(5)
            .size());
        filter.nextMode();
        assertEquals("Rules", filter.mode());
        filter.nextMode();
        assertTrue(filter.content()
            .lines(5)
            .getLast()
            .contains("Note: (C#,Db)"));
        assertTrue(filter.widgetLines()
            .getFirst()
            .contains("Passed: 1 | Blocked: 1"));
    }

    @Test
    void saturatedLogsStayBoundedAndDoNotLoseDecisionCounts() {
        var filter = new FilterModule(List.of(), List.of());
        var event = envelope("F8");
        for (int i = 0; i < 5000; i++) {
            assertEquals(List.of(event.message()), filter.process(event));
        }
        assertTrue(filter.widgetLines()
            .getFirst()
            .contains("Passed: 5000 | Blocked: 0"));
        filter.nextMode();
        var lines = filter.content()
            .lines(6000);
        assertEquals(2001, lines.size());
        assertTrue(lines.getFirst()
            .contains("UI drops 3000"));
        filter.nextMode();
        assertEquals(1, filter.content()
            .lines(6000)
            .size());
    }

    @Test
    void configuredDetailsShowActualYamlRulesAndSelections() throws Exception {
        Path file = directory.resolve("settings.yaml");
        Files.writeString(file,
            """
            whitelist:
              - type: NOTE
                channels: [1, 2]
                notes: [60, 61]
                velocity: {min: 10, max: 127}
            blacklist:
              - type: CLOCK
            """
        );
        var filter = Builtins.providers()
            .stream()
            .filter(provider -> provider.type()
                .equals("midi-message-filter"))
            .findFirst()
            .orElseThrow()
            .prepare(Value.read(file), List.of(input));
        String details = String.join("\n", filter.details()
            .content()
            .lines(100));
        assertTrue(details.contains("whitelist:"));
        assertTrue(details.contains("blacklist:"));
        assertTrue(details.contains("channels: [1, 2]"));
        assertTrue(details.contains("min: 10"));
        assertFalse(details.contains("Scope["));
    }

    private Envelope envelope(String hex) {
        return new Envelope(input, 123, MessageCodec.parse("hex", hex), input, "Hardware keyboard");
    }
}
