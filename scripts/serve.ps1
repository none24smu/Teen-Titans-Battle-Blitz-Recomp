# Serves the repo over http://localhost:<port>/ so the players in platforms/web can load files.
#   Flash hub: http://localhost:8000/platforms/web/
#   Game:      http://localhost:8000/platforms/web/shockwave.html
# Usage: scripts\serve.ps1 [-Port 8000]    (Ctrl+C to stop)
param([int]$Port = 8000)
. (Join-Path $PSScriptRoot '_common.ps1')

$types = @{
    '.html' = 'text/html; charset=utf-8'; '.js' = 'text/javascript'; '.json' = 'application/json'
    '.wasm' = 'application/wasm'; '.map' = 'application/json'; '.css' = 'text/css'
    '.swf' = 'application/x-shockwave-flash'
    '.dcr' = 'application/x-director'; '.dir' = 'application/x-director'; '.dxr' = 'application/x-director'
    '.cct' = 'application/x-director'; '.cst' = 'application/x-director'; '.cxt' = 'application/x-director'
}

$listener = New-Object Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()
Write-Host "Serving $Root at http://localhost:$Port/"
Write-Host "  Game:      http://localhost:$Port/platforms/web/shockwave.html"
Write-Host "  Flash hub: http://localhost:$Port/platforms/web/"

$rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
try {
    while ($listener.IsListening) {
        $ctx = $listener.GetContext()
        $rel = [Uri]::UnescapeDataString($ctx.Request.Url.AbsolutePath).TrimStart('/')
        if ($rel -eq '' -or $rel.EndsWith('/')) { $rel += 'index.html' }
        $path = [IO.Path]::GetFullPath((Join-Path $Root $rel))
        # Like Director, resolve a protected cast/movie name to its unprotected twin (the decompiled
        # movies still link e.g. empty_Robin.cct, but src/ holds empty_Robin.cst).
        if (-not (Test-Path $path -PathType Leaf)) {
            $twin = @{ '.cct' = '.cst'; '.cxt' = '.cst'; '.dcr' = '.dir'; '.dxr' = '.dir' }[[IO.Path]::GetExtension($path).ToLower()]
            if ($twin) { $path = [IO.Path]::ChangeExtension($path, $twin) }
        }
        $res = $ctx.Response
        try {
            if ($path.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path $path -PathType Leaf)) {
                $ext = [IO.Path]::GetExtension($path).ToLower()
                $res.ContentType = if ($types[$ext]) { $types[$ext] } else { 'application/octet-stream' }
                $bytes = [IO.File]::ReadAllBytes($path)
                $res.ContentLength64 = $bytes.Length
                if ($ctx.Request.HttpMethod -ne 'HEAD') { $res.OutputStream.Write($bytes, 0, $bytes.Length) }
            } else {
                $res.StatusCode = 404
            }
        } catch {
            # A client that disconnects mid-download shouldn't take the server down.
            Write-Host "  $($ctx.Request.HttpMethod) /$rel failed: $($_.Exception.Message)"
        }
        try { $res.Close() } catch {}
    }
} finally {
    $listener.Stop()
}
