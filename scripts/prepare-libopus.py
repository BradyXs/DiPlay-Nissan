#!/usr/bin/env python3
"""Fetch the exact libopus source used by the legacy microphone encoder.

Requires git and network access for the first build. Subsequent builds reuse the
verified checkout; never download from unpinned HEAD.
"""
from pathlib import Path
import subprocess
import tempfile
import shutil

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "shared/src/main/jni/libopus"
COMMIT = "e85ed7726db5d677c9c0677298ea0cb9c65bdd23"
REPO = "https://github.com/xiph/opus.git"

def run(*args):
    subprocess.run(args, check=True)

def main():
    marker = DEST / ".diplay-opus-commit"
    if marker.is_file() and marker.read_text().strip() == COMMIT and (DEST / "opus_sources.mk").is_file():
        return
    with tempfile.TemporaryDirectory(prefix="diplay-opus-") as td:
        checkout = Path(td) / "opus"
        run("git", "clone", "--no-checkout", REPO, str(checkout))
        run("git", "-C", str(checkout), "checkout", "--detach", COMMIT)
        actual = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if actual != COMMIT:
            raise RuntimeError("Unexpected libopus revision")
        if DEST.exists():
            shutil.rmtree(DEST)
        shutil.copytree(checkout, DEST, ignore=shutil.ignore_patterns(".git"))
        marker.write_text(COMMIT + "\n")
    for f in ("opus_sources.mk", "celt_sources.mk", "silk_sources.mk", "include/opus.h"):
        if not (DEST / f).is_file():
            raise RuntimeError(f"Missing libopus source: {f}")

if __name__ == "__main__":
    main()
