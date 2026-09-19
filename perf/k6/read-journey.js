import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';
const USERNAME = __ENV.LEARNHUB_USERNAME || 'learnhub';
const PASSWORD = __ENV.LEARNHUB_PASSWORD || 'password123';
const KNOWLEDGE_BASE_ID = __ENV.KNOWLEDGE_BASE_ID || '1';
const QUESTION_ID = __ENV.QUESTION_ID || '5';

export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
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
  const token = response.json()?.data?.token;
  if (!token) throw new Error('Login response did not contain data.token');
  return { token };
}

function request(path, data, options, label) {
  const response = data === undefined
    ? http.get(`${BASE_URL}${path}`, { ...options, tags: { name: label } })
    : http.post(`${BASE_URL}${path}`, JSON.stringify(data), { ...options, tags: { name: label } });
  check(response, {
    [`${label} status is 200`]: (r) => r.status === 200,
    [`${label} response is success`]: (r) => r.body.includes('COMMON_0000'),
  });
  return response;
}

export default function (data) {
  const options = {
    headers: {
      Authorization: `Bearer ${data.token}`,
      'Content-Type': 'application/json',
    },
  };

  request('/api/v1/users/me', undefined, options, 'users-me');
  request('/api/v1/credit/balance', undefined, options, 'credit-balance');
  request('/api/v1/knowledge-bases?page=1&size=50', undefined, options, 'knowledge-bases-page');
  request('/api/v1/document?page=1&size=50', undefined, options, 'document-page');
  request(`/api/v1/knowledge-bases/${KNOWLEDGE_BASE_ID}/task?page=1&size=50`, undefined, options, 'document-task-page');
  request(`/api/v1/study/question/${KNOWLEDGE_BASE_ID}/page`, { knowledgeBaseId: Number(KNOWLEDGE_BASE_ID), page: 1, size: 12 }, options, 'study-question-page');
  request(`/api/v1/study/question/${QUESTION_ID}/detail`, undefined, options, 'study-question-detail');
}
