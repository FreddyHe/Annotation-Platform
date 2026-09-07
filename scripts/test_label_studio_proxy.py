import gzip
from types import SimpleNamespace

from multidict import CIMultiDict

import label_studio_proxy


def _request():
    return SimpleNamespace(headers={"Accept-Encoding": "gzip"})


def test_dynamic_json_gzip_is_never_reused_by_url():
    label_studio_proxy.GZIP_CACHE.clear()
    headers = CIMultiDict({"Content-Type": "application/json"})
    first = b'{"id":216,"payload":"' + (b"a" * 2048) + b'"}'
    second = b'{"id":217,"payload":"' + (b"b" * 2048) + b'"}'

    _, first_compressed = label_studio_proxy._gzip_response(
        "/api/projects", _request(), headers, first, cacheable=False
    )
    _, second_compressed = label_studio_proxy._gzip_response(
        "/api/projects", _request(), headers, second, cacheable=False
    )

    assert gzip.decompress(first_compressed) == first
    assert gzip.decompress(second_compressed) == second
    assert first_compressed != second_compressed
    assert "/api/projects" not in label_studio_proxy.GZIP_CACHE


def test_static_gzip_can_be_reused_by_url():
    label_studio_proxy.GZIP_CACHE.clear()
    headers = CIMultiDict({"Content-Type": "application/javascript"})
    body = b"const app = true;" * 200

    _, compressed = label_studio_proxy._gzip_response(
        "/react-app/main.js", _request(), headers, body, cacheable=True
    )

    assert gzip.decompress(compressed) == body
    assert label_studio_proxy.GZIP_CACHE["/react-app/main.js"] == compressed
