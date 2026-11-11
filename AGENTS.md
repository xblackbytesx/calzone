# CalZone Android: agent guide and decision register

The phone app for CalZone, the household's calendar and contacts. The core,
the sync engine (`crates/client`), the FFI crate and the server live in the
sibling repo `/workspace/calzone-server`; its `AGENTS.md` is the contract
for both repos (phase 4: decisions 14, 15, 16, 44 to 48) and
`docs/plan-phase-4.md` is the plan. Read them first. This file records the
app's own decisions; it never overrides the server's.

Record decisions here when they are made, with the date and the reason, and
keep it true to the code. Never use em dashes. American English in code and
docs; the UI is English and Dutch.

## Status

2026-11-09: phase 4 step 4 built. Sign-in by QR code, a CalZone account
with calendar and contacts sync adapters, the engine run by Android's sync
triggers and by WorkManager (push, "Sync now"), UnifiedPush, the settings
screen (account, last sync, conflicts in plain words, which calendars and
address books this phone shows, push, appearance, sign out), the network
warning for GrapheneOS, English and Dutch, dark by default.

Verified: 12 JVM tests (the server's golden files parsed by the app's
models; the real Rust core through JNA signing in and syncing over the
app's OkHttp transport against a mock server serving those files; the
conflict wording; Robolectric screenshots of every screen, light, dark and
Dutch), lint clean, zero Kotlin warnings. **Not yet run on a device or
against a live server**: camera scanning, the deep link, Android's sync
triggers, push through ntfy, the network warning on GrapheneOS.

2026-11-09: phase 4 step 5 built: the calendar bridge (decisions 13 to
16). CalZone's calendars appear in Android's calendar (Fossify, widgets),
and edits made there come back as pending edits, by meaning. 15 JVM tests,
lint clean. **Device-untested**: the whole bridge needs a phone (Robolectric
has no calendar provider); the mapping it relies on is tested in the core
on the household corpus. Contacts follow in step 6.

2026-11-09: the owner reports the app running on a phone and doing its
job (steps 4 and 5); no item-by-item check yet.

2026-11-09: phase 4 step 6 built: the contacts bridge (decisions 17 to
21). CalZone's address books appear in Android's contacts (the Contacts
app, the phone app, messengers), and edits made there come back by
meaning; custom labels, groups, photos and birthdays without a year
included. One card asks for calendar and contacts access together. 20 JVM
tests, lint clean. **Device-untested**: the bridge needs a phone; the
mapping is tested in the core on all 1412 household cards.

2026-11-09: phase 4 step 7 built: the release workflow (decision 22) and
the switch-over guide (`docs/switch-over.md`). The R8 release build was
checked to build in the sandbox; a release APK has not run on a phone.
The switch on both phones is the owner's.

2026-11-11: phase 4b step 2 built: the calendar is the main screen
(decisions 23 to 26). An agenda of days with their events, from the
core's occurrences (decision 52 in calzone-server), today and a
read-only event sheet; settings behind a button. 28 JVM tests, lint
clean, screenshots of the agenda and the sheet in both languages.
**Device-untested**, also the scrolling and the speed of the first load on
a phone.

2026-11-11: phase 4b step 3 built: day, week and month views (decisions
27 to 29). 41 JVM tests (the grid's layout in `GridTest`), lint clean,
screenshots of each view, Dutch with a Monday week. **Device-untested**:
swiping, the date picker, rotation and the system ending the app.

2026-11-11: phase 4b step 4 built: editing (decisions 30 and 31). New
events from "+" or a long press, quick add, edit, delete, the scope
question, Undo, and each calendar's own name, color, default reminder and
"hide everywhere" in Settings. 51 JVM tests (`DraftTest` checks what the
editor sends), lint clean, screenshots of the editor and the calendar
sheet. **Device-untested**: every write, the pickers, the keyboard over the
form, and the bridge mirroring a write into Android.

2026-11-11: phase 4b step 6 built: search, other apps and widgets
(decisions 34 and 35). 66 JVM tests (`RequestsTest`, `WidgetAgendaTest`),
lint clean (zero findings), a search screenshot. **Device-untested**:
"add to calendar" from a real app, opening an event from another app or
the system, both widgets on a home screen (Glance has no screenshot test
here), the midnight refresh.

2026-11-11: month and days as the default view, and the calendar legend
(decision 33, the owner's request before step 6). 60 JVM tests, lint
clean, screenshots in both languages. **Device-untested.**

2026-11-11: phase 4b step 5 built: CalZone rings its own reminders
(decision 32). 57 JVM tests (`TimingTest`, `NoticeTextTest`); lint had six
warnings missed at hand-over (fixed in step 6);
the core's side is tested in calzone-server. **Device-untested**: every
alarm, the notifications and their buttons, the reboot before and after
unlock, a time zone change, hours of Doze, the permission question.

2026-11-11: the README rewritten in the server's style (the owner's
request): badges, the default view in all three themes, a feature list
and a screenshot table. Its images are a selection of `make screenshots`
(one new test, `monthDayLight`) scaled to 540 pixels wide in
`docs/screenshots/`. No widget images: Glance has no screenshot test here.

## Build and verify

Docker only (`make`); both repos must be checked out side by side.

```
make core         # ../calzone-server `make android-core` -> ../calzone-server/dist (auto when missing)
make test         # JVM tests, incl. the real Rust core through JNA
make build        # debug APK -> build-output/calzone-debug.apk
make lint
make screenshots  # every screen -> build-output/screenshots/
make release      # release APK (signed when keystore.properties / env vars exist)
make keystore     # create the release signing key (once)
make install      # adb install the debug APK
```

`make test`, `build`, `lint` and `screenshots` run `make core` first when
`dist/` is incomplete; after a Rust change run `make core` yourself, since a
present but stale core is not rebuilt. Gradle reads `czCoreDir` (default
`../calzone-server/dist`) and `checkCzCore` fails with that instruction when
the bindings are missing (and, for an APK, the native libraries). The tests
read the server's golden files from `<czCoreDir>/../crates/server/tests/golden`.

CI: `.github/workflows/ci.yml` builds the core HOST-only (`HOST_ONLY=1`, no
NDK) from calzone-server at `czCoreRef` (the default branch while that tag
is not pushed) and runs the JVM tests and lint. Releases:
`.github/workflows/release.yml` on a `vX.Y.Z` tag (decision 22); the steps
are in `docs/switch-over.md`.

Agent sandbox: Temurin 21 (`/opt/java`), a scratchpad SDK (cmdline-tools
16111833, android-37.0, build-tools 37.0.0) and Gradle 9.8.0 with an init
script that moves `buildDir` out of the repo and `--project-cache-dir` into
the scratchpad; `ANDROID_HOME` from the environment, never a
`local.properties`. The core comes from `HOST_ONLY=1
docker/build-android-core.sh` in the server repo. Run `:app:lintDebug` too
before handing over. Delete `.kotlin/`, `.gradle/` and any `app/build/` from
the repo afterwards. Kotlin warnings stay at zero.

## Architecture

Single module `:app`, package `com.xblackbytesx.calzone`. Hilt, DataStore,
kotlinx.serialization, Compose Material 3, WorkManager, JNA for UniFFI.

| Path | Role |
|---|---|
| `core/Core.kt` | The Rust store and engine, typed. The ONLY user of `uniffi.calzone_ffi`; maps the core's errors to `CoreError` (Offline, SignedOut, Server, Bug) |
| `model/Models.kt` | Mirrors of the core's and the client's JSON (the shapes `/sync/v1` sends) |
| `net/Http.kt` | `OkHttpTransport`: the engine's HTTP (decision 2) |
| `account/Session.kt` | Server and device token (Keystore AES-GCM at rest); `AndroidAccounts`: the account the sync adapters hang on |
| `sync/SyncRunner.kt` | One engine run at a time, its outcome kept for the screen |
| `sync/Adapters.kt` | The authenticator, the calendar and contacts sync adapters, `SyncWorker` |
| `bridge/Bridges.kt` | Both bridges around a run: their outbound halves, then one pass over the store's change log (decision 17) |
| `bridge/CalendarBridge.kt`, `bridge/Columns.kt` | The calendar bridge (decision 13); `columns` and `colorInt` are pure and tested |
| `bridge/ContactsBridge.kt`, `bridge/ContactColumns.kt` | The contacts bridge (decisions 17 to 21); `dataRows` and `contactOf` are pure and tested |
| `push/Push.kt` | UnifiedPush: endpoint and keys to the server, decrypted notices start a sync |
| `prefs/` | Theme and which collections this phone shows; `Network` (reachable, the app's settings page) |
| `signin/SignIn.kt`, `qr/Qr.kt` | Sign-in (scan, paste), CameraX + ZXing scanner |
| `calendar/CalendarScreen.kt` | The main screen (stateless `CalendarContent`): top bar, view menu, date picker, the agenda, the day's list, the event sheet, `CalendarViewModel` |
| `calendar/Draft.kt`, `calendar/Editor.kt` | The editor's form and what it sends (`changeBody`, pure, tested), the editor screen, the scope question |
| `reminders/Timing.kt`, `reminders/Reminders.kt`, `reminders/Receivers.kt` | When reminders ring (pure, tested); the one alarm, the notifications, the kept times; the alarm and boot receivers |
| `calendar/Requests.kt`, `calendar/Search.kt`, `bridge/SystemEvents.kt` | Intents as requests (pure, tested), "add to calendar" drafts; the search screen; Android rows mapped back to CalZone's events |
| `widget/WidgetData.kt`, `widget/Widgets.kt` | The widgets' data (`widgetAgenda`, pure, tested), refresh and midnight alarm; the Glance agenda and month widgets |
| `home/CalendarPrefs.kt` | My own view of a calendar: name, color, default reminder, hidden everywhere |
| `calendar/Grid.kt`, `calendar/Views.kt` | Day, week and month: pages, overlap columns and bar lanes (pure, tested), and the pagers that draw them |
| `calendar/Agenda.kt`, `calendar/Format.kt` | Occurrences into days (`agendaDays`, pure, tested); dates, times, repeats and reminders in the phone's words |
| `home/Home.kt`, `home/Explain.kt` | The settings screen (stateless `HomeContent`, behind the calendar's button), conflicts in plain words (pure, tested) |
| `ui/Theme.kt` | The web app's color roles, dark and light |

## Decisions

Settled 2026-11-09 (phase 4 step 4):

1. **The core decides.** Sync, merging, the store and the sign-in address
   are the Rust core's (decision 48 in calzone-server), reached only
   through `core/Core.kt`. The app never re-implements them; it starts
   runs, shows their outcome and, from step 5, mirrors the store into
   Android.
2. **HTTP is Android's.** `OkHttpTransport` sends what the engine asks
   for: the server's address in front, `Authorization: Bearer` once signed
   in, no redirects (a redirect would carry the token elsewhere), 15 s to
   connect, 60 s to read. TLS trusts the system's and the user's
   certificates. Plain `http://` works for a LAN server, with a warning at
   sign-in. "No answer" is the only transport error, so the core tells
   offline from signed out.
3. **One run at a time.** `SyncRunner` holds a mutex: the calendar and the
   contacts sync adapters (Android's triggers: an edit in another app with
   `upload`, their refresh, the daily periodic sync) and `SyncWorker`
   (a push, "Sync now"; expedited, needs a network, unique work appended
   so a request during a run is not lost) all wait their turn. A quiet run
   is one request, so the doubles Android sends cost nothing. The sync
   adapters report offline as an I/O error (Android backs off and retries)
   and a signed-out phone as an auth error.
4. **The Android account.** Type = the application id (a debug build has
   its own), name `user@host` as Fossify and Contacts list it. Made at
   sign-in (any other CalZone account is removed), syncable for calendar
   and contacts with automatic sync and a daily periodic sync, removed at
   sign-out (and with it whatever the bridge put in Android). The contacts
   adapter declares DAVx5's EditSchema from the start (GPL-3.0, credited in
   `res/xml/contacts.xml`): without it the Contacts app crashes when our
   account is chosen (decision 45 in calzone-server).
5. **One server per phone.** Server and device token in DataStore, the
   token sealed with an Android Keystore key. A `401` is shown as "This
   phone was signed out" with "Sign in again": the store is kept, and
   signing in again to the same account keeps edits that were waiting
   (decision 48). Signing out unregisters push, tells the server (when it
   can be reached), wipes the store and removes the account; with edits
   waiting it says how many will be lost first.
6. **Sign-in without typing**: scan the QR code from the web's Devices
   page in the app, open it with the phone's own camera (an unverified
   `VIEW` filter on `/link/czl_` on any host, `autoVerify="false"`: a
   self-hosted server cannot be App-Links verified; the address is parsed
   again by the core), or paste the address. No passwords in the app.
   Android 17's local-network permission is asked on the sign-in screen.
7. **The network, said plainly.** INTERNET is declared from the first
   version (GrapheneOS does not ask again for a permission an update adds).
   When Android shows the app no network, the screen says so and opens the
   app's settings page, because with GrapheneOS's Network permission off
   every sync waits silently (decision 45).
8. **Push is a hint.** UnifiedPush with a distributor such as ntfy; the
   endpoint goes to the server with its WebPush keys; a message that did
   not decrypt, or is not a notice, is dropped; a good one starts a sync.
   Signing in again registers the endpoint for the new token. The switch
   explains when the server has push off or the phone has no distributor. Settings fetches
   the account again each time it opens (2026-11-11 fix): before, the
   push hosts were read only at sign-in and after a (un)registration, so a
   server that turned push on later stayed "push off" on the phone, with
   the switch that would refresh it disabled.
9. **The settings screen is the app** until 4b: account, last sync and
   "Sync now", edits waiting, conflicts in plain words with "Got it"
   (`Explain.kt`: the field in people's terms, what stayed, what was
   yours; refusals and gone collections), calendars and address books with
   their color, owner, view-only or subscribed, and a "show on this phone"
   switch, push, appearance, sign out.
10. **Which collections this phone shows** is the phone's own choice,
    stored on the phone; until it is made, it follows the person's
    visibility on the web. Changing it never touches the server.
11. **English and Dutch** (decision 35 in calzone-server), **dark by
    default** with light and system to choose (decision 36), colors from the
    web app's tokens.
12. **minSdk 35, target and compile 37** (decision 44): both phones run
    GrapheneOS on the newest Android.

Settled 2026-11-09 (phase 4 step 5):

13. **The calendar bridge wraps every run** (decision 49 in
    calzone-server): with calendar access, `SyncRunner` reads Android's
    dirty and deleted rows into the store, runs the engine, then writes the
    server's changes (the store's local change log) into Android, also
    when the server could not be reached. A bridge failure is logged
    without content and never stops a sync. Without access the engine
    still syncs, and the main screen asks for it ("Show CalZone in your
    calendar apps").
14. **Rows are keyed** by `_SYNC_ID` (the object's name) for the event,
    and by `ORIGINAL_ID` plus `ORIGINAL_INSTANCE_TIME` for its exceptions;
    `SYNC_DATA1` holds the collection the row was last written for, so a
    row another app moved to another of our calendars is seen as a move.
    Calendars carry the collection id in `_SYNC_ID`.
15. **Another app's edit wins over a rewrite in progress**: a row still
    dirty is never updated or deleted by the bridge (updates select
    `DIRTY = 0`); the next run reads it first. An edit the core refuses
    stays dirty and is tried again, never dropped.
16. **Everything is written again** when a calendar is new to the phone,
    when the mapping version (`CalendarBridge.VERSION`) changes, or when
    the phone's zone changes (floating times are shown in it). Switching a
    calendar off removes it from Android after its edits were read.

Settled 2026-11-09 (phase 4 step 6):

17. **Both bridges wrap every run** (decision 50 in calzone-server).
    `Bridges` runs their outbound halves before the engine, then reads
    the store's change log once and hands each change to the bridge
    whose collection it is in. A bridge without access, or one that
    failed this run, writes everything again on its next good run instead
    of missing what the log held meanwhile (before this, a calendar
    change that came in while access was off was never written). One card
    on the main screen asks for calendar and contacts access together
    ("Show CalZone in your other apps").
18. **The Contacts app offers only what CalZone keeps.** The EditSchema
    (DAVx5's) is trimmed: no instant messaging, nickname, website,
    relation, SIP address or dates other than the birthday, and only the
    phone types a vCard can say. The name kind stays DAVx5's as it is,
    so phonetic names are offered but not kept yet.
19. **One account holds every address book this phone shows.** A raw
    contact is keyed by `SOURCE_ID` (the vCard's name) and `SYNC1` (its
    book). A contact made in another app goes to the person's own first
    writable book (with none, it waits). A view-only book's contacts are
    `RAW_CONTACT_IS_READ_ONLY`, and an edit or delete there is undone by
    writing the stored copy again. Data rows are replaced in batches of
    about 300 operations; each contact's operations start with a check
    that it is still clean, and a batch that fails it is applied again
    contact by contact, so a contact someone is editing is left for the
    next run. A contact the server deleted while it was edited here is
    removed (the edit is lost, as a merge would report).
20. **Photos go in as the display-photo file** (decision 45), after the
    batch: `SYNC2` holds a hash of the photo written and `SYNC3` the file
    id Android gave it, after waiting up to 5 s for Android to process it
    and then clearing the dirty mark it sets. A photo whose file id is
    unchanged, or still `pending` (Android has not processed it, or could
    not read it), is taken as unchanged, so Android's re-encoding never
    goes back to the server; a new file id is a new photo.
21. **Groups are the vCards' CATEGORIES**, by title. A group the bridge
    made (`SYNC1` = `calzone`) is removed when no contact is in it. A
    group renamed or deleted in another app reaches every vCard: its
    members are marked dirty first, found by membership and by the vCards
    that name the old title (a deleted group's memberships are gone).

Settled 2026-11-09 (phase 4 step 7):

22. **Releases the wordwarz way.** A `vX.Y.Z` tag builds a signed release
    APK on GitHub (tests and lint first) and attaches it to the release,
    for Obtainium; versionCode is `X*1000000 + Y*1000 + Z`. The core comes
    from calzone-server at the tag in `gradle.properties` `czCoreRef`,
    built from source, so the server is tagged first. A tag build without
    the signing secrets fails rather than publish an unsigned APK. The
    release build (application id without `.debug`) has its own account,
    so a debug build next to it shows everything twice.

Settled 2026-11-11 (phase 4b step 2):

23. **The calendar is the app.** Signed in, the app opens on the agenda;
    the settings screen of steps 4 to 7 sits behind its gear button (back
    returns). Anything there that needs the person (signed out, no
    network, no calendar and contacts access) shows as a red dot on the
    button and a one-line notice above the days.
24. **The agenda** lists every day that has something, and today always
    ("Nothing planned"). Within a day: all-day first, then by start time,
    then title. An event longer than a day shows on each day it covers
    (from its start time, all day, until its end time); one that ends at
    midnight belongs to the day before. Days gone by are dimmed. It opens
    on today and loads 90 more days when either end comes in sight; the
    month of the top day is the title. Pull down to sync. Times follow
    the phone's 12- or 24-hour setting and its language.
25. **Conflicts in place.** Calendar conflicts are a one-line notice
    above the days that opens them all; the event sheet shows those about
    its event, each with "Got it". Contacts' conflicts stay in Settings.
26. **The event sheet** is read-only until step 4: title, when, how it
    repeats and until when, place, notes, the person's own reminders, the
    calendar and whose it is, and "waiting to be sent" for an edit not on
    the server yet. Shared calendars show the owner's initials in the
    calendar's color, as on the web. The core's expansion cache is warmed
    by the first load after start; the agenda shows "Loading" until then.

Settled 2026-11-11 (phase 4b step 3):

27. **Four views, one date in sight.** Agenda (the default until decision
    33 made month and days the default), day, week and month, chosen from a menu in the top bar; the title (the month,
    for a week the month of its middle day) opens a date picker. Today,
    the picker and a change of view all go to one date, the agenda's top
    day or the page shown. The view model keeps the view and that date in
    its saved state, so both survive rotation and the system ending the
    app; a fresh start opens on today, in the view chosen last (`Prefs.view`,
    this phone only). The day changing while away goes back to today.
    Weeks start on the phone's own first day (`WeekFields` of its locale).
28. **Day and week are hours with an all-day row.** One `HorizontalPager`
    per view, pages numbered from 1970 so a date always has the same page;
    one vertical scroll shared by every page, opening 90 minutes before
    now on today, else at 7:00. Events that overlap, directly or through
    others, share the width, each in the first free column; a short event
    takes at least 30 minutes of room. All-day events and timed ones of 24
    hours or more are bars in the all-day row (three lanes, then "+n");
    shorter ones past midnight split over both days. A red line marks now
    and moves each minute. Blocks are the calendar's color with black or
    white text, as on the web; the day view adds the time, place and the
    owner's initials. A week's day heading opens that day.
29. **The month** shows the weeks the month touches; bars for all-day and
    long events, a dot and title for timed ones on the day they start, as
    many lanes as fit, then per day the one left or "+n". Tapping a day
    opens its list in a sheet (with "Day" to open it in the day view);
    an event there opens the event sheet. The grids load around the date
    in sight (75 days back, 105 ahead) and reload only when a page leaves
    that; the agenda keeps loading 90 days more at either end. No drag to
    move or long press to add yet (step 4, and drag is out of scope).

Settled 2026-11-11 (phase 4b step 4):

30. **Editing works as on the web** (decision 37 in calzone-server). The
    editor is a full-screen form: quick add (new events; fills the form
    through the core's parser, in the phone's language), title, calendar
    (new events only, the one used last first; events do not move between
    calendars, as on the web), all day, start and end (the end keeps the
    length when the start moves), repeat with "stops never, on a day,
    after n times" ("as set elsewhere" kept unless changed), place,
    notes, and my reminders as chips (several allowed; a new event starts
    with the calendar's default). Only what changed is sent (`changeBody`,
    the web's rule); a recurring event asks "only this one, this and
    following, all of them", except when only my reminders changed. On a
    view-only calendar, Edit becomes "My reminders". New events come from
    "+" or a long press on an hour (the half hour) or a month day. Delete
    has no "are you sure": every write shows Undo for 10 seconds, kept by
    the core in memory (gone if the app is ended), and starts a sync, which
    also lets the bridge mirror it into Android. Closing the editor with
    changes asks before throwing them away (typed work only).
31. **My layer per calendar from Settings.** Tapping a calendar opens my
    name for it, my color (the web's palette, or the calendar's own),
    the reminder new events get, and "hide everywhere" (the web and my
    other phones; this phone's own switch stays in the list). Saved
    through the core at once and sent with the next sync.

Settled 2026-11-11 (phase 4b step 5):

32. **CalZone rings its own reminders** (decision 51 in calzone-server).
    The core finds them (`Core.reminders`: each occurrence with its own
    part's reminders, in the calendars this phone shows). One exact alarm
    (`setExactAndAllowWhileIdle`) for the next due moment; when it rings,
    everything due since the last ring is shown (at most a day back, and
    only while the event lasts or within an hour of its time), then the
    next is set. Set again after every sync run (offline too), every edit
    and undo in the app, and on boot, unlock, update, clock and zone
    changes; a clock set back starts again from now, a fresh install does
    not ring the past. Device-protected storage holds only the last ring
    and the next 64 times; before the first unlock a ring says "a reminder
    at 09:00, unlock to see it", replaced by the real ones at unlock.
    One notification per occurrence (high importance, category reminder,
    private on the lock screen, the calendar's color) with Snooze (10
    minutes, kept with its content in credential storage) and Open (the
    event's sheet). The notification permission is asked once, when a
    reminder is first set; refused, Settings shows a notice and the gear
    its red dot. Signing out cancels the alarm, the times and the
    notifications. A reminder more than 35 days before its event is not
    found.

Settled 2026-11-11 (between steps 5 and 6, the owner's request):

33. **Month and days is the default view, with a legend**, as Fossify
    has it (`reference-screenshots/`). A small month (always six weeks, so
    the list never jumps) with a dot per calendar on each day (at most
    three, all-day events first) over the list of that month's days in
    the agenda's rows. Swipe the month to page; tap a day to select it
    (ringed) and bring it to the top of the list; long press a day for a
    new event. A fresh start without a chosen view opens it. Along the
    bottom of every view, the calendars this phone shows, each name over
    a bar in its color; it scrolls sideways when there are many. Display
    only: which calendars show is still chosen in Settings.

Settled 2026-11-11 (phase 4b step 6):

34. **Other apps and search.** The app answers what calendar apps answer
    (AOSP Calendar's filters): `INSERT` of an event opens the editor
    filled in (title, place, notes, begin and end, all day in UTC days;
    in the calendar used last, its default reminder; repeat rules sent
    along are not taken); `VIEW` or `EDIT` of
    `content://com.android.calendar/events/<id>` finds CalZone's event by
    the bridge's keys (the calendar's `_SYNC_ID`, the row's `_SYNC_ID` or
    `ORIGINAL_SYNC_ID`) and the occurrence by the begin time sent (or the
    row's original instance), then shows its sheet or its editor; a row
    that is not CalZone's, or no calendar access, says so. `VIEW` of a
    `time/<ms>` goes to that day. Notifications and widgets use the same
    path (`requestOf` into `CalendarRequests`), and any of them closes
    Settings. Search (the top bar's magnifier) runs in the core over the
    calendars this phone shows: every word in the title, place or notes,
    one occurrence per event (the next one, else the latest), coming
    first; typing waits 250 ms. The title drops the year in the current
    year, so four buttons fit.
35. **Two widgets, drawn with Glance** (1.2.0): the agenda (today and
    the next 13 days, today's ended events left out, "+" for a new event)
    and the month (six weeks with a dot per calendar, today in the
    accent). They follow the phone's dark or light setting in the app's
    colors, show only calendars this phone shows, and open the app on the
    event or day tapped. No periodic update: refreshed after every sync
    run and every edit in the app, on boot, clock and zone changes, and
    by an inexact alarm just after midnight. Signed out or failing, they
    say so and open the app.

Settled 2026-11-11 (the owner's request, after v0.2.1; released as v0.3.0):

36. **A Black theme for OLED screens** next to Dark, Light and System
    (Settings, Appearance): Dark's colors with a true black canvas and
    cards and sheets lifted just enough to tell apart (`BlackCz`). System
    still means Dark at night. The widgets keep following the phone's
    dark or light setting with Dark's colors.

Settled 2026-11-11 (the owner's feedback: stutter when swiping the month):

37. **No per-occurrence work on the main thread while swiping; short
    transitions only.** Each load groups the occurrences by day once, on
    the IO thread (`CalendarUi.byDate`); the grids and the month's list
    look their days up there (`itemsOn`, `dayColorsOf`, `monthDaysOf`)
    instead of going through every loaded occurrence per page. The grids
    load 120 days back and 240 ahead and reload only within 60 and 120
    days of an edge. In month and days, a swiped-to month selects today
    (this month) or its 1st, and its list slides in from the swipe's side
    (180 ms) already at that day instead of scrolling there; a day tapped
    in the same month scrolls smoothly; a grayed day of another month
    turns the page. A 150 ms crossfade between views and out of
    "Loading", list items fade in and out (150 and 100 ms), the selected
    day's ring fades (120 ms). Nothing waits on an animation.

## Invariants

- `uniffi.calzone_ffi` is imported only by `core/Core.kt` (and tests).
- Screens are stateless `*Content` composables, so screenshots render them
  with made-up data; the ViewModels are thin.
- Text needs `LocalContentColor` from `CzTheme`, or it is black on the dark
  canvas (quantifyme lesson).
- The device token and push endpoint never reach a log.
- Calendar and contact data never leaves the phone except to the
  household's server (`allowBackup="false"`, no cloud backup or device
  transfer).
- The owner does all git; never commit, tag or push.

## Roadmap

1. Device checks: push through ntfy, the calendar bridge (step 5) and the
   contacts bridge (step 6).
2. Step 7: the first release and the switch on both phones
   (`docs/switch-over.md`), then a few weeks of daily use.
3. 4b: CalZone's own calendar screens (`docs/plan-phase-4b.md` in
   calzone-server): step 7 under way. v0.2.0 is released (core v0.7.0);
   the household test (`docs/household-test.md` in calzone-server,
   rewritten for the app) is the owner's to run next. Settings shows the
   app's version at the bottom, for reports.
