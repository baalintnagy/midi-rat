# Source style and enforcement

The normative contributor rules are in [AGENTS.md](../AGENTS.md). The repository
includes these reusable settings:

| File | Purpose |
| --- | --- |
| [`.editorconfig`](../.editorconfig) | Four-space default indentation, 100-column target, LF line endings, final newlines, two-space YAML, and preserved Markdown trailing spaces. Shell continuation/case settings and Makefile tabs apply when those files exist. |
| [`eclipse/java-style.properties`](../eclipse/java-style.properties) | JDK 25 Java formatting, four-space indentation, 100-column wrapping, annotation placement, leading boolean operators, forced call-chain wrapping, and text-block layout. |
| [`checkstyle/checkstyle.xml`](../checkstyle/checkstyle.xml) | Java line length, tabs, explicit/used/nonredundant imports, braces, annotation placement, text-block delimiter layout, and a conservative unnecessary-text-block check. |

## Current integration status

The root Maven build compiles maintained Java sources with JDK 25 and `-Xlint:all`.
Spotless 3.10.4 applies the local Eclipse 4.41 profile, explicit import order, import
cleanup, and the conservative short-text-block conversion. Checkstyle 14.0.0 runs
through Maven Checkstyle 3.6.0. Both checks bind to `verify`, including tests.
Native generated projections, build output, and downloaded dependencies are outside
these maintained Java roots. The commands are:

```shell
mvn spotless:apply
mvn spotless:check checkstyle:check
mvn verify
```

Use maintained production and test source roots, including future module roots;
exclude generated sources, vendored sources, temporary utilities, and build output.
Do not copy unrelated module paths or application dependencies into the build.

The root reactor runs Spotless and Checkstyle over both `src` and
`extensions/sostenuto/src`, including their maintained tests. Child projects skip
duplicate style executions; run the documented style commands from the repository
root. The separate application/module/distribution builds retain JDK 25 compiler
checks. Generated and build output remain excluded.

## Pinned tooling

The Maven descriptor pins the following tools:

| Tool | Baseline version |
| --- | --- |
| Spotless Maven plugin | 3.10.4 |
| Eclipse JDT formatter | 4.41 |
| Maven Checkstyle plugin | 3.6.0 |
| Checkstyle engine | 14.0.0 |
| Groovy JSR223 | Not used; no script engine extension is required. |

Import order is `\#,java,`. A supported Spotless `replaceRegex` step converts the
conservative unnecessary-single-line-text-block pattern shared with Checkstyle.
Eclipse handles call-chain wrapping, indentation, and standard annotation placement.
Complex string layout, receiver placement, and continuation indentation still need
review; the formatter does not prove semantic string-value preservation. No independent
source-rewriting utility is part of the build. Run a second `spotless:apply` pass
when changing formatter settings to establish stable output.

`scripts/verify-style-contract.ps1` creates isolated fixtures under `target`, verifies
that tabs/unused imports/annotation layout are rejected, applies automatic fixes,
checks accepted and stable output, and compares compiled string values before/after.
It does not alter maintained source roots or replace the formatter.

## Enforcement limits and review

Checkstyle's line-length check excludes package/import declarations. It does not
currently exempt arbitrary long literals or URLs; any unavoidable exception needs
a narrow, documented configuration change.

The following remain code-review requirements unless future tooling explicitly
enforces them:

- Explicit production local types and appropriate `instanceof` pattern matching.
- `.formatted()` instead of string concatenation and recurring static imports.
- Separate continuation lines for arguments containing calls, constructors, or lambdas.
- Appropriate use of streams versus stateful or resource-sensitive loops.
- String-value preservation, complex quoting, and borderline text-block decisions.
- Warning cleanup and the quality of unresolved-warning explanations.

For generated source roots in Eclipse/JDT, use the source-folder attribute
`ignore_optional_problems=true` where supported. Keep those roots on the compilation
classpath with required errors enabled. Do not disable diagnostics for maintained
production or test sources. Recheck generated-root settings after a build-tool import.

When rules change, update `AGENTS.md`, the affected tool configurations, and this
coverage document together.
