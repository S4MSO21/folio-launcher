param([string]$Out = (Join-Path $PSScriptRoot 'amostras\teste.epub'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Add-Entry($zip, $name, $text) {
    $e = $zip.CreateEntry($name)
    $w = New-Object IO.StreamWriter($e.Open(), (New-Object Text.UTF8Encoding($false)))
    $w.Write($text); $w.Dispose()
}

$para = 'Era uma vez um tablet antigo que queria ser um leitor. Ele tinha pouca memória, uma tela pequena e muita vontade de mostrar apenas palavras. '
$ch = { param($n) "<?xml version=`"1.0`" encoding=`"utf-8`"?><html xmlns=`"http://www.w3.org/1999/xhtml`"><head><title>Capítulo $n</title></head><body><h1>Capítulo $n</h1>" + ((1..30 | ForEach-Object { "<p>$para</p>" }) -join '') + '</body></html>' }

New-Item -ItemType Directory (Split-Path $Out) -Force | Out-Null
if (Test-Path $Out) { Remove-Item $Out }
$fs = [IO.File]::Create($Out)
$zip = New-Object IO.Compression.ZipArchive($fs, 'Create')
Add-Entry $zip 'mimetype' 'application/epub+zip'
Add-Entry $zip 'META-INF/container.xml' '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'
Add-Entry $zip 'OEBPS/content.opf' '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Livro de Teste</dc:title><dc:creator>Folio</dc:creator></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>'
Add-Entry $zip 'OEBPS/c1.xhtml' (& $ch 1)
Add-Entry $zip 'OEBPS/c2.xhtml' (& $ch 2)
$zip.Dispose(); $fs.Dispose()
"OK: $Out"
