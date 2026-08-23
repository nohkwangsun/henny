/* 헨니 체크 — 핵심 로직
 *
 * 안드로이드 Repository.kt 를 그대로 옮긴 것이다. 데이터 구조와 판단 규칙이
 * 양쪽에서 같아야 기존 팀 저장소를 그대로 이어서 쓸 수 있다.
 *
 * ---------------------------------------------------------------------------
 * 이 파일의 위치
 *
 * 서버 앱으로 치면 도메인 + 리포지토리 + 동기화 계층을 한 파일에 넣은 것이다.
 * 화면(ui.js)은 여기 있는 Repo 를 부르기만 하고 규칙을 직접 알지 못한다.
 *
 * 서버와 결정적으로 다른 전제가 셋 있다.
 *
 * 1. 서버가 없다. 중앙에서 조정해 주는 주체가 없고, 기기들이 공용 저장소
 *    (Firebase Realtime Database) 를 각자 읽고 쓴다. 그래서 "누가 이겼는가"를
 *    코드가 직접 정해야 한다 (아래 mergePlans).
 *
 * 2. 트랜잭션이 없다. 저장소는 사실상 JSON 파일 몇 개를 HTTP 로 GET/PUT 하는
 *    것이다. 락도, compare-and-swap 도 없다. 그래서 충돌을 막는 대신
 *    "충돌이 나도 합쳐지는" 자료 구조를 쓴다.
 *
 * 3. 오프라인이 정상 상태다. 지하철에 들어가면 그냥 안 된다. 그래서 모든 쓰기는
 *    로컬에 먼저 반영하고(localStorage), 네트워크는 나중에 맞춘다.
 *
 * 요약하면 마지막 쓰기 우선(LWW) + 삭제 묘비(tombstone) 를 항목 단위로 적용한
 * 아주 단순한 CRDT 다. 규모가 가족 몇 명이라 이 정도로 충분하다.
 */

export const BUILD = '__BUILD__';
export const DEFAULT_POINTS = 100;

const DAY_NAMES = ['월', '화', '수', '목', '금', '토', '일'];

// ------------------------------------------------------------------ 날짜

export function dateKey(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function parseKey(key) {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d);
}

/** 월=1 … 일=7 (ISO). JS 의 0=일요일과 다르므로 반드시 이걸 거친다. */
export function isoDow(d) {
  return d.getDay() === 0 ? 7 : d.getDay();
}

export function addDays(d, n) {
  const x = new Date(d);
  x.setDate(x.getDate() + n);
  return x;
}

export function minuteToText(minute) {
  const h = Math.floor(minute / 60);
  const m = minute % 60;
  const ampm = h < 12 ? '오전' : '오후';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return m === 0 ? `${ampm} ${h12}시` : `${ampm} ${h12}시 ${m}분`;
}

export function dayName(d) {
  return DAY_NAMES[isoDow(d) - 1];
}

export function daysText(days) {
  const s = [...days].sort((a, b) => a - b);
  if (s.join() === '1,2,3,4,5') return '평일';
  if (s.join() === '1,2,3,4,5,6,7') return '매일';
  if (!s.length) return '없음';
  return s.map((n) => DAY_NAMES[n - 1]).join(' ');
}

export function newId(prefix) {
  const rnd = Math.random().toString(36).slice(2, 10);
  return `${prefix}_${rnd}`;
}

/*
 * localStorage 는 브라우저(여기서는 WebView)가 주는 키-값 저장소다.
 * 서버 개발자가 오해하기 쉬운 특성 몇 가지:
 *
 *   - 문자열만 담는다. 그래서 넣고 뺄 때마다 JSON.stringify/parse 를 거친다.
 *   - 동기 API 다. 읽기도 쓰기도 메인 스레드를 잠깐 막는다. 큰 자료를 자주
 *     쓰면 화면이 버벅인다. 이 앱은 자료가 작아 문제되지 않는다.
 *   - 용량이 오리진당 5~10MB 로 작다. 넘으면 예외가 난다(아래 save 참고).
 *   - 격리 단위가 오리진(스킴+호스트+포트)이다. 우리 WebView 는 항상 같은
 *     주소를 열므로 같은 저장소를 계속 쓴다.
 *   - 앱을 지우면 같이 사라진다. 덮어 설치로는 안 사라진다. 이 차이가
 *     "업데이트하면 자료가 없어지나?"의 답이다. (docs/CODE-TOUR.md 참고)
 *
 * 실질적으로 이 앱의 단일 진실 공급원은 여기가 아니라 팀 저장소다.
 * localStorage 는 오프라인에서도 쓰기 위한 로컬 사본에 가깝다.
 */
// ------------------------------------------------------------------ 저장

const KEYS = { settings: 'henny.settings', plan: 'henny.plan', progress: 'henny.progress.' };

function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw);
  } catch (e) {
    // 형식이 안 맞으면 원본을 옆에 남겨 두고 기본값으로 시작한다.
    try { localStorage.setItem(key + '.broken', localStorage.getItem(key) || ''); } catch (_) {}
    return fallback;
  }
}

function save(key, value) {
  // 용량이 넘치면 예외가 난다. 여기서 터뜨리면 체크 한 번에 화면 전체가 죽으므로
  // 삼킨다. 대신 팀 저장소를 쓰고 있으면 다음 동기화 때 원격에 남는다.
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* 용량 초과 */ }
}

const MIGRATED = 'henny.migrated';

/**
 * Compose 시절 앱이 파일로 남긴 자료를 localStorage 로 옮긴다.
 *
 * 그때는 앱이 파일에, 지금은 WebView 가 localStorage 에 담는다. 자리가 달라서
 * 덮어 설치하면 화면이 빈 채로 뜬다. 파일은 남아 있으므로 처음 한 번 읽어 온다.
 * 자료 구조가 그때와 같아 그대로 넣으면 된다.
 *
 * 이미 이 기기에서 쓰던 자료가 있으면 건드리지 않는다. 덮어쓰면 최신 기록이
 * 옛 파일로 되돌아간다.
 */
export function importLegacy() {
  if (localStorage.getItem(MIGRATED)) return null;
  let raw = '';
  try { raw = window.HennyShell?.legacyData?.() || ''; } catch (_) { return null; }
  if (!raw) return null;

  let files;
  try { files = JSON.parse(raw); } catch (_) { return null; }

  const moved = [];
  const take = (fileName, key) => {
    if (!files[fileName]) return;
    if (localStorage.getItem(key)) return;
    localStorage.setItem(key, JSON.stringify(files[fileName]));
    moved.push(key);
  };

  take('settings.json', KEYS.settings);
  take('plan.json', KEYS.plan);
  Object.keys(files).forEach((name) => {
    const found = /^progress_(.+)\.json$/.exec(name);
    if (found) take(name, KEYS.progress + found[1]);
  });

  // 옮길 게 없었더라도 표시는 남긴다. 매번 다리를 두드릴 이유가 없다.
  localStorage.setItem(MIGRATED, String(Date.now()));
  return moved.length ? moved : null;
}

export const EMPTY_SETTINGS = {
  role: 'NONE',
  workerId: '',
  backend: 'NONE',
  apiKey: '',
  firebaseDb: '',
  planBin: '',
  progressBins: {},
  setupDone: false,
  lastSyncAt: 0,
  lastSyncError: '',
  managerSummaryMinute: 20 * 60 + 30,
  managerSummaryOn: true,
};

export const EMPTY_PLAN = {
  schema: 1, updatedAt: 0, workers: [], routines: [], assignments: [], reminders: [],
  // 지운 항목 id -> 지운 시각. 관리자가 여럿일 때 삭제가 되살아나지 않게 한다.
  deleted: {},
};

const emptyProgress = (workerId) => ({
  schema: 2, workerId, updatedAt: 0, days: {}, archive: [], ledger: [],
});

/**
 * 마일리지 원장.
 *
 * 예전에는 누적 마일리지를 "지금 남아 있는 체크 기록을 전부 다시 더해서" 냈다.
 * 그래서 값이 기록에 딸려 흔들렸다. 150일이 지나 기록이 정리되거나, 관리자가
 * 배점을 고치거나, 체크를 껐다 켜면 누적이 같이 움직였다. 무엇보다 모은 것을
 * "쓰는" 방법이 없었다. 상을 주고 차감할 자리가 아예 없었기 때문이다.
 *
 * 이제는 적립도 차감도 하나의 사실로 원장에 남긴다. 잔액은 그 사실들의 합이다.
 * 체크 기록을 정리해도 원장은 그대로이므로 누적이 줄지 않는다.
 *
 *   { id, at, delta, reason }
 *
 * id 는 같은 사건에 늘 같은 값이 나오게 짓는다. 작업 체크는
 * "d:<날짜>:<작업id>" 라서 껐다 켜도 줄이 하나뿐이고, 기기 두 대가 각자
 * 올려도 합칠 때 겹치지 않는다. 체크를 끄면 지우는 대신 delta 를 0 으로 둔다.
 * 지우면 상대 기기의 옛 줄이 되살아나기 때문이다(계획 쪽 삭제 표시와 같은 이유).
 */
function ledgerId(dateKeyStr, taskId) { return `d:${dateKeyStr}:${taskId}`; }

/**
 * 원장이 없던 시절의 자료에 원장을 한 번 만들어 준다.
 *
 * 남아 있는 일별 기록과 월별 합계를 훑어 그때의 적립을 그대로 옮긴다. 둘은
 * 겹치지 않으므로(정리된 것만 월별로 넘어간다) 이중으로 더해지지 않는다.
 * 결과는 예전 방식으로 계산하던 값과 같다. 한 번 만들고 나면 다시 만들지 않는다.
 */
function ensureLedger(p) {
  if (Array.isArray(p.ledger)) return p;
  const ledger = [];
  Object.entries(p.days || {}).forEach(([key, log]) => {
    (log.items || []).forEach((i) => {
      if (!i.doneAt) return;
      ledger.push({
        id: ledgerId(key, i.taskId), at: i.doneAt,
        delta: i.points || 0, reason: i.title || '',
      });
    });
  });
  (p.archive || []).forEach((m) => {
    if (!m.points) return;
    ledger.push({ id: `arch:${m.month}`, at: 0, delta: m.points, reason: `${m.month} 합계` });
  });
  return { ...p, schema: 2, ledger };
}

/*
 * 저장소 어댑터. 백엔드가 넷(NONE/FIREBASE/JSONBIN/HTTP)이지만 하는 일은 같다.
 * "주소 하나에 JSON 한 덩어리를 GET/PUT 한다"가 전부다. DB 라기보다 키-값 버킷에
 * 가깝고, 질의도 인덱스도 없다.
 *
 * supportsFieldFetch 가 중요한 최적화다. Firebase 는 문서 안의 필드 하나만
 * 골라 읽을 수 있어서, 15초마다 updatedAt(숫자 하나)만 확인하고 값이 달라졌을
 * 때만 전체를 받는다. 확인 한 번이 수십 바이트라 폴링이 부담되지 않는다.
 * 그게 안 되는 백엔드는 매번 전체를 받아야 해서 주기를 60초로 늘려 잡았다.
 */
// ------------------------------------------------------------------ 원격

class Remote {
  constructor(settings) {
    this.backend = settings.backend || 'NONE';
    this.apiKey = settings.apiKey || '';
  }

  get configured() { return this.backend !== 'NONE'; }

  get supportsFieldFetch() { return this.backend === 'FIREBASE'; }

  withAuth(handle) {
    if (!this.apiKey) return handle;
    return handle + (handle.includes('?') ? '&' : '?') + 'auth=' + encodeURIComponent(this.apiKey);
  }

  async get(handle) {
    if (!this.configured) return null;
    if (this.backend === 'JSONBIN') {
      const res = await fetch(`https://api.jsonbin.io/v3/b/${handle}/latest`, {
        headers: { 'X-Master-Key': this.apiKey, 'X-Bin-Meta': 'false' },
      });
      if (res.status === 404) return null;
      if (!res.ok) throw new Error(`저장소 오류 ${res.status}`);
      return await res.text();
    }
    const url = this.backend === 'FIREBASE' ? this.withAuth(handle) : handle;
    const res = await fetch(url);
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`저장소 오류 ${res.status}`);
    const text = await res.text();
    // Realtime Database 는 빈 경로에 리터럴 null 을 준다.
    return text.trim() === 'null' ? null : text;
  }

  async put(handle, body) {
    if (!this.configured) return;
    if (this.backend === 'JSONBIN') {
      const res = await fetch(`https://api.jsonbin.io/v3/b/${handle}`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-Master-Key': this.apiKey,
          'X-Bin-Versioning': 'false',
        },
        body,
      });
      if (!res.ok) throw new Error(`저장소 오류 ${res.status}`);
      return;
    }
    const url = this.backend === 'FIREBASE' ? this.withAuth(handle) : handle;
    let res = await fetch(url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    // npoint 계열은 PUT 을 받지 않는다.
    if (res.status === 405) {
      res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      });
    }
    if (!res.ok) throw new Error(`저장소 오류 ${res.status}`);
  }

  /** 변경 여부만 싸게 확인한다. 지원하지 않는 백엔드면 null. */
  async getUpdatedAt(handle) {
    if (!this.supportsFieldFetch) return null;
    const field = handle.replace(/\.json$/, '') + '/updatedAt.json';
    const text = await this.get(field);
    if (text == null) return null;
    const n = Number(text.trim().replace(/"/g, ''));
    return Number.isFinite(n) ? n : null;
  }
}

// ------------------------------------------------------------------ 본체

export class Repo {
  constructor() {
    this.settings = { ...EMPTY_SETTINGS, ...load(KEYS.settings, {}) };
    this.plan = { ...EMPTY_PLAN, ...load(KEYS.plan, {}) };
    this.progress = {};
    this.syncing = false;
    this.listeners = new Set();
    this.reloadProgress();
  }

  // --- 상태 알림
  subscribe(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit() { this.listeners.forEach((fn) => fn()); }

  reloadProgress() {
    const ids = new Set(this.plan.workers.map((w) => w.id));
    if (this.settings.workerId) ids.add(this.settings.workerId);
    const next = {};
    ids.forEach((id) => {
      next[id] = ensureLedger({ ...emptyProgress(id), ...load(KEYS.progress + id, {}) });
    });
    this.progress = next;
  }

  progressOf(workerId) { return this.progress[workerId] || emptyProgress(workerId); }
  worker(workerId) { return this.plan.workers.find((w) => w.id === workerId) || null; }
  workerName(workerId) { return this.worker(workerId)?.name || '이름 없음'; }

  updateSettings(patch) {
    this.settings = { ...this.settings, ...patch };
    save(KEYS.settings, this.settings);
    this.emit();
  }

  // --- 오늘 할 일
  tasksFor(workerId, date) {
    const dow = isoDow(date);
    const key = dateKey(date);
    const log = this.progressOf(workerId).days[key];
    const doneMap = {};
    (log?.items || []).forEach((it) => { doneMap[it.taskId] = it.doneAt ?? null; });

    const assignments = this.plan.assignments
      .filter((a) => a.workerId === workerId && a.date === key)
      .map((a) => ({
        id: a.id, title: a.title, dueMinute: a.dueMinute ?? null,
        remindBefore: a.remindBefore ?? 60, isAssignment: true,
        doneAt: doneMap[a.id] ?? null, points: a.points ?? DEFAULT_POINTS,
      }));

    const routines = this.plan.routines
      .filter((r) => r.workerId === workerId && r.active !== false && (r.days || []).includes(dow))
      .sort((a, b) => (a.order || 0) - (b.order || 0) || a.title.localeCompare(b.title))
      .map((r) => ({
        id: r.id, title: r.title, dueMinute: r.dueMinute ?? null,
        remindBefore: r.remindBefore ?? 60, isAssignment: false,
        doneAt: doneMap[r.id] ?? null, points: r.points ?? DEFAULT_POINTS,
      }));

    return [...assignments, ...routines];
  }

  expectedCount(workerId, date) {
    const dow = isoDow(date);
    const key = dateKey(date);
    return this.plan.routines.filter((r) => r.workerId === workerId && r.active !== false && (r.days || []).includes(dow)).length
      + this.plan.assignments.filter((a) => a.workerId === workerId && a.date === key).length;
  }

  toggle(workerId, date, taskId) {
    const now = Date.now();
    const items = this.tasksFor(workerId, date).map((t) => ({
      taskId: t.id,
      title: t.title,
      doneAt: t.id !== taskId ? t.doneAt : (t.doneAt ? null : now),
      points: t.points,
    }));
    const hit = items.find((i) => i.taskId === taskId);

    // 기록과 원장을 한 번에 저장한다. 따로 저장하면 그사이 동기화가 끼어들어
    // 둘이 어긋날 수 있다.
    const cur = ensureLedger(this.progressOf(workerId));
    const id = ledgerId(dateKey(date), taskId);
    const rest = (cur.ledger || []).filter((e) => e.id !== id);
    this.saveProgress(workerId, {
      ...cur,
      days: { ...cur.days, [dateKey(date)]: { date: dateKey(date), items, updatedAt: now } },
      // 껐으면 0 으로 덮는다. 지우지 않는 이유는 원장 설명 참고.
      ledger: [...rest, {
        id, at: now,
        delta: hit && hit.doneAt ? (hit.points || 0) : 0,
        reason: hit ? hit.title : '',
      }],
    });
  }

  writeDay(workerId, date, log) {
    const cur = ensureLedger(this.progressOf(workerId));
    this.saveProgress(workerId, { ...cur, days: { ...cur.days, [dateKey(date)]: log } });
  }

  /** 진행 기록을 저장하는 유일한 길목. 정리·저장·알림·업로드를 함께 한다. */
  saveProgress(workerId, next) {
    const updated = this.pruned({ ...next, workerId, updatedAt: Date.now() });
    this.progress = { ...this.progress, [workerId]: updated };
    save(KEYS.progress + workerId, updated);
    this.emit();
    this.schedulePush();
  }

  /** 오래된 날짜는 월별 합계로 접어 문서 크기를 작게 유지한다. */
  pruned(p, keepDays = 150) {
    const cutoff = addDays(new Date(), -keepDays);
    const keep = {};
    const rolled = {};
    (p.archive || []).forEach((m) => { rolled[m.month] = { ...m }; });
    let changed = false;
    Object.entries(p.days || {}).forEach(([k, log]) => {
      if (parseKey(k) < cutoff) {
        changed = true;
        const month = k.slice(0, 7);
        const prev = rolled[month] || { month, done: 0, total: 0, points: 0 };
        const done = (log.items || []).filter((i) => i.doneAt).length;
        rolled[month] = {
          month,
          done: prev.done + done,
          total: prev.total + (log.items || []).length,
          points: prev.points + (log.items || []).filter((i) => i.doneAt).reduce((s, i) => s + (i.points || 0), 0),
        };
      } else {
        keep[k] = log;
      }
    });
    if (!changed) return p;
    return { ...p, days: keep, archive: Object.values(rolled).sort((a, b) => a.month.localeCompare(b.month)) };
  }

  // --- 통계
  statFor(workerId, from, to) {
    const today = new Date();
    const last = to > today ? today : to;
    const logs = this.progressOf(workerId).days;
    const perDay = [];
    for (let d = new Date(from); d <= last; d = addDays(d, 1)) {
      const log = logs[dateKey(d)];
      if (log && (log.items || []).length) {
        const done = log.items.filter((i) => i.doneAt);
        perDay.push({
          date: new Date(d), done: done.length, total: log.items.length,
          points: done.reduce((s, i) => s + (i.points || 0), 0),
        });
      } else {
        perDay.push({ date: new Date(d), done: 0, total: this.expectedCount(workerId, d), points: 0 });
      }
    }
    const done = perDay.reduce((s, x) => s + x.done, 0);
    const total = perDay.reduce((s, x) => s + x.total, 0);
    return {
      done, total, perDay,
      points: perDay.reduce((s, x) => s + x.points, 0),
      rate: total === 0 ? 0 : Math.floor((done * 100) / total),
      perfectDays: perDay.filter((x) => x.total > 0 && x.done === x.total).length,
    };
  }

  weekStat(workerId, anchor = new Date()) {
    const monday = addDays(anchor, -(isoDow(anchor) - 1));
    return this.statFor(workerId, monday, addDays(monday, 6));
  }

  monthStat(workerId, anchor = new Date()) {
    const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
    const lastDay = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0);
    return this.statFor(workerId, first, lastDay);
  }

  /** 지금 잔액. 원장에 적힌 것을 모두 더한 값이다. */
  lifetimePoints(workerId) {
    return (this.progressOf(workerId).ledger || []).reduce((s, e) => s + (e.delta || 0), 0);
  }

  /** 원장 최근 순. 설정 화면에서 내역을 보여줄 때 쓴다. */
  ledgerOf(workerId, limit = 50) {
    return [...(this.progressOf(workerId).ledger || [])]
      .filter((e) => e.delta)
      .sort((a, b) => (b.at || 0) - (a.at || 0))
      .slice(0, limit);
  }

  /**
   * 원장에 한 줄 남긴다. 같은 id 가 있으면 그 줄을 고친다.
   * 체크를 껐을 때는 delta 0 으로 덮어 "적립이 없던 일이 됐다"를 남긴다.
   */
  postLedger(workerId, entry) {
    const cur = ensureLedger(this.progressOf(workerId));
    const rest = (cur.ledger || []).filter((e) => e.id !== entry.id);
    this.saveProgress(workerId, { ...cur, ledger: [...rest, entry] });
  }

  /** 관리자가 손으로 더하거나 뺀다. 상을 주거나 모은 것을 쓸 때. */
  adjustPoints(workerId, delta, reason) {
    if (!delta) return;
    this.postLedger(workerId, {
      id: newId('adj'), at: Date.now(), delta, reason: reason || '직접 조정',
    });
  }

  /** 할 일이 없던 날은 건너뛰고, 아직 진행 중인 오늘은 연속을 끊지 않는다. */
  streak(workerId) {
    const today = new Date();
    const logs = this.progressOf(workerId).days;
    let count = 0;
    let d = new Date(today);
    for (let i = 0; i < 400; i++) {
      const log = logs[dateKey(d)];
      const expected = log ? (log.items || []).length : this.expectedCount(workerId, d);
      if (expected === 0) { d = addDays(d, -1); continue; }
      const done = log ? (log.items || []).filter((x) => x.doneAt).length : 0;
      if (done >= expected) { count++; d = addDays(d, -1); continue; }
      if (dateKey(d) === dateKey(today)) { d = addDays(d, -1); continue; }
      return count;
    }
    return count;
  }

  // --- 계획 수정
  mutatePlan(fn) {
    const now = Date.now();
    const next = stampPlan(this.plan, { ...fn(this.plan) }, now);
    this.plan = next;
    save(KEYS.plan, next);
    this.reloadProgress();
    this.emit();
    this.schedulePush();
  }

  addWorker(name, emoji = '') {
    const worker = { id: newId('w'), name: name.trim(), emoji, colorSeed: 0 };
    this.mutatePlan((p) => ({
      ...p,
      workers: [...p.workers, worker],
      reminders: [...p.reminders, ...defaultReminders(worker.id)],
    }));
    return worker;
  }

  renameWorker(id, name) {
    this.mutatePlan((p) => ({
      ...p, workers: p.workers.map((w) => (w.id === id ? { ...w, name: name.trim() } : w)),
    }));
  }

  deleteWorker(id) {
    this.mutatePlan((p) => ({
      ...p,
      workers: p.workers.filter((w) => w.id !== id),
      routines: p.routines.filter((r) => r.workerId !== id),
      assignments: p.assignments.filter((a) => a.workerId !== id),
      reminders: p.reminders.filter((r) => r.workerId !== id),
    }));
    const bins = { ...this.settings.progressBins };
    delete bins[id];
    this.updateSettings({ progressBins: bins });
  }

  addRoutine(workerId, title, days, dueMinute, points = DEFAULT_POINTS) {
    this.mutatePlan((p) => {
      const order = Math.max(0, ...p.routines.filter((r) => r.workerId === workerId).map((r) => r.order || 0)) + 1;
      return {
        ...p,
        routines: [...p.routines, {
          id: newId('t'), workerId, title: title.trim(), days,
          dueMinute: dueMinute ?? null, remindBefore: 60, active: true, order, points,
        }],
      };
    });
  }

  updateRoutine(routine) {
    this.mutatePlan((p) => ({ ...p, routines: p.routines.map((r) => (r.id === routine.id ? routine : r)) }));
  }

  deleteRoutine(id) {
    this.mutatePlan((p) => ({ ...p, routines: p.routines.filter((r) => r.id !== id) }));
  }

  addAssignment(workerId, title, date, dueMinute, points = DEFAULT_POINTS) {
    this.mutatePlan((p) => {
      const cutoff = addDays(new Date(), -45);
      const fresh = p.assignments.filter((a) => parseKey(a.date) >= cutoff);
      return {
        ...p,
        assignments: [...fresh, {
          id: newId('a'), workerId, title: title.trim(), date: dateKey(date),
          dueMinute: dueMinute ?? null, remindBefore: 60, note: '', points,
        }],
      };
    });
  }

  deleteAssignment(id) {
    this.mutatePlan((p) => ({ ...p, assignments: p.assignments.filter((a) => a.id !== id) }));
  }

  upsertReminder(reminder) {
    this.mutatePlan((p) => {
      const exists = p.reminders.some((r) => r.id === reminder.id);
      return {
        ...p,
        reminders: exists ? p.reminders.map((r) => (r.id === reminder.id ? reminder : r)) : [...p.reminders, reminder],
      };
    });
  }

  deleteReminder(id) {
    this.mutatePlan((p) => ({ ...p, reminders: p.reminders.filter((r) => r.id !== id) }));
  }

  // --- 동기화
  remote() { return new Remote(this.settings); }

  schedulePush() {
    clearTimeout(this._pushTimer);
    this._pushTimer = setTimeout(() => this.sync().catch(() => {}), 2000);
  }

  flushPush() {
    if (this._pushTimer) {
      clearTimeout(this._pushTimer);
      this._pushTimer = null;
      this.sync().catch(() => {});
    }
  }

  async sync() {
    const net = this.remote();
    if (!net.configured) { publishSchedule(this); return; }
    if (this._syncing) return;
    this._syncing = true;
    this.syncing = true;
    this.emit();
    try {
      if (this.settings.role === 'MANAGER') await this.syncAsManager(net);
      else if (this.settings.role === 'WORKER') await this.syncAsWorker(net);
      this.updateSettings({ lastSyncAt: Date.now(), lastSyncError: '' });
    } catch (e) {
      this.updateSettings({ lastSyncError: e.message || '동기화 실패' });
    } finally {
      this._syncing = false;
      this.syncing = false;
      publishSchedule(this);
      this.emit();
    }
  }

  async syncAsManager(net) {
    const s = this.settings;
    if (s.planBin) {
      const text = await net.get(s.planBin);
      const remotePlan = text ? safeParse(text) : null;
      if (!remotePlan) {
        await net.put(s.planBin, JSON.stringify(this.plan));
      } else {
        // 관리자가 여럿일 수 있다. 늦게 올린 쪽으로 통째로 갈아치우면 그사이
        // 다른 관리자가 더한 작업이 사라진다. 항목 단위로 합친다.
        const remote = { ...EMPTY_PLAN, ...remotePlan };
        const merged = mergePlans(this.plan, remote);
        if (JSON.stringify(merged) !== JSON.stringify(this.plan)) {
          this.plan = merged;
          save(KEYS.plan, merged);
          this.reloadProgress();
        }
        if (JSON.stringify(merged) !== JSON.stringify(remote)) {
          await net.put(s.planBin, JSON.stringify(merged));
        }
      }
    }
    for (const worker of this.plan.workers) {
      const handle = s.progressBins[worker.id];
      if (!handle) continue;
      const text = await net.get(handle);
      const remoteProgress = text ? safeParse(text) : null;
      if (!remoteProgress) continue;
      const merged = mergeProgress(this.progressOf(worker.id), { ...remoteProgress, workerId: worker.id });
      this.progress = { ...this.progress, [worker.id]: merged };
      save(KEYS.progress + worker.id, merged);
    }
  }

  async syncAsWorker(net) {
    const s = this.settings;
    if (s.planBin) {
      const text = await net.get(s.planBin);
      const remotePlan = text ? safeParse(text) : null;
      if (remotePlan && remotePlan.updatedAt >= this.plan.updatedAt) {
        this.plan = { ...EMPTY_PLAN, ...remotePlan };
        save(KEYS.plan, this.plan);
        this.reloadProgress();
      }
    }
    const handle = s.progressBins[s.workerId];
    if (!handle) return;
    const text = await net.get(handle);
    const remoteProgress = text ? safeParse(text) : null;
    const mine = this.progressOf(s.workerId);
    const merged = remoteProgress
      ? mergeProgress(mine, { ...remoteProgress, workerId: s.workerId })
      : mine;
    if (JSON.stringify(merged) !== JSON.stringify(mine)) {
      this.progress = { ...this.progress, [s.workerId]: merged };
      save(KEYS.progress + s.workerId, merged);
    }
    if (!remoteProgress || merged.updatedAt > remoteProgress.updatedAt) {
      await net.put(handle, JSON.stringify(merged));
    }
  }

  /** 화면이 보이는 동안만 도는 확인 루프. updatedAt 한 값만 읽어 값싸게 감시한다. */
  startLive() {
    if (this._liveTimer) return;
    this.sync().catch(() => {});
    const tick = async () => {
      try { await this.pollForChanges(); } catch (_) {}
      this._liveTimer = setTimeout(tick, this.remote().supportsFieldFetch ? 15000 : 60000);
    };
    this._liveTimer = setTimeout(tick, this.remote().supportsFieldFetch ? 15000 : 60000);
  }

  stopLive() {
    clearTimeout(this._liveTimer);
    this._liveTimer = null;
    this.flushPush();
  }

  async pollForChanges() {
    const s = this.settings;
    const net = this.remote();
    if (!net.configured) return;
    if (!net.supportsFieldFetch) { await this.sync(); return; }

    let changed = false;
    if (s.planBin) {
      const at = await net.getUpdatedAt(s.planBin);
      if (at != null && at > this.plan.updatedAt) changed = true;
    }
    if (!changed && s.role === 'MANAGER') {
      for (const worker of this.plan.workers) {
        const handle = s.progressBins[worker.id];
        if (!handle) continue;
        const at = await net.getUpdatedAt(handle);
        if (at != null && at > this.progressOf(worker.id).updatedAt) { changed = true; break; }
      }
    }
    if (changed) await this.sync();
  }

  // --- 연결 코드
  encodeCode(payload) {
    const json = JSON.stringify(payload);
    const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(json)))
      .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    return 'HENNY2:' + b64;
  }

  pairingCode(workerId) {
    const s = this.settings;
    return this.encodeCode({
      v: 2, role: 'WORKER', backend: s.backend, apiKey: s.apiKey, firebaseDb: s.firebaseDb,
      planBin: s.planBin, bins: { [workerId]: s.progressBins[workerId] || '' },
      name: this.workerName(workerId),
    });
  }

  managerBackupCode() {
    const s = this.settings;
    return this.encodeCode({
      v: 2, role: 'MANAGER', backend: s.backend, apiKey: s.apiKey, firebaseDb: s.firebaseDb,
      planBin: s.planBin, bins: s.progressBins, name: '관리자',
    });
  }

  applyCode(raw) {
    const body = raw.trim().replace(/^HENNY[12]:/, '').trim();
    const b64 = body.replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    const payload = JSON.parse(new TextDecoder().decode(bytes));

    const isManager = payload.role === 'MANAGER';
    const workerId = Object.keys(payload.bins || {})[0] || '';
    if (!isManager && !workerId) throw new Error('코드에 작업자 정보가 없습니다.');

    this.updateSettings({
      role: payload.role,
      workerId: isManager ? '' : workerId,
      backend: payload.backend || 'NONE',
      apiKey: payload.apiKey || '',
      firebaseDb: payload.firebaseDb || '',
      planBin: payload.planBin || '',
      progressBins: payload.bins || {},
      setupDone: true,
    });
    this.reloadProgress();
    return payload.name || (isManager ? '관리자' : '작업자');
  }

  /** Firebase 는 쓰는 순간 경로가 생기므로 만들 것이 없다. 주소만 계산한다. */
  provision() {
    const s = this.settings;
    const base = (s.firebaseDb || '').trim().replace(/\/+$/, '');
    if (!base.startsWith('https://')) throw new Error('Firebase 데이터베이스 주소를 확인해 주세요.');
    // 이미 쓰던 경로가 있으면 반드시 그대로 다시 쓴다. 새로 만들면 자료가 고아가 된다.
    const found = /\/henny\/([^/]+)\/plan\.json$/.exec(s.planBin || '');
    const family = found ? found[1] : (newId('f') + newId('x')).replace(/_/g, '');
    const bins = { ...s.progressBins };
    this.plan.workers.forEach((w) => { bins[w.id] = `${base}/henny/${family}/progress/${w.id}.json`; });
    this.updateSettings({ planBin: `${base}/henny/${family}/plan.json`, progressBins: bins });
  }

  async wipeAll() {
    const net = this.remote();
    const s = this.settings;
    const EMPTY = '{"schema":1,"updatedAt":0}';
    if (net.configured) {
      for (const handle of Object.values(s.progressBins)) {
        if (handle) await net.put(handle, EMPTY);
      }
      if (s.role === 'MANAGER' && s.planBin) await net.put(s.planBin, EMPTY);
    }
    Object.keys(this.progress).forEach((id) => {
      const empty = emptyProgress(id);
      save(KEYS.progress + id, empty);
      this.progress[id] = empty;
    });
    this.plan = { ...EMPTY_PLAN, updatedAt: Date.now() };
    save(KEYS.plan, this.plan);
    this.emit();
    publishSchedule(this);
  }

  brokenKeys() {
    return Object.keys(localStorage).filter((k) => k.endsWith('.broken'));
  }

  clearBroken() {
    this.brokenKeys().forEach((k) => localStorage.removeItem(k));
  }
}

function defaultReminders(workerId) {
  return [
    { id: newId('r'), workerId, minute: 7 * 60 + 30, text: '오늘 작업을 확인하세요', onlyIfIncomplete: false, days: [1, 2, 3, 4, 5], enabled: true },
    { id: newId('r'), workerId, minute: 16 * 60, text: '아직 시작하지 않은 작업이 있습니다', onlyIfIncomplete: true, days: [1, 2, 3, 4, 5], enabled: true },
    { id: newId('r'), workerId, minute: 20 * 60, text: '마감 전 최종 점검', onlyIfIncomplete: true, days: [1, 2, 3, 4, 5], enabled: true },
  ];
}

function safeParse(text) {
  try { return JSON.parse(text); } catch (e) { return null; }
}

/** 날짜별로 더 최근에 손댄 쪽을 채택한다. */
const PLAN_LISTS = ['workers', 'routines', 'assignments', 'reminders'];
const TOMBSTONE_KEEP_MS = 90 * 24 * 60 * 60 * 1000;

/**
 * 바뀐 항목에만 시각을 찍고, 사라진 항목은 지웠다는 표시를 남긴다.
 *
 * 관리자가 둘 이상이면 두 기기가 각자 계획을 고친다. 문서 전체의 시각만
 * 보고 늦게 올린 쪽을 택하면, 그사이 다른 관리자가 더한 작업이 통째로
 * 사라진다. 항목마다 시각이 있어야 합칠 수 있다.
 *
 * 지웠다는 표시가 없으면 합칠 때 상대가 아직 들고 있는 항목이 되살아난다.
 * 그래서 삭제도 기록으로 남긴다. 90일이 지나면 정리한다.
 */
/**
 * 바뀐 항목에만 시각을 찍고, 사라진 항목은 묘비에 남긴다.
 *
 * 여기가 이 병합 방식의 핵심이다. 저장할 때마다 모든 항목의 updatedAt 을
 * 갱신하면(= 흔한 실수) 손대지도 않은 항목이 전부 "방금 수정됨"이 되어
 * 다른 관리자의 진짜 수정을 밀어낸다. 그래서 내용 비교를 먼저 한다.
 */
function stampPlan(prev, next, now) {
  const deleted = { ...(prev.deleted || {}), ...(next.deleted || {}) };

  PLAN_LISTS.forEach((list) => {
    const before = new Map((prev[list] || []).map((x) => [x.id, x]));
    const after = next[list] || [];

    next[list] = after.map((item) => {
      const old = before.get(item.id);
      // 내용이 그대로면 시각도 그대로 둔다. 안 그러면 안 고친 항목이
      // 매번 최신이 되어 다른 관리자의 수정을 밀어낸다.
      if (old && sameItem(old, item)) return old;
      return { ...item, updatedAt: now };
    });

    const alive = new Set(after.map((x) => x.id));
    before.forEach((_, id) => { if (!alive.has(id)) deleted[id] = now; });
  });

  const cutoff = now - TOMBSTONE_KEEP_MS;
  Object.keys(deleted).forEach((id) => { if (deleted[id] < cutoff) delete deleted[id]; });

  return { ...next, deleted, updatedAt: now };
}

/** updatedAt 을 뺀 내용이 같은지. */
function sameItem(a, b) {
  const strip = (x) => { const { updatedAt, ...rest } = x; return JSON.stringify(rest); };
  return strip(a) === strip(b);
}

/**
 * 계획 두 벌을 항목 단위로 합친다. 관리자가 여럿일 때 쓴다.
 * 같은 항목은 나중에 고친 쪽을, 지운 표시가 더 나중이면 삭제를 따른다.
 *
 * 항목 단위 LWW + 삭제 묘비. 교환법칙과 멱등성이 성립하므로 어느 쪽이 먼저
 * 올라오든, 같은 것이 두 번 합쳐지든 결과가 같다. (test_core.mjs 가 확인한다)
 *
 * 문서 전체에 시각 하나만 두면 왜 안 되는가:
 *   관리자 A 가 작업을 추가하고, 그 사이 B 가 다른 작업을 추가한 뒤 늦게 올리면
 *   B 의 문서가 통째로 이기면서 A 가 추가한 것이 사라진다. 실제로 그랬다.
 *
 * 묘비가 왜 필요한가:
 *   A 가 지운 항목을 B 는 아직 들고 있다. 항목 단위로만 합치면 "한쪽에만 있는
 *   것"으로 보여 되살아난다. 그래서 삭제도 사실로 기록해 둔다. 다만 영원히
 *   쌓이면 안 되므로 90일 뒤 정리한다(TOMBSTONE_KEEP_MS).
 *   그 기간보다 오래 꺼져 있던 기기가 돌아오면 지운 항목이 되살아날 수 있는데,
 *   가족용이라 감수한다.
 *
 * 진행 기록(progress)에는 이 장치가 없다. 작업자 한 명만 자기 것을 쓰기 때문에
 * 쓰는 사람이 하나뿐이라 충돌 자체가 나지 않는다.
 */
export function mergePlans(a, b) {
  const deleted = { ...(a.deleted || {}) };
  Object.entries(b.deleted || {}).forEach(([id, at]) => {
    if (!deleted[id] || at > deleted[id]) deleted[id] = at;
  });

  const out = { ...a, deleted, updatedAt: Math.max(a.updatedAt || 0, b.updatedAt || 0) };

  PLAN_LISTS.forEach((list) => {
    const byId = new Map();
    const order = [];
    [...(a[list] || []), ...(b[list] || [])].forEach((item) => {
      const cur = byId.get(item.id);
      if (!cur) { byId.set(item.id, item); order.push(item.id); return; }
      if ((item.updatedAt || 0) > (cur.updatedAt || 0)) byId.set(item.id, item);
    });
    out[list] = order
      .map((id) => byId.get(id))
      .filter((item) => !(deleted[item.id] > (item.updatedAt || 0)));
  });

  return out;
}

export function mergeProgress(a, b) {
  const days = { ...a.days };
  Object.entries(b.days || {}).forEach(([k, v]) => {
    const cur = days[k];
    if (!cur || (v.updatedAt || 0) > (cur.updatedAt || 0)) days[k] = v;
  });
  const byMonth = {};
  [...(a.archive || []), ...(b.archive || [])].forEach((m) => {
    const prev = byMonth[m.month];
    byMonth[m.month] = prev
      ? { month: m.month, done: Math.max(prev.done, m.done), total: Math.max(prev.total, m.total), points: Math.max(prev.points || 0, m.points || 0) }
      : { ...m };
  });
  // 원장은 id 로 합친다. 같은 사건은 나중에 적힌 쪽을 따르고, 한쪽에만 있으면
  // 그대로 남긴다. 문서 전체를 늦게 올린 쪽으로 덮으면 다른 기기가 그사이
  // 적립한 줄이 사라진다.
  const byId = {};
  [...(a.ledger || []), ...(b.ledger || [])].forEach((e) => {
    if (!e || !e.id) return;
    const prev = byId[e.id];
    if (!prev || (e.at || 0) >= (prev.at || 0)) byId[e.id] = e;
  });

  return {
    ...a,
    workerId: a.workerId || b.workerId,
    updatedAt: Math.max(a.updatedAt || 0, b.updatedAt || 0),
    days,
    archive: Object.values(byMonth).sort((x, y) => x.month.localeCompare(y.month)),
    ledger: Object.values(byId).sort((x, y) => (x.at || 0) - (y.at || 0)),
  };
}

// ------------------------------------------------------ 껍데기(안드로이드) 다리

/**
 * 알람은 네이티브가 담당한다. 웹은 "언제 무엇을 띄울지" 목록만 계산해 넘긴다.
 * 3일치를 미리 넘기므로 앱을 안 열어도 정기 작업 알림은 그대로 울린다.
 */
export function computeSchedule(repo, days = 3) {
  const s = repo.settings;
  const out = [];
  const now = Date.now();
  const today = new Date();

  const targets = s.role === 'WORKER' && s.workerId ? [s.workerId] : [];

  for (let offset = 0; offset < days; offset++) {
    const date = addDays(today, offset);
    const dow = isoDow(date);
    const at = (minute) => {
      const d = new Date(date);
      d.setHours(0, minute, 0, 0);
      return d.getTime();
    };

    targets.forEach((workerId) => {
      const tasks = repo.tasksFor(workerId, date);
      const undone = tasks.filter((t) => !t.doneAt);

      repo.plan.reminders
        .filter((r) => r.workerId === workerId && r.enabled !== false && (r.days || []).includes(dow))
        .forEach((r) => {
          // 오늘 이미 다 끝냈으면 "남았을 때만" 알림은 뺀다.
          if (offset === 0 && r.onlyIfIncomplete && undone.length === 0) return;
          const body = undone.length
            ? `${r.text}\n${undone.map((t) => `• ${t.title} (${t.points}P)`).join('\n')}\n남은 마일리지 ${undone.reduce((x, t) => x + t.points, 0)}P`
            : r.text;
          out.push({
            at: at(r.minute),
            title: undone.length ? `남은 작업 ${undone.length}개` : '오늘 작업 완료',
            body,
            tag: `rem:${r.id}:${dateKey(date)}`,
          });
        });

      tasks.forEach((t) => {
        if (t.dueMinute == null) return;
        const remindAt = t.dueMinute - (t.remindBefore ?? 60);
        if (remindAt < 0) return;
        if (offset === 0 && t.doneAt) return;
        out.push({
          at: at(remindAt),
          title: t.title,
          body: `${minuteToText(t.dueMinute)}까지입니다. (${t.points}P)`,
          tag: `due:${t.id}:${dateKey(date)}`,
        });
      });
    });

    if (s.role === 'MANAGER' && s.managerSummaryOn) {
      const summary = repo.plan.workers.map((w) => {
        const tasks = repo.tasksFor(w.id, date);
        const done = tasks.filter((t) => t.doneAt);
        const earned = done.reduce((x, t) => x + t.points, 0);
        return `${w.name} ${done.length}/${tasks.length} (${earned}P)`;
      }).join('  ');
      if (summary) {
        out.push({
          at: at(s.managerSummaryMinute),
          title: '오늘 작업 현황',
          body: summary,
          tag: `sum:${dateKey(date)}`,
        });
      }
    }
  }

  return out.filter((e) => e.at > now).sort((a, b) => a.at - b.at);
}

/**
 * 계산한 알림 일정을 네이티브 껍데기에 넘긴다.
 *
 * ***이 함수가 웹과 네이티브의 경계다.*** 여기서 넘어가는 것은
 * [{at, title, body, tag}, ...] 뿐이고, 껍데기는 이걸 해석하지 않고
 * 그대로 OS 알람에 건다.
 *
 * 그래서 알림 문구나 규칙을 아무리 바꿔도 APK 는 그대로다. 반대로 이 네 개로
 * 표현할 수 없는 알림(버튼 달린 알림 등)을 만들려면 그때는 APK 를 고쳐야 한다.
 *
 * 자료가 바뀔 때마다 불린다. 껍데기가 없으면(= 그냥 브라우저로 열었으면)
 * 조용히 넘어간다. 화면은 멀쩡히 돌아가고 알림만 안 온다.
 */
export function publishSchedule(repo) {
  try {
    const schedule = computeSchedule(repo);
    window.HennyShell?.setSchedule?.(JSON.stringify(schedule));
  } catch (e) { /* 브라우저에서 열었을 때는 껍데기가 없다 */ }
}

/**
 * 네이티브 껍데기로 가는 창구. 저쪽 MainActivity.Bridge 와 짝이다.
 *
 * 전부 try/catch 로 감싼 이유: 이 웹앱은 세 가지 환경에서 돌아간다.
 *   1) 앱 안의 WebView  -- HennyShell 이 있다. 알림이 온다.
 *   2) 그냥 모바일 브라우저 -- 없다. 화면은 다 되고 알림만 안 온다.
 *   3) 개발용 데스크톱 브라우저 -- 마찬가지.
 * 없는 것을 불렀을 때 화면이 죽으면 안 되므로 전부 방어한다.
 * present 로 확인해 설정 화면 안내 문구를 바꾼다.
 *
 * 여기 있는 목록이 곧 "APK 가 제공하는 기능 전부"다. 새 항목이 필요해지는
 * 순간이 재설치가 필요해지는 순간이다.
 */
export const shell = {
  get present() { return typeof window !== 'undefined' && !!window.HennyShell; },
  version() { try { return window.HennyShell?.version?.() || ''; } catch (e) { return ''; } },
  canNotify() { try { return !!window.HennyShell?.canNotify?.(); } catch (e) { return false; } },
  requestNotify() { try { window.HennyShell?.requestNotify?.(); } catch (e) {} },
  openNotificationSettings() { try { window.HennyShell?.openNotificationSettings?.(); } catch (e) {} },
  openAlarmSettings() { try { window.HennyShell?.openAlarmSettings?.(); } catch (e) {} },
};
