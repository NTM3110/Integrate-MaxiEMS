param(
    [string]$SourceRoot = (Join-Path $PSScriptRoot 'apps/ems/inverters'),
    [string]$OutputFile = (Join-Path $PSScriptRoot 'pv_inverter_channels.csv')
)

$dict = @{}
$bundles = @()

Get-ChildItem -Path $SourceRoot -Recurse -Filter "*.java" | Where-Object { $_.FullName -like "*io.openems.edge.pvinverter*" -and $_.FullName -notmatch '[\\/](generated|bin|bin_test)[\\/]' } | ForEach-Object {
    $content = Get-Content $_.FullName -Raw
    if ($content -match 'enum ChannelId\s*(?:implements.*?ChannelId)?\s*\{([\s\S]*?)(?:private\s+final\s+Doc|;)') {
        $enumBody = $matches[1]
        $bundleName = $_.Directory.Parent.Name + "/" + $_.Name
        if ($bundles -notcontains $bundleName) {
            $bundles += $bundleName
        }

        $regex = '([A-Z][A-Z0-9_]*)\s*\('
        $enumBody | Select-String -Pattern $regex -AllMatches | ForEach-Object {
            $_.Matches | ForEach-Object {
                $channel = $_.Groups[1].Value
                if (-not $dict.ContainsKey($channel)) {
                    $dict[$channel] = @{}
                }
                $dict[$channel][$bundleName] = "x"
            }
        }
    }
}

$csv = "ChannelId"
foreach ($b in $bundles) {
    $csv += "," + $b
}
$csv += "`n"

foreach ($channel in $dict.Keys | Sort-Object) {
    $line = $channel
    foreach ($b in $bundles) {
        if ($dict[$channel].ContainsKey($b)) {
            $line += ",x"
        } else {
            $line += ","
        }
    }
    $csv += $line + "`n"
}

$csv | Out-File $OutputFile -Encoding UTF8
Write-Output "Created $OutputFile"
