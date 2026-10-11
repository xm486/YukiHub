# Changelog

All notable changes to this project will be documented in this file.

## [0.3.5]

### Added
- PC engine support, compatible with Winlator-CN quick launch (requires Winlator-CN 1005 or newer)
- FVP engine compatibility
- Search in big-screen mode (at the top of the category bar)
- NextMoe stickers in the community chat
- Platform badge distinguishing phone / PC online presence

### Changed
- FVP engine: added a virtual mouse button (reusing the KRKR / Artemis solution)

### Fixed
- Scanned games now match against the metadata source selected in Settings, instead of always using VNDB
- Big-screen playtime is now reported to the server (fixes missing EXP and unfinished daily tasks)
- Settings panel: swiping could be misread as a tap and lock up the UI
- FVP video playback (requires manually converting videos to standard MP4: H.264 (AVC) video + AAC audio)
- Removed the Bangumi mirror source (frequently unavailable); existing users are migrated to the official Bangumi source

### Notes
- Network-related silent failures now emit logs, making issues easier to diagnose
- None of the above changes affect engine save-mirror logic

## [0.3]

### Added
- Big-screen mode
- Gal news on the home page
- NextMoe data source and NextMoe quick login
- DLsite / Steam store links in the web community game pages
- Forgot password / account recovery
- Playtime editing
- Offline 3D game exhibition and multiplayer online exhibition (cinema, music room, etc.)

### Changed
- Full UI redesign (visual language and color system)
- Login / register page interaction improvements
- In-app update source switched to GitCode

### Fixed
- Friend list showing "playing" for offline users
- Chat images being only half-cached
- Missing animation feedback for verification codes
- Coexisting build failing to scan and add files

## [0.1.0] - Initial public release

### Added
- Game management and editing
- Empty-directory game entries
- GameHub shortcut import with icons and search
- Sync support for games and play records
- Settings page disclaimer entry
- First-launch disclaimer gate with required consent
- Dark-themed card-style onboarding dialogs

### Notes
- The project is currently in an open-source release-ready state
- Some cloud or synchronization features depend on external services
