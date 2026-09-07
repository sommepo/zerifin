#!/usr/bin/env python3
"""Small trusted-LAN YouTube adapter. No accounts, persistent media or Jellyfin credentials."""
import argparse
import html
import ipaddress
import json
import logging
import math
import os
import re
import secrets
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = logging.getLogger('zerifin.youtube')
MAX_CAPTION = 2 * 1024 * 1024
SESSIONS = {}
LOCK = threading.Lock()
WORK = threading.BoundedSemaphore(8)
EXTRACT = threading.BoundedSemaphore(2)
ID = re.compile(r'^[A-Za-z0-9_-]{11}$')
FFMPEG = None


class ApiError(Exception):
    def __init__(self, code, message, status=422):
        self.code, self.message, self.status = code, message, status


def video_id(value):
    if ID.fullmatch(value):
        return value
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ('https', 'http') or parsed.username or parsed.password:
        raise ApiError('invalid_url', 'Paste a YouTube video URL.')
    host = (parsed.hostname or '').lower()
    if host in ('youtu.be', 'www.youtu.be'):
        candidate = parsed.path.strip('/')
    elif host in ('youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com'):
        candidate = urllib.parse.parse_qs(parsed.query).get('v', [''])[0]
        if not candidate and re.fullmatch(r'/(shorts|live|embed)/[^/]+/?', parsed.path):
            candidate = parsed.path.strip('/').split('/')[1]
    else:
        candidate = ''
    if not ID.fullmatch(candidate):
        raise ApiError('invalid_url', 'Paste a YouTube video URL, not a playlist or channel.')
    return candidate


def upstream_url(url):
    p = urllib.parse.urlsplit(url)
    host = (p.hostname or '').lower()
    if p.scheme != 'https' or p.username or p.password or p.port not in (None, 443) or not any(
        host == suffix or host.endswith('.' + suffix) for suffix in ('googlevideo.com', 'youtube.com', 'ytimg.com')
    ):
        raise ApiError('invalid_upstream', 'YouTube returned an unsupported stream address.')
    return url


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        upstream_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


OPENER = urllib.request.build_opener(SafeRedirect())


def fetch(url, headers=None):
    return OPENER.open(urllib.request.Request(upstream_url(url), headers=headers or {}), timeout=25)


def clean_headers(headers):
    return {k: v for k, v in (headers or {}).items() if k.lower() in ('user-agent', 'referer', 'origin', 'accept-language')
            and isinstance(v, str) and '\r' not in v and '\n' not in v}


class ExtractLog:
    # yt-dlp messages can contain signed URLs; expose categories instead of raw messages.
    def debug(self, message):
        pass

    def warning(self, message):
        LOG.warning('extractor warning: %s', error_code(message))

    def error(self, message):
        LOG.warning('extractor error: %s', error_code(message))


def error_code(message):
    message = str(message).lower()
    if any(word in message for word in ('sign in', 'age-restricted', 'login', 'not a bot', 'po token')):
        return 'youtube_restricted'
    if any(word in message for word in ('unavailable', 'private video', 'removed', 'copyright')):
        return 'unavailable'
    if any(word in message for word in ('timed out', 'network', 'connection', 'resolve host')):
        return 'network'
    return 'extraction_failed'


def extract(value, search=False):
    from yt_dlp import YoutubeDL
    from yt_dlp.utils import DownloadError
    options = dict(quiet=True, no_warnings=True, logger=ExtractLog(), skip_download=True, noplaylist=True,
                   socket_timeout=20, retries=1, extractor_retries=1, cachedir=False,
                   js_runtimes={'node': {}}, extract_flat='in_playlist' if search else False,
                   format='bestvideo[height<=720]+bestaudio/best', ignore_no_formats_error=True)
    try:
        if not EXTRACT.acquire(blocking=False):
            raise ApiError('busy', 'Two videos are already resolving. Try again in a moment.', 503)
        try:
            with YoutubeDL(options) as ydl:
                return ydl.extract_info(value, download=False)
        finally:
            EXTRACT.release()
    except DownloadError as exc:
        code = error_code(exc)
        messages = {'youtube_restricted': 'YouTube requires login or has restricted this server. Try another public video.',
                    'unavailable': 'This video is unavailable, private or region restricted.',
                    'network': 'The server could not reach YouTube. Try again.'}
        raise ApiError(code, messages.get(code, 'YouTube extraction failed. Check the resolver log and yt-dlp version.')) from None


def metadata(info):
    return dict(id=info['id'], title=str(info.get('title') or 'YouTube video')[:500],
                channel=str(info.get('channel') or info.get('uploader') or '')[:200],
                durationMs=int((info.get('duration') or 0) * 1000))


def caption_track(info, language):
    for kind in ('subtitles', 'automatic_captions'):
        tracks = info.get(kind) or {}
        # Never use automatic translations as if they were original Japanese captions.
        keys = sorted((k for k in tracks if k == language or k.startswith(language + '-')),
                      key=lambda k: (k != language, k != language + '-orig', k))
        for key in keys:
            for track in tracks[key]:
                query = urllib.parse.parse_qs(urllib.parse.urlsplit(track.get('url', '')).query)
                if kind == 'automatic_captions' and query.get('tlang'):
                    continue
                if track.get('ext') == 'json3':
                    return kind, key, track
    return None


def timestamp(ms):
    seconds, millis = divmod(ms, 1000)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f'{hours:02}:{minutes:02}:{seconds:02}.{millis:03}'


def normalize_captions(payload):
    """Flatten JSON3 timed text to absolute WebVTT; remove roll-up overlap and repeated windows."""
    cues = []
    for event in payload.get('events', []):
        text = ''.join(seg.get('utf8', '') for seg in event.get('segs', []))
        text = ' '.join(html.unescape(text).split())
        if not text:
            continue
        start = max(0, int(event.get('tStartMs', 0)))
        end = start + max(0, int(event.get('dDurationMs', 0)))
        if end > start:
            cues.append([start, end, text])
    cues.sort(key=lambda cue: cue[0])
    compact = []
    for cue in cues:
        if compact and compact[-1][2] == cue[2] and cue[0] <= compact[-1][1] + 50:
            compact[-1][1] = max(compact[-1][1], cue[1])
        else:
            compact.append(cue)
    for first, second in zip(compact, compact[1:]):
        first[1] = min(first[1], second[0])
    result = 'WEBVTT\n\n' + '\n\n'.join(
        f'{timestamp(start)} --> {timestamp(end)}\n{html.escape(text, quote=False)}'
        for start, end, text in compact if end > start)
    data = result.encode('utf-8')
    if not compact or len(data) > MAX_CAPTION:
        raise ApiError('captions_invalid', 'The caption track is empty or too large.')
    return data


def captions(info, language):
    selected = caption_track(info, language)
    if not selected:
        LOG.info('captions video=%s language=%s absent', info['id'], language)
        return None
    kind, key, track = selected
    with fetch(track['url'], clean_headers(info.get('http_headers'))) as response:
        data = response.read(MAX_CAPTION + 1)
    if len(data) > MAX_CAPTION:
        raise ApiError('captions_invalid', 'The caption track is too large.')
    result = normalize_captions(json.loads(data))
    LOG.info('captions video=%s language=%s kind=%s selected=%s normalized_bytes=%d', info['id'], language, kind, key, len(result))
    return result


def select_formats(info):
    formats = [f for f in info.get('formats', []) if f.get('url') and f.get('protocol') == 'https' and not f.get('has_drm')]
    video = [f for f in formats if f.get('vcodec') not in (None, 'none') and (f.get('height') or 0) <= 720]
    if not video:
        raise ApiError('no_format', 'No supported direct video stream is available for this video.')
    # A single progressive MP4 is cheap and robust; otherwise combine two streams in Media3.
    combined = [f for f in video if f.get('acodec') not in (None, 'none')]
    japanese = lambda f: str(f.get('language') or '').split('-')[0] == 'ja'
    japanese_separate = any(f.get('vcodec') == 'none' and japanese(f) for f in formats)
    if japanese_separate:
        combined = [f for f in combined if japanese(f)]
    if combined:
        chosen = max(combined, key=lambda f: (japanese(f), f.get('height') or 0, f.get('tbr') or 0))
        audio = chosen
    else:
        video = [f for f in video if f.get('acodec') == 'none']
        if not video:
            raise ApiError('no_format', 'No compatible separate video stream is available.')
        chosen = max(video, key=lambda f: (str(f.get('vcodec')).startswith('avc'), f.get('height') or 0, f.get('tbr') or 0))
        audios = [f for f in formats if f.get('vcodec') == 'none' and f.get('acodec') not in (None, 'none')]
        if not audios:
            raise ApiError('no_audio', 'No supported audio stream is available.')
        audio = max(audios, key=lambda f: (japanese(f), f.get('language_preference') or 0, f.get('abr') or 0))
    for f in (chosen, audio):
        upstream_url(f['url'])
    LOG.info('formats video=%s video_format=%s audio_format=%s height=%s direct=true',
             info['id'], chosen.get('format_id'), audio.get('format_id'), chosen.get('height'))
    return chosen, audio


def prune_sessions():
    now = time.monotonic()
    for key in list(SESSIONS):
        if now - SESSIONS[key]['created'] > 6 * 3600:
            del SESSIONS[key]
    while len(SESSIONS) >= 32:
        del SESSIONS[next(iter(SESSIONS))]


def resolve(value):
    identifier = video_id(value)
    LOG.info('resolve video=%s begin', identifier)
    info = extract('https://www.youtube.com/watch?v=' + identifier)
    if info.get('is_live') or info.get('live_status') == 'is_upcoming':
        raise ApiError('live_unsupported', 'Choose a recorded video; live streams are not supported yet.')
    video, audio = select_formats(info)
    ja = captions(info, 'ja')
    en = captions(info, 'en') if caption_track(info, 'en') else None
    session = secrets.token_urlsafe(24)
    japanese_audio = str(audio.get('language') or info.get('language') or '').split('-')[0] == 'ja'
    # Original Japanese auto-captions are also evidence of the original audio language.
    original_ja = 'ja-orig' in (info.get('automatic_captions') or {}) or any(
        urllib.parse.parse_qs(urllib.parse.urlsplit(t.get('url', '')).query).get('kind') == ['asr']
        and urllib.parse.parse_qs(urllib.parse.urlsplit(t.get('url', '')).query).get('lang') == ['ja']
        for tracks in (info.get('automatic_captions') or {}).values() for t in tracks
    )
    if not audio.get('language') and original_ja:
        japanese_audio = True
    audio_unknown = not audio.get('language') and not info.get('language') and not original_ja
    with LOCK:
        prune_sessions()
        SESSIONS[session] = dict(created=time.monotonic(), video=video, audio=audio, ja=ja, en=en,
                                 japanese_audio=japanese_audio, audio_unknown=audio_unknown)
    result = metadata(info)
    result.update(session=session, videoUrl=video['url'], audioUrl=audio['url'] if video is not audio else None,
                  videoMime='video/webm' if video.get('ext') == 'webm' else 'video/mp4',
                  audioMime='audio/webm' if audio.get('ext') == 'webm' else 'audio/mp4',
                  videoHeaders=clean_headers(video.get('http_headers') or info.get('http_headers')),
                  audioHeaders=clean_headers(audio.get('http_headers') or info.get('http_headers')),
                  height=video.get('height') or 720, width=video.get('width') or 1280,
                  japaneseCaptions=ja is not None, englishCaptions=en is not None, japaneseAudio=japanese_audio,
                  audioLanguageUnknown=audio_unknown,
                  warning=None if ja else 'No Japanese subtitles are available. Playback works, but Japanese lookup needs captions.')
    LOG.info('resolve video=%s ready japanese_audio=%s', identifier, japanese_audio)
    return result


def session_for(key):
    with LOCK:
        session = SESSIONS.get(key)
        if session is None or time.monotonic() - session['created'] > 6 * 3600:
            raise ApiError('expired', 'This playback session expired. Open the video again.', 410)
        return session


def clip(session, start, end, relay_url):
    if not math.isfinite(start) or not math.isfinite(end) or start < 0 or not 0 < end - start <= 30:
        raise ApiError('invalid_clip', 'Sentence clips must be between zero and 30 seconds.')
    if not session['japanese_audio']:
        raise ApiError('no_japanese_audio', 'A Japanese audio track could not be identified.')
    if not FFMPEG:
        raise ApiError('no_ffmpeg', 'Install ffmpeg on the resolver to enable sentence audio.', 503)
    # The pinned ffmpeg's HTTPS stack crashes with some YouTube URLs. Our existing
    # bounded range relay handles TLS and headers; ffmpeg only reads this server's HTTP endpoint.
    args = [FFMPEG, '-nostdin', '-hide_banner', '-loglevel', 'error', '-rw_timeout', '20000000',
            '-protocol_whitelist', 'http,tcp', '-ss', str(start), '-i', relay_url,
            '-t', str(end - start), '-vn', '-ac', '1', '-ar', '24000', '-acodec', 'pcm_s16le', '-f', 's16le', 'pipe:1']
    try:
        result = subprocess.run(args, capture_output=True, timeout=40, check=False)
    except subprocess.TimeoutExpired:
        raise ApiError('clip_timeout', 'Sentence audio took too long. Try again.', 504) from None
    if result.returncode or len(result.stdout) < round((end - start) * 24000) * 2:
        raise ApiError('clip_failed', 'Could not extract sentence audio. Open the video again if its stream expired.', 502)
    return result.stdout[:round((end - start) * 24000) * 2]


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def log_message(self, *args):
        pass  # URLs carry session capabilities; never print access-log paths.

    def reply(self, data, status=200, content_type='application/json'):
        if not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        acquired = False
        try:
            if self.headers.get('Origin'):
                raise ApiError('native_only', 'Use the native app to access this resolver.', 403)
            parsed = urllib.parse.urlsplit(self.path)
            path = parsed.path.strip('/').split('/')
            if path == ['health']:
                self.reply(dict(status='ok', sentenceAudio=bool(FFMPEG)))
                return
            acquired = WORK.acquire(blocking=False)
            if not acquired:
                raise ApiError('busy', 'The resolver is busy. Try again in a moment.', 503)
            if self.command == 'POST' and path in (['search'], ['resolve']):
                if self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                    raise ApiError('invalid_request', 'Send application/json.', 415)
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 4096:
                    raise ApiError('invalid_request', 'The request is too large or empty.', 400)
                body = json.loads(self.rfile.read(length))
                value = str(body.get('query', '')).strip()
                if not value or len(value) > 500:
                    raise ApiError('invalid_request', 'Enter a search or YouTube URL.', 400)
                if path == ['resolve']:
                    self.reply(resolve(value))
                else:
                    LOG.info('search begin characters=%d', len(value))
                    info = extract('ytsearch10:' + value, search=True)
                    entries = [metadata(e) for e in info.get('entries', []) if e and ID.fullmatch(e.get('id', ''))]
                    LOG.info('search ready results=%d', len(entries))
                    self.reply(dict(results=entries))
                return
            if self.command == 'POST' and len(path) == 3 and path[0] == 'session' and path[2] == 'japanese-audio':
                session = session_for(path[1])
                with LOCK:
                    if not session['japanese_audio'] and not session.get('audio_unknown'):
                        raise ApiError('audio_language', 'This audio track is labelled as another language.')
                    session['japanese_audio'] = True
                    session['audio_unknown'] = False
                LOG.info('audio language confirmed Japanese by viewer')
                self.reply(dict(status='ok'))
                return
            if self.command == 'GET' and len(path) == 3 and path[0] == 'session':
                session = session_for(path[1])
                resource = path[2]
                if resource in ('ja.vtt', 'en.vtt'):
                    data = session[resource[:2]]
                    if data is None:
                        raise ApiError('no_captions', 'This caption track is unavailable.', 404)
                    self.reply(data, content_type='text/vtt; charset=utf-8')
                elif resource == 'sentence.pcm':
                    query = urllib.parse.parse_qs(parsed.query)
                    host, port = self.server.server_address
                    relay_url = f'http://{host}:{port}/session/{path[1]}/audio'
                    data = clip(session, float(query.get('start', ['-1'])[0]), float(query.get('end', ['-1'])[0]), relay_url)
                    self.reply(data, content_type='application/octet-stream')
                    LOG.info('sentence clip ready bytes=%d', len(data))
                elif resource in ('video', 'audio'):
                    self.relay(session[resource])
                else:
                    raise ApiError('not_found', 'Unknown resource.', 404)
                return
            raise ApiError('not_found', 'Unknown endpoint.', 404)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except ApiError as exc:
            LOG.warning('request error=%s', exc.code)
            self.reply(dict(error=exc.code, message=exc.message), exc.status)
        except (urllib.error.URLError, TimeoutError):
            LOG.warning('request error=upstream_network')
            self.reply(dict(error='upstream_network', message='YouTube could not be reached or the stream expired. Open the video again.'), 502)
        except (ValueError, KeyError, TypeError):
            LOG.warning('request error=invalid_data')
            self.reply(dict(error='invalid_data', message='Invalid request or unsupported YouTube response.'), 400)
        except Exception as exc:
            LOG.error('request error=internal type=%s', type(exc).__name__)
            self.reply(dict(error='internal', message='The resolver failed. Check its log.'), 500)
        finally:
            if acquired:
                WORK.release()

    def relay(self, media):
        headers = clean_headers(media.get('http_headers'))
        byte_range = self.headers.get('Range')
        if byte_range:
            if not re.fullmatch(r'bytes=\d*-\d*', byte_range):
                raise ApiError('invalid_range', 'Unsupported byte range.', 416)
            headers['Range'] = byte_range
        response = fetch(media['url'], headers)
        with response:
            self.send_response(response.status)
            for key in ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges'):
                if response.headers.get(key):
                    self.send_header(key, response.headers[key])
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            while data := response.read(64 * 1024):
                self.wfile.write(data)


def private_bind(value):
    address = ipaddress.ip_address(value)
    allowed = address.is_loopback or address in ipaddress.ip_network('10.0.0.0/8') or address in ipaddress.ip_network('172.16.0.0/12') or address in ipaddress.ip_network('192.168.0.0/16') or address in ipaddress.ip_network('100.64.0.0/10')
    if address.version != 4 or not allowed:
        raise argparse.ArgumentTypeError('Bind to a specific IPv4 LAN, Tailscale or loopback address.')
    return value


def main():
    global FFMPEG
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', type=private_bind, default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8767)
    args = parser.parse_args()
    if args.port in (8765, 8766):
        parser.error('Ports 8765 and 8766 are reserved; use 8767.')
    FFMPEG = os.environ.get('ZERIFIN_FFMPEG') or shutil.which('ffmpeg')
    if not FFMPEG:
        try:
            import imageio_ffmpeg
            FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
        except (ImportError, RuntimeError):
            pass
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s %(message)s')
    LOG.info('starting bind=%s port=%d sentence_audio=%s', args.bind, args.port, bool(FFMPEG))
    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    server.daemon_threads = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
