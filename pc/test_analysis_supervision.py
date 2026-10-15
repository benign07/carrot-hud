import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from receiver import run_analysis


class AnalysisSupervisionTest(unittest.TestCase):
    def test_success_preserves_output_and_returncode(self):
        result=run_analysis([sys.executable,'-c','print("complete")'],os.environ.copy(),timeout=5)
        self.assertEqual(result.returncode,0)
        self.assertEqual(result.stdout.strip(),b'complete')

    def test_timeout_terminates_decoder_descendant(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'child.pid'
            child='import time;time.sleep(60)'
            script=('import subprocess,sys,time;from pathlib import Path;'
                    f'p=subprocess.Popen([sys.executable,"-c",{child!r}]);'
                    f'Path({str(path)!r}).write_text(str(p.pid));time.sleep(60)')
            with self.assertRaises(subprocess.TimeoutExpired):
                run_analysis([sys.executable,'-c',script],os.environ.copy(),timeout=1)
            if path.exists() and os.name!='nt':
                state=Path('/proc')/path.read_text()/'stat'
                # A reparented zombie is terminated, pending init reap.
                self.assertTrue(not state.exists() or state.read_text().split()[2]=='Z')


if __name__=='__main__':unittest.main()
