# Rebuilds build\<name>.swf from src\<name>\swf.xml plus the ActionScript in src\<name>\scripts.
# Usage: scripts\build.ps1 [name ...]   (default: every SWF in original\)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
. (Join-Path $PSScriptRoot '_common.ps1')

New-Item -ItemType Directory -Force $BuildDir | Out-Null
foreach ($name in Get-SwfNames $Names) {
    $src = Join-Path $SrcDir $name
    $xml = Join-Path $src 'swf.xml'
    if (-not (Test-Path $xml)) { throw "Missing $xml; run scripts\decompile.ps1 first." }

    Write-Host "== $name"
    $tmp = Join-Path $BuildDir ".$name.noscripts.swf"
    Invoke-Ffdec -xml2swf $xml $tmp | Out-Null
    # importScript recompiles every script file present and leaves the rest of the SWF untouched.
    Invoke-Ffdec -importScript $tmp (Join-Path $BuildDir "$name.swf") (Join-Path $src 'scripts')
    Remove-Item $tmp
    Write-Host "  built -> build\$name.swf"
}
