# Third-party components

The executable JAR retains dependency license and notice resources. Distribution
must include this notice and the notices shipped in the bundled Temurin runtime.
The MIDI Rat project's own license remains undecided.

The static network viewer bundles these unmodified browser libraries. Full license
texts accompany the published assets in `vendor/` (repository path `site/vendor/`).

| Browser component | Version | License / upstream |
| --- | --- | --- |
| Ace | 1.45.2 | BSD-3-Clause, https://github.com/ajaxorg/ace-builds; `vendor/ACE-LICENSE` |
| yaml | 2.9.1 | ISC, https://github.com/eemeli/yaml; `vendor/YAML-LICENSE` |
| ELK.js | 0.12.0 | EPL-2.0, https://github.com/kieler/elkjs; `vendor/ELK-LICENSE.md` |
| Panzoom | 4.6.2 | MIT, https://github.com/timmywil/panzoom; `vendor/PANZOOM-LICENSE.txt` |

Prettier 3.9.10 (MIT, https://github.com/prettier/prettier) and ESLint 10.14.0
(MIT, https://github.com/eslint/eslint) are development tooling and are not included
in the published site.

The adapted sostenuto processing includes work copyright (c) 2025 Balint Nagy,
under the MIT license. Its complete notice is retained in the application JAR at
`META-INF/third-party/sostenuto/LICENSE`.

| Component | Version | License / upstream |
| --- | --- | --- |
| TamboUI | 0.6.0 | MIT, https://github.com/tamboui/tamboui |
| JLine (TamboUI backend) | 3.25.1 | BSD-3-Clause, https://github.com/jline/jline3 |
| SnakeYAML | 2.5 | Apache-2.0, https://bitbucket.org/snakeyaml/snakeyaml |
| JNA / JNA Platform | 5.18.1 | LGPL-2.1-or-later OR Apache-2.0, https://github.com/java-native-access/jna |
| Windows MIDI Services SDK | 0.99.92-preview.11 | MIT, https://github.com/microsoft/MIDI |
| C++/WinRT | 3.0.260520.1 | MIT, https://github.com/microsoft/cppwinrt |
| Visual C++ runtime | Visual Studio 2026 (VC145) redistributable | Microsoft Visual Studio redistribution terms; not covered by the SDK MIT license |
| Eclipse Temurin | JDK 25 | GPL-2.0 with Classpath Exception; see runtime/legal |

Windows MIDI Services and C++/WinRT copyright Microsoft Corporation. Their MIT
license permits use, copying, modification, distribution, sublicensing and sale
provided the copyright and permission notice are included. They are provided
without warranty. Full upstream license texts are installed by the native build
script beside the native DLLs in the distribution's app directory.
