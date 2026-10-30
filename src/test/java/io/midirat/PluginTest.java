package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import io.midirat.config.Value;
import io.midirat.host.Plugins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginTest {
    @TempDir
    Path directory;

    Path jar(String type, int api, String version) throws Exception {
        Path source = directory.resolve("source/sample/Provider.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,
            """
            package sample;
            import io.midirat.api.ModuleProvider;
            import io.midirat.api.ModuleInstance;
            import io.midirat.config.Value;
            import io.midirat.midi.Endpoint;
            import java.util.List;
            public class Provider implements ModuleProvider {
                public String type() { return "%s"; }
                public String version() { return "%s"; }
                public int apiVersion() { return %d; }
                public Role role() { return Role.PROCESSOR; }
                public ModuleInstance prepare(Value settings, List<Endpoint> sources) {
                    settings.mapping("enabled");
                    return e -> List.of(e.message());
                }
            }
            """
                .formatted(type, version, api));
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes);
        int result = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null,
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                source.toString());
        assertEquals(0, result);
        Path modules = directory.resolve("modules");
        Files.createDirectories(modules);
        Path jar = modules.resolve("sample.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("sample/Provider.class"));
            Files.copy(classes.resolve("sample/Provider.class"), out);
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/io.midirat.api.ModuleProvider"));
            out.write("sample.Provider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return modules;
    }

    @Test
    void discoveryReuseReplacementAndRemoval() throws Exception {
        Path modules = jar("sample", 1, "1");
        Path settings = directory.resolve("settings.yaml");
        Files.writeString(settings, "{}");
        try (var first = new Plugins(modules, _ -> {
        })) {
            var provider = first.providers()
                .get("sample");
            assertEquals("1", provider.version());
            provider.prepare(Value.read(settings), List.of())
                .close();
            try (var same = new Plugins(modules, _ -> {
            }, first)) {
                assertSame(provider, same.providers()
                    .get("sample"));
            }
            jar("sample", 1, "2");
            try (var replacement = new Plugins(modules, _ -> {
            }, first)) {
                assertNotSame(provider, replacement.providers()
                    .get("sample"));
                assertEquals("2", replacement.providers()
                    .get("sample")
                    .version());
            }
            Files.delete(modules.resolve("sample.jar"));
            try (var removed = new Plugins(modules, _ -> {
            }, first)) {
                assertTrue(!removed.providers()
                    .containsKey("sample"));
            }
        }
    }

    @Test
    void incompatibleAndConflictingProvidersRejected() throws Exception {
        Path modules = jar("sample", 99, "1");
        assertThrows(IllegalArgumentException.class, () -> new Plugins(modules, _ -> {
        }));
        jar("midi-remap", 1, "1");
        assertThrows(IllegalArgumentException.class, () -> new Plugins(modules, _ -> {
        }));
    }
}
