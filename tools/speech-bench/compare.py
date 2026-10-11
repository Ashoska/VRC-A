"""Are two tiers really different? Paired bootstrap over the same clips.

  compare.py <results dir> <fleurs lang, e.g. de_de> <engine,engine,...>   (biggest tier first)

Prints each engine's error % on the clips they all have, then each neighbouring pair's
difference with a 95% interval (2,000 resamples of the clips). If the interval includes 0
the two tiers are a tie on this data, and the lighter one should be offered alone (the
picker shows accuracy; two sizes with the same number confuse, user-reported for German).
Engine = results file name (results/<engine>.json); split jobs are named per language.
"""
import sys, os, json, re, random
import jiwer
from whisper_normalizer.basic import BasicTextNormalizer
from whisper_normalizer.english import EnglishTextNormalizer

CER = {"zh", "yue", "ja", "th", "ko"}
basic, eng = BasicTextNormalizer(), EnglishTextNormalizer()
try:
    import opencc; t2s = opencc.OpenCC("t2s")
except Exception:
    t2s = None

def norm(t, iso):
    if iso == "en": return eng(t)
    t = basic(t)
    if iso in ("zh", "yue") and t2s: t = t2s.convert(t)
    return t

def errs(ref, hyp, iso):
    """(edit errors, reference length) for one clip: words, or characters for CER languages."""
    if iso in CER:
        r, h = re.sub(r"\s+", "", ref), re.sub(r"\s+", "", hyp)
        if not r: return None
        o = jiwer.process_characters(r, h or " ")
        return o.substitutions + o.deletions + o.insertions, len(r)
    if not ref.split(): return None
    o = jiwer.process_words(ref, hyp or " ")
    return o.substitutions + o.deletions + o.insertions, len(ref.split())

def main():
    res, lang, engines = sys.argv[1], sys.argv[2], sys.argv[3].split(",")
    per = {}
    for e in engines:
        out = json.load(open(os.path.join(res, e + ".json")))
        L = out["langs"][lang]; iso = L["iso"]
        per[e] = {h["id"]: errs(norm(h["ref"], iso), norm(h["hyp"], iso), iso) for h in L["hyps"]}
    ids = sorted(set.intersection(*(set(k for k, v in per[e].items() if v) for e in engines)))
    rate = lambda e, sel: 100 * sum(per[e][i][0] for i in sel) / sum(per[e][i][1] for i in sel)
    print(f"{lang}: {len(ids)} clips")
    for e in engines: print(f"  {e:12s} {rate(e, ids):5.1f}% wrong")
    rng = random.Random(1)
    for a, b in zip(engines, engines[1:]):
        d = rate(b, ids) - rate(a, ids)
        boots = sorted(rate(b, s) - rate(a, s) for s in ([rng.choice(ids) for _ in ids] for _ in range(2000)))
        lo, hi = boots[50], boots[1949]
        verdict = "TIE (lighter one is as good)" if lo <= 0 <= hi else ("real difference" if lo > 0 else f"{b} is BETTER")
        print(f"  {a} -> {b}: {d:+.1f} pts  95% [{lo:+.1f}, {hi:+.1f}]  {verdict}")

main()
