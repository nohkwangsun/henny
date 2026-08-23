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
fs.writeFileSync(path.join(site, 'version.txt'), 'flow-test');
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

/** 모달의 요일 버튼을 모두 켠다. 검사가 무슨 요일에 돌든 같게 하려는 것이다. */
async function enableAllDays(page) {
  const count = (await page.$$('.days button')).length;
  for (let i = 0; i < count; i++) {
    const b = (await page.$$('.days button'))[i];
    const on = ((await b.getAttribute('class')) || '').includes('on');
    if (!on) await b.click();
  }
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
  // 매일 하는 작업으로 만든다.
  //
  // 기본값이 평일(월~금)이라 그대로 두면 주말에 돌릴 때 오늘치 작업이 없다.
  // 그러면 뒤따르는 작업자 검사가 통째로 깨진다. 실제로 일요일에 4건이
  // 한꺼번에 실패했다. 기능 문제가 아니라 검사가 요일에 매인 문제였다.
  await enableAllDays(manager);
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

console.log('입력 다루기');

await check('동기화가 돌아도 입력하던 값이 지워지지 않는다', async () => {
  // 예전에는 화면을 통째로 다시 그려서, 15초마다 도는 동기화가 한 번 끝나면
  // 타이핑하던 글자가 사라졌다. 팀 저장소를 붙인 뒤 앱이 못 쓸 만큼
  // 불편했던 원인이라 실제로 동기화를 돌려 놓고 확인한다.
  await manager.click('[data-act="tab"][data-id="TASKS"]');
  await manager.click('[data-act="add-routine"]');
  await manager.fill('#m-title', '아직 입력 중인 제목');

  // 화면을 다시 그리게 하는 실제 경로를 그대로 탄다. 앱으로 돌아오거나
  // 동기화가 끝나면 둘 다 여기로 와서 draw() 를 부른다.
  await manager.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await manager.waitForTimeout(600);

  const left = await manager.inputValue('#m-title');
  if (left !== '아직 입력 중인 제목') throw new Error(`입력이 "${left}" 로 바뀌었다`);
});

await check('요일을 눌러도 입력한 값이 남아 있다', async () => {
  await manager.fill('#m-title', '요일 검사');
  await manager.fill('#m-points', '70');
  const days = await manager.$$('.days button');
  await days[5].click();          // 토요일 켜기
  await manager.waitForTimeout(200);

  if (await manager.inputValue('#m-title') !== '요일 검사') throw new Error('제목이 날아갔다');
  if (await manager.inputValue('#m-points') !== '70') throw new Error('배점이 날아갔다');
  if (!(await days[5].getAttribute('class') || '').includes('on')) throw new Error('요일이 안 켜졌다');

  await manager.click('[data-act="confirm"]');
  await manager.waitForTimeout(300);
  if (!(await html(manager)).includes('요일 검사')) throw new Error('저장이 안 됐다');
});

await check('요일을 눌러도 꺼 둔 체크박스가 다시 켜지지 않는다', async () => {
  // 요일 버튼이 화면을 다시 그리면서 제목·배점·마감만 되돌려 놓았다.
  // 체크박스는 그 목록에 없어서, 꺼 둔 것이 말없이 다시 켜졌다.
  // "사용 중" 체크박스는 이미 있는 작업을 고칠 때만 나온다.
  // 모달을 닫으면 본문을 다시 그리므로 잡아 둔 참조가 끊긴다. 매번 새로 찾는다.
  const count = (await manager.$$('[data-act="edit-routine"]')).length;
  let opened = false;
  for (let i = 0; i < count; i++) {
    const rows = await manager.$$('[data-act="edit-routine"]');
    await rows[i].click();
    await manager.waitForTimeout(250);
    if (await manager.inputValue('#m-title') === '요일 검사') { opened = true; break; }
    await manager.click('[data-act="close"]');
    await manager.waitForTimeout(250);
  }
  if (!opened) throw new Error('방금 만든 작업을 열지 못했다');

  const active = await manager.$('#m-active');
  if (!active) throw new Error('"사용 중" 체크박스가 없다');
  await active.uncheck();

  const days = await manager.$$('.days button');
  await days[6].click();          // 일요일도 켜기
  await manager.waitForTimeout(250);

  if (await active.isChecked()) throw new Error('꺼 둔 체크박스가 다시 켜졌다');
  await manager.click('[data-act="close"]');
  await manager.waitForTimeout(200);
});

await check('배점 칩을 누르면 배점이 채워진다', async () => {
  await manager.click('[data-act="add-routine"]');
  await manager.click('[data-act="points"][data-id="500"]');
  await manager.waitForTimeout(150);
  if (await manager.inputValue('#m-points') !== '500') throw new Error('배점이 안 들어갔다');
  await manager.click('[data-act="close"]');
  await manager.waitForTimeout(200);
});

await check('임시 작업을 배정하고 다시 뺄 수 있다', async () => {
  await manager.click('[data-act="tab"][data-id="TODAY"]');
  await manager.click('[data-act="assign"]');
  await manager.fill('#m-title', '잘못 배정한 일');
  await manager.click('[data-act="confirm"]');
  await manager.waitForTimeout(400);
  if (!(await html(manager)).includes('잘못 배정한 일')) throw new Error('배정이 안 됐다');

  await manager.click('[data-act="del-assignment"]');
  await manager.waitForTimeout(200);
  await manager.click('[data-act="confirm"]');
  await manager.waitForTimeout(400);
  if ((await html(manager)).includes('잘못 배정한 일')) throw new Error('배정이 그대로 남았다');
});

await check('키보드가 올라와도 저장 버튼에 닿는다', async () => {
  // 안드로이드는 키보드가 떠도 100vh 가 그대로다. 그래서 모달을 화면
  // 한가운데 두면 키보드 뒤에 가려 저장 버튼을 누를 수 없었다.
  // 키보드가 차지한 만큼 보이는 높이가 줄어든 상황을 만들어 확인한다.
  await manager.setViewportSize({ width: 390, height: 844 });
  await manager.click('[data-act="tab"][data-id="TASKS"]');
  await manager.waitForTimeout(200);
  await manager.click('[data-act="add-routine"]');
  await manager.waitForTimeout(300);

  const KEYBOARD = 420;
  await manager.evaluate((h) => {
    document.documentElement.style.setProperty('--vvh', h + 'px');
  }, 844 - KEYBOARD);
  await manager.waitForTimeout(200);

  // 키보드 윗선(=화면 위에서 424px) 아래는 손이 닿지 않는 자리다. 내용이
  // 길면 스크롤해서 올려도 되지만, 끝까지 내렸을 때 저장 버튼이 이 선
  // 위로 올라와야 한다. 예전에는 화면 한가운데 고정이라 스크롤조차 되지
  // 않아 저장 버튼에 영영 닿을 수 없었다.
  const bottom = await manager.evaluate(() => {
    document.querySelectorAll('.backdrop, .modal').forEach((el) => { el.scrollTop = el.scrollHeight; });
    return Math.round(document.querySelector('[data-act="confirm"]').getBoundingClientRect().bottom);
  });
  if (bottom > 844 - KEYBOARD) {
    throw new Error(`끝까지 내려도 저장 버튼이 키보드에 가린다 (${bottom} > ${844 - KEYBOARD})`);
  }
  // 실제로 눌리는지까지 본다
  await manager.fill('#m-title', '키보드 검사');
  await manager.click('[data-act="confirm"]');
  await manager.waitForTimeout(300);
  if (!(await html(manager)).includes('키보드 검사')) throw new Error('저장 버튼이 눌리지 않았다');

  await manager.evaluate(() => document.documentElement.style.removeProperty('--vvh'));
  await manager.waitForTimeout(200);
});

console.log('마일리지와 통계');

await check('관리자가 작업자 대신 완료를 표시할 수 있다', async () => {
  // 오늘 확실히 있는 작업을 하나 만든다. 정기 작업은 요일을 타므로
  // 임시 작업으로 둔다. 날짜가 바뀌어도 결과가 같다.
  await manager.click('[data-act="tab"][data-id="TODAY"]');
  await manager.waitForTimeout(300);
  await manager.click('[data-act="assign"]');
  await manager.fill('#m-title', '관리자가 대신 체크');
  await manager.click('[data-act="confirm"]');
  await manager.waitForTimeout(500);

  const rows = await manager.$$('[data-act="mgr-toggle"]');
  if (!rows.length) throw new Error('누를 수 있는 작업 줄이 없다');
  await rows[rows.length - 1].click();
  await manager.waitForTimeout(600);
  if (!/완료/.test(await html(manager))) throw new Error('완료 표시가 안 됐다');
});

await check('마일리지를 직접 주고 쓸 수 있다', async () => {
  await manager.click('[data-act="give-points"]');
  await manager.waitForTimeout(300);
  await manager.click('[data-act="points"][data-id="-200"]');
  await manager.fill('#m-title', '문구점');
  await manager.click('[data-act="confirm"]');
  await manager.waitForTimeout(500);

  await manager.click('[data-act="tab"][data-id="STATS"]');
  await manager.waitForTimeout(300);
  await manager.click('[data-act="ledger"]');
  await manager.waitForTimeout(300);
  const h = await html(manager);
  if (!h.includes('문구점')) throw new Error('내역에 안 남았다');
  if (!h.includes('-200P')) throw new Error('차감이 안 보인다');
  await manager.click('[data-act="close"]');
  await manager.waitForTimeout(300);
});

await check('통계에서 지난 주와 지난 달을 볼 수 있다', async () => {
  await manager.click('[data-act="tab"][data-id="STATS"]');
  await manager.waitForTimeout(300);
  if (!(await html(manager)).includes('이번 주')) throw new Error('이번 주가 안 보인다');

  await manager.click('[data-act="week-move"][data-id="-1"]');
  await manager.waitForTimeout(300);
  if ((await html(manager)).includes('이번 주')) throw new Error('지난 주로 안 넘어갔다');

  // 앞으로는 지금을 넘지 못한다
  await manager.click('[data-act="week-move"][data-id="1"]');
  await manager.waitForTimeout(300);
  if (!(await html(manager)).includes('이번 주')) throw new Error('이번 주로 안 돌아왔다');

  await manager.click('[data-act="month-move"][data-id="-1"]');
  await manager.waitForTimeout(300);
  if (!/\d+년 \d+월/.test(await html(manager))) throw new Error('지난 달로 안 넘어갔다');
});

await check('감점 작업을 만들 수 있다', async () => {
  await manager.click('[data-act="tab"][data-id="TASKS"]');
  await manager.click('[data-act="add-routine"]');
  await manager.fill('#m-title', '지각');
  await manager.click('[data-act="points"][data-id="-100"]');
  await manager.waitForTimeout(150);
  if (await manager.inputValue('#m-points') !== '-100') throw new Error('음수 배점이 안 들어갔다');
  await enableAllDays(manager);
  await manager.click('[data-act="confirm"]');
  await manager.waitForTimeout(400);
  if (!(await html(manager)).includes('지각')) throw new Error('감점 작업이 저장되지 않았다');
});

await check('새 앱 버전이 있으면 받는 링크가 화면에 뜬다', async () => {
  // 껍데기가 있는 척하고(HennyShell), 깃허브가 더 새 버전을 준다고 가로챈다.
  const dev = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await dev.route(FAKE_DB + '/**', serveDb);
  await dev.route('https://api.github.com/**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ tag_name: 'v1.0.99' }) }));
  await dev.addInitScript(() => {
    window.HennyShell = {
      version: () => '1.0.41',
      canNotify: () => true,
      setSchedule: () => {},
      legacyData: () => '',
    };
  });
  const pg = await dev.newPage();
  await pg.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });
  await pg.click('[data-act="go-code"]');
  await pg.fill('#code-input', code);
  await pg.click('[data-act="apply-code"]');
  await pg.waitForTimeout(1800);

  const h = await pg.content();
  if (!h.includes('새 버전 1.0.99')) throw new Error('새 버전 안내가 안 떴다');
  if (!h.includes('releases/latest/download/henny.apk')) throw new Error('받는 링크가 없다');

  // 닫으면 그 버전에 대해서는 다시 뜨지 않는다
  await pg.click('[data-act="hide-update"]');
  await pg.waitForTimeout(400);
  if ((await pg.content()).includes('새 버전 1.0.99')) throw new Error('닫아도 그대로 있다');
  await dev.close();
});

await check('최신이면 아무것도 안 뜬다', async () => {
  const dev = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await dev.route(FAKE_DB + '/**', serveDb);
  await dev.route('https://api.github.com/**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ tag_name: 'v1.0.41' }) }));
  await dev.addInitScript(() => {
    window.HennyShell = { version: () => '1.0.41', canNotify: () => true,
      setSchedule: () => {}, legacyData: () => '' };
  });
  const pg = await dev.newPage();
  await pg.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });
  await pg.waitForTimeout(1200);
  if ((await pg.content()).includes('새 버전')) throw new Error('최신인데 안내가 떴다');
  await dev.close();
});

// 껍데기 판마다 여백 처리가 달라 세 번 헛돌았다. 세 경우를 다 못 박는다.
async function topPad(page) {
  return page.evaluate(() => getComputedStyle(document.body).paddingTop);
}

await check('껍데기가 안 밀어 줬으면 웹이 민다', async () => {
  // v1.0.46 처럼 껍데기가 아무것도 안 미는 판. 예전에는 여기서 겹쳤다.
  const dev = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await dev.route(FAKE_DB + '/**', serveDb);
  await dev.addInitScript(() => {
    // 화면 전체 높이와 웹에 주어진 높이가 같다 = 아무도 안 밀었다
    Object.defineProperty(window.screen, 'height', { get: () => window.innerHeight });
    window.HennyShell = {
      version: () => '1.0.46', canNotify: () => true,
      setSchedule: () => {}, legacyData: () => '',
      insets: () => JSON.stringify({ top: 40, bottom: 16, measured: true }),
    };
  });
  const pg = await dev.newPage();
  await pg.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });
  await pg.waitForTimeout(400);
  if (await topPad(pg) !== '40px') throw new Error(`위 여백이 ${await topPad(pg)} 다 (40px 여야 함)`);
  await dev.close();
});

await check('껍데기가 이미 밀었으면 웹은 안 민다', async () => {
  // v1.0.45/47 처럼 껍데기가 WebView 를 미는 판. 여기서 또 밀면 띠가 두 번.
  const dev = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await dev.route(FAKE_DB + '/**', serveDb);
  await dev.addInitScript(() => {
    // 껍데기가 민 만큼 웹에 주어진 높이가 작다
    Object.defineProperty(window.screen, 'height', { get: () => window.innerHeight + 64 });
    window.HennyShell = {
      version: () => '1.0.47', canNotify: () => true,
      setSchedule: () => {}, legacyData: () => '',
      insets: () => JSON.stringify({ top: 40, bottom: 24, measured: true }),
    };
  });
  const pg = await dev.newPage();
  await pg.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });
  await pg.waitForTimeout(400);
  if (await topPad(pg) !== '0px') throw new Error(`위 여백이 ${await topPad(pg)} 다 (0px 여야 함)`);
  await dev.close();
});

await check('아주 옛 껍데기라 알려주지도 않으면 최소값으로 민다', async () => {
  const dev = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await dev.route(FAKE_DB + '/**', serveDb);
  await dev.addInitScript(() => {
    Object.defineProperty(window.screen, 'height', { get: () => window.innerHeight });
    window.HennyShell = { version: () => '1.0.20', canNotify: () => true,
      setSchedule: () => {}, legacyData: () => '' };   // insets() 없음
  });
  const pg = await dev.newPage();
  await pg.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });
  await pg.waitForTimeout(400);
  if (await topPad(pg) !== '28px') throw new Error(`위 여백이 ${await topPad(pg)} 다 (28px 여야 함)`);
  await dev.close();
});

await check('껍데기가 알려준 여백만큼 화면이 밀린다', async () => {
  // 껍데기가 --inset-top 을 넣어 주면 본문이 그만큼 내려가야 한다.
  // 안드로이드 WebView 의 env(safe-area-inset-top) 은 상태바를 안 알려주므로
  // 이 경로가 실제로 쓰이는 유일한 길이다.
  const dev = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await dev.route(FAKE_DB + '/**', serveDb);
  await dev.addInitScript(() => {
    window.HennyShell = {
      version: () => '1.0.99', canNotify: () => true,
      setSchedule: () => {}, legacyData: () => '',
      insets: () => JSON.stringify({ top: 42, bottom: 16, measured: true }),
    };
    // 껍데기가 페이지가 뜬 뒤에 넣어 주는 것과 같은 동작
    addEventListener('DOMContentLoaded', () => {
      document.documentElement.style.setProperty('--inset-top', '42px');
    });
  });
  const pg = await dev.newPage();
  await pg.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });
  await pg.waitForTimeout(400);

  const pad = await pg.evaluate(() => getComputedStyle(document.body).paddingTop);
  if (pad !== '42px') throw new Error(`위쪽 여백이 ${pad} 다 (42px 여야 함)`);

  // 제목이 상태바 아래에서 시작하는지 실제 좌표로 본다
  const top = await pg.evaluate(() => {
    const h = document.querySelector('h1');
    return h ? Math.round(h.getBoundingClientRect().top) : -1;
  });
  if (top < 42) throw new Error(`제목이 ${top}px 에서 시작한다. 상태바(42px) 안으로 들어갔다`);
  await dev.close();
});

await check('새 배포가 있으면 앱이 스스로 새로 읽는다', async () => {
  // 껍데기는 화면이 처음 만들어질 때만 주소를 부른다. 앱을 나갔다 돌아오는
  // 것으로는 다시 부르지 않아, 최근 앱 목록에 남아 있으면 예전 화면이 그대로였다.
  const dev = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await dev.route(FAKE_DB + '/**', serveDb);
  const pg = await dev.newPage();
  await pg.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });

  let reloads = 0;
  pg.on('framenavigated', (f) => { if (f === pg.mainFrame()) reloads++; });

  // 아직 같은 버전이면 그대로 둬야 한다
  await pg.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await pg.waitForTimeout(600);
  if (reloads !== 0) throw new Error('같은 버전인데 새로 읽었다');

  // 새 배포가 올라온 상황
  await dev.route('**/version.txt*', (route) =>
    route.fulfill({ status: 200, contentType: 'text/plain', body: 'newer-build' }));
  await pg.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await pg.waitForTimeout(1200);
  if (reloads === 0) throw new Error('새 배포가 있는데 새로 읽지 않았다');
  await dev.close();
});

await check('첫 화면에도 버전이 보인다', async () => {
  // 설정 탭까지 못 들어가는 상태에서도 무엇이 도는지 알 수 있어야 한다.
  const dev = await browser.newContext({ viewport: { width: 412, height: 915 } });
  await dev.route(FAKE_DB + '/**', serveDb);
  await dev.addInitScript(() => {
    window.HennyShell = { version: () => '1.0.48', canNotify: () => true,
      setSchedule: () => {}, legacyData: () => '' };
  });
  const pg = await dev.newPage();
  await pg.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'networkidle' });
  await pg.waitForTimeout(300);
  const h = await pg.content();
  if (!h.includes('앱 v1.0.48')) throw new Error('앱 버전이 안 보인다');
  if (!h.includes('웹 flow-test')) throw new Error('웹 버전이 안 보인다');
  await dev.close();
});

await check('로직 파일이 배포마다 새 주소로 불린다', async () => {
  // core.js 에 규칙이 다 들어 있는데 주소가 늘 같으면 캐시에 걸려 옛 로직이
  // 남는다. 배포해도 안 바뀌는 것처럼 보이던 원인 중 하나였다.
  const src = fs.readFileSync(path.join(site, 'ui.js'), 'utf8');
  if (!/from '\.\/core\.js\?v=/.test(src)) {
    throw new Error('ui.js 가 core.js 를 버전 없이 부른다');
  }
  if (src.includes('__BUILD__')) throw new Error('ui.js 에 __BUILD__ 가 안 바뀐 채 남았다');
});

await check('앱에 넘길 알람 일정이 만들어진다', async () => {
  // 검사가 요일과 시각에 휘둘리지 않도록 입력을 직접 만든다.
  //
  // 기본 알림은 평일에만 걸린다. 그래서 금요일 저녁에 돌리면 3일 창이
  // 금·토·일이 되고, 금요일 알림 시각은 이미 지나 하나도 안 남는다.
  // 실제로 그렇게 깨졌다. 기능 문제가 아니라 검사가 벽시계에 매인 문제였다.
  //
  // 매일 걸리는 알림을 하나 두면 "내일"이 항상 창 안에 있으므로 언제 돌려도 같다.
  const count = await worker.evaluate(async () => {
    const m = await import('./core.js');
    const repo = new m.Repo();
    repo.upsertReminder({
      id: 'flow-check', workerId: repo.settings.workerId,
      minute: 9 * 60, text: '검사용 알림',
      onlyIfIncomplete: false, days: [1, 2, 3, 4, 5, 6, 7], enabled: true,
    });
    return m.computeSchedule(repo, 3).length;
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
