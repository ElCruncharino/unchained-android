# TorBox proof of concept

Phases 1, 2 and 3 of a TorBox integration. The goal is to show that TorBox support fits the existing
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

### Download links (phase 3)

- On Real-Debrid a downloaded torrent carries a list of hoster links that the unrestrict endpoint
  turns into direct download URLs one by one. TorBox has no hoster links: `torrents/requestdl`
  returns a CDN URL per file. To reuse the whole existing download flow, a mapped TorBox torrent
  whose download is ready (`download_finished` or `download_present`) gets one synthetic link per
  file: `torbox://<torrent_id>/<file_id>?name=<url encoded file name>&size=<bytes>&mime=<mimetype>`.
  The file name, size and mimetype are encoded into the link at mapping time, so the unrestrict
  step does not need to fetch the torrent again (`requestdl` alone returns no file metadata).
- `UnrestrictApiHelperImpl.getUnrestrictedLink` recognizes the `torbox://` scheme, calls
  `torrents/requestdl` (which wants the raw API key as a `token` query parameter, no `Bearer`
  prefix, same key resolution as the other TorBox calls) and synthesizes the `DownloadItem` from
  the returned CDN URL plus the metadata in the link: id `tb-<torrent_id>-<file_id>`, host
  `torbox`, streamable when the mimetype is video or audio so the "send to player" buttons show
  up. Any other link goes to the Real-Debrid unrestrict endpoint unchanged.
- Because the links list is populated, the existing UI flows work as they are: a single file
  torrent unrestricts straight to the download details screen, a multi file torrent opens the
  folder list screen which unrestricts every synthetic link through the same repository call.
  Download to device, send to player (local and Kodi/VLC remotes), open, copy and share all
  operate on the CDN URL.
- TorBox download URLs are valid to start for 3 hours. The app mints them on demand right before
  use so this rarely matters, and the unrestrict cache TTL (2 hours) is below the validity window.
  Only links copied/shared and used much later can expire; unrestricting again mints a fresh one.

## What works

- Login with a TorBox API key, Real-Debrid key or OAuth, in any combination and order (TorBox
  while Real-Debrid is active is added from settings)
- User screen: the Real-Debrid account when both are active, otherwise the TorBox account
  (username from the email prefix, premium state and remaining days)
- Merged torrents list with per-item routing, TorBox items marked with the `torbox` host
- Adding magnets and .torrent files to either service, with the settings choice when both are
  active
- Deleting TorBox torrents, torrent details for `tb-` ids (mapped from `mylist?id=`)
- Downloading TorBox files: single file torrents go straight to the download details screen,
  multi file torrents open the folder list, both backed by `torrents/requestdl` CDN URLs

## Known limitations / out of scope

- Phase 4: web downloads / hosters via the TorBox `webdl` endpoints.
- TorBox downloads are not persisted anywhere: Real-Debrid keeps an account side downloads list
  (unrestricting adds to it, the downloads tab reads it), TorBox has nothing equivalent, so
  unrestricted TorBox files never appear in the downloads tab and the delete button on their
  download details screen hits the Real-Debrid endpoint and just reports an error.
- The transcoded streams button ("load streams") stays disabled for TorBox items (they carry no
  alternative streams), matching how it behaves for Real-Debrid users without streaming support;
  if it were somehow triggered the RD streaming call fails and is logged without UI effect. The
  "stream in browser" entry of the streaming popup builds a real-debrid.com URL, which for a
  `tb-` id opens a broken web page. The other popup entries (Kodi, VLC and friends) receive the
  plain CDN URL and work.
- The whole torrent zip download (`requestdl` with `zip_link=true`) is not wired.
- The user screen only shows the Real-Debrid account when both services are active; the TorBox
  account info is not displayed anywhere in that case.
- The list filter (e.g. active torrents) is only applied to the Real-Debrid page; the appended
  TorBox items are always the full list.
- TorBox torrents are only merged into the first page, so they all load at once (TorBox caps the
  endpoint at 1000 items anyway).
- Setting the TorBox key from settings while completely logged out stores the key, but the login
  screen still expects a token paste to authenticate the app itself.
- None of this has been exercised against live accounts; the mapping (including the exact
  `requestdl` response shape) is based on the public API documentation with defensive parsing.
