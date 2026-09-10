"""Generate local lab credentials without printing or replacing existing secrets."""
import os
from pathlib import Path
import secrets


def main():
    directory = Path(__file__).resolve().parent / "secrets"
    directory.mkdir(mode=0o700, exist_ok=True)
    values = {
        "mysql_password": secrets.token_hex(32) + "\n",
        "mysql_root_password": secrets.token_hex(32) + "\n",
        "redis.conf": (
            "bind 0.0.0.0\nprotected-mode yes\n"
            f"requirepass {secrets.token_hex(32)}\n"
            "dir /data\nappendonly yes\nmaxmemory 128mb\n"
            "maxmemory-policy noeviction\n"
        ),
    }
    for name, value in values.items():
        try:
            descriptor = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            print(f"Kept existing {name}")
            continue
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(value)
        # Compose mounts files read-only; allow each image's service UID to read.
        # The containing directory remains private (0700) on the Linux host.
        (directory / name).chmod(0o644)
        print(f"Created {name}")
    print("Local credentials only; do not commit deploy/secrets/.")


if __name__ == "__main__":
    main()
