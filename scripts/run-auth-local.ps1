param(
    [string]$SearchRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$AuthProject = (Join-Path (Split-Path -Parent $PSScriptRoot) 'authorization')
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
foreach ($required in 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD') {
    if (-not $settings[$required]) {
        throw "Required setting $required is missing from .env"
    }
}

$jwtBytes = New-Object byte[] 48
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($jwtBytes)
$random.Dispose()

$env:AUTH_DB_URL = $settings['DB_URL']
$env:AUTH_DB_USERNAME = $settings['DB_USERNAME']
$env:AUTH_DB_PASSWORD = $settings['DB_PASSWORD']
$env:AUTH_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
$env:AUTH_SERVER_PORT = '5555'
if ($settings['REGISTRATION_DEFAULT_ROLE']) {
    $env:REGISTRATION_DEFAULT_ROLE = $settings['REGISTRATION_DEFAULT_ROLE']
}
if ($settings['BOOTSTRAP_ADMIN_EMAILS']) {
    $env:BOOTSTRAP_ADMIN_EMAILS = $settings['BOOTSTRAP_ADMIN_EMAILS']
}

if (-not $env:JAVA_HOME) {
    $ideaJava = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\jbr'
    if (Test-Path -LiteralPath $ideaJava) {
        $env:JAVA_HOME = $ideaJava
    }
}

Set-Location -LiteralPath $AuthProject
$maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd'
if (Test-Path -LiteralPath $maven) {
    & $maven -q spring-boot:run '-Dspring-boot.run.profiles=search'
} else {
    & 'mvn' -q spring-boot:run '-Dspring-boot.run.profiles=search'
}
exit $LASTEXITCODE
