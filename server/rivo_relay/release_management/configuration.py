"""Machine-local release settings. Never store credentials in this file."""
import json
import os
from pathlib import Path
import re
import sys


def settings(root):
    path = Path(os.environ.get('RIVO_RELEASE_CONFIG', str(Path(root) / 'workspace/release-config.json')))
    value = json.loads(path.read_text()) if path.exists() else {}
    if not isinstance(value, dict):
        raise ValueError('release-config.json 必须是 JSON 对象')
    allowed = {'python', 'javaHome', 'androidHome', 'gatewayHost', 'gatewayIdentity', 'asrHost',
               'mode', 'signingStore', 'gatewayUrl', 'gatewayPin', 'compatibility'}
    if set(value) - allowed:
        raise ValueError('未知发布配置项：' + ', '.join(sorted(set(value) - allowed)))
    for key, item in value.items():
        if key != 'compatibility' and (not isinstance(item, str) or not item.strip()):
            raise ValueError('发布配置必须是非空字符串：' + key)
    for key in ('gatewayHost', 'asrHost'):
        if key in value:
            host(value[key])
    if value.get('mode', 'legacy') not in ('legacy', 'manager'):
        raise ValueError('mode 必须为 manager 或 legacy')
    if value.get('mode') == 'manager':
        if not re.fullmatch('[a-f0-9]{64}', value.get('gatewayPin', '')):
            raise ValueError('管理节点需要已信任的 VPS 证书 SHA-256')
        if not value.get('gatewayUrl', '').startswith('https://'):
            raise ValueError('管理节点需要 HTTPS 更新中心')
    if 'compatibility' in value:
        item = value['compatibility']
        if (not isinstance(item, dict) or set(item) != {'host', 'root', 'python'}
                or not all(isinstance(v, str) and v for v in item.values())):
            raise ValueError('compatibility 需要 host/root/python')
        host(item['host'])
        if not all(Path(item[k]).is_absolute() for k in ('root', 'python')):
            raise ValueError('compatibility 路径必须为绝对路径')
    return value


def host(value):
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.@:-]*', value):
        raise ValueError('无效 SSH 主机或别名')
    return value


def path(root, value):
    result = Path(value).expanduser()
    return str(result if result.is_absolute() else Path(root) / result)


def python(root):
    config = settings(root)
    if config.get('python'):
        return path(root, config['python'])
    local = Path(root) / '.venv/bin/python'
    return str(local) if local.is_file() else sys.executable


def environment(root):
    config = settings(root)
    env = dict(os.environ)
    for key, variable in [('javaHome', 'JAVA_HOME'), ('androidHome', 'ANDROID_HOME')]:
        if config.get(key):
            env[variable] = path(root, config[key])
    env.setdefault('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))
    studio = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
    if 'JAVA_HOME' not in env and studio.is_dir():
        env['JAVA_HOME'] = str(studio)
    env['PYTHONPATH'] = os.pathsep.join([str(Path(root) / 'server'), str(Path(root) / 'server/tests')])
    if config.get('signingStore'):
        env['RIVO_RELEASE_KEYSTORE'] = path(root, config['signingStore'])
    return env


def gateway_args(root):
    hostname, identity = gateway_connection(root)
    args = ['--host', hostname]
    if identity:
        args += ['--identity', identity]
    cfg = settings(root)
    if cfg.get('mode') == 'manager':
        args += ['--gateway-url', cfg['gatewayUrl'], '--gateway-pin', cfg['gatewayPin']]
    return args


def gateway_connection(root):
    config = settings(root)
    identity = config.get('gatewayIdentity')
    if identity:
        identity = path(root, identity)
    elif not config.get('gatewayHost'):
        # Compatibility with the original publisher; other machines use SSH config.
        previous = Path.home() / 'Documents/QiGuangBot.pem'
        identity = str(previous) if previous.is_file() else None
    return config.get('gatewayHost', 'root@134.175.156.219'), identity
