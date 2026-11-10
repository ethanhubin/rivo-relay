"""Load the restart CLI for unit tests and opt-in launchd drills."""
import importlib.util
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))

spec = importlib.util.spec_from_file_location('restart', Path(__file__).resolve().parents[3] / 'scripts/restart-service.py')
restart_service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restart_service)
