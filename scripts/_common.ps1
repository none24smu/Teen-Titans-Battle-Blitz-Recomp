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

# Names of the SWFs to process: the arguments given, or every SWF in original\.
function Get-SwfNames([string[]]$Names) {
    if ($Names) { return $Names | ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_) } }
    $files = @(Get-ChildItem -Path $OriginalDir -Filter *.swf -File -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { throw 'No SWF files in original\.' }
    return $files | ForEach-Object { $_.BaseName }
}
