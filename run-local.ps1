$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Path $logs -Force | Out-Null

if (-not (Test-Path -LiteralPath (Join-Path $root '.env'))) {
    throw 'Create .env from .env.example and configure PostgreSQL.'
}

function Get-LocalEnvValue {
    param([string]$Name)

    $envLine = Get-Content -LiteralPath (Join-Path $root '.env') -Encoding UTF8 |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -Last 1
    if (-not $envLine) {
        return $null
    }
    return (($envLine -split '=', 2)[1]).Trim().Trim('"').Trim("'")
}

$llmProvider = Get-LocalEnvValue 'LLM_PROVIDER'
if ($llmProvider -match '(?i)lm\s*studio') {
    $lmsExecutable = Join-Path $env:USERPROFILE '.lmstudio\bin\lms.exe'
    if (-not (Test-Path -LiteralPath $lmsExecutable)) {
        Write-Warning 'LM Studio CLI is not installed. The assistant will use its local fallback mode.'
    } else {
        if (-not (Get-NetTCPConnection -State Listen -LocalPort 1234 -ErrorAction SilentlyContinue)) {
            Write-Host 'Starting LM Studio API...'
            & $lmsExecutable server start --port 1234 | Out-Null
        }

        $llmDeadline = (Get-Date).AddSeconds(30)
        do {
            Start-Sleep -Milliseconds 500
            $llmReady = Get-NetTCPConnection -State Listen -LocalPort 1234 -ErrorAction SilentlyContinue
        } while (-not $llmReady -and (Get-Date) -lt $llmDeadline)

        if (-not $llmReady) {
            Write-Warning 'LM Studio API failed to start on port 1234. The assistant will use its local fallback mode.'
        } else {
            $llmModel = Get-LocalEnvValue 'OPENAI_MODEL'
            if ([string]::IsNullOrWhiteSpace($llmModel)) {
                $llmModel = 'local-qwen3-8b'
            }
            $previousErrorActionPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $loadedModels = (& $lmsExecutable ps 2>&1 | Out-String)
            $ErrorActionPreference = $previousErrorActionPreference
            if ($loadedModels -notmatch [regex]::Escape($llmModel)) {
                Write-Host "Loading the LM Studio model $llmModel..."
                & $lmsExecutable load 'qwen/qwen3-8b' --identifier $llmModel --parallel 2 --yes | Out-Null
            }
        }
    }
}

$mailUsername = Get-LocalEnvValue 'MAIL_USERNAME'
$mailPassword = Get-LocalEnvValue 'MAIL_PASSWORD'
if ($mailUsername -and $mailPassword -and -not (Get-NetTCPConnection -State Listen -LocalPort 8771 -ErrorAction SilentlyContinue)) {
    Start-Process powershell.exe `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'scripts\run-email-local.ps1')) `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'email-sender.out.log') `
        -RedirectStandardError (Join-Path $logs 'email-sender.err.log') | Out-Null
} elseif (-not $mailUsername -or -not $mailPassword) {
    Write-Warning 'MAIL_USERNAME/MAIL_PASSWORD are not configured. Password recovery is ready but cannot send email yet.'
}

if ($mailUsername -and $mailPassword) {
    $normalizedMailPassword = $mailPassword -replace '\s', ''
    if ($mailUsername -match '(?i)@gmail\.com$' -and $normalizedMailPassword.Length -ne 16) {
        Write-Warning 'For Gmail, MAIL_PASSWORD must be a 16-character Google App Password, not the regular account password.'
    }
    $mailDeadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Milliseconds 500
        $mailReady = Get-NetTCPConnection -State Listen -LocalPort 8771 -ErrorAction SilentlyContinue
    } while (-not $mailReady -and (Get-Date) -lt $mailDeadline)
    if ($mailReady) {
        try {
            $smtpHealth = Invoke-RestMethod -Uri 'http://localhost:8771/api/v1/email/health' -TimeoutSec 15
            if (-not $smtpHealth.result) { throw 'SMTP is unavailable' }
            Write-Host 'SMTP authentication: ready'
        } catch {
            Write-Warning 'Email service started, but SMTP authentication failed. Check MAIL_USERNAME and the provider app password.'
        }
    } else {
        Write-Warning "Email sender failed to start. Check $logs\email-sender.out.log"
    }
}

if (-not (Get-NetTCPConnection -State Listen -LocalPort 5555 -ErrorAction SilentlyContinue)) {
    Start-Process powershell.exe `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'scripts\run-auth-local.ps1')) `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'authorization.out.log') `
        -RedirectStandardError (Join-Path $logs 'authorization.err.log') | Out-Null
}

$deadline = (Get-Date).AddSeconds(90)
do {
    Start-Sleep -Milliseconds 750
    $authReady = Get-NetTCPConnection -State Listen -LocalPort 5555 -ErrorAction SilentlyContinue
} while (-not $authReady -and (Get-Date) -lt $deadline)

if (-not $authReady) {
    throw "Authorization service failed to start. Check $logs\authorization.out.log"
}

if (-not (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)) {
    Start-Process powershell.exe `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'scripts\run-search-local.ps1')) `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'search-engine.out.log') `
        -RedirectStandardError (Join-Path $logs 'search-engine.err.log') | Out-Null
}

Write-Host 'Services are starting:'
Write-Host '  Authorization: http://localhost:5555'
Write-Host '  Registration:  http://localhost:8080/register'
Write-Host '  Login:         http://localhost:8080/login'
if ($mailUsername -and $mailPassword) {
    Write-Host '  Email sender:  http://localhost:8771'
}
if ($llmProvider -match '(?i)lm\s*studio') {
    Write-Host '  Local LLM API: http://localhost:1234'
}
