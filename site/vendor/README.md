# Browser libraries

Unmodified runtime assets, copied from the pinned npm packages by `npm run vendor`.
The site serves them locally; viewing diagrams makes no CDN requests.

| Package | Version | Source | License |
| --- | --- | --- | --- |
| ace-builds | 1.45.2 | https://github.com/ajaxorg/ace-builds | BSD-3-Clause, `ACE-LICENSE` |
| yaml | 2.9.1 | https://github.com/eemeli/yaml/tree/v2.9.1 | ISC, `YAML-LICENSE` |
| elkjs | 0.12.0 | https://github.com/kieler/elkjs/tree/0.12.0 | EPL 2.0, `ELK-LICENSE.md` |
| @panzoom/panzoom | 4.6.2 | https://github.com/timmywil/panzoom/tree/4.6.2 | MIT, `PANZOOM-LICENSE.txt` |

Run `npm ci` followed by `npm run vendor` to reproduce these files. Maintain
licenses alongside the corresponding assets and keep generated/vendor code out
of first-party formatting checks.
