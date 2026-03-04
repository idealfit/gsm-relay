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
  - CSV users: import + export
  - CSV events: export per relay + per location
  - filtre interval timp pentru events (relay/location)
- Deliberat exclus din iOS:
  - gateway/SMS orchestration (ramane pe Android Gateway)

## End of day note (2026-02-20)
- GitHub repo conectat: `https://github.com/idealfit/gsm-relay`
- CI iOS existent si functional ca pipeline, dar ultimul run a esuat pe o eroare Swift parser in `AppViewModel.swift` (linia `nextFreeKnownSlot`).
- Fix local pregatit: optional chaining rescris explicit (`let slot ...; return slot?.id`) pentru Xcode 16.4.
- Primul pas in urmatoarea sesiune:
  1. confirm push la ultimul fix iOS,
  2. rerun `iOS Client Build`,
  3. daca build verde, trecere pe testare UAT Android vs iOS.

## Git discipline (important)
- Nu folosi `git add .` in acest repo (sunt multe foldere locale/untracked).
- Foloseste add selectiv doar pentru fisierele vizate (ex: `ios-client`, `.github/workflows`, `status.md`, `relay_project.md`).

## Sesiune curenta - sync hardening (2026-02-20)

### Ce s-a fixat (esential)
- S-a stabilizat sincronizarea intre:
  - Windows UI (vizualizare setup + coada),
  - Android Client (coada/snapshot),
  - Android Gateway (executie SMS + confirmari).
- Principiu impus: SMS-ul este confirmarea reala de executie; serverul este adevarul comun pentru statusurile cozii.

### Schimbari tehnice aplicate
- `server-firebase/index.js`
  - `/api/commands`:
    - `pending` rulat ASC (executor),
    - celelalte statusuri livrate DESC (UI vede ultimele comenzi),
    - limit pentru UI extins (pana la 1000).
  - `/api/commands/ack-relay` foloseste si `gatewayId`.
- `windows-ui/GSMRelayDesktop/ViewModels/MainViewModel.cs`
  - poll la 10s,
  - incarcare 1000 comenzi pentru setup/coada locatie.
- `android/app/src/main/java/com/security/gsmrelay/data/network/ServerApi.kt`
  - fetch commands pana la 1000,
  - ACK trimite `gatewayId`.
- `android/app/src/main/java/com/security/gsmrelay/sms/SmsReceiver.kt`
  - ACK se trimite chiar daca releul nu este in cache local (fallback `fromNumber`).
- `android/app/src/main/java/com/security/gsmrelay/service/CommandPollService.kt`
  - guard anti-duplicate pe `commandId`,
  - retry update status catre server (3 incercari),
  - recovery pentru comenzi in-flight:
    - verifica marker SMS,
    - inchide comanda `done` la confirmare,
    - retry controlat daca lipseste confirmarea.

### Caz real inchis: `0731373297`
- Problema: retransmitere continua desi setup-ul parea finalizat.
- Root cause: comenzi legacy `sent` ramase in coada.
- Fix:
  - recovery pe `sent` restrictionat la setup + fereastra recenta,
  - cleanup punctual facut pe DB local: `sent` vechi -> `failed` pentru releul afectat.

### Builduri/artefacte validate
- Android client + gateway: build OK.
- Windows desktop: build OK.
- Copii in root (gata de instalare/rulare):
  - `GSMRelayGateway-debug.apk`
  - `GSMRelayClient-debug.apk`
  - `GSMRelayDesktop.lnk` (target pe ultimul `GSMRelayDesktop.exe` din Debug)

### Regula operationala setata
- Dupa fiecare build:
  - Android: copie automata `.apk` in root proiect.
  - Windows: update shortcut `.lnk` la ultimul exe build-uit.

### Checklist maine (scurt)
1. Deploy/restart server `server-firebase`.
2. Instaleaza pe telefon gateway ultimul `GSMRelayGateway-debug.apk`.
3. Verifica la onboarding multiplu:
   - tranzitii corecte `pending -> sent_waiting -> done`,
   - fara retransmitere infinita,
   - setup card in Windows se inchide la toti pasii.

