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

const { Repo, dateKey, addDays, computeSchedule, minuteToText, daysText, isoDow, DEFAULT_POINTS } =
  await import('../web/core.js');

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
repo.addRoutine(w.id, '일일 점검표 작성', [dow], 17 * 60, 100);
repo.addRoutine(w.id, '재고 확인', [dow], null, 200);
// 다른 요일에만 걸리는 것은 오늘 안 나와야 한다
repo.addRoutine(w.id, '주말 정리', [dow === 7 ? 1 : dow + 1], null, 50);

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

// --- 결과
if (fails.length) {
  console.log(`실패 ${fails.length}건 (통과 ${pass}건):`);
  fails.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
console.log(`검사 ${pass}건 모두 통과`);
