# CalZone for Android

The phone app for [CalZone](https://github.com/xblackbytesx/calzone-server),
a self-hosted calendar and contacts system for a household. It signs in
with a QR code from CalZone's website, keeps your calendars and contacts on
the phone, and syncs them with your own server: edits made offline merge
field by field, and when two people change the same thing you are told what
was kept.

Your calendars show in the phone's own calendar apps (Fossify, widgets),
and your address books in its Contacts app, phone app and messengers,
replacing DAVx5. What you change there comes back to CalZone, merged field
by field, with custom labels, groups and photos kept. CalZone's own
calendar is growing in the app: month and days, agenda, day, week and month, editing, reminders, search and widgets.

- No Google Play Services, no tracking: it talks only to your server and,
  if you want instant updates, to your UnifiedPush distributor (ntfy).
- English and Dutch. Dark by default.
- Android 15 or newer.

## Install

Signed APKs are on this repo's GitHub releases, for Obtainium. Moving a
phone over from DAVx5: [docs/switch-over.md](docs/switch-over.md).

## Build

Needs Docker and the server repo checked out next to this one
(`../calzone-server`).

```
make build     # debug APK in build-output/
make test
```

See `AGENTS.md` for how it is put together.

## License

GPL-3.0-or-later. The contact field list in `app/src/main/res/xml/contacts.xml`
comes from [DAVx5](https://github.com/bitfireAT/davx5-ose) (GPL-3.0).
