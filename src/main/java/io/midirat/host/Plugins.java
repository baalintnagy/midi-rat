package io.midirat.host;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.midirat.api.ModuleProvider;
import io.midirat.module.Builtins;

/** Reference-counted stable shadow copies keep unchanged instances and loaders intact. */
public final class Plugins implements AutoCloseable {
    private final Map<String, ModuleProvider> providers = new LinkedHashMap<>();
    private final Map<Path, Package> packages = new LinkedHashMap<>();
    private final Consumer<String> errors;

    public Plugins(Path directory, Consumer<String> errors) throws IOException {
        this(directory, errors, null);
    }

    public Plugins(Path directory, Consumer<String> errors, Plugins previous) throws IOException {
        this.errors = errors;
        Builtins.providers()
            .forEach(p -> providers.put(p.type(), p));
        Files.createDirectories(directory);
        try (Stream<Path> files = Files.list(directory)) {
            for (Path jar : files.filter(p -> p.getFileName()
                .toString()
                .endsWith(".jar"))
                .sorted()
                .toList()) {
                byte[] digest = digest(jar);
                Package existing = previous == null ? null : previous.packages.get(jar);
                Package loaded;
                if (existing != null
                    && Arrays.equals(existing.digest, digest)) {
                    loaded = existing;
                    loaded.references++;
                } else {
                    loaded = new Package(jar, digest);
                }
                packages.put(jar, loaded);
                for (ModuleProvider provider : loaded.providers) {
                    if (providers.putIfAbsent(provider.type(), provider) != null) {
                        throw new IllegalArgumentException(
                            "Conflicting plugin type: %s".formatted(provider.type()));
                    }
                }
            }
        } catch (IOException | RuntimeException | ServiceConfigurationError exception) {
            close();
            throw exception;
        }
    }

    private static byte[] digest(Path file) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public Map<String, ModuleProvider> providers() {
        return Map.copyOf(providers);
    }

    public boolean changed(String type, Plugins previous) {
        return providers.get(type) != previous.providers.get(type);
    }

    @Override
    public void close() {
        providers.clear();
        packages.values()
            .forEach(p -> {
                if (--p.references == 0) {
                    try {
                        p.loader.close();
                        Files.deleteIfExists(p.copy);
                    } catch (IOException exception) {
                        errors.accept("Plugin cleanup: %s".formatted(exception.getMessage()));
                    }
                }
            });
        packages.clear();
    }

    private static final class Package {
        private final byte[] digest;
        private final Path copy;
        private final URLClassLoader loader;
        private final List<ModuleProvider> providers;
        private int references = 1;

        Package(Path jar, byte[] digest) throws IOException {
            this.digest = digest;
            copy = Files.createTempFile("midi-rat-plugin-", ".jar");
            URLClassLoader candidate = null;
            try {
                Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
                if (!Arrays.equals(digest, digest(copy))
                    || !Arrays.equals(digest, digest(jar))) {
                    throw new IOException("Plugin changed during copy: %s; retry".formatted(jar));
                }
                candidate = new URLClassLoader(new URL[] { copy.toUri()
                    .toURL() }, ModuleProvider.class.getClassLoader());
                List<ModuleProvider> discovered = new ArrayList<>();
                for (ModuleProvider provider : ServiceLoader.load(ModuleProvider.class,
                    candidate)) {
                    if (provider.type() == null
                        || provider.type()
                            .isBlank()
                        || provider.version() == null
                        || provider.version()
                            .isBlank()
                        || provider.role() == null
                        || provider.apiVersion() != ModuleProvider.HOST_API_VERSION) {
                        throw new IllegalArgumentException(
                            "Invalid/incompatible plugin metadata: %s".formatted(jar));
                    }
                    discovered.add(provider);
                }
                if (discovered.isEmpty()) {
                    throw new IllegalArgumentException("No module providers in %s".formatted(jar));
                }
                loader = candidate;
                providers = List.copyOf(discovered);
            } catch (IOException | RuntimeException | ServiceConfigurationError exception) {
                if (candidate != null) {
                    candidate.close();
                }
                Files.deleteIfExists(copy);
                throw exception;
            }
        }
    }
}
