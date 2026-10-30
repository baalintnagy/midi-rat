# Source style and enforcement

The normative contributor rules are in [AGENTS.md](../AGENTS.md). The repository
includes these reusable settings:

| File | Purpose |
| --- | --- |
| [`.editorconfig`](../.editorconfig) | Four-space default indentation, 100-column target, LF line endings, final newlines, two-space YAML, and preserved Markdown trailing spaces. Shell continuation/case settings and Makefile tabs apply when those files exist. |
| [`eclipse/java-style.properties`](../eclipse/java-style.properties) | JDK 25 Java formatting, four-space indentation, 100-column wrapping, annotation placement, leading boolean operators, forced call-chain wrapping, and text-block layout. |
| [`checkstyle/checkstyle.xml`](../checkstyle/checkstyle.xml) | Java line length, tabs, explicit/used/nonredundant imports, braces, annotation placement, text-block delimiter layout, and a conservative unnecessary-text-block check. |

## Current integration status

There is no Java build descriptor or application source yet. EditorConfig can be
used by compatible editors, and the Eclipse profile can be used by supported IDE
or formatter integrations. Checkstyle settings are present but are not wired into
an automated build. No formatting or lint execution is implied by their presence.

Maven is selected by the final specification; build integration remains pending.
Integrate Spotless and Checkstyle, use these local configuration paths, and bind
their checks to `verify`. After integration, the intended commands are:

```shell
mvn spotless:apply
mvn spotless:check checkstyle:check
mvn verify
```

Use maintained production and test source roots, including future module roots;
exclude generated sources, vendored sources, temporary utilities, and build output.
Do not copy unrelated module paths or application dependencies into the build.

## Tooling baseline for build integration

The imported settings were paired with the following pinned tools. These are an
integration baseline, not installed or validated dependencies in this repository:

| Tool | Baseline version |
| --- | --- |
| Spotless Maven plugin | 3.10.4 |
| Eclipse JDT formatter | 4.41 |
| Maven Checkstyle plugin | 3.6.0 |
| Checkstyle engine | 14.0.0 |
| Groovy JSR223, if the text-block extension is retained | 4.0.32 |

The formatter profile alone does not reproduce the full formatting pipeline.
For equivalent Maven integration, configure explicit import cleanup and the import
order `\#,java,`, and use supported Spotless steps to:

- Convert the conservative subset of unnecessary single-line text blocks to quoted
  strings, keeping its pattern synchronized with Checkstyle's multiline regex.
- Keep receivers and their first calls together while preserving comments and literals.
- Align text-block delimiters and add one continuation level for assignments and
  standalone return, yield, and throw expressions without changing string values.
- Wrap long quoted string assignments and applicable annotation values.

Use the existing Spotless extensions where needed rather than introducing a separate
formatter. Validate representative fixtures and a second formatting pass when wiring
these steps into the build. Exact build integration remains pending.

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
