package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Launches only the host JAR, so test-classpath implementations cannot mask SPI failures. */
class SostenutoPluginIT {
    @TempDir
    Path directory;

    @Test
    void sostenutoRequiresAnExternalJarAndAllExamplesLoadThroughTheModuleDirectory()
        throws Exception {
        Path host = Path.of(System.getProperty("host.jar"));
        Path plugin = Path.of(System.getProperty("module.jar"));
        try (var jar = new JarFile(host.toFile())) {
            assertFalse(jar.stream()
                .anyMatch(entry -> entry.getName()
                    .startsWith("io/midirat/sostenuto/")));
        }
        try (var jar = new JarFile(plugin.toFile())) {
            assertTrue(jar.stream()
                .filter(entry -> entry.getName()
                    .endsWith(".class"))
                .allMatch(entry -> entry.getName()
                    .startsWith("io/midirat/sostenuto/")));
            assertTrue(jar.getJarEntry("META-INF/services/io.midirat.api.ModuleProvider") != null);
        }
        Path configs = Files.createDirectories(directory.resolve("config"));
        try (var files = Files.list(Path.of("config"))) {
            for (var file : files.filter(Files::isRegularFile)
                .toList()) {
                Files.copy(file, configs.resolve(file.getFileName()));
            }
        }
        Path installed = directory.resolve("modules/sostenuto.jar");
        List<String> examples = List.of("wmm-application-sostenuto.yaml",
            "wms-application-sostenuto.yaml", "wmm-application-sostenuto-launchpad.yaml",
            "wms-application-sostenuto-launchpad.yaml");
        validate(host, configs.resolve(examples.getFirst()), false);
        Files.copy(plugin, installed);
        for (String example : examples) {
            validate(host, configs.resolve(example), true);
        }
        Files.delete(installed);
        validate(host, configs.resolve(examples.getFirst()), false);
    }

    private void validate(Path host, Path config, boolean expected) throws Exception {
        Path output = directory.resolve("validation.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED",
            "-jar", host.toString(), "--config", config.toString(), "--validate")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Validation timed out");
            String text = Files.readString(output);
            assertEquals(expected ? 0 : 1, process.exitValue(), text);
            assertTrue(text.contains(expected ? "5 instances" : "Unavailable module type"), text);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
