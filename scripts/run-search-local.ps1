param(
    [string]$SearchRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$env:AUTH_SERVICE_ENABLED = 'true'
$env:AUTH_SERVICE_URL = 'http://localhost:5555'

if (-not $env:JAVA_HOME) {
    $ideaJava = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\jbr'
    if (Test-Path -LiteralPath $ideaJava) {
        $env:JAVA_HOME = $ideaJava
    }
}

$maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd'
$module = Join-Path $SearchRoot 'Searchengine_1'
Set-Location -LiteralPath $module

if (Test-Path -LiteralPath $maven) {
    & $maven -q -DskipTests spring-boot:run
} else {
    & 'mvn' -q -DskipTests spring-boot:run
}
exit $LASTEXITCODE
