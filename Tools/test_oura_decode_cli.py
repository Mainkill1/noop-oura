"""Execute the offline Oura CLI, including complete-envelope validation."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class OuraDecodeCLITests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        swift = shutil.which("swift")
        if not swift:
            raise unittest.SkipTest("Swift toolchain is required for the offline CLI")
        package = ROOT / "Packages/OuraProtocol"
        subprocess.run([swift, "build", "--package-path", str(package), "--product", "oura-decode"], check=True, capture_output=True)
        bindir = subprocess.check_output([swift, "build", "--package-path", str(package), "--show-bin-path"], text=True).strip()
        cls.executable = str(Path(bindir) / "oura-decode")

    def run_cli(self, *args, stdin=""):
        return subprocess.run([self.executable, *args], input=stdin, capture_output=True, text=True, timeout=10)

    def test_canonical_commands_exit_without_capture(self):
        process = subprocess.Popen([self.executable, "--live-motion-commands"],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            # Keep stdin open: a regression that reads the capture before this branch blocks here.
            self.assertEqual(process.wait(timeout=5), 0)
            self.assertEqual(process.stdout.read(), "acm_start 060720000000010000\nacm_stop 060400000000\n")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()
        self.assertNotEqual(self.run_cli("--live-motion-commands", "--hex", "3300").returncode, 0)

    def test_one_and_two_vectors_keep_signed_extremes_and_opaque_metadata(self):
        result = self.run_cli("--live-motion", "--json", "--hex", "330831fe0080ff7fffff", "330e32ff000001003412ff7f00800000")
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = json.loads(result.stdout)
        self.assertEqual([r["kind"] for r in rows], ["LIVE_MOTION", "LIVE_MOTION"])
        self.assertIn("metadata=49 sequence=254 xyz=(-32768,32767,-1)", rows[0]["detail"])
        self.assertIn("xyz=(0,1,4660);(32767,-32768,0)", rows[1]["detail"])

    def test_invalid_envelope_never_emits_valid_prefix(self):
        for capture in ["330831fe0080ff7fffff33", "330931fe0080ff7fffff00", "330e31fe0080ff7fffff"]:
            result = self.run_cli("--live-motion", "--json", "--hex", capture)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn("LIVE_MOTION", result.stdout)

    def test_timed_capture_summary_preserves_counts_means_and_extremes(self):
        capture = json.dumps([
            {"hex": "33080001010002000300", "ts_ms": 1000123},
            {"hex": "33080002ffff0080ff7f", "ts_ms": 1000789},
        ])
        result = self.run_cli("--live-motion-summary", stdin=capture)
        self.assertEqual(result.returncode, 0, result.stderr)
        summary = json.loads(result.stdout)[0]
        self.assertEqual(summary["count"], 2)
        self.assertEqual(summary["mean_x"], 0)
        self.assertEqual(summary["min_y"], -32768)
        self.assertEqual(summary["max_z"], 32767)
        self.assertEqual(summary["first_receipt_ms"], 1000123)
        self.assertEqual(summary["last_receipt_ms"], 1000789)
        self.assertNotEqual(self.run_cli("--live-motion-summary", stdin='[{"hex":"33080001010002000300"}]').returncode, 0)

    def test_json_capture_and_old_historical_record_remain_usable(self):
        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / "capture.json"
            capture.write_text(json.dumps([{"hex": "33080001010002000300", "ts_ms": 1234567890123}]))
            result = self.run_cli("--live-motion", "--json", str(capture))
            self.assertEqual(json.loads(result.stdout)[0]["kind"], "LIVE_MOTION")
        old = self.run_cli("--json", "--hex", "801202000100698c660e652a6a09670f6d2b693e")
        self.assertEqual(old.returncode, 0, old.stderr)
        self.assertEqual([row["kind"] for row in json.loads(old.stdout)], ["IBI"] * 6)


if __name__ == "__main__":
    unittest.main()
