#!/usr/bin/env python3
"""Keep the official Kotlin ABI; build JNI/ORT from the same pinned upstream source."""
import argparse
from pathlib import Path
import zipfile

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('aar', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.aar) as archive:
        (args.output / 'classes.jar').write_bytes(archive.read('classes.jar'))
