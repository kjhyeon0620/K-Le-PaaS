// LLM 경로 부하 시험 (#182, 스펙 0182). 사용법은 scripts/load/README.md.
// SCENARIO=s1   동시 VUS명이 DURATION 동안 자연어 명령을 반복한다
// SCENARIO=s2   s1과 함께 DB만 읽는 사용량 조회(PROBE_RPS/초)의 지연을 따로 잰다. VUS=0이면 조회만(부하 없음 기준)
// SCENARIO=s3   VUS명이 자연어 명령을 한 번씩 보내고 TIMEOUT까지 기다린다(LLM 무응답용)
// SCENARIO=s4   VUS명이 동시에 한 번씩 보낸다(예산 초과량용)
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:18068/api/v1';
const TOKEN = __ENV.TOKEN; // 로컬 테스트 사용자의 CLI 토큰. 출력하지 않는다
const VUS = parseInt(__ENV.VUS || '1');
const DURATION = __ENV.DURATION || '60s';
const TIMEOUT = __ENV.TIMEOUT || '60s';
const SCENARIO = __ENV.SCENARIO || 's1';
const HEADERS = { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' };

const scenarios = {
  s1: { nlp: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'nlp' } },
  s2: {
    probe: { executor: 'constant-arrival-rate', rate: parseInt(__ENV.PROBE_RPS || '2'), timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 20, maxVUs: 200, exec: 'probe' },
    ...(VUS > 0 ? { nlp: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'nlp' } } : {}),
  },
  s3: { nlp: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '1h', exec: 'nlp' } },
  s4: { nlp: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '10m', exec: 'nlp' } },
};

export const options = {
  scenarios: scenarios[SCENARIO],
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'max', 'count'],
  // 시나리오별 지연을 요약에 따로 보이게 한다
  thresholds: { 'http_req_duration{scenario:nlp}': ['max>=0'], 'http_req_duration{scenario:probe}': ['max>=0'] },
};

export function nlp() {
  const res = http.post(`${BASE}/nlp/command`, JSON.stringify({ command: '저장소 목록 보여줘' }),
    { headers: HEADERS, timeout: TIMEOUT, tags: { name: 'nlp' } });
  check(res, { 'nlp 200': (r) => r.status === 200, 'nlp 429': (r) => r.status === 429 });
}

export function probe() {
  const res = http.get(`${BASE}/llm/usage?days=1`, { headers: HEADERS, timeout: TIMEOUT, tags: { name: 'probe' } });
  check(res, { 'probe 200': (r) => r.status === 200 });
}
