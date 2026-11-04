param([Parameter(Mandatory = $true)][string]$Version)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & "$PSScriptRoot/build-native.ps1"
    $inputDirectory = Join-Path $root 'target/jpackage-input'
    New-Item -ItemType Directory -Force $inputDirectory | Out-Null
    $jarName = "midi-rat-$Version.jar"
    Copy-Item -LiteralPath (Join-Path $root "target/$jarName") -Destination $inputDirectory
    Copy-Item -Path (Join-Path $root 'target/native/*') -Destination $inputDirectory
    $destination = Join-Path $root 'target/jpackage-out'
    $image = [IO.Path]::GetFullPath((Join-Path $destination 'MidiRat'))
    $allowed = [IO.Path]::GetFullPath((Join-Path $root 'target/jpackage-out/MidiRat'))
    if ($image -ne $allowed -or -not $image.StartsWith($root + [IO.Path]::DirectorySeparatorChar)) {
        throw 'Unsafe app-image target'
    }
    if (Test-Path -LiteralPath $image) { Remove-Item -LiteralPath $image -Recurse -Force }
    & "$env:JAVA_HOME/bin/jpackage.exe" --type app-image --name MidiRat `
        --input $inputDirectory --main-jar $jarName --main-class io.midirat.Main `
        --dest $destination --app-version ($Version -replace '-.*$', '') --win-console `
        --java-options '--enable-native-access=ALL-UNNAMED' `
        --java-options '-Djna.library.path=$APPDIR' `
        --java-options '-Djava.library.path=$APPDIR' `
        --add-modules java.base,java.desktop,java.logging,java.management,jdk.jfr,jdk.unsupported
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
    Copy-Item -LiteralPath (Join-Path $root 'config') -Destination $image -Recurse
    New-Item -ItemType Directory -Force (Join-Path $image 'modules'), (Join-Path $image 'logs') | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'extensions/sostenuto/target/sostenuto.jar') `
        -Destination (Join-Path $image 'modules/sostenuto.jar')
    foreach ($example in @('wmm-application-sostenuto.yaml', 'wms-application-sostenuto.yaml',
            'wmm-application-sostenuto-launchpad.yaml', 'wms-application-sostenuto-launchpad.yaml')) {
        & (Join-Path $image 'MidiRat.exe') --config (Join-Path $image "config/$example") --validate
        if ($LASTEXITCODE -ne 0) { throw "Packaged external sostenuto validation failed: $example" }
    }
    Copy-Item -LiteralPath (Join-Path $root 'THIRD-PARTY-NOTICES.md') -Destination $image
    # Some JDK distributions mark launcher/runtime templates read-only. Keep the
    # generated image removable by Maven clean without changing installed JDK files.
    Get-ChildItem -LiteralPath $image -Recurse -File | ForEach-Object { $_.IsReadOnly = $false }
    if (-not (Test-Path -LiteralPath (Join-Path $image 'MidiRat.exe'))) { throw 'Missing launcher' }
    if (-not (Test-Path -LiteralPath (Join-Path $image 'runtime/bin/java.dll'))) { throw 'Missing runtime' }
    $dependencies = & "$env:JAVA_HOME/bin/jdeps.exe" --multi-release 25 `
        --ignore-missing-deps --print-module-deps (Join-Path $inputDirectory $jarName)
    if ($LASTEXITCODE -ne 0) { throw 'Runtime module dependency inspection failed' }
    $release = Get-Content -LiteralPath (Join-Path $image 'runtime/release') -Raw
    $moduleLine = [regex]::Match($release, '(?m)^MODULES="([^"]+)"').Groups[1].Value
    $modules = $moduleLine.Split(' ')
    foreach ($required in ($dependencies -join '').Trim().Split(',')) {
        if ($required -notin $modules) { throw "Bundled runtime is missing module: $required" }
    }
    # Exercise TamboUI through a temporary launcher using the actual bundled JVM.
    # --validate does not load the terminal UI and cannot detect missing UI modules.
    $probeClass = Join-Path $root 'target/test-classes/io/midirat/PackagedUiProbe.class'
    if (-not (Test-Path -LiteralPath $probeClass)) { throw 'Compile test sources before packaging' }
    $probeLauncher = Join-Path $image 'MidiRatPackageProbe.exe'
    $probeConfig = Join-Path $image 'app/MidiRatPackageProbe.cfg'
    try {
        Copy-Item -LiteralPath (Join-Path $image 'MidiRat.exe') -Destination $probeLauncher
        $probeClasspath = Join-Path $root 'target/test-classes'
        $configuration = @'
[Application]
app.classpath=$APPDIR\{0}
app.classpath={1}
app.mainclass=io.midirat.PackagedUiProbe

[JavaOptions]
java-options=--enable-native-access=ALL-UNNAMED
'@ -f $jarName, $probeClasspath
        [IO.File]::WriteAllText($probeConfig, $configuration, [Text.UTF8Encoding]::new($false))
        & $probeLauncher
        if ($LASTEXITCODE -ne 0) { throw 'Bundled runtime UI smoke test failed' }
    } finally {
        Remove-Item -LiteralPath $probeLauncher, $probeConfig -Force -ErrorAction SilentlyContinue
    }
} finally { Pop-Location }
