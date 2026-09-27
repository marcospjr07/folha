# Folha: instruções para continuar

- Editor de texto Android, offline e privado. UX sóbria; a maior parte da tela pertence ao texto.
- Kotlin, Compose/Material 3, um módulo. Campo nativo EditText em AndroidView é intencional: preserve seleção, IME e suporte a textos longos.
- `EditorViewModel` é a única fonte de estado. `Document` guarda conteúdo e baseline. `DocumentFiles` lida com SAF; `DraftStore` com recovery. Não crie UseCases/Repositories/interfaces sem uma necessidade concreta.
- Build: JDK 17 + SDK 37/Build Tools 36.0.0; `./gradlew assembleDebug`.
- Validação local: `./scripts/validate.sh` (= `./gradlew test lint assembleDebug`). Dispositivo: `./gradlew connectedDebugAndroidTest` em emulador isolado.
- APK: `app/build/outputs/apk/debug/app-debug.apk`. Consulte `docs/qa/VALIDATION.md` antes de afirmar cobertura em aparelho.
- Priorize testes que protegem dados: UTF-8, dirty, recovery, corrida de snapshots, salvamento, URI revogado e conflitos. Teste também seleção/IME após mudar o editor.
- Nunca atualize baseline antes de a gravação ser confirmada. Recovery deve ser durável antes de truncar destino. Falhas não podem apagar ou substituir o buffer.
- Preserve bytes UTF-8, BOM e quebras existentes; não normalize Markdown. Não guarde texto grande em Bundle/SavedStateHandle.
- I/O normal em Dispatchers.IO. Exceção documentada: checkpoint síncrono e ordenado em onPause para sobreviver à morte do processo.
- Não faça autosave silencioso na origem. Grants SAF só para documentos escolhidos. Não peça acesso amplo ao armazenamento.
- Não adicione rede, analytics, publicidade, login, nuvem, banco de dados, DI, navegação ou dependências externas sem justificativa real.
- UI em português, identificadores Kotlin em inglês. Estilo Kotlin idiomático, arquivos por responsabilidade.
- Respeite dados existentes do usuário e evite limpar dados de um aparelho real durante testes.
- Não versionar local.properties, SDK/JDK, builds, secrets ou keystores. Atualize README e validação quando mudar limites/comportamento.
