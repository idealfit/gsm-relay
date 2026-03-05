require("dotenv").config();

const express = require("express");
const cors = require("cors");
const { v4: uuidv4 } = require("uuid");
const { openDb } = require("./db");

const app = express();
app.use(cors());
app.use(express.json({ limit: "5mb" }));

const PORT = process.env.PORT || 3000;
const USER = process.env.GSM_USER || "admin";
const PASS = process.env.GSM_PASS || "admin";

function error(res, status, message) {
  return res.status(status).json({ error: message });
}

function basicAuth(req, res, next) {
  const header = req.headers.authorization || "";
  if (!header.startsWith("Basic ")) {
    res.set("WWW-Authenticate", "Basic");
    return error(res, 401, "Unauthorized");
  }
  const encoded = header.slice("Basic ".length).trim();
  const decoded = Buffer.from(encoded, "base64").toString("utf8");
  const [user, pass] = decoded.split(":");
  if (user !== USER || pass !== PASS) {
    return error(res, 403, "Forbidden");
  }
  req.syncUserId = user;
  return next();
}

app.get("/api/health", (_req, res) => {
  res.json({ ok: true });
});

app.get("/api/snapshot", basicAuth, async (req, res) => {
  const userId = req.syncUserId;
  try {
    const db = await openDb();
    const relaysRows = await db.all(
      "SELECT relayKey, id, name, phoneNumber, password, location, usersJson, lastSync FROM relays WHERE userId = ?",
      userId
    );
    const historyRows = await db.all(
      "SELECT id, relayName, relayPhone, command, description, timestamp, status FROM history WHERE userId = ? ORDER BY timestamp DESC",
      userId
    );
    const eventRows = await db.all(
      "SELECT id, relayName, relayPhone, operatorPhone, message, timestamp FROM events WHERE userId = ? ORDER BY timestamp DESC",
      userId
    );
    const locationRows = await db.all(
      "SELECT name FROM locations WHERE userId = ? ORDER BY name ASC",
      userId
    );

    const relays = relaysRows.map((row) => ({
      id: row.id || 0,
      name: row.name || "",
      phoneNumber: row.phoneNumber || "",
      password: row.password || "",
      location: row.location || "",
      users: safeJsonParse(row.usersJson, []),
      lastSync: row.lastSync || null,
      cloudBackup: false,
    }));

    const history = historyRows.map((row) => ({
      id: row.id || 0,
      relayName: row.relayName || "",
      relayPhone: row.relayPhone || "",
      command: row.command || "",
      description: row.description || "",
      timestamp: row.timestamp || 0,
      status: row.status || "",
    }));

    const events = eventRows.map((row) => ({
      id: row.id || 0,
      relayName: row.relayName || "",
      relayPhone: row.relayPhone || "",
      operatorPhone: row.operatorPhone || "",
      message: row.message || "",
      timestamp: row.timestamp || 0,
    }));

    const relayLocations = relays
      .map((relay) => normalizeLocationName(relay.location))
      .filter((name) => !!name);
    const explicitLocations = locationRows
      .map((row) => normalizeLocationName(row.name))
      .filter((name) => !!name);
    const locations = Array.from(new Set([...explicitLocations, ...relayLocations])).sort();

    res.json({ relays, history, events, locations });
  } catch (err) {
    console.error("snapshot_get_failed", err);
    return error(res, 500, "db_error");
  }
});

app.get("/api/commands", basicAuth, async (req, res) => {
  const userId = req.syncUserId;
  const status = (req.query.status || "").toString().trim();
  const gatewayId = (req.query.gatewayId || "").toString().trim();
  const requestedLimit = parseInt(req.query.limit || (status === "pending" ? "50" : "500"), 10) || (status === "pending" ? 50 : 500);
  const maxLimit = status === "pending" ? 200 : 1000;
  const limit = Math.max(1, Math.min(requestedLimit, maxLimit));
  const orderDirection = status === "pending" ? "ASC" : "DESC";
  try {
    const db = await openDb();
    const clauses = ["userId = ?"];
    const params = [userId];
    if (status) {
      clauses.push("status = ?");
      params.push(status);
    }
    if (gatewayId) {
      clauses.push("gatewayId = ?");
      params.push(gatewayId);
    }
    let whereSql = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
    if (status === "pending") {
      // Keep per-relay execution strictly sequential:
      // do not expose next pending command while a previous command for that relay is still waiting confirmation.
      whereSql += `${whereSql ? " AND " : "WHERE "}NOT EXISTS (
        SELECT 1
        FROM commands c2
        WHERE c2.userId = commands.userId
          AND c2.gatewayId = commands.gatewayId
          AND c2.relayKey = commands.relayKey
          AND c2.status = 'sent_waiting'
          AND c2.createdAt < commands.createdAt
      )`;
    }
    const rows = await db.all(
      `SELECT id, relayPhone, relayKey, gatewayId, command, description, status, source, createdAt, updatedAt, responseText
       FROM commands ${whereSql} ORDER BY createdAt ${orderDirection} LIMIT ?`,
      ...params,
      limit
    );
    const commands = rows.map((row) => ({
      id: row.id,
      relayPhone: row.relayPhone || "",
      relayKey: row.relayKey || "",
      gatewayId: row.gatewayId || "",
      command: row.command || "",
      description: row.description || "",
      status: row.status || "",
      source: row.source || "",
      createdAt: row.createdAt || 0,
      updatedAt: row.updatedAt || 0,
      responseText: row.responseText || "",
    }));
    res.json({ commands });
  } catch (err) {
    console.error("commands_get_failed", err);
    return error(res, 500, "db_error");
  }
});

app.post("/api/commands", basicAuth, async (req, res) => {
  const userId = req.syncUserId;
  const relayPhone = (req.body.relayPhone || "").toString().trim();
  const command = (req.body.command || "").toString().trim();
  const description = (req.body.description || "").toString().trim();
  const source = (req.body.source || "desktop").toString().trim();
  const gatewayId = (req.body.gatewayId || "").toString().trim();

  if (!relayPhone || !command || !gatewayId) {
    return error(res, 400, "relayPhone_command_gateway_required");
  }

  try {
    const db = await openDb();
    const now = Date.now();
    const id = uuidv4();
    const relayKey = normalizePhone(relayPhone);
    const assignment = parseUserAssignmentCommand(command);
    if (assignment && assignment.hasPhonePayload) {
      const requestedPhoneKey = normalizeUserPhoneForCompare(assignment.phonePayload);
      if (requestedPhoneKey) {
        const relayRow = await db.get(
          `SELECT usersJson
           FROM relays
           WHERE userId = ? AND relayKey = ?
           LIMIT 1`,
          userId,
          relayKey
        );
        const relayUsers = safeJsonParse(relayRow?.usersJson, []);
        const existingUser = Array.isArray(relayUsers)
          ? relayUsers.find((u) => normalizeUserPhoneForCompare(u?.phone || "") === requestedPhoneKey)
          : null;
        if (existingUser) {
          return res.status(409).json({
            error: "user_phone_exists_on_relay",
            slotId: Number(existingUser.id) || null,
          });
        }
      }

      const activeRows = await db.all(
        `SELECT id, command
         FROM commands
         WHERE userId = ? AND relayKey = ? AND status IN ('pending', 'sent_waiting', 'sent')`,
        userId,
        relayKey
      );
      const conflict = activeRows.find((row) => {
        const parsed = parseUserAssignmentCommand(row.command || "");
        return parsed && parsed.hasPhonePayload && parsed.slotId === assignment.slotId;
      });
      if (conflict) {
        return res.status(409).json({
          error: "slot_reserved_in_queue",
          slotId: assignment.slotId,
        });
      }
      if (requestedPhoneKey) {
        const phoneConflict = activeRows.find((row) => {
          const parsed = parseUserAssignmentCommand(row.command || "");
          if (!parsed || !parsed.hasPhonePayload) return false;
          return normalizeUserPhoneForCompare(parsed.phonePayload) === requestedPhoneKey;
        });
        if (phoneConflict) {
          const parsed = parseUserAssignmentCommand(phoneConflict.command || "");
          return res.status(409).json({
            error: "user_phone_exists_in_queue",
            slotId: parsed?.slotId || null,
          });
        }
      }
    }
    await db.run(
      `INSERT INTO commands (id, userId, relayPhone, relayKey, gatewayId, command, description, status, source, createdAt, updatedAt, responseText)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      id,
      userId,
      relayPhone,
      relayKey,
      gatewayId,
      command,
      description,
      "pending",
      source,
      now,
      now,
      ""
    );
    return res.json({ ok: true, id });
  } catch (err) {
    console.error("commands_post_failed", err);
    return error(res, 500, "db_error");
  }
});

app.post("/api/commands/:id/status", basicAuth, async (req, res) => {
  const userId = req.syncUserId;
  const commandId = req.params.id;
  const status = (req.body.status || "").toString().trim();
  const responseText = (req.body.responseText || "").toString();
  if (!commandId || !status) {
    return error(res, 400, "id_and_status_required");
  }
  try {
    const db = await openDb();
    await db.run(
      `UPDATE commands SET status = ?, responseText = ?, updatedAt = ? WHERE id = ? AND userId = ?`,
      status,
      responseText,
      Date.now(),
      commandId,
      userId
    );
    return res.json({ ok: true });
  } catch (err) {
    console.error("commands_status_failed", err);
    return error(res, 500, "db_error");
  }
});

app.post("/api/commands/ack-relay", basicAuth, async (req, res) => {
  const userId = req.syncUserId;
  const relayPhone = (req.body.relayPhone || "").toString().trim();
  const gatewayId = (req.body.gatewayId || "").toString().trim();
  const responseText = (req.body.responseText || "").toString();
  if (!relayPhone) {
    return error(res, 400, "relayPhone_required");
  }
  try {
    const db = await openDb();
    const relayKey = normalizePhone(relayPhone);
    if (!relayKey) {
      return error(res, 400, "relayPhone_invalid");
    }
    const now = Date.now();
    const row = gatewayId
      ? await db.get(
          `SELECT id
           FROM commands
           WHERE userId = ? AND relayKey = ? AND gatewayId = ? AND status = 'sent_waiting'
           ORDER BY createdAt ASC
           LIMIT 1`,
          userId,
          relayKey,
          gatewayId
        )
      : await db.get(
          `SELECT id
           FROM commands
           WHERE userId = ? AND relayKey = ? AND status = 'sent_waiting'
           ORDER BY createdAt ASC
           LIMIT 1`,
          userId,
          relayKey
        );
    if (!row?.id) {
      return res.json({ ok: true, updated: false });
    }
    await db.run(
      `UPDATE commands
       SET status = ?, responseText = ?, updatedAt = ?
       WHERE id = ? AND userId = ?`,
      "done",
      responseText,
      now,
      row.id,
      userId
    );
    return res.json({ ok: true, updated: true, id: row.id });
  } catch (err) {
    console.error("commands_ack_relay_failed", err);
    return error(res, 500, "db_error");
  }
});

app.delete("/api/relays/:relayPhone", basicAuth, async (req, res) => {
  const userId = req.syncUserId;
  const relayPhone = (req.params.relayPhone || "").toString().trim();
  const relayKey = normalizePhone(relayPhone);
  if (!relayKey) {
    return error(res, 400, "relayPhone_invalid");
  }

  try {
    const db = await openDb();
    const now = Date.now();
    await db.exec("BEGIN");

    await db.run(
      "DELETE FROM commands WHERE userId = ? AND relayKey = ?",
      userId,
      relayKey
    );
    await db.run(
      "DELETE FROM history WHERE userId = ? AND relayKey = ?",
      userId,
      relayKey
    );
    await db.run(
      "DELETE FROM events WHERE userId = ? AND relayKey = ?",
      userId,
      relayKey
    );

    const relayRow = await db.get(
      "SELECT location FROM relays WHERE userId = ? AND relayKey = ? LIMIT 1",
      userId,
      relayKey
    );
    await db.run(
      "DELETE FROM relays WHERE userId = ? AND relayKey = ?",
      userId,
      relayKey
    );
    await db.run(
      `INSERT INTO relay_deletions (userId, relayKey, deletedAt)
       VALUES (?, ?, ?)
       ON CONFLICT(userId, relayKey) DO UPDATE SET deletedAt = excluded.deletedAt`,
      userId,
      relayKey,
      now
    );

    const relayLocation = normalizeLocationName(relayRow?.location || "");
    if (relayLocation) {
      const locationStillUsed = await db.get(
        "SELECT 1 FROM relays WHERE userId = ? AND location = ? LIMIT 1",
        userId,
        relayLocation
      );
      if (!locationStillUsed) {
        await db.run(
          "DELETE FROM locations WHERE userId = ? AND name = ?",
          userId,
          relayLocation
        );
      }
    }

    await db.exec("COMMIT");
    return res.json({ ok: true, relayKey });
  } catch (err) {
    console.error("relay_delete_failed", err);
    try {
      const db = await openDb();
      await db.exec("ROLLBACK");
    } catch (_) {
      // ignore rollback errors
    }
    return error(res, 500, "db_error");
  }
});

app.post("/api/relays/:relayPhone/clear-db", basicAuth, async (req, res) => {
  const userId = req.syncUserId;
  const relayPhone = (req.params.relayPhone || "").toString().trim();
  const relayKey = normalizePhone(relayPhone);
  if (!relayKey) {
    return error(res, 400, "relayPhone_invalid");
  }

  try {
    const db = await openDb();
    const now = Date.now();
    await db.exec("BEGIN");

    const relayRow = await db.get(
      "SELECT 1 FROM relays WHERE userId = ? AND relayKey = ? LIMIT 1",
      userId,
      relayKey
    );
    if (!relayRow) {
      await db.exec("ROLLBACK");
      return error(res, 404, "relay_not_found");
    }

    await db.run(
      "DELETE FROM commands WHERE userId = ? AND relayKey = ?",
      userId,
      relayKey
    );
    await db.run(
      "DELETE FROM history WHERE userId = ? AND relayKey = ?",
      userId,
      relayKey
    );
    await db.run(
      "DELETE FROM events WHERE userId = ? AND relayKey = ?",
      userId,
      relayKey
    );
    await db.run(
      "UPDATE relays SET usersJson = ?, lastSync = ? WHERE userId = ? AND relayKey = ?",
      JSON.stringify([]),
      now,
      userId,
      relayKey
    );

    await db.exec("COMMIT");
    return res.json({ ok: true, relayKey });
  } catch (err) {
    console.error("relay_clear_db_failed", err);
    try {
      const db = await openDb();
      await db.exec("ROLLBACK");
    } catch (_) {
      // ignore rollback errors
    }
    return error(res, 500, "db_error");
  }
});

app.post("/api/snapshot", basicAuth, async (req, res) => {
  const userId = req.syncUserId;
  const relays = Array.isArray(req.body.relays) ? req.body.relays : [];
  const history = Array.isArray(req.body.history) ? req.body.history : [];
  const events = Array.isArray(req.body.events) ? req.body.events : [];
  const hasLocationsPayload = Array.isArray(req.body.locations);
  const incomingLocations = hasLocationsPayload
    ? req.body.locations.map((value) => normalizeLocationName(value)).filter((name) => !!name)
    : [];

  try {
    const db = await openDb();
    const existingRows = await db.all(
      "SELECT relayKey, id, name, phoneNumber, password, location, usersJson, lastSync FROM relays WHERE userId = ?",
      userId
    );
    const existingLocationRows = await db.all(
      "SELECT name FROM locations WHERE userId = ?",
      userId
    );
    const deletedRelayRows = await db.all(
      "SELECT relayKey, deletedAt FROM relay_deletions WHERE userId = ?",
      userId
    );
    const existingByPhone = new Map();
    existingRows.forEach((row) => {
      const key = row.relayKey || normalizePhone(row.phoneNumber);
      if (key) {
        existingByPhone.set(key, {
          id: row.id || 0,
          name: row.name || "",
          phoneNumber: row.phoneNumber || "",
          password: row.password || "",
          location: row.location || "",
          lastSync: row.lastSync || null,
          users: safeJsonParse(row.usersJson, []),
        });
      }
    });
    const deletedRelayByKey = new Map();
    deletedRelayRows.forEach((row) => {
      if (!row?.relayKey) return;
      const ts = Number(row.deletedAt || 0);
      deletedRelayByKey.set(row.relayKey, Number.isFinite(ts) ? ts : 0);
    });

    await db.exec("BEGIN");

    const incomingRelayKeys = new Set();
    const relayLocations = new Set();
    for (const relay of relays) {
      const key = normalizePhone(relay.phoneNumber);
      if (!key) continue;
      if (deletedRelayByKey.has(key)) {
        // Deleted relays stay deleted until explicit server-side restore.
        continue;
      }
      incomingRelayKeys.add(key);
      const relayLocation = normalizeLocationName(relay.location);
      if (relayLocation) {
        relayLocations.add(relayLocation);
      }
      const merged = mergeRelay(existingByPhone.get(key), relay);
      await db.run(
        `INSERT INTO relays (userId, relayKey, id, name, phoneNumber, password, location, usersJson, lastSync)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(userId, relayKey) DO UPDATE SET
           id = excluded.id,
           name = excluded.name,
           phoneNumber = excluded.phoneNumber,
           password = excluded.password,
           location = excluded.location,
           usersJson = excluded.usersJson,
           lastSync = excluded.lastSync`,
        userId,
        key,
        merged.id,
        merged.name,
        merged.phoneNumber,
        merged.password,
        merged.location || "",
        JSON.stringify(merged.users || []),
        merged.lastSync || null
      );
    }
    // Snapshot payloads can be partial (from gateway/device local cache).
    // Keep server relays as source of truth and only upsert incoming relays;
    // do not delete relays that are missing from this specific payload.

    const existingLocations = existingLocationRows
      .map((row) => normalizeLocationName(row.name))
      .filter((name) => !!name);
    const finalLocations = hasLocationsPayload
      ? new Set([...incomingLocations, ...relayLocations])
      : new Set([...existingLocations, ...relayLocations]);

    await db.run("DELETE FROM locations WHERE userId = ?", userId);
    for (const location of finalLocations) {
      await db.run(
        `INSERT INTO locations (userId, name) VALUES (?, ?)
         ON CONFLICT(userId, name) DO NOTHING`,
        userId,
        location
      );
    }

    for (const item of history) {
      if (!item) continue;
      const relayKey = normalizePhone(item.relayPhone) || "unknown";
      if (relayKey !== "unknown" && deletedRelayByKey.has(relayKey)) continue;
      const docId = `${relayKey}_${item.id}`;
      await db.run(
        `INSERT INTO history (docId, userId, relayKey, id, relayName, relayPhone, command, description, timestamp, status)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(docId) DO UPDATE SET
           relayName = excluded.relayName,
           relayPhone = excluded.relayPhone,
           command = excluded.command,
           description = excluded.description,
           timestamp = excluded.timestamp,
           status = excluded.status`,
        docId,
        userId,
        relayKey,
        item.id || 0,
        item.relayName || "",
        item.relayPhone || "",
        item.command || "",
        item.description || "",
        item.timestamp || 0,
        item.status || ""
      );
    }

    for (const item of events) {
      if (!item) continue;
      const relayKey = normalizePhone(item.relayPhone) || "unknown";
      if (relayKey !== "unknown" && deletedRelayByKey.has(relayKey)) continue;
      const docId = `${relayKey}_${item.id}`;
      await db.run(
        `INSERT INTO events (docId, userId, relayKey, id, relayName, relayPhone, operatorPhone, message, timestamp)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(docId) DO UPDATE SET
           relayName = excluded.relayName,
           relayPhone = excluded.relayPhone,
           operatorPhone = excluded.operatorPhone,
           message = excluded.message,
           timestamp = excluded.timestamp`,
        docId,
        userId,
        relayKey,
        item.id || 0,
        item.relayName || "",
        item.relayPhone || "",
        item.operatorPhone || "",
        item.message || "",
        item.timestamp || 0
      );
    }

    await db.exec("COMMIT");
    return res.json({ ok: true, relays: relays.length, history: history.length, events: events.length });
  } catch (err) {
    console.error("snapshot_post_failed", err);
    try {
      const db = await openDb();
      await db.exec("ROLLBACK");
    } catch (_) {
      // ignore rollback errors
    }
    return error(res, 500, "db_error");
  }
});

app.listen(PORT, () => {
  console.log(`SQLite snapshot server running on port ${PORT}`);
});

function normalizePhone(phone) {
  if (!phone) return "";
  const digits = String(phone).replace(/\D/g, "");
  return digits.slice(-8);
}

function mergeRelay(existing, incoming) {
  if (!existing) {
    return {
      id: incoming.id,
      name: incoming.name,
      phoneNumber: incoming.phoneNumber,
      password: incoming.password,
      location: incoming.location || "",
      lastSync: incoming.lastSync || null,
      users: incoming.users || [],
    };
  }
  const existingSync = relaySyncValue(existing);
  const incomingSync = relaySyncValue(incoming);
  const preferIncoming = incomingSync >= existingSync || existingSync === 0;
  const primary = preferIncoming ? incoming : existing;
  const secondary = preferIncoming ? existing : incoming;
  return {
    id: preferIncoming ? incoming.id || existing.id : existing.id,
    name: preferredText(primary.name, secondary.name),
    phoneNumber: existing.phoneNumber || incoming.phoneNumber,
    password: preferredText(primary.password, secondary.password),
    location: typeof primary.location === "string"
      ? primary.location
      : (typeof secondary.location === "string" ? secondary.location : ""),
    lastSync: Math.max(existingSync, incomingSync) || null,
    users: preferIncoming
      ? mergeUsersAuthoritative(incoming.users || [], existing.users || [])
      : mergeUsersAuthoritative(existing.users || [], incoming.users || []),
  };
}

function normalizeLocationName(location) {
  if (typeof location !== "string") return "";
  const name = location.trim();
  if (!name) return "";
  if (name.toLowerCase() === "fara locatie") return "";
  return name;
}

function relaySyncValue(relay) {
  const value = Number(relay?.lastSync || 0);
  return Number.isFinite(value) ? value : 0;
}

function preferredText(primary, fallback) {
  const first = (primary || "").toString().trim();
  if (first) return first;
  const second = (fallback || "").toString().trim();
  return second;
}

function mergeUsersAuthoritative(authoritativeUsers, fallbackUsers) {
  if (!authoritativeUsers || authoritativeUsers.length === 0) return fallbackUsers || [];
  const authById = new Map(authoritativeUsers.map((u) => [u.id, u]));
  const fallbackById = new Map((fallbackUsers || []).map((u) => [u.id, u]));
  const ids = new Set([...authById.keys(), ...fallbackById.keys()]);
  const merged = [];
  Array.from(ids)
    .sort((a, b) => a - b)
    .forEach((id) => {
      if (authById.has(id)) {
        // Keep authoritative entry exactly as-is, including explicit empty values.
        merged.push(authById.get(id));
        return;
      }
      merged.push(fallbackById.get(id));
    });
  return merged;
}

function safeJsonParse(value, fallback) {
  if (!value) return fallback;
  try {
    return JSON.parse(value);
  } catch (_err) {
    return fallback;
  }
}

function parseUserAssignmentCommand(command) {
  const text = (command || "").toString().trim();
  if (!text) return null;

  for (let i = 0; i <= text.length - 5; i += 1) {
    if (text[i].toUpperCase() !== "A") continue;
    const d1 = text[i + 1];
    const d2 = text[i + 2];
    const d3 = text[i + 3];
    if (!/\d/.test(d1) || !/\d/.test(d2) || !/\d/.test(d3)) continue;
    if (text[i + 4] !== "#") continue;

    const slotId = Number.parseInt(text.slice(i + 1, i + 4), 10);
    if (!Number.isFinite(slotId)) return null;

    const payloadStart = i + 5;
    const payloadEnd = text.indexOf("#", payloadStart);
    if (payloadEnd < 0) return null;
    const payload = text.slice(payloadStart, payloadEnd).trim();
    return {
      slotId,
      hasPhonePayload: payload.length > 0,
      phonePayload: payload,
    };
  }

  return null;
}

function normalizeUserPhoneForCompare(phone) {
  const digits = String(phone || "").replace(/\D/g, "");
  if (!digits) return "";
  if (digits.startsWith("40") && digits.length === 11) {
    return `0${digits.slice(2)}`;
  }
  if (digits.length === 9) {
    return `0${digits}`;
  }
  if (digits.length > 10) {
    return digits.slice(-10);
  }
  return digits;
}
