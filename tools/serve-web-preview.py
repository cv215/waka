"""Local web preview of the WAKA app for a phone on the same Wi-Fi (an iPhone through Safari).

Serves apps/mobile/build/web and relays /api/ to the local server on 127.0.0.1:8080, which stays
closed to the network. Test data only: run it at home, stop it with Ctrl+C when done.
Build first:  flutter build web --debug --dart-define=WAKA_ENV=dev --dart-define=WAKA_API_BASE_URL=origin
"""
import http.client
import http.server
import socket
import sys
from pathlib import Path

PORT = 8090
BACKEND = ('127.0.0.1', 8080)
ROOT = Path(__file__).resolve().parent.parent / 'apps' / 'mobile' / 'build' / 'web'
MAX_BODY = 12 * 1024 * 1024
# Origin and Referer are dropped: for the server the relay is a local caller, like the Android emulator.
DROPPED = {'host', 'origin', 'referer', 'connection', 'keep-alive', 'proxy-connection', 'te', 'trailer',
           'transfer-encoding', 'upgrade', 'accept-encoding', 'cookie'}


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def end_headers(self):
        if not self.path.startswith('/api/'):
            self.send_header('Cache-Control', 'no-cache')
        super().end_headers()

    def _relay(self):
        length = int(self.headers.get('Content-Length') or 0)
        if length < 0 or length > MAX_BODY:
            self.send_error(413)
            return
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in DROPPED}
        conn = http.client.HTTPConnection(*BACKEND, timeout=70)
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            reply = conn.getresponse()
            data = reply.read()
        except OSError:
            self.send_error(502, 'Serveur WAKA local injoignable')
            return
        finally:
            conn.close()
        self.send_response_only(reply.status)
        for k, v in reply.getheaders():
            if k.lower() not in {'connection', 'keep-alive', 'transfer-encoding', 'content-length'}:
                self.send_header(k, v)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith('/api/'):
            self._relay()
        else:
            super().do_GET()

    def do_POST(self):
        self._relay() if self.path.startswith('/api/') else self.send_error(405)

    def do_PATCH(self):
        self._relay() if self.path.startswith('/api/') else self.send_error(405)

    def do_PUT(self):
        self._relay() if self.path.startswith('/api/') else self.send_error(405)

    def do_DELETE(self):
        self._relay() if self.path.startswith('/api/') else self.send_error(405)

    def log_message(self, fmt, *args):
        pass  # no paths, numbers or tokens in the console


def home_addresses():
    found = set()
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(('192.168.1.1', 9))  # no packet is sent; only picks the outgoing interface
        found.add(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
        found.add(info[4][0])
    return sorted(a for a in found if a.startswith(('192.168.', '10.')) or
                  (a.startswith('172.') and 16 <= int(a.split('.')[1]) <= 31))


if __name__ == '__main__':
    if not (ROOT / 'index.html').exists():
        sys.exit('Version web absente : construire apps/mobile d abord (voir en tete de ce fichier).')
    server = http.server.ThreadingHTTPServer(('0.0.0.0', PORT), Handler)
    print('Apercu WAKA pret. Sur l iPhone (meme Wi-Fi), ouvrir dans Safari :')
    for address in home_addresses():
        print('   http://%s:%d' % (address, PORT))
    print('Arreter avec Ctrl+C.')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
