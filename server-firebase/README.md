# GSM Relay Snapshot Server (SQLite)

Backend Node.js + Express pentru sincronizare compatibila cu aplicatia Android si Windows.
Stocheaza datele local in SQLite (fisier).

## Structura
- package.json
- index.js
- db.js
- .env
- README.md

## 1) Configurare
Editeaza `.env`:
- `PORT` (implicit 3000)
- `GSM_USER` / `GSM_PASS` (Basic Auth)
- `DATA_DIR` (optional, implicit `./data`)
- `DB_FILE` (optional, implicit `./data/gsm-relay.db`)

## 2) Ruleaza local
```bash
npm install
node index.js
```
Serverul ruleaza pe `http://localhost:3000` (sau PORT din .env).

## 3) Endpoint-uri (compatibile cu app)
### GET /api/health
Raspuns:
```json
{ "ok": true }
```

### GET /api/snapshot
Necesita Basic Auth.
Raspuns:
```json
{
  "relays": [
    {
      "id": 1,
      "name": "Relay 1",
      "phoneNumber": "072...",
      "password": "1234",
      "location": "",
      "users": [],
      "lastSync": 1700000000000,
      "cloudBackup": false
    }
  ],
  "history": [
    {
      "id": 1,
      "relayName": "Relay 1",
      "relayPhone": "072...",
      "command": "AL001",
      "description": "Interogare",
      "timestamp": 1700000000000,
      "status": "confirmed"
    }
  ]
}
```

### POST /api/snapshot
Necesita Basic Auth.
Body identic cu snapshot-ul aplicatiei:
```json
{
  "relays": [ ... ],
  "history": [ ... ]
}
```
Raspuns:
```json
{ "ok": true, "relays": 1, "history": 2 }
```

### GET /api/commands
Necesita Basic Auth.
Query:
- `status` (optional, ex: `pending`)
- `limit` (optional):
  - `status=pending` -> max 200
  - alte statusuri / fara status -> max 1000
- `gatewayId` (optional, recomandat)

Raspuns:
```json
{
  "commands": [
    {
      "id": "uuid",
      "relayPhone": "072...",
      "relayKey": "12345678",
      "gatewayId": "gateway-01",
      "command": "0000A001#0712345678#",
      "description": "Adauga user",
      "status": "pending",
      "source": "desktop",
      "createdAt": 1700000000000,
      "updatedAt": 1700000000000,
      "responseText": ""
    }
  ]
}
```

### POST /api/commands
Necesita Basic Auth.
Body:
```json
{
  "relayPhone": "072...",
  "gatewayId": "gateway-01",
  "command": "0000A001#0712345678#",
  "description": "Adauga user",
  "source": "desktop"
}
```
Raspuns:
```json
{ "ok": true, "id": "uuid" }
```

### POST /api/commands/:id/status
Necesita Basic Auth.
Body:
```json
{ "status": "sent", "responseText": "" }
```
Raspuns:
```json
{ "ok": true }
```

## Model de date in SQLite
- `relays` (PRIMARY KEY: userId + relayKey)
- `history` (PRIMARY KEY: docId)
- `commands` (PRIMARY KEY: id)
