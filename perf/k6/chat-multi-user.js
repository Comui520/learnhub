import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';
const USER_PREFIX = __ENV.USER_PREFIX || 'lhload260919';
const PASSWORD = __ENV.LEARNHUB_PASSWORD || 'LoadTest123!';
const KNOWLEDGE_BASE_BASE = Number(__ENV.KNOWLEDGE_BASE_BASE || '3');
const QUESTION = __ENV.QUESTION || '请用一句话总结这份资料。';

export const options = {
  vus: Number(__ENV.VUS || 8),
  duration: __ENV.DURATION || '60s',
  thresholds: {
    http_req_failed: ['rate<0.30'],
    http_req_duration: ['p(95)<90000'],
  },
};

let token = null;

function usernameForCurrentVu() {
  return `${USER_PREFIX}${String(__VU).padStart(2, '0')}`;
}

function ensureToken() {
  if (token) return token;
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: usernameForCurrentVu(), password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'multi-user-login' } },
  );
  check(response, { 'multi-user login is 200': (r) => r.status === 200 });
  if (response.status !== 200) return null;
  token = response.json()?.data?.token || null;
  return token;
}

export default function () {
  const jwt = ensureToken();
  if (!jwt) return;
  const response = http.post(
    `${BASE_URL}/api/v1/knowledge-bases/${KNOWLEDGE_BASE_BASE + __VU - 1}/chat`,
    JSON.stringify({ question: QUESTION }),
    {
      headers: {
        Authorization: `Bearer ${jwt}`,
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
      },
      tags: { name: 'multi-user-chat-sse' },
      timeout: '90s',
    },
  );
  check(response, {
    'multi-user chat HTTP status is 200': (r) => r.status === 200,
    'multi-user chat contains SSE content': (r) => r.body.includes('event:') || r.body.includes('data:'),
  });
}
