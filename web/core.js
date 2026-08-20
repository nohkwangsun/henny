/* 헨니 체크 — 핵심 로직
 *
 * 안드로이드 Repository.kt 를 그대로 옮긴 것이다. 데이터 구조와 판단 규칙이
 * 양쪽에서 같아야 기존 팀 저장소를 그대로 이어서 쓸 수 있다.
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
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* 용량 초과 */ }
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
};

const emptyProgress = (workerId) => ({
  schema: 1, workerId, updatedAt: 0, days: {}, archive: [],
});

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
      next[id] = { ...emptyProgress(id), ...load(KEYS.progress + id, {}) };
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
    this.writeDay(workerId, date, { date: dateKey(date), items, updatedAt: now });
  }

  writeDay(workerId, date, log) {
    const cur = this.progressOf(workerId);
    const updated = this.pruned({
      ...cur,
      workerId,
      updatedAt: Date.now(),
      days: { ...cur.days, [dateKey(date)]: log },
    });
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

  lifetimePoints(workerId) {
    const p = this.progressOf(workerId);
    const fromDays = Object.values(p.days || {})
      .reduce((s, log) => s + (log.items || []).filter((i) => i.doneAt).reduce((a, i) => a + (i.points || 0), 0), 0);
    const fromArchive = (p.archive || []).reduce((s, m) => s + (m.points || 0), 0);
    return fromDays + fromArchive;
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
    const next = { ...fn(this.plan), updatedAt: Date.now() };
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
      if (remotePlan && remotePlan.updatedAt > this.plan.updatedAt) {
        this.plan = { ...EMPTY_PLAN, ...remotePlan };
        save(KEYS.plan, this.plan);
        this.reloadProgress();
      } else if (!remotePlan || remotePlan.updatedAt < this.plan.updatedAt) {
        await net.put(s.planBin, JSON.stringify(this.plan));
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
function mergeProgress(a, b) {
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
  return {
    ...a,
    workerId: a.workerId || b.workerId,
    updatedAt: Math.max(a.updatedAt || 0, b.updatedAt || 0),
    days,
    archive: Object.values(byMonth).sort((x, y) => x.month.localeCompare(y.month)),
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

export function publishSchedule(repo) {
  try {
    const schedule = computeSchedule(repo);
    window.HennyShell?.setSchedule?.(JSON.stringify(schedule));
  } catch (e) { /* 브라우저에서 열었을 때는 껍데기가 없다 */ }
}

export const shell = {
  get present() { return typeof window !== 'undefined' && !!window.HennyShell; },
  version() { try { return window.HennyShell?.version?.() || ''; } catch (e) { return ''; } },
  canNotify() { try { return !!window.HennyShell?.canNotify?.(); } catch (e) { return false; } },
  requestNotify() { try { window.HennyShell?.requestNotify?.(); } catch (e) {} },
  openNotificationSettings() { try { window.HennyShell?.openNotificationSettings?.(); } catch (e) {} },
  openAlarmSettings() { try { window.HennyShell?.openAlarmSettings?.(); } catch (e) {} },
};
