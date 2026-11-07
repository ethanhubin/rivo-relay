"""Bounded dynamic model catalog with QiGuang's last-known-good fallback.

No built-in model allowlist is used. A stale catalog is display information;
the connected Codex runtime still validates every settings/write operation.
"""
import copy
import threading
import time

from .connection import CodexError


class ModelCatalog:
    def __init__(self, loader, fingerprint, *, clock=time.time, ttl=300):
        self.loader, self.fingerprint, self.clock, self.ttl = loader, fingerprint, clock, ttl
        self._lock = threading.Lock()
        self._good = None
        self.observations = None

    @staticmethod
    def _validated(models):
        if not isinstance(models, list) or not models or len(models) > 500:
            raise CodexError('模型目录暂不可用', code='native_protocol')
        seen, result = set(), []
        for model in models:
            if not isinstance(model, dict):
                raise CodexError('模型目录格式无效', code='native_protocol')
            identity = model.get('id') or model.get('model')
            if not isinstance(identity, str) or not identity.strip() or identity in seen:
                raise CodexError('模型目录身份无效', code='native_protocol')
            seen.add(identity)
            result.append(copy.deepcopy(model))
        return result

    def configure(self, observations):
        self.observations = observations
        try:
            stored = observations.get('__relay_model_catalog__').get(('models', ''))
            if isinstance(stored, dict):
                stored['models'] = self._validated(stored.get('models'))
                if isinstance(stored.get('updatedAt'), (int, float)):
                    self._good = stored
        except (CodexError, TypeError, ValueError):
            pass

    def public(self, *, refresh=False):
        with self._lock:
            now, fingerprint = self.clock(), self.fingerprint()
            if (not refresh and self._good and self._good.get('fingerprint') == fingerprint
                    and 0 <= now - self._good['updatedAt'] < self.ttl):
                return self._public(self._good, stale=False, source='cache')
            try:
                models = self._validated(self.loader())
            except Exception:
                if self._good:
                    return self._public(self._good, stale=True, source='last_known_good')
                raise
            good = {'models': models, 'updatedAt': now, 'fingerprint': self.fingerprint()}
            self._good = good
            if self.observations:
                try:
                    self.observations.put('__relay_model_catalog__', 'models', '', good)
                except Exception:
                    # Display-cache persistence must not discard a good native read.
                    pass
            return self._public(good, stale=False, source='native')

    @staticmethod
    def _public(value, *, stale, source):
        return {'models': copy.deepcopy(value['models']),
                'catalog': {'stale': stale, 'source': source, 'updatedAt': value['updatedAt']}}
