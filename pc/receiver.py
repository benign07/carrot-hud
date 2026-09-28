"""Authenticated phone archive receiver. Bind only to your Tailscale address.

python receiver.py --init PRIVATE_DIRECTORY --bind 100.x.y.z --phone 100.a.b.c --out RECORD_DIRECTORY
python receiver.py --config PRIVATE_DIRECTORY/receiver-config.json
"""
import argparse
import asyncio
import gzip
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time
import uuid

from aiohttp import web

MAX_CHUNK = 4 * 1024**2
MAX_RAW = 16 * 1024**2
ID = re.compile(r'^[a-f0-9]{32}$')
HASH = re.compile(r'^[a-f0-9]{64}$')
TAILNET = ipaddress.ip_network('100.64.0.0/10')


def atomic_json(path, value):
    temp = path.with_suffix('.tmp')
    with temp.open('w', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate_chunk(path):
    total = 0
    with gzip.open(path, 'rb') as stream:
        first = True
        while True:
            line = stream.readline(MAX_RAW + 1)
            if not line:
                break
            total += len(line)
            if total > MAX_RAW:
                raise ValueError('Uncompressed chunk too large')
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError('Invalid record')
            if first and (row.get('kind') != 'header' or row.get('schema') != 1):
                raise ValueError('Unsupported record schema')
            if row.get('kind') not in ('header', 'sample', 'can_sample', 'event'):
                raise ValueError('Unsupported record type')
            first = False
    if first:
        raise ValueError('Empty record')


class Receiver:
    def __init__(self, config):
        self.config = config
        self.root = Path(config['out']).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = asyncio.Lock()
        self.changed = asyncio.Event()
        self.analysis_task = None

    def authorize(self, request):
        supplied = request.headers.get('Authorization', '')
        if not hmac.compare_digest(supplied, 'Bearer ' + self.config['token']):
            raise web.HTTPUnauthorized()
        if request.headers.get('X-Receiver-Id') != self.config['receiver_id']:
            raise web.HTTPForbidden()
        if request.remote not in self.config['allowed_peers']:
            raise web.HTTPForbidden()

    async def health(self, request):
        self.authorize(request)
        return web.json_response({'receiver_id': self.config['receiver_id'], 'ready': True})

    async def upload(self, request):
        self.authorize(request)
        ident = request.match_info['ident']
        expected = request.headers.get('X-Chunk-SHA256', '')
        reason = request.headers.get('X-Chunk-Reason', 'phone_upload')
        size = request.content_length
        if not ID.fullmatch(ident) or not HASH.fullmatch(expected) or type(size) is not int or not 0 < size <= MAX_CHUNK:
            raise web.HTTPBadRequest(text='Invalid manifest')
        if request.headers.get('Content-Encoding') or request.headers.get('Transfer-Encoding'):
            raise web.HTTPBadRequest(text='Fixed length gzip file required')
        if not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', reason):
            raise web.HTTPBadRequest(text='Invalid close reason')
        async with self.lock:
            target = self.root / (ident + '.jsonl.gz')
            manifest = self.root / (ident + '.manifest.json')
            # Conflicting immutable IDs cannot overwrite a valid archived record.
            if manifest.exists():
                old = json.loads(manifest.read_text(encoding='utf-8'))
                if old['sha256'] != expected or old['bytes'] != size:
                    raise web.HTTPConflict(text='Immutable ID conflict')
            if target.exists() and target.stat().st_size == size and await asyncio.to_thread(digest, target) == expected:
                await request.read()
                await asyncio.to_thread(validate_chunk, target)
                self.finish(manifest, ident, size, expected, reason)
                self.changed.set()
                return self.receipt(ident, size, expected)
            usage = sum(p.stat().st_size for p in self.root.iterdir() if p.is_file())
            if usage + size > self.config.get('quota_bytes', 20 * 1024**3) or shutil.disk_usage(self.root).free < self.config.get('reserve_bytes', 2 * 1024**3) + size:
                raise web.HTTPInsufficientStorage(text='Existing records preserved')
            partial = self.root / (ident + '.' + uuid.uuid4().hex + '.upload')
            try:
                received = 0
                calculated = hashlib.sha256()
                async with asyncio.timeout(45):
                    with partial.open('xb') as stream:
                        async for data in request.content.iter_chunked(64 * 1024):
                            received += len(data)
                            if received > size:
                                raise web.HTTPBadRequest(text='Length mismatch')
                            stream.write(data)
                            calculated.update(data)
                        stream.flush()
                        os.fsync(stream.fileno())
                if received != size or calculated.hexdigest() != expected:
                    raise web.HTTPBadRequest(text='Hash or length mismatch')
                try:
                    await asyncio.to_thread(validate_chunk, partial)
                except (ValueError, OSError, EOFError):
                    raise web.HTTPBadRequest(text='Invalid diagnostic chunk') from None
                os.replace(partial, target)
                self.finish(manifest, ident, size, expected, reason)
                self.changed.set()
                return self.receipt(ident, size, expected, 201)
            finally:
                partial.unlink(missing_ok=True)

    def finish(self, manifest, ident, size, expected, reason):
        # Commit both file and manifest before acknowledging to the phone.
        atomic_json(manifest, {'id': ident, 'bytes': size, 'sha256': expected, 'reason': reason, 'received_via': 'phone_upload', 'received_at': time.time()})

    def receipt(self, ident, size, expected, status=200):
        return web.json_response({'stored': True, 'receiver_id': self.config['receiver_id'], 'id': ident,
                                  'bytes': size, 'sha256': expected}, status=status)

    async def analysis(self):
        while True:
            await self.changed.wait()
            await asyncio.sleep(20)
            self.changed.clear()
            script = self.config.get('analysis_script')
            if not script:
                continue
            def run():
                import sys
                try:
                    result = subprocess.run([sys.executable, '-X', 'utf8', script, '--out', str(self.root), '--analyze-only'],
                                            capture_output=True, timeout=120)
                    return {'ok': result.returncode == 0, 'checked_at': time.time()}
                except (OSError, subprocess.TimeoutExpired):
                    return {'ok': False, 'checked_at': time.time()}
            atomic_json(self.root / 'receiver-analysis-status.json', await asyncio.to_thread(run))

    async def startup(self, app):
        self.analysis_task = asyncio.create_task(self.analysis())

    async def cleanup(self, app):
        self.analysis_task.cancel()
        try:
            await self.analysis_task
        except asyncio.CancelledError:
            pass


def create_app(config):
    receiver = Receiver(config)
    app = web.Application(client_max_size=MAX_CHUNK)
    app.router.add_get('/v1/health', receiver.health)
    app.router.add_put('/v1/chunks/{ident}', receiver.upload)
    app.on_startup.append(receiver.startup)
    app.on_cleanup.append(receiver.cleanup)
    return app


def validate_config(config):
    if ipaddress.ip_address(config['bind']) not in TAILNET:
        raise ValueError('Bind must be a Tailscale IPv4 address')
    if not config['allowed_peers'] or any(ipaddress.ip_address(x) not in TAILNET for x in config['allowed_peers']):
        raise ValueError('Allow only named Tailscale peers')
    if not ID.fullmatch(config['receiver_id']) or not re.fullmatch(r'[A-Za-z0-9_-]{43,128}', config['token']):
        raise ValueError('Invalid receiver identity')
    if type(config['port']) is not int or not 1 <= config['port'] <= 65535:
        raise ValueError('Invalid port')


async def serve(config):
    runner = web.AppRunner(create_app(config), access_log=None)
    await runner.setup()
    try:
        site = web.TCPSite(runner, config['bind'], config['port'])
        waiting = False
        while True:
            try:
                await site.start()
                break
            except OSError:
                if not waiting:
                    print('Waiting for Tailscale interface/receiver port', flush=True)
                    waiting = True
                await asyncio.sleep(15)
        print('Carrot receiver ready on Tailscale; credentials and record contents are not logged', flush=True)
        await asyncio.Event().wait()
    finally:
        await runner.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--init', type=Path)
    parser.add_argument('--bind')
    parser.add_argument('--phone')
    parser.add_argument('--port', type=int, default=7041)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--analysis-script', type=Path)
    args = parser.parse_args()
    if args.init:
        if not args.out or not args.bind or not args.phone:
            parser.error('--init requires --bind, --phone, --out')
        config = {'bind': args.bind, 'port': args.port, 'allowed_peers': [args.phone],
                  'out': str(args.out.resolve()), 'token': secrets.token_urlsafe(32), 'receiver_id': uuid.uuid4().hex,
                  'analysis_script': str(args.analysis_script.resolve()) if args.analysis_script else None}
        validate_config(config)
        args.init.mkdir(parents=True, exist_ok=True)
        dest = args.init / 'receiver-config.json'
        pairing = args.init / 'z13.pairing.json'
        if dest.exists() or pairing.exists():
            raise ValueError('Existing pairing is preserved; use its configuration')
        atomic_json(dest, config)
        atomic_json(pairing, {'schema': 1, 'url': f'http://{args.bind}:{args.port}', 'token': config['token'], 'receiver_id': config['receiver_id']})
        print('Private receiver configuration and phone pairing file created. Keep both files private.')
    elif args.config:
        config = json.loads(args.config.read_text(encoding='utf-8'))
        validate_config(config)
        asyncio.run(serve(config))
    else:
        parser.error('--config or --init is required')


if __name__ == '__main__':
    main()
