[CmdletBinding()]
param(
	[Parameter(Mandatory)][string]$Root,
	[Parameter(Mandatory)][int]$Port,
	[Parameter(Mandatory)][string]$Token,
	[Parameter(Mandatory)][string]$ReadyFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$resolvedRoot = [System.IO.Path]::GetFullPath($Root)
$routes = @{
	'meta-data' = 'meta-data.yaml'
	'user-data' = 'user-data.yaml'
	'vendor-data' = $null
}
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()
[System.IO.File]::WriteAllText($ReadyFile, "127.0.0.1:$Port/$Token/",
	[System.Text.UTF8Encoding]::new($false))
try {
	while ($true) {
		$client = $listener.AcceptTcpClient()
		try {
			$stream = $client.GetStream()
			$reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
			$requestLine = $reader.ReadLine()
			do { $header = $reader.ReadLine() } while ($null -ne $header -and $header.Length -gt 0)
			$status = '404 Not Found'
			$body = [System.Text.Encoding]::UTF8.GetBytes("not found`n")
			if ($requestLine -match '^GET\s+/([^/]+)/([^?\s]+)(?:\?[^\s]*)?\s+HTTP/') {
				$requestToken = $Matches[1]
				$route = $Matches[2]
				if ($requestToken -eq $Token -and $routes.ContainsKey($route)) {
					$fileName = $routes[$route]
					if ($null -eq $fileName) {
						$body = [byte[]]::new(0)
					} else {
						$path = [System.IO.Path]::GetFullPath((Join-Path $resolvedRoot $fileName))
						if (-not $path.StartsWith($resolvedRoot + [System.IO.Path]::DirectorySeparatorChar,
							[System.StringComparison]::OrdinalIgnoreCase)) { throw 'Seed route escaped root.' }
						$body = [System.IO.File]::ReadAllBytes($path)
					}
					$status = '200 OK'
				}
			}
			$headers = "HTTP/1.1 $status`r`nContent-Type: text/plain`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
			$headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
			$stream.Write($headerBytes, 0, $headerBytes.Length)
			if ($body.Length -gt 0) { $stream.Write($body, 0, $body.Length) }
			$stream.Flush()
		} finally {
			$client.Dispose()
		}
	}
} finally {
	$listener.Stop()
	if (Test-Path $ReadyFile) { Remove-Item -LiteralPath $ReadyFile -Force }
}
