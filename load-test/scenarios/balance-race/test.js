import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate } from "k6/metrics";
import { SharedArray } from "k6/data";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

// =============================================================================
// 잔액 경합 시나리오 테스트
//
// 시나리오:
//   1. 사용자 잔액을 상품 가격(4,500원)과 동일하게 설정
//   2. 동일 상품을 2건 동시 구매 요청
//   3. 잔액이 1건만 감당 가능 → 1건만 성공해야 함
//   4. 잔액이 음수가 되면 경합 방어 실패
//
// 회귀 테스트:
//   subtractBalance의 WHERE balance >= :amount 조건이
//   동시 부하에서도 정상 동작하는지 검증
//   기대: [201, 400] 패턴 다수 → 전체 PASS
// =============================================================================

const testData = JSON.parse(open("../../results/users.json"));
const userIds = new SharedArray("userIds", () => testData.userIds);
const productIds = new SharedArray("productIds", () => testData.productIds);

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";
const CONCURRENT = parseInt(__ENV.CONCURRENT_REQUESTS || "2");
const PRODUCT_PRICE = 4500; // 스타벅스 아메리카노
const PRODUCT_INDEX = 0;    // productIds[0] = 스타벅스 아메리카노
const HEADERS = { "Content-Type": "application/json" };

// --------------- Custom Metrics ---------------
const negativeBalance = new Counter("negative_balance");
const overPurchase = new Counter("over_purchase");
const balanceCorrect = new Rate("balance_correct_rate");
const singlePurchase = new Rate("single_purchase_rate");
const noServerError = new Rate("no_server_error_rate");

// 응답 패턴 카운터
const pattern201_400 = new Counter("pattern_201_400");   // 이상적 (1 성공 + 잔액부족)
const pattern201_201 = new Counter("pattern_201_201");   // 경합 실패 (2건 모두 성공)
const pattern201_5xx = new Counter("pattern_201_5xx");   // 1 성공 + 서버에러
const patternAll4xx  = new Counter("pattern_all_4xx");   // 전부 실패 (잔액부족)
const patternOther   = new Counter("pattern_other");     // 기타

// --------------- Test Options ---------------
const VUS = parseInt(__ENV.TARGET_VUS || "20");
const ITERATIONS = parseInt(__ENV.ITERATIONS || "10");

let initialBalances = {};
try {
  initialBalances = JSON.parse(open("../../results/balance-race/initial-balances.json"));
} catch (_) {
  // 스냅샷 파일이 없으면 빈 객체
}

export const options = {
  scenarios: {
    balanceRace: {
      executor: "per-vu-iterations",
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: "10m",
    },
  },
  thresholds: {
    negative_balance: ["count==0"],
    over_purchase: ["count==0"],
    balance_correct_rate: ["rate>0.95"],
  },
};

// --------------- Setup: 잔액 소진 + 초기 상태 기록 ---------------
export function setup() {
  const userMap = {};
  const productId = productIds[PRODUCT_INDEX];

  for (let vu = 1; vu <= VUS; vu++) {
    const uid = userIds[vu % userIds.length];
    if (userMap[uid]) {
      userMap[uid].expectedIterations += ITERATIONS;
      continue;
    }

    // 1. 현재 잔액 조회
    const res = http.get(`${BASE_URL}/users/${uid}/wallet`);
    if (res.status !== 200) continue;
    let balance = JSON.parse(res.body).balance;

    // 2. 기존 잔액 소진 (다른 테스트에서 쌓인 잔액 제거)
    //    경합 조건을 만들려면 잔액이 정확히 PRODUCT_PRICE여야 함
    while (balance >= PRODUCT_PRICE) {
      const batchSize = Math.min(Math.floor(balance / PRODUCT_PRICE), 50);
      const batch = [];
      for (let i = 0; i < batchSize; i++) {
        batch.push([
          "POST",
          `${BASE_URL}/users/${uid}/gifticons`,
          JSON.stringify({ productId }),
          { headers: HEADERS, tags: { name: "잔액소진_setup" } },
        ]);
      }
      const responses = http.batch(batch);
      let bought = 0;
      for (const r of responses) {
        if (r.status === 201) bought++;
      }
      balance -= bought * PRODUCT_PRICE;
      if (bought === 0) break;
    }

    // 3. 소진 후 잔액 확인 (0 ~ PRODUCT_PRICE-1 범위)
    const afterDrain = http.get(`${BASE_URL}/users/${uid}/wallet`);
    const drainedBalance = afterDrain.status === 200
      ? JSON.parse(afterDrain.body).balance
      : balance;

    console.log(
      `[SETUP] ${uid.slice(0, 8)}... 잔액 소진: ${balance} → ${drainedBalance}원`
    );

    userMap[uid] = {
      initialBalance: drainedBalance,
      expectedIterations: ITERATIONS,
    };
  }

  return { userMap, productId };
}

// --------------- Main Test ---------------
export default function (data) {
  const userId = userIds[__VU % userIds.length];
  const productId = data.productId;

  // 1. 현재 잔액 조회
  const balanceRes = http.get(`${BASE_URL}/users/${userId}/wallet`, {
    tags: { name: "잔액조회" },
  });
  if (balanceRes.status !== 200) {
    console.warn(`[SKIP] 잔액 조회 실패: userId=${userId}, status=${balanceRes.status}`);
    return;
  }
  const currentBalance = JSON.parse(balanceRes.body).balance;

  // 2. 잔액을 정확히 PRODUCT_PRICE로 맞추기
  const needed = PRODUCT_PRICE - currentBalance;
  if (needed > 0) {
    const chargeRes = http.post(
      `${BASE_URL}/wallets/${userId}/charges`,
      JSON.stringify({ amount: needed }),
      {
        headers: {
          ...HEADERS,
          "Idempotency-Key": uuidv4(),
        },
        tags: { name: "잔액충전" },
      }
    );
    if (chargeRes.status !== 200) {
      console.warn(`[SKIP] 충전 실패: userId=${userId}, needed=${needed}, status=${chargeRes.status}`);
      return;
    }
  }

  // 3. 구매 직전 잔액 확인
  const beforeRes = http.get(`${BASE_URL}/users/${userId}/wallet`, {
    tags: { name: "구매전_잔액" },
  });
  if (beforeRes.status !== 200) return;
  const beforeBalance = JSON.parse(beforeRes.body).balance;
  const maxAffordable = Math.floor(beforeBalance / PRODUCT_PRICE);

  // 4. 동시 N건 구매 요청 (핵심)
  const batch = [];
  for (let i = 0; i < CONCURRENT; i++) {
    batch.push([
      "POST",
      `${BASE_URL}/users/${userId}/gifticons`,
      JSON.stringify({ productId }),
      {
        headers: HEADERS,
        tags: { name: `동시구매_${i + 1}` },
      },
    ]);
  }
  const responses = http.batch(batch);

  // 5. 응답 분류
  let successCount = 0;
  let rejectCount = 0;
  let errCount = 0;
  const statuses = [];

  for (const res of responses) {
    statuses.push(res.status);
    if (res.status === 201) successCount++;
    else if (res.status >= 400 && res.status < 500) rejectCount++;
    else errCount++;
  }

  // 6. 응답 패턴 분류
  if (successCount === 1 && rejectCount >= 1 && errCount === 0) {
    pattern201_400.add(1);
  } else if (successCount >= 2) {
    pattern201_201.add(1);
  } else if (successCount === 1 && errCount >= 1) {
    pattern201_5xx.add(1);
  } else if (successCount === 0 && errCount === 0) {
    patternAll4xx.add(1);
  } else {
    patternOther.add(1);
  }

  // 7. 메트릭 기록
  singlePurchase.add(successCount === 1);
  noServerError.add(errCount === 0);

  // 8. 잔액 검증 (가장 중요한 검증)
  sleep(0.5);

  const afterRes = http.get(`${BASE_URL}/users/${userId}/wallet`, {
    tags: { name: "구매후_잔액" },
  });

  if (afterRes.status === 200) {
    const afterBalance = JSON.parse(afterRes.body).balance;

    // 핵심 검증: 잔액 음수 여부
    if (afterBalance < 0) {
      negativeBalance.add(1);
      console.error(
        `[잔액음수] userId=${userId}, ` +
          `구매전=${beforeBalance}, 구매후=${afterBalance}, ` +
          `성공=${successCount}건, 응답=[${statuses}]`
      );
    }

    // 초과 구매 검증
    if (successCount > maxAffordable) {
      overPurchase.add(1);
      console.error(
        `[초과구매] userId=${userId}, ` +
          `잔액=${beforeBalance}, 감당가능=${maxAffordable}건, ` +
          `실제성공=${successCount}건, 응답=[${statuses}]`
      );
    }

    // 잔액 정합성
    const expectedBalance = beforeBalance - (successCount * PRODUCT_PRICE);
    const isCorrect = afterBalance === expectedBalance && afterBalance >= 0;
    balanceCorrect.add(isCorrect);

    if (!isCorrect) {
      console.warn(
        `[정합성] userId=${userId}, ` +
          `기대=${expectedBalance}, 실제=${afterBalance}, ` +
          `성공=${successCount}건, 응답=[${statuses}]`
      );
    }
  }

  if (successCount !== 1 || errCount > 0) {
    console.log(
      `[패턴] VU=${__VU} ITER=${__ITER} ` +
        `201=${successCount} 4xx=${rejectCount} err=${errCount} ` +
        `잔액전=${beforeBalance} statuses=[${statuses}]`
    );
  }

  sleep(0.3);
}

// --------------- Teardown: 사용자별 결과 검증 ---------------
export function teardown(data) {
  if (!data || !data.userMap) return;

  const entries = Object.entries(data.userMap);
  const pad = (s, n) => String(s).padStart(n);
  const comma = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ",");

  console.log("\n");
  console.log("  ┌──────────────────────────────────────────────────────────────────────────┐");
  console.log("  │                      사용자별 잔액 경합 검증                               │");
  console.log("  ├──────────────┬──────────────┬──────────────┬──────────────┬────────────────┤");
  console.log("  │ 사용자       │ 시작 잔액     │ 현재 잔액     │ 잔액 변화     │ 판정           │");
  console.log("  ├──────────────┼──────────────┼──────────────┼──────────────┼────────────────┤");

  let passCount = 0;

  for (const [uid, info] of entries) {
    const res = http.get(`${BASE_URL}/users/${uid}/wallet`, {
      tags: { name: "검증_teardown" },
    });
    if (res.status !== 200) continue;

    const finalBalance = JSON.parse(res.body).balance;
    const delta = finalBalance - info.initialBalance;
    const ok = finalBalance >= 0;

    if (ok) passCount++;

    console.log(
      `  │ ${uid.slice(0, 12)}│` +
        `${pad(comma(info.initialBalance), 13)} │` +
        `${pad(comma(finalBalance), 13)} │` +
        `${pad((delta >= 0 ? "+" : "") + comma(delta), 13)} │` +
        ` ${ok ? "✅ 잔액 >= 0  " : "❌ 잔액 음수  "} │`
    );
  }

  console.log("  ├──────────────┴──────────────┴──────────────┴──────────────┼────────────────┤");
  console.log(
    `  │                                                              │ ${passCount}/${entries.length} 통과       │`
  );
  console.log("  └──────────────────────────────────────────────────────────────────────────────┘");
  console.log("");
}

// --------------- Report ---------------
export function handleSummary(data) {
  const m = data.metrics;
  const now = new Date().toISOString().slice(0, 10);
  const duration = data.state.testRunDurationMs;
  const min = Math.floor(duration / 60000);
  const sec = Math.floor((duration % 60000) / 1000);

  const v = (metric, stat) => {
    const val = m[metric] && m[metric].values[stat];
    return val !== undefined ? val : 0;
  };
  const fmt = (n, d = 2) => Number(n).toFixed(d);
  const comma = (n) =>
    String(Math.round(Number(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const pct = (n) => fmt(n * 100, 1) + "%";

  // thresholds
  const thresholds = [];
  const thresholdMetrics = [
    ["negative_balance", "잔액 음수 발생"],
    ["over_purchase", "초과 구매 발생"],
    ["balance_correct_rate", "잔액 정합성"],
  ];
  for (const [key, label] of thresholdMetrics) {
    if (m[key] && m[key].thresholds) {
      for (const [cond, t] of Object.entries(m[key].thresholds)) {
        const isRate = key.endsWith("_rate");
        const actual = isRate ? pct(v(key, "rate")) : comma(v(key, "count"));
        thresholds.push({ name: label, cond, actual, ok: t.ok });
      }
    }
  }

  const totalIterations = v("iterations", "count");
  const negativeCount = v("negative_balance", "count");
  const overPurchaseCount = v("over_purchase", "count");
  const singlePurchaseRate = v("single_purchase_rate", "rate");
  const balanceRate = v("balance_correct_rate", "rate");
  const noErrorRate = v("no_server_error_rate", "rate");

  // 응답 패턴 집계
  const p201_400 = v("pattern_201_400", "count");
  const p201_201 = v("pattern_201_201", "count");
  const p201_5xx = v("pattern_201_5xx", "count");
  const pAll4xx = v("pattern_all_4xx", "count");
  const pOther = v("pattern_other", "count");
  const patternTotal = p201_400 + p201_201 + p201_5xx + pAll4xx + pOther;

  const patternPct = (n) =>
    patternTotal > 0 ? fmt((n / patternTotal) * 100, 1) + "%" : "0%";

  // 전체 판정
  const allGood = negativeCount === 0 && overPurchaseCount === 0 && balanceRate > 0.95;
  const hasNegative = negativeCount > 0;
  const hasOverPurchase = overPurchaseCount > 0 && negativeCount === 0;

  let verdictClass, verdictText;
  if (allGood) {
    verdictClass = "verdict-pass";
    verdictText = "PASS - 잔액 경합 방어 성공, 음수 잔액 없음";
  } else if (hasNegative) {
    verdictClass = "verdict-fail";
    verdictText = `FAIL - 잔액 음수 ${comma(negativeCount)}건 발생 (동시 구매 경합 방어 실패)`;
  } else if (hasOverPurchase) {
    verdictClass = "verdict-fail";
    verdictText = `FAIL - 초과 구매 ${comma(overPurchaseCount)}건 (감당 불가능한 구매 성공)`;
  } else {
    verdictClass = "verdict-fail";
    verdictText = "FAIL - 잔액 정합성 미달";
  }

  const html = `<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>잔액 경합 시나리오 테스트 결과</title>
  <style>
    *{margin:0;padding:0;box-sizing:border-box}
    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0f172a;color:#e2e8f0;padding:2rem}
    h1{font-size:1.8rem;margin-bottom:.3rem}
    .subtitle{color:#94a3b8;margin-bottom:2rem;font-size:.9rem}
    .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:1rem;margin-bottom:2rem}
    .card{background:#1e293b;border-radius:12px;padding:1.5rem;border:1px solid #334155}
    .card-label{font-size:.75rem;color:#94a3b8;text-transform:uppercase;letter-spacing:.05em;margin-bottom:.5rem}
    .card-value{font-size:2rem;font-weight:700}
    .card-sub{font-size:.8rem;color:#64748b;margin-top:.25rem}
    .green{color:#4ade80}.red{color:#f87171}.blue{color:#60a5fa}.yellow{color:#facc15}.purple{color:#a78bfa}.orange{color:#fb923c}
    .section{margin-bottom:2rem}
    .section h2{font-size:1.2rem;margin-bottom:1rem;padding-bottom:.5rem;border-bottom:1px solid #334155}
    table{width:100%;border-collapse:collapse}
    th,td{text-align:left;padding:.75rem 1rem}
    th{color:#94a3b8;font-size:.75rem;text-transform:uppercase;letter-spacing:.05em;border-bottom:1px solid #334155}
    td{border-bottom:1px solid #1e293b;font-variant-numeric:tabular-nums}
    tr:hover td{background:#1e293b}
    .badge{display:inline-block;padding:.2rem .6rem;border-radius:9999px;font-size:.75rem;font-weight:600}
    .badge-pass{background:#065f46;color:#6ee7b7}
    .badge-fail{background:#7f1d1d;color:#fca5a5}
    .badge-warn{background:#78350f;color:#fde68a}
    .scenario-box{background:#1e293b;border:1px solid #334155;border-radius:12px;padding:1.5rem;margin-bottom:2rem;font-size:.85rem;line-height:1.8}
    .scenario-box code{background:#334155;padding:.15rem .4rem;border-radius:4px;font-size:.8rem}
    .info-table td{border:none;padding:.3rem 1rem .3rem 0;font-size:.85rem}
    .info-label{color:#94a3b8}
    .verdict{text-align:center;padding:2rem;border-radius:12px;margin-bottom:2rem;font-size:1.4rem;font-weight:700}
    .verdict-pass{background:#065f46;border:2px solid #4ade80}
    .verdict-fail{background:#7f1d1d;border:2px solid #f87171}
    .verdict-warn{background:#78350f;border:2px solid #facc15;color:#fde68a}
    .bar-container{display:flex;height:36px;border-radius:8px;overflow:hidden;margin-top:.5rem}
    .bar-segment{display:flex;align-items:center;justify-content:center;font-size:.75rem;font-weight:600;min-width:2px}
    .bar-legend{display:flex;gap:1.5rem;margin-top:.8rem;font-size:.8rem;flex-wrap:wrap}
    .bar-legend span{display:flex;align-items:center;gap:.4rem}
    .bar-legend .dot{width:10px;height:10px;border-radius:50%;display:inline-block}
  </style>
</head>
<body>

  <a href="/" style="display:inline-block;margin-bottom:1.5rem;color:#94a3b8;text-decoration:none;font-size:.85rem;transition:color .2s" onmouseover="this.style.color='#e2e8f0'" onmouseout="this.style.color='#94a3b8'">&larr; 대시보드</a>
  <h1>잔액 경합 시나리오 테스트</h1>
  <p class="subtitle">gonza-payment · 동시 구매 잔액 경합 검증 · ${now} · 총 ${min}분 ${sec}초</p>

  <div class="verdict ${verdictClass}">
    ${verdictText}
  </div>

  <div class="scenario-box">
    <strong>테스트 시나리오</strong><br>
    1. 사용자 잔액을 <code>${comma(PRODUCT_PRICE)}원</code>(스타벅스 아메리카노 가격)으로 설정<br>
    2. 동일 상품을 <strong>${CONCURRENT}건 동시 구매 요청</strong> (<code>http.batch</code>)<br>
    3. 잔액이 1건만 감당 가능 &rarr; <strong class="green">1건만 성공</strong>해야 정상<br>
    4. 2건 모두 성공 시 잔액이 <strong class="red">음수</strong>가 됨 &rarr; 경합 방어 실패<br>
    5. 구매 후 잔액 조회 &rarr; <code>잔액 &ge; 0</code> 검증
  </div>

  <div class="grid" style="grid-template-columns:1fr 1fr;margin-bottom:2rem">
    <div class="card">
      <div class="card-label" style="margin-bottom:.8rem;font-size:.85rem">테스트 조건</div>
      <table class="info-table">
        <tr><td class="info-label">동시 구매 수</td><td>${CONCURRENT}건 (동일 상품)</td></tr>
        <tr><td class="info-label">VU 수</td><td>${VUS}명</td></tr>
        <tr><td class="info-label">VU당 반복</td><td>${ITERATIONS}회</td></tr>
        <tr><td class="info-label">총 테스트 케이스</td><td>${comma(totalIterations)}건</td></tr>
        <tr><td class="info-label">상품 가격</td><td>${comma(PRODUCT_PRICE)}원 (스타벅스 아메리카노)</td></tr>
      </table>
    </div>
    <div class="card">
      <div class="card-label" style="margin-bottom:.8rem;font-size:.85rem">경합 조건</div>
      <table class="info-table">
        <tr><td class="info-label">구매 전 잔액</td><td>${comma(PRODUCT_PRICE)}원 (1건 감당 가능)</td></tr>
        <tr><td class="info-label">동시 구매 금액</td><td>${comma(PRODUCT_PRICE * CONCURRENT)}원 (${CONCURRENT}건)</td></tr>
        <tr><td class="info-label">기대 결과</td><td>1건 성공 + ${CONCURRENT - 1}건 잔액부족</td></tr>
        <tr><td class="info-label">방어 메커니즘</td><td>WHERE balance >= price (DB 레벨)</td></tr>
      </table>
    </div>
  </div>

  <div class="grid">
    <div class="card">
      <div class="card-label">잔액 음수</div>
      <div class="card-value ${negativeCount === 0 ? "green" : "red"}">${comma(negativeCount)}건</div>
      <div class="card-sub">구매 후 잔액이 음수가 된 횟수</div>
    </div>
    <div class="card">
      <div class="card-label">초과 구매</div>
      <div class="card-value ${overPurchaseCount === 0 ? "green" : "red"}">${comma(overPurchaseCount)}건</div>
      <div class="card-sub">감당 불가능한 구매가 성공한 횟수</div>
    </div>
    <div class="card">
      <div class="card-label">잔액 정합성</div>
      <div class="card-value ${balanceRate > 0.95 ? "green" : "red"}">${pct(balanceRate)}</div>
      <div class="card-sub">구매 전후 잔액 차이가 정확한 비율</div>
    </div>
    <div class="card">
      <div class="card-label">정확히 1건 성공</div>
      <div class="card-value ${singlePurchaseRate > 0.95 ? "green" : "yellow"}">${pct(singlePurchaseRate)}</div>
      <div class="card-sub">${CONCURRENT}건 중 201 응답이 정확히 1건</div>
    </div>
  </div>

  <div class="section">
    <h2>응답 패턴 분포 (${comma(patternTotal)}건)</h2>
    <p style="font-size:.85rem;color:#94a3b8;margin-bottom:1rem">동시 ${CONCURRENT}건 구매 요청 시 서버 응답 조합 분포</p>
    <div class="bar-container">
      ${p201_400 > 0 ? `<div class="bar-segment" style="width:${(p201_400/patternTotal*100)}%;background:#4ade80">${p201_400 > patternTotal * 0.05 ? comma(p201_400) : ""}</div>` : ""}
      ${p201_201 > 0 ? `<div class="bar-segment" style="width:${(p201_201/patternTotal*100)}%;background:#f87171">${p201_201 > patternTotal * 0.05 ? comma(p201_201) : ""}</div>` : ""}
      ${p201_5xx > 0 ? `<div class="bar-segment" style="width:${(p201_5xx/patternTotal*100)}%;background:#fb923c">${p201_5xx > patternTotal * 0.05 ? comma(p201_5xx) : ""}</div>` : ""}
      ${pAll4xx > 0  ? `<div class="bar-segment" style="width:${(pAll4xx/patternTotal*100)}%;background:#a855f7">${pAll4xx > patternTotal * 0.05 ? comma(pAll4xx) : ""}</div>` : ""}
      ${pOther > 0   ? `<div class="bar-segment" style="width:${(pOther/patternTotal*100)}%;background:#64748b">${pOther > patternTotal * 0.05 ? comma(pOther) : ""}</div>` : ""}
    </div>
    <div class="bar-legend">
      <span><span class="dot" style="background:#4ade80"></span> 201+400 이상적 (${patternPct(p201_400)})</span>
      <span><span class="dot" style="background:#f87171"></span> 201+201 경합실패 (${patternPct(p201_201)})</span>
      <span><span class="dot" style="background:#fb923c"></span> 201+5xx 서버에러 (${patternPct(p201_5xx)})</span>
      <span><span class="dot" style="background:#a855f7"></span> 전부 잔액부족 (${patternPct(pAll4xx)})</span>
      <span><span class="dot" style="background:#64748b"></span> 기타 (${patternPct(pOther)})</span>
    </div>

    <table style="margin-top:1.5rem">
      <thead><tr><th>응답 패턴</th><th>의미</th><th>건수</th><th>비율</th><th>판정</th></tr></thead>
      <tbody>
        <tr>
          <td><code>201, 400</code></td>
          <td>1건 성공 + 잔액부족 거절</td>
          <td>${comma(p201_400)}</td>
          <td>${patternPct(p201_400)}</td>
          <td><span class="badge badge-pass">이상적</span></td>
        </tr>
        <tr>
          <td><code>201, 201</code></td>
          <td>2건 모두 성공 (잔액 음수 위험)</td>
          <td>${comma(p201_201)}</td>
          <td>${patternPct(p201_201)}</td>
          <td><span class="badge badge-fail">경합실패</span></td>
        </tr>
        <tr>
          <td><code>201, 5xx</code></td>
          <td>1건 성공 + 서버 에러</td>
          <td>${comma(p201_5xx)}</td>
          <td>${patternPct(p201_5xx)}</td>
          <td><span class="badge badge-warn">에러</span></td>
        </tr>
        <tr>
          <td><code>전부 4xx</code></td>
          <td>모두 잔액부족 (충전 실패?)</td>
          <td>${comma(pAll4xx)}</td>
          <td>${patternPct(pAll4xx)}</td>
          <td><span class="badge badge-warn">확인필요</span></td>
        </tr>
        <tr>
          <td><code>기타</code></td>
          <td>분류 불가 패턴</td>
          <td>${comma(pOther)}</td>
          <td>${patternPct(pOther)}</td>
          <td><span class="badge" style="background:#334155;color:#94a3b8">-</span></td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="section">
    <h2>성공 기준 (Thresholds)</h2>
    <table>
      <thead><tr><th>지표</th><th>기준</th><th>실측값</th><th style="text-align:right">결과</th></tr></thead>
      <tbody>
${thresholds.map((t) => `        <tr><td>${t.name}</td><td>${t.cond}</td><td>${t.actual}</td><td style="text-align:right"><span class="badge ${t.ok ? "badge-pass" : "badge-fail"}">${t.ok ? "PASS" : "FAIL"}</span></td></tr>`).join("\n")}
      </tbody>
    </table>
  </div>

  <div class="section">
    <h2>방어 메커니즘 검증</h2>
    <p style="font-size:.85rem;color:#94a3b8;margin-bottom:1rem">
      현재 <code>subtractBalance</code>는 <code>WHERE balance &ge; :amount</code> 조건으로 DB 레벨에서 경합을 방어합니다.<br>
      이 테스트는 해당 방어가 동시 부하에서도 정상 동작하는지 검증하는 <strong>회귀 테스트</strong>입니다.
    </p>
    <table>
      <thead><tr><th>방어 없을 때 (가정)</th><th>현재 구현 (DB WHERE 절)</th></tr></thead>
      <tbody>
        <tr>
          <td><code>201 + 201</code> 두 건 모두 성공 &rarr; 잔액 음수</td>
          <td><code>201 + 400</code> 1건 성공 + 잔액부족 거절</td>
        </tr>
        <tr>
          <td class="red">UPDATE balance = balance - price (조건 없이)</td>
          <td class="green">UPDATE ... WHERE balance &ge; price (원자적 검증)</td>
        </tr>
        <tr>
          <td class="red">동시 트랜잭션이 모두 차감 &rarr; 음수 가능</td>
          <td class="green">row lock 후 WHERE 재평가 &rarr; 1건만 통과</td>
        </tr>
        <tr>
          <td><span class="badge badge-fail">FAIL</span></td>
          <td><span class="badge badge-pass">PASS (기대값)</span></td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="section">
    <h2>응답 시간 (ms)</h2>
    <table>
      <thead><tr><th>지표</th><th>평균</th><th>중앙값</th><th>p(90)</th><th>p(95)</th><th>최대</th></tr></thead>
      <tbody>
        <tr>
          <td>총 응답시간</td>
          <td>${fmt(v("http_req_duration", "avg"))}</td>
          <td>${fmt(v("http_req_duration", "med"))}</td>
          <td>${fmt(v("http_req_duration", "p(90)"))}</td>
          <td>${fmt(v("http_req_duration", "p(95)"))}</td>
          <td>${fmt(v("http_req_duration", "max"))}</td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="section">
    <h2>사용자별 잔액 검증</h2>
    <p style="font-size:.85rem;color:#94a3b8;margin-bottom:1rem">
      API 서버 구동 중일 때 실시간 잔액을 조회하여 음수 잔액 여부를 검증합니다
    </p>
    <div id="user-verify">
      <p style="color:#94a3b8" id="uv-status">잔액 조회 중...</p>
    </div>
  </div>

<script>
(function() {
  var userData = ${JSON.stringify(initialBalances)};
  var productPrice = ${PRODUCT_PRICE};
  var container = document.getElementById('user-verify');
  var statusEl = document.getElementById('uv-status');
  var entries = Object.entries(userData);

  if (!entries.length) {
    statusEl.textContent = '초기 잔액 스냅샷이 없습니다. balance-snapshot 서비스가 정상 실행되었는지 확인하세요.';
    return;
  }

  function comma(n) {
    return String(Math.round(n)).replace(/\\B(?=(\\d{3})+(?!\\d))/g, ',');
  }

  async function verify() {
    var rows = [];
    var passCount = 0;

    for (var i = 0; i < entries.length; i++) {
      var uid = entries[i][0];
      var info = entries[i][1];
      try {
        var res = await fetch('/api/users/' + uid + '/wallet');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        var data = await res.json();
        var finalBalance = data.balance;
        var delta = finalBalance - info.initialBalance;
        var ok = finalBalance >= 0;
        if (ok) passCount++;
        rows.push({
          uid: uid,
          initialBalance: info.initialBalance,
          finalBalance: finalBalance,
          delta: delta,
          ok: ok
        });
      } catch (e) {
        statusEl.innerHTML =
          'API 서버에 연결할 수 없습니다.<br>' +
          '<span style="font-size:.8rem;color:#64748b">' +
          'docker compose up api report 로 서버를 시작한 뒤 새로고침하세요.</span>';
        return;
      }
    }
    renderTable(rows, passCount, entries.length);
  }

  function renderTable(rows, passCount, total) {
    var html = '<table>';
    html += '<thead><tr>';
    html += '<th>사용자</th><th>시작 잔액</th><th>현재 잔액</th>';
    html += '<th>잔액 변화</th><th>판정</th>';
    html += '</tr></thead><tbody>';
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      var badge = r.ok
        ? '<span class="badge badge-pass">정상</span>'
        : '<span class="badge badge-fail">음수</span>';
      html += '<tr>';
      html += '<td><code>' + r.uid.slice(0, 8) + '...</code></td>';
      html += '<td>' + comma(r.initialBalance) + '원</td>';
      html += '<td class="' + (r.ok ? '' : 'red') + '">' + comma(r.finalBalance) + '원</td>';
      html += '<td class="' + (r.delta >= 0 ? 'green' : 'red') + '">' + (r.delta >= 0 ? '+' : '') + comma(r.delta) + '원</td>';
      html += '<td>' + badge + '</td>';
      html += '</tr>';
    }
    html += '<tr style="border-top:2px solid #334155;font-weight:600">';
    html += '<td colspan="4">합계</td>';
    html += '<td>' + passCount + '/' + total + ' 통과</td>';
    html += '</tr>';
    html += '</tbody></table>';
    container.innerHTML = html;
  }

  verify();
})();
</script>

</body>
</html>`;

  const summary = textSummary(data, { indent: " ", enableColors: true });
  const footer = "\n\n  ✅ 리포트: http://localhost:19000/balance-race/report.html\n";

  return {
    stdout: summary + footer,
    "/scripts/results/balance-race/summary.json": JSON.stringify(data, null, 2),
    "/scripts/results/balance-race/report.html": html,
  };
}
