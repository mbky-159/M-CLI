[CmdletBinding()]
param(
    [string]$ServerAddress = "47.84.77.65",
    [string]$SshUser = "root",
    [string]$IdentityFile = "$HOME\Desktop\m-cli-admin.pem",
    [int]$LocalPort = 18080
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if (-not (Test-Path -LiteralPath $IdentityFile)) {
    throw "找不到 SSH 私钥：$IdentityFile"
}

$sshTarget = "${SshUser}@${ServerAddress}"
$baseUri = "http://127.0.0.1:$LocalPort"
$tunnel = $null
$client = $null

function Invoke-MCliJson {
    param(
        [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [object]$Body
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), $Uri)
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress
        $request.Content = [System.Net.Http.StringContent]::new(
            $json, [System.Text.Encoding]::UTF8, "application/json")
    }
    $response = $Client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "M-CLI API 返回 HTTP $([int]$response.StatusCode)：$text"
    }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json
}

try {
    Write-Host "正在连接 M-CLI 云端..." -ForegroundColor DarkGray
    $tunnelArgs = @(
        "-i", $IdentityFile,
        "-o", "BatchMode=yes",
        "-o", "ExitOnForwardFailure=yes",
        "-N", "-L", "${LocalPort}:127.0.0.1:8080", $sshTarget
    )
    $tunnel = Start-Process -FilePath "ssh" -ArgumentList $tunnelArgs -PassThru -WindowStyle Hidden

    $apiKey = (& ssh -i $IdentityFile -o BatchMode=yes $sshTarget `
        "sed -n 's/^PAICLI_RUNTIME_API_KEY=//p' /etc/m-cli/m-cli.env").Trim()
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        throw "服务器没有配置 PAICLI_RUNTIME_API_KEY"
    }

    $healthy = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            $probe = [System.Net.Http.HttpClient]::new()
            $health = $probe.GetStringAsync("$baseUri/healthz").GetAwaiter().GetResult()
            $probe.Dispose()
            if ($health -match '"status"\s*:\s*"ok"') { $healthy = $true; break }
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $healthy) { throw "SSH 隧道已建立，但云端健康检查未通过" }

    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $apiKey)
    $thread = Invoke-MCliJson -Client $client -Method "POST" -Uri "$baseUri/v1/threads"
    $threadId = $thread.id

    Clear-Host
    Write-Host "M-CLI Cloud" -ForegroundColor Cyan
    Write-Host "已连接云端沙箱入口。输入 exit 或 /exit 退出。" -ForegroundColor DarkGray
    Write-Host ""

    while ($true) {
        $inputText = Read-Host "你"
        if ($inputText -in @("exit", "/exit", "quit", "/quit")) { break }
        if ([string]::IsNullOrWhiteSpace($inputText)) { continue }

        $turn = Invoke-MCliJson -Client $client -Method "POST" `
            -Uri "$baseUri/v1/threads/$threadId/turns" -Body @{ input = $inputText }
        $turnId = $turn.id
        $after = 0
        $answer = ""
        $completed = $false
        Write-Host "M-CLI> " -NoNewline -ForegroundColor Green

        while ($true) {
            $eventText = $client.GetStringAsync(
                "$baseUri/v1/threads/$threadId/events?after=$after").GetAwaiter().GetResult()
            foreach ($block in ($eventText -split "\r?\n\r?\n")) {
                if ($block -match "(?m)^id:\s*(\d+)$") { $after = [int64]$Matches[1] }
                if ($block -notmatch "(?m)^data:\s*(.+)$") { continue }
                $data = $Matches[1] | ConvertFrom-Json
                if ($data.turn_id -ne $turnId) { continue }
                if ($block -match "(?m)^event:\s*message\.delta$") {
                    $answer += [string]$data.content
                } elseif ($block -match "(?m)^event:\s*turn\.failed$") {
                    throw "云端任务失败：$($data.error)"
                } elseif ($block -match "(?m)^event:\s*turn\.completed$") {
                    Write-Host $answer
                    Write-Host ""
                    $completed = $true
                    break
                }
            }
            if ($completed) { break }
            Start-Sleep -Milliseconds 500
        }
    }
} finally {
    if ($client) { $client.Dispose() }
    if ($tunnel -and -not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force }
    $apiKey = $null
}
