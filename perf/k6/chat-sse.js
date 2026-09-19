import http from 'k6/http';
import { check } from 'k6';

// This calls the real model provider and consumes one credit.
// Run only with a dedicated test account / knowledge base and a short duration.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';
const USERNAME = __ENV.LEARNHUB_USERNAME || 'learnhub';
const PASSWORD = __ENV.LEARNHUB_PASSWORD || 'password123';
const KNOWLEDGE_BASE_ID = __ENV.KNOWLEDGE_BASE_ID || '1';
const QUESTION = __ENV.QUESTION || '请用一句话总结这份资料。';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<30000'],
  },
};

export function setup() {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login-setup' } },
  );
  check(response, { 'login status is 200': (r) => r.status === 200 });
  if (response.status !== 200) throw new Error(`Login failed: ${response.body}`);
  return { token: response.json()?.data?.token };
}

export default function (data) {
  const response = http.post(
    `${BASE_URL}/api/v1/knowledge-bases/${KNOWLEDGE_BASE_ID}/chat`,
    JSON.stringify({ question: QUESTION }),
    {
      headers: {
        Authorization: `Bearer ${data.token}`,
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
      },
      tags: { name: 'chat-sse' },
      timeout: '60s',
    },
  );
  check(response, {
    'chat HTTP status is 200': (r) => r.status === 200,
    'chat contains SSE content': (r) => r.body.includes('event:') || r.body.includes('data:'),
  });
}
