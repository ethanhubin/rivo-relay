# Shared test fixtures

- `native.py`: native RPC, writer backend and execution queue substitutes.
- `files.py` / `media.py`: temporary filesystem access and generated media.
- `http.py` / `gateway.py`: local HTTP and TLS gateway lifecycle fixtures.
- `restart.py`: shared restart CLI module for unit tests and the opt-in launchd drill.

Import helpers from this package, not from `test_*.py`. Fixture mixins contain no
`test_*` methods and do not inherit `TestCase`, so discovery does not collect
additional tests. Keep per-test resource setup and cleanup on the test instance;
do not share live servers, workspaces or native execution state across tests.

Project metadata tests live in `test_project_catalog.py`; notification batching
and disconnect delivery live in `test_notification_queue.py`. Activity probes,
live offsets and catalog invalidation stay with their corresponding features.
