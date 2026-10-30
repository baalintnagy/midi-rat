package io.midirat.config;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

import io.midirat.api.EmitterControls;
import io.midirat.api.EmitterControls.Sequence;
import io.midirat.midi.MessageCodec;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

public final class EmitterPersistence {
    private EmitterPersistence() {
    }

    public static byte[] save(Path file, byte[] expected, String name,
        EmitterControls emitter) throws IOException {
        Sequence sequence = emitter.snapshot();
        if (!Arrays.equals(expected, Files.readAllBytes(file))) {
            throw new IOException(
                "Network changed externally; reload or resolve edits before Save");
        }
        Value root = Value.read(file);
        Value network = root.mapping()
            .get("network");
        Value definition = network.mapping()
            .get("modules")
            .list()
            .stream()
            .filter(v -> v.mapping()
                .get("name")
                .string()
                .equals(name))
            .findFirst()
            .orElseThrow();
        Value settings = definition.mapping()
            .get("settings");
        MappingNode mapping = (MappingNode) settings.node();
        // Preserve unrelated scalar values/tags, canonicalizing integers before serialization.
        mapping.getValue()
            .removeIf(tuple -> {
                String key = ((ScalarNode) tuple.getKeyNode()).getValue();
                return key.equals("format")
                    || key.equals("messages");
            });
        mapping.getValue()
            .add(new NodeTuple(string("format"), string(sequence.format())));
        List<Node> messages = sequence.messages()
            .stream()
            .map(EmitterPersistence::string)
            .map(n -> (Node) n)
            .toList();
        mapping.getValue()
            .add(new NodeTuple(string("messages"),
                new SequenceNode(Tag.SEQ, messages, DumperOptions.FlowStyle.BLOCK)));
        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        StringWriter writer = new StringWriter();
        new Yaml(options).serialize(canonicalNumbers(root.node()), writer);
        byte[] replacement = writer.toString()
            .getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), ".midi-rat-", ".yaml");
        try {
            Files.write(temporary, replacement);
            Value.read(temporary);
            if (!Arrays.equals(expected, Files.readAllBytes(file))) {
                throw new IOException("Network changed during Save; resolve edits before retrying");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            return replacement;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ScalarNode string(String text) {
        return new ScalarNode(Tag.STR, text, null, null, DumperOptions.ScalarStyle.DOUBLE_QUOTED);
    }

    private static Node canonicalNumbers(Node node) {
        if (node instanceof ScalarNode scalar
            && scalar.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN
            && (scalar.getTag()
                .equals(Tag.INT)
                || scalar.getTag()
                    .equals(Tag.STR)
                    && scalar.getValue()
                        .matches("-?0X[0-9A-Fa-f]+"))) {
            return new ScalarNode(Tag.INT,
                Integer.toString(MessageCodec.integer(scalar.getValue())),
                null, null, DumperOptions.ScalarStyle.PLAIN);
        }
        if (node instanceof MappingNode mapping) {
            List<NodeTuple> tuples = mapping.getValue()
                .stream()
                .map(t -> new NodeTuple(t.getKeyNode(), canonicalNumbers(t.getValueNode())))
                .toList();
            return new MappingNode(Tag.MAP, tuples, DumperOptions.FlowStyle.BLOCK);
        }
        if (node instanceof SequenceNode sequence) {
            return new SequenceNode(Tag.SEQ, sequence.getValue()
                .stream()
                .map(EmitterPersistence::canonicalNumbers)
                .toList(), DumperOptions.FlowStyle.BLOCK);
        }
        return node;
    }
}
