import http from 'k6/http';
import { check } from 'k6';

// One real AI question-generation request. Keep this as a one-shot smoke test.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';
const USERNAME = __ENV.LEARNHUB_USERNAME || 'learnhub';
const PASSWORD = __ENV.LEARNHUB_PASSWORD || 'password123';
const KNOWLEDGE_BASE_ID = __ENV.KNOWLEDGE_BASE_ID || '1';
const TOPIC = __ENV.TOPIC || '资料中的核心概念';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<60000'],
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
    `${BASE_URL}/api/v1/study/question/${KNOWLEDGE_BASE_ID}/generate`,
    JSON.stringify({ count: 1, topic: TOPIC, questionType: 'SINGLE_CHOICE' }),
    {
      headers: {
        Authorization: `Bearer ${data.token}`,
        'Content-Type': 'application/json',
      },
      tags: { name: 'study-question-generate' },
      timeout: '90s',
    },
  );
  check(response, {
    'question generation status is 200': (r) => r.status === 200,
    'question generation response is success': (r) => r.body.includes('COMMON_0000'),
  });
}
