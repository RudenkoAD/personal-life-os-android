# Implementation contracts

Package `com.personallifeos.mobile`. Kotlin 2.2.21, AGP 8.13.2, Gradle 8.13, Java17, minSdk26/target36. Compose BOM 2025.10.01, Glance1.1.1, WorkManager2.10.5, serialization1.9.0, coroutines1.10.2, OkHttp4.12.0. Root owns build files, manifest, app/Application, work scheduler, packaging and integration.

## Model (calendar/domain worker owns model/ and its tests)

`model.LifeState(revision:Long=0, settings:Settings=Settings(), cards:List<Card>=emptyList(), boards:List<Board>=emptyList(), tags:List<Scope>=emptyList(), sources:List<Source>=emptyList(), events:List<CalendarEvent>=emptyList(), calendarSeries:List<EventSeries>=emptyList())`. Serializable data classes must match service fields, with sensible defaults. Other unknown server fields are ignored; clients only POST actions, never replace the aggregate.

Card matches service: id,title,notes,type,placement,boardId,columnId,childBoardId?,tags,steps,done,start?,end?,archived,createdAt,recurrenceId?. `Scope(id,title,color)`, `Board(id,title,columns:List<BoardColumn>)`, `BoardColumn(id,title)`, `Settings(autoArchiveCompleted:Boolean=true)`. CalendarEvent and EventSeries follow source TS exactly.

`@Serializable Mutation(id:String, at:String, action:JsonObject)` — immutable UUIDv4 and strict ISO instant with milliseconds. `object Actions` factories: `capture(title:String):JsonObject`, `complete(id:String,done:Boolean=true)`, `update(id:String,title:String,notes:String)`, `schedule(id:String,start:Instant,end:Instant)`, `move(id:String,boardId:String,columnId:String)`, `eventCreate(title:String,date:LocalDate,time:LocalTime,durationMinutes:Int,allDay:Boolean=false):JsonObject`. No synthetic task fields go to the server.

`object StateProjection { fun apply(state:LifeState, mutation:Mutation):LifeState }` — optimistic projection of those actions; deterministic capture id `m_<mutation.id>_0`; archive completion according to setting. No server revision increment in local projection. Failures must not erase queued actions.

`data class CalendarItem(id:String,title:String,start:Instant,end:Instant,allDay:Boolean,color:String,notes:String="",location:String="",cardId:String?=null,seriesId:String?=null,occurrenceDate:String?=null,sourceId:String="local",done:Boolean=false)`.
`object CalendarProjection { val zone:ZoneId; fun items(state:LifeState,from:LocalDate,toExclusive:LocalDate):List<CalendarItem> }` includes scheduled active tasks, enabled external sources, expanded local series/exceptions. Moscow timezone parity, half-open ranges, all-day end exclusive. Render/don't mutate.

## Data (data worker owns data/ and its tests)

`data.MobileSnapshot(state:LifeState?=null,pending:Int=0,syncing:Boolean=false,error:String?=null,connected:Boolean=false,lastSyncedAt:String?=null,blocked:Boolean=false)`.
`class LifeRepository`: `companion object fun get(context:Context):LifeRepository`; `val snapshots:StateFlow<MobileSnapshot>`; `suspend fun snapshot():MobileSnapshot` awaits initial disk load; `fun baseUrl():String`; `suspend fun connect(baseUrl:String,password:String)`; `suspend fun connectToken(baseUrl:String,token:String)`; `suspend fun enqueue(action:JsonObject)`; `suspend fun sync()`; `suspend fun disconnect(discardPending:Boolean=false)` (refuse dropping pending by default). Local token, confirmed snapshot + outbox encrypted via Android Keystore and atomic file; account/server changes never mix caches or discard pending silently.

Login via password form with Origin, no redirects, cookie only in memory then POST /api/tokens (name Android, scope write). Persist token, never password/cookie. GET /api/state?mutations=... -> {state,applied}; POST /api/actions request `{revision,mutation}` -> full `LifeState` response. No private credentials in logs or APK. HTTPS origin only (localhost HTTP allowed only debug fixture). Use separate state and network mutexes: enqueue never waits on network. Retry same mutation id/at after uncertain errors; 409 reload + rebase; 400/422 preserve blocked outbox. Bound queue <=100 pending IDs.

After enqueue/connection call `work.SyncScheduler.enqueue(context)`; after persisted state changes call `widgets.WidgetUpdates.update(context)` (suspend). These are owned by root/widgets worker; can be unresolved while parallel work lands. Call widget updates OUTSIDE state lock. Network sync also updates widgets. Don't invoke actual network in provideGlance.

## Widgets (widgets worker owns widgets/ and res/xml+res/drawable widget files only)

`widgets.WidgetUpdates.update(context)` suspend updates all three providers. `InboxWidgetReceiver`, `AgendaWidgetReceiver`, `MonthWidgetReceiver` (Glance1.1.1), `WidgetConfigActivity` for Inbox/board/list filtering by appWidgetId. Root registers activities/receivers. Use `data.LifeRepository.get(context).snapshot()` and CalendarProjection. Complete callback queues Actions.complete and immediately updates cached view; network handled by WorkManager.

Deep links use explicit `ui.MainActivity` intents with extras: `screen` = inbox/calendar/settings/capture/task/event, `id` optional, `date` ISO LocalDate optional. MainActivity singleTop handles onNewIntent. Capture/widget/calendar controls may launch the app's compact sheet. No text input inside RemoteViews.

Widget XML names: widget_inbox_info, widget_agenda_info, widget_month_info. Widgets resizable width/height, header+add+refresh, compact wrap-aware list, accessible touch targets, daily list and month grid with navigation/today/dots. Per-instance preferences; remember month independent between instances. Data cached offline, no fake success when pending/blocked. Empty/auth/error states actionable. Avoid Google/Trello logos, use same layout/function patterns with Life OS identity.

## App UI (UI worker owns ui/ only)

`ui.MainActivity` is launcher/singleTop entry, Compose Material3. Screens Inbox/calendar/settings; password login (default service https://personal-life-os.51-250-78-132.sslip.io), alternative token in collapsed advanced settings. Collect repository snapshots. Fast capture sheet; task detail/edit/complete/move/schedule; agenda/month selection and event detail, event creation via eventCreate; imported event read-only; pin widget buttons in settings using AppWidgetManager requestPinAppWidget for three receiver classes. Settings queue/error/retry, disconnect with explicit pending guard. Compact UI Russian labels, indigo blue, dark/light/system theme, 48dp touch targets but dense rows. No embedded WebView shell.

## Testing

Domain/JVM tests with fake data; HTTP via MockWebServer; actual server tests READ ONLY only using credentials outside repo. No production tasks/events/test tokens created automatically. User credentials entered at device setup. Root will build/install APK and test with isolated fixture backend/emulator if available. No browser opening without explicit permission.
