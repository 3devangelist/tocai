# Tocaí — Arquitetura e contrato entre módulos

> **Tocaí** ("toca aí") transforma eventos do calendário do telemóvel em **despertadores a sério**:
> som de alarme em loop, vibração e ecrã inteiro, mesmo com o telemóvel bloqueado.
> Não são notificações. Interface em português de Portugal (pt-PT).
>
> **Modelo mental:** a app mostra o que está marcado (eventos de **todos** os calendários do telemóvel,
> filtrados pelos "Calendários visíveis") e o utilizador **escolhe os eventos** que querem despertador
> (interruptor por evento = `forcedOn`/`forcedOff`). Todas as manhãs, à hora da escolha diária (defeito
> 07:00), abre-se o ecrã "Bom dia! O que tens hoje?" para escolher os do dia. Os calendários automáticos
> são uma opção **avançada**, desligada por defeito.

Este documento é o **contrato** entre os módulos. Cada ficheiro tem **um dono**, e as
assinaturas públicas abaixo são vinculativas: não as alteres. Podes acrescentar métodos
ou ficheiros privados ao teu módulo, mas nunca mudes nem removas o que está aqui.
Não há forma de compilar localmente, por isso o código tem de compilar à primeira:
- imports exatos;
- nada de APIs inventadas;
- verifica o `SDK_INT` antes de usar APIs mais recentes do que o minSdk.

## Stack (fixa)

- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2 (sem wrapper jar; o CI usa `gradle/actions/setup-gradle`)
- `compileSdk 35`, `targetSdk 35`, `minSdk 26`, Java 17
- **Views + XML + Material 3**. Sem Compose, kapt, KSP, Room ou Hilt.
- Dependências permitidas (e só estas):
  - `androidx.core:core-ktx:1.13.1`
  - `androidx.appcompat:appcompat:1.7.0`
  - `com.google.android.material:material:1.12.0`
  - `androidx.recyclerview:recyclerview:1.3.2`
  - `androidx.work:work-runtime-ktx:2.9.1`
  - `androidx.preference:preference-ktx:1.2.1`
  - `androidx.activity:activity-ktx:1.9.3`
  - `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
  - testes: `junit:junit:4.13.2`
- `namespace` e `applicationId`: `pt.tocai.app`. O `viewBinding` está **desligado**: usa `findViewById`.
- `android.nonTransitiveRClass=true`: usa sempre `pt.tocai.app.R`. Para recursos do Material,
  usa os atributos de tema (`?attr/colorPrimary`, etc.).

## Estrutura de pastas

```
app/src/main/java/pt/tocai/app/
  TocaiApp.kt                      [sync]
  data/Models.kt                   [data]
  data/SettingsStore.kt            [data]
  data/CalendarRepository.kt       [data]
  rules/RuleSettings.kt            [rules]
  rules/AlarmPlanner.kt            [rules]
  rules/SnoozePolicy.kt            [rules]  quando mostrar "Adiar" (puro, testado na JVM)
  alarm/AlarmPayload.kt            [alarm]
  alarm/AlarmScheduler.kt          [alarm]
  alarm/AlarmReceiver.kt           [alarm]
  alarm/AlarmService.kt            [ring]
  alarm/AlarmActivity.kt           [ring]
  sync/SyncWorker.kt               [sync]
  sync/SystemEventsReceiver.kt     [sync]
  sync/NotificationChannels.kt     [sync]
  sync/DailySync.kt                [sync]
  sync/DailySyncReceiver.kt        [sync]
  ui/MainActivity.kt               [main-ui]
  ui/EventsAdapter.kt              [main-ui]
  ui/SettingsActivity.kt           [settings-ui]
  ui/SettingsFragment.kt           [settings-ui]
  ui/CalendarPickerDialog.kt       [settings-ui]
  ui/VisibleCalendarsDialog.kt     [settings-ui]
  ui/OffsetsDialog.kt              [main-ui]
  ui/MorningPickerActivity.kt      [morning]
  ui/FlashActivity.kt              [ring]   luz de aviso (ecrã azul a piscar)
  ui/OnboardingActivity.kt         [onboarding]
  util/PermissionsHelper.kt        [onboarding]
  util/TimeFormat.kt               [main-ui]
  util/CallState.kt                [ring]   deteção de chamada/reunião (AudioManager.mode)
  util/BluePulse.kt                [ring]   animação do fundo azul a pulsar
  widget/NextAlarmWidget.kt        [widget]
  widget/NextAlarmTileService.kt   [widget]
app/src/test/java/pt/tocai/app/rules/AlarmPlannerTest.kt   [rules]
app/src/test/java/pt/tocai/app/rules/SnoozePolicyTest.kt   [rules]
app/src/test/java/pt/tocai/app/sync/DailySyncTest.kt       [sync]
app/src/test/java/pt/tocai/app/data/EventOffsetsCodecTest.kt [data]
app/src/test/java/pt/tocai/app/util/CallStateTest.kt       [ring]
```

### Recursos

Cada módulo cria os seus **próprios** ficheiros de recursos, para não haver conflitos:
- strings em `res/values/strings_<modulo>.xml` (ex.: `strings_main.xml`), com nomes prefixados
  (ex.: `main_title`);
- layouts e drawables com o prefixo do módulo (ex.: `layout/main_activity.xml`,
  `drawable/main_ic_settings.xml`).

O módulo **infra** é dono de:
- `AndroidManifest.xml`, `values/strings.xml` (só `app_name` = "Tocaí"), `values/colors.xml`,
  `values/themes.xml`, `values-night/themes.xml`;
- os ícones da app e `drawable/ic_tocai_alarm.xml` (vetor 24dp do despertador, cor `#FFFFFFFF`,
  para ser tingido);
- os ficheiros Gradle, o CI, o README e a LICENSE.

Recursos partilhados que **existem** e podes usar:
- cores: `@color/tocai_primary` (#1A73E8), `@color/tocai_alarm_bg` (#0B1F3A),
  `@color/tocai_on_alarm` (#FFFFFF), `@color/tocai_flash_bright` (#1A73E8, azul vivo da luz a piscar),
  `@color/tocai_warning_bg` (#FFF3CD),
  `@color/tocai_warning_fg` (#664D03);
- temas: `@style/Theme.Tocai` (Material3 DayNight NoActionBar),
  `@style/Theme.Tocai.Alarm` (escuro, fundo `tocai_alarm_bg`);
- drawable: `@drawable/ic_tocai_alarm`.

## Componentes no Manifest (nomes fixos)

| Classe | Tipo | Notas |
|---|---|---|
| `.TocaiApp` | Application | `android:name` |
| `.ui.MainActivity` | Activity | launcher, exported |
| `.ui.OnboardingActivity` | Activity | not exported |
| `.ui.SettingsActivity` | Activity | not exported, parentActivityName MainActivity |
| `.ui.MorningPickerActivity` | Activity | not exported, excludeFromRecents, launchMode singleTask, taskAffinity `pt.tocai.app.morning`; **sem** showWhenLocked |
| `.alarm.AlarmActivity` | Activity | showWhenLocked, turnScreenOn, excludeFromRecents, launchMode singleTask, taskAffinity "", theme Theme.Tocai.Alarm |
| `.ui.FlashActivity` | Activity | not exported, showWhenLocked, turnScreenOn, excludeFromRecents, launchMode singleTask, taskAffinity "", theme Theme.Tocai.Alarm; só lançada por fullScreenIntent |
| `.alarm.AlarmService` | Service | foregroundServiceType `mediaPlayback`, not exported |
| `.alarm.AlarmReceiver` | Receiver | not exported |
| `.sync.DailySyncReceiver` | Receiver | not exported; disparado pelo AlarmManager (agendamento diário) |
| `.sync.SystemEventsReceiver` | Receiver | exported; BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED, `android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` |
| `.widget.NextAlarmWidget` | Receiver (AppWidgetProvider) | `APPWIDGET_UPDATE`, meta-data `@xml/widget_next_alarm_info` |
| `.widget.NextAlarmTileService` | Service | `android.service.quicksettings.action.QS_TILE`, permission BIND_QUICK_SETTINGS_TILE, icon `@drawable/ic_tocai_alarm`, exported |

Permissões:
- `READ_CALENDAR`, `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`;
- `SCHEDULE_EXACT_ALARM` (maxSdkVersion 32);
- `USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, `WAKE_LOCK`;
- `SYSTEM_ALERT_WINDOW` ("Mostrar por cima de outras apps"; pedida no onboarding, recomendada, não obrigatória):
  isenta o Tocaí das restrições de abertura de atividades em segundo plano;
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`;
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `com.android.alarm.permission.SET_ALARM`.

## Contrato de código (assinaturas públicas)

### [data] `pt.tocai.app.data`

```kotlin
data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

data class CalendarEvent(
    val eventId: Long, val begin: Long, val end: Long,
    val title: String, val location: String, val description: String,
    val calendarId: Long, val calendarName: String, val color: Int,
    val allDay: Boolean, val declined: Boolean,
) { val key: String get() = "${eventId}_$begin" }

/** Um despertador planeado. Um evento pode ter vários (ex.: 15 e 5 min antes). */
data class PlannedAlarm(val event: CalendarEvent, val triggerAt: Long, val offsetMinutes: Int) {
    val alarmKey: String get() = "${event.key}@$offsetMinutes"
}

object SettingsKeys {  // chaves das SharedPreferences por defeito (PreferenceManager.getDefaultSharedPreferences)
    const val DEFAULT_OFFSETS = "default_offsets"        // String CSV de minutos, ex. "15,5"; defeito "5"
    const val AUTO_CALENDAR_IDS = "auto_calendar_ids"    // StringSet de ids
    const val CALENDAR_OFFSETS = "calendar_offsets"      // String "id:15,5;id2:10" (vazio = usar defeito)
    const val INCLUDE_KEYWORDS = "include_keywords"      // String CSV; vazio = todos
    const val EXCLUDE_KEYWORDS = "exclude_keywords"      // String CSV
    const val SKIP_DECLINED = "skip_declined"            // Boolean, defeito true
    const val LOOK_AHEAD_DAYS = "look_ahead_days"        // String (ListPreference) "1","3","7","14"; defeito "7"
    const val RINGTONE_URI = "ringtone_uri"              // String; vazio/ausente = alarme do sistema
    const val VIBRATE = "vibrate"                        // Boolean, defeito true
    const val GRADUAL_VOLUME = "gradual_volume"          // Boolean, defeito true
    const val SNOOZE_MINUTES = "snooze_minutes"          // String "5","10","15"; defeito "5"
    const val MAX_RING_MINUTES = "max_ring_minutes"      // String "1","3","5","10"; defeito "3"
    const val CALL_DISCREET = "call_discreet"            // Boolean, defeito true: em chamada/reunião, só aviso discreto
    const val BLUE_FLASH = "blue_flash"                  // Boolean, defeito true: ecrã desligado -> acende a piscar azul
    const val ONBOARDING_DONE = "onboarding_done"        // Boolean
    const val FORCED_ON = "forced_on"                    // StringSet de CalendarEvent.key
    const val FORCED_OFF = "forced_off"                  // StringSet de CalendarEvent.key
    const val SCHEDULED_ALARM_KEYS = "scheduled_alarm_keys" // StringSet de PlannedAlarm.alarmKey
    const val DAILY_SYNC_TIME = "daily_sync_time"        // String "HH:mm" (24 h); defeito "07:00"
    const val DAILY_SUMMARY = "daily_summary"            // Boolean, defeito false (só com MORNING_PICKER desligado)
    const val MORNING_PICKER = "morning_picker"          // Boolean, defeito true
    const val HIDDEN_CALENDAR_IDS = "hidden_calendar_ids" // StringSet de ids escondidos na UI; vazio = todos visíveis
    const val EVENT_OFFSETS = "event_offsets"            // String "eventKey:15,5;eventKey2:10" (minutos antes por evento)
}

class SettingsStore(context: Context) {
    var defaultOffsets: List<Int>          // sem repetidos, por ordem decrescente, cada um em 0..1440; nunca vazia (fallback [5])
    var autoCalendarIds: Set<Long>
    fun calendarOffsets(calendarId: Long): List<Int>?        // null = usa defaultOffsets
    fun setCalendarOffsets(calendarId: Long, offsets: List<Int>?)
    var includeKeywords: List<String>
    var excludeKeywords: List<String>
    var skipDeclined: Boolean
    var lookAheadDays: Int
    var ringtoneUri: String?
    var vibrate: Boolean
    var gradualVolume: Boolean
    var snoozeMinutes: Int
    var maxRingMinutes: Int
    var onboardingDone: Boolean
    var forcedOn: Set<String>
    var forcedOff: Set<String>
    var scheduledAlarmKeys: Set<String>
    var dailySyncTime: String              // "HH:mm"; inválida -> "07:00"
    val dailySyncHourMinute: Pair<Int, Int>
    var dailySummary: Boolean              // defeito false
    var morningPicker: Boolean             // defeito true
    var hiddenCalendarIds: Set<Long>       // só UI (lista e escolha da manhã); não muda o agendamento
    fun isCalendarVisible(calendarId: Long): Boolean
    fun eventOffsets(eventKey: String): List<Int>?           // null = calendário/defeito
    fun setEventOffsets(eventKey: String, offsets: List<Int>?)
    fun allEventOffsets(): Map<String, List<Int>>
    /** Liga/desliga à mão. autoEnabled = o que as regras dariam sem override. */
    fun setEventEnabled(event: CalendarEvent, on: Boolean, autoEnabled: Boolean)
    /** Remove chaves forcedOn/forcedOff/eventOffsets de eventos com begin < now - 24h (o begin é o sufixo depois do último '_'). */
    fun pruneEventKeys(now: Long)
    fun toRuleSettings(): pt.tocai.app.rules.RuleSettings
    companion object {
        fun parseOffsets(csv: String?): List<Int>          // "15, 5,x" -> [15,5]; inválidos ignorados; vazio -> []
        fun parseTime(raw: String?): Pair<Int, Int>?       // "07:05" -> (7, 5); inválido -> null
        fun formatTime(hour: Int, minute: Int): String     // (7, 5) -> "07:05"
        fun encodeEventOffsets(map: Map<String, List<Int>>): String
        fun decodeEventOffsets(raw: String?): Map<String, List<Int>>
    }
}

object CalendarRepository {
    fun hasPermission(ctx: Context): Boolean
    fun calendars(ctx: Context): List<CalendarInfo>                       // ordenados por nome
    fun events(ctx: Context, from: Long, to: Long): List<CalendarEvent>   // CalendarContract.Instances, ordenados por begin; exclui STATUS_CANCELED; preenche declined via SELF_ATTENDEE_STATUS
    fun eventsOrNull(ctx: Context, from: Long, to: Long): List<CalendarEvent>?   // igual, mas null se a leitura falhar (sem permissão, exceção, cursor nulo); events() = eventsOrNull() ?: emptyList()
}
```

### [rules] `pt.tocai.app.rules` (Kotlin puro, **sem** Android, testável na JVM)

```kotlin
data class RuleSettings(
    val defaultOffsets: List<Int>,
    val autoCalendarIds: Set<Long>,
    val calendarOffsets: Map<Long, List<Int>>,
    val includeKeywords: List<String>,
    val excludeKeywords: List<String>,
    val skipDeclined: Boolean,
    val forcedOn: Set<String>,
    val forcedOff: Set<String>,
    val eventOffsets: Map<String, List<Int>> = emptyMap(),   // por CalendarEvent.key
)

object AlarmPlanner {
    /** O que as regras automáticas decidem, ignorando forcedOn/forcedOff. */
    fun isAutoEnabled(event: CalendarEvent, rs: RuleSettings): Boolean
    /** Decisão final: forcedOff -> false; forcedOn -> true (mesmo noutros calendários; dia inteiro continua false); senão isAutoEnabled. */
    fun isEnabled(event: CalendarEvent, rs: RuleSettings): Boolean
    /** Prioridade: eventOffsets[event.key] -> calendarOffsets[calendarId] -> defaultOffsets -> [5]. */
    fun offsetsFor(event: CalendarEvent, rs: RuleSettings): List<Int>
    /** Despertadores com triggerAt > now para eventos ativos, ordenados por triggerAt. */
    fun plan(events: List<CalendarEvent>, rs: RuleSettings, now: Long): List<PlannedAlarm>
}
```

Regras de `isAutoEnabled`, por esta ordem:
1. evento de dia inteiro: false;
2. `declined` com `skipDeclined`: false;
3. calendário fora de `autoCalendarIds`: false (por defeito `autoCalendarIds` está vazio, por isso só tocam os eventos escolhidos);
4. `excludeKeywords` contida no título (sem distinguir maiúsculas nem acentos): false;
5. `includeKeywords` não vazia e nenhuma contida no título: false;
6. caso contrário, true.

`triggerAt = begin - offset*60_000`.

### [alarm] `pt.tocai.app.alarm`

```kotlin
data class AlarmPayload(val alarmKey: String, val title: String, val location: String, val begin: Long, val offsetMinutes: Int) {
    fun writeTo(intent: Intent): Intent
    companion object { fun from(intent: Intent): AlarmPayload?; fun of(p: PlannedAlarm): AlarmPayload }
}

object AlarmScheduler {
    fun canScheduleExact(ctx: Context): Boolean
    /** Lê o calendário e as definições, agenda com AlarmManager.setAlarmClock (via broadcast para AlarmReceiver), cancela os antigos,
     *  guarda scheduledAlarmKeys, chama NextAlarmWidget.updateAll(ctx) e devolve o plano. Não faz nada (devolve emptyList) sem permissões.
     *  Se a leitura do calendário falhar (CalendarRepository.eventsOrNull == null) não cancela nada nem mexe em scheduledAlarmKeys
     *  (devolve emptyList); um calendário vazio de verdade cancela normalmente (SyncDecision.keysToCancel). */
    fun syncNow(ctx: Context): List<PlannedAlarm>
    /** O plano atual sem agendar nada (para a UI). */
    fun currentPlan(ctx: Context): List<PlannedAlarm>
    fun nextAlarm(ctx: Context): PlannedAlarm?
    fun scheduleAt(ctx: Context, triggerAt: Long, payload: AlarmPayload)
    fun cancel(ctx: Context, alarmKey: String)
    fun scheduleSnooze(ctx: Context, payload: AlarmPayload, minutes: Int)   // alarmKey "snooze:" + original
    fun scheduleTest(ctx: Context, secondsFromNow: Int = 10)                // alarmKey "test"
}

class AlarmReceiver : BroadcastReceiver()   // AlarmPayload.from(intent) -> AlarmService.start(ctx, payload)
```

### [ring] `pt.tocai.app.alarm`

```kotlin
class AlarmService : Service() {
    companion object {
        const val ACTION_RING = "pt.tocai.app.action.RING"
        const val ACTION_STOP = "pt.tocai.app.action.STOP"
        const val ACTION_SNOOZE = "pt.tocai.app.action.SNOOZE"
        const val ACTION_ALARM_FINISHED = "pt.tocai.app.action.ALARM_FINISHED"  // broadcast com setPackage(packageName)
        fun start(ctx: Context, payload: AlarmPayload)   // startForegroundService com ACTION_RING
        fun stop(ctx: Context)
        fun snooze(ctx: Context)
        fun canSnooze(ctx: Context, payload: AlarmPayload?, now: Long = System.currentTimeMillis()): Boolean
    }
}
class AlarmActivity : AppCompatActivity()
```

O serviço:
- chama startForeground (tipo MEDIA_PLAYBACK em API 29+) com uma notificação no canal `NotificationChannels.ALARM`,
  categoria ALARM, ongoing, fullScreenIntent e contentIntent para a AlarmActivity e as ações Parar/Adiar
  (Adiar só quando `canSnooze`, ver abaixo);
- abre a AlarmActivity com `startActivity` (FLAG_ACTIVITY_NEW_TASK). Com o telemóvel desbloqueado/em uso, o
  Android mostra o fullScreenIntent só como heads-up e bloqueia aberturas de atividades a partir de segundo
  plano; com "Mostrar por cima de outras apps" (`Settings.canDrawOverlays`) a abertura é permitida
  (`PermissionsHelper.startActivityFromBackground`). Sem ela tenta-se na mesma e, se falhar, fica a notificação.
  Em chamada (modo discreto) nunca abre;
- toca o som com MediaPlayer em `USAGE_ALARM`, em loop (`ringtoneUri` ou o alarme do sistema, com fallback);
- se `gradualVolume`, sobe o volume ao longo de 30 s;
- vibra se `vibrate`;
- adquire um wakelock;
- para sozinho ao fim de `maxRingMinutes`;
- se tocar outro alarme enquanto este toca, substitui-o;
- com o ecrã desligado (`PowerManager.isInteractive` false), adquire durante 10 s um wakelock
  `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` (deprecated, mas funcional) como reforço do
  `setTurnScreenOn` da atividade.

**Modo chamada/reunião** (`call_discreet`, defeito true). `CallState.isInCall(ctx)` lê `AudioManager.mode`
(sem permissões novas) e considera chamada `MODE_RINGTONE`, `MODE_IN_CALL`, `MODE_IN_COMMUNICATION`
(VoIP: Teams, WhatsApp, Zoom, Meet…), `MODE_CALL_SCREENING` (API 30+), `MODE_CALL_REDIRECT` e
`MODE_COMMUNICATION_REDIRECT` (API 33+); a lógica pura é `CallState.isCallMode(mode, sdkInt)` (testada na JVM).
Se o despertador disparar em chamada:
- não abre a AlarmActivity, não pede foco de áudio, não toca em loop nem vibra em loop;
- a notificação do primeiro plano passa a ser a discreta, no canal `ALARM_CALL` ("Despertadores em chamada",
  IMPORTANCE_HIGH, sem som próprio), **sem** fullScreenIntent, título "⏰ HH:mm · <título>" (hora do evento),
  texto "Estás em chamada — aviso discreto", ações Parar/Adiar (as mesmas do serviço) e toque que abre a AlarmActivity;
- toca o som de notificação por defeito (`USAGE_NOTIFICATION`) 2 vezes, com 1,5 s de intervalo, e vibra 300 ms
  (se `vibrate`);
- no fim (ou ao fim de 12 s, no máximo) sai do primeiro plano com `STOP_FOREGROUND_DETACH` (a notificação fica),
  pára o serviço e pede `SyncWorker.runNow`. Parar/Adiar na notificação voltam a arrancar o serviço, que trata
  a ação e apaga a notificação.
**Quando há "Adiar".** `SnoozePolicy.canSnooze(offsetMinutes, now, begin, snoozeMinutes)` (Kotlin puro): só se
o despertador foi marcado com `offsetMinutes >= 10` **e** `now + snoozeMinutes < begin` (o adiamento ainda toca
antes do início). Aplica-se à AlarmActivity, à notificação normal e à discreta; o alarme de teste (offset 0) fica
só com Parar. Se chegar um `ACTION_SNOOZE` (ex.: notificação antiga) quando já não faz sentido, o serviço só pára.

Enquanto toca normalmente, o serviço verifica o modo de áudio a cada 2 s; se começar uma chamada, fecha a
AlarmActivity (`ACTION_ALARM_FINISHED`) e passa ao modo discreto. Com `call_discreet` desligado, comportamento antigo.

A atividade:
- mostra a hora atual em grande, o título, "Começa às HH:mm" e o local;
- tem os botões **Parar** e **Adiar N min**; o **Adiar** só aparece quando faz sentido, senão fica só o Parar
  (a toda a largura); reavalia a cada minuto;
- desliza para parar é opcional;
- fecha-se ao receber `ACTION_ALARM_FINISHED`;
- o botão voltar não a fecha;
- com `blue_flash` (defeito true), o fundo pulsa entre `tocai_alarm_bg` (#0B1F3A) e `tocai_flash_bright` (#1A73E8)
  com um `ValueAnimator` de cor (`BluePulse`, 1 s por meio ciclo, REVERSE); o texto é branco e o botão Parar
  é branco com texto azul escuro, legível nas duas cores.

**Luz de aviso** (`ui/FlashActivity`, `blue_flash`). Muitos telemóveis já não têm LED; quando o Tocaí avisa,
o ecrã está desligado e não há chamada (`FlashActivity.shouldFlash`), o aviso acende o ecrã ~10 s com o fundo
azul a piscar, o ícone do Tocaí e o título/texto da notificação. Toque em qualquer sítio ou 10 s fecham;
**Abrir** pede o desbloqueio (`requestDismissKeyguard`) e abre o destino. É lançada só por fullScreenIntent
(com o ecrã ligado mantém-se o comportamento normal):
- **escolha da manhã:** o fullScreenIntent da notificação `MORNING` passa a ser a FlashActivity (destino
  MorningPicker). Ao fechar (toque/tempo) a FlashActivity abre a `MorningPickerActivity`, que fica por trás do
  bloqueio e aparece ao desbloquear, como antes. Escolhida por ser a opção mais simples: um só fullScreenIntent,
  sem duas atividades a disputar o ecrã. Se o sistema não deixar abrir o picker (ex.: a luz já fora do ecrã),
  a notificação continua lá e abre-o;
- **marcação nova:** além da notificação normal (canal `tocai_new_events`, IMPORTANCE_DEFAULT, que não permite
  ecrã inteiro), é publicada uma notificação extra sem som no canal `FLASH` ("Luz de aviso", IMPORTANCE_HIGH;
  id `FlashActivity.NEW_EVENT_FLASH_ID`, `timeoutAfter` 30 s) só para o fullScreenIntent; a FlashActivity
  apaga-a ao fechar. **Abrir** leva à `MainActivity` (a notificação da marcação, com Pôr despertador, continua lá);
- **despertador:** usa a própria AlarmActivity a pulsar (acima).

### [sync] `pt.tocai.app` / `pt.tocai.app.sync`

```kotlin
class TocaiApp : Application()   // onCreate: NotificationChannels.create(this); SyncWorker.ensureScheduled(this); DailySync.schedule(this)
object NotificationChannels {
    const val ALARM = "tocai_alarm"     // IMPORTANCE_HIGH, sem som no canal (o serviço toca), bypassDnd, lockscreen PUBLIC
    const val STATUS = "tocai_status"   // IMPORTANCE_LOW
    const val MORNING = "tocai_morning" // "Escolha da manhã": IMPORTANCE_HIGH, sem som nem vibração
    const val ALARM_CALL = "tocai_alarm_call" // "Despertadores em chamada": IMPORTANCE_HIGH, sem som nem vibração no canal
    const val FLASH = "tocai_flash"     // "Luz de aviso": IMPORTANCE_HIGH, sem som; só lança a FlashActivity
    fun create(ctx: Context)
}
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        fun ensureScheduled(ctx: Context)   // periódico 30 min (KEEP) + vigia de content URI ao calendário (KEEP)
        fun runNow(ctx: Context)            // one-time REPLACE
    }
}
class SystemEventsReceiver : BroadcastReceiver()  // ensureScheduled + runNow + DailySync.schedule
object DailySync {
    fun schedule(ctx: Context)   // próximo disparo à hora dailySyncTime (setExactAndAllowWhileIdle RTC_WAKEUP; sem alarmes exatos: setAndAllowWhileIdle)
    fun cancel(ctx: Context)
    fun nextTriggerAt(now: Long, hour: Int, minute: Int): Long   // próxima ocorrência (hora local) estritamente depois de now
}
class DailySyncReceiver : BroadcastReceiver()   // goAsync + thread: AlarmScheduler.syncNow, DailySync.schedule, escolha da manhã (ou resumo do dia)
```

**Agendamento diário.** O WorkManager pode ser adiado horas pelas restrições de bateria, por isso há também
um disparo diário garantido com o AlarmManager: todos os dias à hora `daily_sync_time` (defeito 07:00) o
`DailySyncReceiver` corre `AlarmScheduler.syncNow`, mesmo que o utilizador nunca abra a app, e reagenda o dia
seguinte. É agendado em `TocaiApp.onCreate`, no `SystemEventsReceiver` (arranque, hora/fuso, atualização,
permissão de alarmes exatos) e quando a hora muda nas Definições. O PendingIntent é um broadcast
`FLAG_IMMUTABLE`, com um request code fixo, por isso reagendar substitui sempre o anterior.

**Escolha da manhã.** Se `morning_picker` estiver ligado (defeito) e houver permissão de notificações e pelo
menos um evento por escolher hoje (não de dia inteiro, ainda por começar, num calendário visível), o receiver
publica no canal `MORNING` uma notificação (id `MorningPickerActivity.NOTIFICATION_ID`) com
`setFullScreenIntent(pi, true)`, `contentIntent` igual, categoria REMINDER, autoCancel e `setTimeoutAfter` até ao fim
do dia, onde `pi` abre a `MorningPickerActivity`. Com o telemóvel bloqueado/ecrã desligado, o sistema lança a
atividade por trás do bloqueio (não usa showWhenLocked) e ela fica visível assim que o utilizador desbloqueia.
Com o telemóvel em uso, ou se `NotificationManager.canUseFullScreenIntent()` for false (API 34+, permissão
"ecrã inteiro" retirada), fica a notificação heads-up (silenciosa); tocar nela abre o ecrã. Com o ecrã ligado,
sem chamada e com "Mostrar por cima de outras apps", o receiver abre também a `MorningPickerActivity`
diretamente (`PermissionsHelper.startActivityFromBackground`). Sem eventos hoje, não publica nada.
A `FlashActivity` não precisa deste mecanismo: só é usada com o ecrã desligado, onde o fullScreenIntent abre
sempre a atividade.

**Resumo do dia.** Só quando a escolha da manhã está desligada (a preferência depende dela). Se `daily_summary` estiver ligado (e houver permissão de notificações), o receiver publica no
canal `STATUS` a notificação "Despertadores de hoje" com as horas e os títulos dos despertadores que faltam hoje
(InboxStyle), ou "Sem despertadores hoje". Tocar abre a `MainActivity`. Sem permissão, não publica nada.

O `doWork` chama `AlarmScheduler.syncNow`. Se for o trabalho de vigia (tag `"calendar-watch"`), volta a pôr-se em fila com
`APPEND_OR_REPLACE`, usando `addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)`, um atraso de 5 s
e um máximo de 1 min.

### [main-ui] `pt.tocai.app.ui` e `pt.tocai.app.util`

```kotlin
object TimeFormat {   // Locale("pt","PT")
    fun hm(t: Long): String                 // "13:10"
    fun relativeDay(t: Long, now: Long = System.currentTimeMillis()): String   // "hoje", "amanhã", "quinta-feira, 5 de novembro"
    fun dayKey(t: Long): Int
    fun minutesLabel(m: Int): String        // "5 min", "1 h", "1 h 30 min"
}
class MainActivity : AppCompatActivity()
class EventsAdapter(...) : RecyclerView.Adapter<...>
```

A MainActivity tem:
- uma MaterialToolbar com menu: Escolher os de hoje (abre `MorningPickerActivity`), Calendários visíveis
  (`VisibleCalendarsDialog.show`), Testar despertador (`AlarmScheduler.scheduleTest`) e Definições (abre `SettingsActivity`);
- um cartão do "Próximo despertador", com os minutos por defeito e a dica "Liga o interruptor dos eventos que querem
  despertador. Todas as manhãs às HH:mm…";
- um cartão de aviso quando falta alguma permissão (`PermissionsHelper.firstProblem`), com o botão "Resolver";
- a lista dos próximos eventos (só calendários visíveis), agrupada por dia, com cor do calendário, hora, título, calendário, as horas dos despertadores ou "já passou",
  um botão "⏱ N min antes" nos eventos ligados (abre `OffsetsDialog`: como os outros, à hora, 1, 5, 10, 15, 30, 60, 15+5 ou outro;
  guarda com `SettingsStore.setEventOffsets`), um interruptor (escondido nos eventos de dia inteiro) (`SettingsStore.setEventEnabled` seguido de `AlarmScheduler.syncNow`) e um botão "Relógio" que cria o alarme na app Relógio
  (`AlarmClock.ACTION_SET_ALARM` com `EXTRA_SKIP_UI`, só nas próximas 24 h);
- um SwipeRefresh? **Não**: não está nas dependências. Atualiza em `onResume`.

Se `!onboardingDone`, a MainActivity abre a `OnboardingActivity` e termina.

### [morning] `pt.tocai.app.ui.MorningPickerActivity`

- Título "Bom dia! O que tens hoje?", data, lista dos eventos de **hoje** de agora até ao fim do dia (calendários
  visíveis; os de dia inteiro aparecem sem caixa), com caixa, hora, título, calendário e a hora do despertador
  (tocar nela abre o `OffsetsDialog.pick`, que só escolhe: a mudança fica em memória, `PendingOffsets`, guardada
  em `onSaveInstanceState`, e a lista já a mostra). As caixas vêm marcadas para os eventos que já estão ligados (`isEnabled`).
- "Confirmar despertadores": `SettingsStore.setEventOffsets` para cada mudança de minutos pendente,
  `SettingsStore.setEventEnabled` para cada mudança de caixa + `AlarmScheduler.syncNow`;
  mostra "N despertadores agendados" e fecha. "Agora não" fecha sem mudar nada (descarta caixas e minutos). "Ver amanhã" mostra o dia seguinte.
- Sem eventos: mensagem e botão "Fechar". Ao abrir (e em `onNewIntent`), cancela a notificação da manhã.

### [settings-ui] `pt.tocai.app.ui`

```kotlin
class SettingsActivity : AppCompatActivity()             // aloja SettingsFragment, toolbar com "voltar"
class SettingsFragment : PreferenceFragmentCompat()      // res/xml/settings_preferences.xml
object CalendarPickerDialog {
    fun show(activity: AppCompatActivity, onSaved: () -> Unit = {})  // multi-escolha de autoCalendarIds + por calendário "minutos antes" opcional
}
```

As preferências usam exatamente as `SettingsKeys`:
- `default_offsets`: EditTextPreference, validada com `parseOffsets`;
- `include_keywords` e `exclude_keywords`: EditTextPreference;
- `skip_declined`, `vibrate` e `gradual_volume`: SwitchPreferenceCompat;
- `call_discreet` ("Em chamadas, só aviso discreto") e `blue_flash` ("Acender o ecrã a piscar azul"):
  SwitchPreferenceCompat, defeito true, na categoria Som;
- `look_ahead_days`, `snooze_minutes` e `max_ring_minutes`: ListPreference com os valores do contrato;
- `ringtone_uri`: Preference simples que abre `RingtoneManager.ACTION_RINGTONE_PICKER` (TYPE_ALARM) e guarda o URI;
- "Calendários visíveis" (`settings_visible_calendars`): abre o `VisibleCalendarsDialog` (multi-escolha, guarda
  `hidden_calendar_ids`; só afeta a UI: um evento escolhido num calendário escondido continua a tocar);
- categoria **Avançado**: "Ligar automaticamente todos os eventos de certos calendários" (abre o `CalendarPickerDialog`;
  vazio = desligado, o defeito) e os filtros de palavras-chave / recusados, que só se aplicam a esses calendários;
- categoria "Escolha da manhã": `daily_sync_time` ("Hora da escolha diária"): Preference (não persistente) que abre um `MaterialTimePicker` de 24 h,
  guarda com `SettingsStore.dailySyncTime` e mostra a hora no summary; `morning_picker` ("Abrir escolha da manhã",
  defeito true, `disableDependentsState`) e `daily_summary` ("Resumo do dia", defeito false, depende de
  `morning_picker` desligado): SwitchPreferenceCompat. Ao mudar qualquer um, chama `DailySync.schedule`;
- "Testar despertador" e "Sobre" (com a versão).

Depois de qualquer alteração, a app chama `SyncWorker.runNow`.

### [onboarding] `pt.tocai.app.ui` e `pt.tocai.app.util`

```kotlin
object PermissionsHelper {
    data class Problem(val message: String, val action: String, val intent: Intent?, val runtimePermissions: Array<String>)
    fun runtimePermissionsNeeded(ctx: Context): Array<String>   // READ_CALENDAR, POST_NOTIFICATIONS (33+)
    fun notificationsEnabled(ctx: Context): Boolean
    fun canUseFullScreen(ctx: Context): Boolean                 // true abaixo da API 34
    fun canDrawOverlays(ctx: Context): Boolean                  // Settings.canDrawOverlays
    fun overlayIntent(ctx: Context): Intent                     // ACTION_MANAGE_OVERLAY_PERMISSION com package
    fun startActivityFromBackground(ctx: Context, intent: Intent): Boolean  // só com canDrawOverlays
    fun ignoringBatteryOptimizations(ctx: Context): Boolean
    fun exactAlarmIntent(ctx: Context): Intent
    fun fullScreenIntent(ctx: Context): Intent
    fun batteryIntent(ctx: Context): Intent                     // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS com package
    fun appSettingsIntent(ctx: Context): Intent
    fun notificationSettingsIntent(ctx: Context): Intent
    /** Primeiro problema por ordem: calendário, notificações, alarmes exatos, ecrã inteiro, por cima de outras apps, bateria. null = tudo ok. */
    fun firstProblem(ctx: Context): Problem?
}
class OnboardingActivity : AppCompatActivity()
```

O onboarding é um assistente de passos (boas-vindas, calendário, notificações, alarmes exatos, ecrã inteiro,
"Abrir o despertador por cima de tudo" (SYSTEM_ALERT_WINDOW, marcado "Recomendado", não obrigatório), bateria,
escolha da manhã, testar):
- cada passo tem um ícone, um texto curto e um botão;
- um passo já resolvido fica com um visto;
- no fim marca `onboardingDone` e abre a MainActivity.

**Cada utilizador escolhe os seus eventos.** A app é genérica e não pré-seleciona nenhum calendário.
- Não há passo obrigatório de calendários: o botão "Começar" está sempre ativo. O passo "Escolha da manhã" explica
  "Todas as manhãs às 07:00 o Tocaí abre-se para escolheres os despertadores do dia" e permite mudar a hora
  (`MaterialTimePicker`, guarda `dailySyncTime` e chama `DailySync.schedule`).
- O ecrã inteiro (API 34+) passa a servir também para a escolha da manhã abrir sozinha.
- Na MainActivity, cada evento mostra o nome e a cor do seu calendário; os eventos de calendários automáticos
  (opção avançada) mostram "automático".

### [widget] `pt.tocai.app.widget`

```kotlin
class NextAlarmWidget : AppWidgetProvider() { companion object { fun updateAll(ctx: Context) } }
class NextAlarmTileService : TileService()
```

O widget:
- tem 2x1 células e fundo arredondado;
- mostra "⏰ 13:10" e o título do próximo despertador (via `AlarmScheduler.nextAlarm`), ou "Sem despertadores";
- ao tocar, abre a MainActivity;
- usa `res/xml/widget_next_alarm_info.xml` e `res/layout/widget_next_alarm.xml`;
- `updateAll` não pode falhar sem permissões (usa try/catch).

O tile:
- mostra o próximo despertador como subtítulo (API 29+);
- ao clicar, abre a MainActivity com `startActivityAndCollapse`, usando a variante com PendingIntent em API 34+.

### [infra]

- `settings.gradle.kts`, `build.gradle.kts` e `app/build.gradle.kts`:
  - a assinatura vem das variáveis de ambiente `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`, com fallback para debug;
  - `versionCode` vem de `GITHUB_RUN_NUMBER`;
  - `lint { abortOnError = false; checkReleaseBuilds = false }`.
- `gradle.properties`.
- `.github/workflows/build.yml`:
  - setup-java 17, `android-actions/setup-android@v3` e setup-gradle 8.10.2;
  - corre `gradle :app:testDebugUnitTest :app:assembleRelease`;
  - publica uma release `v1.0.N` com `Tocai.apk`.
- Manifest, temas, cores, ícones (adaptive icon em `mipmap-anydpi-v26`, com foreground vetorial), README (pt-PT), LICENSE (MIT) e `docs/PRIVACIDADE.md`.

## Fluxo

```
Calendário (CalendarContract) ──► CalendarRepository ──► AlarmPlanner (regras) ──► AlarmScheduler ──► AlarmManager.setAlarmClock
       ▲ mudança                                                                                         │ hora
   SyncWorker (vigia + 30 min) ◄── SystemEventsReceiver (boot/hora) ──► DailySync (AlarmManager, 1×/dia) ──► DailySyncReceiver ──► syncNow + escolha da manhã (MorningPickerActivity via fullScreenIntent)                                   AlarmReceiver
                                                                                                         ▼
                                                                    AlarmActivity ◄── fullScreen ── AlarmService (som+vibração)
                                                                    (em chamada: só notificação ALARM_CALL + 2 toques)
```
