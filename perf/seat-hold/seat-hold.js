// 좌석 점유 API 앞열 쏠림 부하. 요청 열이 u^3 분포라 앞열에 몰린다(열 번호 = floor(100 * u^3)).
//   BASE_URL  점유 API 주소 (기본 http://reservation:8084)
//   RATE      초당 요청 수 (기본 300)
//   DURATION  지속 시간 (기본 60s)
//   USERS     사용자 풀 크기 (기본 200000. 한도 409가 결과를 흐리지 않게 크게 잡는다)
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://reservation:8084';
const RATE = Number(__ENV.RATE || 300);
const DURATION = __ENV.DURATION || '60s';
const USERS = Number(__ENV.USERS || 200000);
const ROWS = 100;
const SEATS_PER_ROW = 100;

const holdOk = new Counter('hold_ok');
const holdSeats = new Counter('hold_seats');
const holdConflict = new Counter('hold_conflict');
const holdLimit = new Counter('hold_limit');
const holdError = new Counter('hold_error');
const latencyOk = new Trend('hold_latency_ok', true);
const latencyConflict = new Trend('hold_latency_conflict', true);

export const options = {
  scenarios: {
    open: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 200,
      maxVUs: 2000,
    },
  },
  // 좌석 경쟁에서 지는 409는 실패가 아니다. 5xx와 연결 실패만 http_req_failed로 센다
  thresholds: {
    http_req_duration: ['p(95)<300'],
    hold_error: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(201, 409));

function pickSeats() {
  const row = Math.floor(ROWS * Math.pow(Math.random(), 3));
  const roll = Math.random();
  const count = roll < 0.3 ? 1 : roll < 0.7 ? 2 : roll < 0.8 ? 3 : 4;
  const first = Math.floor(Math.random() * (SEATS_PER_ROW - count + 1));
  const seatIds = [];
  for (let i = 0; i < count; i++) {
    seatIds.push(row * SEATS_PER_ROW + first + i + 1);
  }
  return seatIds;
}

export default function () {
  const userId = 1 + Math.floor(Math.random() * USERS);
  const res = http.post(`${BASE_URL}/api/v1/sessions/1/holds`, JSON.stringify({ seatIds: pickSeats() }), {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': String(userId) },
  });
  if (res.status === 201) {
    holdOk.add(1);
    holdSeats.add(res.json('seatIds').length);
    latencyOk.add(res.timings.duration);
  } else if (res.status === 409) {
    if (res.json('code') === 'PURCHASE_LIMIT_EXCEEDED') {
      holdLimit.add(1);
    } else {
      holdConflict.add(1);
    }
    latencyConflict.add(res.timings.duration);
  } else {
    holdError.add(1);
  }
}

export function handleSummary(data) {
  return { '/out/summary.json': JSON.stringify(data, null, 2), stdout: textSummary(data) };
}

function textSummary(data) {
  const m = data.metrics;
  const t = (name) => {
    const v = m[name] && m[name].values;
    return v ? `p50=${v.med.toFixed(1)} p95=${v['p(95)'].toFixed(1)} p99=${v['p(99)'].toFixed(1)} max=${v.max.toFixed(1)}` : '-';
  };
  const c = (name) => (m[name] ? m[name].values.count : 0);
  const secs = data.state.testRunDurationMs / 1000;
  return [
    `requests          ${c('http_reqs')} (${(c('http_reqs') / secs).toFixed(0)}/s)`,
    `hold_ok           ${c('hold_ok')} (${(c('hold_ok') / secs).toFixed(0)}/s), seats ${c('hold_seats')}`,
    `hold_conflict     ${c('hold_conflict')}`,
    `hold_limit        ${c('hold_limit')}`,
    `hold_error        ${c('hold_error')}`,
    `dropped_iters     ${c('dropped_iterations')}`,
    `latency all (ms)  ${t('http_req_duration')}`,
    `latency 201 (ms)  ${t('hold_latency_ok')}`,
    `latency 409 (ms)  ${t('hold_latency_conflict')}`,
    '',
  ].join('\n');
}
