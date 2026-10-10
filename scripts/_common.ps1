# Shared settings for the pipeline scripts. Dot-sourced, not executed.
$ErrorActionPreference = 'Stop'

$Root        = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$OriginalDir = Join-Path $Root 'original'
$SrcDir      = Join-Path $Root 'src'
$BuildDir    = Join-Path $Root 'build'
$ToolsDir    = Join-Path $Root 'tools'

$FfdecVersion = '26.3.0'
$FfdecUrl     = "https://github.com/jindrapetrik/jpexs-decompiler/releases/download/version$FfdecVersion/ffdec_$FfdecVersion.zip"
$FfdecSha256  = '35f4930eb7c380afe66f2117f90b006deac0631473ad7500bb39c78f68645ecd'
$FfdecJar     = Join-Path $ToolsDir 'ffdec\ffdec.jar'

# ProjectorRays (Shockwave/Director decompiler). Override with $env:PROJECTORRAYS.
$PrVersion = '0.2.0'
$PrUrl     = "https://github.com/ProjectorRays/ProjectorRays/releases/download/v$PrVersion/projectorrays-$PrVersion.exe"
$PrSha256  = 'e9814428ee503cf129b6f5cff54524177b7bdd63201a9095d8d19433535c70db'
$PrExe     = if ($env:PROJECTORRAYS) { $env:PROJECTORRAYS } else { Join-Path $ToolsDir 'projectorrays\projectorrays.exe' }

# Director file types ProjectorRays understands.
$DirectorExts = @('.dir', '.dxr', '.dcr', '.cst', '.cxt', '.cct')

# dirplayer-rs web polyfill (plays Director/Shockwave movies in the browser; platforms/web/shockwave.html).
$DirplayerVersion = '0.8.1'
$DirplayerUrl     = "https://github.com/igorlira/dirplayer-rs/releases/download/v$DirplayerVersion/dirplayer-polyfill-$DirplayerVersion.zip"
$DirplayerSha256  = '6f5000412c50833345a630ade25fb70359dc4e1aae66352c80de7da77542162c'
$DirplayerDir     = Join-Path $ToolsDir 'dirplayer\polyfill'

if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $Java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $Java = 'java'
}

function Invoke-Ffdec {
    if (-not (Test-Path $FfdecJar)) { throw 'FFDec not found. Run scripts\setup.ps1 first.' }
    & $Java -jar $FfdecJar @args
    if ($LASTEXITCODE -ne 0) { throw "FFDec failed: $args" }
}

# Exports a Director movie/cast's assets (scripts/extract/DirectorExtract.java), compiling the
# extractor into tools\ the first time or when its source changed.
function Invoke-DirectorExtract([string]$File, [string]$OutDir) {
    $src = Join-Path $Root 'scripts\extract\DirectorExtract.java'
    $classes = Join-Path $ToolsDir 'extract'
    $class = Join-Path $classes 'DirectorExtract.class'
    if (-not (Test-Path $class) -or (Get-Item $src).LastWriteTime -gt (Get-Item $class).LastWriteTime) {
        $javac = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac' }
        New-Item -ItemType Directory -Force $classes | Out-Null
        & $javac -nowarn -d $classes $src
        if ($LASTEXITCODE -ne 0) { throw 'Compiling DirectorExtract failed (a JDK is needed for javac).' }
    }
    & $Java -cp $classes DirectorExtract $File $OutDir
    if ($LASTEXITCODE -ne 0) { throw "Asset export failed: $File" }
}

function Invoke-ProjectorRays {
    if (-not (Test-Path $PrExe)) { throw "ProjectorRays not found at $PrExe. Run scripts\setup.ps1, or set PROJECTORRAYS." }
    & $PrExe @args
    if ($LASTEXITCODE -ne 0) { throw "ProjectorRays failed: $args" }
}

# Names of the SWFs to process: the arguments given, or every SWF in original\.
function Get-SwfNames([string[]]$Names) {
    if ($Names) { return $Names | ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_) } }
    return Get-ChildItem -Path $OriginalDir -Filter *.swf -File -ErrorAction SilentlyContinue | ForEach-Object { $_.BaseName }
}

# Folders in original\ that hold a Director/Shockwave game: the arguments given, or all of them.
function Get-DirectorNames([string[]]$Names) {
    if ($Names) { return $Names | ForEach-Object { Split-Path -Leaf $_ } }
    return Get-ChildItem -Path $OriginalDir -Directory -ErrorAction SilentlyContinue | Where-Object {
        @(Get-ChildItem -File $_.FullName | Where-Object { $DirectorExts -contains $_.Extension.ToLower() }).Count -gt 0
    } | ForEach-Object { $_.Name }
}
