"""KOPIS 장애 주입용 스텁. 저장한 fixture XML을 돌려주되 /__mode/<ok|timeout|5xx|broken>으로 응답 방식을 바꾼다."""
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FIXTURES = sys.argv[1]
mode = "ok"
LIST_XML = '<?xml version="1.0" encoding="UTF-8"?><dbs></dbs>'


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global mode
        if self.path.startswith("/__mode/"):
            mode = self.path.rsplit("/", 1)[1]
            return self.send(200, f"mode={mode}")
        if mode == "timeout":
            time.sleep(60)
            return self.send(200, LIST_XML)
        if mode == "5xx":
            return self.send(503, "unavailable")
        if mode == "broken":
            return self.send(200, "<dbs><db><mt20id>")
        parts = self.path.split("?")[0].strip("/").split("/")
        if len(parts) == 2:
            name = f"{'pblprfr' if parts[0] == 'pblprfr' else 'prfplc'}-{parts[1]}.xml"
            return self.send_file(name)
        return self.send_file("pblprfr-list.xml" if "cpage=1" in self.path else None)

    def send_file(self, name):
        if name is None:
            return self.send(200, LIST_XML)
        path = os.path.join(FIXTURES, name)
        if not os.path.exists(path):
            return self.send(404, "not found")
        with open(path, encoding="utf-8") as f:
            self.send(200, f.read())

    def send(self, status, body):
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/xml;charset=UTF-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
