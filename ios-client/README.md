# GSM Relay iOS Client

This folder contains the SwiftUI client implementation for iOS, aligned with Android Client behavior (without SMS gateway features).

Current scope:
- persisted server settings with prefilled defaults
- sync snapshot (`/api/snapshot`) + upload snapshot (`/api/snapshot`)
- command queue list (`/api/commands`)
- create command (`/api/commands`)
- app flow: `Locations -> Location detail -> Relay detail`
- location management: add / rename / delete
- relay management: add / edit / delete
- users management: add / delete / add on multiple selected relays
- relay command actions: query users / change password / timer / allow all / allow authorized / scrape events
- tabs for location and relay (queue/history/events/notifications)
- auto sync loop (15s), close to Android client behavior

Default server settings:
- Base URL: `http://86.120.150.58:5174`
- Username: `admin`
- Password: `admin1316`
- Gateway ID: `pQF6bci9`
- Master Phone: `0724264464`

## How to use
1. On macOS, create a new iOS App project in Xcode named `GSMRelayClientIOS`.
2. Copy the `GSMRelayClientIOS` folder contents into that Xcode project.
3. Set deployment target iOS 16+ (or adjust code for lower targets).
4. Run on simulator/device.

## Build without Mac (GitHub Actions)
- Workflow file: `.github/workflows/ios-build.yml`
- It uses `macos-latest` runner + `xcodegen` and builds the iOS client for simulator.
- Trigger:
  - manually from Actions (`workflow_dispatch`), or
  - push/PR changes under `ios-client/**`.
- Output artifact:
  - `GSMRelayClientIOS-simulator.app.zip`

## Notes
- iOS app is client-only. SMS gateway orchestration remains on Android Gateway by design.
- Export/import file workflows and some UI polish details can be extended in next iteration.
