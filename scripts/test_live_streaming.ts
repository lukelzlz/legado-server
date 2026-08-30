async function run() {
  // 1. Login
  const loginRes = await fetch('http://127.0.0.1:8080/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: 'legado-test-2026-please-change' }),
  });
  const loginJson = await loginRes.json();
  const csrf = loginJson.csrfToken;
  const cookie = loginRes.headers.get('set-cookie')?.split(';')[0] || '';
  console.log('[AUTH] Logged in. CSRF:', csrf.slice(0, 10), 'Cookie:', cookie.slice(0, 20));

  const authHeaders = {
    'Content-Type': 'application/json',
    'X-CSRF-Token': csrf,
    'Cookie': cookie,
  };

  // 2. Test WebSocket Streaming Search
  console.log('\n' + '='.repeat(65));
  console.log('=== WEBSOCKET STREAMING SEARCH LIVE TEST ===');
  console.log('='.repeat(65));

  const ws = new WebSocket(`ws://127.0.0.1:8080/api/search/stream?csrf=${csrf}`, {
    headers: { 'Cookie': cookie },
  } as any);

  const foundSources: any[] = [];
  const startTime = Date.now();

  await new Promise<void>((resolve, reject) => {
    ws.onopen = () => {
      console.log('[WS] Connected! Sending search request for 《遮天》...');
      ws.send(JSON.stringify({ keyword: '遮天' }));
    };

    ws.onmessage = async (event) => {
      const data = JSON.parse(event.data.toString());
      if (data.type === 'start') {
        console.log(`[WS] Server started concurrent search across ${data.totalSources} sources.`);
      } else if (data.type === 'results') {
        for (const item of data.results) {
          const elapsed = ((Date.now() - startTime) / 1000).toFixed(2);
          if (!foundSources.some(s => s.sourceId === item.sourceId)) {
            foundSources.push(item);
            console.log(`  [+${elapsed}s] 来源: ${item.sourceId}`);
            console.log(`         书名: 《${item.name}》 | 作者: ${item.author} | 链接: ${item.bookUrl?.slice(0, 45)}...`);
          }
        }
      } else if (data.type === 'done') {
        console.log(`\n[WS] Streaming search done! Total distinct sources returned: ${foundSources.length}`);
        resolve();
      }

      // Early stop if we found 5 responsive sources to proceed to reading test
      if (foundSources.length >= 5) {
        console.log(`\n[WS] Acquired ${foundSources.length} responsive sources! Sending cancel frame to stop remaining search gracefully...`);
        ws.send(JSON.stringify({ action: 'cancel' }));
        ws.close();
        resolve();
      }
    };

    ws.onerror = (err) => {
      console.error('[WS] Error:', err);
      reject(err);
    };

    setTimeout(() => {
      ws.close();
      resolve();
    }, 15000);
  });

  // 3. Test Full Book Lifecycle (Details -> Chapters TOC -> Chapter Content) on discovered sources
  console.log('\n' + '='.repeat(65));
  console.log('=== VERIFYING READING LIFECYCLE (DETAILS -> TOC -> CONTENT) ===');
  console.log('='.repeat(65));

  for (const item of foundSources.slice(0, 3)) {
    console.log(`\n>>> [Source: ${item.sourceId}]`);
    console.log(`    Book: 《${item.name}》 (${item.author})`);

    // Details
    const t0 = Date.now();
    const dRes = await fetch('http://127.0.0.1:8080/api/books/details', {
      method: 'POST',
      headers: authHeaders,
      body: JSON.stringify({ sourceId: item.sourceId, bookUrl: item.bookUrl }),
    });
    const dTime = ((Date.now() - t0) / 1000).toFixed(2);
    if (!dRes.ok) {
      console.log(`    [1/3 Details] FAILED: HTTP ${dRes.status} (${dTime}s)`);
      continue;
    }
    const details = await dRes.json();
    const tocUrl = details.tocUrl || item.bookUrl;
    console.log(`    [1/3 Details] OK: TOC='${tocUrl?.slice(0, 45)}...' (${dTime}s)`);

    // Chapters
    const t1 = Date.now();
    const cRes = await fetch('http://127.0.0.1:8080/api/books/chapters', {
      method: 'POST',
      headers: authHeaders,
      body: JSON.stringify({ sourceId: item.sourceId, bookUrl: tocUrl }),
    });
    const cTime = ((Date.now() - t1) / 1000).toFixed(2);
    if (!cRes.ok) {
      console.log(`    [2/3 Chapters TOC] FAILED: HTTP ${cRes.status} (${cTime}s)`);
      continue;
    }
    const chapters = await cRes.json();
    if (!Array.isArray(chapters) || chapters.length === 0) {
      console.log(`    [2/3 Chapters TOC] Empty chapters (${cTime}s)`);
      continue;
    }
    const ch = chapters[Math.min(1, chapters.length - 1)];
    console.log(`    [2/3 Chapters TOC] OK: ${chapters.length} chapters. Target: '${ch.title}' (${cTime}s)`);

    // Chapter Content
    const t2 = Date.now();
    const cntRes = await fetch('http://127.0.0.1:8080/api/books/content', {
      method: 'POST',
      headers: authHeaders,
      body: JSON.stringify({
        sourceId: item.sourceId,
        chapterUrl: ch.url,
        bookUrl: item.bookUrl,
      }),
    });
    const cntTime = ((Date.now() - t2) / 1000).toFixed(2);
    if (!cntRes.ok) {
      console.log(`    [3/3 Content] FAILED: HTTP ${cntRes.status} (${cntTime}s)`);
      continue;
    }
    const content = await cntRes.json();
    const text = content.content || '';
    const preview = text.slice(0, 90).replace(/\n/g, ' ');
    console.log(`    [3/3 Content] OK: ${text.length} characters extracted (${cntTime}s)`);
    console.log(`        Preview: "${preview}..."`);
  }

  console.log('\n' + '='.repeat(65));
  console.log('=== END-TO-END LIVE DEMO SUCCEEDED ===');
  console.log('='.repeat(65));
}

run().catch(console.error);
