"""Compare real client-generated XHTTP fixtures and check them with RayBox."""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path


def verify(paths, core):
    expected = {
        "type": "xray", "tag": "contract-xhttp", "detour": "contract-hop",
        "outbound": {
            "protocol": "vless",
            "settings": {"vnext": [{
                "address": "example.invalid", "port": 443,
                "users": [{"id": "00000000-0000-0000-0000-000000000001", "encryption": "none"}],
            }]},
            "streamSettings": {
                "network": "xhttp", "security": "tls",
                "tlsSettings": {"serverName": "edge.example.invalid", "alpn": ["h2"], "fingerprint": "chrome"},
                "xhttpSettings": {"path": "/contract", "host": "edge.example.invalid", "mode": "packet-up"},
            },
        },
    }
    for path in paths:
        actual = json.loads(path.read_text(encoding="utf-8-sig"))
        normalized = json.loads(json.dumps(actual))
        # SimpleNet prefixes its real node/front tags; only these declared references differ.
        if path.name == "generated-xhttp-simplenet.json":
            if (normalized.get("tag"), normalized.get("detour")) != ("node-contract-hop", "front-contract-hop"):
                raise ValueError("Unexpected SimpleNet runtime tag references: " + str(path))
            normalized["tag"] = "contract-xhttp"
            normalized["detour"] = "contract-hop"
        if normalized != expected:
            raise ValueError("Generated fixture differs from the agreed contract: " + str(path))
        with tempfile.TemporaryDirectory(prefix="raybox-client-contract-") as temporary:
            config = Path(temporary) / "config.json"
            config.write_text(json.dumps({
                "log": {"disabled": True},
                "outbounds": [{"type": "direct", "tag": actual["detour"]}, actual],
            }), encoding="utf-8")
            subprocess.run([str(core), "check", "-c", str(config)], check=True, timeout=20)
    return {"clients": len(paths), "equal": True, "nativeCoreCheck": True, "rxDefault": False, "oobDefault": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixtures", type=Path, nargs="+")
    parser.add_argument("--core", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.fixtures, args.core.resolve(strict=True)), indent=2))
