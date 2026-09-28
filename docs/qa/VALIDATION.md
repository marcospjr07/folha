# Validação da v0.1

## Refinamento de scroll e IME — setembro de 2026

Escopo: somente navegação do editor e regressões de seleção/IME. A árvore estava limpa no início da tarefa; as alterações pendentes foram preservadas na retomada. O relatório anterior ao refinamento final registra 10/10 testes com `NestedScrollView`; aprovação funcional não foi usada como evidência de desempenho.

### Causa e alternativas

O caminho de toque do `EditText` usa `ArrowKeyMovementMethod`/`Touch`: arrasta, mas não implementa fling. `TextView.onTouchEvent` pode solicitar IME ao receber `ACTION_UP` mesmo após arrastar. `imePadding` apenas redimensiona a área disponível; não havia interceptador Compose causando o problema.

`NestedScrollView` + `WRAP_CONTENT` resolve fling e cancela o toque do filho quando assume o drag. Porém, permite que a View do editor cresça até a altura de todo o documento. A alternativa `EditorViewport` mantém `MATCH_PARENT`, intercepta somente navegação vertical após `touchSlop` e respeita `requestDisallowInterceptTouchEvent` da seleção nativa. `GestureDetector` mede a velocidade e `OverScroller` aplica a física Android via `computeScroll`/frames. Não há timer, cópia de conteúdo, escrita no ViewModel ou checkpoint durante rolagem. Um novo toque interrompe a inércia na posição corrente; os extremos são limitados ao conteúdo, sem efeito elástico customizado.

Foco e `showSoftInputOnFocus` não são alternados. Tap, long press, handles, copiar/colar e acessibilidade continuam no `EditText`; o parent só recebe o gesto de navegação. Busca/seleção, mudança de tamanho, perda de foco da janela e remoção da View interrompem a inércia.

Referências de implementação: [Touch/AOSP](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/text/method/Touch.java), [interceptação em ViewGroup](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup), [GestureDetector e OverScroller](https://developer.android.com/develop/ui/views/touch-and-input/gestures/scroll).

### Comparação reproduzível de layout

Diagnóstico opt-in, separado das asserções funcionais:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.folha.EditorGestureTest#nearLimitDocumentScrollDoesNotEditBufferAndCaretCanBeRevealed \
  -Pandroid.testInstrumentationRunnerArguments.layoutProbe=true
adb -s emulator-5554 logcat -d -s FolhaLayoutProbe:I
```

O probe usa o mesmo texto de 2.092.500 bytes UTF-8 e a mesma tipografia/padding em ambas as disposições, nas ordens bounded/wrap e wrap/bounded. Mede tempo até concluir o primeiro `dispatchDraw`, 12 desenhos com reposicionamento visual e PSS. Não mede apresentação de frames pela GPU ou FPS. Não inclui SAF, recovery nem Compose no trecho medido, para isolar layout/renderização. PSS é uma fotografia do processo, com efeitos de GC/caches entre rodadas; não equivale a memória retida atribuível exclusivamente ao widget. Não há limiar de tempo usado para fabricar aprovação de desempenho.

Resultado em 28/09/2026, mesmo emulador e APK debug, duas rodadas por alternativa:

| Medida | EditText limitado + OverScroller | NestedScrollView + WRAP_CONTENT |
| --- | --- | --- |
| Altura do editor / viewport | 960 / 960 px | 2.295.073 / 960 px |
| Primeiro `dispatchDraw` | 14 e 6 ms | 11.312 e 11.980 ms |
| Construção/layout até concluir primeiro desenho | 45.269 e 45.938 ms | 53.988 e 53.853 ms |
| PSS total do processo observado | 126.671 e 120.978 KiB | 176.593 e 201.524 KiB |
| PSS nativo observado | 18.041 e 16.277 KiB | 72.418 e 96.138 KiB |

Dados brutos, incluindo todos os tempos de desenho: [editor-layout-probe.txt](editor-layout-probe.txt). Os reposicionamentos são programáticos para isolar desenho; a aprovação de fling vem dos eventos reais dos testes de gesto. Depois de preencher o cache, a versão expandida desenhou em 0–14 ms; portanto, não se afirma que o campo limitado vence em toda operação. A regressão relevante é o grande custo inicial de desenho e o crescimento da View/cache para milhões de pixels. A escolha foi **campo limitado + OverScroller**, mantendo nativos o buffer, a seleção, IME e física de fling. O layout inicial de 67.500 linhas continua caro; esses tempos absolutos de emulação sem aceleração não representam um celular nem uma promessa de abertura instantânea.

### Regressões e roteiro

`EditorGestureTest` cobre swipe com IME fechado, tap/foco/cursor/IME real, scroll com IME aberto, Voltar seguido de scroll e novo tap, movimento após `ACTION_UP`, interrupção por toque, texto curto, long press/cópia/cola nativos e documento próximo de 2 MiB com busca/seleção perto do fim. A identidade de `Document` é conferida durante navegação: scroll não emite alterações de texto ou seleção. O teste existente de lifecycle preserva Unicode, digitação e seleção após recriação.

Executar os testes em emulador isolado com teclado virtual habilitado. Os testes observam `WindowInsetsCompat.Type.ime()`, não uma flag simulada nem uma heurística de altura. O probe é diagnóstico e não substitui a execução normal dos dez testes.

Em 28/09/2026, no AOSP API 29 por software (540×960, 210 dpi), `./gradlew test lint assembleDebug connectedDebugAndroidTest` passou em 3m59s: **10/10 instrumentados, 14 unitários por variante debug/release e lint `No issues found`**. Resultado completo: [editor-instrumentation.xml](editor-instrumentation.xml). A execução aprovada verificou IME real, fling, seleção, copiar/colar, documento de 2.092.500 bytes, digitação/Unicode/recriação, arquivos e recovery. O logcat do processo aprovado não contém crashes/exceções relacionados ao editor/touch/IME.

Uma tentativa anterior foi invalidada por um diálogo de ANR do `com.android.systemui`, registrado durante o boot do emulador às 01:16:09. O diálogo cobria o app e recebia os gestos; a instrumentação foi interrompida, o diálogo removido e a suíte integral repetida. Essa tentativa não foi contada como aprovação nem usada para alterar expectativas do teste de fling.

Inspeção manual desta alteração: documento de 300 linhas, swipes lento/rápido com IME fechado, tap para abrir IME e rolagem com IME aberto. A captura de leitura mostra o documento avançado até as linhas 13–23 com teclado fechado; a captura posterior mostra as linhas 16–20 com teclado aberto. Coordenadas vieram da árvore UI Automator. Capturas: [leitura](editor-scroll-reading.png) e [teclado](editor-scroll-keyboard.png). Fling após soltar, interrupção por toque, fechar/reabrir IME, long press/cópia/cola, seleção distante e recriação foram confirmados pelos testes instrumentados.

Limites desta rodada: a inspeção manual completa de Modo Foco, busca pela barra, duplo toque e arraste dos handles não foi repetida antes do encerramento; o emulador já estava desligado na retomada final. A seleção distante/`bringPointIntoView` foi testada, mas não equivale ao fluxo inteiro da barra de busca. Permanecem pendentes aparelho físico, Gboard/Samsung Keyboard, TalkBack, atalhos em teclado físico e APIs 26/37. Os checks aprovados acima correspondem ao código entregue; não se afirma que todo o roteiro manual abaixo foi executado nesta rodada.

APK validado: `app/build/outputs/apk/debug/app-debug.apk`; SHA-256 `8389ba465b0a2452a3e76e0982443fdf963f61bebfb1a7fe99b706ba874ede81`.

Roteiro manual: abrir texto longo; arrastar lentamente e fazer swipes rápidos nos dois sentidos; interromper fling com toque; fechar IME e navegar repetidamente; tocar uma linha para posicionar cursor e abrir IME; rolar com IME aberto; selecionar por long press/duplo toque, mover handles, copiar e colar; ativar/desativar Foco; buscar ocorrência distante; recriar/rotacionar; conferir conteúdo e seleção. Verificar também extremos e documento curto. Em aparelho: repetir com Gboard/Samsung Keyboard, TalkBack e teclado físico/Ctrl+S/Ctrl+Shift+S/Ctrl+F.

Ambiente: Debian x86_64, JDK 17 local, Android SDK 37.0 / Build Tools 36.0.0, AGP 9.4.0 / Gradle 9.8.0, emulador AOSP API 29 por software (sem /dev/kvm), 540×960 / 210 dpi. Não há aparelho físico conectado. As latências do emulador sem aceleração não representam desempenho em celular.

## Histórico dos checks da primeira versão — 27/09/2026

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
