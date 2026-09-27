# Validação da v0.1

Ambiente: Debian x86_64, JDK 17 local, Android SDK 37.0 / Build Tools 36.0.0, AGP 9.4.0 / Gradle 9.8.0, emulador AOSP API 29 por software (sem /dev/kvm), 540×960 / 210 dpi. Não há aparelho físico conectado. As latências do emulador sem aceleração não representam desempenho em celular.

## Checks reproduzíveis

- `./gradlew assembleDebug`: APK gerado.
- `./gradlew test`: 14 testes por variante (debug/release), sem falhas.
- `./gradlew lint`: nenhum apontamento (`No issues found`).
- `./scripts/validate.sh`: executa todos os checks locais acima.
- `./gradlew connectedDebugAndroidTest`: **6 testes instrumentados aprovados, 0 falhas**, incluindo digitação por eventos de teclado, Unicode/seleção/lifecycle, integridade de arquivos e retorno antecipado do picker. Revalidados em 27/09/2026 às 20:09 CEST com a stack atualizada. Build + checks: 2m24s; a instrumentação usa emulador por software.

O Gradle reporta a depreciação interna `Configuration.setVisible(boolean)` no plugin Android; não é um erro do aplicativo. API 37 foi usada para compilar, mas ainda não foi testada em execução; o dispositivo de teste é API 29.

## Roteiro de aceite em aparelho

1. Instalar debug e abrir. Criar texto, acentos/emoji, várias linhas; selecionar, copiar, colar, mover cursor e editar usando IME e teclado físico.
2. Salvar como `.txt` via DocumentsUI; confirmar bytes fora do app. Editar para texto menor, salvar, confirmar que o sufixo antigo foi truncado.
3. Salvar como `.md`; reabrir pelas ações do app, recentes e gerenciador externo. Confirmar Markdown literal intacto.
4. Editar sem salvar, pressionar Home, matar processo e reabrir; confirmar texto e dirty; arquivo original deve permanecer igual.
5. Recriar Activity/rotacionar com texto grande e seleção; confirmar texto e seleção.
6. Sair com alterações: cancelar, salvar e descartar; cancelar seletor de destino também deve manter buffer.
7. Alterar origem externamente antes de salvar; verificar que conflito oferece cópia e não sobrescreve.
8. Revogar URI/remover arquivo; recente apresenta erro compreensível; buffer atual intacto.
9. Claro, escuro, sistema; reiniciar e confirmar preferência. Localizar texto, percorrer ocorrência e voltar ao início; busca sem resultados. Foco esconde barra e Voltar restaura.
10. Compartilhar snapshot pelo chooser, sem modificar a origem. Verificar editor após fechar chooser.
11. UTF-8 com BOM, LF, CRLF; arquivo binário/malformado/maior que 2 MiB deve ser recusado. Arquivo próximo ao limite e uma linha enorme precisam de medição em celular antes de elevar o limite.

## Limites da evidência

Os testes de ContentResolver usam o FileProvider real em armazenamento privado; a navegação do seletor deve ser verificada separadamente com DocumentsUI. Não equivalem a testes em todos os provedores de fabricantes ou serviços instalados pelo usuário.

A primeira tentativa de instrumentação ocorreu antes do boot completo e falhou ao instalar; uma tentativa posterior passou nos 4 testes de arquivos, mas foi bloqueada por um diálogo de ANR do **System UI do emulador**, antes de iniciar o teste visual. Essas tentativas não foram contadas como aprovação integral.

## Revisão crítica

A digitação real por `adb input text` revelou que a realimentação contínua do texto do Compose para o EditText podia reaplicar snapshots antigos durante teclas rápidas. A ponte foi corrigida: inicializa o campo uma vez por instância/documento e propaga edições ao ViewModel; recomposição não escreve no Editable. O teste de lifecycle agora injeta uma frase por eventos de teclado e exige igualdade completa, além do teste Unicode grande e da seleção após recriação.

Outras correções da revisão: retorno do picker aguarda recovery e verifica ID do documento; SEND importa uma cópia sem URI permanente; snapshots limpos consultam origem ao reiniciar; colagem acima do limite é rejeitada inteira; o encoder não grava conteúdo NUL que a leitura posteriormente recusaria; grants sem uso são liberados sem concorrência com novas aberturas; tema nativo acompanha claro/escuro; backups privados desativados.

## Aceite manual realizado no APK atualizado

Sessão em 27/09/2026, emulador AOSP API 29. Ações por ADB com coordenadas obtidas da árvore UI Automator; capturas inspecionadas visualmente.

| Fluxo | Resultado observado |
| --- | --- |
| Instalação e novo texto | APK instalado; buffer vazio criado sem destino obrigatório |
| Digitação multilinha | `Folha teste 123` + LF + `Segunda linha` preservados integralmente |
| Salvar como TXT e MD | Ambos criados em Downloads pelo DocumentsUI; cópias extraídas e comparadas: os mesmos 29 bytes UTF-8 esperados |
| Recovery real | Acrescentado ` PENDENTE`, Home, `am force-stop`, reabertura: os 38 caracteres retornaram com indicador de não salvo; origem continuou com 29 bytes |
| Salvar arquivo existente | Edição recuperada gravada no mesmo MD; os 38 bytes foram confirmados fora do app |
| Recentes e abertura | TXT reaberto pelos recentes com texto exato; MD reaberto pelo seletor com texto exato |
| Claro e escuro | Editor inspecionado nos dois temas; preferência DARK confirmada em SharedPreferences; instalação limpa iniciou seguindo o sistema |
| Localizar | `teste` selecionado corretamente no texto, sem modificar o buffer |
| Foco | Editor mantido, barra/menu ausentes; Voltar restaurou o menu |
| Informações | 38 caracteres, 6 palavras e 2 linhas, conforme o conteúdo |
| Compartilhar | Chooser Android exibiu o arquivo MD e destinos; nenhum envio realizado |
| Saída com alterações | Voltar apresentou Salvar/Descartar/Cancelar; Cancelar manteve o `!` pendente; Descartar retornou à home e preservou o MD salvo com 38 bytes |
| Associações de arquivos | Resolução de VIEW para `text/markdown` encontrou Folha; `image/png` encontrou Gallery, sem Folha |
| Crashes | Buffer de crash do logcat vazio ao fim dos testes |

Capturas: [home](home-light.png), [recentes](recents-light.png), [editor claro](editor-light.png), [editor escuro](editor-dark.png), [busca](search-dark.png), [foco](focus-dark.png).

Resumo dos checks e SHA-256 do APK testado: [instrumentation.txt](instrumentation.txt).

Permanecem pendentes: aparelho físico, execução nas APIs 26 e 37, teclado físico real, outros IMEs/provedores, medição com arquivos próximos de 2 MiB e linhas gigantes. A validação em emulador por software não sustenta afirmações de desempenho em celular.
