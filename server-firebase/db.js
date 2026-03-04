const fs = require("fs");
const path = require("path");
const sqlite3 = require("sqlite3");
const { open } = require("sqlite");

let db;

async function openDb() {
  if (db) return db;
  const dataDir = process.env.DATA_DIR || path.join(__dirname, "data");
  fs.mkdirSync(dataDir, { recursive: true });
  const filename = process.env.DB_FILE || path.join(dataDir, "gsm-relay.db");

  db = await open({ filename, driver: sqlite3.Database });
  await db.exec("PRAGMA journal_mode=WAL;");
  await db.exec("PRAGMA foreign_keys=ON;");

  await db.exec(`
    CREATE TABLE IF NOT EXISTS relays (
      userId TEXT NOT NULL,
      relayKey TEXT NOT NULL,
      id INTEGER,
      name TEXT,
      phoneNumber TEXT,
      password TEXT,
      location TEXT,
      usersJson TEXT,
      lastSync INTEGER,
      PRIMARY KEY (userId, relayKey)
    );
  `);

  await db.exec(`
    CREATE TABLE IF NOT EXISTS history (
      docId TEXT PRIMARY KEY,
      userId TEXT NOT NULL,
      relayKey TEXT,
      id INTEGER,
      relayName TEXT,
      relayPhone TEXT,
      command TEXT,
      description TEXT,
      timestamp INTEGER,
      status TEXT
    );
  `);

  await db.exec(`
    CREATE TABLE IF NOT EXISTS commands (
      id TEXT PRIMARY KEY,
      userId TEXT NOT NULL,
      relayPhone TEXT,
      relayKey TEXT,
      gatewayId TEXT,
      command TEXT,
      description TEXT,
      status TEXT,
      source TEXT,
      createdAt INTEGER,
      updatedAt INTEGER,
      responseText TEXT
    );
  `);

  await db.exec(`
    CREATE TABLE IF NOT EXISTS events (
      docId TEXT PRIMARY KEY,
      userId TEXT NOT NULL,
      relayKey TEXT,
      id INTEGER,
      relayName TEXT,
      relayPhone TEXT,
      operatorPhone TEXT,
      message TEXT,
      timestamp INTEGER
    );
  `);

  await db.exec(`
    CREATE TABLE IF NOT EXISTS locations (
      userId TEXT NOT NULL,
      name TEXT NOT NULL,
      PRIMARY KEY (userId, name)
    );
  `);

  await db.exec(`
    CREATE TABLE IF NOT EXISTS relay_deletions (
      userId TEXT NOT NULL,
      relayKey TEXT NOT NULL,
      deletedAt INTEGER NOT NULL,
      PRIMARY KEY (userId, relayKey)
    );
  `);

  await db.exec(`CREATE INDEX IF NOT EXISTS idx_history_user_ts ON history(userId, timestamp DESC);`);
  await db.exec(`CREATE INDEX IF NOT EXISTS idx_commands_user_status ON commands(userId, status);`);
  await db.exec(`CREATE INDEX IF NOT EXISTS idx_commands_user_gateway ON commands(userId, gatewayId);`);
  await db.exec(`CREATE INDEX IF NOT EXISTS idx_events_user_ts ON events(userId, timestamp DESC);`);
  await db.exec(`CREATE INDEX IF NOT EXISTS idx_locations_user_name ON locations(userId, name);`);
  await db.exec(`CREATE INDEX IF NOT EXISTS idx_relay_deletions_user_key ON relay_deletions(userId, relayKey);`);

  return db;
}

module.exports = { openDb };
