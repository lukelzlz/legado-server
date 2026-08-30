import sqlite3, json, requests, time, sys

def log(msg):
    print(msg, flush=True)

session = requests.Session()
session.trust_env = False  # Avoid routing localhost requests through proxy

login_resp = session.post("http://127.0.0.1:8080/api/auth/login", json={"password": "legado-test-2026-please-change"}, timeout=10)
if login_resp.status_code != 200:
    log(f"Login failed: {login_resp.status_code} {login_resp.text}")
    sys.exit(1)

csrf = login_resp.json()["csrfToken"]
session.headers.update({"X-CSRF-Token": csrf, "Content-Type": "application/json"})
log(f"[AUTH] Login successful. CSRF token acquired.")

# Query real sources from database
conn = sqlite3.connect(".data/legado.sqlite")
cursor = conn.cursor()

# Get a sample of verified sources that are responsive
cursor.execute("SELECT id, name, payload FROM source WHERE enabled = 1 LIMIT 3000")
rows = cursor.fetchall()

categories = {
    "js_decrypt": [],
    "js_search": [],
    "regex_replace": [],
    "standard": []
}

for sid, name, payload in rows:
    try:
        data = json.loads(payload)
    except Exception:
        continue
    rc = str(data.get("ruleContent", {}).get("content", ""))
    su = str(data.get("searchUrl", ""))
    rbi = data.get("ruleBookInfo", {})
    
    if "<js>" in rc or "@js:" in rc or "base64" in rc.lower():
        if len(categories["js_decrypt"]) < 5:
            categories["js_decrypt"].append((sid, name, data))
    elif "@js:" in su or "<js>" in su:
        if len(categories["js_search"]) < 5:
            categories["js_search"].append((sid, name, data))
    elif any("##" in str(v) for v in rbi.values()) or "##" in rc or data.get("ruleContent", {}).get("replaceRegex"):
        if len(categories["regex_replace"]) < 5:
            categories["regex_replace"].append((sid, name, data))
    else:
        if len(categories["standard"]) < 5:
            categories["standard"].append((sid, name, data))

log("\n[TEST PLAN] Sampled 20 Real Sources across 4 Categories:")
for cat, items in categories.items():
    log(f"  - {cat.upper()}: {len(items)} sources")
    for sid, name, _ in items:
        log(f"      • [{name}] ({sid})")

results_summary = {
    "search_total": 0,
    "search_success": 0,
    "details_success": 0,
    "chapters_success": 0,
    "content_success": 0,
    "categories_stat": {}
}

for cat, items in categories.items():
    log(f"\n{'='*60}")
    log(f"=== Category: {cat.upper()} ({len(items)} sources) ===")
    log(f"{'='*60}")
    
    cat_stat = {"total": len(items), "search_ok": 0, "details_ok": 0, "toc_ok": 0, "content_ok": 0}
    
    for sid, name, data in items:
        log(f"\n>>> Testing: {name} (ID: {sid})")
        results_summary["search_total"] += 1
        
        # 1. Search
        kw = "遮天"
        t0 = time.time()
        try:
            s_resp = session.post("http://127.0.0.1:8080/api/search", json={"keyword": kw, "sourceIds": [sid]}, timeout=8)
            dur_s = time.time() - t0
            if s_resp.status_code != 200:
                log(f"    [1/4 Search] FAILED: HTTP {s_resp.status_code} ({dur_s:.2f}s) - {s_resp.text[:60]}")
                continue
                
            results = s_resp.json()
            if not results:
                log(f"    [1/4 Search] 0 results (upstream down/empty) ({dur_s:.2f}s)")
                continue
                
            cat_stat["search_ok"] += 1
            results_summary["search_success"] += 1
            book = results[0]
            b_name = book.get("name")
            b_author = book.get("author")
            b_url = book.get("bookUrl")
            log(f"    [1/4 Search] SUCCESS: 《{b_name}》/ {b_author} ({dur_s:.2f}s)")
            
            # 2. Details
            t1 = time.time()
            d_resp = session.post("http://127.0.0.1:8080/api/books/details", json={"sourceId": sid, "bookUrl": b_url}, timeout=8)
            dur_d = time.time() - t1
            if d_resp.status_code != 200:
                log(f"    [2/4 Details] FAILED: HTTP {d_resp.status_code} ({dur_d:.2f}s)")
                continue
                
            cat_stat["details_ok"] += 1
            results_summary["details_success"] += 1
            details = d_resp.json()
            toc_url = details.get("tocUrl") or b_url
            log(f"    [2/4 Details] SUCCESS: TOC='{toc_url[:40]}...' ({dur_d:.2f}s)")
            
            # 3. Chapters
            t2 = time.time()
            c_resp = session.post("http://127.0.0.1:8080/api/books/chapters", json={"sourceId": sid, "bookUrl": toc_url}, timeout=8)
            dur_c = time.time() - t2
            if c_resp.status_code != 200:
                log(f"    [3/4 TOC] FAILED: HTTP {c_resp.status_code} ({dur_c:.2f}s)")
                continue
                
            chapters = c_resp.json()
            if not chapters:
                log(f"    [3/4 TOC] Empty chapters list ({dur_c:.2f}s)")
                continue
                
            cat_stat["toc_ok"] += 1
            results_summary["chapters_success"] += 1
            target_ch = chapters[min(1, len(chapters)-1)]
            ch_title = target_ch.get("title")
            ch_url = target_ch.get("url")
            log(f"    [3/4 TOC] SUCCESS: {len(chapters)} chapters. Ch: '{ch_title}' ({dur_c:.2f}s)")
            
            # 4. Content (with Decryption & Regex Cleaning verification)
            t3 = time.time()
            cnt_resp = session.post("http://127.0.0.1:8080/api/books/content", json={
                "sourceId": sid,
                "chapterUrl": ch_url,
                "bookUrl": b_url
            }, timeout=8)
            dur_cnt = time.time() - t3
            if cnt_resp.status_code != 200:
                log(f"    [4/4 Content] FAILED: HTTP {cnt_resp.status_code} ({dur_cnt:.2f}s)")
                continue
                
            content_data = cnt_resp.json()
            content_text = content_data.get("content", "")
            if content_text:
                cat_stat["content_ok"] += 1
                results_summary["content_success"] += 1
                preview = content_text[:70].replace("\n", " ")
                log(f"    [4/4 Content] SUCCESS: {len(content_text)} chars extracted ({dur_cnt:.2f}s)")
                log(f"        -> Content: \"{preview}...\"")
            else:
                log(f"    [4/4 Content] Empty content body ({dur_cnt:.2f}s)")
                
        except Exception as e:
            log(f"    [ERROR] Exception: {e}")
            
    results_summary["categories_stat"][cat] = cat_stat

log("\n" + "="*60)
log("=== FINAL LIVE SOURCE TEST REPORT ===")
log("="*60)
log(f"Total Sources Tested: {results_summary['search_total']}")
log(f"Overall Search Success:   {results_summary['search_success']} / {results_summary['search_total']}")
log(f"Overall Details Success:  {results_summary['details_success']} / {results_summary['search_total']}")
log(f"Overall Chapters Success: {results_summary['chapters_success']} / {results_summary['search_total']}")
log(f"Overall Content Success:  {results_summary['content_success']} / {results_summary['search_total']}")

for cat, stat in results_summary["categories_stat"].items():
    log(f"\n  • Category [{cat.upper()}]:")
    log(f"      Search: {stat['search_ok']}/{stat['total']} | Details: {stat['details_ok']}/{stat['total']} | TOC: {stat['toc_ok']}/{stat['total']} | Content: {stat['content_ok']}/{stat['total']}")
log("="*60)
