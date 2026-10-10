# Checks the decompiled output against the originals. See scripts/verify.sh for details.
# Usage: scripts\verify.ps1 [name ...]   (default: everything in original\)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
. (Join-Path $PSScriptRoot '_common.ps1')

# Relative path -> hash for every file under a directory.
function Get-TreeHashes([string]$Dir) {
    $map = @{}
    if (-not (Test-Path $Dir)) { return $map }
    $base = (Resolve-Path $Dir).Path.TrimEnd('\') + '\'
    Get-ChildItem -Recurse -File $Dir | ForEach-Object {
        $map[$_.FullName.Substring($base.Length)] = (Get-FileHash -Algorithm MD5 $_.FullName).Hash
    }
    return $map
}

function Compare-Trees([string]$A, [string]$B) {
    $ha = Get-TreeHashes $A
    $hb = Get-TreeHashes $B
    $diffs = @()
    foreach ($k in ($ha.Keys + $hb.Keys | Sort-Object -Unique)) {
        if ($ha[$k] -ne $hb[$k]) { $diffs += $k }
    }
    return ,$diffs
}

function New-WorkDir([string]$Name) {
    $work = Join-Path $BuildDir ".verify-$Name"
    if (Test-Path $work) { Remove-Item -Recurse -Force $work }
    New-Item -ItemType Directory -Force $work | Out-Null
    return $work
}

$script:failed = $false

function Test-Swf([string]$Name) {
    $built = Join-Path $BuildDir "$Name.swf"
    if (-not (Test-Path $built)) { throw "Missing $built; run scripts\build.ps1 first." }

    Write-Host "== $Name (Flash)"
    $work = New-WorkDir $Name

    Invoke-Ffdec -export frame (Join-Path $work 'orig') (Join-Path $OriginalDir "$Name.swf") | Out-Null
    Invoke-Ffdec -export frame (Join-Path $work 'built') $built | Out-Null
    $frameDiffs = Compare-Trees (Join-Path $work 'orig') (Join-Path $work 'built')
    $frames = @(Get-ChildItem -Recurse -File (Join-Path $work 'orig')).Count
    if ($frameDiffs.Count -eq 0) {
        Write-Host "  frames:  OK ($frames frames render identically)"
    } else {
        Write-Host "  frames:  DIFFERENT -- see $work"
        $frameDiffs | Select-Object -First 20 | ForEach-Object { Write-Host "    $_" }
        $script:failed = $true
    }

    $srcScripts = Join-Path $SrcDir "$Name\scripts"
    Invoke-Ffdec -export script (Join-Path $work 'scripts') $built | Out-Null
    $scriptDiffs = @()
    if (Test-Path $srcScripts) { $scriptDiffs = Compare-Trees $srcScripts (Join-Path $work 'scripts\scripts') }
    if ($scriptDiffs.Count -eq 0) {
        Write-Host "  scripts: OK (rebuilt bytecode decompiles to src\$Name\scripts)"
    } else {
        Write-Host '  scripts: DIFFERENT'
        $scriptDiffs | Select-Object -First 20 | ForEach-Object { Write-Host "    $_" }
        $script:failed = $true
    }
}

function Test-Director([string]$Name) {
    $movies = Join-Path $SrcDir "$Name\movies"
    if (-not (Test-Path $movies)) { throw "Missing $movies; run scripts\decompile.ps1 first." }

    Write-Host "== $Name (Director)"
    $work = New-WorkDir $Name
    $count = 0
    $bad = $false
    foreach ($f in Get-ChildItem -File $movies | Where-Object { @('.dir', '.cst') -contains $_.Extension.ToLower() }) {
        Invoke-ProjectorRays decompile $f.FullName -o $work --dump-scripts | Out-Null
        $diffs = Compare-Trees (Join-Path $movies $f.BaseName) (Join-Path $work $f.BaseName)
        if ($diffs.Count -gt 0) {
            Write-Host "  $($f.BaseName): Lingo differs after re-decompiling"
            $bad = $true
        }
        $count++
    }
    $scripts = @(Get-ChildItem -Recurse -File -Filter *.ls $movies).Count
    if ($bad) {
        $script:failed = $true
    } else {
        Write-Host "  lingo:   OK ($count movies/casts, $scripts scripts re-decompile identically)"
    }
}

$did = $false
foreach ($name in @(Get-SwfNames $Names)) {
    if (-not (Test-Path (Join-Path $OriginalDir "$name.swf"))) { continue }
    Test-Swf $name
    $did = $true
}
foreach ($name in @(Get-DirectorNames $Names)) {
    if (-not (Test-Path (Join-Path $OriginalDir $name) -PathType Container)) { continue }
    Test-Director $name
    $did = $true
}
if (-not $did) { throw 'Nothing to verify in original\.' }

if ($script:failed) {
    Write-Host 'Output differs from the original. Expected if you edited src\.'
    exit 1
}
Write-Host 'All checks passed.'
