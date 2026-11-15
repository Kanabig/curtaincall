// 좌석 배치도 캐시 미스 동시 요청. 캐시가 빈 상태(event 재시작 직후)에서 VUS명이 한꺼번에 같은 회차의 배치도를 요청한다.
// single-flight가 없으면 동시 요청 수만큼 DB 조회·직렬화가 일어난다. DB 조회 수는 run 쪽에서 performance_schema로 센다.
//   BASE_URL  기본 http://gateway:8080   SESSION_ID   VUS(기본 500)
import http from 'k6/http';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://gateway:8080';
const SID = Number(__ENV.SESSION_ID || 1);
const cold = new Trend('seatmap_cold_ms', true);

export const options = {
  scenarios: { burst: { executor: 'per-vu-iterations', vus: Number(__ENV.VUS || 500), iterations: 1 } },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/sessions/${SID}/seat-map`, { headers: { 'Accept-Encoding': 'gzip' } });
  cold.add(res.timings.duration);
}
