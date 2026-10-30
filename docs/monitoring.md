# Monitor the current physical inputs

[wmm-application-monitor.yaml](../config/wmm-application-monitor.yaml) binds all eight
currently discovered WinMM inputs other than `postSSA` and `postSSB`.
[network-monitor.yaml](../config/network-monitor.yaml) routes those inputs through
a filter that blocks every `CLOCK` message. All other messages pass through the
internal `monitor-input` bus to a [chord observer](chord-module.md) and a message
monitor. There are no physical output bindings, emitters, or remappers.

The filter subscribes to logical aliases such as `hammer-main` and
`launchpad-midi-2`; the application file maps each alias to an exact physical name.
The monitor displays the discovered hardware port name and immediate endpoint in
separate, unlabeled columns, for example `Hammer 88 Pro [group 1] | monitor-input`
with Windows MIDI Services. Origin comes first, immediate source second, and `Note`
remains last. Logical aliases still control wiring and source matching.
At startup, hardware display names and network endpoint names determine fixed source
column widths. The timestamp also reserves a fixed width, so traffic with different
names, values, or timestamp lengths does not move subsequent columns. Network reload
recomputes the widths; an unexpected longer display name is ellipsized to preserve alignment.
The type column is 11 characters wide. Monitor-only abbreviations are `CONT`, `TC`,
`SONG_POS`, `SONG_SEL`, `TUNE_REQ`, `ACT_SEN`, and `SYS_RES`; YAML type names stay unchanged.
The nanosecond column shows the 13 least significant decimal digits, zero-padded
and grouped with spaces (for example `1 234 567 890 123 ns`). Full receipt timestamps
remain intact internally.
This is a snapshot of the current devices, not an automatic wildcard subscription.
Add a new device to both the input mappings and filter sources when needed. If a
configured device is disconnected, startup reports the missing binding; remove it
from both files or reconnect it before starting.

## PowerShell / Windows Terminal

From the repository directory, run the already-built JAR with the installed JDK 25:

```powershell
Set-Location C:\develop\gitrepos\midi-rat
& 'C:\Java\jdk25\bin\java.exe' --enable-native-access=ALL-UNNAMED -jar .\target\midi-rat-0.1.0-SNAPSHOT.jar --config .\config\wmm-application-monitor.yaml
```

Alternatively, use the packaged launcher and its bundled Java runtime:

```powershell
.\target\jpackage-out\MidiRat\MidiRat.exe --config .\config\wmm-application-monitor.yaml
```

Run these in an interactive terminal. Monitor controls: `p` pause/resume, `c` clear,
`d` raw/decoded details, `y` copy the latest displayed message, `q` exit.

The monitor fills the available terminal height with the newest messages, instead
of stopping at twelve rows. A taller terminal shows more rows; expanded details
share the same space. Resizing does not discard the retained history. The `Note`
column shows pitch-class names on NOTE_ON/OFF rows: naturals such as `C`, and both
common enharmonic names such as `(C#,Db)`. Other message types show `--`. The numeric
MIDI note remains in D1, and copied emitter text preserves the original MIDI bytes.

The colored view uses bright cyan for note-on, cyan for note-off, bright yellow
for CC, yellow for pitch bend, bright magenta for pressure/program messages,
bright red for undefined messages, and gray for other system messages. Channel
values are white, D1 yellow, D2 green, status red, source bright blue, and time gray.
Numeric accents use the darker ANSI variants for note-off, pitch bend, and channel
pressure; other types use bright accents. Labels and separators use the terminal
default color. The same palette applies to the compact view; `colors: false` and
the terminal's plain-color fallback disable it.

To validate the files without opening devices or starting the UI:

```powershell
& 'C:\Java\jdk25\bin\java.exe' --enable-native-access=ALL-UNNAMED -jar .\target\midi-rat-0.1.0-SNAPSHOT.jar --config .\config\wmm-application-monitor.yaml --validate
```

To rebuild the JAR without invoking native/app-image packaging:

```powershell
$env:JAVA_HOME = 'C:\Java\jdk25'
mvn '-P!windows-package' verify
```

## VS Code

Open the repository folder and install the recommended **Extension Pack for Java**
if needed. The workspace settings select the installed `C:/Java/jdk25` runtime.
If that location changes, update `.vscode/settings.json` and `.vscode/tasks.json`.

In **Run and Debug**, select **MIDI Rat: monitor inputs**, then press F5 to debug
or Ctrl+F5 to run. It uses the integrated terminal for keyboard input. A separate
external-terminal launch is also available, along with validation and port-listing
launches. Let the Java extension finish importing the Maven project before launch.

Ctrl+Shift+B runs **MIDI Rat: build JAR**. **Terminal → Run Task** also offers the
Windows distribution build and **MIDI Rat: run input monitor (JAR)**, which builds
the JAR before running it. The VS Code Debug Console is unsuitable for the UI
because it does not accept interactive terminal input.

## Windows MIDI Services monitor

[wms-application-monitor.yaml](../config/wms-application-monitor.yaml)
selects `windows-midi-services`. It binds the current machine's eight physical
input/group ports by their discovered stable IDs: Hammer 88 Pro groups 1–4,
Launchpad Pro MK3 groups 1–3, and MOTU M Series MIDI group 1. `postSSA` and `postSSB`
are excluded. It shares `network-monitor.yaml`, including the CLOCK blacklist,
monitor history, and color settings.

Stop the running WinMM application and start the Services configuration:

```powershell
.\target\jpackage-out\MidiRat\MidiRat.exe --config .\config\wms-application-monitor.yaml
```

Inspect the native endpoint/group IDs with the same command plus `--ports`.
The bindings are machine-specific; re-enumerate and update them if devices change.
Changing backend requires a restart, rather than a network reload.

VS Code also offers **MIDI Rat: monitor inputs (Windows MIDI Services)** and
**MIDI Rat: list ports (Windows MIDI Services)**. Both build the native bridge first
and set the native DLL paths to `target/native`. The packaged launcher supplies
those DLL paths itself. The backend explicitly preloads its app-local SDK DLL
before WinRT activation; the Windows MIDI Service must also be available.
