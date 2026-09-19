import http from 'k6/http';
import { check } from 'k6';

// This is an intentionally small, write-producing smoke test.
// Do not run it against a showcase database without planning cleanup.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';
const USERNAME = __ENV.LEARNHUB_USERNAME || 'learnhub';
const PASSWORD = __ENV.LEARNHUB_PASSWORD || 'password123';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<5000'],
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
  const fileName = `learnhub-load-test-${Date.now()}.md`;
  const content = `# LearnHub load-test document\n\nCreated at ${new Date().toISOString()} for a one-shot upload smoke test.\n`;
  const response = http.post(
    `${BASE_URL}/api/v1/document`,
    { file: http.file(content, fileName, 'text/markdown') },
    { headers: { Authorization: `Bearer ${data.token}` }, tags: { name: 'document-upload' } },
  );
  check(response, {
    'upload status is 200': (r) => r.status === 200,
    'upload response is success': (r) => r.body.includes('COMMON_0000'),
  });
}
