"""Bounded native plan/diff/usage observations, never a copy of chat history."""
import json
import time


class Observations:
    def __init__(self, db):
        self.db = db

    def put(self, thread_id, kind, turn_id, payload):
        encoded = json.dumps(payload, ensure_ascii=False)
        if len(encoded.encode()) > 2 * 1024 * 1024:
            return
        with self.db.transaction() as c:
            c.execute("INSERT OR REPLACE INTO native_observations VALUES(?,?,?,?,?)",
                      (thread_id, kind, turn_id or '', encoded, time.time()))
            c.execute("DELETE FROM native_observations WHERE rowid IN (SELECT rowid FROM native_observations ORDER BY updated_at DESC LIMIT -1 OFFSET 1000)")

    def get(self, thread_id):
        with self.db.read() as c:
            return {(r['kind'], r['turn_id']): json.loads(r['payload_json']) for r in
                    c.execute("SELECT * FROM native_observations WHERE thread_id=? ORDER BY updated_at", (thread_id,))}
