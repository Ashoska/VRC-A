#!/usr/bin/env python3
"""Client for a running Cardinal Lab (tools/cardinal-lab/lab.sh interactive). Stdlib only.

  labctl.py say alice "hey @Cardinal how's your day"     one message (waits for the bot)
  labctl.py script FILE|-                                  run script lines (see lab README)
  labctl.py state | transcript [channel] [n] | calls [n] [kind] | prompt [call#|reply|observe|director]
  labctl.py events [n] | config key=value ... | report | quit | wait-ready [seconds]
Port: $LAB_PORT (default 18750).
"""
import json, os, sys, time, urllib.error, urllib.request

PORT = int(os.environ.get("LAB_PORT", "18750"))
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # localhost: never via a proxy


def call(method, path, body=None, ctype="text/plain; charset=utf-8", timeout=900):
    data = body.encode("utf-8") if isinstance(body, str) else body
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=data, method=method,
                                 headers={"Content-Type": ctype})
    with OPENER.open(req, timeout=timeout) as r:
        return r.read().decode("utf-8")


def main(argv):
    if not argv:
        print(__doc__); return 2
    cmd, args = argv[0], argv[1:]
    if cmd == "say":
        who, text = args[0], " ".join(args[1:])
        print(call("POST", "/script", f"{who}: {text}\n> wait"))
    elif cmd == "script":
        src = sys.stdin.read() if not args or args[0] == "-" else open(args[0], encoding="utf-8").read()
        print(call("POST", "/script", src))
    elif cmd == "state":
        print(call("GET", "/state"))
    elif cmd == "transcript":
        ch = args[0] if args else "general"; n = args[1] if len(args) > 1 else "40"
        print(call("GET", f"/transcript?channel={ch}&n={n}"))
    elif cmd == "calls":
        n = args[0] if args else "10"; kind = f"&kind={args[1]}" if len(args) > 1 else ""
        print(call("GET", f"/calls?last={n}{kind}"))
    elif cmd == "prompt":
        a = args[0] if args else "reply"
        print(call("GET", f"/prompt?n={a}" if a.isdigit() else f"/prompt?kind={a}"))
    elif cmd == "events":
        print(call("GET", f"/events?n={args[0] if args else 60}"))
    elif cmd == "config":
        o = {}
        for kv in args:
            k, v = kv.split("=", 1)
            o[k] = (v.lower() in ("1", "true")) if k == "shadow" else (int(v) if v.lstrip("-").isdigit() else v)
        print(call("POST", "/config", json.dumps(o), "application/json"))
    elif cmd == "report":
        print(call("POST", "/report", ""))
    elif cmd == "quit":
        print(call("POST", "/quit", ""))
    elif cmd == "wait-ready":
        deadline = time.time() + float(args[0] if args else 600)
        while time.time() < deadline:
            try:
                print(call("GET", "/health", timeout=5).strip()); return 0
            except (urllib.error.URLError, ConnectionError, OSError):
                time.sleep(2)
        print("lab not ready"); return 1
    else:
        print(__doc__); return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
