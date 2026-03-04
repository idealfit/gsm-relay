# GSM Relay Project Status (2026-02-19)

## TL;DR
Starea proiectului este buna pentru continuare. Fluxul de onboarding releu a fost refacut astfel incat sa nu se mai blocheze dupa prima comanda si sa suporte perioade in care serverul este offline.

## Update sesiune (2026-02-20)

### Dashboard setup state machine in Windows UI (implementat)
- In `windows-ui/GSMRelayDesktop`, pe releul selectat se afiseaza acum un card de progres setup:
  - stare: `Setup in curs` / `Setup blocat` / `Setup finalizat`
  - pas curent (`Pas X/5` sau `Pas X/6` daca exista master 001)
  - marker SMS asteptat pentru pasul activ
  - timp scurs de asteptare pentru pasul activ
  - ultimul SMS relevant (din evenimentele releului)
- Detectia pasilor foloseste comenzile reale din coada (`pending`, `sent_waiting`, `done`, `failed`) pentru batch-ul cel mai recent de onboarding.

### Build verificat
- `dotnet build windows-ui/GSMRelayDesktop/GSMRelayDesktop.csproj -c Debug --no-restore` -> **OK**

### iOS Client - start implementare (2026-02-20)
- A fost creat folderul `ios-client/GSMRelayClientIOS` cu baza SwiftUI pentru client (fara SMS/gateway):
  - modele API compatibile cu serverul (`ServerSnapshot`, `CommandQueueItem`, `Relay`, etc.)
  - `APIClient` pentru `/api/snapshot`, `/api/commands` (list + create)
  - persistenta configuratie server cu valori implicite precompletate
  - ecrane initiale: `Relays`, `Relay detail + send command`, `Command queue`, `Settings`
- Configuratia implicita iOS este aliniata cu Windows/Android:
  - URL `http://86.120.150.58:5174`
  - user `admin`
  - parola `admin1316`
  - gateway `pQF6bci9`
  - master `0724264464`

### iOS Client - extindere paritate Android (2026-02-20)
- ViewModel iOS extins cu logica apropiata de Android Client:
  - sync snapshot + queue, auto-sync 15s
  - selectie locatie/releu persistata in UI
  - management locatii (add/rename/delete)
  - management relee (add/edit/delete)
  - management utilizatori (add/delete + bulk pe selectie)
  - comenzi releu (query, password, timer, ALL/AUT, scrape)
  - istoric + notificari locale in fluxul de comenzi
- UI iOS refacut pe flow-ul Android:
  - `Locations -> Location detail (Relee/Coada/Evenimente) -> Relay detail (Utilizatori/Comenzi/Coada/Istoric/Evenimente/Notificari)`

### iOS Client - paritate functionala extinsa (2026-02-20)
- Adaugat import/export CSV pentru utilizatori pe releu.
- Adaugat export CSV pentru evenimente pe releu si pe locatie.
- Adaugat filtrare interval timp pe evenimente (releu + locatie).
- Adaugat `Scrape` pe interval pentru releu si `Scrape location` pentru toate releele din locatie.
- Adaugate validari suplimentare:
  - query users cu interval invalid,
  - scrape interval invalid,
  - add user pe slot neverificat sau ocupat.

### GitHub / CI iOS (final de zi)
- Repo GitHub activ: `https://github.com/idealfit/gsm-relay`
- Workflow iOS activ: `.github/workflows/ios-build.yml`
- Build iOS pe Actions a rulat, dar a cazut pe eroare Swift:
  - `AppViewModel.swift:691:13: expected ':' after '? ...' in ternary expression`
- Fixul pentru aceasta eroare a fost pregatit local:
  - functie `nextFreeKnownSlot(...)` rescrisa explicit (`slot?.id`) pentru compatibilitate Xcode 16.4.
- Daca build-ul e inca rosu maine, primul pas este push la ultimul fix local si rerun workflow.

## Ce s-a schimbat azi (important)

### 1) Fix major onboarding (blocaj dupa prima comanda)
- Problema initiala: procesul de initializare releu se putea opri dupa prima comanda.
- Rezolvare:
  - Windows onboarding trimite secventa robust (fara a se bloca de `SelectedRelay`).
  - Android Gateway si Android app au trecut pe workflow orientat pe confirmari SMS, nu doar delay fix.

### 2) Confirmari SMS pe markeri reali (din teren)
Pe baza mesajelor reale de la releu, sistemul asteapta:
- `1234P2005` -> exceptie: timeout fix 15 sec.
- `2005T...` -> marker: `Set Time OK`
- `2005A001#...#` -> marker: `001:`
- `2005GON10#RIDICARE/DESCHIDERE#` -> marker: `Relay ON will return SMS`
- `2005GOFF##` -> marker: `Relay OFF will not return SMS`
- `2005AL001#500#` -> marker final: `500:`

### 3) Auto-admin pe primele sloturi libere
- Ramane pe logica corecta: inrolare automata pe primele sloturi libere/known.
- Nu se folosesc pozitii fixe A002..A011.

### 4) Comenzi in asteptare cand releul nu raspunde (offline/power loss)
Schimbare critica pentru productie:
- Comanda trimisa din coada nu mai e marcata direct `done`, ci `sent_waiting`.
- Serverul nu mai livreaza urmatoarea comanda pentru acelasi releu pana nu vine confirmarea SMS.
- Cand vine SMS de confirmare, gateway face ACK la server si comanda trece in `done`.

Asta permite scenariul real:
- releu fara curent/retea -> comanda ramane in asteptare;
- releu revine si trimite SMS -> fluxul continua de unde a ramas.

### 5) Endpoint nou pe server
- Adaugat: `POST /api/commands/ack-relay`
- Rol: marcheaza `done` cea mai veche comanda `sent_waiting` pentru releul confirmat prin SMS.

## Fisiere principale modificate in aceasta sesiune
- `server/index.js`
- `android/app/src/main/java/com/security/gsmrelay/data/network/ServerApi.kt`
- `android/app/src/main/java/com/security/gsmrelay/service/CommandPollService.kt`
- `android/app/src/main/java/com/security/gsmrelay/sms/SmsReceiver.kt`
- `android/app/src/main/java/com/security/gsmrelay/viewmodel/AppViewModel.kt`
- `windows-ui/GSMRelayDesktop/ViewModels/MainViewModel.cs`

## Build/artefacte validate azi
- Windows build Debug: OK
- Android build Debug (client + gateway): OK
- APK gateway disponibil:
  - `D:\vlad\IDEAL FIT\SCRIPT\gsm relay\GSMRelayGateway-debug.apk`

## Shortcut Windows
- `D:\vlad\IDEAL FIT\SCRIPT\gsm relay\GSMRelayDesktop.lnk`
- Target curent:
  - `windows-ui/GSMRelayDesktop/bin/Debug/net8.0-windows/GSMRelayDesktop.exe`

## Cum continui maine (fara explicatii lungi)

1. Porneste serverul local (cand ai net si PC-ul server online):
```powershell
cd "D:\vlad\IDEAL FIT\SCRIPT\gsm relay\server"
node index.js
```

2. Verifica health:
```powershell
curl http://127.0.0.1:5174/api/health
```
Asteptat: `{"ok":true}`

3. Pe telefonul Gateway:
- instaleaza/ruleaza `GSMRelayGateway-debug.apk`
- lasa app activa (foreground service)

4. Test onboarding releu nou:
- verifica in Coada ca statusul urmeaza: `pending` -> `sent_waiting` -> `done` pe masura ce vin SMS-urile.
- daca releul e offline, statusul trebuie sa ramana `sent_waiting` pana la revenire.

5. iOS CI unblock + test:
- impinge ultimul fix iOS local (daca nu e deja in `origin/main`),
- ruleaza `iOS Client Build` in GitHub Actions,
- daca e verde: incepe UAT comparativ Android vs iOS,
- daca e rosu: pastreaza doar primele linii `error:` din log pentru fix rapid.

## Observatii operationale
- Din Gateway app comanda poate pleca direct SMS chiar daca serverul e picat.
- Din Windows/Android Client comanda trece prin server; daca serverul e jos, ele nu pot queue-ui comenzi.

## Riscuri ramase (cunoscute)
- iOS inca necesita validare practica pe device/simulator dupa build verde in CI.
- In continuare exista warning-uri minore Android deprecated API, fara impact functional imediat.

## Update sesiune (2026-02-20 - sync hardening)

### Obiectiv
- S-a lucrat pe "simbioza" Windows + Android Client + Android Gateway, cu SMS ca sursa de confirmare pentru executie.

### Fixuri implementate azi
- Server (`server-firebase/index.js`):
  - `/api/commands`:
    - `pending` ramane ordonat ASC (executie corecta pe gateway),
    - restul statusurilor sunt livrate DESC (UI vede comenzile cele mai noi),
    - limita marita pentru UI pana la 1000.
  - `ack-relay` primeste si `gatewayId` (ACK corect pe gateway-ul curent).
- Windows (`windows-ui/GSMRelayDesktop/ViewModels/MainViewModel.cs`):
  - auto-refresh redus la 10 secunde,
  - lista comenzi marita la 1000 pentru vizibilitate setup curent.
- Android Client:
  - fetch queue marit la 1000 (`AppViewModel.kt`, `ServerApi.kt`).
- Android Gateway:
  - ACK din `SmsReceiver` merge si cand releul nu e inca in cache local (fallback pe `fromNumber`),
  - `CommandPollService`:
    - retry status update cu backoff scurt,
    - guard anti-duplicate pe `commandId` (nu retrimite aceeasi comanda imediat),
    - recovery pentru `sent_waiting`: verifica marker SMS, marcheaza `done` daca exista confirmare, altfel retry controlat.

### Incident gestionat: releu `0731373297`
- Simptom: trimiteri continue desi setup vizual parea finalizat.
- Cauza: comenzi legacy cu status `sent` ramase in coada (vechi), reluate de recovery.
- Corectie:
  - recovery pe gateway pentru `sent` este restrictionat doar la comenzi setup + doar daca sunt recente (fereastra limitata),
  - cleanup punctual executat pe DB local server: `sent` vechi pentru `0731373297` marcate `failed`.

### Contract functional consolidat
- SMS confirma executia reala.
- Gateway executa, parseaza SMS, actualizeaza statusurile in server.
- Server ramane sursa unica de adevar pentru coada (`pending`, `sent_waiting`, `done`, `failed`).
- Windows/Android Client afiseaza si orchestreaza, fara "confirmari locale" care pot diverge.

### Build-uri validate in aceasta sesiune
- `dotnet build windows-ui/GSMRelayDesktop/GSMRelayDesktop.csproj -c Debug --no-restore` -> OK
- `.\gradlew.bat :app:assembleDebug :app:assembleGatewayDebug` -> OK

### Artefacte livrate in root proiect (regula operationala)
- `GSMRelayGateway-debug.apk`
- `GSMRelayClient-debug.apk`
- `GSMRelayDesktop.lnk` actualizat la:
  - `windows-ui/GSMRelayDesktop/bin/Debug/net8.0-windows/GSMRelayDesktop.exe`

