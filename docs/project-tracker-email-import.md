# Импорт писем в виджет активностей проекта (ecos-project-tracker)

## Контекст

В `ecos-project-tracker` нужно добавить возможность загружать входящие письма в виджет активностей проекта — аналогично CRM, где письма автоматически превращаются в `email-activity` и вложения. Требуются **два сценария**:

1. **Общий ящик** — один на всё приложение; маршрутизация конкретному проекту по ключу проекта (`KEY` или `KEY-N`) в subject письма.
2. **Индивидуальный ящик на проект** — опционально включается на карточке проекта.

Подход — скопировать архитектуру `ecos-ecom` (Camel route + `ecos-config`), так как sync `emailprocessing` в `ecos-integrations` не используется ни одним живым потребителем, а у ecom-варианта уже есть готовые утилиты (`MailBodyExtractor`, attachment-пайплайн). Тип активности **переиспользуется** — `emodel/type@email-activity` уже существует в ядре платформы (потомок `planned-activity` → `ecos-activity`), новый подтип не вводится.

## Итоговая архитектура

```
┌─────────────────────────── ecos-ecom ─────────────────────────┐
│                                                                │
│  ReadMailboxPTRoute (статический)                              │
│   └─ @EcosConfig("mail-inbox-pt") ─ объект                     │
│       {enabled, imap, credentials(ref), folder, …}             │
│   └─ buildImapUri() подтягивает username/password              │
│      из ecos-credentials под runAsSystem                       │
│   └─ .bean(MailBodyExtractor, "extract(*)")                    │
│   └─ .process(ReadMailboxPTProcessor)  ──┐                     │
│                                          │                     │
│  ReadProjectMailboxesRoute (timer 60s)   │                     │
│   └─ from("timer:...?period=60000")      │                     │
│   └─ .process(PollProjectMailboxesProcessor) ──┐               │
│       for each enabled project:                │               │
│       ConsumerTemplate.receiveBody(uri, 5000)  │               │
│                                                │               │
│                       ┌────────────────────────┴───┐           │
│                       ▼                            ▼           │
│               ProjectEmailImportService (общий обработчик)     │
│                       │                                        │
│                       ▼                                        │
│     RecordsService → transactional:                            │
│       - email-activity (всегда, к проекту)                     │
│       - ecos-comment (если найден KEY-N, к issue)              │
│       - attachments (ecos-type@attachment)                     │
└────────────────────────────────────────────────────────────────┘

┌──────────────────── ecos-project-tracker ─────────────────────┐
│                                                                │
│  model/aspect/ept-project-mailbox.yml     (новый)              │
│  model/type/project/project.yml           (добавить aspect)    │
│  ui/form/project-form.json                (секция «Почта»)     │
└────────────────────────────────────────────────────────────────┘

┌──────────────────── ecos-ecom (aspect email-atts) ────────────┐
│                                                                │
│  model/aspect/email-atts.yml              (новый)              │
│  app/artifact-patch/…                     (apply to            │
│                                            emodel/type@email-  │
│                                            activity)           │
└────────────────────────────────────────────────────────────────┘
```

## Хранение конфигурации

### Aspect `ept-project-mailbox` на типе `project`

Операционные настройки per-project ящика:

- `mailboxEnabled: BOOLEAN`
- `mailboxImap: TEXT` (`imaps://host:port`, **без** credentials)
- `mailboxFolder: TEXT` (по умолчанию `INBOX`)
- `mailboxSuccessFolder: TEXT`, `mailboxErrorFolder: TEXT`
- `mailboxCredentials: ASSOC → emodel/type@ecos-credentials`
- `mailboxLastSync: DATETIME`, `mailboxLastError: TEXT` (сервисные, read-only в форме)

`mailboxDelay` **не заводим** — при реализации через общий timer (период фиксирован в route) per-project задержка смысла не имеет.

### ecos-config `mail-inbox-pt` (глобальный ящик)

Используем паттерн `valueDef` (как `default-content-storage`), чтобы хранить структурированное значение с ASSOC-ссылкой на credentials и иметь отдельную форму редактирования.

```yaml
id: mail-inbox-pt
name:
  ru: Настройка почтового ящика Project Tracker
  en: Project Tracker mailbox config
value:
  enabled: false
  imap: ""
  credentials: null
  folder: "INBOX"
  successFolder: ""
  errorFolder: ""
  delay: 60000
valueDef:
  formRef: uiserv/form@config-mail-inbox-pt
  attributes:
    - id: enabled
      type: BOOLEAN
    - id: imap
      type: TEXT
    - id: credentials
      type: ASSOC
    - id: folder
      type: TEXT
    - id: successFolder
      type: TEXT
    - id: errorFolder
      type: TEXT
    - id: delay
      type: NUMBER
```

Форма `config-mail-inbox-pt` — `selectJournal` для `credentials` (`journalId: ecos-credentials`).

### ecos-credentials (SecureRecord)

Логин и пароль живут в существующем `emodel/type@ecos-credentials` (`sourceId: integrations/credentials`). Пароли шифруются платформой. В runtime читаются под `AuthContext.runAsSystem`:

```kotlin
val atts = AuthContext.runAsSystem {
    recordsService.getAtts(credsRef, listOf("username", "password"))
}
val username = atts.getAtt("username").asText()
val password = atts.getAtt("password").asText()
```

Копировать `BasicCredentials` DTO из `ecos-integrations` не нужно — прямое чтение двух полей за один round-trip.

### Aspect `email-atts` (применяется к `emodel/type@email-activity`)

Для хранения метаданных письма, пригодных для query/дедупа. Определяется в ecos-ecom, применяется к существующему типу `email-activity` через `artifact-patch`:

- `emailFrom: TEXT`
- `emailTo: TEXT`
- `emailCc: TEXT`
- `emailSubject: TEXT`
- `emailMessageId: TEXT` — для дедупликации
- `emailInReplyTo: TEXT` — для будущего тредирования (сохраняем, не используем)
- `emailReceivedAt: DATETIME`

Применяется к общему типу `email-activity`, поэтому CRM-активности тоже получат эти (пока пустые) поля. Обратная совместимость — полная (все поля опциональны).

## Изменения по файлам

### ecos-project-tracker

- **`src/main/resources/eapps/artifacts/model/aspect/ept-project-mailbox.yml`** — новый aspect с атрибутами выше.
- **`src/main/resources/eapps/artifacts/model/type/project/project.yml`** — добавить `- ref: emodel/aspect@ept-project-mailbox`.
- **`src/main/resources/eapps/artifacts/ui/form/project-form.json`** — секция «Почта» с полями aspect + переключатель `mailboxEnabled` + visibility: только для роли `responsible`/`administrator`. Credentials — `selectJournal` `ecos-credentials`.

### ecos-ecom

- **`src/main/resources/eapps/artifacts/app/config/mail-inbox-pt.yml`** — ecos-config с `valueDef` (см. выше).
- **`src/main/resources/eapps/artifacts/ui/form/config-mail-inbox-pt.json`** — форма редактирования конфига со `selectJournal` для `credentials`.
- **`src/main/resources/eapps/artifacts/model/aspect/email-atts.yml`** — новый aspect.
- **`src/main/resources/eapps/artifacts/app/artifact-patch/email-activity-add-email-atts.yml`** — patch, добавляющий `email-atts` в список aspects типа `emodel/type@email-activity`.
- **`src/main/java/ru/citeck/ecos/ecom/routes/ReadMailboxPTRoute.java`** — аналог `ReadMailboxCRMRoute`, `@EcosConfig("mail-inbox-pt")` возвращает объект, `buildImapUri()` дёргает credentials под `runAsSystem`, далее `.bean(MailBodyExtractor,…).process(ReadMailboxPTProcessor)`.
- **`src/main/java/ru/citeck/ecos/ecom/processor/ReadMailboxPTProcessor.java`** — парсит subject/from/body из Camel Exchange, делегирует `ProjectEmailImportService`.
- **`src/main/java/ru/citeck/ecos/ecom/routes/ReadProjectMailboxesRoute.java`** — `from("timer:pt-project-mailboxes?period=60000&delay=10000").process(pollProcessor)`.
- **`src/main/java/ru/citeck/ecos/ecom/processor/PollProjectMailboxesProcessor.kt`**:
  1. `recordsService.query` по `emodel/type@project` с предикатом `mailboxEnabled=true`.
  2. Для каждого проекта (в try/catch): `ConsumerTemplate.receiveBody(buildImapUri(project), 5000)` в цикле до `null` → `ProjectEmailImportService.import(project, email)` → move в successFolder/errorFolder через Camel headers (`copyTo` + `delete=true`), если folder указан.
  3. Обновить `mailboxLastSync` / `mailboxLastError` на проекте.
  4. Одна ошибка на проекте не блокирует обработку остальных.
- **`src/main/java/ru/citeck/ecos/ecom/service/ProjectEmailImportService.kt`** — общая логика импорта (детали ниже, раздел «Маршрутизация»).

### Переиспользуемые утилиты (не меняются)

- `MailBodyExtractor`, `EcomCamelMailUtils` в `ru.citeck.ecos.ecom.service.cameldsl`.
- `AddEmailActivityProcessor` — как образец создания `email-activity` и обработки вложений.
- `EcosContentApi`, `RecordsService`, `AuthContext`.

## Маршрутизация письма

### Извлечение ключа проекта из subject

1. **Strip префиксов** (case-insensitive, циклом пока матчит начало):
   - `Re:`, `Fwd:`, `Fw:`, `Ответ:`, `Переслано:` (+ любые пробелы вокруг).
   - Это покрывает и пересылку письма пользователем, когда ящик не был в копии.
2. **Regex извлечения кандидатов** (все матчи подряд): `\[?([A-Z][A-Z0-9]{1,9})(?:-(\d+))?\]?` — 2–10 символов UPPERCASE (нижний регистр не матчит намеренно — стабильный контракт).
3. **Валидация кандидатов по БД** (вместо жёстких regex-эвристик):
   - Если есть номер `KEY-N` → query `emodel/type@ept-issue` с predicate `eq issueKey "KEY-N"`.
   - Если чистый `KEY` → query `emodel/type@project` с predicate `eq key "KEY"`.
   - **Issue-матч приоритетнее project-матча** (даже если идёт позже в subject).
   - **Fallback:** если `KEY-N` указан, но такой issue не существует, а проект с ключом `KEY` есть — используем проект + warn в лог («issue KEY-N не найдена»).
4. **Несколько валидных матчей** → первый выигрывает, warn с перечислением найденных.
5. **Ни одного валидного матча** → errorFolder + warn с subject/sender.

Ложные срабатывания вроде `OK`, `FYI` устраняются автоматически — такого проекта в БД не найдётся.

### Кеш

`ConcurrentHashMap<String, CacheEntry>` с TTL 60 секунд:
- ключ — строка кандидата (`KEY` или `KEY-N`)
- значение — `projectRef`, `issueRefOrNull`, `expiresAt`

Защищает от N × query на каждое письмо при всплесках входящей почты.

### Создание активности и комментария

Импорт — **транзакционная** операция:

```kotlin
recordsService.doWithTxn {
    AuthContext.runAsSystem {
        // 1. Дедуп: query email-activity by emailMessageId
        if (existsByMessageId(messageId)) return@runAsSystem

        // 2. Загрузить проект для issue (источник истины)
        val projectRef = issueRef
            ?.let { recordsService.getAtt(it, "link-project:project?id").asText() }
            ?.toEntityRef()
            ?: candidateProjectRef

        // 3. Создать email-activity c _parent=projectRef,
        //    _parent_att="has-ecos-activities:ecosActivities"
        //    и полями aspect email-atts (emailFrom/To/Subject/MessageId/…)
        createEmailActivity(projectRef, email, messageId)

        // 4. Если issueRef != null — создать ecos-comment
        //    (record=issueRef, text=body)
        issueRef?.let { createInternalComment(it, email) }
    }
}
```

Вся операция в одной транзакции — либо и активность, и комментарий созданы, либо ничего. Retry после фейла заново пройдёт дедуп (messageId нет → re-create).

**Дедуп только на активности**, комментарий отдельно не тегируется Message-ID (не требуется из-за транзакции).

### Согласованность issue ↔ project

Если issue `KEY-N` привязан к проекту, отличному от распознанного из subject — **источник истины — `link-project:project` у issue**. Активность создаётся у этого проекта, в лог — warn с обоими ключами.

### Обработка пересылок

- `Fwd:`/`Fw:`/`Переслано:` снимаются strip-префиксом → regex находит ключ в остатке subject.
- `emailFrom` = адрес пересылающего (не оригинального отправителя).
- `emailMessageId` = внешний (forwarded) Message-ID. Повторные осознанные пересылки одного письма создают разные активности — это ожидаемо.
- Body — as-is, включая quoted-блок оригинала.
- **Ключ ищется только в subject.** Тело не парсим.

### Вложения

- Каждое вложение загружается как `ecos-type@attachment` через `EcosContentApi`.
- Ссылки встраиваются в тело активности как inline-JSON `lexical-file-node` (как в `AddEmailActivityProcessor`).
- **Лимит размера — 10 МБ** на вложение (дефолт). Конфигурируется через `mail.attachment.max-size-mb` в `application.yml` (`@Value` инъекция в `ProjectEmailImportService`). Превышение — skip этого вложения, warn в лог, активность создаётся с остальными.

## Безопасность

- Пароли IMAP — **только** через `emodel/type@ecos-credentials` (SecureRecord) для обоих сценариев (глобального и per-project). Поля `mailboxPassword` / password-in-URL не заводим.
- IMAP URL валидируется при сохранении формы: разрешены только `imap://` / `imaps://`.
- Блок «Почта» на форме проекта — видим только для `responsible`/`administrator`.
- Форма конфига `mail-inbox-pt` доступна только администраторам (через journal-селектор credentials виден только `name`, сам пароль не отображается).

## Изоляция ошибок и идемпотентность

- **Дедупликация** по `Message-ID` защищает от retry/рестартов.
- **Транзакционность** импорта — активность и комментарий атомарны.
- **Per-project try/catch** в timer-route: один сломанный ящик не блокирует остальные.
- **Таймаут** `ConsumerTemplate.receiveBody(endpoint, 5000)` ограничивает залипание одного ящика.
- Глобальный Camel `.onException(Exception).handled(true)` в route builder — route не падает при непредвиденном exception.
- **Lock между репликами ecos-ecom не делаем (MVP, паттерн CRM).** Тех-долг: при переходе на 2+ реплики — мигрировать на `PollingConsumerPollStrategy` + `EcosAppLockService` (заодно для CRM/SD).

## Тестирование

### 1. Unit-тесты (пишутся параллельно с реализацией)

**`ProjectEmailImportServiceTest`** (ecos-ecom, mock `RecordsService`, `EcosContentApi`):

| # | Сценарий | Ожидание |
|---|---|---|
| U1 | subject `[PRJCTMNG] ...`, проект существует | создана `email-activity` у PRJCTMNG, `parent_att=has-ecos-activities:ecosActivities`, поля `email-atts` заполнены |
| U2 | subject `PRJCTMNG-33: ...`, issue существует | создана активность у проекта issue **+** `ecos-comment` у issue PRJCTMNG-33 (обе записи в одной транзакции) |
| U3 | тот же `Message-ID` импортируется повторно | вторая активность не создаётся, комментарий тоже не создаётся (транзитивно) |
| U4 | subject без распознаваемого ключа или ключи не резолвятся в БД | `EmailRoutingException` → errorFolder |
| U5 | `KEY-N`, issue `KEY-N` существует, но привязан к другому проекту | активность у проекта из `link-project`, **не** из subject; warn в логе |
| U6 | `KEY-N`, issue не существует, проект `KEY` существует | активность у проекта `KEY` (fallback), комментарий не создаётся, warn про issue |
| U7 | письмо с вложениями (2+ attachment) | созданы `ecos-type@attachment` записи; в теле активности — inline JSON `lexical-file-node` |
| U8 | вложение >10 МБ | это вложение пропущено (warn), остальные обработаны, активность создана |
| U9 | subject в нижнем регистре (`prjctmng-33`) | regex не матчит → errorFolder (ключи только UPPERCASE) |
| U10 | subject `Fwd: [PRJCTMNG-33] Original subject` | префикс снят, ключ найден, `emailFrom` = пересылающий, активность + комментарий созданы |
| U11 | несколько валидных ключей в subject | первый выигрывает, warn с перечислением; если среди них есть issue-матч — он побеждает |
| U12 | middle of import падает исключение | транзакция откатилась — следующий retry заново создаёт всё |

**`ReadMailboxPTProcessorTest`** — парсинг subject/from/body из Camel Exchange, делегирование `ProjectEmailImportService`.

**`PollProjectMailboxesProcessorTest`**:
- T1: 3 проекта с `mailboxEnabled=true` — все обойдены, `mailboxLastSync` обновлён у всех.
- T2: один проект кидает IMAP exception — остальные два обработаны, `mailboxLastError` у первого заполнен.
- T3: `ConsumerTemplate.receiveBody` возвращает null сразу — проект скипается без обращения к `ProjectEmailImportService`.

**`MailboxKeyResolverTest`** (regex + БД-валидация + кеш):
- R1: `[OK]` — проекта нет, errorFolder.
- R2: `[PRJCTMNG]` — проект найден, возвращает projectRef.
- R3: `PRJCTMNG-33` без скобок — issue найдена, возвращает issueRef + projectRef.
- R4: `Re: Fwd: [PRJCTMNG-33]` — префиксы сняты, issue найдена.
- R5: повторный вызов с тем же ключом в TTL — из кеша, без query.

### 2. Приёмочные тест-кейсы (зафиксированы до реализации, проверяются после)

Чеклист в GitLab MR / issue трекере перед мёржем.

| # | Кейс | Шаги | Ожидание |
|---|---|---|---|
| A1 | Глобальный ящик: happy path по ключу | Настроить `mail-inbox-pt` (enabled=true, imap, credentials); отправить письмо `[PRJCTMNG] Тест` | В виджете активностей проекта PRJCTMNG появилась email-активность с корректным from/subject/body |
| A2 | Глобальный ящик: ключ + issue | Отправить `PRJCTMNG-33: детали` | Активность у проекта + internal comment у issue PRJCTMNG-33 |
| A3 | Глобальный ящик: нераспознанный ключ | Отправить `Hello world` | Письмо в `errorFolder`, активность не создана, warn в логе |
| A4 | Настройка ящика проекта | На форме проекта включить `mailboxEnabled`, ввести IMAP URL, выбрать существующий `ecos-credentials` ref | Конфиг сохранён; в `records_query` видны атрибуты aspect |
| A5 | Ящик проекта: импорт | После A4 отправить письмо | В виджете активностей проекта появилась email-активность; `mailboxLastSync` обновлён |
| A6 | Отключение ящика проекта | Снять `mailboxEnabled` | На следующем тике timer-route проект скипает |
| A7 | Ошибка credentials | Указать неверный пароль в credentials | `mailboxLastError` заполнен; активность не создана; остальные проекты работают |
| A8 | Дедупликация Message-ID | Эмулировать повторную доставку SMTP-сервером (один и тот же Message-ID приходит дважды) | Активность создана один раз |
| A9 | Вложения | Отправить письмо с 2 PDF | Активность создана; 2 attachment-записи; inline-ссылки в теле открываются |
| A10 | Issue-project mismatch | Subject `PRJCTMNG-33`, но issue перенесена в COREDEV | Активность у COREDEV, не у PRJCTMNG; warn в логе |
| A11 | Видимость секции «Почта» | Открыть форму проекта под пользователем без admin/responsible | Секция скрыта |
| A12 | Пароль не видим | Открыть форму под admin | В селекторе credentials — только имя записи, пароль не отображается |
| A13 | Несколько проектов с ящиками | 3 проекта с разными IMAP-ящиками | Все 3 опрошены за тик, активности у соответствующих проектов |
| A14 | Пересылка без ящика в копии | Пользователь пересылает стороннее письмо с subject `Fwd: [PRJCTMNG-33] ...` на глобальный ящик | Активность создана, `emailFrom` = пересылающий, оригинал в теле (quoted), комментарий у issue |
| A15 | Лимит вложения >10 МБ | Письмо с вложением 15 МБ + 1 МБ | Активность создана, вложение 1 МБ загружено, 15 МБ пропущено (warn в логе) |

### 3. Проверка через Records API (MCP citeck)

- **Дедупликация (U3, A8)**:
  ```
  records_query: { sourceId: "emodel/records", query: {
    language: "predicate",
    query: { t: "eq", a: "emailMessageId", v: "<test-msgid>" }
  }, attributes: ["id", "emailFrom", "emailSubject", "_parent"] }
  ```
  Ожидание: ровно одна запись типа `email-activity`.

- **Привязка к проекту (A1)**: `records_query` на `emodel/type@email-activity` с фильтром `_parent == <projectRef>`.

- **Internal comment у issue (A2)**: `records_query` на `emodel/comment` с фильтром `record == <issueRef>`, проверка текста.

- **Конфигурация ящика (A4)**: `records_query` на проект, атрибуты `mailboxEnabled`, `mailboxImap`, `mailboxCredentials`, `mailboxLastSync`, `mailboxLastError`.

- **Конфиг глобального ящика (A1)**: `records_query` на `mail-inbox-pt`, атрибуты `enabled`, `imap`, `credentials`.

### 4. UI-проверка через Playwright MCP (A4/A5/A9/A11/A14)

1. `browser_navigate` на форму проекта → `browser_fill_form` (IMAP URL, credentials) → `browser_click` Save → `browser_snapshot`.
2. Через shell отправить тестовое письмо (msmtp/curl SMTP).
3. `browser_navigate` на project-dashboard → `browser_wait_for` появления email-активности → `browser_take_screenshot` → открыть активность, проверить body и ссылки на attachments.
4. `browser_navigate` на issue → проверить наличие internal comment.
5. **Visibility A11**: под пользователем без прав — снимок формы, секция «Почта» отсутствует в DOM.
6. `browser_console_messages` + `browser_network_requests` — отсутствие 4xx/5xx и JS-ошибок.

### 5. Деплой на локальный стенд

```bash
cd ecos-project-tracker && mvn clean package -DskipTests
cd ../ecos-ecom && mvn clean package -DskipTests

cp ecos-project-tracker/target/classes/apps/ecos-project-tracker.zip \
   ~/Library/Application\ Support/Citeck/launcher/ws/DEFAULT/ns/<ns>/rtfiles/app/eapps/ecos-apps/
cp ecos-ecom/target/classes/apps/ecos-ecom.zip \
   ~/Library/Application\ Support/Citeck/launcher/ws/DEFAULT/ns/<ns>/rtfiles/app/eapps/ecos-apps/

docker logs citeck_eapps_<ns>_default --tail 100 2>&1 | grep -E "DEPLOYED|FAILED|mail-inbox-pt|ept-project-mailbox|email-atts"
```

## Ограничения текущей итерации

- Нет исходящих писем (ответов из интерфейса проекта).
- Нет тредирования (`emailMessageId`/`emailInReplyTo` сохраняются в атрибутах, но пока не используются для группировки цепочек).
- Один ящик на проект (aspect, не assoc). Можно расширить в следующей итерации.
- Нет распределённой блокировки между репликами ecos-ecom (паттерн CRM/SD). При переходе на 2+ реплики нужна миграция на `PollingConsumerPollStrategy` + `EcosAppLockService` — **отдельная задача на миграцию CRM/SD/PT**.
- Тело пересланного письма сохраняется as-is, без «очистки» quoted-блока.

---

## Статус на 2026-06-04 (текущий)

Сессия фиксации фичи в git и доведения тестового покрытия.

### ✅ Сделано

**Git:**
- Заведена задача **PRJCTMNG-58**; наработка разнесена по веткам `feature/PRJCTMNG-58-email-import` в `ecos-ecom` и `ecos-project-tracker` (ранее всё лежало незакоммиченным).
- Проверка регрессий по ветке: среди существующих файлов изменены только `application.yml` (+1 ключ `mail.attachment.max-size-mb`) и `.gitignore`; всё остальное — новые файлы. `project-form.json` — строго аддитивный diff (+197/−0), валидный JSON. Полный `mvn test` ecos-ecom — 49 тестов, 0 падений (включая поднятие Spring-контекста и SD/CRM-почтовые тесты) → старая функциональность не затронута.

**Тестовое покрытие (юнит, всегда выполняются):** 35 тестов, все зелёные.
- `MailboxKeyResolverTest` — 10.
- `ProjectEmailImportServiceTest` — 7.
- `MailboxMessageMoverTest` — 5.
- `ReadMailboxPTProcessorTest` — **8 (новый)**: маппинг исходов IMPORTED/DUPLICATE/NO_TARGET/FAILED, exception→FAILED, null-body→NO_TARGET, проброс полей письма в запрос, projectRef как EntityRef/String/пусто.
- `PollProjectMailboxesProcessorTest` — **5 (новый)**: все проекты опрошены + `mailboxLastSync`, изоляция ошибок + `mailboxLastError`, пустой ящик, blank imap, нет проектов. `EcosSecrets` мокается через `mockStatic`.

> Примечание: формулировка «25 passing» в статусе 2026-04-18 была неточной — фактически на тот момент в репозитории было 22 теста. Сейчас 35.

**Тестовое покрытие (интеграционные, включаются отдельно):** `ProjectMailboxImportIntegrationTest` — **10 (новый)**, все зелёные.
- Гейтинг по образцу `citeck-ai`: `@EnabledIfEnvironmentVariable(named = "EMAIL_INTEGRATION_TESTS", matches = "true")`. По умолчанию пропускаются (в общем прогоне — Skipped).
- Запуск: `EMAIL_INTEGRATION_TESTS=true mvn test -Dtest=ProjectMailboxImportIntegrationTest`.
- E2E через реальный pipeline: GreenMail (SMTP+IMAP in-memory) + in-mem Records (`InMemDataRecordsDao`) + **глобальный** роут `ReadMailboxPTRoute` (непрерывный consumer — устойчив на GreenMail, в отличие от pull-модели per-project). Конфиг подаётся через `EcosConfigServiceFactory.inMemConfigProvider`, секрет — через `mockStatic(EcosSecrets)`.
- Сценарии: I1 проект-ключ→активность+move в Processed; I2 issue-ключ→активность+комментарий; I4 нет ключа→move в Errors без активности; I5 вложения (inline `lexical-file-node`); I6 вложение >10 МБ пропущено; I7 пересылка `Fwd:` (`emailFrom`=пересылающий); R-fallback `KEY-N`→`KEY`; R-mismatch (источник истины — `link-project` issue); R-multikey (issue > project); R-lowercase→Errors.

### ⚠️ Ограничения in-process интеграционного теста
- **Дедуп (A8) намеренно не покрыт in-process.** `existsByMessageId` фильтрует по `AND(_type, email-atts:emailMessageId)`: `InMemDataRecordsDao` не отдаёт `_type` через `getAtt` (хранится null), а `jakarta.mail.Transport.send` перегенерирует `Message-ID`. Воспроизвести дубль в in-mem нельзя. Дедуп покрыт юнитом `ProjectEmailImportServiceTest.U3` (мок `queryOne`) и стенд-кейсом A8.
- **Per-project опрос** (`PollProjectMailboxesProcessor`, pull через `ConsumerTemplate.receive`) на GreenMail падает в `FolderClosedException`, поэтому in-process прогоняется через глобальный роут; общая логика import/routing/move у них одна. Оркестрация per-project покрыта юнит-тестом + стенд-смоуком.

### ✅ Стенд-смоук (2026-06-04, `tdcuosa` после редеплоя `edeploy` + GreenMail sidecar)
Per-project ящик настраивался через `records_mutate` аспекта `ept-project-mailbox` (таймер-роут реактивен без рестарта).
- **A1** — `[TESTMAIL]` → `email-activity` под проектом (`_parent=emodel/project@TESTMAIL`), `mailboxLastSync` обновлён, письмо перенесено INBOX→Processed. ✅
- **A8 дедуп** — повторная доставка того же `Message-ID` → второй активности нет (на реальной платформе `_type` индексируется, в отличие от in-mem). ✅
- **A3 нет ключа** — активность не создана, письмо → Errors. ✅
- **A2 issue-ключ** — `CRM1-6: …` через ящик на проекте CRM1 → активность под проектом CRM1 + `emodel/comment` на `emodel/ept-issue@CRM1-6`. ✅ Потребовался запущенный сервис `notifications` (создание комментария шлёт уведомление); при его отсутствии импорт падал и транзакция откатывалась целиком — **транзакционность подтверждена**.

### 🟢 A11 / 🔴 A12 — видимость секции «Почта» по ролям (Playwright, 2026-06-04)
- **A11 (видна для responsible/administrator)** — ✅ admin (Ответственный на CRM1) видит вкладку «Почта» со всеми полями.
- **A12 (пароль не раскрыт)** — ✅ в «Учётные данные» отображается только имя секрета («PT Greenmail test»), пароль не показан.
- **A12 негатив (секция скрыта для непривилегированного) — ❌ ДЕФЕКТ**, см. ниже.

### 🐞 BUG (A12): секция «Почта» видна непривилегированному участнику в карточке проекта
- **Симптом.** Пользователь `fet` (USER-участник workspace CRM1, НЕ responsible/administrator, `permissions.Write=false`, `_roles[].id=[""]`) открывает карточку проекта CRM1 и видит секцию «Почта» целиком: IMAP URL, имя секрета, папки, `mailboxLastSync`/`mailboxLastError`. Скриншот: `a12-mailbox-visible-to-fet-FINDING.png`.
- **Корень.** В форме секция гейтится `customConditional` (`data._roles` ∋ `responsible`/`administrator`). Это условие выполняется только в **edit-форме Formio**. Карточка проекта «Свойства» рендерит форму **собственным read-only компонентом и `customConditional` НЕ выполняет** (на странице нет Formio-инстанса с этой панелью; роли `fet` корректно пусты, т.е. это не баг вычисления ролей). Клиентский `customConditional` в принципе не является границей безопасности.
- **Масштаб.** Не раскрывается только пароль (хранится в секрете). Но конфигурация ящика (IMAP-хост, папки, ссылка на секрет) экспонируется любому участнику workspace, видящему карточку. Редактирование при этом закрыто (у `fet` «Нет доступных действий»).
- **Варианты фикса.**
  - **A. Уровень данных (рекомендуется).** Хранить mailbox-конфиг не как открытые атрибуты аспекта, а в отдельной записи/типе с правами чтения только responsible/administrator (или атрибут-уровневые read-права на `ept-project-mailbox`). Тогда непривилегированный получает пусто независимо от UI.
  - **B. Платформенный фикс ecos-ui.** Научить read-only карточку «Свойства» учитывать `customConditional`. Широкое влияние, вне ecos-project-tracker.
  - **C. Принять как known-issue** (edit закрыт, пароль скрыт) и описать.
- **Статус.** Краткосрочно — C; настоящий фикс — A (follow-up). Чисто UI-обходного пути нет: карточка игнорирует и `customConditional`, и обычные conditional.

### 🔁 Остаётся
- Исправление BUG A12 (направление A — приватность mailbox-конфига в карточке).
- A7 (ошибка credentials → `mailboxLastError`) и A13 (несколько ящиков) — на стенде в эту сессию не прогонялись (покрыто юнитом `PollProjectMailboxesProcessorTest`).

---

## Статус на 2026-04-18

### ✅ Закрытые в сессии 2026-04-18 итерации

| # | Итем | Где |
|---|---|---|
| #1 | Move писем в `mailboxSuccessFolder` / `mailboxErrorFolder` после обработки (approach B) | `MailboxMessageMover` + per-project и global маршруты |
| #2 | Per-project E2E на `tdcuosa` (A4–A7, A13) | BLD1 + CHK1 через GreenMail |
| #2a | Error reporting Camel-level сбоев → `mailboxLastError` | `MailboxMessageMover.probeConnection` + `pollProject` |
| #2b | `@EcosConfig` reactivity в `ReadMailboxPTRoute` без `docker restart` | Чтение `config.get()` внутри лямбды |
| #2c | Правильная мутация `mail-inbox-pt` через records API (`_value` attr) | Доку обновили в разделе «Как продолжить» |
| #4 | `activityDate` = EPOCH → fallback на `Instant.now()` | `ProjectEmailImportService.createEmailActivity` |
| #5a | Вложения U7/A9 (несколько вложений) | E2E на TESTMAIL через GreenMail |
| #5b | Лимит вложения 10 МБ (A15) | E2E на TESTMAIL через GreenMail |
| #5c | Пересылка U10 (`Fwd:` префикс) | E2E на TESTMAIL через GreenMail |
| #5d | U5/A10 issue↔project mismatch | E2E: CRM1-1 re-linked к TESTMAIL → активность у TESTMAIL |
| #5e | U6 fallback `KEY-N` → `KEY` | E2E: `TESTMAIL-999` → fallback WARN + активность у TESTMAIL |
| #5f | U11 несколько валидных ключей | Покрыт в U5/A10-тесте: `[CRM1-1, CRM1, TESTMAIL]` → issue wins |
| #8 | `mail.attachment.max-size-mb` → `application.yml` | `@Value` инъекция, 7/7 unit-тестов + smoke на `tdcuosa` |

**Ключевые артефакты/код, добавленные в сессии:**
- `src/main/java/ru/citeck/ecos/ecom/service/pt/MailboxMessageMover.kt` — `move()` + `probeConnection()`.
- `ReadMailboxPTProcessor.ImportOutcome` enum + Exchange-property `ptImportResult`.
- `PollProjectMailboxesProcessor.pollProject` — `probeConnection` + `moveAfterImport`.
- `ReadMailboxPTRoute.buildMoveProcessor` — живое чтение `config.get("successFolder")` / `errorFolder`.
- `src/main/resources/eapps/artifacts/app/config/mail-inbox-pt.yml` — defaults `successFolder: "Processed"`, `errorFolder: "Errors"`.
- `PollProjectMailboxesProcessor` — `mapMailMessage=true` (фикс `FolderClosedException`).

**Unit-тесты (25 passing, новый 1 и MailboxMessageMoverTest добавлен):**
- `MailboxKeyResolverTest` — 10 passing.
- `ProjectEmailImportServiceTest` — 7 passing (включая `U13 missing Date header falls back to now`).
- `MailboxMessageMoverTest` — 5 passing (happy path, auto-create target, not-found, blank target, blank messageId — все через GreenMail in-memory).

**E2E на стенде `tdcuosa` (GreenMail sidecar):**
- **BLD1** per-project: `bld1-move-001@local` → `Processed` (auto-created) — подтвердил A4/A5 + move.
- **CHK1** per-project (невалидный хост): `mailboxLastError="Couldn't connect to host, port: nonexistent-host-a7, 3143; timeout 5000"`, BLD1 не заблокирован → A7 + Camel-error reporting.
- **Global `ptuser`**: `testmail-after-restart-ok@local` → `Processed`, `testmail-after-restart-err@local` → `Errors` (NoTarget → errorFolder).
- **Reactivity**: мутация `eapps/config@app/ecom$mail-inbox-pt` → `successFolder=ProcessedV2, errorFolder=ErrorsV2` **без restart** → следующий poll перенёс `tm-reactive-ok@local` в `ProcessedV2`, `tm-reactive-err@local` в `ErrorsV2`. Обе папки созданы автоматически.
- **U7/A9 multi-attachment** (Message-ID `u7-multi-3297dd96@local`, subject `[TESTMAIL] U7 multi-attachment`): письмо с 2 вложениями (`u7-doc-a.pdf` 53B, `u7-notes-b.txt` 35B) → активность `emodel/activity@fc28017e-...` с `_parent=emodel/project@TESTMAIL`, `_parentAtt=has-ecos-activities:ecosActivities`; обе attachment-записи `emodel/attachment@*` созданы с `_parent=emodel/project@TESTMAIL`, `_parentAtt=docs:documents`, `_type=emodel/type@attachment`; в теле — два inline `lexical-file-node` JSON-блока со ссылками на `fileRecordId`. Письмо перенесено в `Processed`.
- **A15 size limit 10 MB** (Message-ID `a15-retry-e8cc6e11@local`, subject `[TESTMAIL] A15 size limit retry`): письмо с `a15-small-r.txt` (950B) + `a15-huge-r.bin` (12 MB) → лог `WARN ... Attachment 'a15-huge-r.bin' exceeds size limit 10 MB — skipped`, активность создана только с маленьким вложением (`size=950`, ровно один `lexical-file-node` в теле), большое пропущено. Письмо перенесено в `Processed` (импорт **не** упал из-за skip). Первая попытка (`a15-limit-4d30b6ee@local`) совпала с рестартом `citeck_eproc_tdcuosa_default` (11:22:51) — `EcosWebException: Failed to connect to /172.18.0.4:17021` во время `EcosContentApi.uploadFile().writeContent()`, импорт ушёл в FAILED, письмо в `Errors`. Не баг кода — внешний сервис был недоступен.
- **U10 forwarded** (Message-ID `u10-fwd-a569b173@local`, subject `Fwd: [TESTMAIL] Original meeting notes`, From `forwarder@example.com`, body — стандартный `---------- Forwarded message ---------` блок с оригинальным sender/subject/to внутри): `Fwd:` префикс снят `MailboxKeyResolver`'ом для regex'а → ключ `TESTMAIL` найден → активность создана у `emodel/project@TESTMAIL`, `email-atts:emailFrom=forwarder@example.com` (пересылающий, **не** оригинальный sender), `email-atts:emailSubject=Fwd: [TESTMAIL] Original meeting notes` (subject хранится as-is, prefix снят только для роутинга), тело сохранено as-is с цитированным оригиналом. Перенесено в `Processed`.
- **U5/A10 issue↔project mismatch** (Message-ID `u5-mismatch-8165b0ae@local`, subject `CRM1-1: U5 mismatch test ...`): issue `CRM1-1` временно перевязан `link-project:project → emodel/project@TESTMAIL` (был CRM1, восстановлен после теста). Subject содержит 3 кандидата: `CRM1-1`, `CRM1`, `TESTMAIL`. WARN `MailboxKeyResolver: Multiple mailbox keys resolved ... Using issue CRM1-1. All: [CRM1-1, CRM1, TESTMAIL]` → issue-матч выигрывает. Активность создана у `emodel/project@TESTMAIL` (link-project источник истины), **не** у CRM1; `ecos-comment` создан на `emodel/ept-issue@CRM1-1`. Письмо в `Processed`.
  - **Implementation gap vs design**: дизайн говорит «warn с обоими ключами при mismatch» — текущий `MailboxKeyResolver.resolveFromDbImpl` тихо использует `link-project` без сравнения `candidate.projectKey` (из subject) с ключом целевого проекта. Поведение корректное, но специализированного WARN про mismatch нет (только общий multi-key WARN). Подумать, надо ли добавлять.
- **U6 fallback `KEY-N` → `KEY`** (Message-ID `u6-fallback-642d0575@local`, subject `TESTMAIL-999: ...`): issue `TESTMAIL-999` не существует, проект `TESTMAIL` есть → WARN `MailboxKeyResolver: Issue TESTMAIL-999 not found, falling back to project TESTMAIL`. Активность создана у TESTMAIL, `ecos-comment` **не** создан (нет issueRef). Письмо в `Processed`.
- **Config refactor smoke** (Message-ID `smoke-cfg-a8301b3b@local`): после выноса `ATTACHMENT_MAX_SIZE_BYTES` → `mail.attachment.max-size-mb: 10` в `application.yml` + `@Value` инъекции в `ProjectEmailImportService`, повторный deploy через `edeploy` + smoke-письмо со 115B вложением → активность создана, attachment загружен, `lexical-file-node` в теле, WARN про size не сработал. `mvn test -Dtest=ProjectEmailImportServiceTest` — 7/7 passing (конструктор теста обновлён: `10L` 4-м параметром).

### 🔁 Остаётся открытым

| # | Итем | Детали |
|---|---|---|
| #3 | Форма «Почта» на проекте — видимость по ролям | Playwright кейсы A4/A11/A12 не прогонялись |
| #7 | Cleanup старых «пустых» email-activity записей | `records_mutate _action=delete` на старых UUID возвращает 500; нужен правильный путь удаления |
| #9 | Спец-WARN про issue↔project mismatch | Дизайн говорит warn с обоими ключами, реализация тихо использует link-project; решить — добавлять или зафиксировать как есть |

**Cosmetic:** Camel MailConsumer логирует WARN `MessageRemovedException` после нашего `expunge` (пытается commit original). Функциональность не страдает; можно отфильтровать в logback при необходимости.

### 📝 Ссылки на навигацию

- Основной сервис: `src/main/java/ru/citeck/ecos/ecom/service/pt/ProjectEmailImportService.kt`
- Mover: `src/main/java/ru/citeck/ecos/ecom/service/pt/MailboxMessageMover.kt`
- Per-project route: `src/main/java/ru/citeck/ecos/ecom/routes/ReadProjectMailboxesRoute.kt` + `src/main/java/ru/citeck/ecos/ecom/processor/pt/PollProjectMailboxesProcessor.kt`
- Global route: `src/main/java/ru/citeck/ecos/ecom/routes/ReadMailboxPTRoute.java`
- Import processor: `src/main/java/ru/citeck/ecos/ecom/processor/ReadMailboxPTProcessor.java`
- Aspect ept-project-mailbox: `../ecos-project-tracker/src/main/resources/eapps/artifacts/model/aspect/ept-project-mailbox.yml`
- Config mail-inbox-pt: `src/main/resources/eapps/artifacts/app/config/mail-inbox-pt.yml`

---

## Статус на 2026-04-17

### ✅ Сделано и проверено на стенде `tdcuosa`

**Артефакты (задеплоены через eapps, DEPLOYED):**
- `emodel/aspect@ept-project-mailbox` — добавлен к типу `project`
- `emodel/aspect@email-atts` — добавлен к типу `email-activity` через `artifact-patch`
- `app/config@app/ecom$mail-inbox-pt` + форма `uiserv/form@config-mail-inbox-pt`
- Форма `uiserv/form@project-form` с секцией «Почта» (видимость по ролям `responsible`/`administrator`)

**Код ecos-ecom (собрано, образ `nexus.citeck.ru/ecos-ecom:local`):**
- `ReadMailboxPTRoute` — глобальный IMAP-консьюмер, `@EcosConfig("mail-inbox-pt")`
- `ReadMailboxPTProcessor` — делегирование в `ProjectEmailImportService`
- `ReadProjectMailboxesRoute` + `PollProjectMailboxesProcessor` — timer 60s для per-project ящиков
- `ProjectEmailImportService` — дедуп, transactional create activity+comment, вложения
- `MailboxKeyResolver` — strip префиксов, regex, DB-валидация, TTL-кеш

**Unit-тесты (22 passing):**
- `MailboxKeyResolverTest` — 10 тестов (R1-R5 + edge cases)
- `ProjectEmailImportServiceTest` — 7 тестов (U1-U4 + explicit project + mismatch + U13 EPOCH fallback)
- `MailboxMessageMoverTest` — 5 тестов (GreenMail: happy path, auto-create target, not-found, blank target, blank messageId)

**Приёмочные сценарии, проверенные end-to-end через GreenMail (sidecar, network `citeck_network_tdcuosa_default`):**
- **A1 (happy path)** ✅ — письмо `[TESTMAIL] 6th test` → создана `email-activity` у `emodel/project@TESTMAIL` с корректно заполненными атрибутами aspect `email-atts` (`emailFrom=tester@example.com`, `emailSubject=...`, `emailMessageId=test-006@local`, `emailTo=ptuser@greenmail-test`, `_parent=emodel/project@TESTMAIL`, `text`)
- **A8 (дедуп Message-ID)** ✅ — повторная доставка того же `<test-006@local>` → лог `Email with Message-ID=test-006@local already imported, skipping`, активность не создаётся

### 🔁 Ключевые уточнения относительно исходного плана

1. **`emodel/type@ecos-credentials` → `emodel/type@secret`**
   На стенде не запущен `ecos-integrations`, поэтому `integrations/credentials` недоступен. Платформа предоставляет нативный механизм секретов в `ecos-model`:
   - sourceId: `emodel/secret`
   - тип: `emodel/type@secret`
   - журнал: `uiserv/journal@ecos-secrets`
   - форма: `uiserv/form@ecos-secret`

   Aspect + config + обе формы обновлены на использование `journalId: ecos-secrets` и `typeRef: emodel/type@secret`.

2. **Чтение секрета: `EcosSecrets.getBasicDataOrNull(id)` вместо `recordsService.getAtts("username","password")`**
   Данные `emodel/secret@*` не экспонируются через обычный Records API (даже под admin возвращают null). Используется `ru.citeck.ecos.secrets.lib.EcosSecrets` + `BasicSecretData` — пример см. `S3ContentStorageFactory`. `ModelEcosSecretsProvider` зарегистрирован автоматически через `EcosEndpointsAndSecretsConfig` в `ecos-webapp-commons`.

3. **Префикс aspect при записи/чтении атрибутов: `email-atts:emailFrom` (не просто `emailFrom`)**
   Без префикса атрибуты не сохраняются (возвращают null при query). При записи: `.set("email-atts:emailFrom", ...)`. При дедуп-запросе: `Predicates.eq("email-atts:emailMessageId", ...)`. Unit-тесты обновлены.

### ❗️ Открытые проблемы / TODO для следующей итерации

1. ~~**IMAP poll оставляет письма с `\Seen` без move в success/error folder.**~~ **✅ Implemented (2026-04-18) — approach B:**
   - **Новый сервис** `ru.citeck.ecos.ecom.service.pt.MailboxMessageMover` — открывает отдельный JavaMail-сеанс (IMAP Store), ищет письмо по заголовку `Message-ID` (нормализуя `<...>`), копирует в целевую папку (создавая её при необходимости), помечает оригинал `\Deleted` и делает `expunge`.
   - **ReadMailboxPTProcessor** теперь пробрасывает результат импорта (`IMPORTED`/`DUPLICATE`/`NO_TARGET`/`FAILED`) через Exchange-property `ptImportResult`.
   - **PollProjectMailboxesProcessor** (per-project) — после `producerTemplate.send(IMPORT_ENDPOINT)` читает outcome и зовёт mover с `mailboxSuccessFolder` или `mailboxErrorFolder` из aspect'а.
   - **ReadMailboxPTRoute** (global) — таким же образом, с `successFolder`/`errorFolder` из `mail-inbox-pt` config.
   - **Unit-тесты**: `MailboxMessageMoverTest` — 5 тестов на GreenMail (happy path, auto-create target folder, missing messageId, blank target, blank messageId).
   - **E2E на `tdcuosa`** (per-project BLD1): письмо `bld1-move-001@local` → импортировано + перенесено из `INBOX` в `Processed` (новая папка создана автоматически). Подтверждено через IMAP-листинг.
   - **E2E глобального ящика (2026-04-18, после правильной мутации конфига — см. 2c):**
     - `testmail-after-restart-ok@local` (`[TESTMAIL] ...`) → импортирован как activity + перенесён в `Processed`.
     - `testmail-after-restart-err@local` (без распознаваемого ключа) → FAILED outcome + перенесён в `Errors`.
     - Обе папки `Processed`/`Errors` созданы автоматически при первом move.
   - **Cosmetic issue**: После успешного move Camel'овский consumer логирует WARN `MessageRemovedException` при попытке commit'нуть original message (который мы уже expunge'нули). На функциональность не влияет — но шум в логах. Опционально можно отфильтровать `MessageRemovedException` в Camel-опциях или в logback.

2. ~~**Per-project timer-route (A4–A7, A13)**~~ **✅ Validated E2E (2026-04-18) на `tdcuosa`** с двумя проектами:
   - **BLD1** (`imap://greenmail-test:3143` + secret `bld1-greenmail-creds`) — рабочая конфигурация.
   - **CHK1** (`imap://nonexistent-host-a7:3143` + secret `chk1-broken-creds`) — заведомо сломанный хост.
   - **A5 import** — `bld1-002@local`, `bld1-a7@local` созданы как `email-activity` под BLD1, `_parent=emodel/project@6b18e3b7-...`, `mailboxLastSync` обновляется каждый тик.
   - **A6 disable** — пока `mailboxEnabled=false`, письмо лежит в INBOX и НЕ импортируется; после re-enable штатно подхватывается на следующем тике.
   - **A7 isolation** — CHK1 Camel consumer падает `MailConnectException: timeout 30000` (WARN), BLD1 продолжает обрабатываться. 💡 **Следствие**: Camel's IMAP consumer сам глушит ошибки подключения на уровне consumer (наш `ConsumerTemplate.receive()` просто получает null), поэтому `mailboxLastError` для CHK1 остаётся пустым — **ошибка подключения не видна пользователю**. См. TODO ниже.
   - **A13 несколько ящиков** — оба проекта опрошены в одном цикле, BLD1 не блокируется CHK1.
   - **Критичный фикс по пути**: `ConsumerTemplate.receive()` возвращал Exchange после закрытия IMAP-папки Camel'ом → `jakarta.mail.FolderClosedException` при доступе к `message.content`. **Решение**: в `PollProjectMailboxesProcessor.buildEndpointUri` выставлен `mapMailMessage=true` — Camel материализует тело письма во время poll (до закрытия папки). `prepareExchange` упрощён: больше не нужна ручная конвертация body.

2a. ~~**TODO — error reporting для Camel-level сбоев (A7).**~~ **✅ Fixed & validated E2E (2026-04-18):**
   - В `MailboxMessageMover.probeConnection(imapUrl, username, password)` — короткоживущий IMAP-`connect()` с `connectiontimeout=5000ms` и `timeout=5000ms`, затем `close()`. Пробрасывает `jakarta.mail.MessagingException` при фейле.
   - `PollProjectMailboxesProcessor.pollProject` теперь зовёт `probeConnection` ДО `consumerTemplate.receive()`. Если проба падает, exception пузырится в outer-catch и `mailboxLastError` заполняется.
   - **Оверхед**: 1 IMAP-коннект на project на тик. Для 10 проектов × 60с тиков ≈ 10 соединений/мин — незначительно.
   - **Альтернатива, которую не стали реализовывать**: Camel `EventNotifierSupport` на `FailedToPollEndpointException`. Требует маппинга endpoint-URI → projectRef, сложнее и хрупче. Probe проще и эксплицитнее.
   - **E2E на `tdcuosa`**: CHK1 (imap://nonexistent-host-a7:3143) → `mailboxLastError="Couldn't connect to host, port: nonexistent-host-a7, 3143; timeout 5000"`, BLD1 остаётся healthy (`mailboxLastError=""`). Изоляция + отчёт об ошибке работают вместе.

2b. ~~**Open — `@EcosConfig` в `ReadMailboxPTRoute` не реактивен.**~~ **✅ Fixed & validated E2E (2026-04-18):**
   - `BeanConsumerServiceImpl` (из `ecos-config:1.9.0`) при событии change дергает **setter поля**, помеченного `@EcosConfig` (`callable.call(bean, newValue)`), т.е. `this.config = newObjectData`. Лямбда в Camel-процессоре closes over `this`, поэтому `this.config.get(...)` всегда читает актуальное значение.
   - Фикс в `ReadMailboxPTRoute.buildMoveProcessor()`: вместо чтения `successFolder`/`errorFolder` из captured `MailboxContext` — читаем `config.get("successFolder").asText()` / `...errorFolder` напрямую внутри лямбды. `MailboxContext` упрощён (поля `successFolder`/`errorFolder` убраны).
   - **E2E на `tdcuosa`**: `records_mutate _value={successFolder=ProcessedV2, errorFolder=ErrorsV2}` — без `docker restart` — следующий тик перенёс `tm-reactive-ok@local` → `ProcessedV2`, `tm-reactive-err@local` → `ErrorsV2`. Новые папки созданы автоматически.
   - **Ограничение**: `imap URL`, `credentials`, `folder`, `delay` по-прежнему читаются один раз при сборке route'а (они baked в Camel endpoint URI). Их мутация требует `docker restart`. Это ожидаемо — Camel endpoints не поддерживают горячую перестройку URI.

2c. ~~**Open — правильная мутация `mail-inbox-pt` через records API.**~~ **✅ Решено (2026-04-18):**
   Исходная попытка мутировать `eapps/config@app/ecom$mail-inbox-pt` с атрибутом `value` (без underscore) проходила как `ok:true`, но не писала в ZooKeeper (eapps-config уровень read-only для запись в рантайм-значение).
   **Правильный путь** (найден в `CfgRecordsDao`, `ecos-config-records`):
   - Использовать атрибут `_value` (с underscore prefix), значение — raw объект (без обёртки `{"v":{}}`).
   - SourceId можно оставить `eapps/config@app/ecom$mail-inbox-pt`. Либо более короткий `cfg@mail-inbox-pt` в локальном контексте app-а (но это не работает через gateway — 500).
   - Пример:
     ```json
     {
       "id": "eapps/config@app/ecom$mail-inbox-pt",
       "attributes": {
         "_value": {"imap": "...", "enabled": true, "successFolder": "Processed", "errorFolder": "Errors", ...}
       }
     }
     ```
   - После мутации ecos-config broadcast'ит `Config was changed` event, но см. TODO 2b — `@EcosConfig` в route не реактивен.

3. **Форма проекта «Почта» — видимость по ролям не проверена визуально.**
   `customConditional` на секции использует `data._roles`. На стенде Playwright не прогонялся (кейсы A4, A11, A12). Нужно:
   - открыть форму проекта от имени `responsible` / администратора — секция видна;
   - открыть от имени обычного пользователя — секция скрыта;
   - подтвердить, что в `selectJournal` для credentials отображается только имя секрета, а не данные.

4. ~~**`activityDate` = EPOCH** когда письмо не содержит заголовка `Date`~~ **✅ Fixed & validated E2E (2026-04-17):**
   `ProjectEmailImportService.createEmailActivity` вычисляет `effectiveDate = if (mail.date == Instant.EPOCH) Instant.now() else mail.date` и использует это значение и для `activityDate`, и для `email-atts:emailReceivedAt` — гарантирует консистентность. Покрыто unit-тестом `U13 missing Date header falls back to now`. На стенде `tdcuosa`: письмо `test-007@local` без заголовка `Date` → `activityDate=2026-04-17T21:02:57.072627Z` (совпадает с `emailReceivedAt`), не EPOCH как в старых `test-006`/`test-008`.

5. **Вложения, fallback issue→project, пересылки (U5–U11), несколько per-project ящиков (A13), лимит 10 МБ (A15) — не прогонялись на стенде.**
   Unit-тесты покрывают логику, приёмочные тесты через GreenMail ещё предстоят.

6. **Тип проекта `Budget` в журнале: `aspects` показывает `["Versionable","Budget","Project mailbox"]`** — Versionable автоматически добавляется фреймворком. Всё ок.

7. **Старые «пустые» email-activity записи в `emodel/project@TESTMAIL` (до перехода на prefix `email-atts:`) не удалены.**
   `records_mutate { _action: delete }` на `emodel/activity@<uuid>` вернул 500. Нужно удалить вручную через UI либо прямым SQL, либо выяснить корректный путь удаления.

### 🔧 Как продолжить (runbook)

**Среда на стенде `tdcuosa`:**
- `citeck_ecom_tdcuosa_default` — собран из `nexus.citeck.ru/ecos-ecom:local` (1.15-SNAPSHOT, 2026-04-17)
- `citeck_ecos-project-tracker_tdcuosa_default` — собран из `:local`
- `greenmail-test` — sidecar на `citeck_network_tdcuosa_default`, порты host: `3025` (SMTP), `3143` (IMAP)
- `emodel/secret@pt-greenmail-creds` — BASIC секрет (ptuser/ptpass)
- `emodel/secret@bld1-greenmail-creds` — BASIC секрет для per-project BLD1 (username=`bld1@greenmail-test` — GreenMail auto-creates mailbox по полному адресу), password=`bld1pass`
- `emodel/secret@chk1-broken-creds` — BASIC секрет для A7 isolation test (невалидные creds, URL — несуществующий хост)
- Config `app/ecom$mail-inbox-pt`: `enabled=true`, `imap=imap://greenmail-test:3143`, `credentials=emodel/secret@pt-greenmail-creds`, `folder=INBOX`, `delay=60000`
- Тестовый проект `emodel/project@TESTMAIL` (глобальный ящик)
- Проект `emodel/project@6b18e3b7-...` (BLD1, per-project валидный), `emodel/project@6967abe1-...` (CHK1, per-project сломанный — для A7)

**Ключевые state-артефакты на `tdcuosa` (сейчас):**
- BLD1: `mailboxEnabled=true`, `mailboxSuccessFolder=Processed`, `mailboxErrorFolder=Errors` — валидная per-project конфигурация, move отработал на `bld1-move-001`.
- CHK1: `mailboxEnabled=false` (после E2E A7 был отключён, чтобы не спамило WARN). Для повторного A7-теста — выставить `true`.
- Global `app/ecom$mail-inbox-pt`: после мутации через `_value` → `successFolder=Processed`, `errorFolder=Errors` на уровне ZK. Но из-за TODO 2b route надо перезапускать, чтобы подхватить.
- В `ptuser@greenmail-test` INBOX накопилось ~16 \Seen сообщений от предыдущих полов; для проверки move нужны свежие письма с новыми Message-ID.

**Команды:**
- Redeploy ecos-ecom после правок: `cd ecos-ecom && edeploy`
- Мутация глобального конфига (через MCP records_mutate):
  ```
  id: "eapps/config@app/ecom$mail-inbox-pt"
  attributes: {"_value": {"imap": "...", "enabled": true, "successFolder": "Processed", ...}}
  ```
  После — `docker restart citeck_ecom_tdcuosa_default` чтобы @EcosConfig route picked up значения.
- Отправить тест: `python3 -c "import smtplib; from email.mime.text import MIMEText; msg=MIMEText('body'); msg['Subject']='[TESTMAIL] X'; msg['From']='tester@example.com'; msg['To']='ptuser@greenmail-test'; msg['Message-ID']='<test-N@local>'; s=smtplib.SMTP('localhost',3025); s.send_message(msg); s.quit()"`
- Проверка активности: MCP `records_query source_id=emodel/activity query={t:eq,a:_parent,v:emodel/project@TESTMAIL}`
- Логи импорта: `docker logs citeck_ecom_tdcuosa_default --since 2m | grep -iE "ProjectEmail|activity|mailbox"`
