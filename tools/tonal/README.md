# Pinned Tonal bundle

The upstream browser bundle is Tonal 5.1.3 (MIT, copyright 2015 danigb), downloaded
unchanged from <https://cdn.jsdelivr.net/npm/tonal@5.1.3/browser/tonal.min.js>.
Its SHA-256 is
`20454ef1ea3cf6076e12e11dc7ad369dc840c1b840c66d8d8f4367429300633d`.
Upstream: <https://github.com/tonaljs/tonal>.

Rhino 1.8.1 cannot parse all syntax in that bundle. Babel transpiles syntax to ES5
without replacing any Tonal algorithms. The generated runtime asset is committed
at `src/main/resources/vendor/tonal/tonal-5.1.3.es5.js`, alongside Tonal's MIT license
and the Babel helpers' MIT license. Maven packages it without JavaScript tooling.
Maintained Java style checks do not apply to vendored/generated JavaScript.

To regenerate from this directory, with Node/npm installed:

```powershell
npm ci --ignore-scripts --no-audit --no-fund
npm run build
```

`build.mjs` checks the upstream digest before transforming it. Dependencies are
pinned in `package-lock.json`. The application uses only Tonal's MIDI note-name
conversion and `Chord.detect`; source identities and held-key accounting stay in Java.
