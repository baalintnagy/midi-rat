param([ValidateSet('x64', 'ARM64')][string]$Architecture = 'x64')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
    throw 'Install Visual Studio 2026 Build Tools with Desktop development with C++ (vswhere.exe is missing).'
}
$visualStudio = & $vswhere -latest -products '*' -version '[18.0,19.0)' `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($visualStudio)) {
    throw 'Visual Studio 2026 C++ tools not found. Use windows-2025-vs2026 in CI or install the C++ workload locally.'
}
if (-not (Get-Command cmake -ErrorAction SilentlyContinue)) {
    throw 'Install CMake 4.2 or newer for the Visual Studio 2026 generator.'
}
$cmakeCapabilities = & cmake -E capabilities
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect CMake capabilities.' }
$cmakeCapabilities = $cmakeCapabilities | ConvertFrom-Json
if ('Visual Studio 18 2026' -notin $cmakeCapabilities.generators.name) {
    throw 'Install CMake 4.2 or newer for the Visual Studio 2026 generator.'
}
$metadata = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/UnionMetadata/10.0.26100.0/Windows.winmd'
if (-not (Test-Path -LiteralPath $metadata -PathType Leaf)) {
    throw 'Install Windows SDK 10.0.26100.0'
}
Write-Host "Native toolchain: Visual Studio 2026 at $visualStudio; v145; Windows SDK 10.0.26100.0; $Architecture"
$cache = Join-Path $root 'target/native-sdk'
New-Item -ItemType Directory -Force $cache | Out-Null
function Get-PinnedArchive($Name, $Url, $Hash) {
    $archive = Join-Path $cache "$Name.zip"
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri $Url -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Hash) {
        throw "Checksum mismatch: $archive"
    }
    $destination = Join-Path $cache $Name
    if (-not (Test-Path -LiteralPath $destination)) {
        Expand-Archive -LiteralPath $archive -DestinationPath $destination
    }
}
Get-PinnedArchive 'midi' `
    'https://github.com/microsoft/MIDI/releases/download/inbox-preview-11/Windows.Devices.Midi2.0.99.92-preview.11.nupkg' `
    '13AFC6E6DA2ADAC309AB8C174352F46F1C0EBE2D79E65475BD6274DEFB73DF74'
Get-PinnedArchive 'cppwinrt' `
    'https://api.nuget.org/v3-flatcontainer/microsoft.windows.cppwinrt/3.0.260520.1/microsoft.windows.cppwinrt.3.0.260520.1.nupkg' `
    'D22E2E26133D63217AE26E91B1685FB024B03A508A78AF645F8347A3126C8435'
$generated = Join-Path $cache 'generated'
& (Join-Path $cache 'cppwinrt/bin/cppwinrt.exe') `
    -input (Join-Path $cache 'midi/ref/native/Windows.Devices.Midi2.winmd') `
    -input $metadata -base -output $generated
if ($LASTEXITCODE -ne 0) { throw 'C++/WinRT projection generation failed' }
$build = Join-Path $root "target/native-build-vs2026-$($Architecture.ToLowerInvariant())"
cmake -S (Join-Path $root 'native') -B $build -G 'Visual Studio 18 2026' `
    -A $Architecture -T v145 "-DCMAKE_GENERATOR_INSTANCE=$visualStudio" `
    "-DGENERATED_HEADERS=$generated" '-DCMAKE_SYSTEM_VERSION=10.0.26100.0'
if ($LASTEXITCODE -ne 0) { throw 'Native configure failed' }
cmake --build $build --config Release
if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
$out = Join-Path $root 'target/native'
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item -LiteralPath (Join-Path $build 'Release/midirat_bridge.dll') -Destination $out
$runtime = Join-Path $cache "midi/runtimes/win-$($Architecture.ToLowerInvariant())/native"
Copy-Item -Path (Join-Path $runtime 'Windows.Devices.Midi2.dll'), `
    (Join-Path $runtime 'Windows.Devices.Midi2.pri') -Destination $out
Copy-Item -LiteralPath (Join-Path $root 'native/MICROSOFT-LICENSE.txt') -Destination $out
$redistRoot = Join-Path $visualStudio 'VC/Redist/MSVC'
$redistVersion = Get-ChildItem -LiteralPath $redistRoot -Directory |
    Where-Object Name -Match '^14\.' | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $redistVersion) { throw 'Install the Visual Studio 2026 C++ redistributable component.' }
$crt = Join-Path $redistVersion.FullName "$($Architecture.ToLowerInvariant())/Microsoft.VC145.CRT"
if (-not (Test-Path -LiteralPath $crt -PathType Container)) {
    throw "Visual Studio 2026 CRT files are missing: $crt"
}
Copy-Item -Path (Join-Path $crt '*.dll') -Destination $out
