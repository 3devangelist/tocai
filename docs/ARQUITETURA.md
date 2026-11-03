# Tocaí — Arquitetura e contrato entre módulos

> **Tocaí** ("toca aí") transforma eventos do calendário do telemóvel em **despertadores a sério**:
> som de alarme em loop, vibração e ecrã inteiro, mesmo com o telemóvel bloqueado.
> Não são notificações. Interface em português de Portugal (pt-PT).

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
  ui/OnboardingActivity.kt         [onboarding]
  util/PermissionsHelper.kt        [onboarding]
  util/TimeFormat.kt               [main-ui]
  widget/NextAlarmWidget.kt        [widget]
  widget/NextAlarmTileService.kt   [widget]
app/src/test/java/pt/tocai/app/rules/AlarmPlannerTest.kt   [rules]
app/src/test/java/pt/tocai/app/sync/DailySyncTest.kt       [sync]
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
  `@color/tocai_on_alarm` (#FFFFFF), `@color/tocai_warning_bg` (#FFF3CD),
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
| `.alarm.AlarmActivity` | Activity | showWhenLocked, turnScreenOn, excludeFromRecents, launchMode singleTask, taskAffinity "", theme Theme.Tocai.Alarm |
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
    const val ONBOARDING_DONE = "onboarding_done"        // Boolean
    const val FORCED_ON = "forced_on"                    // StringSet de CalendarEvent.key
    const val FORCED_OFF = "forced_off"                  // StringSet de CalendarEvent.key
    const val SCHEDULED_ALARM_KEYS = "scheduled_alarm_keys" // StringSet de PlannedAlarm.alarmKey
    const val DAILY_SYNC_TIME = "daily_sync_time"        // String "HH:mm" (24 h); defeito "07:00"
    const val DAILY_SUMMARY = "daily_summary"            // Boolean, defeito true
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
    var dailySummary: Boolean
    /** Liga/desliga à mão. autoEnabled = o que as regras dariam sem override. */
    fun setEventEnabled(event: CalendarEvent, on: Boolean, autoEnabled: Boolean)
    /** Remove chaves forcedOn/forcedOff de eventos com begin < now - 24h (o begin é o sufixo depois do último '_'). */
    fun pruneEventKeys(now: Long)
    fun toRuleSettings(): pt.tocai.app.rules.RuleSettings
    companion object {
        fun parseOffsets(csv: String?): List<Int>          // "15, 5,x" -> [15,5]; inválidos ignorados; vazio -> []
        fun parseTime(raw: String?): Pair<Int, Int>?       // "07:05" -> (7, 5); inválido -> null
        fun formatTime(hour: Int, minute: Int): String     // (7, 5) -> "07:05"
    }
}

object CalendarRepository {
    fun hasPermission(ctx: Context): Boolean
    fun calendars(ctx: Context): List<CalendarInfo>                       // ordenados por nome
    fun events(ctx: Context, from: Long, to: Long): List<CalendarEvent>   // CalendarContract.Instances, ordenados por begin; exclui STATUS_CANCELED; preenche declined via SELF_ATTENDEE_STATUS
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
)

object AlarmPlanner {
    /** O que as regras automáticas decidem, ignorando forcedOn/forcedOff. */
    fun isAutoEnabled(event: CalendarEvent, rs: RuleSettings): Boolean
    /** Decisão final: forcedOff -> false; forcedOn -> true (mesmo noutros calendários; dia inteiro continua false); senão isAutoEnabled. */
    fun isEnabled(event: CalendarEvent, rs: RuleSettings): Boolean
    fun offsetsFor(event: CalendarEvent, rs: RuleSettings): List<Int>
    /** Despertadores com triggerAt > now para eventos ativos, ordenados por triggerAt. */
    fun plan(events: List<CalendarEvent>, rs: RuleSettings, now: Long): List<PlannedAlarm>
}
```

Regras de `isAutoEnabled`, por esta ordem:
1. evento de dia inteiro: false;
2. `declined` com `skipDeclined`: false;
3. calendário fora de `autoCalendarIds`: false;
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
     *  guarda scheduledAlarmKeys, chama NextAlarmWidget.updateAll(ctx) e devolve o plano. Não faz nada (devolve emptyList) sem permissões. */
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
    }
}
class AlarmActivity : AppCompatActivity()
```

O serviço:
- chama startForeground (tipo MEDIA_PLAYBACK em API 29+) com uma notificação no canal `NotificationChannels.ALARM`,
  categoria ALARM, fullScreenIntent para AlarmActivity e as ações Parar/Adiar;
- toca o som com MediaPlayer em `USAGE_ALARM`, em loop (`ringtoneUri` ou o alarme do sistema, com fallback);
- se `gradualVolume`, sobe o volume ao longo de 30 s;
- vibra se `vibrate`;
- adquire um wakelock;
- para sozinho ao fim de `maxRingMinutes`;
- se tocar outro alarme enquanto este toca, substitui-o.

A atividade:
- mostra a hora atual em grande, o título, "Começa às HH:mm" e o local;
- tem os botões **Parar** e **Adiar N min**;
- desliza para parar é opcional;
- fecha-se ao receber `ACTION_ALARM_FINISHED`;
- o botão voltar não a fecha.

### [sync] `pt.tocai.app` / `pt.tocai.app.sync`

```kotlin
class TocaiApp : Application()   // onCreate: NotificationChannels.create(this); SyncWorker.ensureScheduled(this); DailySync.schedule(this)
object NotificationChannels {
    const val ALARM = "tocai_alarm"     // IMPORTANCE_HIGH, sem som no canal (o serviço toca), bypassDnd, lockscreen PUBLIC
    const val STATUS = "tocai_status"   // IMPORTANCE_LOW
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
class DailySyncReceiver : BroadcastReceiver()   // goAsync + thread: AlarmScheduler.syncNow, DailySync.schedule, resumo do dia
```

**Agendamento diário.** O WorkManager pode ser adiado horas pelas restrições de bateria, por isso há também
um disparo diário garantido com o AlarmManager: todos os dias à hora `daily_sync_time` (defeito 07:00) o
`DailySyncReceiver` corre `AlarmScheduler.syncNow`, mesmo que o utilizador nunca abra a app, e reagenda o dia
seguinte. É agendado em `TocaiApp.onCreate`, no `SystemEventsReceiver` (arranque, hora/fuso, atualização,
permissão de alarmes exatos) e quando a hora muda nas Definições. O PendingIntent é um broadcast
`FLAG_IMMUTABLE`, com um request code fixo, por isso reagendar substitui sempre o anterior.

**Resumo do dia.** Se `daily_summary` estiver ligado (e houver permissão de notificações), o receiver publica no
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
- uma MaterialToolbar com menu: Calendários (`CalendarPickerDialog.show`), Testar despertador (`AlarmScheduler.scheduleTest`) e Definições (abre `SettingsActivity`);
- um cartão do "Próximo despertador";
- um cartão de aviso quando falta alguma permissão (`PermissionsHelper.firstProblem`), com o botão "Resolver";
- a lista dos próximos eventos, agrupada por dia, com cor do calendário, hora, título, calendário, as horas dos despertadores ou "já passou",
  um interruptor (`SettingsStore.setEventEnabled` seguido de `AlarmScheduler.syncNow`) e um botão "Relógio" que cria o alarme na app Relógio
  (`AlarmClock.ACTION_SET_ALARM` com `EXTRA_SKIP_UI`, só nas próximas 24 h);
- um SwipeRefresh? **Não**: não está nas dependências. Atualiza em `onResume`.

Se `!onboardingDone`, a MainActivity abre a `OnboardingActivity` e termina.

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
- `look_ahead_days`, `snooze_minutes` e `max_ring_minutes`: ListPreference com os valores do contrato;
- `ringtone_uri`: Preference simples que abre `RingtoneManager.ACTION_RINGTONE_PICKER` (TYPE_ALARM) e guarda o URI;
- "Calendários automáticos": Preference que abre o `CalendarPickerDialog`;
- `daily_sync_time` ("Agendamento diário"): Preference (não persistente) que abre um `MaterialTimePicker` de 24 h,
  guarda com `SettingsStore.dailySyncTime` e mostra a hora no summary; `daily_summary` ("Resumo do dia"):
  SwitchPreferenceCompat. Ao mudar qualquer um, chama `DailySync.schedule`;
- "Testar despertador" e "Sobre" (com a versão).

Depois de qualquer alteração, a app chama `SyncWorker.runNow`.

### [onboarding] `pt.tocai.app.ui` e `pt.tocai.app.util`

```kotlin
object PermissionsHelper {
    data class Problem(val message: String, val action: String, val intent: Intent?, val runtimePermissions: Array<String>)
    fun runtimePermissionsNeeded(ctx: Context): Array<String>   // READ_CALENDAR, POST_NOTIFICATIONS (33+)
    fun notificationsEnabled(ctx: Context): Boolean
    fun canUseFullScreen(ctx: Context): Boolean                 // true abaixo da API 34
    fun ignoringBatteryOptimizations(ctx: Context): Boolean
    fun exactAlarmIntent(ctx: Context): Intent
    fun fullScreenIntent(ctx: Context): Intent
    fun batteryIntent(ctx: Context): Intent                     // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS com package
    fun appSettingsIntent(ctx: Context): Intent
    fun notificationSettingsIntent(ctx: Context): Intent
    /** Primeiro problema por ordem: calendário, notificações, alarmes exatos, ecrã inteiro, bateria. null = tudo ok. */
    fun firstProblem(ctx: Context): Problem?
}
class OnboardingActivity : AppCompatActivity()
```

O onboarding é um assistente de passos (boas-vindas, calendário, notificações, alarmes exatos, ecrã inteiro, bateria,
escolher calendários, testar):
- cada passo tem um ícone, um texto curto e um botão;
- um passo já resolvido fica com um visto;
- no fim marca `onboardingDone` e abre a MainActivity.

**Cada utilizador escolhe os seus calendários.** A app é genérica e não pré-seleciona nenhum calendário por nome.
- O passo "Escolher calendários" do onboarding é **obrigatório**: o botão "Começar" fica desativado enquanto não houver
  pelo menos um calendário automático ou o utilizador não tocar em "Ligo eventos à mão".
- O diálogo mostra todos os calendários do telemóvel, agrupados por conta, com a cor de cada um.
- Na MainActivity, cada evento mostra o nome e a cor do seu calendário. O cartão do topo diz em que calendários o
  despertador está automático (ex.: "Automático em: Trabalho, Consultas") e permite mudá-los com um toque.

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
   SyncWorker (vigia + 30 min) ◄── SystemEventsReceiver (boot/hora) ──► DailySync (AlarmManager, 1×/dia) ──► DailySyncReceiver ──► syncNow + resumo do dia                                   AlarmReceiver
                                                                                                         ▼
                                                                    AlarmActivity ◄── fullScreen ── AlarmService (som+vibração)
```
