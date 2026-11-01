# Live verification of the reader bookmark API against the running server.
#   .\scripts\verify-bookmark.ps1
#   $env:LEGADO_BASE_URL / $env:LEGADO_ADMIN_PASSWORD to point at another instance.
# Keep this file ASCII only: Windows PowerShell 5.1 reads BOM-less scripts as ANSI,
# and non-ASCII source bytes break the parser (same trap as the .cmd launchers).
$ErrorActionPreference = 'Stop'
$base = if ($env:LEGADO_BASE_URL) { $env:LEGADO_BASE_URL } else { 'http://127.0.0.1:8080' }
$password = if ($env:LEGADO_ADMIN_PASSWORD) { $env:LEGADO_ADMIN_PASSWORD } else { 'w71251478' }
$curl = 'curl.exe'
$jar = Join-Path $env:TEMP 'legado-bookmark-cookies.txt'
Remove-Item $jar -ErrorAction SilentlyContinue

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
function Write-JsonFile([string]$path, [string]$json) {
    [System.IO.File]::WriteAllText($path, $json, $utf8NoBom)
}

# Chinese payloads are built from code points so this file stays pure ASCII.
$bookName = -join ([char]0x8054, [char]0x8C03, [char]0x4E66)            # 联调书
$chapterName = -join ([char]0x7B2C, '4', [char]0x7AE0, ' verify')        # 第4章 verify
$snippet = -join ([char]0x4E66, [char]0x7B7E, ' snippet')
$note = -join ([char]0x5907, [char]0x6CE8, ': insight')

# 1) login -> cookie jar + csrf
$loginBody = Join-Path $env:TEMP 'legado-login.json'
Write-JsonFile $loginBody (@{ password = $password } | ConvertTo-Json -Compress)
$loginRaw = & $curl -s -c $jar -H 'Content-Type: application/json' --data-binary "@$loginBody" "$base/api/auth/login"
$csrf = ($loginRaw | ConvertFrom-Json).csrfToken
Write-Host "LOGIN ok csrf=$($csrf.Substring(0,8))..."

# 2) add a bookmark
$addBody = Join-Path $env:TEMP 'legado-bookmark-add.json'
Write-JsonFile $addBody (@{
    bookName     = $bookName
    bookAuthor   = 'verify-author'
    chapterIndex = 3
    chapterName  = $chapterName
    chapterPos   = 1234
    bookText     = $snippet
    content      = ''
} | ConvertTo-Json -Compress)
$created = & $curl -s -b $jar -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' `
    --data-binary "@$addBody" "$base/api/bookmarks" | ConvertFrom-Json
Write-Host "ADD   id=$($created.id) chapterIndex=$($created.chapterIndex) chapterPos=$($created.chapterPos) snippetMatch=$($created.bookText -eq $snippet)"

# 3) same position again -> upsert with the same id
$again = & $curl -s -b $jar -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' `
    --data-binary "@$addBody" "$base/api/bookmarks" | ConvertFrom-Json
Write-Host "ADD*  id=$($again.id) sameId=$($again.id -eq $created.id)"

# 4) list
$q = "bookName=$([uri]::EscapeDataString($bookName))&bookAuthor=verify-author"
$listRaw = & $curl -s -b $jar "$base/api/bookmarks?$q"
$list = $listRaw | ConvertFrom-Json
Write-Host "LIST  count=$($list.items.Count) chapterNameMatch=$($list.items[0].chapterName -eq $chapterName)"

# 5) edit the note only
$editBody = Join-Path $env:TEMP 'legado-bookmark-edit.json'
Write-JsonFile $editBody ((@{ content = $note } | ConvertTo-Json -Compress))
$updated = & $curl -s -b $jar -X PUT -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' `
    --data-binary "@$editBody" "$base/api/bookmarks/$($created.id)" | ConvertFrom-Json
Write-Host "EDIT  noteMatch=$($updated.content -eq $note) chapterPosKept=$($updated.chapterPos -eq 1234)"

# 6) backup export must carry it
$emptyBody = Join-Path $env:TEMP 'legado-bookmark-export.json'
Write-JsonFile $emptyBody '{}'
$export = & $curl -s -b $jar -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' `
    --data-binary "@$emptyBody" "$base/api/webdav/export" | ConvertFrom-Json
Write-Host "EXPORT file=$($export.fileName) bookmarks=$($export.bookmarks)"
$zipFile = Get-ChildItem -Path '_run-data' -Recurse -Filter $export.fileName -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $zipFile) { throw "exported zip not found: $($export.fileName)" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipFile.FullName)
try {
    $entry = $zip.Entries | Where-Object { $_.Name -eq 'bookmark.json' } | Select-Object -First 1
    $reader = New-Object System.IO.StreamReader($entry.Open(), $utf8NoBom)
    $parsed = $reader.ReadToEnd() | ConvertFrom-Json
    $reader.Close()
    $found = $parsed | Where-Object { $_.bookName -eq $bookName -and $_.chapterPos -eq 1234 } | Select-Object -First 1
    Write-Host "ZIP   entries=$($parsed.Count) found=$($null -ne $found)"
    if ($found) {
        Write-Host "ZIP*  nameMatch=$($found.bookName -eq $bookName) noteMatch=$($found.content -eq $note) pos=$($found.chapterPos) time=$($found.time)"
    }
} finally { $zip.Dispose() }

# 7) delete -> 204, then 404
$delCode = & $curl -s -b $jar -X DELETE -H "X-CSRF-Token: $csrf" -o NUL -w '%{http_code}' "$base/api/bookmarks/$($created.id)"
$delAgain = & $curl -s -b $jar -X DELETE -H "X-CSRF-Token: $csrf" -o NUL -w '%{http_code}' "$base/api/bookmarks/$($created.id)"
$after = (& $curl -s -b $jar "$base/api/bookmarks?$q") | ConvertFrom-Json
Write-Host "DEL   first=$delCode again=$delAgain afterCount=$($after.items.Count)"

# 8) unauthenticated must be rejected
$anonCode = & $curl -s -o NUL -w '%{http_code}' "$base/api/bookmarks?bookName=x"
$writeAnon = & $curl -s -o NUL -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "@$addBody" "$base/api/bookmarks"
Write-Host "AUTH  read=$anonCode (expect 401) write=$writeAnon (expect 401)"
