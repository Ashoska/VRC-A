#!/usr/bin/env python3
"""See what the PRODUCTION bot actually sent and got back — from Cloudflare AI Gateway logs.

When Cardinal's Config tab has an AI Gateway id set, every Workers AI call it makes is logged by the
gateway (prompt, response, tokens, duration, status). This turns those logs into a readable digest,
so nobody has to screenshot the phone. Stdlib only.

  aig_logs.py list   [--since 6h] [--limit 100] [--errors] [--model SUBSTR] [--lab]
  aig_logs.py show   <log-id>                      full prompt + response of one call
  aig_logs.py digest [--since 24h] [--limit 60] [--out FILE] [--lab]
                                                   markdown: stats by role/model + every call verbatim

Environment: CF_ACCOUNT_ID, CF_GATEWAY_ID and a token with "AI Gateway: Read" in CF_AIG_READ_TOKEN
(falls back to CF_API_TOKEN). Lab traffic (cf-aig-metadata lab=cardinal) is hidden unless --lab.
Role is recognised from the system prompt: reply / director / observe (8B learn + pile-up observer).
"""
import argparse, json, os, sys, time, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

API = "https://api.cloudflare.com/client/v4"
# neurons per 1M tokens (in, out) — keep in sync with Pricing in the lab harness
PRICES = {
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast": (26668, 204805),
    "@cf/meta/llama-3.1-8b-instruct": (25608, 75147),
    "@cf/meta/llama-3.1-8b-instruct-fp8": (13778, 26128),
    "@cf/meta/llama-3.1-8b-instruct-fp8-fast": (4119, 34868),
    "@cf/meta/llama-3.2-3b-instruct": (4625, 30475),
    "@cf/google/gemma-4-26b-a4b-it": (9091, 27273),
    "@cf/qwen/qwen3-30b-a3b-fp8": (4625, 30475),
    "@cf/zai-org/glm-4.7-flash": (5500, 36400),
    "@cf/ibm-granite/granite-4.0-h-micro": (1542, 10158),
    "@cf/meta/llama-4-scout-17b-16e-instruct": (24545, 77273),
    "@cf/mistralai/mistral-small-3.1-24b-instruct": (31876, 50488),
    "@cf/openai/gpt-oss-20b": (18182, 27273),
}


def env(*keys):
    for k in keys:
        v = os.environ.get(k, "").strip()
        if v:
            return v
    return ""


ACCOUNT = env("CF_ACCOUNT_ID")
GATEWAY = env("CF_GATEWAY_ID")
TOKEN = env("CF_AIG_READ_TOKEN", "CF_API_TOKEN")


def get(path, params=None):
    url = f"{API}/accounts/{ACCOUNT}/ai-gateway/gateways/{GATEWAY}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {TOKEN}"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read().decode("utf-8", "replace")
                try:
                    return json.loads(raw)
                except json.JSONDecodeError:
                    return {"raw": raw}
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 3:
                time.sleep(2 ** attempt); continue
            body = e.read().decode("utf-8", "replace")[:300]
            sys.exit(f"Cloudflare API {e.code} on {path}: {body}")
    return {}


def since_arg(s):
    unit = s[-1]; n = float(s[:-1])
    delta = {"m": timedelta(minutes=n), "h": timedelta(hours=n), "d": timedelta(days=n)}[unit]
    return datetime.now(timezone.utc) - delta


def parse_ts(s):
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except Exception:
        return None


def is_lab(row):
    md = row.get("metadata") or ""
    return "cardinal" in md and "lab" in md


def fetch_rows(since, limit, include_lab):
    rows, page = [], 1
    while len(rows) < limit:
        res = get("/logs", {"per_page": 50, "page": page, "order_by": "created_at", "order_by_direction": "desc"})
        batch = res.get("result") or []
        if not batch:
            break
        stop = False
        for r in batch:
            ts = parse_ts(r.get("created_at", ""))
            if ts and ts < since:
                stop = True; break
            if include_lab or not is_lab(r):
                rows.append(r)
        if stop or len(batch) < 50:
            break
        page += 1
    return rows[:limit]


def role_of(req_body):
    try:
        msgs = req_body.get("messages") or []
        sys_prompt = msgs[0].get("content", "") if msgs and msgs[0].get("role") == "system" else ""
    except Exception:
        return "?"
    if sys_prompt.startswith("You direct a Discord chat regular"):
        return "director"
    if sys_prompt.startswith("You quietly keep MEMORY"):
        return "observe"
    if "Reply with ONLY your message" in sys_prompt:
        return "reply"
    return "other"


def model_short(m):
    return (m or "?").split("/")[-1]


def neurons(model, tin, tout):
    p = PRICES.get(model)
    return None if not p else (tin * p[0] + tout * p[1]) / 1e6


def response_text(resp):
    if not isinstance(resp, dict):
        return str(resp)[:500]
    r = resp.get("result", resp)
    if isinstance(r, dict):
        if r.get("response"):
            return r["response"] if isinstance(r["response"], str) else json.dumps(r["response"])
        ch = r.get("choices")
        if ch:
            return ch[0].get("message", {}).get("content", "")
    if resp.get("errors"):
        return "ERROR " + json.dumps(resp["errors"])[:300]
    return json.dumps(resp)[:500]


def cmd_list(a):
    rows = fetch_rows(since_arg(a.since), a.limit, a.lab)
    if a.errors:
        rows = [r for r in rows if not r.get("success")]
    if a.model:
        rows = [r for r in rows if a.model in (r.get("model") or "")]
    for r in rows:
        n = neurons(r.get("model"), r.get("tokens_in", 0), r.get("tokens_out", 0))
        print(f"{r.get('created_at','')[:19]}  {'ok ' if r.get('success') else 'ERR'} {r.get('status_code','')}  "
              f"{model_short(r.get('model')):32} in={r.get('tokens_in',0):5} out={r.get('tokens_out',0):4} "
              f"{int(r.get('duration',0)):6}ms  {('%.1fn' % n) if n is not None else '   ?'}  {r.get('id')}")
    print(f"\n{len(rows)} call(s)")


def cmd_show(a):
    req = get(f"/logs/{a.id}/request")
    resp = get(f"/logs/{a.id}/response")
    print(f"role: {role_of(req)}  model: {req.get('model','(in URL)')}  max_tokens: {req.get('max_tokens')}")
    for m in req.get("messages", []):
        print(f"\n===== {m.get('role')} =====\n{m.get('content')}")
    print(f"\n===== response =====\n{response_text(resp)}")


def cmd_digest(a):
    rows = fetch_rows(since_arg(a.since), a.limit, a.lab)
    out = [f"# Cardinal production digest — last {a.since} ({len(rows)} calls, newest first)\n"]
    stats = {}
    detail = []
    for r in rows:
        req = get(f"/logs/{r['id']}/request")
        resp = get(f"/logs/{r['id']}/response")
        role = role_of(req)
        key = (role, model_short(r.get("model")))
        s = stats.setdefault(key, {"n": 0, "err": 0, "in": 0, "out": 0, "ms": [], "neurons": 0.0})
        s["n"] += 1; s["err"] += 0 if r.get("success") else 1
        s["in"] += r.get("tokens_in", 0); s["out"] += r.get("tokens_out", 0); s["ms"].append(r.get("duration", 0))
        s["neurons"] += neurons(r.get("model"), r.get("tokens_in", 0), r.get("tokens_out", 0)) or 0
        detail.append((r, role, req, resp))
    out.append("| role | model | calls | errors | avg in | avg out | p50 ms | neurons |\n|---|---|---:|---:|---:|---:|---:|---:|")
    for (role, model), s in sorted(stats.items()):
        ms = sorted(s["ms"]); p50 = ms[len(ms) // 2] if ms else 0
        out.append(f"| {role} | {model} | {s['n']} | {s['err']} | {s['in'] // max(1, s['n'])} | {s['out'] // max(1, s['n'])} | {int(p50)} | {s['neurons']:.1f} |")
    for r, role, req, resp in detail:
        out.append(f"\n---\n\n## {r.get('created_at','')[:19]} · {role} · {model_short(r.get('model'))} · "
                   f"{'ok' if r.get('success') else 'ERROR ' + str(r.get('status_code'))} · in={r.get('tokens_in')} out={r.get('tokens_out')} · {int(r.get('duration',0))}ms\n")
        for m in req.get("messages", []):
            out.append(f"**{m.get('role')}**\n```\n{m.get('content')}\n```")
        out.append(f"**→ response**\n```\n{response_text(resp)}\n```")
    text = "\n".join(out) + "\n"
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"wrote {a.out}")
    else:
        print(text)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    l = sub.add_parser("list"); l.add_argument("--since", default="6h"); l.add_argument("--limit", type=int, default=100)
    l.add_argument("--errors", action="store_true"); l.add_argument("--model"); l.add_argument("--lab", action="store_true")
    s = sub.add_parser("show"); s.add_argument("id")
    d = sub.add_parser("digest"); d.add_argument("--since", default="24h"); d.add_argument("--limit", type=int, default=60)
    d.add_argument("--out"); d.add_argument("--lab", action="store_true")
    a = p.parse_args()
    if not (ACCOUNT and GATEWAY and TOKEN):
        sys.exit("Set CF_ACCOUNT_ID, CF_GATEWAY_ID and CF_AIG_READ_TOKEN (AI Gateway: Read) in the environment.")
    {"list": cmd_list, "show": cmd_show, "digest": cmd_digest}[a.cmd](a)


if __name__ == "__main__":
    main()
