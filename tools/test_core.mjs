/**
 * web/core.js 의 판단 규칙을 실제로 돌려서 확인한다.
 * 브라우저 없이 노드에서 돌리므로 CI 에서도 그대로 쓴다.
 *
 *   node tools/test_core.mjs
 */

// --- 브라우저 흉내
const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
  get length() { return store.size; },
  key: (i) => [...store.keys()][i],
};
// Object.keys(localStorage) 가 저장된 키를 돌려주도록 프록시로 감싼다
globalThis.localStorage = new Proxy(globalThis.localStorage, {
  ownKeys: () => [...store.keys()],
  getOwnPropertyDescriptor: () => ({ enumerable: true, configurable: true }),
});
globalThis.window = { HennyShell: null };

const { Repo, dateKey, addDays, computeSchedule, minuteToText, daysText, isoDow, DEFAULT_POINTS,
  mergePlans, importLegacy, mergeProgress, isNewer } = await import('../web/core.js');

let pass = 0;
const fails = [];
function check(name, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) { pass++; } else { fails.push(`${name}\n    기대: ${e}\n    실제: ${a}`); }
}
function ok(name, cond) {
  if (cond) { pass++; } else { fails.push(name); }
}

// --- 날짜 계산
check('isoDow(월요일)=1', isoDow(new Date(2026, 7, 17)), 1);
check('isoDow(일요일)=7', isoDow(new Date(2026, 7, 23)), 7);
check('dateKey 0채움', dateKey(new Date(2026, 0, 5)), '2026-01-05');
check('minuteToText 정각', minuteToText(16 * 60), '오후 4시');
check('minuteToText 분포함', minuteToText(7 * 60 + 30), '오전 7시 30분');
check('minuteToText 자정', minuteToText(0), '오전 12시');
check('daysText 평일', daysText([1, 2, 3, 4, 5]), '평일');
check('daysText 매일', daysText([1, 2, 3, 4, 5, 6, 7]), '매일');

// --- 기본 흐름
const repo = new Repo();
const w = repo.addWorker('김민준');
ok('작업자 추가됨', repo.plan.workers.length === 1);
ok('기본 알림 3개', repo.plan.reminders.filter((r) => r.workerId === w.id).length === 3);

// 오늘 요일에 걸리는 정기 작업 두 개
const today = new Date();
const dow = isoDow(today);
const tomorrowDow = dow === 7 ? 1 : dow + 1;
repo.addRoutine(w.id, '일일 점검표 작성', [dow], 17 * 60, 100);
repo.addRoutine(w.id, '재고 확인', [dow], null, 200);
// 다른 요일에만 걸리는 것은 오늘 안 나와야 한다
repo.addRoutine(w.id, '주말 정리', [tomorrowDow], null, 50);
// 마감 알림 검사용으로 내일치를 하나 더 둔다. 오늘 걸로 두면 실행 시각에 따라
// (예: 이미 17시가 지난 뒤 돌면) 마감이 지나 알림이 걸러지고 검사가 흔들린다.
// 내일은 언제 돌려도 항상 미래이므로 시각에 좌우되지 않는다.
repo.addRoutine(w.id, '내일 마감 점검', [tomorrowDow], 12 * 60, 80);

let tasks = repo.tasksFor(w.id, today);
check('오늘 작업 수', tasks.length, 2);
check('배점 합', tasks.reduce((s, t) => s + t.points, 0), 300);
check('기본 배점 상수', DEFAULT_POINTS, 100);

// 임시 작업은 맨 위에
repo.addAssignment(w.id, '창고 재고 확인', today, null, 500);
tasks = repo.tasksFor(w.id, today);
check('임시 작업 포함 수', tasks.length, 3);
check('임시 작업이 맨 위', tasks[0].isAssignment, true);

// --- 체크와 마일리지
repo.toggle(w.id, today, tasks[0].id);
tasks = repo.tasksFor(w.id, today);
check('하나 완료', tasks.filter((t) => t.doneAt).length, 1);
check('오늘 획득 마일리지', tasks.filter((t) => t.doneAt).reduce((s, t) => s + t.points, 0), 500);
check('누적 마일리지', repo.lifetimePoints(w.id), 500);

// 다시 누르면 해제
repo.toggle(w.id, today, tasks[0].id);
check('해제됨', repo.tasksFor(w.id, today).filter((t) => t.doneAt).length, 0);
check('누적도 0', repo.lifetimePoints(w.id), 0);

// --- 배점은 체크 시점 값으로 박힌다
repo.toggle(w.id, today, tasks[0].id);
const before = repo.lifetimePoints(w.id);
const target = repo.plan.assignments[0];
repo.mutatePlan((p) => ({
  ...p, assignments: p.assignments.map((a) => (a.id === target.id ? { ...a, points: 9999 } : a)),
}));
check('배점을 바꿔도 쌓인 마일리지는 그대로', repo.lifetimePoints(w.id), before);

// --- 통계
const week = repo.weekStat(w.id);
ok('주간 통계 생성', week.perDay.length >= 1 && week.total > 0);
ok('달성률 범위', week.rate >= 0 && week.rate <= 100);

// --- 연속 달성: 오늘이 미완료여도 끊기지 않는다
const yesterday = addDays(today, -1);
const yTasks = repo.tasksFor(w.id, yesterday);
if (yTasks.length) {
  repo.writeDay(w.id, yesterday, {
    date: dateKey(yesterday),
    items: yTasks.map((t) => ({ taskId: t.id, title: t.title, doneAt: Date.now(), points: t.points })),
    updatedAt: Date.now(),
  });
  ok('진행 중인 오늘이 연속을 끊지 않음', repo.streak(w.id) >= 1);
}

// --- 알람 일정
repo.updateSettings({ role: 'WORKER', workerId: w.id });
const schedule = computeSchedule(repo, 3);
ok('알람 일정이 생성됨', schedule.length > 0);
ok('모두 미래 시각', schedule.every((e) => e.at > Date.now()));
ok('시간순 정렬', schedule.every((e, i) => i === 0 || schedule[i - 1].at <= e.at));
ok('마감 알림에 배점 표시', schedule.some((e) => e.tag.startsWith('due:') && /\d+P/.test(e.body)));
ok('점검 알림에 목록 포함', schedule.some((e) => e.tag.startsWith('rem:')));

// --- 연결 코드 왕복
repo.updateSettings({
  role: 'MANAGER', backend: 'FIREBASE', firebaseDb: 'https://x.firebasedatabase.app',
});
repo.provision();
const planBin1 = repo.settings.planBin;
ok('계획 주소 생성', /\/henny\/[^/]+\/plan\.json$/.test(planBin1));

// 같은 기기에서 다시 부르면 경로가 유지되어야 한다 (안 그러면 자료가 고아가 된다)
repo.provision();
check('provision 재호출 시 경로 유지', repo.settings.planBin, planBin1);

const code = repo.pairingCode(w.id);
ok('연결 코드 형식', code.startsWith('HENNY2:'));

const repo2 = new Repo();
const name = repo2.applyCode(code);
check('코드로 이름 복원', name, '김민준');
check('코드로 역할 복원', repo2.settings.role, 'WORKER');
check('코드로 작업자 복원', repo2.settings.workerId, w.id);
check('코드로 계획 주소 복원', repo2.settings.planBin, planBin1);

const backup = repo.managerBackupCode();
const repo3 = new Repo();
repo3.applyCode(backup);
check('복구 코드로 관리자 복원', repo3.settings.role, 'MANAGER');
check('복구 코드로 모든 주소 복원', repo3.settings.progressBins, repo.settings.progressBins);

// 복구한 관리자가 provision 을 불러도 기존 경로를 지켜야 한다
repo3.provision();
check('복구 후에도 경로 유지', repo3.settings.planBin, planBin1);

// --- 관리자가 여럿일 때 계획 합치기
// 두 관리자가 각자 하나씩 더했다. 둘 다 남아야 한다.
{
  const base = { schema: 1, updatedAt: 100, workers: [], routines: [], assignments: [], reminders: [], deleted: {} };
  const a = { ...base, updatedAt: 200, routines: [{ id: 't1', title: '가', updatedAt: 200 }] };
  const b = { ...base, updatedAt: 300, routines: [{ id: 't2', title: '나', updatedAt: 300 }] };
  const m = mergePlans(a, b);
  check('관리자 둘이 더한 작업이 모두 남는다', m.routines.map((r) => r.id).sort(), ['t1', 't2']);

  // 같은 항목을 둘이 고쳤으면 나중 것을 따른다
  const c = { ...base, routines: [{ id: 't1', title: '먼저', updatedAt: 100 }] };
  const d = { ...base, routines: [{ id: 't1', title: '나중', updatedAt: 500 }] };
  check('같은 작업은 나중 수정이 이긴다', mergePlans(c, d).routines[0].title, '나중');
  check('합치는 순서가 결과를 바꾸지 않는다', mergePlans(d, c).routines[0].title, '나중');

  // 한쪽이 지웠으면 상대가 들고 있어도 되살아나지 않는다
  const e = { ...base, routines: [{ id: 't1', title: '가', updatedAt: 100 }] };
  const f = { ...base, routines: [], deleted: { t1: 400 } };
  check('지운 작업이 되살아나지 않는다', mergePlans(e, f).routines.length, 0);

  // 지운 뒤 다시 만들었으면 살아 있어야 한다
  const g = { ...base, routines: [{ id: 't1', title: '다시', updatedAt: 900 }] };
  check('지운 뒤 다시 만든 작업은 남는다', mergePlans(g, f).routines.length, 1);
}

// 실제 Repo 로도 확인한다. 항목을 고치지 않았으면 시각이 그대로여야
// 다른 관리자의 수정을 밀어내지 않는다.
{
  const r = new Repo();
  r.updateSettings({ role: 'MANAGER', setupDone: true });
  const w = r.addWorker('두관리자');
  r.addRoutine(w.id, '첫 작업', [1, 2, 3, 4, 5], null, 100);
  const before = r.plan.routines[0].updatedAt;
  ok('고친 항목에 시각이 찍힌다', typeof before === 'number' && before > 0);

  r.addRoutine(w.id, '둘째 작업', [1, 2, 3, 4, 5], null, 100);
  check('안 고친 항목의 시각은 그대로', r.plan.routines[0].updatedAt, before);

  const id = r.plan.routines[0].id;
  r.deleteRoutine(id);
  ok('지우면 표시가 남는다', Boolean(r.plan.deleted[id]));
}

// --- 앱 버전 비교
// 자리마다 숫자로 견줘야 한다. 문자열로 비교하면 "1.0.9" > "1.0.10" 이 되어
// 새 버전이 나와도 안내가 안 뜬다.
ok('새 버전을 알아본다', isNewer('v1.0.45', '1.0.41'));
ok('같으면 새것이 아니다', !isNewer('v1.0.41', '1.0.41'));
ok('두 자리 수를 제대로 견준다', isNewer('v1.0.10', '1.0.9'));
ok('거꾸로도 맞다', !isNewer('v1.0.9', '1.0.10'));
ok('가운데 자리도 본다', isNewer('v1.1.0', '1.0.99'));
ok('디버그 접미사가 붙어도 읽는다', isNewer('v1.0.42', '1.0.41-debug'));
ok('값이 없으면 안내하지 않는다', !isNewer('', '1.0.1'));

// --- 마일리지 원장
{
  const r = new Repo();
  r.updateSettings({ role: 'MANAGER', setupDone: true });
  const w = r.addWorker('원장검사');
  const today = new Date();
  const dow = isoDow(today);
  r.addRoutine(w.id, '적립 작업', [dow], null, 300);
  const t = r.tasksFor(w.id, today)[0];

  r.toggle(w.id, today, t.id);
  check('체크하면 잔액이 오른다', r.lifetimePoints(w.id), 300);

  // 같은 것을 껐다 켜도 두 번 쌓이지 않아야 한다
  r.toggle(w.id, today, t.id);
  check('체크를 끄면 되돌아간다', r.lifetimePoints(w.id), 0);
  r.toggle(w.id, today, t.id);
  check('다시 켜도 한 번만 쌓인다', r.lifetimePoints(w.id), 300);

  // 관리자가 손으로 빼기 — 모은 것을 쓰는 경우
  r.adjustPoints(w.id, -200, '문구점');
  check('쓰면 잔액이 줄어든다', r.lifetimePoints(w.id), 100);
  r.adjustPoints(w.id, 500, '보너스');
  check('상을 주면 잔액이 오른다', r.lifetimePoints(w.id), 600);

  ok('내역이 남는다', r.ledgerOf(w.id).length === 3);

  // 배점을 나중에 고쳐도 이미 쌓인 것은 그대로여야 한다
  const rid = r.plan.routines[0].id;
  r.updateRoutine({ ...r.plan.routines[0], points: 9999 });
  check('배점을 고쳐도 쌓인 잔액은 그대로', r.lifetimePoints(w.id), 600);

  // 오래된 기록이 정리돼도 잔액은 줄지 않아야 한다.
  // 예전에는 남은 기록을 다시 더하는 방식이라 여기서 값이 흔들렸다.
  const p = r.progressOf(w.id);
  const old = dateKey(addDays(today, -400));
  r.saveProgress(w.id, {
    ...p,
    days: { ...p.days, [old]: { date: old, items: [], updatedAt: 1 } },
  });
  check('오래된 기록을 정리해도 잔액은 그대로', r.lifetimePoints(w.id), 600);

  // 위 검사는 빈 날짜로만 확인해서, 정작 "점수를 딴 옛 날짜가 접히는" 경우를
  // 보지 못했다. 달이 넘어가면 겪는 것이 바로 그 경우다.
  const oldDay = addDays(today, -200);
  r.addRoutine(w.id, '옛 작업', [isoDow(oldDay)], null, 700);
  const oldTask = r.tasksFor(w.id, oldDay).find((x) => x.title === '옛 작업');
  ok('옛 날짜에도 작업이 잡힌다', !!oldTask);
  r.toggle(w.id, oldDay, oldTask.id);
  check('점수를 딴 옛 날짜가 접혀도 누적은 그대로', r.lifetimePoints(w.id), 1300);

  // 접힌 뒤에도 일별 기록은 사라지고 원장만 남아야 한다.
  ok('옛 날짜는 정리되어 사라진다', !r.progressOf(w.id).days[dateKey(oldDay)]);
  ok('그래도 원장에는 남아 있다', r.ledgerOf(w.id).some((e) => e.delta === 700));
}

// --- 작업 순서 바꾸기
{
  const r = new Repo();
  r.updateSettings({ role: 'MANAGER', setupDone: true });
  const w = r.addWorker('순서검사');
  const today = new Date();
  const all = [1, 2, 3, 4, 5, 6, 7];
  ['가', '나', '다'].forEach((t) => r.addRoutine(w.id, t, all, null, 100));
  const titles = () => r.tasksFor(w.id, today).map((t) => t.title).join(',');
  check('처음에는 만든 차례대로', titles(), '가,나,다');

  const ids = r.plan.routines.filter((x) => x.workerId === w.id)
    .sort((a, b) => (a.order || 0) - (b.order || 0)).map((x) => x.id);
  r.reorderRoutines(w.id, [ids[2], ids[0], ids[1]]);
  check('순서를 바꾸면 오늘 목록도 바뀐다', titles(), '다,가,나');

  // 다른 작업자의 작업까지 건드리면 안 된다
  const w2 = r.addWorker('남');
  r.addRoutine(w2.id, '남의 작업', all, null, 100);
  const before = r.plan.routines.find((x) => x.workerId === w2.id).order;
  r.reorderRoutines(w.id, [ids[0], ids[1], ids[2]]);
  check('남의 작업 순서는 그대로', r.plan.routines.find((x) => x.workerId === w2.id).order, before);

  // 순서만 바꿨을 때 안 옮긴 항목의 시각은 그대로여야 한다.
  // 안 그러면 다른 관리자가 방금 고친 제목을 밀어낸다.
  const stamps = () => Object.fromEntries(r.plan.routines
    .filter((x) => x.workerId === w.id).map((x) => [x.id, x.updatedAt]));
  const s0 = stamps();
  r.reorderRoutines(w.id, [ids[1], ids[0], ids[2]]);   // 앞의 둘만 맞바꿈
  const s1 = stamps();
  ok('안 옮긴 항목은 시각이 그대로다', s0[ids[2]] === s1[ids[2]]);
}

// --- 음수 배점 (감점 작업)
{
  const r = new Repo();
  r.updateSettings({ role: 'MANAGER', setupDone: true });
  const w = r.addWorker('감점검사');
  const today = new Date();
  r.addRoutine(w.id, '지각', [isoDow(today)], null, -50);
  const t = r.tasksFor(w.id, today)[0];
  r.toggle(w.id, today, t.id);
  check('음수 배점은 잔액을 깎는다', r.lifetimePoints(w.id), -50);
}

// --- 두 기기가 각자 적립해도 합쳐진다
{
  const base = { schema: 2, workerId: 'w1', updatedAt: 0, days: {}, archive: [], ledger: [] };
  const a = { ...base, ledger: [{ id: 'd:2026-01-01:t1', at: 10, delta: 100, reason: 'ㄱ' }] };
  const b = { ...base, ledger: [{ id: 'adj_x', at: 20, delta: -30, reason: '사용' }] };
  const m = mergeProgress(a, b);
  check('두 기기의 원장이 모두 남는다', m.ledger.length, 2);

  // 같은 사건은 나중 것을 따른다 (껐다 켠 결과가 뒤집히면 안 된다)
  const c = { ...base, ledger: [{ id: 'd:2026-01-01:t1', at: 30, delta: 0, reason: 'ㄱ' }] };
  const m2 = mergeProgress(a, c);
  check('같은 사건은 나중에 적힌 쪽', m2.ledger.reduce((s, e) => s + e.delta, 0), 0);
  check('합치는 순서가 결과를 바꾸지 않는다',
    mergeProgress(c, a).ledger.reduce((s, e) => s + e.delta, 0), 0);
}

// --- 예전 앱 자료 이관
{
  const keep = new Map(store);
  store.clear();
  globalThis.window.HennyShell = {
    legacyData: () => JSON.stringify({
      'settings.json': { role: 'MANAGER', setupDone: true, backend: 'NONE' },
      'plan.json': { schema: 1, updatedAt: 7, workers: [{ id: 'w1', name: '옛작업자' }] },
      'progress_w1.json': { schema: 1, workerId: 'w1', updatedAt: 7, days: {}, archive: [] },
    }),
  };
  const moved = importLegacy();
  ok('예전 자료를 옮겨 온다', Array.isArray(moved) && moved.length === 3);
  const r = new Repo();
  check('옮겨 온 작업자가 보인다', r.plan.workers[0].name, '옛작업자');
  check('옮겨 온 역할이 살아 있다', r.settings.role, 'MANAGER');

  // 두 번째 실행에서는 다시 옮기지 않는다. 덮어쓰면 최신 기록이 되돌아간다.
  check('한 번 옮기면 다시 옮기지 않는다', importLegacy(), null);

  globalThis.window.HennyShell = null;
  store.clear();
  keep.forEach((v, k) => store.set(k, v));
}

// --- 결과
if (fails.length) {
  console.log(`실패 ${fails.length}건 (통과 ${pass}건):`);
  fails.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
console.log(`검사 ${pass}건 모두 통과`);
