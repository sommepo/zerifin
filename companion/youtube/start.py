#!/usr/bin/env python3
"""Set up and start the Zerifin YouTube companion on Windows, macOS or Linux."""

import argparse
import hashlib
import ipaddress
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys
import venv

ROOT = Path(__file__).resolve().parent
RUNTIME = ROOT / '.runtime'


def allowed_bind(value):
    address = ipaddress.ip_address(value)
    allowed = address.is_loopback or any(address in network for network in (
        ipaddress.ip_network('10.0.0.0/8'),
        ipaddress.ip_network('172.16.0.0/12'),
        ipaddress.ip_network('192.168.0.0/16'),
        ipaddress.ip_network('100.64.0.0/10'),
    ))
    if address.version != 4 or not allowed:
        raise argparse.ArgumentTypeError('Use a LAN or Tailscale IPv4 address.')
    return str(address)


def tailscale_binary():
    found = shutil.which('tailscale')
    if found:
        return found
    if platform.system() == 'Windows':
        candidate = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'Tailscale' / 'tailscale.exe'
        if candidate.is_file():
            return str(candidate)
    return None


def automatic_bind():
    tailscale = tailscale_binary()
    if tailscale:
        result = subprocess.run([tailscale, 'ip', '-4'], capture_output=True, text=True, check=False)
        for line in result.stdout.splitlines():
            try:
                return allowed_bind(line.strip())
            except (ValueError, argparse.ArgumentTypeError):
                pass
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.connect(('192.0.2.1', 9))
            return allowed_bind(probe.getsockname()[0])
        finally:
            probe.close()
    except (OSError, ValueError, argparse.ArgumentTypeError):
        raise SystemExit('Could not choose a private address. Run again with --bind <LAN-or-Tailscale-IP>.')


def require_node():
    node = shutil.which('node')
    if not node:
        raise SystemExit('Node 22 or newer is required. Install the current Node.js LTS release, then run this again.')
    result = subprocess.run([node, '--version'], capture_output=True, text=True, check=False)
    try:
        major = int(result.stdout.strip().lstrip('v').split('.')[0])
    except (ValueError, IndexError):
        major = 0
    if major < 22:
        raise SystemExit(f'Node 22 or newer is required; found {result.stdout.strip() or "an unknown version"}.')


def prepare_runtime():
    environment = os.environ.copy()
    vendor = RUNTIME / 'vendor'
    if platform.system() == 'Linux' and platform.machine() == 'x86_64':
        if not (vendor / 'yt_dlp').is_dir():
            subprocess.check_call([sys.executable, str(ROOT / 'install.py')])
        environment['PYTHONPATH'] = str(vendor)
        return sys.executable, environment

    python = RUNTIME / 'venv' / ('Scripts/python.exe' if platform.system() == 'Windows' else 'bin/python')
    requirements = (ROOT / 'requirements.txt').read_bytes()
    marker = RUNTIME / 'requirements.sha256'
    expected = hashlib.sha256(requirements).hexdigest()
    if not python.is_file():
        RUNTIME.mkdir(parents=True, exist_ok=True)
        venv.EnvBuilder(with_pip=True).create(RUNTIME / 'venv')
    if not marker.is_file() or marker.read_text().strip() != expected:
        subprocess.check_call([
            str(python), '-m', 'pip', 'install', '--disable-pip-version-check', '--no-input',
            '--requirement', str(ROOT / 'requirements.txt'),
        ])
        marker.write_text(expected + '\n')
    return str(python), environment


def main():
    if sys.version_info < (3, 10):
        raise SystemExit('Python 3.10 or newer is required.')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', type=allowed_bind, help='LAN or Tailscale IPv4; detected automatically by default')
    parser.add_argument('--port', type=int, default=8767)
    args = parser.parse_args()
    require_node()
    address = args.bind or automatic_bind()
    python, environment = prepare_runtime()
    print(f'Zerifin resolver address: http://{address}:{args.port}', flush=True)
    print('Keep this window open while using YouTube in Zerifin. Press Ctrl+C to stop.', flush=True)
    try:
        return subprocess.call(
            [python, str(ROOT / 'resolver.py'), '--bind', address, '--port', str(args.port)],
            cwd=ROOT,
            env=environment,
        )
    except KeyboardInterrupt:
        print('\nZerifin resolver stopped.')
        return 0


if __name__ == '__main__':
    raise SystemExit(main())
