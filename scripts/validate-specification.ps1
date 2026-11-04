$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$documents = @(
    'SPECIFICATION-final.md'
    'AGENTS.md'
    'docs/source-style.md'
)
$requiredFiles = $documents + @(
    '.editorconfig'
    '.gitattributes'
    'eclipse/java-style.properties'
    'checkstyle/checkstyle.xml'
    '.github/workflows/build.yml'
)

foreach ($relativePath in $requiredFiles) {
    $absolutePath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        throw "Required file is missing: $relativePath"
    }
}

$javaSources = @(& git -C $repositoryRoot ls-files -- '*.java')
if ($LASTEXITCODE -ne 0) {
    throw 'Cannot inspect tracked Java sources for the LF checkout policy.'
}
$attributes = @(& git -C $repositoryRoot check-attr eol -- @javaSources)
if ($LASTEXITCODE -ne 0 -or @($attributes | Where-Object { $_ -notmatch ': eol: lf$' }).Count -gt 0) {
    throw 'All tracked Java sources must have Git eol=lf, matching Spotless and .editorconfig.'
}

foreach ($relativePath in $documents) {
    $absolutePath = Join-Path $repositoryRoot $relativePath
    $content = [System.IO.File]::ReadAllText($absolutePath)
    if ([string]::IsNullOrWhiteSpace($content)) {
        throw "Document is empty: $relativePath"
    }
    $fences = [regex]::Matches($content, '(?m)^```[^\r\n]*$')
    if ($fences.Count % 2 -ne 0) {
        throw "Unbalanced Markdown fences: $relativePath"
    }
    $links = [regex]::Matches($content, '\[[^\]\r\n]+\]\(([^\s)]+)\)')
    foreach ($link in $links) {
        $target = $link.Groups[1].Value
        if ($target -match '^[a-zA-Z][a-zA-Z0-9+.-]*:' -or $target.StartsWith('#')) {
            continue
        }
        $localPath = [uri]::UnescapeDataString(($target -split '#', 2)[0])
        $resolvedTarget = Join-Path (Split-Path -Parent $absolutePath) $localPath
        if (-not (Test-Path -LiteralPath $resolvedTarget -PathType Leaf)) {
            throw "Broken local link in ${relativePath}: $target"
        }
    }
}

$xmlSettings = [System.Xml.XmlReaderSettings]::new()
$xmlSettings.DtdProcessing = [System.Xml.DtdProcessing]::Ignore
$xmlSettings.XmlResolver = $null
$xmlPath = Join-Path $repositoryRoot 'checkstyle/checkstyle.xml'
$xmlReader = [System.Xml.XmlReader]::Create($xmlPath, $xmlSettings)
try {
    while ($xmlReader.Read()) {
    }
} finally {
    $xmlReader.Dispose()
}

Write-Output 'Specification files, local links, Markdown fences, and Checkstyle XML are valid.'
Write-Output 'This check does not execute Java style checks, compilation, or MIDI tests.'
