"""Stable API contracts, independent of software release numbers."""
import json
import zipfile

API_VERSIONS = [1]
FEATURES = ['voice.direct', 'voice.stream', 'session.goals', 'attachments', 'todos']


def declaration():
    return {'apiVersions': list(API_VERSIONS), 'features': {'1': list(FEATURES)}}


def validate_app(value):
    versions = value.get('apiVersions') if isinstance(value, dict) else None
    if (not isinstance(versions, list) or not versions
            or any(type(v) is not int or v <= 0 for v in versions)
            or len(set(versions)) != len(versions)):
        raise ValueError('Invalid App API compatibility declaration')
    return {'apiVersions': sorted(versions)}


def apk_compatibility(apk):
    with zipfile.ZipFile(apk) as archive:
        info = archive.getinfo('assets/api-compatibility.json')
        if info.file_size > 4096:
            raise ValueError('App compatibility declaration too large')
        return validate_app(json.loads(archive.read(info)))
