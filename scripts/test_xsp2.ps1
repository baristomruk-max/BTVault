Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

# FNV-1a 32-bit hash (corrected)
function fnv1a($str) {
    $hash = [uint32]2166136261
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($str)
    foreach ($b in $bytes) {
        $hash = [uint32]($hash -bxor [uint32]$b)
        $product = [uint64]$hash * [uint64]16777619
        $hash = [uint32]($product % 4294967296)
    }
    return $hash.ToString("x8")
}

# Generate X-Sp token
function generate-XSp($sp, $spT, $offsetSeconds) {
    $n = $spT + $offsetSeconds
    $rng = New-Object System.Random
    $random = [Math]::Floor(2176782336 * $rng.NextDouble()).ToString("36")
    $hashInput = "$sp|$n|$random"
    $hash = fnv1a $hashInput
    $token = "$n.$random.$hash"
    return $token
}

$jeton = "_hejYnd0oZAW_ZvOXkTLVKVDu9_S_CR5y0WYSKH5c2cLHscSoYW-qC6rMzdzmt2yMLSO1jqFh7N3Xw7SvvBozIqKFddueCOG1QqRhU3VzJ0.x8NDB1MPzetDCJnMjv_k39NrWNhXFpE8cE35kVvMY7U"

# Step 1: Fetch fastplay.mom page to get fresh SPG_A and FSP config
Write-Output "Fetching fastplay.mom page..."
$pageHtml = curl.exe -s -L --connect-timeout 8 --max-time 15 "https://fastplay.mom/video/Au9jYbcyUdEa" -H "Referer: https://setplay.sbs/player/" -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

# Extract SPG_A
$spMatch = [regex]::Match($pageHtml, 'sp":"([^"]+)"')
$spTMatch = [regex]::Match($pageHtml, 'spT":(\d+)')
$streamMatch = [regex]::Match($pageHtml, 'stream:\s*"([^"]+)"')

if ($spMatch.Success) { $sp = $spMatch.Groups[1].Value } else { Write-Output "Failed to extract sp"; exit 1 }
if ($spTMatch.Success) { $spT = [int]$spTMatch.Groups[1].Value } else { Write-Output "Failed to extract spT"; exit 1 }
if ($streamMatch.Success) { $streamPath = $streamMatch.Groups[1].Value } else { Write-Output "Failed to extract stream"; exit 1 }

Write-Output "sp = $sp"
Write-Output "spT = $spT"
Write-Output "stream = $streamPath"

$manifestUrl = "https://fastplay.mom$streamPath"
Write-Output "manifestUrl = $manifestUrl"

# Step 2: Try fetching manifest with X-Sp header
$currentUnix = [int]([double]::Parse((Get-Date).ToUniversalTime().ToString("s"))) # approximate

# Try multiple approaches
$approaches = @(
    @{ n = $spT; label = "spT" },
    @{ n = $currentUnix; label = "currentUnix" }
)

foreach ($approach in $approaches) {
    $n = $approach.n
    $rng = New-Object System.Random
    $random = [Math]::Floor(2176782336 * $rng.NextDouble()).ToString("36")
    $hashInput = "$sp|$n|$random"
    $hash = fnv1a $hashInput
    $token = "$n.$random.$hash"
    
    Write-Output ("Trying n={0} ({1}), token={2}" -f $n, $approach.label, $token)
    
    $result = curl.exe -s -o NUL -w "HTTP:%{http_code}" --connect-timeout 8 --max-time 15 -H "Referer: https://fastplay.mom/video/Au9jYbcyUdEa" -H "Origin: https://fastplay.mom" -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36" -H "X-Sp: $token" "$manifestUrl"
    Write-Output "  Result: $result"
}
