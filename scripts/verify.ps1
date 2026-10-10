# Renders every frame of original\<name>.swf and build\<name>.swf and checks they match,
# and checks the rebuilt scripts decompile to the same source as src\<name>\scripts.
# Usage: scripts\verify.ps1 [name ...]   (default: every SWF in original\)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
. (Join-Path $PSScriptRoot '_common.ps1')

# Relative path -> hash for every file under a directory.
function Get-TreeHashes([string]$Dir) {
    $map = @{}
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

$failed = $false
foreach ($name in Get-SwfNames $Names) {
    $built = Join-Path $BuildDir "$name.swf"
    if (-not (Test-Path $built)) { throw "Missing $built; run scripts\build.ps1 first." }

    Write-Host "== $name"
    $work = Join-Path $BuildDir ".verify-$name"
    if (Test-Path $work) { Remove-Item -Recurse -Force $work }
    New-Item -ItemType Directory -Force $work | Out-Null

    Invoke-Ffdec -export frame (Join-Path $work 'orig') (Join-Path $OriginalDir "$name.swf") | Out-Null
    Invoke-Ffdec -export frame (Join-Path $work 'built') $built | Out-Null
    $frameDiffs = Compare-Trees (Join-Path $work 'orig') (Join-Path $work 'built')
    $frames = @(Get-ChildItem -Recurse -File (Join-Path $work 'orig')).Count
    if ($frameDiffs.Count -eq 0) {
        Write-Host "  frames:  OK ($frames frames render identically)"
    } else {
        Write-Host "  frames:  DIFFERENT -- see $work"
        $frameDiffs | Select-Object -First 20 | ForEach-Object { Write-Host "    $_" }
        $failed = $true
    }

    $srcScripts = Join-Path $SrcDir "$name\scripts"
    Invoke-Ffdec -export script (Join-Path $work 'scripts') $built | Out-Null
    $scriptDiffs = @()
    if (Test-Path $srcScripts) { $scriptDiffs = Compare-Trees $srcScripts (Join-Path $work 'scripts\scripts') }
    if ($scriptDiffs.Count -eq 0) {
        Write-Host "  scripts: OK (rebuilt bytecode decompiles to src\$name\scripts)"
    } else {
        Write-Host '  scripts: DIFFERENT'
        $scriptDiffs | Select-Object -First 20 | ForEach-Object { Write-Host "    $_" }
        $failed = $true
    }
}

if ($failed) {
    Write-Host 'Rebuild differs from the original. Expected if you edited src\.'
    exit 1
}
Write-Host 'All checks passed.'
