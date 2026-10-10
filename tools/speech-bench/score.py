"""Score bench results: % words wrong per language and engine (characters for zh/yue/ja/ko/th),
best engine per language, RAM, and a wrong-language check for European languages.

  python -I score.py <work_dir>/results

Normalisation: Whisper's normalisers (English one for en), Chinese folded to Simplified
(OpenCC t2s), whitespace removed for character-error languages. Split jobs ("x-1", "x-2")
merge into family "x". Wrong-language %: lingua detects each output's language (>= 4 words)
and the same detector runs on the reference text as a baseline for its own error.
"""
import sys, json, glob, os, re, unicodedata
import jiwer
from whisper_normalizer.basic import BasicTextNormalizer
from whisper_normalizer.english import EnglishTextNormalizer
from opencc import OpenCC
from lingua import Language, LanguageDetectorBuilder

res_dir = sys.argv[1]
basic, eng, t2s = BasicTextNormalizer(), EnglishTextNormalizer(), OpenCC("t2s")
CER = {"zh", "yue", "ja", "th", "ko"}
NAMES = {"en_us": "English", "es_419": "Spanish", "fr_fr": "French", "de_de": "German", "it_it": "Italian",
         "pt_br": "Portuguese", "ru_ru": "Russian", "uk_ua": "Ukrainian", "pl_pl": "Polish", "nl_nl": "Dutch",
         "sk_sk": "Slovak", "cs_cz": "Czech", "ro_ro": "Romanian", "hr_hr": "Croatian", "bg_bg": "Bulgarian",
         "fi_fi": "Finnish", "sv_se": "Swedish", "hu_hu": "Hungarian", "et_ee": "Estonian", "da_dk": "Danish",
         "lt_lt": "Lithuanian", "mt_mt": "Maltese", "el_gr": "Greek", "lv_lv": "Latvian", "sl_si": "Slovenian",
         "cmn_hans_cn": "Chinese (Mandarin)", "yue_hant_hk": "Cantonese", "ja_jp": "Japanese", "ko_kr": "Korean",
         "th_th": "Thai", "vi_vn": "Vietnamese", "id_id": "Indonesian", "ms_my": "Malay", "hi_in": "Hindi",
         "fil_ph": "Filipino", "ar_eg": "Arabic", "tr_tr": "Turkish", "fa_ir": "Persian", "he_il": "Hebrew",
         "set:casual_norm": "EN casual (real talk)", "set:chatter": "EN + background chatter"}

def norm(t, iso):
    if iso == "en": return eng(t)
    t = basic(t)
    if iso in ("zh", "yue"): t = t2s.convert(t)
    return t

def score(hyps, iso):
    refs = [norm(h["ref"], iso) for h in hyps]; outs = [norm(h["hyp"], iso) for h in hyps]
    pairs = [(a, b) for a, b in zip(refs, outs) if a.strip()]
    if not pairs: return None
    if iso in CER:
        a = [re.sub(r"\s+", "", x) for x, _ in pairs]; b = [re.sub(r"\s+", "", y) for _, y in pairs]
        return round(100 * jiwer.cer(a, b), 1)
    return round(100 * jiwer.wer([x for x, _ in pairs], [y for _, y in pairs]), 1)

# merge split jobs (c1b-1/2/3 -> c1b, pk3-1/2 -> pk3, wsm-1/2/3 -> wsm, om1b-1/2 -> om1b)
fam = {}
for f in sorted(glob.glob(os.path.join(res_dir, "*.json"))):
    if os.path.basename(f).startswith("_"): continue
    r = json.load(open(f)); key = re.sub(r"-\d+$", "", r["engine"])
    e = fam.setdefault(key, {"ram": 0, "langs": {}})
    e["ram"] = max(e["ram"], r.get("rss_end_mb", r.get("rss_after_load_mb", 0)))
    for code, v in r["langs"].items():
        e["langs"][code] = v

scores = {k: {c: score(v["hyps"], v["iso"]) for c, v in e["langs"].items()} for k, e in fam.items()}
json.dump({"scores": scores, "ram": {k: e["ram"] for k, e in fam.items()}}, open(os.path.join(res_dir, "_scores.json"), "w"), indent=1)

# language-confusion check for auto-detect engines, calibrated against the detector's error on the true text
EU = {"en": Language.ENGLISH, "es": Language.SPANISH, "fr": Language.FRENCH, "de": Language.GERMAN, "it": Language.ITALIAN,
      "pt": Language.PORTUGUESE, "ru": Language.RUSSIAN, "uk": Language.UKRAINIAN, "pl": Language.POLISH, "nl": Language.DUTCH,
      "sk": Language.SLOVAK, "cs": Language.CZECH, "ro": Language.ROMANIAN, "hr": Language.CROATIAN, "bg": Language.BULGARIAN,
      "fi": Language.FINNISH, "sv": Language.SWEDISH, "hu": Language.HUNGARIAN, "et": Language.ESTONIAN, "da": Language.DANISH,
      "lt": Language.LITHUANIAN, "el": Language.GREEK, "lv": Language.LATVIAN, "sl": Language.SLOVENE}
det = LanguageDetectorBuilder.from_languages(*EU.values()).with_preloaded_language_models().build()
def wrong_rate(hyps, iso, field):
    if iso not in EU: return None
    n = bad = 0
    for h in hyps:
        t = h[field].strip()
        if len(t.split()) < 4: continue
        n += 1; bad += det.detect_language_of(t) != EU[iso]
    return round(100 * bad / n) if n else None
conf = {}
for k in fam:  # every engine: only auto-detect (multilingual) ones can confuse, but it's cheap
    conf[k] = {}
    for c, v in fam[k]["langs"].items():
        w, base = wrong_rate(v["hyps"], v["iso"], "hyp"), wrong_rate(v["hyps"], v["iso"], "ref")
        if w is not None: conf[k][c] = (w, base)
json.dump(conf, open(os.path.join(res_dir, "_confusion.json"), "w"), indent=1)

cols = sorted(fam)
order = list(NAMES)
print(f"{'language (error %)':24}" + "".join(f"{c:>11}" for c in cols) + "   best")
for code in order:
    row = {c: scores[c].get(code) for c in cols}
    if all(v is None for v in row.values()): continue
    vals = {c: v for c, v in row.items() if v is not None}; best = min(vals, key=vals.get)
    print(f"{NAMES[code][:24]:24}" + "".join(f"{(str(row[c]) if row[c] is not None else '-'):>11}" for c in cols) + f"   {best}")
print("\nRAM MB:  " + "  ".join(f"{c}={round(fam[c]['ram'])}" for c in cols))
for k, v in conf.items():
    bad = {c: wb for c, wb in v.items() if wb[0] - wb[1] >= 5}
    if bad:
        print(f"\n{k}: wrong-language output % (vs detector error on the correct text), >= 5 pts over base:")
        print("  " + "  ".join(f"{NAMES[c].split()[0]} {w}% (base {b}%)" for c, (w, b) in sorted(bad.items(), key=lambda x: -x[1][0])))
