import sqlite3, json, requests, time

session = requests.Session()
session.trust_env = False
login_resp = session.post("http://127.0.0.1:8080/api/auth/login", json={"password": "legado-test-2026-please-change"}).json()
csrf = login_resp["csrfToken"]
session.headers.update({"X-CSRF-Token": csrf, "Content-Type": "application/json"})

keywords = ["遮天", "剑来", "斗破苍穹", "万相之王"]

print("="*65, flush=True)
print("=== LIVE END-TO-END SEARCH & READING TEST ACROSS ACTIVE SOURCES ===", flush=True)
print("="*65, flush=True)

for kw in keywords:
    print(f"\n>>> Searching Keyword: 《{kw}》", flush=True)
    t0 = time.time()
    s_resp = session.post("http://127.0.0.1:8080/api/search", json={"keyword": kw}, timeout=20)
    dur_s = time.time() - t0
    if s_resp.status_code != 200:
        print(f"  Search Failed: HTTP {s_resp.status_code}", flush=True)
        continue
    results = s_resp.json()
    print(f"  Search returned {len(results)} results across enabled sources in {dur_s:.2f}s", flush=True)
    
    # Test top 2 distinct sources
    tested_sources = set()
    for item in results:
        src = item.get("sourceId")
        if src in tested_sources or len(tested_sources) >= 2:
            continue
        tested_sources.add(src)
        b_name = item.get("name")
        b_author = item.get("author")
        b_url = item.get("bookUrl")
        print(f"\n    [Source: {src}]", flush=True)
        print(f"      Book: 《{b_name}》 by {b_author}", flush=True)
        
        # Details
        t1 = time.time()
        d_resp = session.post("http://127.0.0.1:8080/api/books/details", json={"sourceId": src, "bookUrl": b_url}, timeout=10)
        if d_resp.status_code == 200:
            details = d_resp.json()
            toc_url = details.get("tocUrl") or b_url
            d_name = details.get("name")
            d_author = details.get("author")
            print(f"      Details: OK (Name='{d_name}', Author='{d_author}') ({time.time()-t1:.2f}s)", flush=True)
            
            # Chapters
            t2 = time.time()
            c_resp = session.post("http://127.0.0.1:8080/api/books/chapters", json={"sourceId": src, "bookUrl": toc_url}, timeout=10)
            if c_resp.status_code == 200:
                chaps = c_resp.json()
                print(f"      TOC: OK ({len(chaps)} chapters) ({time.time()-t2:.2f}s)", flush=True)
                
                # Content
                if chaps:
                    ch = chaps[min(2, len(chaps)-1)]
                    t3 = time.time()
                    cnt_resp = session.post("http://127.0.0.1:8080/api/books/content", json={
                        "sourceId": src,
                        "chapterUrl": ch.get("url"),
                        "bookUrl": b_url
                    }, timeout=10)
                    if cnt_resp.status_code == 200:
                        cnt = cnt_resp.json()
                        text = cnt.get("content", "")
                        preview = text[:90].replace("\n", " ")
                        print(f"      Content: OK ({len(text)} chars) ({time.time()-t3:.2f}s)", flush=True)
                        print(f"        -> \"{preview}...\"", flush=True)
                    else:
                        print(f"      Content Failed: HTTP {cnt_resp.status_code}", flush=True)
            else:
                print(f"      TOC Failed: HTTP {c_resp.status_code}", flush=True)
        else:
            print(f"      Details Failed: HTTP {d_resp.status_code}", flush=True)

print("\n" + "="*65, flush=True)
print("=== LIVE TEST COMPLETED ===", flush=True)
print("="*65, flush=True)
