import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate } from "k6/metrics";
import { SharedArray } from "k6/data";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

// =============================================================================
// 기프티콘 동시 Consume 시나리오 테스트
//
// 시나리오:
//   1. 사용자가 기프티콘을 구매 (ISSUED 상태)
//   2. 동일 기프티콘에 대해 N건 동시 consume 요청
//      - 네트워크 재시도로 같은 사용자가 2번 요청 (멱등 시나리오)
//      - 또는 공유된 기프티콘을 2명이 동시에 사용 시도 (운영 실수)
//   3. WHERE status=ISSUED 조건부 업데이트로 1건만 성공해야 함
//
// 검증:
//   동시 N건 요청 -> 정확히 1건 200, 나머지 409 (AlreadyConsumed)
// =============================================================================

const testData = JSON.parse(open("../../results/users.json"));
const userIds = new SharedArray("userIds", () => testData.userIds);
const productIds = new SharedArray("productIds", () => testData.productIds);

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";
const HEADERS = { "Content-Type": "application/json" };
const CONCURRENT = parseInt(__ENV.CONCURRENT_REQUESTS || "2");
const CHARGE_AMOUNT = 10000;

// --------------- Custom Metrics ---------------
const doubleConsume = new Counter("double_consume");
const singleSuccess = new Rate("single_success_rate");
const noServerError = new Rate("no_server_error_rate");

// 응답 패턴 카운터
const pattern200_409 = new Counter("pattern_200_409");   // 이상적: 1성공 + N-1 충돌
const pattern200_200 = new Counter("pattern_200_200");   // 위험: 2건 이상 성공
const pattern200_5xx = new Counter("pattern_200_5xx");   // 버그: 미처리 예외
const patternAll4xx  = new Counter("pattern_all_4xx");   // 전부 실패
const patternOther   = new Counter("pattern_other");     // 기타

// --------------- Test Options ---------------
const VUS = parseInt(__ENV.TARGET_VUS || "20");
const ITERATIONS = parseInt(__ENV.ITERATIONS || "10");

export const options = {
  scenarios: {
    consume_race: {
      executor: "per-vu-iterations",
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: "10m",
    },
  },
  thresholds: {
    double_consume: ["count==0"],
    single_success_rate: ["rate>0.95"],
  },
};

// --------------- Setup: 충전 ---------------
export function setup() {
  // 테스트에 사용할 사용자들에게 충분한 잔액 충전
  const targetUsers = new Set();
  for (let vu = 1; vu <= VUS; vu++) {
    targetUsers.add(userIds[vu % userIds.length]);
  }

  for (const uid of targetUsers) {
    for (let i = 0; i < ITERATIONS * 2; i++) {
      http.post(
        `${BASE_URL}/wallets/${uid}/charges`,
        JSON.stringify({ amount: CHARGE_AMOUNT }),
        {
          headers: { ...HEADERS, "Idempotency-Key": uuidv4() },
          tags: { name: "setup_충전" },
        }
      );
    }
  }

  return {};
}

// --------------- Main Test ---------------
export default function () {
  const userId = userIds[__VU % userIds.length];
  const productId = productIds[Math.floor(Math.random() * productIds.length)];

  // 1. 기프티콘 구매 (ISSUED 상태 생성)
  const purchaseRes = http.post(
    `${BASE_URL}/users/${userId}/gifticons`,
    JSON.stringify({ productId }),
    { headers: HEADERS, tags: { name: "구매" } }
  );

  if (purchaseRes.status !== 201) {
    console.warn(
      `[SKIP] 구매 실패: userId=${userId}, status=${purchaseRes.status}, body=${purchaseRes.body}`
    );
    return;
  }

  const gifticonId = JSON.parse(purchaseRes.body).gifticonId;

  // 2. 동일 기프티콘에 대해 동시 N건 consume 요청 (핵심)
  const batch = [];
  for (let i = 0; i < CONCURRENT; i++) {
    batch.push([
      "POST",
      `${BASE_URL}/users/${userId}/gifticons/${gifticonId}/consume`,
      null,
      {
        headers: HEADERS,
        tags: { name: `동시사용_${i + 1}` },
      },
    ]);
  }
  const responses = http.batch(batch);

  // 3. 응답 분류
  let okCount = 0;
  let conflictCount = 0;
  let errCount = 0;
  const statuses = [];

  for (const res of responses) {
    statuses.push(res.status);
    if (res.status === 200) okCount++;
    else if (res.status === 409) conflictCount++;
    else if (res.status >= 400 && res.status < 500) conflictCount++;
    else errCount++;
  }

  // 4. 응답 패턴 분류
  if (okCount === 1 && conflictCount >= 1 && errCount === 0) {
    pattern200_409.add(1);
  } else if (okCount >= 2) {
    pattern200_200.add(1);
    doubleConsume.add(okCount - 1);
  } else if (okCount === 1 && errCount >= 1) {
    pattern200_5xx.add(1);
  } else if (okCount === 0 && errCount === 0) {
    patternAll4xx.add(1);
  } else {
    patternOther.add(1);
  }

  // 5. 메트릭 기록
  singleSuccess.add(okCount === 1);
  noServerError.add(errCount === 0);

  if (okCount !== 1 || errCount > 0) {
    console.log(
      `[패턴] VU=${__VU} ITER=${__ITER} gifticonId=${gifticonId} ` +
        `200=${okCount} 409=${conflictCount} err=${errCount} ` +
        `statuses=[${statuses}]`
    );
  }

  if (okCount >= 2) {
    console.error(
      `[중복사용] gifticonId=${gifticonId}, 응답=[${statuses}] - ` +
        `${okCount}건이 동시에 200 반환 (상태 변경이 2번 이상 발생했을 수 있음)`
    );
  }

  sleep(0.2);
}

// --------------- Teardown ---------------
export function teardown() {
  console.log("\n  테스트 완료: 기프티콘 동시 Consume 시나리오\n");
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
    ["double_consume", "중복 사용 건수"],
    ["single_success_rate", "정확히 1건 성공률"],
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
  const doubleCount = v("double_consume", "count");
  const successRate = v("single_success_rate", "rate");
  const noErrRate = v("no_server_error_rate", "rate");

  // 응답 패턴 집계
  const p200_409 = v("pattern_200_409", "count");
  const p200_5xx = v("pattern_200_5xx", "count");
  const p200_200 = v("pattern_200_200", "count");
  const pAll4xx = v("pattern_all_4xx", "count");
  const pOther = v("pattern_other", "count");
  const patternTotal = p200_409 + p200_5xx + p200_200 + pAll4xx + pOther;

  const patternPct = (n) =>
    patternTotal > 0 ? fmt((n / patternTotal) * 100, 1) + "%" : "0%";

  // 전체 판정
  const allGood = doubleCount === 0 && successRate > 0.95 && noErrRate > 0.95;
  const hasDouble = doubleCount > 0;
  const hasErrors = noErrRate < 0.95 && doubleCount === 0;

  let verdictClass, verdictText;
  if (allGood) {
    verdictClass = "verdict-pass";
    verdictText = "PASS - 조건부 업데이트 정상 동작, 중복 사용 없음";
  } else if (hasDouble) {
    verdictClass = "verdict-fail";
    verdictText = `FAIL - 기프티콘 중복 사용 ${comma(doubleCount)}건 발생`;
  } else if (hasErrors) {
    verdictClass = "verdict-warn";
    verdictText = `WARN - 중복 사용 없으나 서버 에러 발생`;
  } else {
    verdictClass = "verdict-fail";
    verdictText = "FAIL - 정확히 1건 성공률 미달";
  }

  const html = `<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>기프티콘 동시 Consume 테스트 결과</title>
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
    .discussion{background:#1e293b;border:1px solid #334155;border-radius:12px;padding:1.5rem;margin-bottom:2rem}
    .discussion h3{font-size:1rem;margin-bottom:1rem;color:#60a5fa}
    .discussion ul{list-style:none;padding:0}
    .discussion li{padding:.5rem 0;border-bottom:1px solid #334155;font-size:.85rem;line-height:1.6}
    .discussion li:last-child{border-bottom:none}
    .discussion .q{color:#facc15;font-weight:600}
  </style>
</head>
<body>

  <a href="/" style="display:inline-block;margin-bottom:1.5rem;color:#94a3b8;text-decoration:none;font-size:.85rem;transition:color .2s" onmouseover="this.style.color='#e2e8f0'" onmouseout="this.style.color='#94a3b8'">&larr; 대시보드</a>
  <h1>기프티콘 동시 Consume 테스트</h1>
  <p class="subtitle">gonza-payment · 조건부 업데이트 검증 · ${now} · 총 ${min}분 ${sec}초</p>

  <div class="verdict ${verdictClass}">
    ${verdictText}
  </div>

  <div class="scenario-box">
    <strong>테스트 시나리오</strong><br>
    1. 사용자가 기프티콘 1개 구매 (상태: <code>ISSUED</code>)<br>
    2. 동일 기프티콘에 대해 <strong>${CONCURRENT}건 동시 consume 요청</strong> (<code>http.batch</code>)<br>
    &nbsp;&nbsp;&nbsp;- 네트워크 재시도: 같은 사용자가 동일 요청을 2번 보내는 상황<br>
    &nbsp;&nbsp;&nbsp;- 운영 실수: 공유된 기프티콘 코드로 2명이 동시 사용 시도<br>
    3. <code>WHERE status = 'ISSUED'</code> 조건부 업데이트로 정확히 1건만 성공<br>
    4. 나머지 요청은 <code>409 Conflict</code> (AlreadyConsumed) 반환
  </div>

  <div class="grid" style="grid-template-columns:1fr 1fr;margin-bottom:2rem">
    <div class="card">
      <div class="card-label" style="margin-bottom:.8rem;font-size:.85rem">테스트 조건</div>
      <table class="info-table">
        <tr><td class="info-label">동시 요청 수</td><td>${CONCURRENT}건 (동일 기프티콘)</td></tr>
        <tr><td class="info-label">VU 수</td><td>${VUS}명</td></tr>
        <tr><td class="info-label">VU당 반복</td><td>${ITERATIONS}회</td></tr>
        <tr><td class="info-label">총 테스트 케이스</td><td>${comma(totalIterations)}건</td></tr>
      </table>
    </div>
    <div class="card">
      <div class="card-label" style="margin-bottom:.8rem;font-size:.85rem">방어 메커니즘</div>
      <table class="info-table">
        <tr><td class="info-label">전략</td><td>조건부 업데이트 (WHERE status='ISSUED')</td></tr>
        <tr><td class="info-label">성공 응답</td><td>200 OK (상태 CONSUMED로 변경)</td></tr>
        <tr><td class="info-label">실패 응답</td><td>409 Conflict (AlreadyConsumed)</td></tr>
        <tr><td class="info-label">감사 추적</td><td>gifticon.consumed_at 타임스탬프 기록</td></tr>
      </table>
    </div>
  </div>

  <div class="grid">
    <div class="card">
      <div class="card-label">중복 사용</div>
      <div class="card-value ${doubleCount === 0 ? "green" : "red"}">${comma(doubleCount)}건</div>
      <div class="card-sub">동시 요청에서 2건 이상 200 반환된 횟수</div>
    </div>
    <div class="card">
      <div class="card-label">정확히 1건 성공</div>
      <div class="card-value ${successRate > 0.95 ? "green" : "red"}">${pct(successRate)}</div>
      <div class="card-sub">${CONCURRENT}건 중 200 응답이 정확히 1건</div>
    </div>
    <div class="card">
      <div class="card-label">5xx 에러 없음</div>
      <div class="card-value ${noErrRate > 0.95 ? "green" : "yellow"}">${pct(noErrRate)}</div>
      <div class="card-sub">미처리 예외 없이 정상 분기 처리</div>
    </div>
  </div>

  <div class="section">
    <h2>응답 패턴 분포 (${comma(patternTotal)}건)</h2>
    <p style="font-size:.85rem;color:#94a3b8;margin-bottom:1rem">동시 ${CONCURRENT}건 consume 요청 시 서버 응답 조합 분포</p>
    <div class="bar-container">
      ${p200_409 > 0 ? `<div class="bar-segment" style="width:${(p200_409/patternTotal*100)}%;background:#4ade80">${p200_409 > patternTotal * 0.05 ? comma(p200_409) : ""}</div>` : ""}
      ${p200_200 > 0 ? `<div class="bar-segment" style="width:${(p200_200/patternTotal*100)}%;background:#f87171">${p200_200 > patternTotal * 0.05 ? comma(p200_200) : ""}</div>` : ""}
      ${p200_5xx > 0 ? `<div class="bar-segment" style="width:${(p200_5xx/patternTotal*100)}%;background:#fb923c">${p200_5xx > patternTotal * 0.05 ? comma(p200_5xx) : ""}</div>` : ""}
      ${pAll4xx > 0  ? `<div class="bar-segment" style="width:${(pAll4xx/patternTotal*100)}%;background:#a855f7">${pAll4xx > patternTotal * 0.05 ? comma(pAll4xx) : ""}</div>` : ""}
      ${pOther > 0   ? `<div class="bar-segment" style="width:${(pOther/patternTotal*100)}%;background:#64748b">${pOther > patternTotal * 0.05 ? comma(pOther) : ""}</div>` : ""}
    </div>
    <div class="bar-legend">
      <span><span class="dot" style="background:#4ade80"></span> 200+409 이상적 (${patternPct(p200_409)})</span>
      <span><span class="dot" style="background:#f87171"></span> 200+200 중복사용 (${patternPct(p200_200)})</span>
      <span><span class="dot" style="background:#fb923c"></span> 200+5xx 미처리예외 (${patternPct(p200_5xx)})</span>
      <span><span class="dot" style="background:#a855f7"></span> 전부 실패 (${patternPct(pAll4xx)})</span>
      <span><span class="dot" style="background:#64748b"></span> 기타 (${patternPct(pOther)})</span>
    </div>

    <table style="margin-top:1.5rem">
      <thead><tr><th>응답 패턴</th><th>의미</th><th>건수</th><th>비율</th><th>판정</th></tr></thead>
      <tbody>
        <tr>
          <td><code>200, 409</code></td>
          <td>1건 성공 + 나머지 AlreadyConsumed</td>
          <td>${comma(p200_409)}</td>
          <td>${patternPct(p200_409)}</td>
          <td><span class="badge badge-pass">이상적</span></td>
        </tr>
        <tr>
          <td><code>200, 200</code></td>
          <td>2건 이상 성공 (중복 사용!)</td>
          <td>${comma(p200_200)}</td>
          <td>${patternPct(p200_200)}</td>
          <td><span class="badge badge-fail">위험</span></td>
        </tr>
        <tr>
          <td><code>200, 5xx</code></td>
          <td>1건 성공 + 미처리 예외</td>
          <td>${comma(p200_5xx)}</td>
          <td>${patternPct(p200_5xx)}</td>
          <td><span class="badge badge-warn">버그</span></td>
        </tr>
        <tr>
          <td><code>전부 4xx</code></td>
          <td>200 응답 없음 (구매 실패?)</td>
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
      현재 <code>consumeById</code>는 <code>WHERE status = 'ISSUED'</code> 조건부 업데이트로 동시 consume을 방어합니다.<br>
      이 테스트는 해당 방어가 동시 부하에서도 정상 동작하는지 검증하는 <strong>회귀 테스트</strong>입니다.
    </p>
    <table>
      <thead><tr><th>방어 없을 때 (가정)</th><th>현재 구현 (조건부 UPDATE)</th></tr></thead>
      <tbody>
        <tr>
          <td><code>200 + 200</code> 두 건 모두 성공</td>
          <td><code>200 + 409</code> 1건 성공 + AlreadyConsumed</td>
        </tr>
        <tr>
          <td class="red">SELECT 후 UPDATE (TOCTOU 취약)</td>
          <td class="green">UPDATE ... WHERE status='ISSUED' (원자적)</td>
        </tr>
        <tr>
          <td class="red">동시 트랜잭션이 모두 상태 변경 가능</td>
          <td class="green">row lock 후 WHERE 재평가 &rarr; 1건만 통과</td>
        </tr>
        <tr>
          <td><span class="badge badge-fail">FAIL</span></td>
          <td><span class="badge badge-pass">PASS (기대값)</span></td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="discussion">
    <h3>토론 포인트: consume은 멱등하게 할까, 409로 막을까?</h3>
    <ul>
      <li>
        <span class="q">현재 구현 (409 Conflict):</span>
        <code>WHERE status='ISSUED'</code> 조건부 업데이트 실패 시 409 반환.
        클라이언트가 "이미 사용됨"을 명확히 인지할 수 있음.
      </li>
      <li>
        <span class="q">멱등 정책 (200 반환):</span>
        이미 사용된 기프티콘에 대해 200 + 현재 상태를 반환.
        네트워크 재시도에 안전하지만, "2명이 동시에 사용"하는 경우를 구분하기 어려움.
      </li>
      <li>
        <span class="q">하이브리드:</span>
        같은 사용자의 재시도면 200 (멱등), 다른 사용자면 409 (충돌).
        가장 세밀하지만 구현 복잡도 증가.
      </li>
      <li>
        <span class="q">감사 로그:</span>
        어떤 정책이든 사용 시도 이력(성공/실패/시각/요청자)을 기록하면 운영 추적 가능.
      </li>
    </ul>
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

</body>
</html>`;

  const summary = textSummary(data, { indent: " ", enableColors: true });
  const footer = "\n\n  ✅ 리포트: http://localhost:19000/consume-race/report.html\n";

  return {
    stdout: summary + footer,
    "/scripts/results/consume-race/summary.json": JSON.stringify(data, null, 2),
    "/scripts/results/consume-race/report.html": html,
  };
}
