# MIDI Rat contributor instructions

## Project requirements and decision records

- Use JDK 25 for the first stable implementation, TamboUI for the terminal UI,
  and SnakeYAML for YAML configuration. All module settings and wiring use YAML.
- Use `SPECIFICATION-final.md` as the current consolidated requirements. Earlier
  specifications are historical where they conflict with it. All current module
  DSLs, backend/monitor/packaging requirements, and user decisions are included there;
  do not require separate assumptions or companion documents to implement them.
- The MIDI I/O, filter, monitor, and packaging baseline was inspected during
  specification work and incorporated into the final specification. Preserve its
  direct WinMM/JNA integration, complete monitor fields, and Windows app-image
  packaging requirements. Explain deliberate departures rather than silently
  replacing required behavior with generic alternatives.
- The user explicitly rejected note tracking and automatic safety: do not add note
  pairing ledgers, dynamic tuning-channel allocation, corrective note-offs, or
  implicit panic on reload/removal/shutdown. Preserve only the defined CC-trigger
  transition state. Explicit host Panic must cover all buses as specified.
- The user-requested `midi-chord` observer is a narrow exception: keep its own held
  input-note state for chord naming, combining origins and immediate sources.
  Repeated note-ons retrigger a channel/pitch; they do not accumulate release counts.
  This does not authorize note tracking or corrective output in routing/remapping.
- The user-requested `sostenuto` processor is another narrow exception: retain
  pressed/latched/deferred-release state and bounded umbrella scheduling required
  for pedal emulation. Do not introduce lifecycle panic or corrective output.
- The Java coding and formatting conventions requested in the original draft are
  adopted below and in the local style configuration. These rules are self-contained;
  contributors do not need another checkout to follow them.
- Keep product documentation self-contained and free of references to other local
  projects. Do not delete historical source/decision documents unless requested;
  current tooling must work without removable companion documents.
- Mark decisions introduced to resolve ambiguity or insufficient information.
  Distinguish required behavior, proposed extensions, and unresolved decisions.
- Preserve MIDI 2.0 as a future requirement and Rust as a possible later direction;
  neither changes the JDK 25 requirement for the first stable release.

## Engineering principles

- Prefer narrow capability interfaces and polymorphic dispatch over concrete module
  `instanceof` checks in host and UI code. Keep parsing-boundary shape checks, equality
  checks, and exhaustive sealed-domain matching where they express the actual contract.
  Do not replace legitimate checks with reflection, casts, or speculative abstractions.

- Follow clean-code and SOLID principles. Keep MIDI domain behavior separate from
  device access, terminal presentation, filesystem watching, and configuration.
- Prefer established third-party libraries when appropriate. Use supported framework
  settings, checks, and extension points instead of inventing replacement utilities.
- Preserve third-party licenses and notices. The project's own license is not decided
  by adopting coding conventions.

## Source style

- Use four spaces for indentation, no literal tabs in Java, and a target width of
  100 characters. Keep indivisible URLs, literals, and required syntax intact;
  document unavoidable tool exceptions instead of globally disabling checks.
- Use two-space indentation in YAML. Follow `.editorconfig` for whitespace and
  newline settings; preserve intentional Markdown trailing spaces.
- Use explicit imports for every Java type and static member; no wildcard imports.
  Remove unused and redundant imports. Avoid fully qualified names when an import
  is possible. Use explicit static imports for recurring static calls.
- Order imports in groups: static imports, `java` imports, then remaining imports,
  with alphabetical ordering within groups.
- Use explicit local types in production Java. `var` is allowed in tests and loop
  declarations.
- Use `_` for unused catch variables; retain descriptive names when the exception
  is referenced.
- Use modern `instanceof` pattern matching when using the tested value as that type.
  Keep plain `instanceof` for type-only checks.
- Always use braces for conditional and loop bodies in languages that support them.
- Put member and type annotations on separate lines. Keep parameter annotations
  inline in method signatures.
- Do not concatenate Java strings. Use `"...%s...".formatted(...)` for runtime values
  and text blocks for multiline strings. Preserve exact values, whitespace, and
  trailing newlines when changing representation.
- Prefer ordinary quoted strings for single-line values that fit within 100 columns,
  moving the literal to a continuation line when needed. Reserve text blocks for
  multiline values, necessary wrapping, or substantially clearer quoting.
- Put text-block opening delimiters on their own lines, normally after the assignment,
  return keyword, or preceding argument. Align opening and closing delimiters. Indent
  continuation text blocks by one additional four-space level, including their content.
  Count existing argument continuation indentation toward that level. Use text-block
  line continuations where needed to preserve string values while wrapping.
- Break compound call-based conditions before boolean operators so individual calls
  can have breakpoints.
- Put arguments containing function calls, constructor calls, or supplier/lambda
  expressions on separate continuation lines for individual breakpoints.
- Put every invocation after the first call in a Java call chain on its own line,
  with a leading dot and one four-space continuation level, even for short chains.
  Keep the receiver and first call together when they fit; a literal is not a call.
- Prefer streams or collection operations when no extra mutable state is needed.
  Retain stateful, resource-sensitive, and control-flow loops.
- Apply style rules to maintained production and test sources, not vendored code,
  generated output, or dependency lockfiles.

## Warnings, tooling, and verification

- Fix straightforward Java compiler warnings instead of hiding them. If a warning
  cannot readily be fixed, report it with practical options and tradeoffs. This is
  not a blanket warnings-as-errors requirement.
- Distinguish source compiler warnings, IDE/JDT diagnostics, and dependency runtime
  warnings. Do not globally suppress them or claim one clean check establishes all
  are clean.
- Exclude generated source roots from style checks and optional IDE diagnostics,
  while preserving their compilation classpath and required compilation errors.
  Keep checks enabled on maintained production and test sources.
- Use the local Eclipse formatter profile and Checkstyle configuration. See
  `docs/source-style.md` for current build integration and enforcement coverage.
- When introducing the selected Maven build, wire established formatting and lint
  tools into verification, pin their versions, include maintained production and
  test sources, and exclude generated/build output. Use Spotless for formatting
  and import cleanup and Checkstyle for validation; bind both checks to `verify`.
- Once Maven integration exists, use `mvn spotless:apply` for routine Java formatting,
  `mvn spotless:check checkstyle:check` for style-only validation, and `mvn verify`
  for build verification. These commands are wired into the root Maven build.
- Maintain these instructions, tool configuration, and enforcement documentation
  together. Document conventions that remain review requirements. Prefer supported
  formatter/check extensions over independent source-rewriting programs.
- Verify tool changes against representative violations and, when automatic fixes
  exist, check that fixes preserve behavior and produce stable, accepted output.
- Run checks appropriate to the change and report their actual scope. For this
  implementation stage, distinguish synthetic Java tests and native/package build
  checks from physical MIDI, terminal, clean-machine, and latency qualification.
