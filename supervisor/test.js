const t = require('node:test');
const a = require('node:assert');
const { semverGt, pickRelease, shapeStatus, imageTags, nextPhase, isTerminal, apkAsset, sha256Matches, recoveryPage, isPublishTemp, pinnedImage } = require('./lib');

t.test('pinnedImage — only digest refs of the expected repository', () => {
  const d = 'a'.repeat(64);
  a.equal(pinnedImage(`ghcr.io/david0ql/dallycontrol-server@sha256:${d}`, 'server'), `ghcr.io/david0ql/dallycontrol-server@sha256:${d}`);
  a.equal(pinnedImage(`ghcr.io/david0ql/dallycontrol-web@sha256:${d}`, 'web'), `ghcr.io/david0ql/dallycontrol-web@sha256:${d}`);
  a.equal(pinnedImage('ghcr.io/david0ql/dallycontrol-server:1.2.3', 'server'), null);            // mutable tag
  a.equal(pinnedImage(`ghcr.io/david0ql/dallycontrol-web@sha256:${d}`, 'server'), null);        // wrong component
  a.equal(pinnedImage(`docker.io/evil/dallycontrol-server@sha256:${d}`, 'server'), null);       // other registry
  a.equal(pinnedImage(`ghcr.io/x/dallycontrol-server@sha256:${d};rm -rf /`, 'server'), null);    // trailing junk
  a.equal(pinnedImage(`ghcr.io/x/dallycontrol-server@sha256:${'A'.repeat(64)}`, 'server'), null);
  a.equal(pinnedImage(null, 'server'), null);
});

t.test('semverGt', () => {
  a.equal(semverGt('1.2.4', '1.2.3'), true);
  a.equal(semverGt('1.2.3', '1.2.3'), false);
  a.equal(semverGt('v2.0.0', 'v1.9.9'), true);
  a.equal(semverGt('1.0.0', '2.0.0'), false);
  a.equal(semverGt('bad', '1.0.0'), false);
});

t.test('pickRelease — newest stable with a manifest', () => {
  const rs = [
    { tag_name: 'v1.0.0', assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.2.0', prerelease: true, assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.1.0', assets: [{ name: 'manifest.json' }] },
    { tag_name: 'v1.3.0', assets: [{ name: 'other' }] },
    { tag_name: 'v9.9.9', draft: true, assets: [{ name: 'manifest.json' }] },
  ];
  a.equal(pickRelease(rs, 'stable').tag_name, 'v1.1.0'); // prerelease/no-manifest/draft excluded
  a.equal(pickRelease(rs, 'beta').tag_name, 'v1.2.0');   // prerelease allowed on beta
  a.equal(pickRelease([], 'stable'), null);
});

t.test('shapeStatus', () => {
  a.equal(shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: true }).updateAvailable, true);
  a.equal(shapeStatus({ current: '1.1.0', manifest: { version: '1.1.0' }, verified: true }).updateAvailable, false);
  a.equal(shapeStatus({ current: '1.0.0', manifest: { version: '1.1.0' }, verified: false }).updateAvailable, false);
});

t.test('imageTags', () => {
  const m = { version: '1.1.0', components: { serverImage: 'ghcr.io/o/dallycontrol-server:1.1.0', webImage: 'ghcr.io/o/dallycontrol-web:1.1.0' } };
  a.deepEqual(imageTags(m), { serverImage: 'ghcr.io/o/dallycontrol-server:1.1.0', webImage: 'ghcr.io/o/dallycontrol-web:1.1.0', version: '1.1.0' });
  a.deepEqual(imageTags(null), { serverImage: null, webImage: null, version: null });
});

t.test('apkAsset', () => {
  const manifest = { version: '1.2.0', components: { apk: { file: 'dallycontrol-agent.apk', versionCode: 120, sha256: 'abc', signatureChecksum: 'x' } } };
  const release = { assets: [{ name: 'dallycontrol-agent.apk', browser_download_url: 'https://gh/dl/dallycontrol-agent.apk' }, { name: 'manifest.json' }] };
  a.deepEqual(apkAsset(release, manifest), { version: '1.2.0', versionCode: 120, sha256: 'abc', url: 'https://gh/dl/dallycontrol-agent.apk' });
  a.equal(apkAsset({ assets: [] }, manifest), null);     // asset not present
  a.equal(apkAsset(release, { version: '1.2.0', components: {} }), null); // no apk block
  a.equal(apkAsset(null, null), null);
});

t.test('sha256Matches — the APK publish gate', () => {
  const buf = Buffer.from('hello');
  const sha = require('crypto').createHash('sha256').update(buf).digest('hex');
  a.equal(sha256Matches(buf, sha), true);
  a.equal(sha256Matches(buf, sha.toUpperCase()), true);   // case-insensitive
  a.equal(sha256Matches(buf, 'deadbeef'), false);         // mismatch → refuse
  a.equal(sha256Matches(buf, ''), false);                 // missing → refuse
  a.equal(sha256Matches(Buffer.from('hellö'), sha), false);
});

t.test('apply phase state machine', () => {
  a.equal(nextPhase('authorizing'), 'backup');
  a.equal(nextPhase('backup'), 'pull');
  a.equal(nextPhase('healthcheck'), 'done');
  a.equal(nextPhase('done'), null);     // end of happy path
  a.equal(nextPhase('rollback'), null); // not on the happy path
  a.equal(isTerminal('done'), true);
  a.equal(isTerminal('rolled_back'), true);
  a.equal(isTerminal('failed'), true);
  a.equal(isTerminal('pull'), false);
  a.equal(isTerminal('rollback'), false);
});

t.test('recoveryPage — marks the page with whether apply/rollback is supported', () => {
  const html = require('fs').readFileSync(require('path').join(__dirname, 'recovery.html'), 'utf8');
  a.equal(html.split('<body>').length, 2, 'recovery.html must have exactly one bare <body> tag to mark');
  const off = recoveryPage(html, false), on = recoveryPage(html, true);
  a.ok(off.includes('<body data-apply="0">'));
  a.ok(on.includes('<body data-apply="1">'));
  a.ok(!off.includes('<body>') && !on.includes('<body>'));
  // The page itself hides the Roll back card and shows the manual steps under data-apply="0" (CSS, no JS needed).
  a.match(html, /body\[data-apply="0"\] #rbcard\{display:none\}/);
  a.match(html, /body:not\(\[data-apply="0"\]\) #manual\{display:none\}/);
  a.ok(html.includes('git pull &amp;&amp; ./setup.sh') && html.includes('git pull &amp;&amp; sudo ./install/install-native.sh'));
});

t.test('recovery.html escapes status strings before they reach innerHTML', () => {
  const html = require('fs').readFileSync(require('path').join(__dirname, 'recovery.html'), 'utf8');
  const m = html.match(/^function esc\(x\)\{.*\}$/m);
  a.ok(m, 'recovery.html defines a one-line function esc(x){…}');
  const esc = new Function(m[0] + '; return esc;')();
  a.equal(esc('<img src=x onerror="a()">&\''), '&lt;img src=x onerror=&quot;a()&quot;&gt;&amp;&#39;');
  a.equal(esc(null), '');
  a.equal(esc(42), '42');
  // Every server/network-derived string concatenated into markup goes through esc() (or v(), which wraps it).
  a.match(html, /function v\(x\)\{return x==null\?'—':esc\(x\)\}/);
  for (const raw of ["'+s.error+'", "'+a.error+'", '(PH[a.phase]||a.phase)', "(a.fromVersion||'?')", "' → '+a.toVersion",
                     "'+(b.error||", "'+e+'"]) {
    a.ok(!html.includes(raw), 'unescaped interpolation left in recovery.html: ' + raw);
  }
});

t.test('isPublishTemp — only publishApk\'s own temp names', () => {
  a.equal(isPublishTemp('agent.apk.0123456789abcdef.tmp', 'agent.apk'), true);
  a.equal(isPublishTemp('agent.apk.0123456789ABCDEF.tmp', 'agent.apk'), false); // randomBytes().toString('hex') is lowercase
  a.equal(isPublishTemp('agent.apk.tmp', 'agent.apk'), false);
  a.equal(isPublishTemp('agent.apk.0123456789abcde.tmp', 'agent.apk'), false);  // 15 hex
  a.equal(isPublishTemp('agent.apk.0123456789abcdef0.tmp', 'agent.apk'), false); // 17 hex
  a.equal(isPublishTemp('agent.apk.0123456789abcdef.tmp.x', 'agent.apk'), false);
  a.equal(isPublishTemp('other.apk.0123456789abcdef.tmp', 'agent.apk'), false);
  a.equal(isPublishTemp('agentXapk.0123456789abcdef.tmp', 'agent.apk'), false);  // the '.' in the name is literal
  a.equal(isPublishTemp('agent.apk.backup', 'agent.apk'), false);
  a.equal(isPublishTemp('a+b(1).apk.0123456789abcdef.tmp', 'a+b(1).apk'), true);  // regex metacharacters in the name
});

// --- Process-level: the real server.js against a local fake GitHub (no network). Skipped without minisign; the
// supervisor image (where CI runs this file) ships it. ---
const cp = require('child_process');
const HAS_MINISIGN = cp.spawnSync('minisign', ['-v']).status === 0;

// publishApk's temp files (PUBLISH_APK_TO.<16 hex>.tmp) sit in Tomcat's public files/ dir; one left by a process that
// died mid-copy would be served under /files/. The supervisor removes them when it starts (and before each publish,
// below): only that exact shape, only a regular file or a link (the link itself, never its target).
t.test('stale publish temp files are removed at start; nothing else in the files dir is touched', { timeout: 20000 }, async (tt) => {
  const fs = require('fs'), os = require('os'), path = require('path'), http = require('http');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-tmp-'));
  let child = null;
  tt.after(async () => {
    if (child && child.exitCode === null && child.signalCode === null) {
      const exited = new Promise((r) => child.once('exit', r));
      child.kill();
      await exited;
    }
    fs.rmSync(dir, { recursive: true, force: true });
  });
  const files = path.join(dir, 'files'), outside = path.join(dir, 'outside');
  fs.mkdirSync(files);
  fs.writeFileSync(outside, 'OUTSIDE');
  fs.writeFileSync(path.join(files, 'agent.apk'), 'CURRENT APK');
  fs.writeFileSync(path.join(files, 'agent.apk.0123456789abcdef.tmp'), 'PARTIAL COPY');     // stale: removed
  fs.symlinkSync(outside, path.join(files, 'agent.apk.fedcba9876543210.tmp'));              // stale link: unlinked, target kept
  fs.mkdirSync(path.join(files, 'agent.apk.aaaaaaaaaaaaaaaa.tmp'));                         // a directory: left alone
  for (const keep of ['agent.apk.tmp', 'agent.apk.backup', 'agent.apk.0123.tmp', 'other.apk.0123456789abcdef.tmp']) {
    fs.writeFileSync(path.join(files, keep), 'KEEP');
  }
  const port = await new Promise((r) => { const s = http.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
  child = cp.spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    stdio: ['ignore', 'pipe', 'pipe'],
    // No GITHUB_REPO: the startup poll does nothing, so nothing is published and only the start-up cleanup can act.
    env: { ...process.env, GITHUB_REPO: '', GITHUB_TOKEN: '', SUPERVISOR_PORT: String(port), SUPERVISOR_BIND: '127.0.0.1',
      APPLY_SUPPORTED: '0', AUTO_UPDATE: '0', MANIFEST_PUBKEY: path.join(dir, 'none.pub'), APK_CACHE_DIR: path.join(dir, 'apk'),
      PUBLISH_APK_TO: path.join(files, 'agent.apk'), AUTO_FILE: path.join(dir, 'auto.json'),
      RECOVERY_TOKEN_FILE: path.join(dir, 'recovery.token') } });
  let log = '';
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('supervisor never listened:\n' + log)), 10000);
    const onExit = (code) => { clearTimeout(timer); reject(new Error(`supervisor exited (${code}):\n${log}`)); };
    const onData = (d) => { log += d; if (log.includes('supervisor on')) { clearTimeout(timer); child.off('exit', onExit); resolve(); } };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', onExit);
  });
  a.deepEqual(fs.readdirSync(files).sort(), ['agent.apk', 'agent.apk.0123.tmp', 'agent.apk.aaaaaaaaaaaaaaaa.tmp', 'agent.apk.backup',
    'agent.apk.tmp', 'other.apk.0123456789abcdef.tmp'], 'only the stale temp file and link are gone');
  a.equal(fs.readFileSync(outside, 'utf8'), 'OUTSIDE', 'the stale link\'s target is untouched');
  a.equal(fs.readFileSync(path.join(files, 'agent.apk'), 'utf8'), 'CURRENT APK');
});

t.test('/update/status reports the mirrored APK as available once the warm-up download lands, without re-polling; '
  + 'the published copy never goes through a link planted at a temp name, and stale publish temps are removed first',
  { skip: !HAS_MINISIGN && 'minisign not installed', timeout: 20000 }, async (tt) => {
    const fs = require('fs'), os = require('os'), path = require('path'), http = require('http'), crypto = require('crypto');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sup-apk-'));
    let gh = null, child = null;
    // One hook, in order: the supervisor and the fake GitHub are fully down before the temp dir they use is removed.
    tt.after(async () => {
      if (child && child.exitCode === null && child.signalCode === null) {
        const exited = new Promise((r) => child.once('exit', r));
        child.kill();
        await exited;
      }
      if (gh) { gh.closeAllConnections(); await new Promise((r) => gh.close(r)); }
      fs.rmSync(dir, { recursive: true, force: true });
    });

    // A signed release: throwaway key pair, a manifest naming the APK by sha256, the detached signature beside it.
    const apk = crypto.randomBytes(4096);
    const manifest = { version: '9.9.9', channel: 'stable', components: { apk: {
      file: 'dallycontrol-agent.apk', versionCode: 999, sha256: crypto.createHash('sha256').update(apk).digest('hex') } } };
    fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest));
    cp.execFileSync('minisign', ['-G', '-W', '-p', path.join(dir, 'k.pub'), '-s', path.join(dir, 'k.key')], { stdio: 'ignore' });
    cp.execFileSync('minisign', ['-S', '-s', path.join(dir, 'k.key'), '-m', path.join(dir, 'manifest.json')], { stdio: 'ignore' });

    // Fake GitHub: the releases API (counted) and the three release assets.
    let releaseCalls = 0;
    gh = http.createServer((req, res) => {
      const base = `http://127.0.0.1:${gh.address().port}`;
      if (req.url.startsWith('/repos/o/r/releases')) {
        releaseCalls++;
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify([{ tag_name: 'v9.9.9', html_url: base + '/rel', assets: ['manifest.json', 'manifest.json.minisig', 'dallycontrol-agent.apk']
          .map((name) => ({ name, browser_download_url: `${base}/dl/${name}` })) }]));
      } else if (req.url === '/dl/dallycontrol-agent.apk') {
        // A stale publish temp that appears after start-up (not seen by the start-up cleanup): the publish removes it.
        fs.writeFileSync(path.join(dir, 'files', 'agent.apk.1111111111111111.tmp'), 'STALE');
        res.end(apk);
      }
      else if (req.url === '/dl/manifest.json' || req.url === '/dl/manifest.json.minisig') res.end(fs.readFileSync(path.join(dir, req.url.slice(4))));
      else { res.statusCode = 404; res.end(); }
    });
    await new Promise((r) => gh.listen(0, '127.0.0.1', r));

    // server.js calls the fixed https://api.github.com origin; a preload points only that origin at the fake.
    const preload = path.join(dir, 'fake-github.js');
    fs.writeFileSync(preload, "const f = globalThis.fetch;\n"
      + "globalThis.fetch = (u, o) => f(String(u).replace('https://api.github.com', process.env.FAKE_GITHUB), o);\n");
    // On a native install PUBLISH_APK_TO is in Tomcat's files/ directory, and older versions ran the supervisor as root
    // there. A link planted at the predictable temp name the publish step used to write through must be left alone,
    // target included.
    const files = path.join(dir, 'files'), outside = path.join(dir, 'outside');
    fs.mkdirSync(files);
    fs.writeFileSync(outside, 'NOT AN APK');
    fs.symlinkSync(outside, path.join(files, 'agent.apk.tmp'));
    const port = await new Promise((r) => { const s = http.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => r(p)); }); });
    child = cp.spawn(process.execPath, ['--require', preload, path.join(__dirname, 'server.js')], {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, FAKE_GITHUB: `http://127.0.0.1:${gh.address().port}`, GITHUB_REPO: 'o/r', GITHUB_TOKEN: '',
        SUPERVISOR_PORT: String(port), SUPERVISOR_BIND: '127.0.0.1', CURRENT_VERSION: '9.9.9', APPLY_SUPPORTED: '0', AUTO_UPDATE: '0',
        UPDATE_CHANNEL: 'stable', POLL_INTERVAL_HOURS: '6',
        MANIFEST_PUBKEY: path.join(dir, 'k.pub'), APK_CACHE_DIR: path.join(dir, 'apk'), PUBLISH_APK_TO: path.join(files, 'agent.apk'),
        AUTO_FILE: path.join(dir, 'auto.json'), RECOVERY_TOKEN_FILE: path.join(dir, 'recovery.token') } });

    // Wait for the startup poll's warm-up download to land. server.js logs "[apk] mirrored" in the same synchronous
    // block that publishes the file, so any request answered after this line sees the post-download state.
    let log = '';
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('APK never mirrored; supervisor output:\n' + log)), 10000);
      const onExit = (code) => { clearTimeout(timer); reject(new Error(`supervisor exited (${code}):\n${log}`)); };
      const onData = (d) => { log += d; if (log.includes('[apk] mirrored')) { clearTimeout(timer); child.off('exit', onExit); resolve(); } };
      child.stdout.on('data', onData);
      child.stderr.on('data', onData);
      child.on('exit', onExit);
    });

    const status = await (await fetch(`http://127.0.0.1:${port}/update/status`)).json();
    a.equal(status.verified, true, 'the fake release verifies against the throwaway key');
    a.deepEqual({ versionCode: status.apk && status.apk.versionCode, available: status.apk && status.apk.available },
      { versionCode: 999, available: true }, '/update/status must say available once the APK is being served');
    a.equal(releaseCalls, 1, 'the refresh comes from the download itself, not from another poll');

    // publishApk runs in the same synchronous block as the "[apk] mirrored" line, so it has finished by now.
    a.ok(fs.lstatSync(path.join(files, 'agent.apk')).isFile(), 'PUBLISH_APK_TO is a regular file, not the planted link');
    a.ok(fs.readFileSync(path.join(files, 'agent.apk')).equals(apk), 'the verified APK is published to PUBLISH_APK_TO');
    a.equal(fs.readFileSync(outside, 'utf8'), 'NOT AN APK', 'the planted link was not written through');
    a.deepEqual(fs.readdirSync(files).sort(), ['agent.apk', 'agent.apk.tmp'],
      'no temp file is left behind, and the stale one that appeared before the publish is gone');
  });
