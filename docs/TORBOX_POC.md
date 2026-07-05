# TorBox proof of concept

Phase 1 of a TorBox integration. The goal is to show that TorBox support fits the existing
architecture without a rewrite: everything above the `*ApiHelperImpl` layer (helper interfaces,
repositories, viewmodels, fragments) is untouched.

## How it works

- The user pastes their TorBox API key (from torbox.app/settings) into the existing private
  API token login screen.
- TorBox keys are UUIDs (8-4-4-4-12 hex) while Real-Debrid private tokens are not, so the app
  detects the service from the token shape and stores the choice in the shared preference
  `current_debrid_provider` (`real_debrid` / `torbox`).
- `UserApiHelperImpl` and `TorrentApiHelperImpl` check that flag per call: when it is `torbox`
  they call the TorBox API (`https://api.torbox.app/v1/api/`, new `TorBoxApi` Retrofit interface
  behind the `@TorBoxRetrofit` qualifier) and map the responses to the existing Real-Debrid
  models (`TorBoxItems.kt`), otherwise they call Real-Debrid exactly as before.
- TorBox errors are converted to Real-Debrid style error bodies (401/403 become error code 8,
  bad token) so the existing FSM/authentication error handling keeps working.

## Exclusive provider, not simultaneous

The provider flag is a single global switch tied to the single stored credential set: the app
talks to either Real-Debrid or TorBox, never both at the same time. Logging in with one service
replaces the other (OAuth login resets the flag to Real-Debrid). Users who have accounts on both
services must log out and paste the other key to switch. Simultaneous accounts would need
multi-credential storage in the ProtoStore and per-call routing decisions, which is out of scope
for this proof of concept and would only be considered after the phased plan below.

## What works

- Login by pasting a TorBox API key (detected automatically, validated via `GET user/me`)
- User screen: username (email prefix), email, premium state and remaining days
- Torrents list, read only, via `GET torrents/mylist` (state, progress, size, seeders mapped to
  the Real-Debrid torrent statuses)

## Out of scope (phase 1)

- Adding torrents/magnets, selecting files, deleting torrents (the helpers return a graceful
  501 error on TorBox instead of hitting Real-Debrid with the wrong key)
- Torrent details (`torrents/info/{id}` is not mapped, opening a torrent shows no data)
- Downloads list, unrestrict, streaming, hosts and host regexes (Real-Debrid only concepts;
  these calls fail gracefully and the list simply stays empty)
- Points, avatar, locale (TorBox has no equivalents, placeholders are used)

## Phased plan

- Phase 2: add magnet/torrent support via `POST torrents/createtorrent`, torrent deletion via
  `POST torrents/controltorrent`
- Phase 3: generate download links via `GET torrents/requestdl` and wire them into the
  downloads/unrestrict flow
- Phase 4: web downloads / hosters via the TorBox `webdl` endpoints
