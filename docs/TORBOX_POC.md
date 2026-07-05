# TorBox proof of concept

Phases 1 to 4 of a TorBox integration plus a user experience round driven by user feedback. The
goal of the first four phases was to show that TorBox support fits the existing architecture
without a rewrite: everything above the `*ApiHelperImpl` layer (helper interfaces, repositories,
viewmodels, fragments) was untouched, except for a settings entry and the token save path in the
login screen. The UX round deliberately relaxes that constraint where a feature needs it: the user
page, the new download screen and the list adapters now know about the two services.

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
  the TorBox account at any time. Clearing the field removes the TorBox account; when TorBox is
  the main app login the DataStore copy of the key is kept in sync with this field. The user page
  cards can also connect either missing service in place (see the UX round below), so the settings
  field is no longer the only way to add TorBox while Real-Debrid is logged in.
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
  unrestricted in phase 3) removes its local history row (see the parity round below): those items
  are not persisted in any account side list, so there is nothing to delete server side
  (previously this hit the Real-Debrid endpoint and reported an error).

### Parity round: local download history for torrent files

- Real-Debrid's downloads tab is a server side history of everything ever unrestricted, while
  TorBox keeps no account side list of the files fetched from torrents (only web downloads have
  one), so those files used to leave no trace in the downloads tab. For parity, a local Room table
  (`torbox_download`, database version 11 with a manual `Migration` in `DatabaseModule` like the
  previous schema bumps) records every torrent file fetch and the downloads tab lists it.
- What is recorded and when: every successful exchange of a synthetic `torbox://` link for a CDN
  url goes through `UnrestrictRepository.getEitherUnrestrictedLink` (the direct download, folder
  list and send to player flows all end up there), which upserts one row keyed by the download id
  `tb-<torrent_id>-<file_id>`: the synthetic link (it encodes name/size/mime), filename, size,
  mimetype, torrent and file ids and a fetch timestamp. Fetching the same file again keeps a
  single row and refreshes its timestamp (unrestrict cache hits included). The insert is best
  effort: a database problem is only logged and never breaks the download.
- Listing: the merged downloads first page appends the history rows, newest first, after the web
  download rows, with a distinct-by-id pass to drop any duplicates (the `tb-`/`tbw-` prefixes
  already keep the id spaces apart). Each row carries the `torrents/requestdl` redirect permalink
  (`.../torrents/requestdl?token=<key>&torrent_id=<id>&file_id=<id>&redirect=true`), the torrents
  counterpart of the webdl permalink: every hit 302-redirects to a fresh CDN url, so the rows work
  with zero extra api calls and every existing button works untouched. Same caveat as the webdl
  permalinks: the url embeds the raw TorBox api key, sharing the link shares the key.
- Rows can outlive their torrent: if the torrent is later deleted on TorBox the permalink dies,
  and no attempt is made to verify rows against the account (that would cost one api call per
  row). Deleting the row from the downloads list or the download details screen removes it from
  the local table, which doubles as the cleanup for rows whose torrent is gone.
- The lists tab search filters these rows like every other merged item (the client side filename
  filter runs after the merge) and they show the same "TorBox" source label, driven by
  `host == "torbox"`.

### UX round: dual account user page

- The user page shows one card per service instead of a single account view. The Real-Debrid card
  keeps the old fields (username, email, avatar, premium state, remaining days, points) and its
  account page button (real-debrid.com, with the referral dialog). The TorBox card shows the
  `user/me` data: email, plan (0 Free, 1 Essential, 2 Pro, 3 Standard), remaining premium days
  (from `premium_expires_at`) and total downloaded, with buttons opening torbox.app/settings (key
  management) and torbox.app/subscription (plan). No TorBox button opens real-debrid.com.
- A service that is not logged in shows a one line "not connected" hint plus a Connect button on
  its card, so the missing account can be added right there instead of logging out. Both buttons
  open a Material dialog with a short explanation, a tappable link to the token page and an input
  field with a paste button (mirroring the login screen row):
  - The TorBox dialog links to torbox.app/settings, validates the UUID shape of the key (invalid
    keys show the usual invalid token toast and keep the dialog open) and then runs the exact
    save path of the login screen and the settings field: the key always goes to the
    `torbox_api_key` preference, and it only also feeds the DataStore/state machine when
    Real-Debrid is not the main login. The card refreshes right after. The same dialog is offered
    when the TorBox card shows a key error, as a way to replace a dead key.
  - The Real-Debrid dialog links to real-debrid.com/apitoken and takes the private API token. A
    UUID shaped paste is refused with a toast pointing to the TorBox card (it is a TorBox key),
    a too short one with the invalid token toast, both keeping the dialog open. A plausible token
    is verified against the Real-Debrid `user` endpoint before anything is stored: on failure a
    toast reports it and nothing changes, on success the token replaces the DataStore credentials
    (with the usual private token sentinel fields) and the page refreshes. The `torbox_api_key`
    preference is untouched, so the previously main TorBox login stays active next to the new
    Real-Debrid one. No state machine event is needed: this flow only runs while the FSM is
    already in its authenticated private token state (TorBox key as app login) and a Real-Debrid
    private token is a private token login too; the token refresh logic keeps ignoring private
    tokens after the swap. The dialog also mentions that the OAuth login is still available from
    the login screen after logging out: the device flow is not wired into the user page.
- The TorBox account is fetched independently of the main login through a small dedicated
  `TorBoxRepository` (wrapping `TorBoxApi.getUserInfo` with the same key resolution as the api
  helpers: the `torbox_api_key` preference first, the stored login token when it is UUID shaped)
  injected into a new `UserProfileViewModel`. The activity level user fetch that feeds the
  authentication state machine is untouched, and `UserApiHelperImpl` keeps its token shape routing
  for the FSM validation call; its mapped user is just no longer used to fill the Real-Debrid card
  when TorBox backs the main login.

### UX round: choosing where new torrents go, including both

- When both services are logged in the new download screen shows a Real-Debrid / TorBox / Both
  toggle (a `MaterialButtonToggleGroup`) under the torrent upload controls. It starts from the
  "Add new torrents to" preference and writes every change straight back to it, so the toggle and
  the settings dropdown (which gained a "Both" entry) always show the same value and the last
  choice is the new default. The api helper reads the preference when `addMagnet`/`addTorrent`
  run, so the choice reaches the routing with zero signature changes; the tradeoff is that the
  choice is app global, not scoped to one add (deliberate, it doubles as the default).
- "Both" submits the magnet or .torrent to both services: the TorBox `createtorrent` runs first as
  a logged best effort call (a TorBox failure never breaks the add), then the Real-Debrid upload
  proceeds and the app follows it into the usual processing screen (TorBox has no file selection
  phase to follow anyway). A toast on the new download screen tells the user the torrent was also
  sent to TorBox. The .torrent request body is byte array backed, so sending it to both services
  is safe.
- Entry points that bypass the new download screen (magnets sent from the search tab, the torrent
  processing screen reached directly) still honor the preference value, they just do not show the
  toggle or the toast. With a single active service nothing changes: the control stays hidden and
  the old routing applies.

### UX round: search covers both services, obvious source

- The lists tab search already filters both halves of the merged lists: the query from
  `ListTabsViewModel.setListFilter` is applied client side in `TorrentPagingSource` and
  `DownloadPagingSource` with a case insensitive contains on the item names, after the helpers
  have merged the TorBox items into the page. It never was a Real-Debrid only server side filter
  (the `filter` parameter of the torrents endpoint is unused by the app), so no routing change was
  needed here; this was verified rather than modified.
- Every merged row now makes its source obvious: TorBox rows show a "TorBox · " prefix on the
  existing per-row label (the status label on torrent rows, e.g. "TORBOX · READY", and the
  download/streaming label on download rows), bound from `host == "torbox"` in the two list
  adapters. This label was used instead of prefixing the mapped item names because
  `DownloadItem.filename` is also the file name used when downloading to the device and the title
  handed to external players, which a "[TorBox] " prefix would pollute; the names stay raw, which
  also keeps the search filter matching exactly what the user sees as the name.

## What works

- Login with a TorBox API key (own section on the login screen), Real-Debrid key or OAuth, in any
  combination and order
- User screen: one card per service with its own account info and action buttons pointing at the
  right service; the missing service shows a Connect button opening a dialog that adds the
  account in place (Real-Debrid private token, checked against the API before being stored, or
  TorBox API key). OAuth stays on the login screen, reachable after logout
- Merged torrents list with per-item routing, TorBox rows labeled with a "TorBox" tag
- Adding magnets and .torrent files to either service or to both at once, chosen per add from the
  new download screen when both are active (kept in sync with the settings entry)
- Searching the lists tab filters the items of both services by name
- Deleting TorBox torrents, torrent details for `tb-` ids (mapped from `mylist?id=`)
- Downloading TorBox files: single file torrents go straight to the download details screen,
  multi file torrents open the folder list, both backed by `torrents/requestdl` CDN URLs
- The downloads tab shows the TorBox web downloads (one row per file) next to the Real-Debrid
  list, and pasted hoster links are queued on TorBox when it is the only active service
- The downloads tab also shows every TorBox torrent file ever fetched from this device (local
  history table, upserted on each fetch), with working redirect permalinks and newest first
- Deleting TorBox web downloads from the downloads tab or the download details screen; deleting a
  torrent file row removes it from the local history

## Known limitations / out of scope

- The TorBox download rows in the downloads tab carry `requestdl` permalinks that embed the raw
  API key (see the phase 4 notes): the share and copy buttons leak the key to whoever receives
  the link.
- The torrent file history is local to the device (TorBox has no account side list for those
  files): it starts empty on a fresh install and only grows with the fetches made from that
  install. A history row whose torrent was deleted on TorBox keeps its (now dead) permalink until
  the user deletes the row; the app does not verify rows against the account.
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
- The "Both" toast on the new download screen is optimistic: the TorBox copy runs right after and
  only logs its failure. Magnets added from screens other than the new download one (e.g. the
  search tab) honor the "Both" preference without showing any toast.
- The service toggle on the new download screen is read when it opens; adding or removing the
  TorBox key while the screen is alive does not show/hide it until it is recreated.
- TorBox torrents and web downloads are only merged into the first page, so they all load at once
  (TorBox caps both endpoints at 1000 items anyway).
- Setting the TorBox key from settings while completely logged out stores the key, but the login
  screen still expects a token paste to authenticate the app itself.
- The Real-Debrid connect dialog on the user page reports any failed token check as an invalid
  token, including plain network errors; retrying once the connection is back is the recovery
  path. OAuth cannot be started from that dialog, it stays on the login screen.
- None of this has been exercised against live accounts; the mapping (including the exact
  `requestdl` response shapes and the `createwebdownload` result) is based on the public API
  documentation and the OpenAPI spec with defensive parsing.
