const sqlite3 = require('./server-firebase/node_modules/sqlite3');
const db = new sqlite3.Database('D:/vlad/IDEAL FIT/SCRIPT/gsm relay/server-firebase/data/gsm-relay.db');
const phone = '0731372817';
const key = phone.replace(/\D/g,'').slice(-8);
const q1 = `SELECT id,name,phoneNumber,relayKey,location,lastSync,password FROM relays WHERE phoneNumber LIKE '%${phone}%' OR relayKey LIKE '%${key}%'`;
const q2 = `SELECT id,relayPhone,relayKey,command,status,createdAt,updatedAt,responseText FROM commands WHERE relayPhone LIKE '%${phone}%' OR relayKey LIKE '%${key}%' ORDER BY createdAt DESC LIMIT 40`;
db.all(q1, [], (e, relays) => {
  if (e) { console.error('Q1_ERR', e.message); process.exit(1); }
  console.log('RELAYS', JSON.stringify(relays, null, 2));
  db.all(q2, [], (e2, commands) => {
    if (e2) { console.error('Q2_ERR', e2.message); process.exit(1); }
    console.log('COMMANDS', JSON.stringify(commands, null, 2));
    db.close();
  });
});
