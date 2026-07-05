# TorBox proof of concept

Phases 1 to 4 of a TorBox integration. The goal is to show that TorBox support fits the existing
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
  - The login screen has a native TorBox section under the Real-Debrid one (separated by a
    divider): a short explanation linking to torbox.app/settings, a dedicated "TorBox API key"
    field with paste and save buttons. Saving validates the UUID shape (same toast as an invalid
    Real-Debrid token otherwise) and stores the key in `torbox_api_key`. If Real-Debrid is not
    logged in the key is also stored in the DataStore as the app private token, so the existing
    authentication state machine validates it (via the TorBox `user/me`) and reaches its
    authenticated state. If Real-Debrid is already logged in, its credentials are kept and the FSM
    stays authenticated: both services are now active.
  - As a fallback, pasting a TorBox key in the Real-Debrid private token field is still detected
    by its shape and goes through the same save path.
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

### Web downloads / hosters and the downloads tab (phase 4)

- The downloads tab mirrors the merged torrents list: the Real-Debrid downloads page as usual,
  plus the whole TorBox web downloads list (`webdl/mylist`, up to 1000 items, fetched only with
  the first page) appended to it. A TorBox failure never drops the Real-Debrid page, TorBox-only
  accounts get just the TorBox list, and non-first pages are unchanged.
- A TorBox web download becomes one download row per file (a multi file web download expands into
  several rows), id `tbw-<web_id>-<file_id>`, host `torbox`. Web downloads still being fetched by
  TorBox map to no rows until they are ready (`download_present`/`download_finished`).
- Permalink instead of on-demand minting: clicking a row in the downloads tab navigates straight
  to the download details screen with the mapped item, there is no unrestrict step in that flow,
  so `DownloadItem.download` must already be a working URL at list mapping time. Each row
  therefore carries the `webdl/requestdl` redirect permalink
  (`.../webdl/requestdl?token=<key>&web_id=<id>&file_id=<id>&redirect=true`, confirmed against the
  OpenAPI spec), which 302-redirects to a fresh CDN URL on every hit, so no extra API calls are
  needed and every existing button (download, players, open, copy, share) works untouched.
  CAVEAT: the permalink embeds the user's raw TorBox API key (the endpoint has no other
  authentication), so sharing or copying a TorBox download link from the app shares the API key.
  A synthetic-link-plus-minting scheme like phase 3 would avoid that but would require touching
  the details fragment, which this POC deliberately avoids.
- Pasting a hoster link in the new download screen: when Real-Debrid is active it keeps handling
  the link (its unrestrict is instant, the better experience). When only TorBox is active the link
  is queued with `POST webdl/createwebdownload` and `webdl/mylist?id=` is polled for ~15 seconds
  (3 second interval): if TorBox fetches the file in time the first file is unrestricted through
  `webdl/requestdl` and the flow continues exactly like a Real-Debrid unrestrict; if it is still
  fetching, a synthetic error code (100, outside the Real-Debrid range) surfaces as a readable
  toast telling the user the download was queued and will appear in the downloads list later.
- Deleting: a `tbw-` row is routed to `POST webdl/controlwebdownload` with the `delete` operation.
  TorBox has no per file entries, so deleting any file row of a multi file web download deletes
  the whole web download (all its rows). Deleting a `tb-` download row (a torrent file
  unrestricted in phase 3) is a graceful no-op success: those items are minted on the fly and are
  not persisted in any account side list, so there is nothing to delete server side (previously
  this hit the Real-Debrid endpoint and reported an error).

## What works

- Login with a TorBox API key (own section on the login screen), Real-Debrid key or OAuth, in any
  combination and order (TorBox while Real-Debrid is active is added from settings)
- User screen: the Real-Debrid account when both are active, otherwise the TorBox account
  (username from the email prefix, premium state and remaining days)
- Merged torrents list with per-item routing, TorBox items marked with the `torbox` host
- Adding magnets and .torrent files to either service, with the settings choice when both are
  active
- Deleting TorBox torrents, torrent details for `tb-` ids (mapped from `mylist?id=`)
- Downloading TorBox files: single file torrents go straight to the download details screen,
  multi file torrents open the folder list, both backed by `torrents/requestdl` CDN URLs
- The downloads tab shows the TorBox web downloads (one row per file) next to the Real-Debrid
  list, and pasted hoster links are queued on TorBox when it is the only active service
- Deleting TorBox web downloads from the downloads tab or the download details screen

## Known limitations / out of scope

- The TorBox download rows in the downloads tab carry `requestdl` permalinks that embed the raw
  API key (see the phase 4 notes): the share and copy buttons leak the key to whoever receives
  the link.
- Torrent files unrestricted through phase 3 (`tb-` download ids) still do not appear in the
  downloads tab: TorBox has no account side list for them, only web downloads have one. Deleting
  them from the download details screen is a local no-op success.
- Hoster links queued on TorBox that are not fetched within the polling window are only visible
  once ready: the app has no screen showing pending web downloads (their rows appear in the
  downloads tab when the files are present).
- The whole web download zip link (`webdl/requestdl` with `zip_link=true`) is not wired, same as
  the torrents one.
- The `createwebdownload` response id field name (`webdownload_id`) is not spelled out in the
  OpenAPI spec (the response schema is empty); the mapper parses both `webdownload_id` and `id`
  defensively.
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
- TorBox torrents and web downloads are only merged into the first page, so they all load at once
  (TorBox caps both endpoints at 1000 items anyway).
- Setting the TorBox key from settings while completely logged out stores the key, but the login
  screen still expects a token paste to authenticate the app itself.
- None of this has been exercised against live accounts; the mapping (including the exact
  `requestdl` response shapes and the `createwebdownload` result) is based on the public API
  documentation and the OpenAPI spec with defensive parsing.
