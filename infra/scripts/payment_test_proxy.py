"""Loopback-only fault injection for disposable emulator payment tests."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import urllib.error
import urllib.request


class PaymentTestProxy:
    def __init__(self, target_port):
        self.injected = False
        self.recovery_reads = 0
        self._lock = threading.Lock()
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def forward(self):
                if not self.path.startswith('/api/v1/'):
                    self.send_error(404)
                    return
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 <= size <= 16384:
                    self.send_error(413)
                    return
                body = self.rfile.read(size) if size else None
                connection = http.client.HTTPConnection('127.0.0.1', target_port, timeout=15)
                try:
                    headers = {key: value for key, value in self.headers.items() if key.lower() not in ('host', 'connection', 'content-length')}
                    connection.request(self.command, self.path, body, headers)
                    response = connection.getresponse()
                    payload = response.read(1_048_577)
                    if len(payload) > 1_048_576:
                        self.send_error(502)
                        return
                    status = response.status
                    with proxy._lock:
                        if self.command == 'POST' and self.path.endswith('/authorise') and status == 200 and not proxy.injected:
                            proxy.injected = True
                            status, payload = 503, b'{"error":{"code":"SERVICE_UNAVAILABLE"},"demo":true}'
                        elif self.command == 'GET' and '/payment-intents/' in self.path and proxy.injected:
                            proxy.recovery_reads += 1
                            if proxy.recovery_reads <= 2:
                                status, payload = 503, b'{"error":{"code":"SERVICE_UNAVAILABLE"},"demo":true}'
                    self.send_response(status)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(payload)))
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    self.wfile.write(payload)
                finally:
                    connection.close()

            do_GET = forward
            do_POST = forward

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_port

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def self_check():
    writes = []

    class Backend(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def respond(self):
            if self.command == 'POST':
                writes.append(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
            body = b'{"status":"APPROVED"}'
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        do_POST = respond
        do_GET = respond

    backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
    thread = threading.Thread(target=backend.serve_forever, daemon=True)
    thread.start()
    proxy = PaymentTestProxy(backend.server_port)
    try:
        request = urllib.request.Request(f'http://127.0.0.1:{proxy.port}/api/v1/merchants/test/payment-intents/test/authorise', data=b'fixture-proof')
        try:
            urllib.request.urlopen(request, timeout=3)
            raise AssertionError('Expected lost authorisation outcome')
        except urllib.error.HTTPError as error:
            assert error.code == 503
        for _ in range(2):
            try:
                urllib.request.urlopen(f'http://127.0.0.1:{proxy.port}/api/v1/merchants/test/payment-intents/test', timeout=3)
                raise AssertionError('Expected temporary status outage')
            except urllib.error.HTTPError as error:
                assert error.code == 503
        with urllib.request.urlopen(f'http://127.0.0.1:{proxy.port}/api/v1/merchants/test/payment-intents/test', timeout=3) as result:
            assert result.read() == b'{"status":"APPROVED"}'
        assert writes == [b'fixture-proof']
        assert proxy.injected and proxy.recovery_reads == 3
    finally:
        proxy.close()
        backend.shutdown()
        backend.server_close()
        thread.join(timeout=5)
    print('Payment fault proxy self-check passed')


if __name__ == '__main__':
    self_check()
