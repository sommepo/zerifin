import argparse
import json
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch
from http.server import ThreadingHTTPServer
import resolver as r


class ResolverTests(unittest.TestCase):
    def test_links(self):
        for value in ('nS4m2eXb4nU', 'https://youtu.be/nS4m2eXb4nU?t=10',
                      'https://www.youtube.com/watch?v=nS4m2eXb4nU&list=anything',
                      'https://m.youtube.com/shorts/nS4m2eXb4nU'):
            self.assertEqual(r.video_id(value), 'nS4m2eXb4nU')

    def test_reject_non_video_and_ssrf(self):
        for value in ('http://127.0.0.1/admin', 'https://youtube.com.evil.test/watch?v=nS4m2eXb4nU',
                      'https://user@youtube.com/watch?v=nS4m2eXb4nU', 'https://youtube.com/playlist?list=example'):
            with self.assertRaises(r.ApiError):
                r.video_id(value)

    def test_upstream_whitelist(self):
        self.assertEqual(r.upstream_url('https://r1.googlevideo.com/videoplayback'), 'https://r1.googlevideo.com/videoplayback')
        for url in ('http://r1.googlevideo.com/video', 'https://googlevideo.com.evil.org/', 'https://127.0.0.1/',
                    'https://r1.googlevideo.com:123/', 'https://token@youtube.com/'):
            with self.assertRaises(r.ApiError):
                r.upstream_url(url)

    def test_bind_is_never_public(self):
        for address in ('127.0.0.1', '192.168.1.10', '100.64.0.2'):
            self.assertEqual(r.private_bind(address), address)
        for address in ('0.0.0.0', '8.8.8.8', '169.254.169.254', '::'):
            with self.assertRaises(argparse.ArgumentTypeError):
                r.private_bind(address)

    def test_manual_before_auto(self):
        manual = {'ext': 'json3', 'url': 'https://www.youtube.com/api/timedtext?lang=ja'}
        auto = {'ext': 'json3', 'url': 'https://www.youtube.com/api/timedtext?lang=ja&kind=asr'}
        info = {'subtitles': {'ja': [manual]}, 'automatic_captions': {'ja-orig': [auto]}}
        self.assertEqual(r.caption_track(info, 'ja'), ('subtitles', 'ja', manual))
        del info['subtitles']
        self.assertEqual(r.caption_track(info, 'ja'), ('automatic_captions', 'ja-orig', auto))

    def test_translations_are_not_japanese_original(self):
        info = {'automatic_captions': {'ja': [{'ext': 'json3', 'url': 'https://www.youtube.com/api/timedtext?lang=en&tlang=ja'}]}}
        self.assertIsNone(r.caption_track(info, 'ja'))

    def test_absolute_timing_escaping_overlap_and_duplicates(self):
        data = {'events': [
            {'tStartMs': 1000, 'dDurationMs': 3000, 'segs': [{'utf8': '今日は <晴れ> & 晴れ'}]},
            {'tStartMs': 2000, 'dDurationMs': 3000, 'segs': [{'utf8': '今日は <晴れ> & 晴れ'}]},
            {'tStartMs': 4000, 'dDurationMs': 2000, 'segs': [{'utf8': '次の文'}]},
            {'tStartMs': 9000, 'dDurationMs': 1000, 'segs': [{'utf8': '\n'}]},
        ]}
        text = r.normalize_captions(data).decode()
        self.assertIn('00:00:01.000 --> 00:00:04.000', text)
        self.assertIn('00:00:04.000 --> 00:00:06.000', text)
        self.assertEqual(text.count('今日は'), 1)
        self.assertIn('&lt;晴れ&gt; &amp;', text)

    def test_zero_duration_and_empty_captions_rejected(self):
        with self.assertRaises(r.ApiError):
            r.normalize_captions({'events': []})

    def test_select_japanese_audio_and_720p(self):
        info = dict(id='nS4m2eXb4nU', formats=[
            dict(format_id='1080', url='https://x.googlevideo.com/v', protocol='https', vcodec='avc1', acodec='none', height=1080),
            dict(format_id='720', url='https://x.googlevideo.com/v', protocol='https', vcodec='avc1', acodec='none', height=720),
            dict(format_id='en', url='https://x.googlevideo.com/a', protocol='https', vcodec='none', acodec='opus', language='en', abr=160),
            dict(format_id='ja', url='https://x.googlevideo.com/a', protocol='https', vcodec='none', acodec='opus', language='ja', abr=100),
        ])
        video, audio = r.select_formats(info)
        self.assertEqual(video['format_id'], '720')
        self.assertEqual(audio['format_id'], 'ja')

    def test_japanese_separate_track_wins_over_english_progressive(self):
        info = dict(id='nS4m2eXb4nU', formats=[
            dict(format_id='english', url='https://x.googlevideo.com/v', protocol='https', vcodec='avc1', acodec='aac', height=360, language='en'),
            dict(format_id='720', url='https://x.googlevideo.com/v', protocol='https', vcodec='avc1', acodec='none', height=720),
            dict(format_id='ja', url='https://x.googlevideo.com/a', protocol='https', vcodec='none', acodec='opus', language='ja', abr=100),
        ])
        video, audio = r.select_formats(info)
        self.assertEqual(video['format_id'], '720')
        self.assertEqual(audio['format_id'], 'ja')

    def test_expiration_and_bounded_sessions(self):
        with r.LOCK:
            r.SESSIONS.clear()
            for i in range(33):
                r.SESSIONS[str(i)] = {'created': time.monotonic()}
            r.prune_sessions()
            self.assertLess(len(r.SESSIONS), 32)
            r.SESSIONS['expired'] = {'created': time.monotonic() - 6 * 3600 - 1}
        with self.assertRaises(r.ApiError) as caught:
            r.session_for('expired')
        self.assertEqual(caught.exception.status, 410)

    def test_clip_bounds_no_audio_and_missing_ffmpeg(self):
        for start, end in ((0, 31), (-1, 3), (2, 2), (float('nan'), 3)):
            with self.assertRaises(r.ApiError):
                r.clip({}, start, end, "http://127.0.0.1/audio")
        with self.assertRaises(r.ApiError):
            r.clip({'japanese_audio': False}, 0, 3, 'http://127.0.0.1/audio')
        with patch.object(r, 'FFMPEG', None), self.assertRaises(r.ApiError):
            r.clip({'japanese_audio': True}, 0, 3, 'http://127.0.0.1/audio')

    def test_clip_invokes_only_bounded_pcm_not_video_download(self):
        session = {'japanese_audio': True, 'audio': {'url': 'https://x.googlevideo.com/a'}}
        result = type('Result', (), {'stdout': b'\0' * 96000, 'returncode': 0})()
        with patch.object(r, 'FFMPEG', '/fake/ffmpeg'), patch.object(r.subprocess, 'run', return_value=result) as run:
            self.assertEqual(len(r.clip(session, 12, 14, 'http://127.0.0.1/audio')), 96000)
            args = run.call_args.args[0]
            self.assertEqual(args[args.index('-ss') + 1], '12')
            self.assertEqual(args[args.index('-t') + 1], '2')
            self.assertEqual(args[-1], 'pipe:1')

    def test_error_messages_classified_without_signed_urls(self):
        self.assertEqual(r.error_code('Sign in to confirm you are not a bot https://secret'), 'youtube_restricted')
        self.assertEqual(r.error_code('Video unavailable'), 'unavailable')
        self.assertEqual(r.error_code('Connection timed out'), 'network')


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), r.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_health(self):
        with urllib.request.urlopen(self.base + '/health') as response:
            self.assertEqual(json.load(response)['status'], 'ok')

    def test_search_contract(self):
        info = {'entries': [dict(id='nS4m2eXb4nU', title='Japanese', channel='Channel', duration=12)]}
        with patch.object(r, 'extract', return_value=info):
            request = urllib.request.Request(self.base + '/search', data=b'{"query":"Japanese"}', headers={'Content-Type':'application/json'})
            with urllib.request.urlopen(request) as response:
                entry = json.load(response)['results'][0]
                self.assertEqual(entry['durationMs'], 12000)
                self.assertEqual(entry['title'], 'Japanese')

    def test_browser_origin_is_rejected(self):
        request = urllib.request.Request(self.base + '/health', headers={'Origin': 'https://example.com'})
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request)
        caught.exception.close()
        self.assertEqual(caught.exception.code, 403)

    def test_missing_session_readable_error(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(self.base + '/session/missing/ja.vtt')
        with caught.exception as response:
            self.assertEqual(response.code, 410)
            self.assertEqual(json.load(response)['error'], 'expired')

    def test_captions_and_explicit_japanese_confirmation(self):
        with r.LOCK:
            r.SESSIONS['test'] = {'created': time.monotonic(), 'ja': b'WEBVTT\n\n', 'japanese_audio': False, 'audio_unknown': True}
        with urllib.request.urlopen(self.base + '/session/test/ja.vtt') as response:
            self.assertTrue(response.headers['Content-Type'].startswith('text/vtt'))
        req = urllib.request.Request(self.base + '/session/test/japanese-audio', data=b'')
        with urllib.request.urlopen(req) as response:
            self.assertEqual(json.load(response)['status'], 'ok')
        self.assertTrue(r.SESSIONS['test']['japanese_audio'])


if __name__ == '__main__':
    unittest.main()
