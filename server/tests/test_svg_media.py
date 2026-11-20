"""Static SVG thumbnails retain original bytes and never load referenced paths."""
import base64
import io
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from PIL import Image

from relay_test_support.media import png
from rivo_relay.codex.connection import CodexError
from rivo_relay.svg_media import is_svg, thumbnail_png


def svg(body='', *, attributes='viewBox="0 0 400 200"'):
    return ('<svg xmlns="http://www.w3.org/2000/svg" ' + attributes + '>' + body + '</svg>').encode()


class SvgMediaTests(unittest.TestCase):
    def test_identification_is_xml_root_and_bounded_without_dtd_entities(self):
        self.assertTrue(is_svg(svg('<rect width="400" height="200" fill="red"/>')))
        self.assertTrue(is_svg(b'<?xml version="1.0"?><!-- prefix -->' + svg()))
        for data in (b'<html><svg/></html>', b'<svg', b'<SVG/>',
                     b'<!DOCTYPE svg [<!ENTITY x "secret">]><svg>&x;</svg>',
                     b'<!DOCTYPE svg SYSTEM "file:///private/secret"><svg/>',
                     b'not XML', svg('<g>' * 70 + '</g>' * 70)):
            with self.subTest(data=data[:60]): self.assertFalse(is_svg(data))
        with patch('rivo_relay.svg_media.MAX_SVG_BYTES', 20):
            self.assertFalse(is_svg(svg()))
        with patch('rivo_relay.svg_media.MAX_ELEMENTS', 2):
            self.assertFalse(is_svg(svg('<g/><g/>')))

    def test_external_resources_and_active_content_never_reach_renderer(self):
        calls = []
        def render(**options):
            calls.append(options)
            return png((options['width'], options['height']))
        body = ('<script>alert(1)</script><foreignObject><div>HTML</div></foreignObject>'
                '<animate attributeName="href" values="file:///private/secret"/>'
                '<image href="/private/secret.png"/><use href="https://example.org/icon.svg#id"/>'
                '<rect onclick="alert(1)" fill="url(file:///private/paint.svg)"/>'
                '<style>@import "https://example.org/style.css"; rect {fill:red}</style>'
                '<g style="filter:url(//example.org/filter.svg#f)"/>'
                '<g style="fill:u\\72l(file:///private/secret)"/>')
        raw = svg(body)
        with patch.dict(sys.modules, {'resvg_py': types.SimpleNamespace(svg_to_bytes=render)}):
            thumbnail_png(raw)
        options, = calls
        self.assertEqual({'svg_string', 'width', 'height', 'dpi'}, set(options))
        for forbidden in ('script', 'foreignObject', 'onclick', 'animate', '/private/', 'example.org', '@import'):
            self.assertNotIn(forbidden, options['svg_string'])
        self.assertEqual((512, 256), (options['width'], options['height']))
        self.assertEqual(svg(body), raw)  # Original download remains unmodified.

    def test_internal_fragment_and_valid_embedded_raster_remain_renderable(self):
        encoded = base64.b64encode(png()).decode()
        nested_svg = base64.b64encode(svg('<image href="/private/secret.png"/>')).decode()
        source = svg('<defs><linearGradient id="g"><stop stop-color="red"/></linearGradient></defs>'
            '<rect fill="url(#g)"/><use href="#g"/>'
            f'<image href="data:image/png;base64,{encoded}"/>'
            f'<image href="data:image/svg+xml;base64,{nested_svg}"/>')
        calls = []
        def render(**options):
            calls.append(options)
            return png()
        with patch.dict(sys.modules, {'resvg_py': types.SimpleNamespace(svg_to_bytes=render)}):
            thumbnail_png(source)
        body = calls[0]['svg_string']
        self.assertIn('url(#g)', body)
        self.assertIn('href="#g"', body)
        self.assertIn('data:image/png;base64,' + encoded, body)
        self.assertNotIn(nested_svg, body)

    def test_malformed_renderer_output_and_oversized_target_are_rejected(self):
        for output in (b'bad output', png((513, 1))):
            with patch.dict(sys.modules, {'resvg_py': types.SimpleNamespace(svg_to_bytes=lambda **_: output)}):
                with self.assertRaises(CodexError): thumbnail_png(svg())
        with self.assertRaises(CodexError): thumbnail_png(svg(), max_size=513)

    def test_real_renderer_preserves_aspect_and_static_vector_pixels(self):
        output = thumbnail_png(svg('<rect width="400" height="200" fill="#ff0000"/>'))
        with Image.open(io.BytesIO(output)) as image:
            self.assertEqual('PNG', image.format)
            self.assertEqual((512, 256), image.size)
            self.assertEqual((255, 0, 0, 255), image.convert('RGBA').getpixel((256, 128)))
        output = thumbnail_png(svg('<rect width="100" height="100" fill="red"/>',
                                   attributes='width="4in" height="1in" viewBox="0 0 100 100"'))
        with Image.open(io.BytesIO(output)) as image:
            self.assertEqual((512, 128), image.size)

    def test_real_renderer_cannot_read_absolute_or_relative_host_image_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = Path(directory) / 'secret.png'
            secret.write_bytes(png(color='red'))
            for path in (str(secret), secret.as_uri(), 'secret.png'):
                with self.subTest(path=path):
                    output = thumbnail_png(svg(f'<image width="400" height="200" href="{path}"/>'))
                    with Image.open(io.BytesIO(output)) as image:
                        self.assertEqual((0, 0), image.convert('RGBA').getchannel('A').getextrema())
