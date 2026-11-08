import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("contract", Path(__file__).with_name("check_raybox_contract.py"))
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


class ContractChecks(unittest.TestCase):
    def test_rejects_array_host_and_missing_detour_before_running_core(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "generated-xhttp.json"
            for invalid in (
                {"type": "xray", "tag": "contract-xhttp", "outbound": {}},
                {"type": "xray", "tag": "contract-xhttp", "detour": "contract-hop",
                 "outbound": {"streamSettings": {"xhttpSettings": {"host": ["edge.example.invalid"]}}}},
            ):
                path.write_text(json.dumps(invalid), encoding="utf-8")
                with patch.object(contract.subprocess, "run") as run:
                    with self.assertRaisesRegex(ValueError, "contract"):
                        contract.verify([path], Path("unused-core.exe"))
                    run.assert_not_called()

    def test_simplenet_normalization_rejects_unknown_labels(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "generated-xhttp-simplenet.json"
            path.write_text(json.dumps({"tag": "unexpected", "detour": "front-contract-hop"}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "runtime tag"):
                contract.verify([path], Path("unused-core.exe"))


if __name__ == "__main__":
    unittest.main()
