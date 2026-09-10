"""Validate the lab policy, optionally probe a real gVisor sandbox.

Only an administrator runs this script. It is not a tenant-facing dispatcher.
"""
import argparse
import json
from pathlib import Path
import subprocess
import uuid

COMPOSE = ["docker", "compose", "-f", str(Path(__file__).with_name("compose.yaml"))]
PROBE = r'''
import json, os, pathlib
assert os.getuid() == 10001
assert not pathlib.Path('/var/run/docker.sock').exists()
mounts = pathlib.Path('/proc/mounts').read_text().splitlines()
assert any(x.split()[1] == '/' and 'ro' in x.split()[3].split(',') for x in mounts)
network_lines = pathlib.Path('/proc/net/dev').read_text().splitlines()[2:]
interfaces = {line.split(':', 1)[0].strip() for line in network_lines if ':' in line}
assert interfaces == {'lo'}, interfaces
for directory in ['/workspace', '/home/sandbox', '/tmp']:
    path = pathlib.Path(directory) / 'mcli-probe'
    path.write_text('isolated')
    assert path.read_text() == 'isolated'
    path.unlink()
assert not any(key.endswith('API_KEY') for key in os.environ)
print(json.dumps({'uid': os.getuid(), 'root_read_only': True,
                  'network': 'loopback-only', 'writable_tmpfs': True}))
'''


def command(args, timeout=30):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        details = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(args)}\n{details}")
    return result.stdout


def validate(config):
    service = config["services"]["sandbox"]
    required = {
        "runtime": "runsc", "user": "10001:10001", "network_mode": "none",
        "read_only": True, "pids_limit": 128, "mem_limit": 1610612736,
        "memswap_limit": 1610612736,
    }
    for key, expected in required.items():
        actual = service.get(key)
        if key in ("mem_limit", "memswap_limit"):
            actual = int(actual or 0)
        if actual != expected:
            raise ValueError(f"sandbox.{key} must be {expected!r}")
    if float(service.get("cpus", 0)) != 1.0:
        raise ValueError("sandbox must have a one-CPU limit")
    if service.get("cap_drop") != ["ALL"]:
        raise ValueError("sandbox must drop ALL capabilities")
    if "no-new-privileges:true" not in service.get("security_opt", []):
        raise ValueError("sandbox must prevent privilege escalation")
    for forbidden in ("volumes", "ports", "secrets", "devices", "env_file", "environment", "cap_add"):
        if service.get(forbidden):
            raise ValueError(f"sandbox cannot declare {forbidden}")
    if service.get("privileged") or service.get("pid") or service.get("ipc") == "host":
        raise ValueError("sandbox cannot share host privileges or namespaces")
    tmpfs = service.get("tmpfs", [])
    if len(tmpfs) != 3 or any("size=" not in mount for mount in tmpfs):
        raise ValueError("sandbox must use three bounded tmpfs mounts")
    for name in ("mysql", "redis"):
        if config["services"][name].get("ports"):
            raise ValueError(f"{name} cannot publish host ports")
    if not config["networks"]["backend"].get("internal"):
        raise ValueError("middleware network must be internal")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="Probe the built image using a real runsc runtime")
    args = parser.parse_args()
    config = json.loads(command(COMPOSE + ["--profile", "sandbox", "--profile", "middleware", "config", "--format", "json"]))
    validate(config)
    print("PASS: rendered Compose isolation policy")
    if not args.run:
        print("Runtime not tested. On Linux with runsc and the built image, rerun with --run.")
        return
    info = json.loads(command(["docker", "info", "--format", "{{json .}} "]))
    if info.get("OSType") != "linux" or "runsc" not in info.get("Runtimes", {}):
        raise RuntimeError("Linux Docker with runsc is required; no runc fallback")
    name = "m-cli-probe-" + uuid.uuid4().hex
    try:
        print(command(COMPOSE + ["run", "--rm", "--no-deps", "-T", "--name", name,
                                  "sandbox", "python3", "-c", PROBE], timeout=60).strip())
        print("PASS: sandbox runtime probe (not a complete multi-tenant security audit)")
    finally:
        # Only the unique container owned by this invocation; never prune shared resources.
        subprocess.run(["docker", "rm", "-f", name], capture_output=True, timeout=20)


if __name__ == "__main__":
    main()
