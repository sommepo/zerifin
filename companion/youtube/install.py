#!/usr/bin/env python3
"""Install checksum-pinned Linux x86_64 runtime locally without sudo, pip or system changes."""
import hashlib
import io
import json
from pathlib import Path
import platform
import urllib.request
import zipfile

PACKAGES = (
    ('yt-dlp', '2026.8.19', '1d57897e94c6665a0a6f9bc54b34e584284e32c034ffab3a7df25d8f7b24eedf'),
    ('yt-dlp-ejs', '0.8.0', '79300e5fca7f937a1eeede11f0456862c1b41107ce1d726871e0207424f4bdb4'),
    ('imageio-ffmpeg', '0.6.0', 'c7e46fcec401dd990405049d2e2f475e2b397779df2519b544b8aab515195282'),
)


def main():
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise SystemExit('This bootstrap targets Linux x86_64. On other systems use a venv and requirements.txt.')
    directory = Path(__file__).resolve().parent / '.runtime' / 'vendor'
    directory.mkdir(parents=True, exist_ok=True)
    for name, version, checksum in PACKAGES:
        with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{version}/json', timeout=30) as response:
            metadata = json.load(response)
        wheel = next(file for file in metadata['urls'] if file['digests']['sha256'] == checksum)
        with urllib.request.urlopen(wheel['url'], timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != checksum:
            raise SystemExit(f'Checksum mismatch for {name}')
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for member in archive.namelist():
                target = (directory / member).resolve()
                if not target.is_relative_to(directory):
                    raise SystemExit('Unsafe archive path')
            archive.extractall(directory)
        print(f'Installed {name} {version}')
    for binary in (directory / 'imageio_ffmpeg' / 'binaries').glob('ffmpeg-*'):
        binary.chmod(0o755)
    print('Ready. Node 22+ must be on PATH. Run ./run.sh --bind <LAN-IP>.')


if __name__ == '__main__':
    main()
