# Installs the freshly built Windows app for the current user (no admin needed):
#   dist\PhoneMic-Desktop  ->  %LOCALAPPDATA%\Programs\Phone Mic
# plus "Phone Mic" shortcuts on the Desktop and in the Start menu's app list.
# build-exe.bat runs this after local builds (not on CI). Remove again with -Uninstall.
param([switch]$Uninstall)

$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'dist\PhoneMic-Desktop'
$target = Join-Path $env:LOCALAPPDATA 'Programs\Phone Mic'
$exe = Join-Path $target 'PhoneMic-Desktop.exe'
$shortcuts = @(
    (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Phone Mic.lnk'),
    (Join-Path ([Environment]::GetFolderPath('Programs')) 'Phone Mic.lnk') # Start menu > All apps
)

# A running copy locks its files; don't kill it (it may be mid-call), ask instead.
$running = Get-Process -Name 'PhoneMic-Desktop' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($target, [StringComparison]::OrdinalIgnoreCase) }
if ($running) {
    Write-Host "Phone Mic is running from $target - close it and run this again."
    exit 1
}

if ($Uninstall) {
    $shortcuts | Where-Object { Test-Path $_ } | Remove-Item -Force
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Write-Host "Removed $target and its shortcuts."
    exit 0
}

if (-not (Test-Path (Join-Path $source 'PhoneMic-Desktop.exe'))) {
    Write-Host "Build output not found at $source - run build-exe.bat first."
    exit 1
}

# Mirror, so files dropped from a newer build don't linger.
robocopy $source $target /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) {
    Write-Host "Copying to $target failed (robocopy exit $LASTEXITCODE)."
    exit 1
}

$shell = New-Object -ComObject WScript.Shell
foreach ($path in $shortcuts) {
    $link = $shell.CreateShortcut($path)
    $link.TargetPath = $exe
    $link.WorkingDirectory = $target
    $link.IconLocation = "$exe,0"
    $link.Description = 'Phone Mic desktop receiver'
    $link.Save()
}
Write-Host "Installed to $target with Desktop and Start menu shortcuts."
exit 0
