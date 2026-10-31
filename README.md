# Folio

Leitor de e-books estilo Kindle para tablets Android antigos (API 25), em Java nativo, sem Gradle e sem bibliotecas externas.
Launcher/quiosque com biblioteca, leitor EPUB/PDF/TXT/FB2/CBZ/HTML, metas de leitura, Folio Transfer (Wi-Fi, com QR code),
central de controle, busca global, gerenciador de apps e Paper Motion.

## Compilar

`powershell -ExecutionPolicy Bypass -File build.ps1` (precisa do Android SDK em `C:\Android\Sdk` e JDK 17). Gera `build\folio.apk`.

- O número de build vem de `build-number.txt` e sobe a cada compilação.
- O APK é assinado com `debug.keystore` (criado no primeiro build, fora do Git). Guarde uma cópia: atualizações só funcionam com a mesma assinatura.
- O PIN de saída do quiosque vem de `pin.txt` (fora do Git). Se não existir, o build cria um de 6 dígitos aleatórios.

## Atualizar

- **Pelo tablet:** Ajustes → Atualizações do Folio → "Verificar no GitHub" baixa a última release (tag `build-<versionCode>`) e abre o instalador do Android.
- **Por APK:** a mesma tela instala um APK escolhido no armazenamento.
- **Pelo computador:** `adb install -r build\folio.apk`.

Publicar uma versão: compilar e rodar `gh release create build-<N> <apk> --title "Folio 0.2 · build <N>"`.
Livros, progresso e ajustes são mantidos, desde que o APK tenha a mesma assinatura e um build maior.

Dev: Samuel Lima
