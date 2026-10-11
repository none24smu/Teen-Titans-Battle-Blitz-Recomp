# Rebuilds everything in src\ into build\. See scripts/build.sh for details.
# Usage: scripts\build.ps1 [name ...]   (default: everything in original\)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
. (Join-Path $PSScriptRoot '_common.ps1')

function Build-Swf([string]$Name) {
    $src = Join-Path $SrcDir $Name
    $xml = Join-Path $src 'swf.xml'
    if (-not (Test-Path $xml)) { throw "Missing $xml; run scripts\decompile.ps1 first." }
    Write-Host "== $Name (Flash)"
    New-Item -ItemType Directory -Force $BuildDir | Out-Null
    $tmp = Join-Path $BuildDir ".$Name.noscripts.swf"
    Invoke-Ffdec -xml2swf $xml $tmp | Out-Null
    # importScript recompiles every script file present and leaves the rest of the SWF untouched.
    Invoke-Ffdec -importScript $tmp (Join-Path $BuildDir "$Name.swf") (Join-Path $src 'scripts')
    Remove-Item $tmp
    Write-Host "  built -> build\$Name.swf"
}

function Build-Director([string]$Name) {
    $movies = Join-Path $SrcDir "$Name\movies"
    $out = Join-Path $BuildDir "$Name\web"
    if (-not (Test-Path $movies)) { throw "Missing $movies; run scripts\decompile.ps1 first." }
    Write-Host "== $Name (Director)"
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    New-Item -ItemType Directory -Force $out | Out-Null
    foreach ($f in Get-ChildItem -File $movies | Where-Object { @('.dir', '.cst') -contains $_.Extension.ToLower() }) {
        $scripts = Join-Path $movies $f.BaseName
        if (Test-Path $scripts -PathType Container) {
            # compile the Lingo source (with any edits) back into the movie
            if ((Invoke-DirectorJava LingoBuild @($f.FullName, $scripts, (Join-Path $out $f.Name))) -ne 0) {
                throw "Compiling the Lingo into $($f.Name) failed"
            }
        } else {
            Copy-Item $f.FullName (Join-Path $out $f.Name)
        }
    }
    Write-Host "  built -> build\$Name\web\"
}

$did = $false
foreach ($name in @(Get-SwfNames $Names)) {
    if (-not (Test-Path (Join-Path $OriginalDir "$name.swf"))) { continue }
    Build-Swf $name
    $did = $true
}
foreach ($name in @(Get-DirectorNames $Names)) {
    if (-not (Test-Path (Join-Path $OriginalDir $name) -PathType Container)) { continue }
    Build-Director $name
    $did = $true
}
if (-not $did) { throw 'Nothing to build in original\.' }
