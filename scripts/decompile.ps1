# Decompiles original\<name>.swf into src\<name>\. See scripts/decompile.sh for the layout.
# Usage: scripts\decompile.ps1 [name ...]   (default: every SWF in original\)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
. (Join-Path $PSScriptRoot '_common.ps1')

foreach ($name in Get-SwfNames $Names) {
    $swf = Join-Path $OriginalDir "$name.swf"
    $out = Join-Path $SrcDir $name
    if (-not (Test-Path $swf)) { throw "Missing $swf" }

    Write-Host "== $name"
    if (Test-Path $out) { throw "src\$name already exists; delete it first to re-decompile (it may hold your edits)." }
    New-Item -ItemType Directory -Force $out | Out-Null

    Write-Host '  swf.xml'
    Invoke-Ffdec -swf2xml $swf (Join-Path $out 'swf.xml') | Out-Null

    Write-Host '  scripts + assets'
    Invoke-Ffdec -export all (Join-Path $out 'assets') $swf | Out-Null
    $exported = Join-Path $out 'assets\scripts'
    if (Test-Path $exported) {
        Move-Item $exported (Join-Path $out 'scripts')
    } else {
        New-Item -ItemType Directory -Force (Join-Path $out 'scripts') | Out-Null
    }

    Write-Host '  pcode'
    Invoke-Ffdec -format script:pcode -export script (Join-Path $out 'pcode') $swf | Out-Null

    Write-Host '  xfl'
    Invoke-Ffdec -export xfl (Join-Path $out 'xfl') $swf | Out-Null

    Write-Host "  done -> src\$name"
}
