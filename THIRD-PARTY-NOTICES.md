# Third-party components

The executable JAR retains dependency license and notice resources. Distribution
must include this notice and the notices shipped in the bundled Temurin runtime.
The MIDI Rat project's own license remains undecided.

| Component | Version | License / upstream |
| --- | --- | --- |
| TamboUI | 0.6.0 | MIT, https://github.com/tamboui/tamboui |
| JLine (TamboUI backend) | 3.25.1 | BSD-3-Clause, https://github.com/jline/jline3 |
| SnakeYAML | 2.5 | Apache-2.0, https://bitbucket.org/snakeyaml/snakeyaml |
| Tonal | 5.1.3 | MIT, https://github.com/tonaljs/tonal |
| Rhino | 1.8.1 | MPL-2.0, https://github.com/mozilla/rhino |
| Babel helpers (generated into Tonal bundle) | See tools/tonal/package-lock.json | MIT, https://github.com/babel/babel |
| JNA / JNA Platform | 5.18.1 | LGPL-2.1-or-later OR Apache-2.0, https://github.com/java-native-access/jna |
| Windows MIDI Services SDK | 0.99.92-preview.11 | MIT, https://github.com/microsoft/MIDI |
| C++/WinRT | 3.0.260520.1 | MIT, https://github.com/microsoft/cppwinrt |
| Visual C++ runtime | Visual Studio 2022 redistributable | Microsoft Visual Studio redistribution terms; not covered by the SDK MIT license |
| Eclipse Temurin | JDK 25 | GPL-2.0 with Classpath Exception; see runtime/legal |

Windows MIDI Services and C++/WinRT copyright Microsoft Corporation. Their MIT
license permits use, copying, modification, distribution, sublicensing and sale
provided the copyright and permission notice are included. They are provided
without warranty. Full upstream license texts are installed by the native build
script beside the native DLLs in the distribution's app directory.
