# TorBox proof of concept

Phases 1 and 2 of a TorBox integration. The goal is to show that TorBox support fits the existing
architecture without a rewrite: everything above the `*ApiHelperImpl` layer (helper interfaces,
repositories, viewmodels, fragments) is untouched, except for a settings entry and the token save
path in the login screen.

## How it works

### Two accounts at the same time

- The TorBox API key (from torbox.app/settings) lives in its own shared preference,
  `torbox_api_key`. The Real-Debrid credentials keep the single protobuf DataStore slot they
  always had. There is no global provider switch anymore: a service is active when its credential
  is present.
- TorBox keys are UUIDs (8-4-4-4-12 hex) while Real-Debrid tokens are not, so the app detects the
  service from the token shape:
  - Pasting a TorBox key in the private token login screen saves it to `torbox_api_key`. If
    Real-Debrid is not logged in the key is also stored in the DataStore as the app private token,
    so the existing authentication state machine validates it (via the TorBox `user/me`) and
    reaches its authenticated state. If Real-Debrid is already logged in, its credentials are kept
    and the FSM stays authenticated: both services are now active.
  - Pasting a Real-Debrid token (or logging in with OAuth) goes through the normal Real-Debrid
    path and never touches `torbox_api_key`.
- Settings has a "TorBox API key" field (bound to the same preference) to add, replace or remove
  the TorBox account at any time, which is also the way to add TorBox while already logged into
  Real-Debrid since the login screen is only reachable when logged out. Clearing the field removes
  the TorBox account; when TorBox is the main app login the DataStore copy of the key is kept in
  sync with this field.
- Logout removes the credentials of both services. A TorBox key rejected during login validation
  is also removed from `torbox_api_key`.

### Per-call routing, the `tb-` prefix

- The helpers receive the DataStore token on every call. A UUID shaped token means TorBox is the
  main login; anything else means Real-Debrid is logged in. The TorBox authentication header comes
  from `torbox_api_key`, falling back to the DataStore token itself for TorBox-only logins.
- `getTorrentsList` merges both services: the Real-Debrid page as usual, plus the whole TorBox
  list (`torrents/mylist`, up to 1000 items, fetched only with the first page) appended to it. A
  TorBox failure does not drop the Real-Debrid page.
- Mapped TorBox torrents get an id prefixed with `tb-` (e.g. `tb-1234`) and `host = "torbox"`.
  Every other torrent call inspects the id: `tb-` ids are stripped and routed to TorBox, other ids
  go to Real-Debrid exactly as before. The host makes TorBox items recognizable in the existing
  list UI with zero layout changes.
- TorBox errors are converted to Real-Debrid style error bodies (401/403 become error code 8, bad
  token) so the existing error handling keeps working.

### Adding torrents (phase 2)

- `POST torrents/createtorrent` (multipart: `magnet` string or `file` .torrent upload) backs
  `addMagnet`/`addTorrent`. The returned `torrent_id` is mapped to a `tb-` id, then the normal app
  flow fetches the torrent through `torrents/mylist?id=`.
- Where a new torrent goes: the only active service when just one is logged in; when both are
  active, the "Add new torrents to" setting decides (Real-Debrid / TorBox, default Real-Debrid).
- TorBox has no file selection phase: mapped statuses are never `waiting_files_selection`, so the
  torrent processing screen skips the file picker and goes straight to the details screen, and
  `selectFiles` on a `tb-` id is a graceful success no-op. `getAvailableHosts` returns a
  synthesized single-entry list for TorBox-only users so the add flows can proceed.
- A torrent that TorBox only queues (active torrents over the plan limit return a `queued_id` and
  no `torrent_id`) is reported as an error, because the app cannot follow a queued torrent yet.
- `POST torrents/controltorrent` with the `delete` operation backs `deleteTorrent` for `tb-` ids.

## What works

- Login with a TorBox API key, Real-Debrid key or OAuth, in any combination and order (TorBox
  while Real-Debrid is active is added from settings)
- User screen: the Real-Debrid account when both are active, otherwise the TorBox account
  (username from the email prefix, premium state and remaining days)
- Merged torrents list with per-item routing, TorBox items marked with the `torbox` host
- Adding magnets and .torrent files to either service, with the settings choice when both are
  active
- Deleting TorBox torrents, torrent details for `tb-` ids (mapped from `mylist?id=`)

## Known limitations / out of scope

- Phase 3: download links. `torrents/requestdl` is not wired, so TorBox torrents expose no links
  and the download/unrestrict/streaming flows do nothing for them.
- Phase 4: web downloads / hosters via the TorBox `webdl` endpoints.
- The user screen only shows the Real-Debrid account when both services are active; the TorBox
  account info is not displayed anywhere in that case.
- The list filter (e.g. active torrents) is only applied to the Real-Debrid page; the appended
  TorBox items are always the full list.
- TorBox torrents are only merged into the first page, so they all load at once (TorBox caps the
  endpoint at 1000 items anyway).
- Setting the TorBox key from settings while completely logged out stores the key, but the login
  screen still expects a token paste to authenticate the app itself.
- None of this has been exercised against live accounts; the mapping is based on the public API
  documentation with defensive parsing.
