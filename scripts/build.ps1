# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    python (Join-Path $PSScriptRoot 'test_core.py')
    if ($LASTEXITCODE -ne 0) { throw 'Core checks failed. Packaging stopped.' }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw 'Maven 3.9.11 or newer is required. Install Apache Maven and add its bin folder to PATH.'
    }
    mvn --batch-mode --no-transfer-progress verify
    if ($LASTEXITCODE -ne 0) { throw 'Plug-in build failed. See the Maven output above.' }
    python (Join-Path $PSScriptRoot 'verify_site.py')
    if ($LASTEXITCODE -ne 0) { throw 'Update-site verification failed.' }
    Write-Host 'Local Eclipse update site: releng/repository/target/repository'
} finally { Pop-Location }
