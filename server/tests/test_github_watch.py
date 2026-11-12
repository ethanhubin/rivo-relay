import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from rivo_relay.github_watch import GithubWatchError, GithubWatches
from relay_test_support.http import RelayHTTPFixture


def pull(number, *, draft=False):
    return {"number": number, "draft": draft, "title": f"PR {number}",
            "html_url": f"https://github.com/example/repo/pull/{number}"}


class GithubWatchTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.now = 1000.0
        self.pulls = [pull(1)]
        self.calls = []
        def transport(url, *, token="", form=None):
            self.calls.append((url, token, form))
            if url.endswith("/login/device/code"):
                return {"device_code": "secret", "user_code": "TEST-CODE",
                        "verification_uri": "https://github.com/login/device", "interval": 5}, {}
            if url.endswith("/login/oauth/access_token"):
                if form["grant_type"] == "refresh_token":
                    return {"access_token": "renewed", "refresh_token": "refresh2", "expires_in": 28800}, {}
                return {"access_token": "access", "refresh_token": "refresh", "expires_in": 28800}, {}
            if url.endswith("/user"):
                return {"login": "reviewer"}, {}
            return list(self.pulls), {}
        self.service = GithubWatches(Path(self.directory.name) / "github.sqlite3",
                                     clock=lambda: self.now, transport=transport)

    def tearDown(self):
        self.service.close()
        self.directory.cleanup()

    def test_first_scan_is_baseline_then_new_and_ready_prs_alert(self):
        live_signals = []
        self.service.on_alert = lambda owner: live_signals.append(owner)
        watch = self.service.create("phone", "example/repo", "main")
        self.service.scan(watch["id"])
        self.assertEqual([], self.service.alerts("phone", 0)["alerts"])
        self.assertEqual([], live_signals)
        self.assertEqual([1], [item["number"] for item in self.service.list("phone")["watches"][0]["pending"]])
        self.pulls = [pull(1), pull(2, draft=True), pull(3)]
        self.service.scan(watch["id"])
        self.assertEqual([3], [item["number"] for item in self.service.alerts("phone", 0)["alerts"]])
        self.pulls[1] = pull(2)
        self.service.scan(watch["id"])
        alerts = self.service.alerts("phone", 0)["alerts"]
        self.assertEqual([3, 2], [item["number"] for item in alerts])
        self.assertEqual(["phone", "phone"], live_signals)
        self.assertEqual([], self.service.alerts("phone", alerts[-1]["id"])["alerts"])
        self.service.scan(watch["id"])
        self.assertEqual(2, len(self.service.alerts("phone", 0)["alerts"]))

    def test_reopen_and_connection_isolation(self):
        watch = self.service.create("phone", "example/repo", "main")
        self.service.scan(watch["id"])
        self.pulls = []
        self.service.scan(watch["id"])
        self.pulls = [pull(1)]
        self.service.scan(watch["id"])
        self.assertEqual(1, len(self.service.alerts("phone", 0)["alerts"]))
        self.assertEqual([], self.service.alerts("other", 0)["alerts"])
        self.assertEqual([], self.service.list("other")["watches"])
        with self.assertRaises(GithubWatchError):
            self.service.delete("other", watch["id"])

    def test_device_authorization_and_refresh_never_expose_token(self):
        with patch.dict(os.environ, {"RIVO_GITHUB_CLIENT_ID": "client"}):
            flow = self.service.auth_start("phone")
            result = self.service.auth_poll("phone", flow["flowId"])
            self.assertEqual({"pending": False, "login": "reviewer"}, result)
            self.assertNotIn("access", str(result))
            self.assertEqual("reviewer", self.service.auth_status("phone")["login"])
            watch = self.service.create("phone", "example/repo", "main")
            self.service.scan(watch["id"])
            self.assertEqual("access", self.calls[-1][1])
            self.now += 28801
            self.service.scan(watch["id"])
            self.assertEqual("renewed", self.calls[-1][1])
            self.assertNotIn("access_token", str(self.service.list("phone")))

    def test_foreground_scan_requires_authorization_and_is_rate_limited(self):
        self.assertEqual({"scheduled": False, "requiresGithubAuthorization": True}, self.service.request_fast_scan("phone"))
        with patch.dict(os.environ, {"RIVO_GITHUB_CLIENT_ID": "client"}):
            flow = self.service.auth_start("phone")
            self.service.auth_poll("phone", flow["flowId"])
        self.assertEqual({"scheduled": True}, self.service.request_fast_scan("phone"))
        self.assertGreater(self.service.request_fast_scan("phone")["retryAfter"], 0)
        self.now += 60
        self.assertEqual({"scheduled": True}, self.service.request_fast_scan("phone"))

    def test_regular_scan_runs_every_five_minutes(self):
        self.service.create("phone", "example/repo", "main")
        self.service.start()
        def pull_calls():
            return sum("/pulls?" in url for url, _, _ in self.calls)
        deadline = time.monotonic() + 1
        while pull_calls() < 1 and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertEqual(1, pull_calls())
        self.now += 299
        self.service.wake.set()
        time.sleep(.02)
        self.assertEqual(1, pull_calls())
        self.now += 1
        self.service.wake.set()
        deadline = time.monotonic() + 1
        while pull_calls() < 2 and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertEqual(2, pull_calls())

    def test_node_scans_while_foreground_only_with_a_configured_authorized_watch(self):
        def pull_calls():
            return sum("/pulls?" in url for url, _, _ in self.calls)
        def wait_for(count):
            deadline = time.monotonic() + 1
            while pull_calls() < count and time.monotonic() < deadline:
                time.sleep(.005)
            self.assertEqual(count, pull_calls())

        self.service.start()
        self.service.set_foreground("phone", True, "session-a")
        self.service.stream_connected("phone")
        self.service.wake.set()
        time.sleep(.02)
        self.assertEqual(0, pull_calls())
        self.service.create("phone", "example/repo", "main")
        wait_for(1)
        self.now += 30
        self.service.wake.set()
        time.sleep(.02)
        self.assertEqual(1, pull_calls())
        with patch.dict(os.environ, {"RIVO_GITHUB_CLIENT_ID": "client"}):
            flow = self.service.auth_start("phone")
            self.service.auth_poll("phone", flow["flowId"])
        wait_for(2)
        self.now += 30
        self.service.wake.set()
        wait_for(3)
        self.service.stream_disconnected("phone")
        self.now += 30
        self.service.wake.set()
        time.sleep(.02)
        self.assertEqual(3, pull_calls())
        self.service.stream_connected("phone")
        wait_for(4)
        self.service.set_foreground("phone", True, "session-b")
        self.assertEqual({"active": True}, self.service.set_foreground("phone", False, "session-a"))
        self.assertEqual({"active": False}, self.service.set_foreground("phone", False, "session-b"))
        self.now += 30
        self.service.wake.set()
        time.sleep(.02)
        self.assertEqual(4, pull_calls())

    def test_invalid_watch_and_failed_scan_leave_baseline_uninitialized(self):
        with self.assertRaises(GithubWatchError):
            self.service.create("phone", "https://github.com/example/repo", "main")
        watch = self.service.create("phone", "example/repo", "main")
        self.pulls = [{"number": 1}]
        self.service.scan(watch["id"])
        row = self.service.list("phone")["watches"][0]
        self.assertFalse(row["initialized"])
        self.assertEqual("github_invalid_response", row["error"])

    def test_revoked_connection_forgets_authorization_watches_and_alerts(self):
        with patch.dict(os.environ, {"RIVO_GITHUB_CLIENT_ID": "client"}):
            flow = self.service.auth_start("phone")
            self.service.auth_poll("phone", flow["flowId"])
        watch = self.service.create("phone", "example/repo", "main")
        self.service.scan(watch["id"])
        self.pulls.append(pull(2))
        self.service.scan(watch["id"])
        self.assertEqual(1, len(self.service.alerts("phone", 0)["alerts"]))
        self.service.purge_connection("phone")
        self.assertFalse(self.service.auth_status("phone")["connected"])
        self.assertEqual([], self.service.list("phone")["watches"])
        self.assertEqual([], self.service.alerts("phone", 0)["alerts"])

    def test_alerts_survive_node_restart_until_app_reads_them(self):
        watch = self.service.create("phone", "example/repo", "main")
        self.service.scan(watch["id"])
        self.pulls.append(pull(2))
        self.service.scan(watch["id"])
        self.service.close()
        self.service = GithubWatches(Path(self.directory.name) / "github.sqlite3", clock=lambda: self.now)
        alerts = self.service.alerts("phone", 0)["alerts"]
        self.assertEqual([2], [item["number"] for item in alerts])
        self.assertEqual([], self.service.alerts("phone", alerts[0]["id"])["alerts"])


class GithubWatchRouteTests(RelayHTTPFixture, unittest.TestCase):
    def test_authenticated_watch_routes_and_durable_alerts(self):
        self.assertEqual(401, self.request("GET", "/v1/github/watches")[0])
        status, result, _ = self.request("POST", "/v1/github/watches",
            {"repository": "example/repo", "branch": "main"}, token=self.token)
        self.assertEqual(200, status)
        watch_id = result["watch"]["id"]
        status, result, _ = self.request("GET", "/v1/github/watches", token=self.token)
        self.assertEqual((200, 1), (status, len(result["watches"])))
        self.assertNotIn("access_token", str(result))
        self.assertEqual(200, self.request("GET", "/v1/github/alerts?after=0", token=self.token)[0])
        self.assertEqual({"scheduled": False, "requiresGithubAuthorization": True},
            self.request("POST", "/v1/github/scan", {}, token=self.token)[1])
        self.assertEqual(401, self.request("POST", "/v1/github/foreground",
            {"active": True, "sessionId": "session"})[0])
        self.assertEqual(400, self.request("POST", "/v1/github/foreground",
            {"active": "yes", "sessionId": "session"}, token=self.token)[0])
        self.assertEqual({"active": True}, self.request("POST", "/v1/github/foreground",
            {"active": True, "sessionId": "session"}, token=self.token)[1])
        self.assertEqual({"active": False}, self.request("POST", "/v1/github/foreground",
            {"active": False, "sessionId": "session"}, token=self.token)[1])
        self.assertEqual(400, self.request("GET", "/v1/github/alerts?after=-1", token=self.token)[0])
        self.assertEqual(200, self.request("DELETE", f"/v1/github/watches/{watch_id}", token=self.token)[0])
        self.assertEqual(404, self.request("DELETE", f"/v1/github/watches/{watch_id}", token=self.token)[0])


if __name__ == "__main__":
    unittest.main()
