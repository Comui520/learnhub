import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';
const USERNAME = __ENV.LEARNHUB_USERNAME || 'learnhub';
const PASSWORD = __ENV.LEARNHUB_PASSWORD || 'password123';
const KNOWLEDGE_BASE_ID = __ENV.KNOWLEDGE_BASE_ID || '1';

export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
  },
};

export function setup() {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(response, { 'login status is 200': (r) => r.status === 200 });
  if (response.status !== 200) throw new Error(`Login failed: ${response.body}`);
  const token = response.json()?.data?.token;
  if (!token) throw new Error('Login response did not contain data.token');
  return { token };
}

export default function (data) {
  const response = http.post(
    `${BASE_URL}/api/v1/study/question/${KNOWLEDGE_BASE_ID}/page`,
    JSON.stringify({ knowledgeBaseId: Number(KNOWLEDGE_BASE_ID), page: 1, size: 12 }),
    {
      headers: {
        Authorization: `Bearer ${data.token}`,
        'Content-Type': 'application/json',
      },
    },
  );
  check(response, {
    'study page status is 200': (r) => r.status === 200,
    'study page response is success': (r) => r.body.includes('COMMON_0000'),
  });
}
