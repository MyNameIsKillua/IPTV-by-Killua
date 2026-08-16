# Synthetic Xtream provider for local testing.
#
# Serves entirely fabricated data on loopback. It exists so scale and memory problems can be
# reproduced without ever pointing a debug build at a real account, whose Logcat and URLs would
# contain credentials. Never replace these fixtures with captured provider data.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\fake-provider.ps1 -MovieCount 180000
#   adb reverse tcp:8099 tcp:8099
# then sign in with server http://127.0.0.1:8099/ and any user name and password.
#
# Set FAKE_PROVIDER_SLOW_ACTION to an action name to stall that one response for eight seconds.
# That is how a sync step is held on screen long enough to be inspected; without it a small
# synthetic library finishes faster than a screenshot can be taken.
#
# Loopback prefixes avoid needing administrator rights; `adb reverse` is what lets the emulator
# reach them. Binding to 10.0.2.2 directly would need an elevated URL ACL.
#
# Size fixtures on realistic field lengths, not row count alone: 200,000 short rows once passed
# while 153,000 realistic ones exhausted the heap. The reference provider delivers roughly
# 180,000 movies and 60,000 channels.
# Pass -MediaFile to answer stream paths with a local video instead of a 404. That is what makes
# the player itself checkable locally: chrome, gestures, and the next/previous episode controls all
# need something actually playing. Generate a throwaway clip rather than using any real content:
#   ffmpeg -f lavfi -i testsrc=size=640x360:rate=15:duration=180 -f lavfi -i sine=duration=180 `
#          -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest -movflags +faststart sample.mp4
param(
    [int]$Port = 8099,
    [int]$MovieCount = 180000,
    [int]$ChannelCount = 60000,
    [int]$SeriesCount = 400,
    [string]$MediaFile = ""
)

$ErrorActionPreference = "Stop"

if ($MediaFile) {
    $MediaFile = (Resolve-Path -LiteralPath $MediaFile).Path
    Write-Host "Serving stream paths from $MediaFile"
}

Write-Host "Generating $MovieCount synthetic VOD entries..."
$sb = [System.Text.StringBuilder]::new()
[void]$sb.Append('[')
for ($i = 1; $i -le $MovieCount; $i++) {
    if ($i -gt 1) { [void]$sb.Append(',') }
    $cat = 20 + ($i % 40)
    [void]$sb.Append('{"num":' + $i + ',"name":"DE | Beispielfilm Nummer ' + $i + ' - Ein aussergewoehnlich langer Verleihtitel mit Untertitel und Jahresangabe (2019) [4K UHD]","stream_type":"movie","stream_id":"' + (500000 + $i) + '","stream_icon":"https://images.provider.example/content/vod/posters/original/segment-' + $i + '/artwork-' + $i + '-poster-large-hq.jpg","rating":"' + (($i % 100) / 10.0) + '","rating_5based":"' + (($i % 50) / 10.0) + '","added":"' + (1600000000 + $i) + '","category_id":"' + $cat + '","container_extension":"mkv","custom_sid":"","direct_source":""}')
}
[void]$sb.Append(']')
$vodStreams = $sb.ToString()
Write-Host ("VOD payload: {0:N1} MB" -f ($vodStreams.Length / 1MB))

Write-Host "Generating $ChannelCount synthetic channels..."
$cb = [System.Text.StringBuilder]::new()
[void]$cb.Append('[')
for ($i = 1; $i -le $ChannelCount; $i++) {
    if ($i -gt 1) { [void]$cb.Append(',') }
    $cat = 1 + ($i % 30)
    [void]$cb.Append('{"num":' + $i + ',"name":"DE | Testkanal ' + $i + ' HD","stream_type":"live","stream_id":"' + $i + '","stream_icon":"https://images.provider.example/logos/channel-' + $i + '.png","epg_channel_id":"kanal.' + $i + '.de","added":"1600000000","category_id":"' + $cat + '","custom_sid":"","tv_archive":0,"direct_source":"","container_extension":"ts"}')
}
[void]$cb.Append(']')
$liveStreams = $cb.ToString()
Write-Host ("Live payload: {0:N1} MB" -f ($liveStreams.Length / 1MB))

Write-Host "Generating $SeriesCount synthetic series..."
$sbs = [System.Text.StringBuilder]::new()
[void]$sbs.Append('[')
for ($i = 1; $i -le $SeriesCount; $i++) {
    if ($i -gt 1) { [void]$sbs.Append(',') }
    $cat = 90 + ($i % 10)
    [void]$sbs.Append('{"num":' + $i + ',"name":"DE | Beispielserie Nummer ' + $i + ' - Eine synthetische Serie mit langem Titel","series_id":"' + (700000 + $i) + '","cover":"https://images.provider.example/content/series/covers/original/segment-' + $i + '/artwork-' + $i + '-cover-large-hq.jpg","rating":"' + (($i % 100) / 10.0) + '","releaseDate":"2019-05-24","last_modified":"' + (1600000000 + $i) + '","category_id":"' + $cat + '"}')
}
[void]$sbs.Append(']')
$seriesList = $sbs.ToString()
Write-Host ("Series payload: {0:N1} MB" -f ($seriesList.Length / 1MB))

# Category names cycle through three languages so the heuristic language filter has something
# real to separate. The API carries no language field; a tag in the name is the only signal.
$languages = @('DE', 'EN', 'FR')
$vodCategories = '[' + (((20..59) | ForEach-Object { '{"category_id":"' + $_ + '","category_name":"DE | Kategorie ' + $_ + '","parent_id":0}' }) -join ',') + ']'
$liveCategories = '[' + (((1..30) | ForEach-Object { $l = $languages[$_ % 3]; '{"category_id":"' + $_ + '","category_name":"' + $l + ' | Sender ' + $_ + '","parent_id":0}' }) -join ',') + ']'
$seriesCategories = '[' + (((90..99) | ForEach-Object { $l = $languages[$_ % 3]; '{"category_id":"' + $_ + '","category_name":"' + $l + ' | Serien ' + $_ + '","parent_id":0}' }) -join ',') + ']'
$auth = '{"user_info":{"username":"demo-user","password":"demo-pass","auth":1,"status":"Active","exp_date":"4102444800","is_trial":"0","active_cons":"1","created_at":"1600000000","max_connections":"2","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"127.0.0.1","port":"' + $Port + '","https_port":"","server_protocol":"http","timezone":"Europe/Berlin"}}'
$vodInfo = '{"info":{"movie_image":"https://images.provider.example/p.jpg","plot":"Eine synthetische Beschreibung.","genre":"Action","cast":"Erika Mustermann","director":"Max Mustermann","releasedate":"2019-05-24","rating":"7.4","duration_secs":6753},"movie_data":{"stream_id":"500001","name":"DE | Beispielfilm Nummer 1","container_extension":"mkv","category_id":"21"}}'

# Two seasons, so season chips and per-season narrowing have something to act on.
$seriesInfo = '{"info":{"name":"Beispielserie","cover":"https://images.provider.example/s.jpg","plot":"Eine synthetische Serienbeschreibung.","genre":"Drama","cast":"Erika Mustermann","director":"Max Mustermann","releaseDate":"2019-05-24","rating":"8.1"},"episodes":{"1":[{"id":"800001","episode_num":1,"title":"Der Anfang","container_extension":"mkv","info":{"duration_secs":2700,"plot":"Die erste Folge."}},{"id":"800002","episode_num":2,"title":"Der Weg","container_extension":"mkv","info":{"duration_secs":2650}}],"2":[{"id":"800011","episode_num":1,"title":"Neue Wege","container_extension":"mkv","info":{"duration_secs":2800}}]}}'

# A guide anchored on the current time, so "now" and "next" actually have something to show.
# Titles are Base64 because that is what most Xtream servers send.
function ConvertTo-EpgTitle([string]$Text) {
    [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Text))
}
$epgNow = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$epgStart = $epgNow - 900
$shortEpg = '{"epg_listings":[' +
    '{"id":"1","title":"' + (ConvertTo-EpgTitle "Tagesschau") + '","description":"' +
        (ConvertTo-EpgTitle "Nachrichten und Wetter.") + '","start_timestamp":"' + $epgStart +
        '","stop_timestamp":"' + ($epgStart + 1800) + '"},' +
    '{"id":"2","title":"' + (ConvertTo-EpgTitle "Tatort: Der synthetische Fall") +
        '","description":"' + (ConvertTo-EpgTitle "Ein erfundener Kriminalfall.") +
        '","start_timestamp":"' + ($epgStart + 1800) + '","stop_timestamp":"' +
        ($epgStart + 7200) + '"},' +
    '{"id":"3","title":"' + (ConvertTo-EpgTitle "Nachtmagazin") + '","start_timestamp":"' +
        ($epgStart + 7200) + '","stop_timestamp":"' + ($epgStart + 9000) + '"}' +
    ']}'

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Write-Host "Fake provider listening on port $Port"

while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $path = $ctx.Request.Url.AbsolutePath

    # Stream paths carry no action parameter. Answering one with the auth JSON would look like a
    # decoder failure rather than a missing file, so without -MediaFile they get an honest 404. The
    # logged path is also how a live/movie/series URL shape is checked without a real provider.
    if ($path -notlike "*.php" -and $path -ne "/") {
        Write-Host ("STREAM {0}" -f $path) -ForegroundColor Cyan
        if (-not $MediaFile) {
            $ctx.Response.StatusCode = 404
            $ctx.Response.Close()
            continue
        }
        # Range support is not optional: Media3 seeks with it, and a player that cannot seek makes
        # the transport controls untestable.
        $stream = [System.IO.File]::OpenRead($MediaFile)
        try {
            $total = $stream.Length
            $start = [int64]0
            $end = $total - 1
            if ($ctx.Request.Headers["Range"] -match "bytes=(\d+)-(\d*)") {
                $start = [int64]$Matches[1]
                if ($Matches[2]) { $end = [Math]::Min([int64]$Matches[2], $total - 1) }
                $ctx.Response.StatusCode = 206
                $ctx.Response.AddHeader("Content-Range", "bytes $start-$end/$total")
            }
            $ctx.Response.ContentType = "video/mp4"
            $ctx.Response.AddHeader("Accept-Ranges", "bytes")
            $remaining = $end - $start + 1
            $ctx.Response.ContentLength64 = $remaining
            $stream.Position = $start
            $buffer = New-Object byte[] 65536
            while ($remaining -gt 0) {
                $read = $stream.Read($buffer, 0, [int][Math]::Min([int64]$buffer.Length, $remaining))
                if ($read -le 0) { break }
                $ctx.Response.OutputStream.Write($buffer, 0, $read)
                $remaining -= $read
            }
        } catch {
            # The player drops the connection on a seek or on leaving the screen. That is normal.
            Write-Host ("  stream closed: {0}" -f $_.Exception.Message) -ForegroundColor DarkGray
        } finally {
            $stream.Dispose()
            $ctx.Response.Close()
        }
        continue
    }

    $action = $ctx.Request.QueryString["action"]
    if ($env:FAKE_PROVIDER_SLOW_ACTION -and $action -eq $env:FAKE_PROVIDER_SLOW_ACTION) {
        Start-Sleep -Seconds 8
    }
    $body = switch ($action) {
        "get_vod_categories" { $vodCategories }
        "get_vod_streams" { $vodStreams }
        "get_vod_info" { $vodInfo }
        "get_live_categories" { $liveCategories }
        "get_live_streams" { $liveStreams }
        "get_series_categories" { $seriesCategories }
        "get_series" { $seriesList }
        "get_series_info" { $seriesInfo }
        "get_short_epg" { $shortEpg }
        default { $auth }
    }
    $label = if ($action) { $action } else { "auth" }
    Write-Host ("{0} -> {1} bytes" -f $label, $body.Length)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $ctx.Response.ContentType = "application/json"
    $ctx.Response.ContentLength64 = $bytes.Length
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $ctx.Response.Close()
}
