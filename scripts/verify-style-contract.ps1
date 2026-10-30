$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path $root 'target/style-contract'
$sourceDirectory = Join-Path $fixture 'src/main/java/fixture'
New-Item -ItemType Directory -Force $sourceDirectory | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'pom.xml') -Destination $fixture
Copy-Item -LiteralPath (Join-Path $root 'eclipse'), (Join-Path $root 'checkstyle') `
    -Destination $fixture -Recurse -Force
$source = Join-Path $sourceDirectory 'StyleProbe.java'
$content = @'
package fixture;
import java.util.Map;
public final class StyleProbe {
    @Deprecated public static String value() {
        return
            """
            unchanged\
            """;
    }
    public static void main(String[] args) { System.out.print(value()); }
}
'@
# Introduce a literal tab to exercise the maintained-source tab check.
$content = $content.Replace('    @Deprecated', "`t@Deprecated")
[IO.File]::WriteAllText($source, $content, [Text.UTF8Encoding]::new($false))
$pom = Join-Path $fixture 'pom.xml'
mvn --batch-mode --no-transfer-progress -f $pom checkstyle:check *> (Join-Path $fixture 'rejected.log')
if ($LASTEXITCODE -eq 0) { throw 'Expected representative style violations to be rejected' }
& "$env:JAVA_HOME/bin/javac.exe" -d $fixture $source
if ($LASTEXITCODE -ne 0) { throw 'Original fixture did not compile' }
$before = & "$env:JAVA_HOME/bin/java.exe" -cp $fixture fixture.StyleProbe
mvn --batch-mode --no-transfer-progress -f $pom spotless:apply checkstyle:check
if ($LASTEXITCODE -ne 0) { throw 'Automatic formatting did not produce accepted output' }
$first = [IO.File]::ReadAllBytes($source)
mvn --batch-mode --no-transfer-progress -f $pom spotless:apply spotless:check
if ($LASTEXITCODE -ne 0) { throw 'Second formatting pass failed' }
$second = [IO.File]::ReadAllBytes($source)
if ([Convert]::ToBase64String($first) -ne [Convert]::ToBase64String($second)) {
    throw 'Formatting was not stable'
}
& "$env:JAVA_HOME/bin/javac.exe" -d $fixture $source
if ($LASTEXITCODE -ne 0) { throw 'Formatted fixture did not compile' }
$after = & "$env:JAVA_HOME/bin/java.exe" -cp $fixture fixture.StyleProbe
if ($before -ne 'unchanged' -or $after -ne $before) { throw 'Formatter changed string behavior' }
'Style contract: violations rejected, automatic fixes accepted, stable second pass, value preserved.'
