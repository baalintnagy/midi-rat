# Sostenuto module

The external `sostenuto` module emulates sustain and sostenuto pedals and forwards
processed MIDI to configured destinations. Each instance has independent state.
The Windows portable distribution includes the plugin and its Java runtime.

## Application configuration

Save this as `config/application-sostenuto.yaml`. Replace the placeholder device names
with exact names from `MidiRat.exe --ports`.

```yaml
schemaVersion: 1
application:
  backend: winmm
  moduleDirectory: ../modules
  networkFile: ./network-sostenuto.yaml
  ports:
    inputs:
      keyboard: "Example MIDI Keyboard"
    outputs:
      synth: "Example MIDI Synthesizer"
```

## Processing and example network

Save the following as `config/network-sostenuto.yaml`. The sostenuto processor receives
the keyboard's notes and pedal messages,
then forwards its output to both the synthesizer and the observers. The chord display
therefore follows the processed notes, including retained releases.

```yaml
schemaVersion: 1
network:
  buses: [processed]
  modules:
    - name: pedals
      type: sostenuto
      sources: [keyboard]
      destinations: [processed, synth]
      settings: {}
    - name: monitor
      type: midi-monitor
      sources: [processed]
      settings: {historyLimit: 2000, colors: true}
    - name: chords
      type: midi-chord
      sources: [processed]
      settings: {}
```

CC64 controls sustain and CC66 controls sostenuto. Sustain holds releases; sostenuto
captures notes already held when it engages. The processor consumes these pedal
messages while forwarding other ordinary MIDI. CC22 is an additional default sostenuto
trigger. The trigger can instead use a configured CC or note, with momentary or toggle
behavior. Each instance has independent state.

Sostenuto is an external module. Its `sostenuto.jar` must be present in the configured
`moduleDirectory`; the portable distribution and root Maven build install it there.

## Run and validate

From the portable application's directory:

```powershell
.\MidiRat.exe --config .\config\application-sostenuto.yaml --validate
.\MidiRat.exe --config .\config\application-sostenuto.yaml
```

Network reload and module replacement do not migrate pedal state. Reload, reconnect,
module removal, and shutdown do not send automatic note cleanup. Host Panic is an
explicit action.

## Build and installation

With JDK 25 and Maven available, run from the repository root:

```powershell
mvn --batch-mode --no-transfer-progress clean verify
```

The Maven reactor builds `extensions/sostenuto` alongside the host and distribution.
The root build installs the plugin in the development module directory; Windows
packaging includes it in the portable application.

| Output | Location |
| --- | --- |
| External module JAR | `extensions/sostenuto/target/sostenuto.jar` |
| Development plugin copy | `modules/sostenuto.jar` |

When installing manually, copy `sostenuto.jar` into the application's configured
`moduleDirectory`. The host loads it as an external provider; its module type is
`sostenuto`.
