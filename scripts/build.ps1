# Rebuilds everything in src\ into build\. See scripts/build.sh for details.
# Usage: scripts\build.ps1 [name ...]   (default: everything in original\)
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Names)
. (Join-Path $PSScriptRoot '_common.ps1')

# Byte patches for running Director movies in dirplayer-rs (see scripts/build.sh for why).
$DirectorPatches = @(
    @{ Name = 'tell-go-1'; Search = '1c8100014201a300911d'; Replace = '1c9300084201a300911d'; Count = 2 }
)

function ConvertFrom-Hex([string]$Hex) {
    $bytes = New-Object byte[] ($Hex.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [Convert]::ToByte($Hex.Substring($i * 2, 2), 16) }
    return ,$bytes
}

# Replaces every occurrence of $Search with $Replace (same length) in $Data; returns the count.
function Set-BytePatch([byte[]]$Data, [byte[]]$Search, [byte[]]$Replace) {
    $count = 0
    $first = $Search[0]
    $i = [Array]::IndexOf($Data, $first)
    while ($i -ge 0 -and $i -le $Data.Length - $Search.Length) {
        $match = $true
        for ($j = 1; $j -lt $Search.Length; $j++) {
            if ($Data[$i + $j] -ne $Search[$j]) { $match = $false; break }
        }
        if ($match) {
            [Array]::Copy($Replace, 0, $Data, $i, $Replace.Length)
            $count++
            $i += $Search.Length
        } else {
            $i++
        }
        if ($i -ge $Data.Length) { break }
        $i = [Array]::IndexOf($Data, $first, $i)
    }
    return $count
}

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
    Get-ChildItem -File $movies -Filter *.cst | Copy-Item -Destination $out
    foreach ($f in Get-ChildItem -File $movies -Filter *.dir) {
        $data = [IO.File]::ReadAllBytes($f.FullName)
        foreach ($p in $DirectorPatches) {
            $n = Set-BytePatch $data (ConvertFrom-Hex $p.Search) (ConvertFrom-Hex $p.Replace)
            if ($n -gt 0) {
                Write-Host "  $($f.Name): patch $($p.Name) applied $n time(s)"
                if ($n -ne $p.Count -and $f.Name -eq 'game.dir') { Write-Warning "expected $($p.Count) for game.dir (different game version?)" }
            }
        }
        [IO.File]::WriteAllBytes((Join-Path $out $f.Name), $data)
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
