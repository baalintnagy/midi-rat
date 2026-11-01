package io.midirat.ui;

import static dev.tamboui.style.Color.WHITE;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import io.midirat.api.ModuleContext;
import io.midirat.api.ModuleInstance;
import io.midirat.api.SourceNames;
import io.midirat.config.ApplicationConfig;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.config.Value;
import io.midirat.host.DeviceManager;
import io.midirat.host.HostConfiguration;
import io.midirat.host.HostStatus;
import io.midirat.host.RoutingEngine;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MidiMessage;
import io.midirat.module.Builtins;
import io.midirat.module.MonitorModule;

/** Optional visual QA: rasterizes the actual TamboUI buffer, without opening MIDI ports. */
public final class WorkspacePreview {
    private WorkspacePreview() {
    }

    public static void main(String[] args) throws Exception {
        var area = Rect.of(190, 48);
        var buffer = Buffer.empty(area);
        var origin = new Endpoint("hammer-main", Endpoint.Kind.INPUT);
        var source = new Endpoint("musical-input", Endpoint.Kind.BUS);
        var monitor = new MonitorModule(2000, true);
        var context = new ModuleContext() {
            @Override
            public SourceNames sourceNames() {
                return new SourceNames(List.of("Hammer 88 Pro [group 1]"),
                    List.of("musical-input"));
            }

            @Override
            public void publish(Endpoint target, List<MidiMessage> messages) {
            }

            @Override
            public void report(String diagnostic) {
            }
        };
        monitor.start(context);
        var settings = Value.read(Path.of("config/network-sostenuto-launchpad.yaml"))
            .mapping()
            .get("network")
            .mapping()
            .get("modules")
            .list()
            .getFirst()
            .mapping()
            .get("settings");
        var filter = Builtins.providers()
            .stream()
            .filter(provider -> provider.type()
                .equals("midi-message-filter"))
            .findFirst()
            .orElseThrow()
            .prepare(settings, List.of(origin));
        filter.start(context);
        for (int i = 0; i < 36; i++) {
            var message = switch (i % 6) {
            case 0 -> MidiMessage.shortMessage(0x90, 60, 100);
            case 1 -> MidiMessage.shortMessage(0x90, 64, 105);
            case 2 -> MidiMessage.shortMessage(0x90, 67, 108);
            case 3 -> MidiMessage.shortMessage(0xB0, 64, 127);
            case 4 -> MidiMessage.shortMessage(0x80, 60, 0);
            default -> MidiMessage.shortMessage(0xE0, 0, 64);
            };
            monitor.process(new Envelope(source, 1_234_567_890_123L + i * 1_000_000, message,
                origin, "Hammer 88 Pro [group 1]"));
            filter.process(new Envelope(origin, 1_234_567_890_123L + i * 1_000_000, message,
                origin, "Hammer 88 Pro [group 1]"));
        }
        var instances = List.of(
            new Instance("M-IN", "midi-message-filter", List.of(origin), List.of(source),
                filter),
            new Instance("MON", "midi-monitor", List.of(source), List.of(), monitor),
            fixed("SOS", "sostenuto",
                "Sustain channels: 1 | Sostenuto channels: 1 | Umbrella: on",
                "Pressed: 3 | Latched: 4 | Retained note-offs: 2"),
            fixed("CHRD", "midi-chord", "Chord: Cmaj7 | Em/C",
                "Notes: C, C, E, G, B"));
        var status = new HostStatus(new HostStatus.Devices("windows-midi-services", 8, 1,
            "submission n=128 median=2200 p95=4100 p99=6500 max=8100 jitter(p99-median)=4300 ns",
            List.of(new HostStatus.Connection("postSSB", "postSSB [group 1]", true, 0, 0))),
            List.of(origin, source), instances.stream()
                .map(instance -> new HostStatus.Route(instance.name(), true, 0, 0))
                .toList());
        filter
            .process(new Envelope(origin, 1_234_604_890_123L, MessageCodec.parse("hex", "F8"),
                origin, "Hammer 88 Pro [group 1]"));
        if (args.length > 0
            && args[0].startsWith("host")) {
            if (args[0].equals("host")) {
                new WorkspaceView(status, instances, instances.size(),
                    new WorkspaceView.Editor(false, "hex", ""), "", true).render(area, buffer);
            } else {
                var app = ApplicationConfig
                    .read(Path.of("config/wms-application-sostenuto-launchpad.yaml"));
                var configuration = new HostConfiguration(app, Files.readAllLines(app.file()),
                    Files.readAllLines(app.network()), Builtins.providers()
                        .stream()
                        .map(provider -> new HostConfiguration.Provider(provider.type(),
                            provider.version(), provider.apiVersion(), provider.role()))
                        .toList(),
                    RoutingEngine.PROCESSING_CAPACITY, DeviceManager.OUTPUT_CAPACITY);
                var state = new HostDetailsView.State();
                if (args[0].equals("host-configuration")) {
                    state.nextPage();
                }
                new HostDetailsView(status, HostConfigurationFormat.lines(configuration),
                    List.of("Network activated", "Connected 8 inputs and 1 output"), 0,
                    state, true).render(area, buffer);
            }
        } else if (args.length > 0) {
            if (!args[0].equals("rules")) {
                filter.details()
                    .nextMode();
            }
            if (args[0].equals("blocked")) {
                filter.details()
                    .nextMode();
            }
            new DetailsView("M-IN", "midi-message-filter", filter.details(),
                new DetailsView.State(), true).render(area, buffer);
        } else {
            new WorkspaceView(status, instances, 1,
                new WorkspaceView.Editor(false, "hex", ""), "", true).render(area, buffer);
        }
        Font font = new Font("Consolas", Font.PLAIN, 15);
        int cellWidth = 9;
        int cellHeight = 21;
        var image = new BufferedImage(area.width() * cellWidth + 32,
            area.height() * cellHeight + 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(17, 21, 29));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setFont(font);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int y = 0; y < area.height(); y++) {
                for (int x = 0; x < area.width(); x++) {
                    var cell = buffer.get(x, y);
                    var rgb = cell.style()
                        .fg()
                        .orElse(WHITE)
                        .toRgb();
                    graphics.setColor(new Color(rgb.r(), rgb.g(), rgb.b()));
                    graphics.drawString(cell.symbol(), 16 + x * cellWidth, 31 + y * cellHeight);
                }
            }
        } finally {
            graphics.dispose();
        }
        String name = args.length == 0 ? "workspace"
            : args[0].startsWith("host") ? args[0] : "filter-%s".formatted(args[0]);
        Path output = Path.of("target/%s-preview.png".formatted(name));
        ImageIO.write(image, "png", output.toFile());
        System.out.println(output.toAbsolutePath());
    }

    private static Instance fixed(String name, String type, String... rows) {
        ModuleInstance module = new ModuleInstance() {
            @Override
            public List<MidiMessage> process(Envelope input) {
                return List.of();
            }

            @Override
            public List<String> widgetLines() {
                return List.of(rows);
            }
        };
        return new Instance(name, type, List.of(), List.of(), module);
    }
}
