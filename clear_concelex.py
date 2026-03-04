import argparse
import json
import shutil
import sqlite3
from datetime import datetime
from pathlib import Path


def clear_concelex(db_path: Path, user: str, relay_key: str, max_users: int) -> None:
    if not db_path.exists():
        raise FileNotFoundError(f"DB not found: {db_path}")

    backup_path = db_path.with_name(f"{db_path.name}.bak-concelex-{datetime.now().strftime('%Y%m%d-%H%M%S')}")
    shutil.copy2(db_path, backup_path)

    con = sqlite3.connect(db_path)
    try:
        cur = con.cursor()

        relay = cur.execute(
            "SELECT userId, id, name, phoneNumber, location, usersJson FROM relays WHERE userId=? AND relayKey=?",
            (user, relay_key),
        ).fetchone()
        if not relay:
            raise RuntimeError(f"Relay not found for user='{user}', relayKey='{relay_key}'")

        before_counts = cur.execute(
            "SELECT status, COUNT(*) FROM commands WHERE userId=? AND relayKey=? GROUP BY status",
            (user, relay_key),
        ).fetchall()

        cur.execute("DELETE FROM commands WHERE userId=? AND relayKey=?", (user, relay_key))
        deleted_commands = cur.rowcount

        users_json = relay[5] or "[]"
        try:
            users = json.loads(users_json)
            if not isinstance(users, list):
                users = []
        except Exception:
            users = []

        cleared = []
        if users:
            for i, u in enumerate(users, start=1):
                uid = (u or {}).get("id") if isinstance(u, dict) else None
                if not isinstance(uid, int):
                    uid = i
                cleared.append(
                    {
                        "id": uid,
                        "phone": "",
                        "name": "",
                        "group": "general",
                        "addedDate": None,
                        "known": False,
                    }
                )
        else:
            for uid in range(1, max_users + 1):
                cleared.append(
                    {
                        "id": uid,
                        "phone": "",
                        "name": "",
                        "group": "general",
                        "addedDate": None,
                        "known": False,
                    }
                )

        cur.execute(
            "UPDATE relays SET usersJson=?, lastSync=? WHERE userId=? AND relayKey=?",
            (json.dumps(cleared, separators=(",", ":")), int(datetime.now().timestamp() * 1000), user, relay_key),
        )

        after_counts = cur.execute(
            "SELECT status, COUNT(*) FROM commands WHERE userId=? AND relayKey=? GROUP BY status",
            (user, relay_key),
        ).fetchall()

        users_after_row = cur.execute(
            "SELECT usersJson FROM relays WHERE userId=? AND relayKey=?",
            (user, relay_key),
        ).fetchone()
        users_after = json.loads(users_after_row[0]) if users_after_row and users_after_row[0] else []
        non_empty_after = [u for u in users_after if str((u or {}).get("phone") or "").strip()]

        con.commit()

        print(f"backup: {backup_path}")
        print(f"relay: name='{relay[2]}', phone='{relay[3]}', location='{relay[4]}'")
        print(f"before_status_counts: {before_counts}")
        print(f"deleted_commands: {deleted_commands}")
        print(f"after_status_counts: {after_counts}")
        print(f"users_with_phone_after: {len(non_empty_after)}")
        print("done")
    finally:
        con.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Clear commands and users for Concelex Targoviste relay.")
    parser.add_argument(
        "--db",
        default=r"D:\vlad\IDEAL FIT\SCRIPT\gsm relay\server-firebase\data\gsm-relay.db",
        help="Path to sqlite DB file",
    )
    parser.add_argument("--user", default="admin", help="User ID in DB")
    parser.add_argument("--relay-key", default="23258741", help="Relay key to clean")
    parser.add_argument("--max-users", type=int, default=200, help="Fallback number of empty user slots")
    args = parser.parse_args()

    clear_concelex(Path(args.db), args.user, args.relay_key, args.max_users)


if __name__ == "__main__":
    main()
