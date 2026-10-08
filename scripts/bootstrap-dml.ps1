[CmdletBinding()]
param([string]$PublishedJar)
$ErrorActionPreference = 'Stop'
$issue4Root = Split-Path $PSScriptRoot -Parent
$issue4Expected = 'fd5156eed8993ca0b9255012abd47402e833970d047258b1115957e1e7c26d7f'
$issue4Output = Join-Path $issue4Root 'libs/onnxruntime-dml.jar'
if ((Test-Path -LiteralPath $issue4Output) -and (Get-FileHash -LiteralPath $issue4Output -Algorithm SHA256).Hash.ToLower() -eq $issue4Expected) { return }
if (!$PublishedJar) {
    $PublishedJar = Join-Path $issue4Root 'build/inputs/td-v1.3.0-windows.jar'
    New-Item -ItemType Directory -Force (Split-Path $PublishedJar) | Out-Null
    Invoke-WebRequest 'https://github.com/Alesrr/terra-diffusion/releases/download/TD_v1.3.0/NeoForge-terra_diffusion-1.3.0-windows%2B1.21.1.jar' -OutFile $PublishedJar
}
$issue4PublishedHash = '1ef0cecd0f65984ed86654dc185e9c0021223344b1c2fa36e7cb5c1ff32859fd5f8213d459963245e365cc0b09ccecf1380e257a007e6293a54bc6222d3bfc76'
if ((Get-FileHash -LiteralPath $PublishedJar -Algorithm SHA512).Hash.ToLower() -ne $issue4PublishedHash) { throw 'Original TD Windows artifact SHA-512 mismatch' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$issue4Archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $PublishedJar))
try {
    $issue4Entry = $issue4Archive.GetEntry('META-INF/jarjar/onnxruntime-dml-1.0.jar')
    if (!$issue4Entry) { throw 'Pinned DirectML input missing from original artifact' }
    New-Item -ItemType Directory -Force (Split-Path $issue4Output) | Out-Null
    [IO.Compression.ZipFileExtensions]::ExtractToFile($issue4Entry, $issue4Output, $true)
} finally { $issue4Archive.Dispose() }
if ((Get-FileHash -LiteralPath $issue4Output -Algorithm SHA256).Hash.ToLower() -ne $issue4Expected) { throw 'Extracted DirectML input SHA-256 mismatch' }
Write-Host 'Verified pinned original DirectML build input'
