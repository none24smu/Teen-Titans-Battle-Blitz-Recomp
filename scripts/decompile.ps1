# Decompiles everything in original\ into src\. See scripts/decompile.sh for the layout.
# Usage: scripts\decompile.ps1 [name ...]   (default: everything in original\)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
. (Join-Path $PSScriptRoot '_common.ps1')

function Assert-Fresh([string]$Name) {
    if (Test-Path (Join-Path $SrcDir $Name)) { throw "src\$Name already exists; delete it first to re-decompile (it may hold your edits)." }
}

function Export-Swf([string]$Name) {
    $swf = Join-Path $OriginalDir "$Name.swf"
    $out = Join-Path $SrcDir $Name
    Write-Host "== $Name (Flash)"
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
}

function Export-Director([string]$Name) {
    $in  = Join-Path $OriginalDir $Name
    $out = Join-Path $SrcDir "$Name\movies"
    Write-Host "== $Name (Director)"
    New-Item -ItemType Directory -Force $out | Out-Null
    foreach ($f in Get-ChildItem -File $in | Where-Object { $DirectorExts -contains $_.Extension.ToLower() }) {
        Write-Host "  $($f.Name)"
        Invoke-ProjectorRays decompile $f.FullName -o $out --dump-scripts | Out-Null
        # Already-unprotected inputs come out as <base>_decompiled.<ext>.
        Get-ChildItem -File $out -Filter "$($f.BaseName)_decompiled.*" | ForEach-Object {
            Move-Item -Force $_.FullName (Join-Path $out ($f.BaseName + $_.Extension))
        }
    }

    # Art, sound, palettes, text, labels and member metadata in open formats.
    $assets = Join-Path $SrcDir "$Name\assets"
    foreach ($f in Get-ChildItem -File $out | Where-Object { @('.dir', '.cst') -contains $_.Extension.ToLower() }) {
        Write-Host "  assets: $($f.BaseName)"
        Invoke-DirectorExtract $f.FullName (Join-Path $assets $f.BaseName)
    }
}

$did = $false
foreach ($name in @(Get-SwfNames $Names)) {
    if (-not (Test-Path (Join-Path $OriginalDir "$name.swf"))) { continue }
    Assert-Fresh $name
    Export-Swf $name
    $did = $true
}
foreach ($name in @(Get-DirectorNames $Names)) {
    if (-not (Test-Path (Join-Path $OriginalDir $name) -PathType Container)) { continue }
    Assert-Fresh $name
    Export-Director $name
    $did = $true
}
if (-not $did) { throw 'Nothing to decompile in original\.' }
Write-Host 'Done.'
