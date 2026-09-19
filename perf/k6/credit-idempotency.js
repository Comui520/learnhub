import http from 'k6/http';
import { check } from 'k6';

// Creates one order and sends the same payment notification five times.
// This changes the test account balance. Verify one credit transaction afterward.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8088';
const USERNAME = __ENV.LEARNHUB_USERNAME || 'learnhub';
const PASSWORD = __ENV.LEARNHUB_PASSWORD || 'password123';
const AMOUNT = Number(__ENV.ORDER_AMOUNT || '0.01');

export const options = {
  vus: 1,
  iterations: 5,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500'],
  },
};

export function setup() {
  const login = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login-setup' } },
  );
  check(login, { 'login status is 200': (r) => r.status === 200 });
  if (login.status !== 200) throw new Error(`Login failed: ${login.body}`);
  const token = login.json()?.data?.token;

  const order = http.post(
    `${BASE_URL}/api/v1/credit/orders`,
    JSON.stringify({ amount: AMOUNT }),
    { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, tags: { name: 'credit-order' } },
  );
  check(order, { 'order status is 200': (r) => r.status === 200 });
  if (order.status !== 200) throw new Error(`Order creation failed: ${order.body}`);
  const data = order.json()?.data;
  return { orderNo: data.orderNo, amount: data.amount, tradeNo: `K6-${Date.now()}` };
}

export default function (data) {
  const response = http.post(
    `${BASE_URL}/api/v1/credit/payments/notify`,
    JSON.stringify({ orderNo: data.orderNo, amount: data.amount, tradeNo: data.tradeNo }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'credit-payment-notify' } },
  );
  check(response, {
    'payment notify status is 200': (r) => r.status === 200,
    'payment notify response is success': (r) => r.body.includes('COMMON_0000'),
  });
}
