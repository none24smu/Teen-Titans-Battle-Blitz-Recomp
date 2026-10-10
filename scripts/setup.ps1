# Downloads the pinned JPEXS Free Flash Decompiler (FFDec) into tools\ffdec.
. (Join-Path $PSScriptRoot '_common.ps1')

if (Test-Path $FfdecJar) {
    Write-Host "FFDec already installed at $FfdecJar"
    return
}

if (-not (Get-Command $Java -ErrorAction SilentlyContinue)) { throw 'Java 11+ is required (set JAVA_HOME or put java on PATH).' }

New-Item -ItemType Directory -Force $ToolsDir | Out-Null
$zip = Join-Path $ToolsDir "ffdec_$FfdecVersion.zip"
Write-Host "Downloading FFDec $FfdecVersion..."
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest -Uri $FfdecUrl -OutFile $zip -UseBasicParsing

$actual = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
if ($actual -ne $FfdecSha256) {
    Remove-Item $zip
    throw "Checksum mismatch for $zip (got $actual)"
}

$dest = Join-Path $ToolsDir 'ffdec'
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
Expand-Archive -Path $zip -DestinationPath $dest
Remove-Item $zip
Write-Host "FFDec installed at $FfdecJar"
