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
        if (Test-Path (Join-Path $work $f.BaseName)) {
            Invoke-DirectorJava LingoFixups @($f.FullName, (Join-Path $work $f.BaseName)) (Join-Path $work "$($f.BaseName).fixups.txt") | Out-Null
        }
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

    # Every script must compile back to the movie's own bytecode (scripts\lingo\LingoCompiler.java).
    $compileBad = $false
    foreach ($f in Get-ChildItem -File $movies | Where-Object { @('.dir', '.cst') -contains $_.Extension.ToLower() }) {
        $scriptDir = Join-Path $movies $f.BaseName
        if (-not (Test-Path $scriptDir)) { continue }
        if ((Invoke-DirectorJava LingoRoundTrip @($f.FullName, $scriptDir, "-v") (Join-Path $work "$($f.BaseName).compile.txt")) -ne 0) {
            Write-Host "  $($f.BaseName): Lingo doesn't compile back to the original bytecode (see build\.verify-$Name\$($f.BaseName).compile.txt)"
            $compileBad = $true
        }
    }
    if ($compileBad) {
        $script:failed = $true
    } else {
        Write-Host "  compile: OK ($scripts scripts compile back to identical bytecode)"
    }

    # Writing the compiled scripts back must reproduce each movie exactly (scripts\lingo\LingoBuild.java).
    $buildBad = $false
    $built = 0
    foreach ($f in Get-ChildItem -File $movies | Where-Object { @('.dir', '.cst') -contains $_.Extension.ToLower() }) {
        $scriptDir = Join-Path $movies $f.BaseName
        if (-not (Test-Path $scriptDir -PathType Container)) { continue }
        $rebuilt = Join-Path $work "rebuilt-$($f.Name)"
        Invoke-DirectorJava LingoBuild @($f.FullName, $scriptDir, $rebuilt) (Join-Path $work "$($f.BaseName).build.txt") | Out-Null
        $same = (Test-Path $rebuilt) -and ((Get-FileHash $f.FullName).Hash -eq (Get-FileHash $rebuilt).Hash)
        if (-not $same) {
            Write-Host "  $($f.Name): rebuilding it from its Lingo source changes the file"
            $buildBad = $true
        }
        $built++
    }
    if ($buildBad) {
        $script:failed = $true
    } else {
        Write-Host "  build:   OK ($built movies/casts rebuild byte-identical from source)"
    }

    $assets = Join-Path $SrcDir "$Name\assets"
    if (Test-Path $assets) {
        $errors = @(Get-ChildItem -Recurse -File -Filter cast.json $assets | Where-Object { (Get-Content -Raw $_.FullName) -match '"error"' }).Count
        $pngs = @(Get-ChildItem -Recurse -File -Filter *.png $assets).Count
        $wavs = @(Get-ChildItem -Recurse -File -Filter *.wav $assets).Count
        if ($errors -eq 0) {
            Write-Host "  assets:  OK ($pngs images, $wavs sounds exported, no member failed)"
        } else {
            Write-Host "  assets:  $errors cast(s) have members that failed to export -- see `"error`" in their cast.json"
            $script:failed = $true
        }
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
