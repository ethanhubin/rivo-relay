"""Runtime selection shared by node launch, managed releases and installer."""
import os
from pathlib import Path


_CHOICES = {
    'codex_runtime': ('auto', 'desktop', 'app_server', 'stdio'),
    'runtime': ('codex', 'dsh', 'antigravity'),
    'antigravity_mode': ('cli', 'desktop'),
}
_OPTIONS = ('codex_runtime', 'codex_executable', 'codex_home', 'native_socket', 'runtime',
            'desktop_bridge_dir', 'dsh_bridge_socket', 'dsh_admin_socket', 'antigravity_mode',
            'antigravity_executable', 'antigravity_profile_dir')
_PROJECTS = 'antigravity_project'
_STORED_OPTIONS = frozenset((*_OPTIONS, _PROJECTS))


def add_runtime_arguments(parser):
    for option in _OPTIONS:
        if option in _CHOICES:
            parser.add_argument('--' + option.replace('_', '-'), choices=_CHOICES[option])
        else:
            parser.add_argument('--' + option.replace('_', '-'), type=Path)
    parser.add_argument('--antigravity-project', type=Path, action='append')


def runtime_settings(args, stored):
    settings = {}
    for option in _OPTIONS:
        override = getattr(args, option, None)
        value = str(override) if override is not None else stored.get(option)
        if value is None:
            continue
        if not isinstance(value, str) or not value or '\0' in value or option in _CHOICES and value not in _CHOICES[option]:
            raise ValueError(f'Invalid runtime setting: {option}')
        settings[option] = value
    override = getattr(args, _PROJECTS, None)
    projects = [str(path) for path in override] if override is not None else stored.get(_PROJECTS)
    if projects is not None:
        if not isinstance(projects, list) or any(not isinstance(path, str) or not path or '\0' in path for path in projects):
            raise ValueError('Invalid Antigravity project list')
        settings[_PROJECTS] = projects
    runtime = settings.get('runtime', 'codex')
    if runtime != 'codex' and set(settings) & {'codex_runtime', 'codex_executable', 'codex_home', 'native_socket', 'desktop_bridge_dir'}:
        raise ValueError('Codex runtime options require the Codex node type')
    if runtime != 'dsh' and set(settings) & {'dsh_bridge_socket', 'dsh_admin_socket'}:
        raise ValueError('DSH runtime options require the DSH node type')
    if runtime != 'antigravity' and any(key.startswith('antigravity_') for key in settings):
        raise ValueError('Antigravity runtime options require the Antigravity node type')
    if runtime == 'antigravity':
        settings.setdefault('antigravity_mode', 'cli')
    return settings


def runtime_arguments(settings):
    arguments = []
    for option in _OPTIONS:
        if option in settings:
            arguments.extend(('--' + option.replace('_', '-'), settings[option]))
    for project in settings.get(_PROJECTS, ()):
        arguments.extend(('--antigravity-project', project))
    return arguments


def replace_runtime_settings(stored, settings):
    return ({key: value for key, value in stored.items() if key not in _STORED_OPTIONS} |
            {key: value for key, value in settings.items() if key in _STORED_OPTIONS})


def relay_config(workspace, local_port, settings, **options):
    from ..config import Config
    runtime = settings.get('runtime', 'codex')
    parameters = {key: Path(value) for key, value in settings.items() if key in _OPTIONS
                  and key not in _CHOICES and key != 'native_socket'}
    if runtime == 'antigravity':
        parameters.update(antigravity_mode=settings.get('antigravity_mode', 'cli'),
                          antigravity_projects=tuple(Path(project) for project in settings.get(_PROJECTS, ())))
    return Config(workspace=workspace, port=local_port, runtime=runtime,
                  codex_runtime=settings.get('codex_runtime') or (os.environ.get('RIVO_CODEX_RUNTIME', 'auto')
                                                                if runtime != 'antigravity' else 'auto'),
                  **parameters, **options)


def runtime_matches(health, runtime, identity, *, allow_legacy):
    if allow_legacy and 'runtime' not in health and 'runtimeIdentity' not in health:
        return runtime == 'codex'
    return health.get('runtime') == runtime and health.get('runtimeIdentity') == identity
