// 좌석 상태 폴링 부하. VUS명이 각자 INTERVAL 초마다 조회한다(화면 폴링).
//   BASE_URL  기본 http://reservation:8084
//   VUS       동시 폴링 수 (기본 300)
//   INTERVAL  폴링 주기 초 (기본 3)
//   DURATION  지속 시간 (기본 60s)
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://reservation:8084';
const INTERVAL = Number(__ENV.INTERVAL || 3);

export const options = {
  scenarios: {
    poll: { executor: 'constant-vus', vus: Number(__ENV.VUS || 300), duration: __ENV.DURATION || '60s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/sessions/1/seat-status`);
  check(res, { ok: (r) => r.status === 200 });
  sleep(INTERVAL * (0.8 + Math.random() * 0.4));
}
