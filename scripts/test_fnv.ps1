Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

function fnv1a($str) {
    $hash = [uint32]2166136261
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($str)
    foreach ($b in $bytes) {
        $hash = [uint32]($hash -bxor [uint32]$b)
        # JavaScript Math.imul equivalent: 32-bit multiplication
        $product = [uint64]$hash * [uint64]16777619
        $hash = [uint32]($product % 4294967296)
    }
    return $hash.ToString("x8")
}

$empty = fnv1a ""
Write-Output "FNV-1a('') = $empty"

$a = fnv1a "a"
Write-Output "FNV-1a('a') = $a"

$fo = fnv1a "fo"
Write-Output "FNV-1a('fo') = $fo"
