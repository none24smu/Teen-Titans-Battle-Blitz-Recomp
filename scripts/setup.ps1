# Downloads the pinned decompilers into tools\:
#   FFDec          (Flash .swf)          tools\ffdec\
#   ProjectorRays  (Director/Shockwave)  tools\projectorrays\
. (Join-Path $PSScriptRoot '_common.ps1')

New-Item -ItemType Directory -Force $ToolsDir | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-Verified([string]$Url, [string]$Dest, [string]$Sha256) {
    Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing
    $actual = (Get-FileHash -Algorithm SHA256 $Dest).Hash.ToLower()
    if ($actual -ne $Sha256) {
        Remove-Item $Dest
        throw "Checksum mismatch for $Dest (got $actual)"
    }
}

if (Test-Path $FfdecJar) {
    Write-Host "FFDec already installed at $FfdecJar"
} else {
    if (-not (Get-Command $Java -ErrorAction SilentlyContinue)) { throw 'Java 11+ is required (set JAVA_HOME or put java on PATH).' }
    $zip = Join-Path $ToolsDir "ffdec_$FfdecVersion.zip"
    Write-Host "Downloading FFDec $FfdecVersion..."
    Get-Verified $FfdecUrl $zip $FfdecSha256
    $dest = Join-Path $ToolsDir 'ffdec'
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    Expand-Archive -Path $zip -DestinationPath $dest
    Remove-Item $zip
    Write-Host "FFDec installed at $FfdecJar"
}

if (Test-Path $PrExe) {
    Write-Host "ProjectorRays found at $PrExe"
} else {
    Write-Host "Downloading ProjectorRays $PrVersion..."
    New-Item -ItemType Directory -Force (Split-Path $PrExe) | Out-Null
    Get-Verified $PrUrl $PrExe $PrSha256
    Write-Host "ProjectorRays installed at $PrExe"
}
