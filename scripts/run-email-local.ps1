param(
    [string]$SearchRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$EmailProject = (Join-Path (Split-Path -Parent $PSScriptRoot) 'emailsender')
)

$ErrorActionPreference = 'Stop'

function Read-DotEnv([string]$Path) {
    $result = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
            $parts = $line.Split('=', 2)
            $result[$parts[0].Trim()] = $parts[1].Trim().Trim('"').Trim("'")
        }
    }
    return $result
}

$settings = Read-DotEnv (Join-Path $SearchRoot '.env')
$env:MAIL_HOST = if ($settings['MAIL_HOST']) { $settings['MAIL_HOST'] } else { 'smtp.gmail.com' }
$env:MAIL_PORT = if ($settings['MAIL_PORT']) { $settings['MAIL_PORT'] } else { '587' }
$env:MAIL_USERNAME = $settings['MAIL_USERNAME']
$env:MAIL_PASSWORD = $settings['MAIL_PASSWORD']
$env:SENDER_PORT = '8771'

if (-not $env:JAVA_HOME) {
    $ideaJava = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\jbr'
    if (Test-Path -LiteralPath $ideaJava) { $env:JAVA_HOME = $ideaJava }
}

Set-Location -LiteralPath $EmailProject
$maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd'
if (Test-Path -LiteralPath $maven) {
    & $maven -q spring-boot:run
} else {
    & 'mvn' -q spring-boot:run
}
exit $LASTEXITCODE
