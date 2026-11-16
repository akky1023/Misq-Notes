param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = '',
    [ValidateSet('net.neomenia.misqnotes.probe', 'net.neomenia.misqnotes')]
    [string]$Package = 'net.neomenia.misqnotes.probe',
    [ValidateRange(1, 86400)][int]$DurationSeconds = 1800,
    [ValidateRange(5, 300)][int]$IntervalSeconds = 15,
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Adb) { $Adb = Join-Path $repository '.tools/sdk/platform-tools/adb.exe' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repository "captures/slowdown-$(Get-Date -Format yyyyMMdd-HHmmss)" }
if ($Serial -notmatch '^[A-Za-z0-9.:_-]+$') { throw 'Invalid ADB serial' }
$Adb = (Resolve-Path -LiteralPath $Adb).Path
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$utf8 = New-Object System.Text.UTF8Encoding($false)

# No app launch/stop, logcat clear, data writes, or installation. Read-only device collection.
function Invoke-Adb([string[]]$AdbArguments) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $Adb
    # Arguments here are fixed commands, a validated serial/package and decimal PIDs.
    $start.Arguments = '-s ' + $Serial + ' ' + ($AdbArguments -join ' ')
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) {
            $process.Kill()
            $process.WaitForExit()
            return @{ error = 'timeout'; elapsedMs = $clock.ElapsedMilliseconds }
        }
        $raw = $stdout.GetAwaiter().GetResult()
        $limit = 196608
        $truncated = $raw.Length -gt $limit
        if ($truncated) { $raw = $raw.Substring($raw.Length - $limit) }
        return @{ exitCode = $process.ExitCode; text = $raw; truncated = $truncated
            error = $stderr.GetAwaiter().GetResult(); elapsedMs = $clock.ElapsedMilliseconds }
    } finally { $process.Dispose() }
}

# Each stream has four files of at most 2 MiB, including JSON escaping overhead.
function Write-Record([string]$Stream, $Value) {
    $line = ($Value | ConvertTo-Json -Depth 12 -Compress) + "`n"
    $bytes = $utf8.GetByteCount($line)
    if ($bytes -gt 2MB) { throw "Record exceeds capacity: $Stream" }
    $file = Join-Path $OutputDirectory "$Stream.jsonl"
    if ((Test-Path -LiteralPath $file) -and ((Get-Item -LiteralPath $file).Length + $bytes -gt 2MB)) {
        $oldest = Join-Path $OutputDirectory "$Stream.3.jsonl"
        if (Test-Path -LiteralPath $oldest) { Remove-Item -LiteralPath $oldest }
        for ($i = 2; $i -ge 0; $i--) {
            $source = if ($i -eq 0) { $file } else { Join-Path $OutputDirectory "$Stream.$i.jsonl" }
            if (Test-Path -LiteralPath $source) {
                Move-Item -LiteralPath $source -Destination (Join-Path $OutputDirectory "$Stream.$($i + 1).jsonl")
            }
        }
    }
    [System.IO.File]::AppendAllText($file, $line, $utf8)
}

$state = Invoke-Adb @('get-state')
if ($state.exitCode -ne 0 -or $state.text.Trim() -ne 'device') {
    throw "Device not authorized/available: $($state | ConvertTo-Json -Compress)"
}
$metadata = @{ utc = [DateTimeOffset]::UtcNow.ToString('o'); serial = $Serial; package = $Package
    seconds = $DurationSeconds; intervalSeconds = $IntervalSeconds
    fingerprint = (Invoke-Adb @('shell', 'getprop', 'ro.build.fingerprint'))
    source = (& git -C $repository rev-parse HEAD)
    dirty = @(& git -C $repository status --short)
    packageInfo = (Invoke-Adb @('shell', 'dumpsys', 'package', $Package)) }
[System.IO.File]::WriteAllText((Join-Path $OutputDirectory 'metadata.json'), ($metadata | ConvertTo-Json -Depth 8), $utf8)

function Capture-Exit([string]$Phase) {
    Write-Record 'exits' @{ utc = [DateTimeOffset]::UtcNow.ToString('o'); phase = $Phase
        # API 30+ shell history; API 29 reports unsupported rather than inferring a reason.
        history = (Invoke-Adb @('shell', 'dumpsys', 'activity', 'exit-info', $Package))
        crashes = (Invoke-Adb @('logcat', '-d', '-b', 'crash', '-v', 'epoch', '-t', '300')) }
}

$clock = [System.Diagnostics.Stopwatch]::StartNew()
try {
    Capture-Exit 'before'
    do {
        $sampleStart = $clock.ElapsedMilliseconds
        $row = @{ utc = [DateTimeOffset]::UtcNow.ToString('o'); elapsedMs = $sampleStart }
        $row.pid = Invoke-Adb @('shell', 'pidof', $Package)
        $row.battery = Invoke-Adb @('shell', 'dumpsys', 'battery')
        $row.thermal = Invoke-Adb @('shell', 'dumpsys', 'thermalservice')
        $row.memory = Invoke-Adb @('shell', 'dumpsys', 'meminfo', $Package)
        $row.cpu = Invoke-Adb @('shell', 'dumpsys', 'cpuinfo')
        $row.frames = Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $Package)
        $row.uptime = Invoke-Adb @('shell', 'cat', '/proc/uptime')
        $row.collectionMs = $clock.ElapsedMilliseconds - $sampleStart
        Write-Record 'samples' $row
        Write-Record 'logcat' @{ utc = [DateTimeOffset]::UtcNow.ToString('o')
            # Bounded overlapping snapshots preserve surrounding system messages; never clear logcat.
            log = (Invoke-Adb @('logcat', '-d', '-v', 'epoch', '-t', '1500')) }
        Write-Host "Collected $([int]$clock.Elapsed.TotalSeconds)s -> $OutputDirectory"
        $wait = [Math]::Min($IntervalSeconds * 1000 - ($clock.ElapsedMilliseconds - $sampleStart),
            $DurationSeconds * 1000 - $clock.ElapsedMilliseconds)
        if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]$wait) }
    } while ($clock.Elapsed.TotalSeconds -lt $DurationSeconds)
} finally { Capture-Exit 'after' }
