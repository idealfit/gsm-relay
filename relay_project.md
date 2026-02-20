# GSM Relay Project - Working Notes for Next Session (2026-02-19)

## Scope
Proiect multi-client pentru management relee GSM prin SMS, cu sincronizare prin server Node.js + SQLite.

Componente:
- Android Gateway (trimite/primeste SMS + executa coada)
- Android Client (fara SMS, foloseste coada server)
- Windows Desktop (fara SMS, foloseste coada server)
- Server `server` (snapshot + queue)

## Current Architecture (practical)

### Command flow
- Windows/Android Client -> `POST /api/commands` -> status initial `pending`
- Gateway poll -> trimite SMS catre releu -> status `sent_waiting`
- Cand vine SMS de confirmare -> gateway face `POST /api/commands/ack-relay` -> status `done`

### Sequential safety per relay
Serverul livreaza `pending` strict secvential pe acelasi releu:
- daca exista o comanda mai veche `sent_waiting`, urmatoarea nu este expusa la poll.

Asta evita concurenta/comenzi suprapuse pe releu instabil.

## Onboarding relay - actual logic

### Setup commands
1. `1234P2005` (exceptie: timeout fix 15s)
2. `2005TddMMyyHHmm` (asteapta `Set Time OK`)
3. optional `2005A001#<master>#` (asteapta `001:`)
4. `2005GON10#RIDICARE/DESCHIDERE#` (asteapta `Relay ON will return SMS`)
5. `2005GOFF##` (asteapta `Relay OFF will not return SMS`)
6. `2005AL001#500#` (asteapta marker final `500:`)

Dupa query final:
- sync inbox,
- auto-inrolare ADMIN_PHONES pe primele sloturi libere (`known && phone blank`),
- cu ACK/SMS-driven progression.

## Real SMS markers confirmed from field
- `Set Time OK`
- `001:<numar>`
- `Relay ON will return SMS:...`
- `Relay OFF will not return SMS`
- query multiline pana la `500:...`

## Key file map (where to edit next)

### Server
- `server/index.js`
  - queue filtering for `pending` + blocking by `sent_waiting`
  - new endpoint `/api/commands/ack-relay`

### Android Gateway / Client logic
- `android/app/src/main/java/com/security/gsmrelay/service/CommandPollService.kt`
  - poll + send + `sent_waiting` + SMS marker waits
- `android/app/src/main/java/com/security/gsmrelay/sms/SmsReceiver.kt`
  - detect success SMS + call `ack-relay`
- `android/app/src/main/java/com/security/gsmrelay/data/network/ServerApi.kt`
  - `acknowledgeRelayWaitingCommand(...)`
- `android/app/src/main/java/com/security/gsmrelay/viewmodel/AppViewModel.kt`
  - local setup flow aligned to marker-based progression

### Windows
- `windows-ui/GSMRelayDesktop/ViewModels/MainViewModel.cs`
  - robust setup queue submission; local fixed delays removed (gateway orchestrates)

## Build outputs / runbook

### Android APKs
- Gateway source build output:
  - `android/app/build/outputs/apk/gateway/debug/app-gateway-debug.apk`
- Copie rapida in root proiect:
  - `GSMRelayGateway-debug.apk`

### Windows exe
- `windows-ui/GSMRelayDesktop/bin/Debug/net8.0-windows/GSMRelayDesktop.exe`
- Shortcut root:
  - `GSMRelayDesktop.lnk`

## Important operator notes
- Daca serverul e picat:
  - Gateway poate trimite direct SMS din app local.
  - Windows/Android Client nu pot queue-ui comenzi pana revine serverul.
- Daca releul e picat/offline:
  - comanda ramane `sent_waiting`, nu se pierde, nu e marcata done.
  - cand revine si raspunde prin SMS, se face ACK si fluxul continua.

## Next session checklist
1. Start server local:
```powershell
cd "D:\vlad\IDEAL FIT\SCRIPT\gsm relay\server"
node index.js
```
2. Verify health: `http://127.0.0.1:5174/api/health`
3. Porneste Gateway app pe telefon.
4. Ruleaza test onboarding releu nou.
5. Verifica in coada tranzitiile: `pending -> sent_waiting -> done`.

## Optional next improvement
- Retry policy controlat pentru situatii fara confirmare lunga.

## Implementat in sesiunea 2026-02-20
- Windows UI afiseaza acum "Setup state machine" per relay:
  - step curent,
  - marker asteptat,
  - elapsed wait,
  - ultimul SMS relevant.

## iOS client track (started 2026-02-20)
- Folder nou: `ios-client/GSMRelayClientIOS`
- Scope curent implementat:
  - config server persistenta + default-uri precompletate
  - sync snapshot + upload snapshot
  - queue list + create command
  - UI SwiftUI pe flow Android: locations -> location detail -> relay detail
  - tabs locatie: relee / coada / evenimente
  - tabs releu: utilizatori / comenzi / coada / istoric / evenimente / notificari
  - operatii: add/edit/delete locatie, add/edit/delete releu, add/delete user, bulk user pe selectie
  - comenzi rapide: query users, change password, timer, ALL/AUT, scrape events
- Deliberat exclus din iOS:
  - gateway/SMS orchestration (ramane pe Android Gateway)

