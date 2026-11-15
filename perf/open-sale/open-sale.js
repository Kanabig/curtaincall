// 판매 오픈 부하. 두 모드가 있다.
//
//   MODE=flow  1만 명이 오픈 순간에 한꺼번에 진입한다. 대기열 진입 → 폴링 → 입장 → 앞열 쏠림 점유 →
//              주문 → 결제 → 확정 확인까지 사용자 한 명이 한 VU로 끝까지 간다. 입장 토큰은 queue가 발급한 것을 쓴다.
//   MODE=nfr7  예매 API 응답 시간을 대기열 폴링 유무로 비교한다(NFR-7). 예매는 일정한 도착률(BOOK_RATE)로 걸고,
//              입장 토큰은 직접 서명해서 쓴다(대기열과 독립). POLLERS명이 대기열에 들어가 폴링한다. 0이면 폴링 없음.
//
// 모든 요청은 gateway를 거친다. access token과 입장 토큰은 gateway와 같은 키로 HS256 서명해 만든다
// (gateway가 서명·만료·sub만 보고 사용자 존재 여부는 보지 않는다).
//
// 환경변수
//   BASE_URL        gateway 주소 (기본 http://gateway:8080)
//   SESSION_ID      회차 ID
//   OPEN_AT_MS      판매 오픈 시각(epoch ms). VU가 이때까지 기다린 뒤 진입한다
//   ACCESS_SECRET, ENTRY_SECRET   서명 키(.env의 JWT_ACCESS_SECRET, JWT_ENTRY_SECRET)
//   SEAT_BASE       첫 좌석 ID. 좌석은 100열 x 100석, 좌석 ID = SEAT_BASE + 열 * 100 + 번호 - 1 (perf/open-sale/seed-event.sql)
//   [flow]  USERS(10000)  SPREAD_MS(1000, 진입 시각 분산)  THINK_MS(1500)  MAX_HOLD_ATTEMPTS(3)  MAX_WAIT_S(900)  PAY(1)
//   [nfr7]  BOOK_RATE(50)  DURATION(60s)  POLLERS(0)  PAY(0)
import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://gateway:8080';
const SID = Number(__ENV.SESSION_ID || 1);
const OPEN_AT_MS = Number(__ENV.OPEN_AT_MS || 0);
const ACCESS_SECRET = __ENV.ACCESS_SECRET;
const ENTRY_SECRET = __ENV.ENTRY_SECRET;
const SEAT_BASE = Number(__ENV.SEAT_BASE || 1);
const MODE = __ENV.MODE || 'flow';
const USERS = Number(__ENV.USERS || 10000);
const SPREAD_MS = Number(__ENV.SPREAD_MS || 1000);
const THINK_MS = Number(__ENV.THINK_MS || 1500);
const MAX_HOLD_ATTEMPTS = Number(__ENV.MAX_HOLD_ATTEMPTS || 3);
const MAX_WAIT_S = Number(__ENV.MAX_WAIT_S || 900);
const ENTRY_ONLY = __ENV.ENTRY_ONLY === '1'; // 진입만 하고 끝낸다(진입 급증만 따로 본다)
// 1이면 gateway를 거치지 않고 queue에 바로 보낸다(gateway 구간 비용 분리). 인증은 X-User-Id로 대신한다
const DIRECT_QUEUE = __ENV.DIRECT_QUEUE === '1';
const QUEUE_URL = __ENV.QUEUE_URL || 'http://queue:8083';
const PAY = (__ENV.PAY || (MODE === 'flow' ? '1' : '0')) === '1';
const BOOK_RATE = Number(__ENV.BOOK_RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const POLLERS = Number(__ENV.POLLERS || 0);
// nfr7의 사용자 ID 구간: 예매 100001~, 폴링 200001~ (flow의 1만 명과 겹치지 않는다)
const BOOK_USER_BASE = 100000;
const POLLER_USER_BASE = 200000;
const ROWS = 100;
const SEATS_PER_ROW = 100;

if (!ACCESS_SECRET || !ENTRY_SECRET) {
  throw new Error('ACCESS_SECRET, ENTRY_SECRET이 필요하다');
}

// 단계별 응답 시간. http_req_duration{name:...}와 같지만 summary에서 바로 읽으려고 따로 둔다
const T = {
  enter: new Trend('t_queue_enter', true),
  poll: new Trend('t_queue_poll', true),
  status: new Trend('t_seat_status', true),
  hold: new Trend('t_hold', true),
  order: new Trend('t_order', true),
  pay: new Trend('t_payment_start', true),
  orderGet: new Trend('t_order_get', true),
};
const wait = new Trend('queue_wait_ms', true); // 진입 요청 완료부터 입장까지
const confirmTime = new Trend('confirm_ms', true); // 결제 시작부터 CONFIRMED까지
const startLag = new Trend('start_lag_ms'); // VU가 오픈 시각보다 얼마나 늦게 시작했나(부하 생성기 지연 확인)
const C = {
  enterOk: new Counter('enter_ok'),
  enterFail: new Counter('enter_fail'),
  pollCount: new Counter('poll_count'),
  admitted: new Counter('admitted'),
  expired: new Counter('expired_reenter'),
  holdOk: new Counter('hold_ok'),
  holdSeats: new Counter('hold_seats'),
  holdConflict: new Counter('hold_conflict'),
  holdLimit: new Counter('hold_limit'),
  holdError: new Counter('hold_error'),
  gaveUp: new Counter('gave_up_no_seat'),
  orderOk: new Counter('order_ok'),
  orderFail: new Counter('order_fail'),
  payOk: new Counter('payment_started'),
  payFail: new Counter('payment_start_fail'),
  confirmed: new Counter('order_confirmed'),
  failedFinal: new Counter('order_failed_final'),
  confirmTimeout: new Counter('order_confirm_timeout'),
  throttled: new Counter('http_429'),
  unavailable: new Counter('http_503'),
  seatsConfirmed: new Counter('seats_confirmed'),
};

export const options = (() => {
  const base = {
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    discardResponseBodies: false,
    // 응답 본문을 읽는 호출만 본문을 받는다. 상태 확인용은 아래에서 따로 끄지 않는다(단순하게 유지)
  };
  if (MODE === 'nfr7') {
    const scenarios = {
      book: {
        executor: 'constant-arrival-rate',
        rate: BOOK_RATE,
        timeUnit: '1s',
        duration: DURATION,
        // 오픈 15초 뒤부터. 그 사이 폴링이 자리를 잡는다(폴링이 없는 실행도 같은 시각에 시작해 조건을 맞춘다)
        startTime: `${Math.max(0, Math.ceil((OPEN_AT_MS - Date.now()) / 1000)) + 15}s`,
        preAllocatedVUs: 200,
        maxVUs: 2000,
        exec: 'book',
      },
    };
    if (POLLERS > 0) {
      scenarios.poll = {
        executor: 'constant-vus',
        vus: POLLERS,
        // 폴링은 예매보다 먼저 시작해 안정된 상태에서 겹치게 한다
        duration: addSeconds(DURATION, Math.max(0, Math.ceil((OPEN_AT_MS - Date.now()) / 1000)) + 15),
        exec: 'poller',
      };
    }
    return Object.assign(base, {
      scenarios,
      thresholds: {
        'http_req_duration{name:hold}': ['p(95)<300'],
        'http_req_duration{name:queue_poll}': ['max>=0'],
        'http_req_duration{name:seat_status}': ['max>=0'],
        'http_req_duration{name:order}': ['max>=0'],
        'http_req_duration{name:payment_start}': ['max>=0'],
        'http_req_duration{name:order_get}': ['max>=0'],
      },
    });
  }
  return Object.assign(base, {
    scenarios: {
      flow: {
        executor: 'per-vu-iterations',
        vus: USERS,
        iterations: 1,
        maxDuration: `${MAX_WAIT_S + 120}s`,
        exec: 'flow',
      },
    },
    thresholds: {
      'http_req_duration{name:queue_enter}': ['p(95)<200'],
      'http_req_failed{name:queue_enter}': ['rate<0.01'],
      'http_req_duration{name:hold}': ['p(95)<300'],
      'http_req_duration{name:queue_poll}': ['max>=0'],
      'http_req_duration{name:seat_status}': ['max>=0'],
      'http_req_duration{name:order}': ['max>=0'],
      'http_req_duration{name:payment_start}': ['max>=0'],
      'http_req_duration{name:order_get}': ['max>=0'],
    },
  });
})();

function addSeconds(d, n) {
  const m = /^(\d+)s$/.exec(d);
  return `${(m ? Number(m[1]) : 60) + n}s`;
}

// ---------- 토큰

function b64url(s) {
  return encoding.b64encode(s, 'rawurl');
}

function jwt(secret, claims) {
  const head = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64url(JSON.stringify(claims));
  const sig = crypto.hmac('sha256', secret, `${head}.${body}`, 'base64rawurl');
  return `${head}.${body}.${sig}`;
}

function accessToken(userId) {
  const now = Math.floor(Date.now() / 1000);
  return jwt(ACCESS_SECRET, { sub: String(userId), role: 'USER', jti: `perf-${userId}`, iat: now, exp: now + 3600 });
}

function entryToken(userId) {
  const now = Math.floor(Date.now() / 1000);
  return jwt(ENTRY_SECRET, { sub: String(userId), sid: String(SID), iat: now, exp: now + 3600 });
}

function uuid() {
  const h = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) s += '-';
    else if (i === 14) s += '4';
    else if (i === 19) s += h[8 + Math.floor(Math.random() * 4)];
    else s += h[Math.floor(Math.random() * 16)];
  }
  return s;
}

// ---------- 요청 도우미

let directUser = 0;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function call(method, path, body, headers, name, expected, trend) {
  const params = {
    headers: Object.assign({}, JSON_HEADERS, headers),
    tags: { name },
    responseCallback: http.expectedStatuses(...expected),
  };
  let base = BASE_URL;
  if (DIRECT_QUEUE && path.startsWith('/api/v1/queue/')) {
    base = QUEUE_URL;
    params.headers = Object.assign({}, params.headers, { 'X-User-Id': String(directUser) });
  }
  const res = method === 'GET' ? http.get(`${base}${path}`, params) : http.post(`${base}${path}`, body === null ? null : JSON.stringify(body), params);
  if (trend) trend.add(res.timings.duration);
  if (res.status === 429) C.throttled.add(1);
  if (res.status === 503) C.unavailable.add(1);
  return res;
}

function json(res) {
  try {
    return res.json();
  } catch (e) {
    return {};
  }
}

function think(ms) {
  sleep((ms * (0.5 + Math.random())) / 1000);
}

// ---------- 좌석 선택

function pickRow() {
  return Math.floor(ROWS * Math.pow(Math.random(), 3));
}

function pickCount() {
  const roll = Math.random();
  return roll < 0.3 ? 1 : roll < 0.7 ? 2 : roll < 0.8 ? 3 : 4;
}

function seatId(row, number) {
  return SEAT_BASE + row * SEATS_PER_ROW + number;
}

// 첫 시도: 화면을 보기 전 감으로 고른다(상태를 모른다). 앞열에 몰린다.
function pickBlind() {
  const row = pickRow();
  const count = pickCount();
  const first = Math.floor(Math.random() * (SEATS_PER_ROW - count + 1));
  const ids = [];
  for (let i = 0; i < count; i++) ids.push(seatId(row, first + i));
  return ids;
}

// 재시도: 좌석 상태를 보고 앞열부터 연속으로 비어 있는 곳을 찾는다.
function pickFree(status) {
  const taken = new Set([].concat(status.held || [], status.sold || []));
  const count = pickCount();
  const start = pickRow();
  for (let d = 0; d < ROWS; d++) {
    const row = (start + d) % ROWS;
    let run = 0;
    for (let n = 0; n < SEATS_PER_ROW; n++) {
      if (taken.has(seatId(row, n))) {
        run = 0;
      } else if (++run === count) {
        const ids = [];
        for (let i = count - 1; i >= 0; i--) ids.push(seatId(row, n - i));
        return ids;
      }
    }
  }
  return null;
}

// ---------- 예매 단계

function hold(auth, token, seatIds) {
  const res = call(
    'POST',
    `/api/v1/sessions/${SID}/holds`,
    { seatIds },
    Object.assign({ 'X-Entry-Token': token }, auth),
    'hold',
    [201, 409],
    T.hold,
  );
  if (res.status === 201) {
    C.holdOk.add(1);
    C.holdSeats.add(seatIds.length);
    return { holdId: json(res).holdId, seats: seatIds.length };
  }
  if (res.status === 409) {
    if (json(res).code === 'PURCHASE_LIMIT_EXCEEDED') C.holdLimit.add(1);
    else C.holdConflict.add(1);
  } else {
    C.holdError.add(1);
  }
  return null;
}

function order(auth, token, holdId) {
  const res = call('POST', '/api/v1/orders', { holdId }, Object.assign({ 'X-Entry-Token': token, 'Idempotency-Key': uuid() }, auth), 'order', [201], T.order);
  if (res.status !== 201) {
    C.orderFail.add(1);
    return null;
  }
  C.orderOk.add(1);
  return json(res).orderId;
}

function startPayment(auth, token, orderId) {
  const res = call(
    'POST',
    `/api/v1/orders/${orderId}/payment`,
    { method: 'CARD', cardToken: 'tok_approve' },
    Object.assign({ 'X-Entry-Token': token, 'Idempotency-Key': uuid() }, auth),
    'payment_start',
    [202],
    T.pay,
  );
  if (res.status !== 202) {
    C.payFail.add(1);
    return false;
  }
  C.payOk.add(1);
  return true;
}

function awaitConfirm(auth, orderId, seats) {
  const t0 = Date.now();
  for (let i = 0; i < 30; i++) {
    sleep(0.5 + Math.random() * 0.5);
    const res = call('GET', `/api/v1/orders/${orderId}`, null, auth, 'order_get', [200], T.orderGet);
    const status = json(res).status;
    if (status === 'CONFIRMED') {
      C.confirmed.add(1);
      C.seatsConfirmed.add(seats);
      confirmTime.add(Date.now() - t0);
      return;
    }
    if (status === 'FAILED' || status === 'EXPIRED' || status === 'REFUNDED' || status === 'REFUNDING') {
      C.failedFinal.add(1);
      return;
    }
  }
  C.confirmTimeout.add(1);
}

// ---------- flow

export function flow() {
  const userId = exec.vu.idInTest; // 1부터
  directUser = userId;
  const auth = { Authorization: `Bearer ${accessToken(userId)}` };

  const lag = Date.now() - OPEN_AT_MS;
  if (lag < 0) sleep(-lag / 1000);
  else startLag.add(lag);
  sleep((Math.random() * SPREAD_MS) / 1000);

  // 1. 진입
  let enteredAt = 0;
  let body = null;
  for (let attempt = 0; attempt < 5; attempt++) {
    const res = call('POST', `/api/v1/queue/sessions/${SID}/entries`, null, auth, 'queue_enter', [200, 201], T.enter);
    if (res.status === 200 || res.status === 201) {
      C.enterOk.add(1);
      enteredAt = Date.now();
      body = json(res);
      break;
    }
    C.enterFail.add(1);
    sleep(1 + Math.random());
  }
  if (!body || ENTRY_ONLY) return;

  // 2. 대기열 폴링
  const deadline = Date.now() + MAX_WAIT_S * 1000;
  while (body.status !== 'ADMITTED') {
    if (Date.now() > deadline) return;
    if (body.status === 'EXPIRED') {
      C.expired.add(1);
      const res = call('POST', `/api/v1/queue/sessions/${SID}/entries`, null, auth, 'queue_enter', [200, 201], T.enter);
      body = json(res);
      continue;
    }
    sleep((body.pollAfterMs || 3000) / 1000);
    const res = call('GET', `/api/v1/queue/sessions/${SID}/entries/me`, null, auth, 'queue_poll', [200], T.poll);
    C.pollCount.add(1);
    body = res.status === 200 ? json(res) : { status: 'WAITING', pollAfterMs: 3000 };
  }
  C.admitted.add(1);
  wait.add(Date.now() - enteredAt);
  const token = body.entryToken;

  // 3. 좌석 화면: 상태를 한 번 보고 고른다
  think(THINK_MS);
  let status = json(call('GET', `/api/v1/sessions/${SID}/seat-status`, null, auth, 'seat_status', [200], T.status));
  let held = null;
  for (let attempt = 0; attempt < MAX_HOLD_ATTEMPTS && !held; attempt++) {
    // 첫 시도는 화면을 보기 전에 마음먹은 좌석을 누른다. 실패하면 새 상태를 보고 다시 고른다
    const ids = attempt === 0 ? pickBlind() : pickFree(status);
    if (!ids) break;
    held = hold(auth, token, ids);
    if (!held) {
      think(THINK_MS / 2);
      status = json(call('GET', `/api/v1/sessions/${SID}/seat-status`, null, auth, 'seat_status', [200], T.status));
    }
  }
  if (!held) {
    C.gaveUp.add(1);
    return;
  }

  // 4. 주문, 결제
  think(THINK_MS);
  const orderId = order(auth, token, held.holdId);
  if (!orderId || !PAY) return;
  think(THINK_MS);
  if (startPayment(auth, token, orderId)) awaitConfirm(auth, orderId, held.seats);
}

// ---------- nfr7

// 예매 하나: 점유(좌석 2개) → 주문 → (PAY=1이면 결제 시작). 좌석과 사용자는 반복 번호로 정해 서로 겹치지 않는다.
export function book() {
  const n = exec.scenario.iterationInTest; // 0부터
  const userId = BOOK_USER_BASE + n + 1;
  const auth = { Authorization: `Bearer ${accessToken(userId)}` };
  const token = entryToken(userId);
  const first = (n * 2) % (ROWS * SEATS_PER_ROW);
  const held = hold(auth, token, [SEAT_BASE + first, SEAT_BASE + first + 1]);
  if (!held) return;
  const orderId = order(auth, token, held.holdId);
  if (orderId && PAY) startPayment(auth, token, orderId);
}

// 대기열에 들어가 폴링만 하는 사용자. 폴링 주기는 서버가 알려 준다(pollAfterMs)
export function poller() {
  const userId = POLLER_USER_BASE + exec.vu.idInTest;
  const auth = { Authorization: `Bearer ${accessToken(userId)}` };
  if (exec.vu.iterationInInstance === 0) {
    const untilOpen = OPEN_AT_MS - Date.now();
    sleep(Math.max(0, untilOpen) / 1000 + Math.random() * 3);
    call('POST', `/api/v1/queue/sessions/${SID}/entries`, null, auth, 'queue_enter_poller', [200, 201], null);
  }
  const res = call('GET', `/api/v1/queue/sessions/${SID}/entries/me`, null, auth, 'queue_poll', [200], T.poll);
  C.pollCount.add(1);
  const after = res.status === 200 ? json(res).pollAfterMs : 3000;
  sleep(((after || 3000) * (0.9 + Math.random() * 0.2)) / 1000);
}

// ---------- 요약

export function handleSummary(data) {
  return { '/out/summary.json': JSON.stringify(data, null, 2), stdout: textSummary(data) };
}

function textSummary(data) {
  const m = data.metrics;
  const t = (name) => {
    const v = m[name] && m[name].values;
    return v && v.med !== undefined ? `p50=${v.med.toFixed(1)} p95=${v['p(95)'].toFixed(1)} p99=${v['p(99)'].toFixed(1)} max=${v.max.toFixed(1)}` : '-';
  };
  const c = (name) => (m[name] ? m[name].values.count : 0);
  const d = (name) => {
    const v = m[name] && m[name].values;
    return v && v.med !== undefined ? v : null;
  };
  const sub = (name) => {
    const v = d(`http_req_duration{name:${name}}`);
    return v ? `p50=${v.med.toFixed(1)} p95=${v['p(95)'].toFixed(1)} p99=${v['p(99)'].toFixed(1)} max=${v.max.toFixed(1)}` : '-';
  };
  const secs = data.state.testRunDurationMs / 1000;
  const lines = [
    `mode=${MODE} users=${USERS} run=${secs.toFixed(0)}s requests=${c('http_reqs')} (${(c('http_reqs') / secs).toFixed(0)}/s) 429=${c('http_429')} 503=${c('http_503')}`,
    `http_req_failed ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(2) : '-'}%  dropped_iterations=${c('dropped_iterations')}`,
    '--- 응답 시간 (ms), 단계별',
  ];
  for (const name of ['queue_enter', 'queue_poll', 'seat_status', 'hold', 'order', 'payment_start', 'order_get']) {
    lines.push(`${name.padEnd(14)} ${sub(name)}`);
  }
  lines.push(
    '--- 진입',
    `enter_ok=${c('enter_ok')} enter_fail=${c('enter_fail')} polls=${c('poll_count')} admitted=${c('admitted')} expired_reenter=${c('expired_reenter')}`,
    `queue_wait_ms  ${t('queue_wait_ms')}`,
    `start_lag_ms   ${t('start_lag_ms')}`,
    '--- 예매',
    `hold_ok=${c('hold_ok')} (seats ${c('hold_seats')}) conflict=${c('hold_conflict')} limit=${c('hold_limit')} error=${c('hold_error')} gave_up=${c('gave_up_no_seat')}`,
    `order_ok=${c('order_ok')} fail=${c('order_fail')} payment_started=${c('payment_started')} fail=${c('payment_start_fail')}`,
    `confirmed=${c('order_confirmed')} (seats ${c('seats_confirmed')}) failed_final=${c('order_failed_final')} confirm_timeout=${c('order_confirm_timeout')}`,
    `confirm_ms     ${t('confirm_ms')}`,
    '',
  );
  return lines.join('\n');
}
