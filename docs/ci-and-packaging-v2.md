# CI and packaging contract — iteration 2

**Status:** CI definition added; Java build and native packages not implemented.  
**Parent:** [Specification, iteration 2](../SPECIFICATION-v2.md), Section 13.

## 1. Current workflow

[`.github/workflows/build.yml`](../.github/workflows/build.yml) runs on branch pushes,
pull requests, version tags beginning with `v`, and manual dispatch. It uses a
Windows runner and JDK 25. Pull-request and branch jobs have read-only repository
permissions. The release job alone receives permission to publish tagged artifacts.

The workflow first executes [the source validation script](../scripts/validate-specification.ps1),
which checks required specification/style files, local links in the new documents,
Markdown fence balance, and Checkstyle XML well-formedness. It does not execute
Checkstyle or prove application behavior.

When no root `pom.xml` exists, normal branch/PR/manual runs report an explicit
documentation/configuration-only result. Java verification and packaging do not run.
A version tag without a build descriptor fails, preventing an empty application release.

Once a root build exists, the workflow automatically requires:

1. `mvn --batch-mode --no-transfer-progress clean verify` on JDK 25.
2. Spotless and Checkstyle executions bound to `verify`, alongside relevant tests.
3. A Windows app image at `target/jpackage-out/MidiRat/` containing `MidiRat.exe`
   and its bundled runtime.
4. Creation of `target/MidiRat-windows.zip` from the app-image contents.
5. Artifact upload; missing required output fails the job.
6. For a `v*` tag, a release job that downloads the verified ZIP and creates a
   GitHub release attached to the existing tag.

The descriptor's presence triggers these gates; it is not a reason to weaken them.
A first build that does not yet package an application will fail this workflow
until the required packaging is supplied. Do not interpret a source-only run as
a successful application build.

## 2. Proposed distribution

The following layout is part of **new assumption N14**, not an implemented artifact:

```text
MidiRat/
  MidiRat.exe
  app/                  # JARs and required native components
  runtime/              # bundled Java runtime
  config/
    application.yaml
    network.yaml
  modules/              # external plugin packages
  logs/
```

Packaged application configuration should use `moduleDirectory: ../modules` and
`networkFile: ./network.yaml` when both YAML files are in `config/`. Paths resolve
relative to `application.yaml`, not the process working directory. A portable JAR
distribution must document its equivalent locations and native prerequisites.

The launcher must preserve console interaction for TamboUI and enable the native
access required by its binding. Package only the native libraries for the selected
architecture; document modern Windows API/service prerequisites separately from
the bundled JDK. A bundled runtime does not install the OS service or establish
that its API is available.

The modern native bridge needs its own toolchain build step when its implementation
exists. Select and pin the SDK, compiler, architecture matrix, runtime dependencies,
and redistribution policy then; the current workflow does not build an absent bridge.

## 3. Release validation still required

Hosted CI can validate deterministic synthetic routing, filter rules, emitter parsing,
remapping/state lifecycles, style, package structure, and native-library load tests
that do not require physical hardware. It cannot establish performance with the
user's MIDI devices or validate all Windows MIDI Services installations.

Before a stable release, test actual input/output and SysEx for every backend,
measure the agreed latency/load budgets, validate device removal/reconnect policy,
exercise terminal input/rendering, and launch the ZIP on a clean supported system.
Record tested OS/architecture, device, driver, service/API, JVM, and terminal versions.

The GitHub workflow has been authored locally. It has not been dispatched or
observed passing on GitHub as part of this specification revision.
