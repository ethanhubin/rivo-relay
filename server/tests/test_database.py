from contextlib import closing
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from rivo_relay.database import Database, SCHEMA_VERSION


class DatabaseTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "rivo.sqlite3"
        self.db = Database(self.path)
        self.addCleanup(self.db.close)

    def test_pragmas_and_private_new_database(self):
        with self.db.read() as connection:
            self.assertEqual(connection.execute("PRAGMA journal_mode").fetchone()[0], "wal")
            self.assertEqual(connection.execute("PRAGMA synchronous").fetchone()[0], 2)
            self.assertEqual(connection.execute("PRAGMA foreign_keys").fetchone()[0], 1)
            self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], SCHEMA_VERSION)
        self.assertEqual(os.stat(self.path).st_mode & 0o777, 0o600)

    def test_outer_rollback_undoes_successful_nested_operation(self):
        with self.assertRaises(RuntimeError):
            with self.db.transaction() as connection:
                connection.execute("INSERT INTO connections VALUES ('one','digest','Phone',1,NULL)")
                with self.db.transaction() as nested:
                    nested.execute("INSERT INTO connections VALUES ('two','other','Phone',1,NULL)")
                raise RuntimeError("outer failed")
        with self.db.read() as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM connections").fetchone()[0], 0)

    def test_nested_rollback_keeps_outer_transaction_usable(self):
        with self.db.transaction() as connection:
            connection.execute("INSERT INTO connections VALUES ('one','digest','Phone',1,NULL)")
            with self.assertRaises(RuntimeError):
                with self.db.transaction() as nested:
                    nested.execute("INSERT INTO connections VALUES ('two','other','Phone',1,NULL)")
                    raise RuntimeError("inner failed")
            self.assertTrue(connection.in_transaction)
        with self.db.read() as connection:
            self.assertEqual([r[0] for r in connection.execute("SELECT id FROM connections")], ["one"])

    def test_unrelated_database_is_rejected_without_schema_migration(self):
        path = Path(self.directory.name) / "unrelated.sqlite3"
        with closing(sqlite3.connect(path)) as connection, connection:
            connection.execute("CREATE TABLE existing_data(value TEXT)")
            connection.execute("INSERT INTO existing_data VALUES ('keep')")
        with self.assertRaises(RuntimeError):
            Database(path)
        with closing(sqlite3.connect(path)) as connection:
            self.assertEqual(connection.execute("SELECT value FROM existing_data").fetchone()[0], "keep")
            self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 0)

    def test_future_schema_is_rejected(self):
        with self.db.transaction() as connection:
            connection.execute("PRAGMA user_version=999")
        with self.assertRaises(RuntimeError):
            Database(self.path)

    def test_constructor_errors_close_connection_before_reraising(self):
        with self.db.transaction() as connection:
            connection.execute("PRAGMA user_version=999")
        cases = ((self.path, {}, RuntimeError),
                 (":memory:", {"timeout": float("nan")}, ValueError))
        for path, options, expected in cases:
            with self.subTest(path=path):
                connection = sqlite3.connect(path, isolation_level=None)
                self.addCleanup(connection.close)
                with patch("rivo_relay.database.sqlite3.connect", return_value=connection):
                    with self.assertRaises(expected):
                        Database(path, **options)
                with self.assertRaisesRegex(sqlite3.ProgrammingError, "closed database"):
                    connection.execute("SELECT 1")


if __name__ == "__main__":
    unittest.main()
