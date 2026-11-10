"""Small generated images and native download fixtures."""
import io
import hashlib
from PIL import Image

def png(size=(32, 16), color='red'):
    output = io.BytesIO()
    Image.new('RGB', size, color).save(output, format='PNG')
    return output.getvalue()


class NativeMedia:
    def __init__(self, raw=None):
        self.raw = raw or png()
        self.calls = []

    def prepare_download(self, path):
        self.calls.append(path)
        return {'size': len(self.raw), 'etag': '"' + hashlib.sha256(self.raw).hexdigest() + '"', 'chunks': iter([self.raw])}
