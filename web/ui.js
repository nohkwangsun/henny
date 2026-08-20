/* 헨니 체크 — 화면
 *
 * 프레임워크 없이 상태가 바뀌면 다시 그리는 방식이다. 화면 수가 적고
 * 로직이 core.js 에 모여 있어 이 정도면 충분하다.
 */
import {
  Repo, BUILD, DEFAULT_POINTS, dateKey, addDays, dayName, daysText,
  minuteToText, isoDow, newId, publishSchedule, shell, importLegacy,
} from './core.js';

// Repo 를 만들기 전에 해야 한다. Repo 는 만들어질 때 저장된 값을 읽는다.
const restored = importLegacy();

const repo = new Repo();
const app = document.getElementById('app');
const ACCENTS = ['--w0', '--w1', '--w2', '--w3'];

let tab = 'TODAY';
let statsWorker = '';
let tasksWorker = '';
let modal = null;

// ------------------------------------------------------------------ 도구

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function accentOf(index) {
  return `var(${ACCENTS[((index % 4) + 4) % 4]})`;
}

function toast(text) {
  document.querySelectorAll('.toast').forEach((t) => t.remove());
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = text;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 2600);
}

function ring(done, total, accent, size = 132) {
  const r = (size - 14) / 2;
  const c = 2 * Math.PI * r;
  const ratio = total === 0 ? 0 : done / total;
  return `
    <div class="ring" style="width:${size}px;height:${size}px">
      <svg width="${size}" height="${size}">
        <circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none"
                stroke="var(--track)" stroke-width="14"/>
        <circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none"
                stroke="${accent}" stroke-width="14" stroke-linecap="round"
                stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - ratio)}"
                style="transition:stroke-dashoffset .45s ease"/>
      </svg>
      <div class="inner">
        <b>${done} / ${total}</b>
        <div class="muted">${total === 0 ? '없음' : Math.round(ratio * 100) + '%'}</div>
      </div>
    </div>`;
}

function bars(perDay, accent) {
  return `<div class="bars">${perDay.map((d) => {
    const ratio = d.total === 0 ? 0 : d.done / d.total;
    const h = ratio === 0 ? 0 : Math.max(8, Math.round(84 * ratio));
    return `<div class="col">
      <div class="bar"><div class="fill" style="height:${h}px;background:${accent};opacity:${ratio >= 1 ? 1 : 0.55}"></div></div>
      <small>${dayName(d.date)}</small>
    </div>`;
  }).join('')}</div>`;
}

function dots(perDay, accent) {
  return `<div class="dots">${perDay.map((d) => {
    const ratio = d.total === 0 ? -1 : d.done / d.total;
    const full = ratio >= 1;
    const style = full ? `background:${accent}` : ratio > 0
      ? `background:color-mix(in srgb, ${accent} ${Math.round(25 + 50 * ratio)}%, transparent)` : '';
    return `<div class="${full ? 'full' : ''}" style="${style}">${d.date.getDate()}</div>`;
  }).join('')}</div>`;
}

function pills(items, accent) {
  return `<div class="pills">${items.map(([label, value]) =>
    `<div class="pill" style="--accent:${accent}"><b>${esc(value)}</b><span>${esc(label)}</span></div>`).join('')}</div>`;
}

function syncLine() {
  const s = repo.settings;
  if (repo.syncing) return '맞추는 중…';
  if (s.backend === 'NONE') return '이 기기에만 저장';
  if (s.lastSyncError) return s.lastSyncError;
  if (!s.lastSyncAt) return '아직 맞춘 적 없음';
  const min = Math.floor((Date.now() - s.lastSyncAt) / 60000);
  if (min < 1) return '방금 맞춤';
  if (min < 60) return `${min}분 전 맞춤`;
  return `${Math.floor(min / 60)}시간 전 맞춤`;
}

// ------------------------------------------------------------------ 모달

function openModal(render) { modal = render; draw(); }
function closeModal() { modal = null; draw(); }

function modalShell(title, body, actions) {
  return `<div class="backdrop" data-close="1"><div class="modal">
    <h3>${esc(title)}</h3>${body}
    <div class="actions">${actions}</div>
  </div></div>`;
}

function promptModal({ title, label, value = '', placeholder = '', confirm = '저장', onConfirm }) {
  openModal(() => modalShell(title,
    `<label class="field"><span>${esc(label)}</span>
      <input id="m-input" value="${esc(value)}" placeholder="${esc(placeholder)}" autocomplete="off"></label>`,
    `<button class="plain" data-act="close">취소</button>
     <button class="plain" data-act="confirm">${esc(confirm)}</button>`));
  pendingConfirm = () => {
    const v = document.getElementById('m-input').value.trim();
    if (v) { closeModal(); onConfirm(v); }
  };
}

let pendingConfirm = null;

// ------------------------------------------------------------- 첫 설정 화면

let setupStep = 'ROLE';

function viewSetup() {
  const s = repo.settings;
  let body = '';

  if (setupStep === 'ROLE') {
    body = `<div class="card"><h3>시작하기</h3>
      <div class="stack">
        <button class="wide" data-act="role-manager">팀을 새로 만들래요</button>
        <p class="muted" style="margin:-4px 0 8px">작업자를 등록하고 할 일을 정합니다.</p>
        <button class="wide ghost" data-act="go-code">코드를 받았어요</button>
        <p class="muted" style="margin:-4px 0 0">작업자 연결, 관리자 추가, 기기 복구 모두 이 쪽입니다.</p>
      </div></div>`;
  } else if (setupStep === 'WORKERS') {
    body = `<div class="card"><h3>작업자를 등록해 주세요</h3>
      ${repo.plan.workers.length === 0
        ? '<p class="muted">작업자마다 할 일이 다르니 한 명씩 따로 등록합니다. 실명 대신 표시 이름을 써도 됩니다.</p>'
        : repo.plan.workers.map((w) => `<div class="list-row"><b class="grow">${esc(w.name)}</b></div>`).join('')}
      <div class="stack" style="margin-top:12px">
        <button class="wide ghost" data-act="add-worker">작업자 추가</button>
        <button class="wide" data-act="setup-next" ${repo.plan.workers.length ? '' : 'disabled'}>다음</button>
      </div></div>`;
  } else if (setupStep === 'STORAGE') {
    body = `<div class="card"><h3>기기끼리 자료를 주고받을 곳</h3>
      <p class="muted">구글이 주는 무료 저장 공간을 씁니다. 구글 계정만 있으면 되고,
      자료는 내 계정 안에 남습니다. 5분이면 끝납니다.</p>

      <ol class="guide">
        <li><a href="https://console.firebase.google.com/" target="_blank" rel="noopener">console.firebase.google.com</a> 열기
          <span class="muted">구글 계정으로 로그인합니다.</span></li>
        <li><b>프로젝트 만들기</b> → 이름은 아무거나 (예: henny)
          <span class="muted">애널리틱스는 꺼도 됩니다.</span></li>
        <li>왼쪽 메뉴 <b>빌드 → Realtime Database</b> → <b>데이터베이스 만들기</b>
          <span class="muted">위치는 그대로 두고, 보안 규칙은 <b>테스트 모드</b>를 고릅니다.</span></li>
        <li>화면 위에 나온 주소를 아래에 붙여넣기
          <span class="muted">https:// 로 시작해 .firebasedatabase.app 이나 .firebaseio.com 으로 끝납니다.</span></li>
      </ol>

      <div class="stack">
        <label class="field"><span>주소</span>
          <input id="fb-url" value="${esc(s.firebaseDb)}" placeholder="https://…firebasedatabase.app"
            autocomplete="off" autocapitalize="off" spellcheck="false"></label>
        <button class="wide" data-act="connect">연결하고 시작</button>
        <button class="wide plain" data-act="later">건너뛰기 (이 기기에서만 사용)</button>
      </div>

      <details style="margin-top:14px">
        <summary class="muted">테스트 모드가 뭔가요? / 30일 뒤에는요?</summary>
        <p class="muted">테스트 모드는 규칙이 30일 뒤 잠기게 되어 있습니다. 그전에
        Realtime Database 의 <b>규칙</b> 탭에서 아래를 붙여넣고 게시하세요.
        자료는 아무도 못 찾는 무작위 주소 아래에 두므로, 이 규칙으로도 연결 코드를
        받은 사람만 닿을 수 있습니다.</p>
        <pre class="code">{"rules":{"henny":{"$team":{".read":true,".write":true}}}}</pre>
      </details>

      <details style="margin-top:8px">
        <summary class="muted">비밀키를 쓰고 싶어요</summary>
        <div class="stack" style="margin-top:8px">
          <label class="field"><span>비밀키 (비워두면 규칙만으로 씁니다)</span>
            <input id="fb-key" value="${esc(s.apiKey)}" autocomplete="off" spellcheck="false"></label>
        </div>
      </details>
    </div>`;
  } else if (setupStep === 'CODE') {
    body = `<div class="card"><h3>코드를 붙여넣으세요</h3>
      <p class="muted">관리자 기기의 <b>설정</b> 탭에서 코드를 받을 수 있습니다.
      작업자 코드를 넣으면 작업자 기기가 되고, 관리자 코드를 넣으면 관리자 기기가 됩니다.</p>
      <div class="stack">
        <label class="field"><span>코드</span>
          <input id="code-input" placeholder="HENNY2:..." autocomplete="off" spellcheck="false"></label>
        <button class="wide" data-act="apply-code">연결하기</button>
        <button class="wide plain" data-act="solo">코드 없이 이 기기에서만 쓰기</button>
      </div></div>`;
  } else if (setupStep === 'SOLO') {
    body = `<div class="card"><h3>표시 이름을 입력하세요</h3>
      <div class="stack">
        <label class="field"><span>이름 또는 표시 이름</span>
          <input id="solo-name" placeholder="예: 김민준, 야간조"></label>
        <button class="wide" data-act="solo-start">시작하기</button>
      </div></div>`;
  }

  return `<div style="padding-top:24px">
    <h1>헨니 체크</h1>
    <p class="muted">오늘 할 작업을 한눈에.</p>
    <div style="height:16px"></div>
    ${body}
    ${setupStep !== 'ROLE' ? '<button class="plain" data-act="setup-back">← 처음으로</button>' : ''}
  </div>`;
}

// -------------------------------------------------------------- 작업자 화면

function viewWorker() {
  const s = repo.settings;
  const today = new Date();
  const worker = repo.worker(s.workerId);
  const index = repo.plan.workers.findIndex((w) => w.id === s.workerId);
  const accent = accentOf(Math.max(0, index));
  const tasks = repo.tasksFor(s.workerId, today);
  const done = tasks.filter((t) => t.doneAt);
  const earned = done.reduce((x, t) => x + t.points, 0);
  const offered = tasks.reduce((x, t) => x + t.points, 0);
  const week = repo.weekStat(s.workerId);

  const headline = tasks.length === 0 ? '오늘 배정된 작업이 없습니다'
    : done.length === tasks.length ? '오늘 작업을 모두 마쳤습니다'
    : `${tasks.length - done.length}개 남았습니다`;

  return `
    <div class="spread" style="margin-bottom:6px">
      <div class="grow">
        <h1>${esc(worker?.name || '나')}</h1>
        <div class="muted">${today.getMonth() + 1}월 ${today.getDate()}일 ${dayName(today)}요일 · ${esc(syncLine())}</div>
      </div>
      <button class="icon" data-act="sync" title="지금 맞추기">⟳</button>
      <button class="icon" data-act="open-settings" title="설정">⚙</button>
    </div>

    <div class="center" style="margin:14px 0">${ring(done.length, tasks.length, accent)}</div>
    <div class="center" style="font-weight:600;margin-bottom:14px;color:${done.length === tasks.length && tasks.length ? accent : 'inherit'}">${esc(headline)}</div>
    ${pills([['오늘 모은 마일리지', earned + 'P'], ['오늘 걸린 마일리지', offered + 'P']], accent)}
    <div style="height:16px"></div>

    ${tasks.map((t) => `
      <button class="task ${t.doneAt ? 'done' : ''}" style="--accent:${accent}" data-act="toggle" data-id="${t.id}">
        <span class="box">${t.doneAt ? '✓' : ''}</span>
        <span class="grow">
          ${t.isAssignment ? '<span class="badge">임시 작업</span><br>' : ''}
          <span class="title">${esc(t.title)}</span>
          ${t.dueMinute != null ? `<br><span class="muted">${minuteToText(t.dueMinute)}까지</span>` : ''}
        </span>
        <span class="pts">${t.points}P</span>
      </button>`).join('')}

    <div class="card" style="--accent:${accent}">
      <h3>이번 주</h3>
      ${bars(week.perDay, accent)}
      <div style="height:14px"></div>
      ${pills([['달성률', week.rate + '%'], ['이번 주', week.points + 'P'], ['연속', repo.streak(s.workerId) + '일']], accent)}
      <div class="spread" style="margin-top:14px">
        <span class="muted">지금까지 모은 마일리지</span>
        <b style="font-size:20px;color:${accent}">${repo.lifetimePoints(s.workerId)}P</b>
      </div>
    </div>`;
}

// -------------------------------------------------------------- 관리자 화면

function viewManagerToday() {
  const today = new Date();
  return `
    <div class="spread">
      <div class="grow"><h1>오늘 현황</h1><div class="muted ${repo.settings.lastSyncError ? 'err' : ''}">${esc(syncLine())}</div></div>
      <button class="icon" data-act="sync" title="새로고침">⟳</button>
    </div>
    ${repo.plan.workers.length === 0
      ? '<div class="card">아직 등록된 작업자가 없습니다. 설정 탭에서 추가하세요.</div>' : ''}
    ${repo.plan.workers.map((w, i) => {
      const accent = accentOf(i);
      const tasks = repo.tasksFor(w.id, today);
      const done = tasks.filter((t) => t.doneAt);
      const earned = done.reduce((x, t) => x + t.points, 0);
      const offered = tasks.reduce((x, t) => x + t.points, 0);
      return `<div class="card" style="--accent:${accent}">
        <div class="row">
          <div class="grow">
            <h2>${esc(w.name)}</h2>
            <div class="muted">${tasks.length === 0 ? '오늘 배정된 작업이 없습니다'
              : done.length === tasks.length ? '오늘 작업 모두 완료' : `${tasks.length - done.length}개 남음`}</div>
            ${tasks.length ? `<div style="color:${accent};font-weight:700;font-size:13px">마일리지 ${earned} / ${offered} P</div>` : ''}
          </div>
          ${ring(done.length, tasks.length, accent, 86)}
        </div>
        <div style="height:10px"></div>
        ${tasks.map((t) => `<div class="row" style="padding:5px 0">
          <span style="width:14px;height:14px;border-radius:99px;flex:0 0 auto;background:${t.doneAt ? accent : 'var(--track)'}"></span>
          <span class="grow" style="${t.doneAt ? 'color:var(--muted);text-decoration:line-through' : ''}">${esc(t.title)}${t.isAssignment ? ' (임시)' : ''}</span>
          <span class="muted" style="font-size:12px">${t.doneAt ? doneAtText(t.doneAt) + ' 완료'
            : t.dueMinute != null ? minuteToText(t.dueMinute) + '까지' : ''}</span>
          <b style="font-size:13px;color:${t.doneAt ? accent : 'var(--muted)'}">${t.points}P</b>
        </div>`).join('')}
        <div style="height:8px"></div>
        <button class="ghost" data-act="assign" data-id="${w.id}">＋ 임시 작업 배정</button>
      </div>`;
    }).join('')}`;
}

function doneAtText(ms) {
  const d = new Date(ms);
  return minuteToText(d.getHours() * 60 + d.getMinutes());
}

function workerChips(selected, act) {
  return `<div class="chips">${repo.plan.workers.map((w, i) =>
    `<button class="chip ${w.id === selected ? 'on' : ''}" style="--accent:${accentOf(i)}"
      data-act="${act}" data-id="${w.id}">${esc(w.name)}</button>`).join('')}</div>`;
}

function viewManagerStats() {
  if (!statsWorker) statsWorker = repo.plan.workers[0]?.id || '';
  const i = Math.max(0, repo.plan.workers.findIndex((w) => w.id === statsWorker));
  const accent = accentOf(i);
  if (!statsWorker) return '<h1>통계</h1><div class="card">작업자를 먼저 추가하세요.</div>';
  const week = repo.weekStat(statsWorker);
  const month = repo.monthStat(statsWorker);
  return `<h1>통계</h1><div style="height:12px"></div>
    ${workerChips(statsWorker, 'pick-stats')}
    <div class="card" style="--accent:${accent}"><h3>이번 주</h3>
      ${bars(week.perDay, accent)}<div style="height:14px"></div>
      ${pills([['달성률', week.rate + '%'], ['마일리지', week.points + 'P'], ['완벽한 날', week.perfectDays + '일']], accent)}
    </div>
    <div class="card" style="--accent:${accent}"><h3>${new Date().getMonth() + 1}월</h3>
      ${dots(month.perDay, accent)}<div style="height:14px"></div>
      ${pills([['달성률', month.rate + '%'], ['마일리지', month.points + 'P'], ['완벽한 날', month.perfectDays + '일']], accent)}
      <div class="muted" style="margin-top:10px">전체 ${month.total}개 중 ${month.done}개 완료 · 누적 마일리지 ${repo.lifetimePoints(statsWorker)}P</div>
    </div>`;
}

function viewManagerTasks() {
  if (!tasksWorker) tasksWorker = repo.plan.workers[0]?.id || '';
  if (!tasksWorker) return '<h1>작업 관리</h1><div class="card">작업자를 먼저 추가하세요.</div>';
  const routines = repo.plan.routines.filter((r) => r.workerId === tasksWorker).sort((a, b) => (a.order || 0) - (b.order || 0));
  const reminders = repo.plan.reminders.filter((r) => r.workerId === tasksWorker).sort((a, b) => a.minute - b.minute);
  return `<h1>작업 관리</h1><div style="height:12px"></div>
    ${workerChips(tasksWorker, 'pick-tasks')}
    <div class="card"><h3>정기 작업</h3>
      ${routines.length === 0 ? '<p class="muted">아직 없습니다. 아래에서 추가하세요.</p>' : ''}
      ${routines.map((r) => `<div class="list-row">
        <div class="grow"><b style="${r.active === false ? 'color:var(--muted)' : ''}">${esc(r.title)}</b>
          <div class="muted">${r.points ?? DEFAULT_POINTS}P · ${daysText(r.days || [])}${r.dueMinute != null ? ' · ' + minuteToText(r.dueMinute) + '까지' : ''}${r.active === false ? ' · 쉼' : ''}</div></div>
        <button class="plain" data-act="edit-routine" data-id="${r.id}">수정</button>
      </div>`).join('')}
      <div style="height:8px"></div>
      <button class="ghost" data-act="add-routine">＋ 작업 추가</button>
    </div>
    <div class="card"><h3>점검 알림</h3>
      <p class="muted">정해둔 시각에 작업자 기기가 알립니다.</p>
      ${reminders.map((r) => `<div class="list-row">
        <div class="grow" data-act="edit-reminder" data-id="${r.id}"><b>${minuteToText(r.minute)}</b>
          <div class="muted">${esc(r.text)}${r.onlyIfIncomplete ? ' · 남았을 때만' : ''}</div></div>
        <button class="plain" data-act="toggle-reminder" data-id="${r.id}">${r.enabled === false ? '켜기' : '끄기'}</button>
        <button class="danger" data-act="del-reminder" data-id="${r.id}">삭제</button>
      </div>`).join('')}
      <div style="height:8px"></div>
      <button class="ghost" data-act="add-reminder">＋ 알림 추가</button>
    </div>`;
}

function viewSettings(isManager) {
  const s = repo.settings;
  const broken = repo.brokenKeys();
  return `<h1>설정</h1><div style="height:12px"></div>
    <div class="card"><h3>알림</h3>
      ${shell.present
        ? `<p class="muted">${shell.canNotify() ? '알림이 켜져 있습니다.' : '<span class="err">알림이 꺼져 있어 아무것도 울리지 않습니다.</span>'}</p>
           <div class="row" style="flex-wrap:wrap;gap:6px">
             ${shell.canNotify() ? '' : '<button data-act="req-notify">알림 켜기</button>'}
             <button class="ghost" data-act="open-noti">알림 설정 열기</button>
             <button class="ghost" data-act="open-alarm">알람 권한 열기</button>
           </div>
           <p class="muted" style="margin-top:10px">삼성 갤럭시는 <b>설정 → 앱 → 헨니 체크 → 배터리 → 제한 없음</b>도 함께 해주세요.
           안 그러면 절전이 알람을 건너뜁니다.</p>`
        : '<p class="muted">브라우저로 열면 알림이 울리지 않습니다. 헨니 체크 앱으로 열어주세요.</p>'}
    </div>

    ${isManager ? `<div class="card"><h3>작업자</h3>
      ${repo.plan.workers.map((w) => `<div class="list-row">
        <div class="grow" data-act="rename-worker" data-id="${w.id}"><b>${esc(w.name)}</b>
          <div class="muted">${s.progressBins[w.id] ? '연결됨' : '저장 공간 없음'} · 눌러서 이름 수정</div></div>
        <button class="plain" data-act="show-code" data-id="${w.id}">연결 코드</button>
        <button class="danger" data-act="del-worker" data-id="${w.id}">삭제</button>
      </div>`).join('')}
      <div style="height:8px"></div>
      <button class="ghost" data-act="add-worker">작업자 추가</button>
    </div>` : ''}

    <div class="card"><h3>팀 저장소</h3>
      ${isManager ? `
        <div class="chips" style="margin-bottom:10px">
          ${['NONE', 'FIREBASE', 'JSONBIN', 'HTTP'].map((b) => `<button class="chip ${s.backend === b ? 'on' : ''}"
            style="--accent:var(--teal)" data-act="backend" data-id="${b}">${{ NONE: '안 씀', FIREBASE: '구글 Firebase', JSONBIN: 'JSONBin', HTTP: '직접 주소' }[b]}</button>`).join('')}
        </div>
        ${s.backend === 'FIREBASE' ? `<div class="stack">
          <label class="field"><span>실시간 데이터베이스 주소</span>
            <input id="fb-url" value="${esc(s.firebaseDb)}" placeholder="https://…firebasedatabase.app"></label>
          <label class="field"><span>비밀키 (규칙으로 열어뒀다면 비워두세요)</span>
            <input id="fb-key" value="${esc(s.apiKey)}"></label>
          <button data-act="connect">연결하기</button>
        </div>` : ''}
        ${s.backend === 'JSONBIN' ? `<div class="stack">
          <label class="field"><span>JSONBin Master Key</span><input id="fb-key" value="${esc(s.apiKey)}"></label>
          <p class="muted">JSONBin 은 문서를 미리 만들어야 합니다. 안드로이드 앱에서 만든 주소를 복구 코드로 옮겨오세요.</p>
        </div>` : ''}`
        : `<p>${s.backend === 'NONE' ? '이 기기에서만 사용 중' : '관리자 기기와 연결되어 있습니다.'}</p>
           <button class="ghost" data-act="repair">연결 코드 다시 입력</button>`}
      <div style="height:12px"></div>
      <button data-act="sync">지금 동기화</button>
      ${s.lastSyncError ? `<div class="muted err" style="margin-top:8px">${esc(s.lastSyncError)}</div>` : ''}
    </div>

    <div class="card"><h3>이 기기</h3>
      <p>${s.role === 'MANAGER' ? '관리자용으로 설정됨' : esc(repo.workerName(s.workerId)) + '의 기기'}</p>
      <div class="muted">웹 ${esc(BUILD)}${shell.present ? ' · 앱 ' + esc(shell.version()) : ''}</div>
      ${broken.length ? `<p class="muted err" style="margin-top:10px">이전 저장 형식을 읽지 못해 새로 시작했습니다.
        팀 저장소를 쓰고 있으면 잠시 뒤 자료가 다시 내려옵니다.
        <button class="plain" data-act="clear-broken">알림 지우기</button></p>` : ''}
      ${isManager ? `<div style="margin-top:12px;border-top:1px solid var(--line);padding-top:12px">
        <b>관리자 코드</b>
        <p class="muted">관리자는 여러 명이어도 됩니다. 다른 사람 기기에서 이 코드를 넣으면
        같은 팀의 관리자가 되어 함께 작업을 정할 수 있습니다.
        기기를 바꾸거나 앱을 지웠다 깔 때도 이 코드로 돌아옵니다. 지금 저장해 두세요.</p>
        <button class="ghost" data-act="backup-code">관리자 코드 보기</button>
      </div>` : ''}
      <div style="margin-top:12px">
        <button class="danger" data-act="reset">이 기기 설정 초기화</button>
        <button class="danger" data-act="wipe">모든 데이터 지우기</button>
      </div>
    </div>`;
}

function viewManager() {
  const body = tab === 'TODAY' ? viewManagerToday()
    : tab === 'STATS' ? viewManagerStats()
    : tab === 'TASKS' ? viewManagerTasks()
    : viewSettings(true);
  return body + `
    <nav class="tabs">
      ${[['TODAY', '🏠', '오늘'], ['STATS', '📅', '통계'], ['TASKS', '✔', '작업'], ['SET', '⚙', '설정']]
        .map(([k, i, l]) => `<button class="${tab === k ? 'on' : ''}" data-act="tab" data-id="${k}">
          <span class="ico">${i}</span>${l}</button>`).join('')}
    </nav>`;
}

// ------------------------------------------------------------------ 그리기

let workerSettingsOpen = false;

function draw() {
  const s = repo.settings;
  let html;
  if (!s.setupDone) html = viewSetup();
  else if (s.role === 'MANAGER') html = viewManager();
  else if (workerSettingsOpen) html = viewSettings(false) + '<button class="plain" data-act="close-settings">← 돌아가기</button>';
  else html = viewWorker();
  app.innerHTML = html + (modal ? modal() : '');
}

// ------------------------------------------------------------------ 동작

const ACTIONS = {
  'role-manager': () => { repo.updateSettings({ role: 'MANAGER' }); setupStep = 'WORKERS'; },
  'role-worker': () => { repo.updateSettings({ role: 'WORKER' }); setupStep = 'CODE'; },
  'go-code': () => { setupStep = 'CODE'; },
  'setup-back': () => { setupStep = 'ROLE'; },
  'setup-next': () => { setupStep = 'STORAGE'; },
  'solo': () => { setupStep = 'SOLO'; },

  'add-worker': () => promptModal({
    title: '작업자 추가', label: '이름 또는 표시 이름', placeholder: '예: 김민준, 야간조',
    confirm: '추가', onConfirm: (name) => { repo.addWorker(name); draw(); },
  }),

  'connect': () => {
    const raw = document.getElementById('fb-url')?.value.trim() || '';
    const key = document.getElementById('fb-key')?.value.trim() || '';
    // 콘솔에서 복사하면 뒤에 경로나 물음표가 붙어 오는 일이 잦다. 앞부분만 남긴다.
    const url = raw.replace(/\/+$/, '').replace(/\/[^/]*\.json.*$/, '').split('?')[0];

    if (!url) { toast('주소를 붙여넣어 주세요.'); return; }
    if (!/^https:\/\//.test(url)) { toast('https:// 로 시작하는 주소여야 합니다.'); return; }
    if (!/firebase(io|database)/.test(url)) {
      toast('Realtime Database 주소가 맞는지 확인해 주세요.');
      return;
    }

    repo.updateSettings({ backend: 'FIREBASE', firebaseDb: url, apiKey: key });
    try {
      repo.provision();
      if (!repo.settings.setupDone) repo.updateSettings({ setupDone: true });
      toast('연결했습니다.');
      repo.sync();
    } catch (e) { toast('실패: ' + e.message); }
  },

  'later': () => repo.updateSettings({ backend: 'NONE', setupDone: true }),

  'apply-code': () => {
    const raw = document.getElementById('code-input')?.value || '';
    try {
      const name = repo.applyCode(raw);
      toast(`${name} 기기로 연결됐습니다.`);
      repo.sync();
    } catch (e) { toast('코드를 다시 확인해 주세요.'); }
  },

  'solo-start': () => {
    const name = document.getElementById('solo-name')?.value.trim();
    if (!name) return;
    const w = repo.addWorker(name);
    repo.updateSettings({ role: 'WORKER', workerId: w.id, backend: 'NONE', setupDone: true });
  },

  'toggle': (id) => repo.toggle(repo.settings.workerId, new Date(), id),
  'sync': () => { repo.sync(); toast('맞추는 중…'); },
  'tab': (id) => { tab = id; },
  'pick-stats': (id) => { statsWorker = id; },
  'pick-tasks': (id) => { tasksWorker = id; },
  'open-settings': () => { workerSettingsOpen = true; },
  'close-settings': () => { workerSettingsOpen = false; },

  'assign': (id) => openAssignmentModal(id),
  'add-routine': () => openRoutineModal(null),
  'edit-routine': (id) => openRoutineModal(repo.plan.routines.find((r) => r.id === id)),
  'add-reminder': () => openReminderModal({
    id: newId('r'), workerId: tasksWorker, minute: 17 * 60, text: '작업 확인하기',
    onlyIfIncomplete: true, days: [1, 2, 3, 4, 5], enabled: true,
  }),
  'edit-reminder': (id) => openReminderModal(repo.plan.reminders.find((r) => r.id === id)),
  'toggle-reminder': (id) => {
    const r = repo.plan.reminders.find((x) => x.id === id);
    repo.upsertReminder({ ...r, enabled: r.enabled === false });
  },
  'del-reminder': (id) => repo.deleteReminder(id),

  'rename-worker': (id) => promptModal({
    title: '이름 바꾸기', label: '이름', value: repo.workerName(id),
    onConfirm: (name) => { repo.renameWorker(id, name); draw(); },
  }),
  'del-worker': (id) => {
    openModal(() => modalShell(`${repo.workerName(id)} 삭제`,
      `<p>${esc(repo.workerName(id))}의 정기 작업, 임시 작업, 알림이 함께 지워집니다. 지금까지의 기록은 남습니다.</p>`,
      `<button class="plain" data-act="close">취소</button><button class="danger" data-act="confirm">삭제</button>`));
    pendingConfirm = () => { closeModal(); repo.deleteWorker(id); };
  },

  'show-code': (id) => showCode('연결 코드', repo.pairingCode(id),
    '작업자 기기에서 앱을 열고 이 코드를 붙여넣으면 끝입니다.'),
  'backup-code': () => showCode('관리자 코드', repo.managerBackupCode(),
    '관리자를 늘리거나 기기를 되살릴 때 씁니다. 팀 저장소 접속 정보가 들어 있으니 팀 밖으로는 보내지 마세요.'),
  'repair': () => promptModal({
    title: '연결 코드 입력', label: '연결 코드 또는 복구 코드', placeholder: 'HENNY2:...',
    confirm: '연결', onConfirm: (raw) => {
      try { const n = repo.applyCode(raw); toast(`${n} 기기로 연결됐습니다.`); repo.sync(); }
      catch (e) { toast('코드를 읽지 못했습니다.'); }
      draw();
    },
  }),

  'backend': (id) => repo.updateSettings({ backend: id }),
  'req-notify': () => shell.requestNotify(),
  'open-noti': () => shell.openNotificationSettings(),
  'open-alarm': () => shell.openAlarmSettings(),
  'clear-broken': () => { repo.clearBroken(); },

  'reset': () => {
    openModal(() => modalShell('이 기기 설정 초기화',
      '<p>이 기기의 연결 설정을 지우고 처음부터 다시 설정합니다. 팀 저장소의 자료는 그대로입니다.</p>',
      `<button class="plain" data-act="close">취소</button><button class="danger" data-act="confirm">초기화</button>`));
    pendingConfirm = () => { localStorage.clear(); location.reload(); };
  },

  'wipe': () => {
    openModal(() => modalShell('모든 데이터 지우기',
      '<p>작업자 정보, 정기 작업, 임시 작업, 알림, 지금까지의 체크 기록을 이 기기와 팀 저장소에서 모두 지웁니다. 되돌릴 수 없습니다.</p>',
      `<button class="plain" data-act="close">취소</button><button class="danger" data-act="confirm">지우기</button>`));
    pendingConfirm = async () => {
      closeModal();
      try { await repo.wipeAll(); toast('모두 지웠습니다.'); }
      catch (e) { toast('실패: ' + e.message); }
    };
  },
};

function showCode(title, code, note) {
  openModal(() => modalShell(title,
    `<p class="muted">${esc(note)}</p><div class="code">${esc(code)}</div>`,
    `<button class="plain" data-act="copy" data-id="${esc(code)}">복사</button>
     <button class="plain" data-act="close">닫기</button>`));
}

function openAssignmentModal(workerId) {
  let points = DEFAULT_POINTS;
  openModal(() => modalShell(`${repo.workerName(workerId)}에게 임시 작업`,
    `<div class="stack">
      <label class="field"><span>작업 내용</span><input id="m-title" placeholder="예: 창고 재고 확인"></label>
      <label class="field"><span>마일리지</span><input id="m-points" type="number" inputmode="numeric" value="${points}"></label>
      <label class="field"><span>마감 시각 (비워도 됨)</span><input id="m-due" type="time"></label>
    </div>`,
    `<button class="plain" data-act="close">취소</button><button class="plain" data-act="confirm">배정</button>`));
  pendingConfirm = () => {
    const title = document.getElementById('m-title').value.trim();
    if (!title) return;
    const pts = parseInt(document.getElementById('m-points').value, 10) || 0;
    const due = document.getElementById('m-due').value;
    const dueMinute = due ? Number(due.slice(0, 2)) * 60 + Number(due.slice(3, 5)) : null;
    closeModal();
    repo.addAssignment(workerId, title, new Date(), dueMinute, pts);
  };
}

function openRoutineModal(routine) {
  let days = routine ? [...(routine.days || [])] : [1, 2, 3, 4, 5];
  const timeValue = routine?.dueMinute != null
    ? `${String(Math.floor(routine.dueMinute / 60)).padStart(2, '0')}:${String(routine.dueMinute % 60).padStart(2, '0')}` : '';

  const render = () => modalShell(routine ? '작업 수정' : '작업 추가',
    `<div class="stack">
      <label class="field"><span>어떤 작업인가요?</span>
        <input id="m-title" value="${esc(routine?.title || '')}" placeholder="예: 일일 점검표 작성"></label>
      <label class="field"><span>마일리지</span>
        <input id="m-points" type="number" inputmode="numeric" value="${routine?.points ?? DEFAULT_POINTS}"></label>
      <div><span class="muted">하는 요일</span>
        <div class="days" style="margin-top:6px">${[1, 2, 3, 4, 5, 6, 7].map((d) =>
          `<button class="${days.includes(d) ? 'on' : ''}" data-act="day" data-id="${d}">${'월화수목금토일'[d - 1]}</button>`).join('')}</div></div>
      <label class="field"><span>마감 시각 (비워도 됨)</span><input id="m-due" type="time" value="${timeValue}"></label>
      ${routine ? `<label class="row"><input type="checkbox" id="m-active" style="width:auto" ${routine.active === false ? '' : 'checked'}> <span>사용 중</span></label>` : ''}
    </div>`,
    `${routine ? '<button class="danger" data-act="del-routine-now">삭제</button>' : ''}
     <button class="plain" data-act="close">취소</button><button class="plain" data-act="confirm">저장</button>`);

  openModal(render);
  dayToggle = (d) => {
    const n = Number(d);
    days = days.includes(n) ? days.filter((x) => x !== n) : [...days, n].sort();
    const title = document.getElementById('m-title')?.value;
    const pts = document.getElementById('m-points')?.value;
    const due = document.getElementById('m-due')?.value;
    draw();
    if (title != null) document.getElementById('m-title').value = title;
    if (pts != null) document.getElementById('m-points').value = pts;
    if (due != null) document.getElementById('m-due').value = due;
  };
  deleteRoutineNow = () => { closeModal(); repo.deleteRoutine(routine.id); };
  pendingConfirm = () => {
    const title = document.getElementById('m-title').value.trim();
    if (!title) return;
    const pts = parseInt(document.getElementById('m-points').value, 10) || 0;
    const due = document.getElementById('m-due').value;
    const dueMinute = due ? Number(due.slice(0, 2)) * 60 + Number(due.slice(3, 5)) : null;
    const active = routine ? !!document.getElementById('m-active')?.checked : true;
    closeModal();
    if (routine) repo.updateRoutine({ ...routine, title, days, dueMinute, points: pts, active });
    else repo.addRoutine(tasksWorker, title, days, dueMinute, pts);
  };
}

function openReminderModal(reminder) {
  let days = [...(reminder.days || [])];
  const timeValue = `${String(Math.floor(reminder.minute / 60)).padStart(2, '0')}:${String(reminder.minute % 60).padStart(2, '0')}`;
  const render = () => modalShell('점검 알림',
    `<div class="stack">
      <label class="field"><span>알릴 시각</span><input id="m-time" type="time" value="${timeValue}"></label>
      <label class="field"><span>알림 문구</span><input id="m-text" value="${esc(reminder.text)}"></label>
      <div><span class="muted">울리는 요일</span>
        <div class="days" style="margin-top:6px">${[1, 2, 3, 4, 5, 6, 7].map((d) =>
          `<button class="${days.includes(d) ? 'on' : ''}" data-act="day" data-id="${d}">${'월화수목금토일'[d - 1]}</button>`).join('')}</div></div>
      <label class="row"><input type="checkbox" id="m-only" style="width:auto" ${reminder.onlyIfIncomplete ? 'checked' : ''}> <span>남은 작업이 있을 때만</span></label>
    </div>`,
    `<button class="plain" data-act="close">취소</button><button class="plain" data-act="confirm">저장</button>`);
  openModal(render);
  dayToggle = (d) => {
    const n = Number(d);
    days = days.includes(n) ? days.filter((x) => x !== n) : [...days, n].sort();
    const t = document.getElementById('m-time')?.value;
    const x = document.getElementById('m-text')?.value;
    draw();
    if (t) document.getElementById('m-time').value = t;
    if (x != null) document.getElementById('m-text').value = x;
  };
  pendingConfirm = () => {
    const t = document.getElementById('m-time').value;
    const text = document.getElementById('m-text').value.trim();
    if (!t || !text) return;
    const minute = Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
    const only = document.getElementById('m-only').checked;
    closeModal();
    repo.upsertReminder({ ...reminder, minute, text, days, onlyIfIncomplete: only });
  };
}

let dayToggle = null;
let deleteRoutineNow = null;

// ------------------------------------------------------------------ 이벤트

app.addEventListener('click', (ev) => {
  const target = ev.target.closest('[data-act]');
  if (!target) {
    if (ev.target.dataset.close) closeModal();
    return;
  }
  const act = target.dataset.act;
  const id = target.dataset.id;

  if (act === 'close') return closeModal();
  if (act === 'confirm') return pendingConfirm?.();
  if (act === 'day') return dayToggle?.(id);
  if (act === 'del-routine-now') return deleteRoutineNow?.();
  if (act === 'copy') {
    navigator.clipboard?.writeText(id).then(() => toast('복사했습니다')).catch(() => toast('복사하지 못했습니다'));
    return;
  }
  const fn = ACTIONS[act];
  if (fn) { fn(id); draw(); }
});

repo.subscribe(() => draw());

// 화면이 보이는 동안만 확인 루프를 돈다
document.addEventListener('visibilitychange', () => {
  if (document.hidden) repo.stopLive();
  else { repo.startLive(); draw(); }
});
window.addEventListener('pagehide', () => repo.stopLive());

draw();
repo.startLive();
publishSchedule(repo);

// 예전 앱 자료를 옮겨 왔으면 알려 준다. 말없이 넘어가면 화면이 비었다가
// 갑자기 차 있는 것으로 보여서 무슨 일이 일어난 건지 알 수 없다.
if (restored) toast('예전 앱에 있던 자료를 그대로 가져왔습니다.');

// 웹 자산이 갱신되면 바로 반영한다
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').then((reg) => {
    reg.addEventListener('updatefound', () => {
      const sw = reg.installing;
      sw?.addEventListener('statechange', () => {
        if (sw.state === 'installed' && navigator.serviceWorker.controller) {
          toast('새 버전이 준비됐습니다. 다시 열면 적용됩니다.');
        }
      });
    });
  }).catch(() => {});
}
