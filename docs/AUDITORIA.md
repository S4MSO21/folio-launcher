# Folio: auditoria técnica (Fase 1, build 4)

Java nativo, sem Gradle (`build.ps1`), minSdk 25, targetSdk 22. Funcionalidades existentes e verificadas no emulador API 25:

| Área | Estado |
|---|---|
| Launcher/quiosque | Home padrão, device owner, lock task, saída por 5 toques no canto + PIN |
| Biblioteca | Grade/lista, capas, favoritos, coleções, busca por título/autor, filtros |
| Leitor | EPUB/TXT/FB2/HTML (WebView), PDF (PdfRenderer), CBZ; barras flutuantes, painel Aa, marcadores, progresso por âncora |
| Dados | SQLite `folio.db` v4 (livros, progresso, sessões, marcadores, coleções) |
| Progresso | Metas, sequência, gráfico de 7 dias |
| Importação | SAF + ACTION_SEND, validação, deduplicação por hash |
| Folio Transfer | HTTP local, código de pareamento, confirmação por arquivo |
| Design | Paper Glass (3 temas), Paper Motion (Eco/Equilibrado/Premium) |
| Novo no build 4 | Ícones de status (Wi-Fi, bateria, SD), brilho, rodapé de versão, aba Apps (busca, fixar, ocultar só no Folio) |

Lacunas para o Folio OS 2.0: busca global, central de controle, tela Home com relógio/atalhos configuráveis, atualização do Folio, testes em Android 11, `<queries>` ao subir o targetSdk.
