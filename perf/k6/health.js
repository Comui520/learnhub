import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';

export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
  },
};

export default function () {
  const response = http.get(`${BASE_URL}/actuator/health`);
  check(response, {
    'health status is 200': (r) => r.status === 200,
    'health status is UP': (r) => r.body.includes('UP'),
  });
}
