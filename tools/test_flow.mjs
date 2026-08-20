// 진짜 브라우저로 앱을 처음부터 끝까지 몰아 보는 검사.
//
// core.js 의 판단 규칙은 tools/test_core.mjs 가 본다. 여기서 보는 것은 화면이다.
// 배포하면 곧바로 가족 모두에게 반영되는 구조라, 화면이 깨진 채 나가면 걸러 줄
// 사람이 아무도 없다. 그래서 배포 전에 실제로 눌러 본다.
//
// 저장소는 가짜다. 요청을 가로채 Map 하나로 받아 주므로 진짜 Firebase 를
// 건드리지 않고도 기기 두 대가 주고받는 흐름을 그대로 볼 수 있다.
//
//   node tools/test_flow.mjs
//
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const PORT = 8731 + (process.pid % 500);
const FAKE_DB = 'https://mock-db.firebasedatabase.app';

// --- 준비: web/ 을 임시 폴더에 복사하고 __BUILD__ 를 채운다.
const site = fs.mkdtempSync(path.join(os.tmpdir(), 'henny-flow-'));
for (const f of fs.readdirSync(path.join(ROOT, 'web'))) {
  let buf = fs.readFileSync(path.join(ROOT, 'web', f));
  if (/\.(js|html|webmanifest)$/.test(f)) {
    buf = Buffer.from(buf.toString('utf8').replaceAll('__BUILD__', 'flow-test'));
  }
  fs.writeFileSync(path.join(site, f), buf);
}
const server = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'],
  { cwd: site, stdio: 'ignore' });
await new Promise((r) => setTimeout(r, 800));

// --- 가짜 Realtime Database. 기기 두 대가 같은 Map 을 본다.
const db = new Map();
async function serveDb(route, req) {
  const key = new URL(req.url()).pathname;
  if (req.method() === 'PUT' || req.method() === 'POST') {
    db.set(key, req.postData() || '');
    return route.fulfill({ status: 200, contentType: 'application/json', body: req.postData() || 'null' });
  }
  // .../plan/updatedAt.json 처럼 한 칸만 읽어 가는 값싼 확인.
  const field = /^(.*)\/updatedAt\.json$/.exec(key);
  if (field) {
    const raw = db.get(field[1] + '.json');
    const v = raw ? (JSON.parse(raw).updatedAt ?? 0) : null;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(v) });
  }
  return route.fulfill({ status: 200, contentType: 'application/json', body: db.get(key) ?? 'null' });
}

const failures = [];
// 보통은 playwright 가 받아 둔 브라우저를 알아서 쓴다. 이미 크로미움이 깔린
// 환경에서는 HENNY_CHROMIUM 으로 그 경로를 알려 주면 다시 받지 않는다.
const browser = await chromium.launch(
  process.env.HENNY_CHROMIUM ? { executablePath: process.env.HENNY_CHROMIUM } : {});

async function device(label) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await ctx.route(FAKE_DB + '/**', serveDb);
  const page = await ctx.newPage();
  page.on('pageerror', (e) => failures.push(`${label}: 화면 오류 ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') failures.push(`${label}: 콘솔 오류 ${m.text()}`); });
  await page.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });
  return page;
}

async function check(name, fn) {
  try { await fn(); console.log('  ok   ' + name); }
  catch (e) { failures.push(`${name}: ${e.message}`); console.log('  실패 ' + name + ' — ' + e.message); }
}

/** 계획 수정은 2초 뒤에 올라간다. 그 시간을 기다려 준다. */
async function waitUpload(test, label) {
  for (let i = 0; i < 30; i++) {
    if ([...db.values()].some((v) => v && test(v))) return;
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error(`${label} 이(가) 저장소에 올라오지 않았다`);
}

const manager = await device('관리자');
const html = (p) => p.content();

console.log('관리자 기기');
await check('첫 설정: 역할 → 작업자 2명 → 저장소 연결', async () => {
  await manager.click('[data-act="role-manager"]');
  for (const name of ['민준', '서준']) {
    await manager.click('[data-act="add-worker"]');
    await manager.fill('#m-input', name);
    await manager.click('[data-act="confirm"]');
  }
  await manager.click('[data-act="setup-next"]');
  await manager.fill('#fb-url', FAKE_DB);
  await manager.click('[data-act="connect"]');
  await waitUpload((v) => v.includes('민준'), '작업자 명단');
  if (!(await html(manager)).includes('민준')) throw new Error('관리자 화면에 작업자가 없다');
});

await check('정기 작업을 만들면 저장소에 올라간다', async () => {
  await manager.click('[data-act="tab"][data-id="TASKS"]');
  await manager.click('[data-act="add-routine"]');
  await manager.fill('#m-title', '수학 문제집 2장');
  await manager.fill('#m-points', '150');
  await manager.click('[data-act="confirm"]');
  if (!(await html(manager)).includes('수학 문제집 2장')) throw new Error('목록에 안 보인다');
  await waitUpload((v) => v.includes('수학 문제집 2장'), '정기 작업');
});

let code = null;
await check('작업자 연결 코드가 나온다', async () => {
  await manager.click('[data-act="tab"][data-id="SET"]');
  await manager.click('[data-act="show-code"]');
  await manager.waitForTimeout(300);
  const found = /HENNY2:[A-Za-z0-9_\-=]+/.exec(await html(manager));
  if (!found) throw new Error('코드 문자열을 찾지 못했다');
  code = found[0];
  await manager.click('.backdrop', { position: { x: 5, y: 5 } });
  await manager.waitForTimeout(300);
});

console.log('작업자 기기');
const worker = await device('작업자');
await check('코드를 넣으면 관리자가 만든 작업이 내려온다', async () => {
  await worker.click('[data-act="go-code"]');
  await worker.fill('#code-input', code);
  await worker.click('[data-act="apply-code"]');
  await worker.waitForTimeout(1500);
  if (!(await html(worker)).includes('수학 문제집 2장')) throw new Error('작업이 안 내려왔다');
});

await check('체크하면 정해 둔 점수가 붙는다', async () => {
  await worker.click('[data-act="toggle"]');
  await worker.waitForTimeout(400);
  if (!(await html(worker)).includes('150')) throw new Error('획득 점수 150 이 안 보인다');
});

await check('체크 결과가 저장소로 올라간다', async () => {
  await worker.click('[data-act="sync"]');
  await waitUpload((v) => v.includes('doneAt'), '체크 기록');
});

console.log('관리자 기기 — 되받기');
await check('관리자가 동기화하면 완료가 보인다', async () => {
  await manager.click('[data-act="tab"][data-id="TODAY"]');
  await manager.click('[data-act="sync"]');
  await manager.waitForTimeout(1500);
  if (!/1\s*\/\s*1|완료|150/.test(await html(manager))) throw new Error('현황에 반영되지 않았다');
});

await check('통계 탭이 열린다', async () => {
  await manager.click('[data-act="tab"][data-id="STATS"]');
  if (!(await html(manager)).includes('민준')) throw new Error('통계에 작업자가 없다');
});

console.log('관리자 두 번째 기기');
let managerCode = null;
await check('관리자 코드가 나온다', async () => {
  await manager.click('[data-act="tab"][data-id="SET"]');
  await manager.click('[data-act="backup-code"]');
  await manager.waitForTimeout(300);
  const found = /HENNY2:[A-Za-z0-9_\-=]+/.exec(await html(manager));
  if (!found) throw new Error('관리자 코드를 찾지 못했다');
  managerCode = found[0];
  await manager.click('.backdrop', { position: { x: 5, y: 5 } });
  await manager.waitForTimeout(300);
});

const manager2 = await device('관리자2');
await check('관리자 코드로 두 번째 관리자가 붙는다', async () => {
  await manager2.click('[data-act="go-code"]');
  await manager2.fill('#code-input', managerCode);
  await manager2.click('[data-act="apply-code"]');
  await manager2.waitForTimeout(1500);
  const h = await html(manager2);
  if (!h.includes('민준') || !h.includes('서준')) throw new Error('팀이 안 내려왔다');
});

await check('두 관리자가 각자 더한 작업이 모두 남는다', async () => {
  await manager2.click('[data-act="tab"][data-id="TASKS"]');
  await manager2.click('[data-act="add-routine"]');
  await manager2.fill('#m-title', '독해 문제집 2장');
  await manager2.click('[data-act="confirm"]');
  await waitUpload((v) => v.includes('독해 문제집 2장'), '두 번째 관리자의 작업');

  await manager.click('[data-act="tab"][data-id="TODAY"]');
  await manager.click('[data-act="sync"]');
  await manager.waitForTimeout(1500);
  await manager.click('[data-act="tab"][data-id="TASKS"]');
  const h = await html(manager);
  // 첫 관리자가 만든 것이 두 번째 관리자의 저장으로 밀려나면 안 된다.
  if (!h.includes('수학 문제집 2장')) throw new Error('첫 관리자의 작업이 사라졌다');
  if (!h.includes('독해 문제집 2장')) throw new Error('두 번째 관리자의 작업이 안 보인다');
});

await check('한쪽에서 지우면 다른 쪽에서도 지워진다', async () => {
  await manager2.click('[data-act="tab"][data-id="TODAY"]');
  await manager2.click('[data-act="sync"]');
  await manager2.waitForTimeout(1500);
  await manager2.click('[data-act="tab"][data-id="TASKS"]');
  // 삭제는 정기 작업을 열어야 나온다.
  const edit = await manager2.$$('[data-act="edit-routine"]');
  if (!edit.length) throw new Error('수정 버튼이 없다');
  await edit[0].click();
  await manager2.waitForTimeout(300);
  await manager2.click('[data-act="del-routine-now"]');
  await manager2.waitForTimeout(2500);

  await manager.click('[data-act="tab"][data-id="TODAY"]');
  await manager.click('[data-act="sync"]');
  await manager.waitForTimeout(1500);
  await manager.click('[data-act="tab"][data-id="TASKS"]');
  const rows = (await html(manager)).match(/문제집 2장/g) || [];
  if (rows.length !== 1) throw new Error(`지운 뒤 남은 작업이 ${rows.length}개다 (1개여야 함)`);
});

await check('앱에 넘길 알람 일정이 만들어진다', async () => {
  const count = await worker.evaluate(async () => {
    const m = await import('./core.js');
    return m.computeSchedule(new m.Repo(), 3).length;
  });
  if (typeof count !== 'number' || count < 1) throw new Error(`일정이 비어 있다 (${count})`);
});

await browser.close();
server.kill();
fs.rmSync(site, { recursive: true, force: true });

if (failures.length) {
  console.log('\n실패 ' + failures.length + '건');
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
console.log('\n화면 흐름 검사 통과');
