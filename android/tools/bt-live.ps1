# Used by bt-live.bat: follows the game's log and copies Mark snapshots as they're written.
param([string]$Mode = "")

$adb = $env:BT_ADB_EXE
$serial = $env:BT_SERIAL
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$liveDir = Join-Path $here "live"
$markDir = Join-Path $liveDir $stamp
New-Item -ItemType Directory -Force -Path $liveDir | Out-Null

$sessionLog = Join-Path $liveDir "session-$stamp.log"
$currentLog = Join-Path $liveDir "current.log"
$enc = New-Object System.Text.UTF8Encoding($false)
$w1 = New-Object System.IO.StreamWriter($sessionLog, $false, $enc); $w1.AutoFlush = $true
$w2 = New-Object System.IO.StreamWriter($currentLog, $false, $enc); $w2.AutoFlush = $true

function Write-Both([string]$line) {
    Write-Host $line
    $w1.WriteLine($line)
    $w2.WriteLine($line)
}

Write-Both "# bt-live $stamp ($Mode) device $serial"
Write-Host "Saving to $sessionLog (and live\current.log). Marks go to $markDir. Ctrl+C to stop."
Write-Host ""

$lcArgs = @("-s", $serial, "logcat", "-v", "time", "-T", "1")
if ($Mode -eq "all") {
    $appPid = (& $adb -s $serial shell pidof io.github.bownz.btrecomp).Trim()
    if (-not $appPid) { Write-Host "The game isn't running; start it and run this again."; exit 1 }
    $lcArgs += @("--pid=$appPid")
} elseif ($Mode -eq "crash") {
    $lcArgs += @("-s", "BTLive:I", "BTRecomp:I", "BTProbe:I", "DEBUG:*", "AndroidRuntime:E", "libc:F")
} else {
    $lcArgs += @("-s", "BTLive:I", "BTRecomp:I", "BTProbe:I")
}

try {
    & $adb @args | ForEach-Object {
        $line = $_
        Write-Both $line
        if ($line -match "MARK (\d+) saved to (\S+\.bin\.gz)") {
            $n = $Matches[1]; $remote = $Matches[2]
            New-Item -ItemType Directory -Force -Path $markDir | Out-Null
            $local = Join-Path $markDir ("mark-{0:D2}.bin.gz" -f [int]$n)
            & $adb -s $serial pull $remote $local 2>&1 | Out-Null
            if (Test-Path $local) {
                Write-Both ("# PC: mark {0} copied to {1}" -f $n, $local)
            } else {
                Write-Both ("# PC: mark {0} could not be copied from {1}" -f $n, $remote)
            }
        }
    }
} finally {
    $w1.Close(); $w2.Close()
}
