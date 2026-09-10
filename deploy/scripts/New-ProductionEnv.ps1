param(
    [string]$SourceEnv = (Join-Path $PSScriptRoot "..\..\.env"),
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\backups\.env.production-transfer"),
    [string]$PublicHost = "search.5-42-117-227.sslip.io",
    [string]$AdminEmail = "solopenko2005@yandex.ru"
)

$ErrorActionPreference = "Stop"

function Read-DotEnv([string]$Path) {
    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*#' -or [string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $pair = $line -split '=', 2
        if ($pair.Count -eq 2) {
            $values[$pair[0].Trim()] = $pair[1]
        }
    }
    return $values
}

function New-HexSecret([int]$Bytes = 32) {
    return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes($Bytes)).ToLowerInvariant()
}

function Format-ComposeValue([string]$Value) {
    if ($null -eq $Value -or $Value.Contains("`r") -or $Value.Contains("`n")) {
        throw "Environment values must be non-null, single-line strings."
    }
    $escaped = $Value.Replace('\', '\\').Replace('"', '\"').Replace('$', '$$')
    return '"' + $escaped + '"'
}

if (-not (Test-Path -LiteralPath $SourceEnv)) {
    throw "Source .env file was not found."
}

$source = Read-DotEnv $SourceEnv
foreach ($required in @('MAIL_HOST', 'MAIL_PORT', 'MAIL_USERNAME', 'MAIL_PASSWORD')) {
    if (-not $source.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($source[$required])) {
        throw "Required setting $required is missing from the source .env file."
    }
}

$lines = @(
    "APP_HOST=$PublicHost"
    "APP_PUBLIC_URL=https://$PublicHost"
    "POSTGRES_DB=search_engine"
    "POSTGRES_USER=search_app"
    "POSTGRES_PASSWORD=$(New-HexSecret)"
    "REDIS_PASSWORD=$(New-HexSecret)"
    "AUTH_JWT_SECRET=$(New-HexSecret 48)"
    "BOOTSTRAP_ADMIN_EMAILS=$AdminEmail"
    "MAIL_HOST=$(Format-ComposeValue $source['MAIL_HOST'])"
    "MAIL_PORT=$(Format-ComposeValue $source['MAIL_PORT'])"
    "MAIL_USERNAME=$(Format-ComposeValue $source['MAIL_USERNAME'])"
    "MAIL_PASSWORD=$(Format-ComposeValue $source['MAIL_PASSWORD'])"
    "MAIL_TEST_CONNECTION=true"
    "LLM_PROVIDER=LM Studio"
    "LLM_BASE_URL=http://host.docker.internal:1235/v1"
    "LLM_API_KEY=lm-studio"
    "LLM_MODEL=local-qwen3-4b"
    "LLM_INFERENCE_PORT=1235"
    "LLM_CPU_THREADS=8"
    "LLM_DRAFT_CPU_THREADS=2"
    "LLM_CONTEXT_SIZE=4096"
    "LLM_SPECULATIVE_DECODING=false"
    "EMBEDDING_BASE_URL=http://host.docker.internal:1234/v1"
    "EMBEDDING_API_KEY=lm-studio"
    "EMBEDDING_MODEL=text-embedding-nomic-embed-text-v1.5"
    "OPENAI_TIMEOUT_SECONDS=120"
    "OPENAI_MAX_OUTPUT_TOKENS=128"
    "OPENAI_BACKGROUND_TIMEOUT_SECONDS=35"
    "OPENAI_BACKGROUND_MAX_OUTPUT_TOKENS=320"
    "ASSISTANT_RAG_MAX_DOCUMENTS=6"
    "ASSISTANT_RAG_MAX_CHARS_PER_DOCUMENT=2000"
    "ASSISTANT_RAG_MAX_INPUT_CHARS=14000"
    "ASSISTANT_RAG_LOCAL_CONTEXT_CHARS=720"
    "SEARCH_JAVA_OPTIONS=-Xms512m -Xmx2g -XX:+UseG1GC"
    "AUTH_JAVA_OPTIONS=-Xms128m -Xmx512m"
    "EMAIL_JAVA_OPTIONS=-Xms64m -Xmx256m"
    "INDEXING_SITE_PARALLELISM=4"
    "INDEXING_CRAWL_PARALLELISM=8"
    "INDEXING_MAX_PAGES_PER_SITE=1000"
    "ASSISTANT_RATE_LIMIT_PER_MINUTE=20"
    "SCOPUS_API_KEY="
)

$parent = Split-Path -Parent $OutputPath
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
[System.IO.File]::WriteAllLines($OutputPath, $lines, [Text.UTF8Encoding]::new($false))

Write-Output "Production environment file created without printing secret values."
