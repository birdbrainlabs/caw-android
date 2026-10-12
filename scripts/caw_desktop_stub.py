#!/usr/bin/env python3
"""Test desktop for Caw's "Send to computer" (Caw Remote Protocol v1-v4, PROTOCOL.md in the caw-desktop repo).

Stands in for the real Caw Desktop (a Rust app written elsewhere) so the phone side can be tested.
Standard library only. It does not type anything: it prints what the phone sends and answers.

    uv run --python 3.12 scripts/caw_desktop_stub.py          # on the Mac (see below)
    python3 scripts/caw_desktop_stub.py                       # where python3 has TLS 1.3

The Mac's /usr/bin/python3 (3.9, LibreSSL 2.8.3) has no TLS 1.3, so on the Mac run it with uv's
Python 3.12 as above. Any CPython 3.8+ built against OpenSSL 1.1.1 or newer works.

What it does:
- First run: makes an EC P-256 key and a self-signed certificate with `openssl` next to this file
  (caw_desktop_stub.key.pem / .cert.pem). desktop_id = first 16 bytes of SHA-256(cert DER), hex.
- Listens on 47820 (TLS 1.3 only), asks for a client certificate and accepts any; the phone is
  checked by fingerprint (pairing proof the first time, the trusted-phones file afterwards).
- Pairing is open for 2 minutes at start (a fresh code each time; press Enter to open it again).
  Prints the code as XXXX-XXXX-XXXX-XXXX and the caw://pair URL; writes the QR as PNG when the
  `qrcode` module happens to be installed. Five wrong codes end pairing mode.
- Trusted phones are kept in caw_desktop_stub.trusted.json next to this file.
- hello (v1 to v4) -> hello_ok; text/key -> printed, ack; ping -> pong; unknown type -> error unknown_type.
- v2 touchpad: pointer_move and scroll are summed and printed at most once per 250 ms
  (">>> MOVE x14 sum dx=31.2 dy=-4.5"), click is printed (">>> CLICK left down"); no ack unless a
  click carries an id. --no-pointer answers the first pointer message of each connection with
  error pointer_unavailable, as a desktop that cannot move the mouse does.
- v3 settings: settings_get -> settings (kept in memory for the stub's run, starting empty with
  speed 1.5 and acceleration 0.05); settings_set is checked against the desktop's limits and
  answered ack or error "invalid_settings: ..." (printed as ">>> SETTINGS ...").
- v4 shortcuts: settings carry `shortcuts` (starting empty); settings_set replaces the whole list
  (at most 48; name 1-30 characters after trimming). key may carry mods (super, ctrl, alt, shift),
  printed as ">>> KEY id=7: super+enter x1"; an unknown key or modifier, or one held twice, is
  answered error unknown_key. --v3 leaves shortcuts out of settings, as an older desktop does.
- Advertises _caw._tcp with `dns-sd -R` (macOS) or `avahi-publish-service` (Linux) when present.

Options: --port N, --name NAME, --code CODE (fixed pairing code, for tests), --no-pairing,
--no-mdns, --pair-seconds N, --no-pointer, --v3.
"""

import argparse
import base64
import ctypes
import datetime
import hashlib
import hmac
import json
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
KEY_FILE = os.path.join(HERE, "caw_desktop_stub.key.pem")
CERT_FILE = os.path.join(HERE, "caw_desktop_stub.cert.pem")
TRUSTED_FILE = os.path.join(HERE, "caw_desktop_stub.trusted.json")
QR_FILE = os.path.join(HERE, "caw_desktop_stub.qr.png")

CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
KEYS = ({"backspace", "delete", "enter", "tab", "escape", "left", "right", "up", "down", "home", "end",
         "space", "page_up", "page_down", "insert", "minus", "equal", "comma", "period", "slash", "semicolon",
         "apostrophe", "bracket_left", "bracket_right", "backslash", "grave"}
        | set("abcdefghijklmnopqrstuvwxyz0123456789") | {"f%d" % n for n in range(1, 13)})
MODS = ("super", "ctrl", "alt", "shift")
MAX_SHORTCUTS = 48
SILENCE_S = 45
MAX_FAILURES = 5
VERSIONS = (1, 2, 3, 4)
BUTTONS = {"left", "right", "middle"}
POINTER_PRINT_S = 0.25

print_lock = threading.Lock()


def say(*parts):
    stamp = datetime.datetime.now().strftime("%H:%M:%S")
    with print_lock:
        print(stamp, *parts, flush=True)


# ---- identity ----

def ensure_identity():
    if os.path.exists(KEY_FILE) and os.path.exists(CERT_FILE):
        return
    openssl = shutil.which("openssl")
    if not openssl:
        sys.exit("needs the `openssl` command to make the desktop certificate the first time")
    subprocess.run([openssl, "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", KEY_FILE],
                   check=True)
    subprocess.run([openssl, "req", "-new", "-x509", "-key", KEY_FILE, "-out", CERT_FILE,
                    "-days", "3650", "-subj", "/CN=caw-desktop-stub", "-sha256"], check=True)
    os.chmod(KEY_FILE, 0o600)
    say("made a new desktop certificate:", CERT_FILE)


def own_cert_der():
    with open(CERT_FILE) as f:
        return ssl.PEM_cert_to_DER_cert(f.read())


def fingerprint(der):
    return hashlib.sha256(der).digest()


def short_id(fp):
    return fp[:16].hex()


# ---- pairing code and proofs (PROTOCOL.md "Pairing") ----

def normalize_code(code):
    return code.upper().replace("-", "").replace(" ", "").replace("O", "0").replace("I", "1").replace("L", "1")


def new_code():
    return "".join(secrets.choice(CROCKFORD) for _ in range(16))


def show_code(code):
    return "-".join(code[i:i + 4] for i in range(0, 16, 4))


def proof(code, label, d, p):
    key = normalize_code(code).encode("utf-8")
    return hmac.new(key, label + d + p, hashlib.sha256).digest()


# ---- trusted phones ----

trusted_lock = threading.Lock()


def load_trusted():
    try:
        with open(TRUSTED_FILE) as f:
            return json.load(f).get("phones", [])
    except FileNotFoundError:
        return []


def trust_phone(device_id, fp_hex, name):
    with trusted_lock:
        phones = [p for p in load_trusted() if p.get("fingerprint") != fp_hex]
        phones.append({"device_id": device_id, "fingerprint": fp_hex, "device_name": name,
                       "paired_at": datetime.datetime.now().isoformat(timespec="seconds")})
        tmp = TRUSTED_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"phones": phones}, f, indent=2)
            f.write("\n")
        os.replace(tmp, TRUSTED_FILE)


def trusted_phone(fp_hex):
    with trusted_lock:
        for p in load_trusted():
            if p.get("fingerprint") == fp_hex:
                return p
    return None


# ---- TLS: ask for a client certificate and accept any ----

_accept_any = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_int, ctypes.c_void_p)(lambda ok, store: 1)


class _DlInfo(ctypes.Structure):
    _fields_ = [("fname", ctypes.c_char_p), ("fbase", ctypes.c_void_p),
                ("sname", ctypes.c_char_p), ("saddr", ctypes.c_void_p)]


def _ssl_image():
    """Path of the binary that holds Python's _ssl module (its own .so, or libpython if built in)."""
    import _ssl
    path = getattr(_ssl, "__file__", None)
    if path:
        return path, None
    me = ctypes.CDLL(None)
    info = _DlInfo()
    me.dladdr(ctypes.cast(ctypes.pythonapi.Py_Initialize, ctypes.c_void_p), ctypes.byref(info))
    return info.fname.decode(), info.fbase


def _openssl_function(name, image, base):
    """OpenSSL's `name` as linked into Python's _ssl: exported, or (static, hidden) found with nm."""
    try:
        return ctypes.cast(getattr(ctypes.CDLL(image), name), ctypes.c_void_p).value
    except (AttributeError, OSError):
        pass
    if base is None or not shutil.which("nm"):
        return None
    table = {}
    for line in subprocess.run(["nm", image], capture_output=True, text=True).stdout.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1] in "tT":
            table.setdefault(parts[2].lstrip("_"), int(parts[0], 16))
    if name not in table or "Py_Initialize" not in table:
        return None
    # nm prints link-time addresses; the loader slid the image. Py_Initialize is exported, so its
    # real address gives the slide.
    slide = ctypes.cast(ctypes.pythonapi.Py_Initialize, ctypes.c_void_p).value - table["Py_Initialize"]
    return table[name] + slide


def accept_any_client_cert(ctx):
    """
    Python's ssl module can ask for a client certificate (CERT_OPTIONAL) but then insists that it
    chains to a known CA, and Caw's phones present self-signed certificates. The protocol checks the
    phone by fingerprint instead, so install an OpenSSL verify callback that accepts every
    certificate. Python gives no handle on its SSL_CTX, so it is read from the context object
    (PySSLContext: PyObject_HEAD, then SSL_CTX *ctx); OpenSSL's functions are looked up in the
    binary that holds _ssl (uv's Python links OpenSSL statically, so there they come from `nm`).
    Everything is checked by toggling the verify mode before it is trusted.
    """
    image, base = _ssl_image()
    set_addr = _openssl_function("SSL_CTX_set_verify", image, base)
    get_addr = _openssl_function("SSL_CTX_get_verify_mode", image, base)
    if not set_addr or not get_addr:
        sys.exit("cannot reach OpenSSL's SSL_CTX_set_verify inside %s" % image)
    set_verify = ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p)(set_addr)
    get_mode = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p)(get_addr)
    head = ctypes.sizeof(ctypes.c_ssize_t) + ctypes.sizeof(ctypes.c_void_p)  # ob_refcnt, ob_type
    ptr = ctypes.c_void_p.from_address(id(ctx) + head).value
    ctx.verify_mode = ssl.CERT_NONE
    mode_none = get_mode(ptr)
    ctx.verify_mode = ssl.CERT_OPTIONAL
    mode_peer = get_mode(ptr)
    if mode_none != 0 or mode_peer != 1:
        sys.exit("could not find this Python's SSL_CTX (unexpected layout); try uv's Python 3.12")
    set_verify(ptr, 1, ctypes.cast(_accept_any, ctypes.c_void_p))  # SSL_VERIFY_PEER, accept all


def server_context():
    if not ssl.HAS_TLSv1_3:
        sys.exit("this Python's ssl (%s) has no TLS 1.3. On the Mac run:\n"
                 "    uv run --python 3.12 scripts/caw_desktop_stub.py" % ssl.OPENSSL_VERSION)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.load_cert_chain(CERT_FILE, KEY_FILE)
    accept_any_client_cert(ctx)
    return ctx


# ---- pairing mode ----

class Pairing:
    def __init__(self, fixed_code, seconds):
        self.fixed_code = fixed_code
        self.seconds = seconds
        self.lock = threading.Lock()
        self.code = None
        self.until = 0.0
        self.failures = 0

    def open(self, host, port, name):
        with self.lock:
            self.code = normalize_code(self.fixed_code) if self.fixed_code else new_code()
            self.until = time.time() + self.seconds
            self.failures = 0
            code = self.code
        url = "caw://pair?" + urllib.parse.urlencode({"h": host, "p": port, "c": code, "n": name},
                                                       quote_via=urllib.parse.quote)
        say("PAIRING OPEN for %d s" % self.seconds)
        say("  code: %s" % show_code(code))
        say("  url:  %s" % url)
        try:
            import qrcode  # optional
            qrcode.make(url).save(QR_FILE)
            say("  qr:   %s" % QR_FILE)
        except ImportError:
            pass
        except Exception as e:  # pragma: no cover - optional extra
            say("  (could not write the QR: %s)" % e)

    def current(self):
        with self.lock:
            if self.code and time.time() < self.until:
                return self.code
            return None

    def failed(self):
        with self.lock:
            self.failures += 1
            if self.failures >= MAX_FAILURES:
                self.code = None
                return True
            return False

    def close(self):
        with self.lock:
            self.code = None


# ---- one connection ----

class DeltaSum:
    """Sums pointer_move (or scroll) deltas and prints them at most once per 250 ms."""

    def __init__(self, label):
        self.label = label
        self.lock = threading.Lock()
        self.n = 0
        self.dx = 0.0
        self.dy = 0.0
        self.last_print = 0.0
        self.timer = None

    def add(self, dx, dy):
        with self.lock:
            self.n += 1
            self.dx += dx
            self.dy += dy
            due = time.monotonic() - self.last_print >= POINTER_PRINT_S
            if not due and self.timer is None:
                # Print what is left once the 250 ms are up, even if no more moves come.
                self.timer = threading.Timer(POINTER_PRINT_S, self.flush)
                self.timer.daemon = True
                self.timer.start()
        if due:
            self.flush()

    def flush(self):
        with self.lock:
            if self.timer is not None:
                self.timer.cancel()
                self.timer = None
            if self.n == 0:
                return
            line = ">>> %s x%d sum dx=%.1f dy=%.1f" % (self.label, self.n, self.dx, self.dy)
            self.n, self.dx, self.dy = 0, 0.0, 0.0
            self.last_print = time.monotonic()
        say(line)


class Conn:
    def __init__(self, sock, addr, ctx, pairing, name, own_fp, no_pointer=False):
        self.sock = sock
        self.addr = "%s:%d" % addr[:2]
        self.ctx = ctx
        self.pairing = pairing
        self.name = name
        self.own_fp = own_fp
        self.buf = b""
        self.tls = None
        self.no_pointer = no_pointer
        self.told_no_pointer = False
        self.moves = DeltaSum("MOVE")
        self.scrolls = DeltaSum("SCROLL")

    def send(self, obj):
        line = json.dumps(obj, separators=(",", ":"), ensure_ascii=False) + "\n"
        self.tls.sendall(line.encode("utf-8"))
        say("  ->", line.rstrip("\n"))

    def lines(self):
        while True:
            while b"\n" in self.buf:
                line, self.buf = self.buf.split(b"\n", 1)
                if line.strip():
                    yield line.decode("utf-8", errors="replace")
            chunk = self.tls.recv(65536)
            if not chunk:
                return
            self.buf += chunk

    def run(self):
        try:
            self.sock.settimeout(SILENCE_S)
            self.tls = self.ctx.wrap_socket(self.sock, server_side=True)
            peer = self.tls.getpeercert(binary_form=True)
            if not peer:
                say(self.addr, "no client certificate; closing")
                return
            phone_fp = fingerprint(peer)
            say(self.addr, "TLS %s, phone fingerprint %s" % (self.tls.version(), phone_fp.hex()))
            self.session(phone_fp)
        except socket.timeout:
            say(self.addr, "45 s of silence; dropping")
        except (ssl.SSLError, OSError) as e:
            say(self.addr, "connection ended: %s" % e)
        finally:
            self.moves.flush()
            self.scrolls.flush()
            try:
                (self.tls or self.sock).close()
            except OSError:
                pass
            say(self.addr, "closed")

    def session(self, phone_fp):
        lines = self.lines()
        ready = False
        for line in lines:
            try:
                msg = json.loads(line)
                kind = msg.get("type") if isinstance(msg, dict) else None
            except ValueError:
                say(self.addr, "<-", line)
                self.send({"type": "error", "reason": "bad_json"})
                continue
            if kind not in ("pointer_move", "scroll"):
                # Moves and scrolls come up to ~80 a second: they are summed in handle() instead.
                say(self.addr, "<-", line)
            if not ready:
                if kind == "pair_request":
                    if not self.pair(msg, phone_fp):
                        return
                    continue
                if kind == "hello":
                    if msg.get("v", 1) not in VERSIONS:
                        say(self.addr, "hello v=%r: this stub speaks v1, v2 and v3; disconnecting" % msg.get("v"))
                        return
                    phone = trusted_phone(phone_fp.hex())
                    if phone is None:
                        say(self.addr, "unknown phone; disconnecting")
                        return
                    if msg.get("device_id") != short_id(phone_fp):
                        say(self.addr, "hello device_id does not match the certificate; disconnecting")
                        return
                    self.send({"type": "hello_ok", "desktop_name": self.name})
                    say(self.addr, "session ready with %s (v%s)" % (phone.get("device_name"), msg.get("v", 1)))
                    ready = True
                    continue
                say(self.addr, "expected pair_request or hello; disconnecting")
                return
            self.handle(msg, kind)

    def pair(self, msg, phone_fp):
        code = self.pairing.current()
        if code is None:
            self.send({"type": "pair_error", "reason": "not_pairing"})
            return False
        if msg.get("device_id") != short_id(phone_fp):
            self.send({"type": "pair_error", "reason": "bad_device_id"})
            return False
        try:
            got = base64.b64decode(msg.get("proof", ""), validate=True)
        except (ValueError, TypeError):
            got = b""
        want = proof(code, b"caw-pair-phone", self.own_fp, phone_fp)
        if not hmac.compare_digest(got, want):
            self.send({"type": "pair_error", "reason": "bad_code"})
            if self.pairing.failed():
                say("five wrong codes: pairing mode ended (press Enter for a new code)")
            return False
        trust_phone(msg.get("device_id"), phone_fp.hex(), msg.get("device_name", ""))
        self.pairing.close()
        answer = base64.b64encode(proof(code, b"caw-pair-desktop", self.own_fp, phone_fp)).decode()
        self.send({"type": "pair_ok", "desktop_id": short_id(self.own_fp), "desktop_name": self.name,
                   "proof": answer})
        say(self.addr, "PAIRED with %r" % msg.get("device_name"))
        return True

    def handle(self, msg, kind):
        mid = msg.get("id")
        if kind == "ping":
            self.send({"type": "pong"})
        elif kind == "pong":
            pass
        elif kind == "text":
            text = msg.get("text")
            if not isinstance(text, str):
                self.send({"type": "error", "id": mid, "reason": "bad_text"})
                return
            say(">>> TEXT id=%s: %r" % (mid, text))
            self.send({"type": "ack", "id": mid})
        elif kind == "key":
            key, count, mods = msg.get("key"), msg.get("count", 1), msg.get("mods", [])
            if not mods_ok(mods) or key not in KEYS:
                self.send({"type": "error", "id": mid, "reason": "unknown_key"})
                return
            if not isinstance(count, int) or count < 1:
                self.send({"type": "error", "id": mid, "reason": "bad_key"})
                return
            say(">>> KEY id=%s: %s x%d" % (mid, "+".join([m for m in MODS if m in mods] + [key]), count))
            self.send({"type": "ack", "id": mid})
        elif kind in ("pointer_move", "scroll", "click"):
            self.pointer(msg, kind, mid)
        elif kind == "settings_get":
            say(">>> SETTINGS get id=%s" % mid)
            shown = {k: v for k, v in SETTINGS.items() if not (OLD_V3 and k == "shortcuts")}
            self.send(dict({"type": "settings", "id": mid, "desktop_name": self.name, "desktop_version": "stub"}, **shown))
        elif kind == "settings_set":
            problem = settings_problem(msg)
            if problem:
                say(">>> SETTINGS set id=%s refused: %s" % (mid, problem))
                self.send({"type": "error", "id": mid, "reason": "invalid_settings: " + problem})
                return
            if "word_fixes" in msg:
                SETTINGS["word_fixes"] = {k.strip().lower(): v for k, v in msg["word_fixes"].items()}
            if "shortcuts" in msg:
                SETTINGS["shortcuts"] = [{"name": s["name"].strip(), "key": s["key"], "mods": [m for m in MODS if m in s.get("mods", [])]}
                                         for s in msg["shortcuts"]]
            for key in ("pointer_speed", "pointer_accel"):
                if key in msg:
                    SETTINGS[key] = float(msg[key])
            say(">>> SETTINGS set id=%s: %d word fixes, %d shortcuts, speed %s, accel %s" % (
                mid, len(SETTINGS["word_fixes"]), len(SETTINGS["shortcuts"]), SETTINGS["pointer_speed"],
                SETTINGS["pointer_accel"]))
            self.send({"type": "ack", "id": mid})
        else:
            reply = {"type": "error", "reason": "unknown_type"}
            if mid is not None:
                reply["id"] = mid
            self.send(reply)


    def pointer(self, msg, kind, mid):
        """v2 touchpad messages: no ack (a click with an id gets one)."""
        if self.no_pointer:
            if not self.told_no_pointer:
                self.told_no_pointer = True
                self.send({"type": "error", "reason": "pointer_unavailable"})
            return
        if kind == "click":
            self.moves.flush()
            self.scrolls.flush()
            button, action = msg.get("button"), msg.get("action")
            if button not in BUTTONS or action not in (None, "down", "up"):
                reply = {"type": "error", "reason": "bad_click"}
                if mid is not None:
                    reply["id"] = mid
                self.send(reply)
                return
            say(">>> CLICK %s%s" % (button, " " + action if action else ""))
            if mid is not None:
                self.send({"type": "ack", "id": mid})
            return
        dx, dy = msg.get("dx", 0), msg.get("dy", 0)
        if not all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in (dx, dy)):
            say(self.addr, "<-", json.dumps(msg), "(bad dx/dy, ignored)")
            return
        (self.moves if kind == "pointer_move" else self.scrolls).add(float(dx), float(dy))


# ---- v3 settings (in memory) ----

SETTINGS = {"word_fixes": {}, "pointer_speed": 1.5, "pointer_accel": 0.05, "shortcuts": []}
OLD_V3 = False  # --v3: no shortcuts in settings, as a desktop older than v4


def mods_ok(mods):
    return isinstance(mods, list) and all(m in MODS for m in mods) and len(set(mods)) == len(mods)


def settings_problem(msg):
    """The desktop's limits for settings_set; None when it is fine."""
    def number(v):
        return isinstance(v, (int, float)) and not isinstance(v, bool)
    if "pointer_speed" in msg and not (number(msg["pointer_speed"]) and 0.1 <= msg["pointer_speed"] <= 10):
        return "pointer_speed must be 0.1-10"
    if "pointer_accel" in msg and not (number(msg["pointer_accel"]) and 0 <= msg["pointer_accel"] <= 1):
        return "pointer_accel must be 0-1"
    if "word_fixes" in msg:
        fixes = msg["word_fixes"]
        if not isinstance(fixes, dict):
            return "word_fixes must be an object"
        if len(fixes) > 500:
            return "at most 500 word fixes"
        for k, v in fixes.items():
            if not isinstance(v, str) or not k.strip() or len(k) > 100 or len(v) > 100:
                return "bad word fix %r" % k
    if "shortcuts" in msg:
        if OLD_V3:
            return "shortcuts are not supported"
        shortcuts = msg["shortcuts"]
        if not isinstance(shortcuts, list):
            return "shortcuts must be a list"
        if len(shortcuts) > MAX_SHORTCUTS:
            return "at most %d shortcuts" % MAX_SHORTCUTS
        for s in shortcuts:
            if not isinstance(s, dict) or not isinstance(s.get("name"), str):
                return "bad shortcut"
            name = s["name"].strip()
            if not 1 <= len(name) <= 30:
                return "shortcut names must be 1-30 characters"
            if s.get("key") not in KEYS or not mods_ok(s.get("mods", [])):
                return "bad shortcut %r" % name
    return None


# ---- network helpers ----

def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; picks the outgoing interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def advertise(name, port, desktop_id):
    txt = ["id=" + desktop_id, "name=" + name, "v=1"]
    if shutil.which("dns-sd"):
        cmd = ["dns-sd", "-R", name, "_caw._tcp", "local", str(port)] + txt
    elif shutil.which("avahi-publish-service"):
        cmd = ["avahi-publish-service", name, "_caw._tcp", str(port)] + txt
    else:
        say("no dns-sd / avahi-publish-service: not advertising _caw._tcp")
        return None
    say("advertising _caw._tcp as %r (%s)" % (name, cmd[0]))
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    ap = argparse.ArgumentParser(description="Test desktop for Caw's Send to computer.")
    ap.add_argument("--port", type=int, default=47820)
    ap.add_argument("--name", default=socket.gethostname().split(".")[0])
    ap.add_argument("--code", help="fixed pairing code instead of a random one (tests)")
    ap.add_argument("--pair-seconds", type=int, default=120)
    ap.add_argument("--no-pairing", action="store_true", help="do not open pairing at start")
    ap.add_argument("--no-mdns", action="store_true")
    ap.add_argument("--no-pointer", action="store_true",
                    help="answer touchpad messages with error pointer_unavailable (once per connection)")
    ap.add_argument("--v3", action="store_true", help="leave shortcuts out of settings, as a desktop older than v4")
    args = ap.parse_args()
    global OLD_V3
    OLD_V3 = args.v3

    ensure_identity()
    ctx = server_context()
    own_fp = fingerprint(own_cert_der())
    desktop_id = short_id(own_fp)
    host = lan_ip()
    say("Caw desktop stub %r, desktop_id %s" % (args.name, desktop_id))
    say("fingerprint %s" % own_fp.hex())
    say("listening on %s:%d (TLS 1.3, %s)" % (host, args.port, ssl.OPENSSL_VERSION))
    say("trusted phones: %d (%s)" % (len(load_trusted()), TRUSTED_FILE))

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", args.port))
    server.listen(8)

    mdns = None if args.no_mdns else advertise(args.name, args.port, desktop_id)
    pairing = Pairing(args.code, args.pair_seconds)
    if not args.no_pairing:
        pairing.open(host, args.port, args.name)

    def keyboard():
        for _ in sys.stdin:
            pairing.open(host, args.port, args.name)

    if sys.stdin and sys.stdin.isatty():
        say("press Enter to open pairing with a new code")
        threading.Thread(target=keyboard, daemon=True).start()

    try:
        while True:
            sock, addr = server.accept()
            say("%s:%d connected" % addr[:2])
            conn = Conn(sock, addr, ctx, pairing, args.name, own_fp, args.no_pointer)
            threading.Thread(target=conn.run, daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        if mdns:
            mdns.terminate()
        server.close()


if __name__ == "__main__":
    main()
