package io.midirat.module;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import io.midirat.api.ModuleContext;
import io.midirat.api.ModuleDetails;
import io.midirat.api.ModuleInstance;
import io.midirat.api.ModuleWidget;
import io.midirat.api.MonitorControls;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.rule.MessageRule;

/** Filtering remains independent of bounded, nonblocking presentation capture. */
public final class FilterModule implements ModuleInstance, ModuleDetails {
    private enum Page {
        RULES, PASSED, BLOCKED
    }

    private final List<MessageRule> whitelist;
    private final List<MessageRule> blacklist;
    private final List<String> rules;
    private final LongSupplier clock;
    private final LongAdder passed = new LongAdder();
    private final LongAdder blocked = new LongAdder();
    private final MonitorModule passedLog = new MonitorModule(2000, true);
    private final MonitorModule blockedLog = new MonitorModule(2000, true);
    private volatile long lastBlocked = Long.MIN_VALUE;
    private Page page = Page.RULES;

    public FilterModule(List<MessageRule> whitelist, List<MessageRule> blacklist) {
        this(whitelist, blacklist, Stream.concat(
            whitelist.stream()
                .map(rule -> "Whitelist: %s".formatted(rule)),
            blacklist.stream()
                .map(rule -> "Blacklist: %s".formatted(rule)))
            .toList());
    }

    public FilterModule(List<MessageRule> whitelist, List<MessageRule> blacklist,
        List<String> rules) {
        this(whitelist, blacklist, rules, System::nanoTime);
    }

    FilterModule(List<MessageRule> whitelist, List<MessageRule> blacklist,
        List<String> rules, LongSupplier clock) {
        this.whitelist = List.copyOf(whitelist);
        this.blacklist = List.copyOf(blacklist);
        this.rules = List.copyOf(rules);
        this.clock = clock;
    }

    @Override
    public void start(ModuleContext context) {
        passedLog.start(context);
        blockedLog.start(context);
    }

    @Override
    public List<MidiMessage> process(Envelope e) {
        boolean accepted = (whitelist.isEmpty()
            || whitelist.stream()
                .anyMatch(r -> r.matches(e)))
            && blacklist.stream()
                .noneMatch(r -> r.matches(e));
        if (accepted) {
            passed.increment();
            passedLog.process(e);
        } else {
            blocked.increment();
            blockedLog.process(e);
            lastBlocked = clock.getAsLong();
        }
        return accepted ? List.of(e.message()) : List.of();
    }

    @Override
    public List<String> widgetLines() {
        // Drain both logs on the UI thread, including the inactive mode's history.
        passedLog.lines(1);
        blockedLog.lines(1);
        long recent = lastBlocked;
        boolean active = recent != Long.MIN_VALUE
            && clock.getAsLong() - recent < 10_000_000L;
        return List.of("%s %dw | %db | Passed: %d | Blocked: %d".formatted(
            active ? "●" : "○", whitelist.size(), blacklist.size(), passed.sum(), blocked.sum()));
    }

    @Override
    public ModuleDetails details() {
        return this;
    }

    private List<String> ruleLines() {
        return Stream.concat(widgetLines().stream(), rules.stream())
            .toList();
    }

    @Override
    public ModuleWidget content() {
        return switch (page) {
        case RULES -> ModuleWidget.fixed(this::ruleLines);
        case PASSED -> passedLog;
        case BLOCKED -> blockedLog;
        };
    }

    @Override
    public String mode() {
        return switch (page) {
        case RULES -> "Rules";
        case PASSED -> "Passed";
        case BLOCKED -> "Blocked";
        };
    }

    @Override
    public void nextMode() {
        page = switch (page) {
        case RULES -> Page.PASSED;
        case PASSED -> Page.BLOCKED;
        case BLOCKED -> Page.RULES;
        };
    }

    @Override
    public Optional<MonitorControls> controls() {
        return switch (page) {
        case RULES -> Optional.empty();
        case PASSED -> Optional.of(passedLog);
        case BLOCKED -> Optional.of(blockedLog);
        };
    }
}
