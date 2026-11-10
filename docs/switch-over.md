# Switching a phone from DAVx5 to CalZone

Phase 4 step 7. Do one phone first, live with it for a few days, then the
other. Going back is always possible (see the end) until DAVx5 is
uninstalled.

## Once: the first release

1. The release key: `make keystore` creates `keystore/release.jks` (git
   ignored; it refuses to replace an existing one) and prints the GitHub
   secrets to set. Back the key up: losing it means uninstalling the app
   on every phone, since Android and Obtainium only accept updates signed
   with the same key.
2. The repo secrets on GitHub, as it printed them (`CORE_TOKEN` too, only
   while calzone-server is private). Then delete
   `keystore/release.jks.base64`.
3. Tag calzone-server first (`v0.6.0`, the tag in `gradle.properties`
   `czCoreRef`), and deploy it: the server and the app share the core.
4. Tag this repo `v0.1.0`. The workflow runs the tests and lint, builds
   the signed APK and attaches it to the GitHub release. It prints the
   certificate's SHA-256; check it against the one `make keystore`
   printed.

Later releases: bump `czCoreRef` when the app needs a newer core, tag the
server, then tag the app.

## Before each phone

- The server holds everything: DAVx5 already syncs with CalZone, not
  Baikal (`docs/leaving-baikal.md` in calzone-server).
- A backup: Import & export on the website, download everything.
- In DAVx5, sync once, so no edit made on the phone is still waiting.
- If a test (debug) build of CalZone is installed: tap Sync now, then
  uninstall it. It has its own account, so next to the release it would
  show everything twice. Uninstalling removes only its copies on the
  phone.

## Switching

1. Install CalZone with Obtainium (this repo's GitHub releases; a private
   repo needs a GitHub token in Obtainium). On GrapheneOS, leave the
   Network permission on.
2. On the website, open Devices, choose Show a sign-in code, and scan it
   in the app.
3. On the main screen, tap Allow on "Show CalZone in your other apps",
   and allow calendar and contacts.
4. Instant updates: with ntfy installed, turn Push on.
5. Wait for the first sync. Writing every contact takes a little while;
   the Contacts app fills up as it goes.
6. In DAVx5, open the account and untick every calendar and address book.
   DAVx5 removes them from the phone; the server keeps everything. Do not
   remove the DAVx5 account yet.
7. In Fossify Calendar, Settings, CalDAV sync: tick CalZone's calendars.
8. New contacts and events: where an app asks which account or calendar
   to save to, choose CalZone. A contact saved to the phone itself never
   reaches the server.

## What to check

- Fossify shows every calendar with its color, recurring events
  included, and a reminder goes off.
- An event added in Fossify appears on the website; one added on the
  website appears in Fossify within a minute (with push).
- The same event edited on the phone while offline and on the website
  merges when the phone is back online: different fields both survive,
  the same field shows under "Changes to look at".
- Contacts show their custom labels, birthdays and photos.
- An incoming call from a contact shows the name.
- A contact edited in the Contacts app (a number, a label, a group) shows
  the change on the website.
- A view-only address book cannot be edited on the phone.

Anything odd: note what you did and what you saw, and keep the phone as
it is. The app's log (`adb logcat -s CalZoneBridge CalZoneSync`) never
holds names or content.

## Living with it

A few weeks of daily use on both phones. Phase 4a is done when nothing
was duplicated or lost in that time. Then uninstall DAVx5.

## Going back

1. In CalZone, tap Sync now, then switch off every calendar and address
   book on the main screen (or sign out). They leave the phone; the
   server keeps them.
2. In DAVx5, tick the calendars and address books again.

Never run both at once: every event and contact would show twice.
