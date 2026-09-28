# Folha

Editor Android offline para `.txt` e `.md`. Uma tela de texto, uma barra discreta e arquivos sob controle do usuário. Sem contas, anúncios, analytics, rede ou permissões amplas de armazenamento.

## Usar

Android 8.0 (API 26) ou superior. Instale `app/build/outputs/apk/debug/app-debug.apk`.

- **Novo texto** abre um buffer local, sem escolher destino.
- **Abrir arquivo** usa o seletor do Android. Selecione `.txt` ou `.md` em UTF-8.
- No menu: salvar, salvar como, localizar, foco, informações, compartilhar e tema.
- Em **Salvar como**, escolha `.txt` ou `.md` e depois nome/destino no seletor.
- **Modo Foco** esconde a barra. Use o gesto/botão **Voltar** para sair. O primeiro Voltar com teclado aberto pode apenas fechar o teclado, conforme o Android.
- Teclado físico: `Ctrl+S`, `Ctrl+Shift+S`, `Ctrl+F`; seleção, copiar/colar e edição são nativos.
- O último buffer é recuperado ao reiniciar; isso não grava silenciosamente no arquivo original. Ao voltar para a tela inicial, alterações exigem salvar, descartar ou cancelar.
- Arquivos recentes dependem de o provedor manter o URI acessível. Acesso revogado: abra novamente pelo seletor. O × remove somente o atalho.

## Stack e build

Kotlin 2.2.20, Compose BOM 2026.09.00, Material 3, AGP 9.4.0, Gradle Wrapper 9.8.0, JDK 17. `minSdk 26`, `compileSdk/targetSdk 37`, Build Tools 36.0.0. Versões estáveis fixadas para reprodutibilidade; não é necessário usar as versões mais recentes.

Instale JDK 17 e Android SDK Command-line Tools/Android Studio, aceite as licenças e instale:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
export JAVA_HOME=/caminho/para/jdk-17
export ANDROID_HOME=/caminho/para/Android/Sdk
# Alternativa ao ANDROID_HOME: sdk.dir=... no local.properties (não versionado).
./gradlew assembleDebug
./gradlew test
./gradlew lint
# Todos os checks locais:
./scripts/validate.sh
# Com um aparelho/emulador conectado:
./gradlew installDebug
adb shell am start -n dev.folha/.MainActivity
./gradlew connectedDebugAndroidTest
```

O primeiro build precisa de internet para obter dependências; **o aplicativo funciona offline**. O wrapper verifica SHA-256 da distribuição Gradle. A chave debug é criada localmente pelo Android SDK e não é versionada.

Relatórios: `app/build/reports/tests/`, `app/build/reports/lint-results-debug.html` e `app/build/reports/androidTests/connected/`.

## Arquitetura

Um módulo, sem camadas cerimoniais:

```text
app/src/main/java/dev/folha/
  MainActivity.kt             intents e checkpoint no lifecycle
  editor/Document.kt         estado imutável, nomes, busca e contagem
  editor/EditorViewModel.kt   única fonte de estado e operações assíncronas
  files/DocumentFiles.kt      SAF, grants, verificação de escrita e share
  files/TextCodec.kt          UTF-8 estrito e limites
  files/DraftStore.kt         snapshot atômico local, ordenação e fallback
  preferences/Preferences.kt tema e recentes locais
  ui/                        Compose/Material 3 + EditText integrado
```

Compose controla a interface. O `EditText` nativo usa `AndroidView` para seleção, IME, rolagem e teclado físico maduros. O texto reside no ViewModel; não vai para `Bundle`, evitando `TransactionTooLargeException`. Cada instância do campo recebe o estado inicial uma vez; a edição nativa atualiza o ViewModel por callbacks. Recomposições nunca reaplicam texto/seleção no Editable em uso: isso evita que um snapshot atrasado apague teclas recentes. Busca envia uma seleção explícita; novos documentos recebem uma nova instância. Uma futura ação Substituir deverá usar um comando explícito, não reaplicar snapshots a cada frame.

### Integridade e privacidade

- UTF-8 estrito; caracteres inválidos e conteúdo binário são rejeitados. BOM e quebras de linha existentes são preservados. Enter adiciona LF; um arquivo CRLF pode ficar misto após novas linhas, sem normalização destrutiva das linhas existentes.
- Limite de **2 MiB por arquivo**. Conteúdo acima do limite é rejeitado antes de abrir o destino para escrita. Inserções acima do limite de 2.097.152 unidades UTF-16 são rejeitadas por inteiro; textos multibyte podem atingir o limite de arquivo antes desse limite de edição.
- Dirty state compara texto atual e baseline salvo; desfazer até o texto original remove o indicador.
- Recovery privado: snapshot atual e anterior em `files/drafts/`, escrita temporária, `fsync` e rename atômico. Sequência monotônica impede que um checkpoint antigo sobrescreva o mais novo. Cada edição agenda a persistência sem debounce; `onPause` conclui um snapshot sincronamente. Há uma pequena janela inevitável entre uma tecla e a escrita em disco em caso de morte abrupta no primeiro plano. Falhas de disco são visíveis.
- Antes de gravar o documento, recovery precisa estar durável. Escrita SAF usa `wt` para truncar conteúdo anterior; releitura confirma bytes. O baseline só muda após sucesso. Providers não oferecem uma transação atômica universal: falha durante a escrita pode deixar a origem parcial, mas o texto completo continua no recovery para **Salvar como**.
- Hash SHA-256 detecta mudança externa antes de salvar; conflito oferece salvar cópia, preservando ambas as versões. A verificação não elimina a corrida entre leitura e escrita de um provedor externo.
- Um snapshot limpo é atualizado da origem na reabertura; se a origem falhar, abre-se a cópia local com indicação de usar Salvar como.
- Grants persistentes somente quando fornecidos pelo provedor. Recentes limitados a 12; permissões que deixam de ser usadas são liberadas.
- `ACTION_VIEW`/`EDIT`/`SEND`: somente tipos `text/plain`, `text/markdown`, `text/x-markdown`; VIEW exige `content://`. Receber um arquivo por SEND importa um novo buffer, sem tentar gravar na origem temporária do compartilhamento. Não há associação global de MIME. O seletor interno mostra todos os tipos porque alguns provedores identificam `.md` como binário; a extensão e UTF-8 são validados depois da seleção.
- Compartilhamento cria um snapshot privado temporário via FileProvider, com grant somente de leitura. Inclui edições ainda não salvas. Snapshots antigos são limpos após 24 horas no próximo compartilhamento.
- Nenhuma permissão de rede/armazenamento. Backup em nuvem e transferência dos dados privados desativados. O recovery é local; limpar dados/desinstalar o app remove rascunhos, mas não os documentos salvos no SAF.

## Testes

`DocumentTest`: dirty/reversão, Unicode, BOM, CRLF, UTF-8 inválido, limite, nomes/extensões, recovery grande e corrompido, checkpoints fora de ordem, recentes, busca literal e contagem Unicode.

`FileSafetyTest` instrumentado: leitura e escrita via ContentResolver/FileProvider real, truncamento de textos menores, Save As nas duas extensões, conflito externo e proteção antes da escrita.

`PickerRecoveryTest`: retorno do seletor antes de concluir o recovery e importação de arquivo compartilhado como novo rascunho.

`EditorLifecycleTest` instrumentado: digitação rápida por eventos de teclado, edição Unicode com 5.000 repetições, seleção e recriação da Activity. O roteiro e as evidências da validação manual ficam em [`docs/qa/VALIDATION.md`](docs/qa/VALIDATION.md). Testes instrumentados devem usar emulador isolado, pois exercitam o buffer atual.

## Escopo e próximos passos

1. Ampliar testes em aparelhos físicos e APIs 26/37: teclado, acessibilidade, arquivos próximos de 2 MiB, linhas gigantes e provedores de diferentes fabricantes. Medir edição e checkpoint antes de aumentar limites.
2. Busca com realce de todas as ocorrências, substituir com confirmação e desfazer explícito. A busca atual é literal, ignora caixa e percorre o documento circularmente.
3. Preview Markdown somente leitura, com renderizador pequeno e mantido, sem recursos de rede e sem modificar o buffer. O preview receberá `Document.content`; edição e persistência permanecem independentes.

Também no roadmap: configuração para iniciar em casa/último documento, tamanho/família da fonte e múltiplos rascunhos. O app atualmente recupera o último buffer automaticamente. Sem preview, substituir ou sincronização na v0.1.

## Licença

Distribuído sob a licença MIT. Veja [`LICENSE`](LICENSE).

Referências: [compatibilidade do AGP](https://developer.android.com/build/releases/gradle-plugin), [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), [Compose BOM](https://developer.android.com/develop/ui/compose/bom).
