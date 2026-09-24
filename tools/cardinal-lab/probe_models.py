#!/usr/bin/env python3
"""Cheap LIVE checks against Workers AI (stdlib only; needs CF_ACCOUNT_ID + CF_API_TOKEN).

  probe_models.py ping [MODEL ...]
      One tiny call per model (~1 neuron each): is it alive, how fast, what it costs.
      Default list = the models Cardinal uses today + the cheaper candidates.

  probe_models.py replay CALLS.jsonl --models A,B,... [--kind reply|director|observe] [--n 5] [--out FILE]
      Re-send the EXACT prompts a lab run captured (lab-runs/<run>/calls.jsonl) to several models and
      print the answers side by side with tokens, latency and neurons — the controlled way to pick a
      cheaper model without guessing about naturalness. Nothing in the app changes.
"""
import argparse, json, os, sys, time, urllib.error, urllib.request

PRICES = {  # neurons per 1M tokens (in, out)
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast": (26668, 204805),
    "@cf/meta/llama-3.1-8b-instruct": (25608, 75147),
    "@cf/meta/llama-3.1-8b-instruct-fp8": (13778, 26128),
    "@cf/meta/llama-3.1-8b-instruct-fp8-fast": (4119, 34868),
    "@cf/meta/llama-3.1-8b-instruct-fast": (None, None),
    "@cf/meta/llama-3.2-3b-instruct": (4625, 30475),
    "@cf/meta/llama-4-scout-17b-16e-instruct": (24545, 77273),
    "@cf/mistralai/mistral-small-3.1-24b-instruct": (31876, 50488),
    "@cf/google/gemma-4-26b-a4b-it": (9091, 27273),
    "@cf/qwen/qwen3-30b-a3b-fp8": (4625, 30475),
    "@cf/zai-org/glm-4.7-flash": (5500, 36400),
    "@cf/ibm-granite/granite-4.0-h-micro": (1542, 10158),
    "@cf/openai/gpt-oss-20b": (18182, 27273),
}
DEFAULT_PING = [
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast",   # REPLY_MODEL
    "@cf/meta/llama-3.1-8b-instruct",             # CHEAP_MODEL (deprecated 2026-05-30?)
    "@cf/meta/llama-3.1-8b-instruct-fp8",
    "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
    "@cf/meta/llama-3.2-3b-instruct",
    "@cf/ibm-granite/granite-4.0-h-micro",
    "@cf/google/gemma-4-26b-a4b-it",
    "@cf/qwen/qwen3-30b-a3b-fp8",
    "@cf/zai-org/glm-4.7-flash",
    "@cf/meta/llama-4-scout-17b-16e-instruct",
]

ACCOUNT = os.environ.get("CF_ACCOUNT_ID", "").strip()
TOKEN = (os.environ.get("CF_API_TOKEN") or os.environ.get("CF_AI_TOKEN") or "").strip()


def run(model, body):
    url = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/ai/run/{model}"
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            raw = json.loads(r.read().decode("utf-8", "replace")); code = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            raw = {"errors": [{"message": raw[:200]}]}
        code = e.code
    ms = int((time.time() - t0) * 1000)
    res = raw.get("result") if isinstance(raw, dict) else None
    text, usage = "", {}
    if isinstance(res, dict):
        usage = res.get("usage") or {}
        text = res.get("response") if isinstance(res.get("response"), str) else json.dumps(res.get("response")) if res.get("response") else ""
        if not text and res.get("choices"):
            text = res["choices"][0].get("message", {}).get("content", "") or ""
    err = None
    if code >= 300 or (isinstance(raw, dict) and raw.get("success") is False):
        err = json.dumps(raw.get("errors") if isinstance(raw, dict) else raw)[:240]
    tin, tout = usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0)
    p = PRICES.get(model, (None, None))
    n = (tin * p[0] + tout * p[1]) / 1e6 if p[0] else None
    return {"model": model, "code": code, "ms": ms, "in": tin, "out": tout, "neurons": n, "text": text or "", "error": err}


def fmt(r):
    n = "%.2fn" % r["neurons"] if r["neurons"] is not None else "?n"
    head = f"{r['model'].split('/')[-1]:34} {r['code']} {r['ms']:6}ms in={r['in']:5} out={r['out']:4} {n}"
    return head + (f"  ERROR {r['error']}" if r["error"] else "")


def cmd_ping(a):
    models = a.models or DEFAULT_PING
    body = {"messages": [{"role": "user", "content": "Reply with just the word OK."}], "max_tokens": 5}
    for m in models:
        r = run(m, body)
        print(fmt(r) + ("" if r["error"] else f"  → {r['text'].strip()[:40]!r}"))


def cmd_replay(a):
    calls = [json.loads(l) for l in open(a.calls, encoding="utf-8")]
    calls = [c for c in calls if c.get("kind") == a.kind][: a.n]
    models = [m.strip() for m in a.models.split(",") if m.strip()]
    lines = []
    for c in calls:
        req = c["request"]
        last = [m for m in req.get("messages", []) if m.get("role") == "user"][-1:]
        lines.append(f"\n=== call #{c['n']} ({c['kind']}) — last user turn: {last[0]['content'][:160] if last else ''!r}")
        for m in models:
            r = run(m, req)
            lines.append(fmt(r))
            lines.append("    " + (r["text"] or "").strip().replace("\n", "\n    ")[:600])
    text = "\n".join(lines) + "\n"
    print(text)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(text)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    pg = sub.add_parser("ping"); pg.add_argument("models", nargs="*")
    rp = sub.add_parser("replay"); rp.add_argument("calls"); rp.add_argument("--models", required=True)
    rp.add_argument("--kind", default="reply"); rp.add_argument("--n", type=int, default=5); rp.add_argument("--out")
    a = p.parse_args()
    if not (ACCOUNT and TOKEN):
        sys.exit("Set CF_ACCOUNT_ID and CF_API_TOKEN (Workers AI) in the environment.")
    {"ping": cmd_ping, "replay": cmd_replay}[a.cmd](a)


if __name__ == "__main__":
    main()
