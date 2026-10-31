$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$out = Join-Path $PSScriptRoot 'amostras'
New-Item -ItemType Directory $out -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$p = 'Era uma vez um tablet antigo que queria ser um leitor. Ele tinha pouca memória, uma tela pequena e muita vontade de mostrar apenas palavras.'

# TXT
[IO.File]::WriteAllText("$out\Texto de Teste.txt", ((1..60 | ForEach-Object { "Parágrafo $_. $p`n" }) -join "`n"), $utf8)

# HTML
[IO.File]::WriteAllText("$out\Pagina de Teste.html", ('<html><head><meta charset="utf-8"><title>Página</title></head><body><h1>Página HTML</h1>' + ((1..25 | ForEach-Object { "<p>$p</p>" }) -join '') + '</body></html>'), $utf8)

# FB2
$sec = { param($n) "<section><title><p>Capítulo $n</p></title>" + ((1..20 | ForEach-Object { "<p>$p <emphasis>Trecho em itálico.</emphasis></p>" }) -join '') + '</section>' }
$fb2 = '<?xml version="1.0" encoding="utf-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><author><first-name>Folio</first-name><last-name>Teste</last-name></author><book-title>Livro FB2 de Teste</book-title></title-info></description><body>' + (& $sec 1) + (& $sec 2) + '</body></FictionBook>'
[IO.File]::WriteAllText("$out\Livro FB2.fb2", $fb2, $utf8)

# CBZ: 3 paginas PNG
$cbz = "$out\Quadrinho de Teste.cbz"
$fs = [IO.File]::Create($cbz); $zip = New-Object IO.Compression.ZipArchive($fs, 'Create')
foreach ($i in 1..3) {
    $bmp = New-Object Drawing.Bitmap 600, 900; $g = [Drawing.Graphics]::FromImage($bmp)
    $g.Clear([Drawing.Color]::FromArgb(255, 30 * $i, 90, 160)); $f = New-Object Drawing.Font('Arial', 60)
    $g.DrawString("Página $i", $f, [Drawing.Brushes]::White, 60, 380)
    $ms = New-Object IO.MemoryStream; $bmp.Save($ms, [Drawing.Imaging.ImageFormat]::Png)
    $e = $zip.CreateEntry(('pagina{0:00}.png' -f $i)); $s = $e.Open(); $ms.WriteTo($s); $s.Dispose()
    $g.Dispose(); $bmp.Dispose()
}
$zip.Dispose(); $fs.Dispose()

# PDF: 3 paginas, minimo valido
$objs = New-Object Collections.Generic.List[string]
$objs.Add('<< /Type /Catalog /Pages 2 0 R >>')
$objs.Add('<< /Type /Pages /Kids [4 0 R 6 0 R 8 0 R] /Count 3 >>')
$objs.Add('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
foreach ($i in 1..3) {
    $n = 3 + 2 * $i - 1
    $objs.Add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 595] /Resources << /Font << /F1 3 0 R >> >> /Contents $($n + 1) 0 R >>")
    $txt = "BT /F1 28 Tf 50 520 Td (PDF de teste - pagina $i) Tj ET BT /F1 14 Tf 50 480 Td (Folio le PDF pelo renderizador do Android.) Tj ET"
    $objs.Add("<< /Length $($txt.Length) >>`nstream`n$txt`nendstream")
}
$sb = New-Object Text.StringBuilder; [void]$sb.Append("%PDF-1.4`n"); $offs = @()
for ($k = 0; $k -lt $objs.Count; $k++) { $offs += $sb.Length; [void]$sb.Append("$($k + 1) 0 obj`n$($objs[$k])`nendobj`n") }
$xref = $sb.Length; [void]$sb.Append("xref`n0 $($objs.Count + 1)`n0000000000 65535 f `n")
foreach ($o in $offs) { [void]$sb.Append(('{0:0000000000} 00000 n ' -f $o) + "`n") }
[void]$sb.Append("trailer`n<< /Size $($objs.Count + 1) /Root 1 0 R >>`nstartxref`n$xref`n%%EOF")
[IO.File]::WriteAllText("$out\PDF de Teste.pdf", $sb.ToString(), [Text.Encoding]::ASCII)

Get-ChildItem $out | Select-Object Name, Length
