import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';
const USERNAME = __ENV.LEARNHUB_USERNAME || 'learnhub';
const PASSWORD = __ENV.LEARNHUB_PASSWORD || 'password123';

export const options = {
  vus: Number(__ENV.VUS || 2),
  iterations: Number(__ENV.ITERATIONS || 20),
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
  },
};

export default function () {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'auth-login' } },
  );
  check(response, {
    'login status is 200': (r) => r.status === 200,
    'login response is success': (r) => r.body.includes('COMMON_0000'),
  });
}
