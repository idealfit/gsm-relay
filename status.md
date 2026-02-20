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

## Observatii operationale
- Din Gateway app comanda poate pleca direct SMS chiar daca serverul e picat.
- Din Windows/Android Client comanda trece prin server; daca serverul e jos, ele nu pot queue-ui comenzi.

## Riscuri ramase (cunoscute)
- Nu exista inca dashboard explicit de tip "setup step state machine" in UI (ex: STEP 3/6 waiting marker).
- In continuare exista warning-uri minore Android deprecated API, fara impact functional imediat.

