"""Bounded native plan/diff/usage observations, never a copy of chat history."""
import json
import time


class Observations:
    def __init__(self, db):
        self.db = db

    def put(self, thread_id, kind, turn_id, payload):
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True)
        if len(encoded.encode()) > 2 * 1024 * 1024:
            return
        with self.db.transaction() as c:
            changed = c.execute("""INSERT INTO native_observations VALUES(?,?,?,?,?)
                ON CONFLICT(thread_id,kind,turn_id) DO UPDATE SET
                    payload_json=excluded.payload_json, updated_at=excluded.updated_at
                WHERE native_observations.payload_json != excluded.payload_json""",
                (thread_id, kind, turn_id or '', encoded, time.time())).rowcount
            if not changed:
                return  # Repeated reads must not dirty WAL pages or refresh retention order.
            c.execute("DELETE FROM native_observations WHERE rowid IN (SELECT rowid FROM native_observations WHERE kind NOT IN ('ownership','externalWorkspace','workspace_share','questionAnswer') ORDER BY updated_at DESC LIMIT -1 OFFSET 1000)")

    def get(self, thread_id):
        with self.db.read() as c:
            return {(r['kind'], r['turn_id']): json.loads(r['payload_json']) for r in
                    c.execute("SELECT * FROM native_observations WHERE thread_id=? ORDER BY updated_at", (thread_id,))}

    def delete(self, thread_id, kind, turn_id=''):
        with self.db.transaction() as c:
            c.execute('DELETE FROM native_observations WHERE thread_id=? AND kind=? AND turn_id=?', (thread_id, kind, turn_id))
