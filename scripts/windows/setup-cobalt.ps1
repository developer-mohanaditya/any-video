<#
.SYNOPSIS
    Installs WSL2, Docker Desktop and a cobalt API container on a Windows machine,
    then stops the machine from sleeping and makes the container start at logon.

.DESCRIPTION
    Re-runnable. Each step is skipped when it is already done, so you can run it
    again after a reboot or to pick up a changed -ApiUrl.

    It does NOT touch your router or open firewall ports. -WithTailscale gives
    tailnet-only HTTPS access; add -Funnel to also make the HTTPS address reachable
    by anyone on the internet (Tailscale Funnel, no client or login for visitors).

.PARAMETER ApiUrl
    The address your phone will use to reach cobalt, WITH a trailing slash.
    Examples:
      http://192.168.1.20:9000/                      home Wi-Fi only
      https://laptop.tail1a2b3c.ts.net/              Tailscale + HTTPS (recommended)
    Wrong values break cobalt's picker/tunnel links, not its API.

.PARAMETER InstallPath
    Folder for docker-compose.yml and keys.json. Default C:\cobalt.

.PARAMETER WithKey
    Generate a random API key, write keys.json and require it on every request.
    The key is printed at the end; paste it into the app's "API key" field.

.PARAMETER WithTailscale
    Install the Tailscale client (you still have to sign in once).

.PARAMETER Funnel
    Publish the HTTPS address to the open internet with Tailscale Funnel instead of
    the tailnet-only 'serve'. Visitors need no Tailscale client and no account. The
    machine still has to be signed in to your tailnet. Implies -WithTailscale.

    Funnel is public: keep an API key on cobalt, and turn it off when you are done
    with:  tailscale funnel --https=443 off

.PARAMETER SkipSleepFixes
    Leave Windows power settings alone.

.EXAMPLE
    .\setup-cobalt.ps1
    .\setup-cobalt.ps1 -ApiUrl "https://laptop.tail1a2b3c.ts.net/" -WithKey -WithTailscale
    .\setup-cobalt.ps1 -ApiUrl "https://laptop.tail1a2b3c.ts.net/" -WithKey -WithTailscale -Funnel

.NOTES
    Run from an elevated (Administrator) PowerShell window:
        Set-ExecutionPolicy -Scope Process Bypass -Force
        .\setup-cobalt.ps1
#>

#Requires -Version 5.1
#Requires -RunAsAdministrator

[CmdletBinding()]
param(
    [string]$ApiUrl = "http://127.0.0.1:9000/",
    [string]$InstallPath = "C:\cobalt",
    [switch]$WithKey,
    [switch]$WithTailscale,
    [switch]$Funnel,
    [switch]$SkipSleepFixes
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# --- tiny output helpers -----------------------------------------------------

function Write-Section([string]$Text) {
    Write-Host ""
    Write-Host "=== $Text ===" -ForegroundColor Cyan
}

function Write-Ok([string]$Text) {
    Write-Host "  [ok]   $Text" -ForegroundColor Green
}

function Write-Info([string]$Text) {
    Write-Host "  [..]   $Text" -ForegroundColor Gray
}

function Write-Warn([string]$Text) {
    Write-Host "  [warn] $Text" -ForegroundColor Yellow
}

function Write-Fail([string]$Text) {
    Write-Host "  [fail] $Text" -ForegroundColor Red
}

# Windows PowerShell 5.1 writes a UTF-8 BOM with Set-Content -Encoding UTF8, which
# breaks JSON.parse in cobalt and can confuse YAML parsers. Always write raw bytes.
function Write-TextFile([string]$Path, [string]$Content) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

# --- preflight ---------------------------------------------------------------

Write-Section "Preflight"

if (-not $ApiUrl.EndsWith("/")) {
    $ApiUrl = "$ApiUrl/"
    Write-Warn "ApiUrl had no trailing slash; using $ApiUrl"
}

$currentBuild = [int](Get-CimInstance Win32_OperatingSystem).BuildNumber
if ($currentBuild -lt 19041) {
    Write-Fail "Windows build $currentBuild is too old for WSL2 (needs 19041 = 2004 or newer)."
    Write-Fail "Update Windows first, then run this again."
    exit 1
}
Write-Ok "Windows build $currentBuild"

$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Fail "Run this from PowerShell started with 'Run as administrator'."
    exit 1
}
Write-Ok "Elevated session"

$hasWinget = $null -ne (Get-Command winget -ErrorAction SilentlyContinue)
if ($hasWinget) { Write-Ok "winget available" } else { Write-Warn "winget missing; will fall back to direct downloads" }

# --- step 1: WSL2 ------------------------------------------------------------

Write-Section "Step 1/6  WSL2"

$rebootNeeded = $false
$wslExe = "$env:SystemRoot\System32\wsl.exe"

$wslInstalled = $false
if (Test-Path $wslExe) {
    & $wslExe --status *> $null
    if ($LASTEXITCODE -eq 0) { $wslInstalled = $true }
}

if ($wslInstalled) {
    Write-Ok "WSL already installed"
    try {
        & $wslExe --set-default-version 2 *> $null
        Write-Ok "Default WSL version is 2"
    } catch {
        Write-Warn "Could not set default WSL version; Docker may complain later."
    }
} else {
    Write-Info "Installing WSL2 without a Linux distro (Docker Desktop brings its own)..."
    & $wslExe --install --no-distribution
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "wsl --install failed with exit code $LASTEXITCODE"
        exit 1
    }
    $rebootNeeded = $true
    Write-Ok "WSL2 staged"
}

# Virtual Machine Platform is what WSL2 actually runs on.
$vmp = Get-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform -ErrorAction SilentlyContinue
if ($vmp -and $vmp.State -ne "Enabled") {
    Write-Info "Enabling Virtual Machine Platform..."
    Enable-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform -All -NoRestart | Out-Null
    $rebootNeeded = $true
    Write-Ok "Virtual Machine Platform enabled"
} elseif ($vmp) {
    Write-Ok "Virtual Machine Platform already enabled"
}

if ($rebootNeeded) {
    Write-Host ""
    Write-Warn "A REBOOT IS REQUIRED before Docker can run."
    Write-Warn "Reboot, then run this same script again -- it picks up where it left off."
    exit 0
}

try { & $wslExe --update *> $null; Write-Ok "WSL kernel up to date" } catch { Write-Warn "wsl --update skipped" }

# --- step 2: Docker Desktop --------------------------------------------------

Write-Section "Step 2/6  Docker Desktop"

$dockerDesktopExe = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
$dockerExe = Join-Path $env:ProgramFiles "Docker\Docker\resources\bin\docker.exe"

if (Test-Path $dockerDesktopExe) {
    Write-Ok "Docker Desktop already installed"
} elseif ($hasWinget) {
    Write-Info "Installing Docker Desktop via winget (a few hundred MB, be patient)..."
    & winget install --exact --id Docker.DockerDesktop --silent `
        --accept-package-agreements --accept-source-agreements --disable-interactivity
    # 0 = installed; -1978335189 = already installed.
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) {
        Write-Warn "winget returned $LASTEXITCODE; trying the direct installer instead."
        $hasWinget = $false
    }
}

if (-not (Test-Path $dockerDesktopExe) -and -not $hasWinget) {
    $installer = Join-Path $env:TEMP "DockerDesktopInstaller.exe"
    Write-Info "Downloading Docker Desktop installer..."
    Invoke-WebRequest -Uri "https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe" `
        -OutFile $installer -UseBasicParsing
    Write-Info "Installing (silent)..."
    Start-Process -FilePath $installer -ArgumentList "install", "--quiet", "--accept-license" -Wait
}

if (-not (Test-Path $dockerDesktopExe)) {
    Write-Fail "Docker Desktop is still not installed. Install it from https://www.docker.com/products/docker-desktop/ and re-run."
    exit 1
}
Write-Ok "Docker Desktop present"

# The installer usually handles this, but non-admin accounts are a common gotcha.
try {
    $group = & net localgroup docker-users 2>$null
    if ($LASTEXITCODE -eq 0 -and $group -notmatch [regex]::Escape($env:USERNAME)) {
        & net localgroup docker-users $env:USERNAME /add *> $null
        Write-Warn "Added $env:USERNAME to docker-users; you may need to sign out and back in."
    }
} catch { }

# --- step 3: compose file ----------------------------------------------------

Write-Section "Step 3/6  cobalt configuration"

if (-not (Test-Path $InstallPath)) {
    New-Item -ItemType Directory -Path $InstallPath -Force | Out-Null
}
$composeFile = Join-Path $InstallPath "docker-compose.yml"

$keyEnabled = $false
$apiKey = $null

if ($WithKey) {
    if (Test-Path (Join-Path $InstallPath "keys.json")) {
        Write-Ok "keys.json already exists (kept as-is)"
        try {
            $existing = Get-Content (Join-Path $InstallPath "keys.json") -Raw | ConvertFrom-Json
            $apiKey = ($existing.PSObject.Properties | Select-Object -First 1).Name
        } catch { }
    } else {
        $apiKey = [guid]::NewGuid().ToString()
        $json = "{`n  `"$apiKey`": { `"name`": `"my-phone`", `"limit`": `"unlimited`" }`n}`n"
        Write-TextFile -Path (Join-Path $InstallPath "keys.json") -Content $json
        Write-Ok "Generated keys.json with one unlimited key"
    }
    $keyEnabled = $true
}

$cookiesFile = Join-Path $InstallPath "cookies.json"
$haveCookies = Test-Path $cookiesFile

$compose = @"
services:
    cobalt:
        image: ghcr.io/imputnet/cobalt:11
        init: true
        read_only: true
        restart: unless-stopped
        container_name: cobalt
        ports:
            - 9000:9000/tcp
        environment:
            # Must be the address your phone uses, trailing slash included.
            API_URL: "$ApiUrl"
"@

if ($keyEnabled) {
    $compose += @"

            API_KEY_URL: "file:///keys.json"
"@
}

if ($haveCookies) {
    $compose += @"

            # Needed for Instagram Reels/posts and other login-walled content.
            COOKIE_PATH: "/cookies.json"
"@
}

$volumes = @()
if ($keyEnabled) { $volumes += "            - ./keys.json:/keys.json:ro" }
if ($haveCookies) { $volumes += "            - ./cookies.json:/cookies.json:ro" }

if ($volumes.Count -gt 0) {
    $compose += "`n        volumes:`n" + ($volumes -join "`n") + "`n"
} else {
    $compose += "`n        # Drop a cookies.json in $InstallPath and re-run to enable Instagram support.`n"
}

Write-TextFile -Path $composeFile -Content $compose
Write-Ok "Wrote $composeFile"
if ($haveCookies) { Write-Ok "Using cookies.json" } else { Write-Info "No cookies.json yet (Instagram will be limited)" }
Write-Info "API_URL = $ApiUrl"

# --- step 4: start the engine and the container -----------------------------

Write-Section "Step 4/6  Starting cobalt"

$running = Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue
if (-not $running) {
    Write-Info "Launching Docker Desktop..."
    Start-Process -FilePath $dockerDesktopExe
}

Write-Info "Waiting for the Docker engine (up to 6 minutes on first run)..."
$engineReady = $false
for ($i = 0; $i -lt 72; $i++) {
    if (Test-Path $dockerExe) {
        & $dockerExe info *> $null
        if ($LASTEXITCODE -eq 0) { $engineReady = $true; break }
    }
    Start-Sleep -Seconds 5
}

if (-not $engineReady) {
    Write-Fail "Docker engine did not come up. Open Docker Desktop, finish its first-run dialog, then re-run this script."
    exit 1
}
Write-Ok "Docker engine is up"

$composeCmd = if (Test-Path $dockerExe) { $dockerExe } else { "docker" }
Push-Location $InstallPath
try {
    & $composeCmd compose up -d
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "docker compose up failed with exit code $LASTEXITCODE"
        Pop-Location
        exit 1
    }
    Write-Ok "Container started"
} finally {
    Pop-Location
}

Write-Info "Waiting for the cobalt API to answer..."
$apiUp = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri "http://127.0.0.1:9000/" -UseBasicParsing -TimeoutSec 5
        if ($resp.StatusCode -lt 500) { $apiUp = $true; break }
    } catch {
        Start-Sleep -Seconds 2
    }
}

if ($apiUp) {
    Write-Ok "cobalt is answering on http://127.0.0.1:9000/"
} else {
    Write-Warn "cobalt did not answer yet. Check with:  docker logs cobalt"
}

# --- step 5: power settings --------------------------------------------------

Write-Section "Step 5/6  Keep it awake"

if ($SkipSleepFixes) {
    Write-Info "Skipped (-SkipSleepFixes)"
} else {
    try {
        # 0 = never, on AC power only; battery behaviour is left alone.
        & powercfg /change standby-timeout-ac 0
        & powercfg /change hibernate-timeout-ac 0
        & powercfg /change monitor-timeout-ac 15

        # Lid close action = do nothing (AC): GUID 4f971e89... LIDACTION 5ca83367...
        & powercfg /setacvalueindex SCHEME_CURRENT 4f971e89-eebd-4455-a8de-9e59040e7347 5ca83367-6e45-459f-a27b-476b1d01c936 0
        & powercfg /setactive SCHEME_CURRENT *> $null

        Write-Ok "Sleep disabled on AC, lid close set to do nothing"
        Write-Info "Leave the laptop plugged in; on battery it will still sleep."
    } catch {
        Write-Warn "Could not change power settings automatically: $($_.Exception.Message)"
    }
}

# Docker Desktop only starts after an interactive sign-in, so add a backstop.
try {
    $taskName = "cobalt-compose-up"
    $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Ok "Scheduled task '$taskName' already exists"
    } else {
        $action = New-ScheduledTaskAction -Execute $composeCmd -Argument "compose up -d" -WorkingDirectory $InstallPath
        $trigger = New-ScheduledTaskTrigger -AtLogOn
        $trigger.Delay = "PT45S"
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
        Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
            -Description "Start the cobalt resolver container at logon" -Force | Out-Null
        Write-Ok "Scheduled task '$taskName' will run 'docker compose up -d' at logon"
    }
} catch {
    Write-Warn "Could not register the logon task: $($_.Exception.Message)"
}

Write-Info "Docker Desktop must autostart: Settings > General > 'Start Docker Desktop when you sign in'."
Write-Info "For unattended reboots give Windows an auto sign-in (run: netplwiz)."

# --- step 6: Tailscale (optional) -------------------------------------------

Write-Section "Step 6/6  Remote access"

if (-not ($WithTailscale -or $Funnel)) {
    Write-Info "Skipped. Re-run with -WithTailscale to install Tailscale for access from anywhere."
    Write-Info "Add -Funnel to make it reachable by anyone on the internet, without a client or login."
} else {
    $tsExe = Join-Path $env:ProgramFiles "Tailscale\tailscale.exe"
    if (Test-Path $tsExe) {
        Write-Ok "Tailscale already installed"
    } elseif ($hasWinget) {
        Write-Info "Installing Tailscale..."
        & winget install --exact --id Tailscale.Tailscale --silent `
            --accept-package-agreements --accept-source-agreements --disable-interactivity
    }

    if (Test-Path $tsExe) {
        $status = & $tsExe status 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $status) {
            Write-Warn "Tailscale is installed but not signed in."
            Write-Warn "Run:  & '$tsExe' up    (a browser window opens; sign in, then come back)"
            $tsVerb = if ($Funnel) { "funnel" } else { "serve" }
            Write-Warn "Then: & '$tsExe' $tsVerb --bg --https=443 http://127.0.0.1:9000"
        } else {
            $dnsName = (& $tsExe status --json | ConvertFrom-Json).Self.DNSName
            $dnsName = $dnsName.TrimEnd(".")
            Write-Ok "Tailscale is up as $dnsName"

            $tsVerb = if ($Funnel) { "funnel" } else { "serve" }
            if ($Funnel) {
                Write-Info "Funnel publishes https://$dnsName/ to the open internet."
                Write-Info "Funnel replaces any existing 'serve' config on this port; the last command wins."
            }

            & $tsExe $tsVerb --bg --https=443 http://127.0.0.1:9000
            if ($LASTEXITCODE -eq 0) {
                if ($Funnel) {
                    Write-Ok "Funnel is on"
                    & $tsExe funnel status 2>$null | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }

                    # Loopback-only proof: this request leaves the machine, crosses the
                    # Tailscale ingress, and comes back in. DNS can take up to 10 min.
                    $public = $false
                    for ($i = 0; $i -lt 5; $i++) {
                        try {
                            $probe = Invoke-WebRequest -Uri "https://$dnsName/" -UseBasicParsing -TimeoutSec 10
                            if ($probe.StatusCode -lt 500) { $public = $true; break }
                        } catch {
                            Start-Sleep -Seconds 15
                        }
                    }
                    if ($public) {
                        Write-Ok "https://$dnsName/ answered from the public internet"
                    } else {
                        Write-Warn "https://$dnsName/ does not answer yet; public DNS can take up to 10 minutes."
                        Write-Warn "Check later:  & '$tsExe' funnel status"
                    }
                    Write-Warn "Funnel is public and survives reboots. Turn it off with:"
                    Write-Warn "  & '$tsExe' funnel --https=443 off"
                } else {
                    Write-Ok "HTTPS proxy enabled (tailnet only)"
                }

                if ($ApiUrl -ne "https://$dnsName/") {
                    Write-Warn "Your API_URL is $ApiUrl but Tailscale would use https://$dnsName/"
                    Write-Warn "Re-run with -ApiUrl `"https://$dnsName/`" and restart the container."
                } else {
                    Write-Ok "API_URL matches the Tailscale address"
                }
            } elseif ($Funnel) {
                Write-Warn "tailscale funnel failed. If it printed a consent URL, open it, approve Funnel for this node, then retry."
                Write-Warn "Funnel also needs MagicDNS and HTTPS certificates enabled in the admin console."
            } else {
                Write-Warn "tailscale serve failed. Enable MagicDNS and HTTPS certificates in the Tailscale admin console, then retry."
            }
        }
    } else {
        Write-Warn "Tailscale not installed; get it from https://tailscale.com/download/windows"
    }
}

# --- summary -----------------------------------------------------------------

Write-Section "Done"

Write-Host ""
Write-Host "  cobalt is running locally : http://127.0.0.1:9000/" -ForegroundColor White
Write-Host "  In the anyvideo app       : Settings > Resolver API" -ForegroundColor White
Write-Host "    API base URL            : $ApiUrl" -ForegroundColor White
if ($apiKey) {
    Write-Host "    API key                 : $apiKey" -ForegroundColor White
} else {
    Write-Host "    API key                 : (none - leave blank)" -ForegroundColor White
}
Write-Host ""
Write-Host "  Useful commands:" -ForegroundColor White
Write-Host "    docker logs -f cobalt            watch cobalt's output"
Write-Host "    docker compose -f $composeFile restart"
Write-Host "    docker compose -f $composeFile down     stop it"
if ($Funnel) {
    Write-Host "    tailscale funnel status          is it still public?"
    Write-Host "    tailscale funnel --https=443 off make it private again"
}
Write-Host ""
Write-Host "  Next: test from the phone's browser first, then in the app." -ForegroundColor White
if ($Funnel) {
    Write-Host "  Public check: phone on mobile data, Tailscale OFF, open the address above." -ForegroundColor White
}
if ($ApiUrl -like "*127.0.0.1*" -or $ApiUrl -like "*localhost*") {
    Write-Warn "API_URL points at loopback, so the phone cannot open cobalt's links." -ForegroundColor Yellow
    Write-Warn "Fine for API-only use; re-run with -ApiUrl for the real address." -ForegroundColor Yellow
}
Write-Host "  Instagram needs cookies: put cookies.json in $InstallPath and re-run." -ForegroundColor White
Write-Host ""
