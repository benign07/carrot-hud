import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from aiohttp.test_utils import TestClient, TestServer
from receiver import create_app, validate_config, MAX_CHUNK


class ReceiverTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.config = {'out': str(self.root), 'token': 't' * 43, 'receiver_id': 'b' * 32,
                       'allowed_peers': ['127.0.0.1'], 'reserve_bytes': 0}
        self.client = TestClient(TestServer(create_app(self.config)))
        await self.client.start_server()
        self.payload = gzip.compress(b'{"kind":"header","schema":1}\n{"kind":"event","name":"test"}\n')
        self.ident = 'a' * 32

    async def asyncTearDown(self):
        await self.client.close()
        self.folder.cleanup()

    def headers(self, data=None):
        return {'Authorization': 'Bearer ' + self.config['token'], 'X-Receiver-Id': self.config['receiver_id'],
                'X-Chunk-SHA256': hashlib.sha256(self.payload if data is None else data).hexdigest()}

    async def put(self, data=None, headers=None, ident=None):
        return await self.client.put('/v1/chunks/' + (ident or self.ident),
                                     data=self.payload if data is None else data, headers=headers or self.headers())

    async def test_store_durable_receipt_and_duplicate(self):
        first = await self.put()
        self.assertEqual(first.status, 201)
        ack = await first.json()
        self.assertTrue(ack['stored'])
        self.assertEqual((self.root / (self.ident + '.jsonl.gz')).read_bytes(), self.payload)
        self.assertTrue((self.root / (self.ident + '.manifest.json')).exists())
        self.assertEqual((await self.put()).status, 200)
        self.assertEqual(len(list(self.root.glob('*.jsonl.gz'))), 1)

    async def test_unauthorized_and_wrong_receiver(self):
        headers = self.headers()
        headers['Authorization'] = 'Bearer wrong'
        self.assertEqual((await self.put(headers=headers)).status, 401)
        headers = self.headers()
        headers['X-Receiver-Id'] = 'c' * 32
        self.assertEqual((await self.put(headers=headers)).status, 403)
        self.assertFalse(list(self.root.iterdir()))

    async def test_corruption_and_invalid_schema_not_published(self):
        self.assertEqual((await self.put(data=b'x' * len(self.payload))).status, 400)
        data = gzip.compress(b'{"kind":"header","schema":9}\n')
        self.assertEqual((await self.put(data=data, headers=self.headers(data))).status, 400)
        self.assertFalse(list(self.root.iterdir()))

    async def test_conflicting_id_preserves_original(self):
        await self.put()
        data = gzip.compress(b'{"kind":"header","schema":1}\n')
        self.assertEqual((await self.put(data=data, headers=self.headers(data))).status, 409)
        self.assertEqual((self.root / (self.ident + '.jsonl.gz')).read_bytes(), self.payload)

    async def test_pc_loss_repairs_file_and_missing_manifest(self):
        await self.put()
        (self.root / (self.ident + '.manifest.json')).unlink()
        self.assertEqual((await self.put()).status, 200)
        (self.root / (self.ident + '.jsonl.gz')).write_bytes(b'bad')
        self.assertEqual((await self.put()).status, 201)

    async def test_recovered_flag_survives_phone_transfer(self):
        headers = self.headers()
        headers['X-Chunk-Reason'] = 'power_loss_recovered'
        self.assertEqual((await self.put(headers=headers)).status, 201)
        saved = json.loads((self.root / (self.ident + '.manifest.json')).read_text())
        self.assertEqual(saved['reason'], 'power_loss_recovered')

    async def test_quota_preserves_existing_data(self):
        self.config['quota_bytes'] = 1
        self.assertEqual((await self.put()).status, 507)
        self.assertFalse(list(self.root.iterdir()))

    async def test_compression_bomb_and_oversize_rejected(self):
        data = gzip.compress(b'a' * (16 * 1024**2 + 1))
        self.assertEqual((await self.put(data=data, headers=self.headers(data))).status, 400)
        data = b'a' * (MAX_CHUNK + 1)
        self.assertEqual((await self.put(data=data, headers=self.headers(data))).status, 400)
        self.assertFalse(list(self.root.iterdir()))

    async def test_invalid_id_and_unapproved_peer(self):
        self.assertEqual((await self.put(ident='bad-id')).status, 400)
        self.config['allowed_peers'] = ['100.103.18.55']
        self.assertEqual((await self.put()).status, 403)

    def test_configuration_cannot_bind_public_interfaces(self):
        good = {**self.config, 'bind': '100.114.242.2', 'port': 7041, 'allowed_peers': ['100.103.18.55']}
        validate_config(good)
        for bad in ('0.0.0.0', '127.0.0.1', '192.168.0.2'):
            with self.assertRaises(ValueError): validate_config({**good, 'bind': bad})


if __name__ == '__main__':
    unittest.main()
