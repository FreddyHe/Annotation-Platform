#!/usr/bin/env python3
"""Small reverse proxy for Label Studio behind AutoDL public port mapping.

AutoDL's public proxy keeps connections open for Django static responses that do
not carry Content-Length. Browsers then wait forever for CSS/JS/font assets and
Label Studio stays blank. This proxy buffers each upstream response and returns
a deterministic Content-Length on port 5001.
"""

from __future__ import annotations

import gzip
import os
import re
from pathlib import Path
from urllib.parse import urljoin

from aiohttp import ClientSession, ClientTimeout, TCPConnector, web
from multidict import CIMultiDict


UPSTREAM = os.environ.get("LABEL_STUDIO_PROXY_UPSTREAM", "http://127.0.0.1:15001").rstrip("/")
HOST = os.environ.get("LABEL_STUDIO_PROXY_HOST", "0.0.0.0")
PORT = int(os.environ.get("LABEL_STUDIO_PROXY_PORT", "5001"))
CLIENT_SESSION_KEY = web.AppKey("label_studio_proxy_client", ClientSession)
STATIC_CACHE: dict[str, tuple[int, str | None, CIMultiDict[str], bytes]] = {}
GZIP_CACHE: dict[str, bytes] = {}
STATIC_PREFIXES = ("/react-app/", "/static/")
STATIC_PATHS = ("/sw.js",)
CUSTOMIZATION_ROOT = Path(__file__).resolve().parent / "label_studio_brand"
CUSTOM_ASSETS = {
    "/annotation-platform/label-studio-theme.css": (
        "text/css; charset=utf-8",
        CUSTOMIZATION_ROOT / "label_studio_theme.css",
    ),
    "/annotation-platform/label-studio-i18n.js": (
        "application/javascript; charset=utf-8",
        CUSTOMIZATION_ROOT / "label_studio_i18n.js",
    ),
    "/annotation-platform/xingmu-annotation-mark.svg": (
        "image/svg+xml; charset=utf-8",
        CUSTOMIZATION_ROOT / "xingmu-annotation-mark.svg",
    ),
}
CUSTOMIZATION_MARKER = b"data-annotation-platform-customization"
PREWARM_PATHS = (
    "/sw.js",
    "/react-app/runtime.js?v=090298",
    "/react-app/vendor.js?v=1",
    "/react-app/main.js?v=090298",
    "/react-app/main.css?v=090298",
    "/react-app/680.js",
    "/react-app/511.js",
    "/react-app/515.js",
    "/react-app/725.js",
    "/react-app/853.js",
    "/react-app/511.css",
    "/react-app/515.css",
    "/react-app/853.css",
)

HOP_BY_HOP_HEADERS = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
}


def _forward_headers(request: web.Request) -> CIMultiDict[str]:
    headers: CIMultiDict[str] = CIMultiDict()
    for name, value in request.headers.items():
        if name.lower() in HOP_BY_HOP_HEADERS:
            continue
        if name.lower() == "host":
            headers[name] = request.host
            continue
        if name.lower() == "accept-encoding":
            continue
        headers[name] = value
    # Keep upstream HTML and assets deterministic. This proxy performs its own
    # compression after it has injected the version-controlled brand layer.
    headers["Accept-Encoding"] = "identity"
    headers["X-Forwarded-Host"] = request.host
    headers["X-Forwarded-Proto"] = request.scheme
    headers["X-Forwarded-For"] = request.remote or ""
    return headers


def _response_headers(
    upstream_headers: CIMultiDict[str],
    body_len: int,
    *,
    strip_set_cookie: bool = False,
) -> CIMultiDict[str]:
    headers: CIMultiDict[str] = CIMultiDict()
    for name, value in upstream_headers.items():
        lower = name.lower()
        if lower in HOP_BY_HOP_HEADERS or lower == "content-length":
            continue
        if strip_set_cookie and lower == "set-cookie":
            continue
        headers.add(name, value)
    headers["Content-Length"] = str(body_len)
    return headers


def _is_static_request(request: web.Request) -> bool:
    return request.method == "GET" and (
        request.path.startswith(STATIC_PREFIXES) or request.path in STATIC_PATHS
    )


def _should_gzip(request: web.Request, headers: CIMultiDict[str], body: bytes) -> bool:
    if "gzip" not in request.headers.get("Accept-Encoding", "").lower() or len(body) < 1024:
        return False
    if headers.get("Content-Encoding"):
        return False
    content_type = headers.get("Content-Type", "").lower()
    return any(item in content_type for item in ("javascript", "text/", "json", "css", "svg"))


def _gzip_response(
    cache_key: str,
    request: web.Request,
    headers: CIMultiDict[str],
    body: bytes,
    *,
    cacheable: bool = False,
) -> tuple[CIMultiDict[str], bytes]:
    if not _should_gzip(request, headers, body):
        return headers, body

    compressed = GZIP_CACHE.get(cache_key) if cacheable else None
    if compressed is None:
        compressed = gzip.compress(body, compresslevel=6)
        if cacheable:
            GZIP_CACHE[cache_key] = compressed

    compressed_headers = CIMultiDict(headers)
    compressed_headers["Content-Encoding"] = "gzip"
    compressed_headers["Vary"] = "Accept-Encoding"
    compressed_headers.popall("ETag", None)
    compressed_headers.popall("Content-MD5", None)
    return compressed_headers, compressed


def _inject_customization(headers: CIMultiDict[str], body: bytes) -> bytes:
    """Attach the Annotation Platform theme and bilingual UI to HTML pages."""
    content_type = headers.get("Content-Type", "").lower()
    if "text/html" not in content_type or CUSTOMIZATION_MARKER in body:
        return body

    # Label Studio ships an orange ``shortcut icon`` before our custom icon.
    # Chromium may keep preferring that first declaration even when a later
    # SVG favicon exists, so remove every upstream favicon declaration before
    # inserting the Xingmu icon as both the standard and legacy relation.
    body = re.sub(
        rb'<link\b(?=[^>]*\brel=["\'][^"\']*\bicon\b[^"\']*["\'])[^>]*>\s*',
        b"",
        body,
        flags=re.IGNORECASE,
    )

    nonce_match = re.search(rb'<script[^>]+nonce=["\']([^"\']+)["\']', body)
    nonce_attr = b""
    if nonce_match:
        nonce_attr = b' nonce="' + nonce_match.group(1) + b'"'

    customization = (
        b'\n<link rel="icon" type="image/svg+xml" sizes="any" '
        b'href="/annotation-platform/xingmu-annotation-mark.svg?v=20260807-2" '
        b'data-annotation-platform-customization="icon">\n'
        b'<link rel="shortcut icon" type="image/svg+xml" '
        b'href="/annotation-platform/xingmu-annotation-mark.svg?v=20260807-2" '
        b'data-annotation-platform-customization="shortcut-icon">\n'
        b'<meta name="theme-color" content="#061a36" data-annotation-platform-customization="color">\n'
        b'\n<link rel="stylesheet" href="/annotation-platform/label-studio-theme.css" '
        b'data-annotation-platform-customization="theme">\n'
        b'<script src="/annotation-platform/label-studio-i18n.js" defer'
        + nonce_attr
        + b' data-annotation-platform-customization="i18n"></script>\n'
    )
    if b"</head>" in body:
        return body.replace(b"</head>", customization + b"</head>", 1)
    return customization + body


def _custom_asset_response(path: str) -> web.Response | None:
    asset = CUSTOM_ASSETS.get(path)
    if asset is None:
        return None
    content_type, asset_path = asset
    if not asset_path.is_file():
        return web.Response(status=404, text="Customization asset not found")
    body = asset_path.read_bytes()
    return web.Response(
        body=body,
        headers={
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
            "Cache-Control": "no-cache, no-store, must-revalidate",
            "X-Content-Type-Options": "nosniff",
        },
    )


def _compatibility_response(request: web.Request) -> web.Response | None:
    """Keep optional upstream features from surfacing as fatal runtime errors.

    Label Studio CE renders two UI hooks that can be absent or restricted in a
    community deployment. Neither endpoint contains project data nor affects
    annotation writes, so an empty successful response is the correct CE
    fallback.
    """
    if request.method == "GET" and request.path.rstrip("/") == "/heidi-tips":
        return web.json_response({"results": []})
    return None


async def proxy(request: web.Request) -> web.Response:
    custom_response = _custom_asset_response(request.path)
    if custom_response is not None:
        return custom_response
    compatibility_response = _compatibility_response(request)
    if compatibility_response is not None:
        return compatibility_response

    upstream_url = urljoin(f"{UPSTREAM}/", request.rel_url.path_qs.lstrip("/"))
    body = await request.read()
    cache_key = request.rel_url.path_qs
    cached = STATIC_CACHE.get(cache_key) if _is_static_request(request) else None

    if cached is not None:
        status, reason, upstream_headers, upstream_body = cached
    else:
        session = request.app[CLIENT_SESSION_KEY]
        async with session.request(
            request.method,
            upstream_url,
            data=body if body else None,
            headers=_forward_headers(request),
            allow_redirects=False,
        ) as upstream_response:
            upstream_body = await upstream_response.read()
            status = upstream_response.status
            reason = upstream_response.reason
            upstream_headers = CIMultiDict(upstream_response.headers)
            if _is_static_request(request) and status == 200:
                # Django's signed-cookie session backend can attach Set-Cookie
                # even to static responses. Caching that header would replay an
                # old anonymous session and randomly log users out while JS/CSS
                # is still loading. Static cache entries must be identity-free.
                cached_headers = CIMultiDict(
                    (name, value)
                    for name, value in upstream_headers.items()
                    if name.lower() != "set-cookie"
                )
                STATIC_CACHE[cache_key] = (status, reason, cached_headers, upstream_body)
                upstream_headers = cached_headers

    if (
        request.method == "GET"
        and re.fullmatch(r"/api/projects/\d+/label-stream-history/?", request.path)
        and status in {401, 403, 404}
    ):
        status = 200
        reason = "OK"
        upstream_headers = CIMultiDict({"Content-Type": "application/json; charset=utf-8"})
        upstream_body = b"[]"

    if status == 200:
        upstream_body = _inject_customization(upstream_headers, upstream_body)

    upstream_headers, upstream_body = _gzip_response(
        cache_key,
        request,
        upstream_headers,
        upstream_body,
        cacheable=_is_static_request(request),
    )
    headers = _response_headers(
        upstream_headers,
        len(upstream_body),
        strip_set_cookie=_is_static_request(request),
    )
    return web.Response(
        body=upstream_body,
        status=status,
        reason=reason,
        headers=headers,
    )


async def prewarm_static_assets(session: ClientSession) -> None:
    warmed = 0
    for path in PREWARM_PATHS:
        try:
            upstream_url = urljoin(f"{UPSTREAM}/", path.lstrip("/"))
            async with session.get(upstream_url, headers={"Accept-Encoding": "identity"}) as response:
                if response.status != 200:
                    continue
                body = await response.read()
                headers = CIMultiDict(
                    (name, value)
                    for name, value in response.headers.items()
                    if name.lower() != "set-cookie"
                )
                STATIC_CACHE[path] = (response.status, response.reason, headers, body)
                content_type = headers.get("Content-Type", "").lower()
                if len(body) >= 1024 and any(
                    item in content_type for item in ("javascript", "text/", "json", "css", "svg")
                ):
                    GZIP_CACHE[path] = gzip.compress(body, compresslevel=6)
                warmed += 1
        except Exception as exc:
            print(f"Label Studio static prewarm failed for {path}: {exc}", flush=True)
    print(f"Label Studio static prewarm complete: {warmed}/{len(PREWARM_PATHS)}", flush=True)


async def client_session_context(app: web.Application):
    timeout = ClientTimeout(total=None, sock_connect=30, sock_read=300)
    connector = TCPConnector(force_close=False, limit=100)
    app[CLIENT_SESSION_KEY] = ClientSession(
        timeout=timeout,
        connector=connector,
        auto_decompress=False,
    )
    await prewarm_static_assets(app[CLIENT_SESSION_KEY])
    yield
    await app[CLIENT_SESSION_KEY].close()


def main() -> None:
    app = web.Application(client_max_size=1024**3)
    app.cleanup_ctx.append(client_session_context)
    app.router.add_route("*", "/{tail:.*}", proxy)
    web.run_app(app, host=HOST, port=PORT, access_log=None)


if __name__ == "__main__":
    main()
