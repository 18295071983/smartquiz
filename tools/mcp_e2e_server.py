#!/usr/bin/env python3
"""Minimal MCP Streamable HTTP server for device E2E tests.

Implements just enough JSON-RPC/MCP:
- initialize (returns Mcp-Session-Id)
- notifications/initialized
- tools/list with cursor pagination (3 tools over 2 pages)
- tools/call: echo / add / structured_only
- Bearer auth: test-token
- logs every request so a failing client can be diagnosed

Run: python mcp_e2e_server.py [port]
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "test-token"
SESSION = "e2e-session-1"

TOOLS_PAGE1 = [
    {
        "name": "echo",
        "description": "Echo the given text back. Useful for connectivity tests.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "text to echo"}},
            "required": ["text"],
        },
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "add",
        "description": "Add two numbers and return the sum.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "a": {"type": "number", "description": "first number"},
                "b": {"type": "number", "description": "second number"},
            },
            "required": ["a", "b"],
        },
        "annotations": {"readOnlyHint": True},
    },
]
TOOLS_PAGE2 = [
    {
        "name": "structured_only",
        "description": "Return only structuredContent (no content array).",
        "inputSchema": {"type": "object", "properties": {}},
        "annotations": {"readOnlyHint": True},
    }
]


def log(msg):
    print(msg, flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log("HTTP " + (fmt % args))

    def _json(self, obj, status=200, extra_headers=None):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _empty(self, status=202):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _check_auth(self):
        auth = self.headers.get("Authorization", "")
        if auth != "Bearer " + TOKEN:
            log("AUTH FAIL header=" + auth)
            self._json({"error": "unauthorized"}, status=401)
            return False
        return True

    def do_POST(self):
        if self.path not in ("/mcp", "/mcp/"):
            self._json({"error": "not found"}, status=404)
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception as e:
            self._json({"error": "bad json: " + str(e)}, status=400)
            return

        method = req.get("method")
        rid = req.get("id")
        log("REQ method=%s id=%s session=%s proto=%s" % (
            method, rid,
            self.headers.get("Mcp-Session-Id", ""),
            self.headers.get("MCP-Protocol-Version", "")))

        if not self._check_auth():
            return

        if method == "initialize":
            self._json({
                "jsonrpc": "2.0",
                "id": rid,
                "result": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": "e2e-test-server", "version": "1.0"},
                },
            }, extra_headers={"Mcp-Session-Id": SESSION})
            return

        if method == "notifications/initialized":
            self._empty(202)
            return

        if method == "tools/list":
            params = req.get("params") or {}
            cursor = params.get("cursor") or ""
            if cursor == "page2":
                result = {"tools": TOOLS_PAGE2}
            else:
                result = {"tools": TOOLS_PAGE1, "nextCursor": "page2"}
            self._json({"jsonrpc": "2.0", "id": rid, "result": result})
            return

        if method == "tools/call":
            params = req.get("params") or {}
            name = params.get("name")
            args = params.get("arguments") or {}
            if name == "echo":
                text = str(args.get("text", ""))
                result = {"content": [{"type": "text", "text": "echo:" + text}]}
            elif name == "add":
                try:
                    total = float(args.get("a")) + float(args.get("b"))
                except Exception:
                    result = {"isError": True,
                              "content": [{"type": "text", "text": "a/b must be numbers"}]}
                else:
                    result = {"content": [{"type": "text", "text": str(total)}]}
            elif name == "structured_only":
                result = {"structuredContent": {"answer": 42, "source": "structured"}}
            else:
                result = {"isError": True,
                          "content": [{"type": "text", "text": "unknown tool: " + str(name)}]}
            self._json({"jsonrpc": "2.0", "id": rid, "result": result})
            return

        self._json({
            "jsonrpc": "2.0",
            "id": rid,
            "error": {"code": -32601, "message": "method not found: " + str(method)},
        })
        return

    def do_GET(self):
        if self.path not in ("/mcp", "/mcp/"):
            self._json({"error": "not found"}, status=404)
            return
        if not self._check_auth():
            return
        log("GET SSE notify session=%s proto=%s" % (
            self.headers.get("Mcp-Session-Id", ""),
            self.headers.get("MCP-Protocol-Version", "")))
        body = b'data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}\n\n'
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    log("MCP E2E server listening on 127.0.0.1:%d token=%s session=%s" % (port, TOKEN, SESSION))
    srv.serve_forever()
