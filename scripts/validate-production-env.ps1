[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$required = @(
    "MYSQL_ROOT_PASSWORD",
    "SECKILL_JWT_SECRET",
    "ORDER_SERVICE_SECRET",
    "INVENTORY_SERVICE_SECRET",
    "INTERNAL_ADMIN_SECRET",
    "CANARY_CONTROL_TOKEN",
    "MOCK_CHANNEL_SECRET"
)

$knownDemoValues = @(
    "seckill-root",
    "seckill-engine-dev-secret-change-me",
    "dev-order-secret",
    "dev-inventory-secret",
    "dev-admin-secret",
    "dev-canary-token",
    "mock-channel-secret"
)

$errors = New-Object System.Collections.Generic.List[string]
foreach ($name in $required) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        $errors.Add("$name is missing")
        continue
    }
    if ($knownDemoValues -contains $value) {
        $errors.Add("$name contains a demo value")
    }
    if ($value.Length -lt 32) {
        $errors.Add("$name must contain at least 32 characters")
    }
}

if ($errors.Count -gt 0) {
    Write-Error ("Production secret validation failed:`n - " + ($errors -join "`n - "))
    exit 1
}

Write-Output "Production secret validation passed. Values were not printed."
