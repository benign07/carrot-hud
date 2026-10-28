"""The APK and Windows artifacts must ship the same portable schema/presets."""
import json
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
class ProfileContract(unittest.TestCase):
 def test_shared_fixture_bytes_and_scope(self):
  android=(ROOT/'app/src/main/assets/tuning_profiles_v1.json').read_bytes()
  self.assertEqual(android,(ROOT/'pc/tuning_lab/tuning_profiles_v1.json').read_bytes())
  profiles=json.loads(android)['profiles'];self.assertEqual(len(profiles),5)
  keys=set(profiles[0]['values']);self.assertEqual(len(keys),27)
  for p in profiles:
   self.assertEqual(p['schema'],1);self.assertEqual(p['kind'],'CarrotTuningProfile')
   self.assertEqual(p['scope'],'longitudinal');self.assertEqual(p['family'],'LX3')
   self.assertEqual(set(p['values']),keys);self.assertEqual(set(p['units']),keys)
   self.assertNotIn('DisableDM',keys);self.assertNotIn('UseLaneLineSpeed',keys)
   self.assertEqual(len(p['behavior_revision']),64)
   self.assertTrue(all(type(x) is int for x in p['values'].values()))
