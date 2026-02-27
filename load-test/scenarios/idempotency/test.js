import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate } from "k6/metrics";
import { SharedArray } from "k6/data";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

// =============================================================================
// 중복결제 시나리오 테스트
//
// 시나리오:
//   1. 클라이언트가 충전 요청
//   2. PG 응답 지연 (PG_MOCK_DELAY_MS=2000)
//   3. 클라이언트 타임아웃 → 동일 Idempotency-Key로 재요청
//   4. 서버가 2건을 동시 처리 → 중복결제 발생 여부 확인
//
// 수정 전/후 동일하게 사용 가능:
//   수정 전: [200, 500] 패턴 다수 → 5xx 에러 표시
//   수정 후: [200, 409] 패턴 다수 → 전체 PASS
// =============================================================================

const testData = JSON.parse(open("../../results/users.json"));
const userIds = new SharedArray("userIds", () => testData.userIds);

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";
const CONCURRENT = parseInt(__ENV.CONCURRENT_REQUESTS || "2");
const CHARGE_AMOUNT = 10000;

// --------------- Custom Metrics ---------------
const duplicateCharges = new Counter("duplicate_charges");
const serverErrors = new Counter("server_errors");
const singleSuccess = new Rate("single_success_rate");
const balanceCorrect = new Rate("balance_correct_rate");
const noServerError = new Rate("no_server_error_rate");

// 응답 패턴 카운터
const pattern200_409 = new Counter("pattern_200_409");   // 이상적 (수정 후 기대)
const pattern200_500 = new Counter("pattern_200_500");   // 미처리 예외 (수정 전 기대)
const pattern200_200 = new Counter("pattern_200_200");   // 멱등 반환 or 중복
const patternAll500 = new Counter("pattern_all_non200"); // 전부 실패
const patternOther = new Counter("pattern_other");       // 기타

// --------------- Test Options ---------------
const VUS = parseInt(__ENV.TARGET_VUS || "20");
const ITERATIONS = parseInt(__ENV.ITERATIONS || "10");

// 초기 잔액 스냅샷 (snapshot.sh → initial-balances.json → init context에서 읽기)
// init context의 open()은 handleSummary()에서도 접근 가능
let initialBalances = {};
try {
  initialBalances = JSON.parse(open("../../results/initial-balances.json"));
} catch (_) {
  // 스냅샷 파일이 없으면 빈 객체 (HTML에서 API 서버 연결 불가 안내)
}

export const options = {
  scenarios: {
    idempotency: {
      executor: "per-vu-iterations",
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: "10m",
    },
  },
  thresholds: {
    duplicate_charges: ["count==0"],
    balance_correct_rate: ["rate>0.95"],
  },
};

// --------------- Setup: 초기 잔액 기록 ---------------
export function setup() {
  const userMap = {};

  for (let vu = 1; vu <= VUS; vu++) {
    const uid = userIds[vu % userIds.length];
    if (userMap[uid]) {
      userMap[uid].expectedCharges += ITERATIONS;
      continue;
    }
    const res = http.get(`${BASE_URL}/users/${uid}/wallet`);
    if (res.status === 200) {
      userMap[uid] = {
        initialBalance: JSON.parse(res.body).balance,
        expectedCharges: ITERATIONS,
      };
    }
  }

  return { userMap };
}

// --------------- Main Test ---------------
export default function () {
  const userId = userIds[__VU % userIds.length];
  const idempotencyKey = `idem-${__VU}-${__ITER}-${Date.now()}`;

  // 1. 충전 전 잔액 조회
  const beforeRes = http.get(`${BASE_URL}/users/${userId}/wallet`, {
    tags: { name: "잔액조회_전" },
  });
  if (beforeRes.status !== 200) {
    console.warn(`[SKIP] 잔액 조회 실패: userId=${userId}, status=${beforeRes.status}`);
    return;
  }
  const beforeBalance = JSON.parse(beforeRes.body).balance;

  // 2. 동일 Idempotency-Key로 동시 N건 요청 (핵심)
  const batch = [];
  for (let i = 0; i < CONCURRENT; i++) {
    batch.push([
      "POST",
      `${BASE_URL}/wallets/${userId}/charges`,
      JSON.stringify({ amount: CHARGE_AMOUNT }),
      {
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": idempotencyKey,
        },
        tags: { name: `동시충전_${i + 1}` },
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
    else errCount++;
  }

  // 4. 응답 패턴 분류
  const sorted = [...statuses].sort();
  const key = sorted.join(",");

  if (okCount === 1 && conflictCount >= 1 && errCount === 0) {
    pattern200_409.add(1);
  } else if (okCount === 1 && errCount >= 1) {
    pattern200_500.add(1);
  } else if (okCount >= 2) {
    pattern200_200.add(1);
  } else if (okCount === 0) {
    patternAll500.add(1);
  } else {
    patternOther.add(1);
  }

  // 5. 메트릭 기록
  singleSuccess.add(okCount === 1);
  noServerError.add(errCount === 0);
  if (errCount > 0) serverErrors.add(errCount);

  // 6. 잔액 검증 (가장 중요한 검증)
  sleep(1);

  const afterRes = http.get(`${BASE_URL}/users/${userId}/wallet`, {
    tags: { name: "잔액조회_후" },
  });

  if (afterRes.status === 200) {
    const afterBalance = JSON.parse(afterRes.body).balance;
    const actualDelta = afterBalance - beforeBalance;
    const expectedDelta = CHARGE_AMOUNT;

    const isCorrect = actualDelta === expectedDelta;
    balanceCorrect.add(isCorrect);

    if (actualDelta > expectedDelta) {
      const extra = (actualDelta - expectedDelta) / CHARGE_AMOUNT;
      duplicateCharges.add(extra);
      console.error(
        `[중복결제] userId=${userId}, key=${idempotencyKey}, ` +
          `기대=+${expectedDelta}, 실제=+${actualDelta}, ` +
          `중복=${extra}건, 응답=[${statuses}]`
      );
    } else if (actualDelta < expectedDelta && okCount > 0) {
      console.warn(
        `[잔액부족] userId=${userId}, key=${idempotencyKey}, ` +
          `기대=+${expectedDelta}, 실제=+${actualDelta}, 응답=[${statuses}]`
      );
    }
  }

  if (okCount !== 1 || errCount > 0) {
    console.log(
      `[패턴] VU=${__VU} ITER=${__ITER} ` +
        `200=${okCount} 409=${conflictCount} err=${errCount} ` +
        `statuses=[${statuses}]`
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
  console.log("  ┌──────────────────────────────────────────────────────────────────────┐");
  console.log("  │                        사용자별 결제 검증                             │");
  console.log("  ├──────────────┬──────┬──────┬──────────────┬──────────────┬────────────┤");
  console.log("  │ 사용자       │ 시도 │ 충전 │ 기대 잔액변화 │ 실제 잔액변화 │ 판정       │");
  console.log("  ├──────────────┼──────┼──────┼──────────────┼──────────────┼────────────┤");

  let totalExpected = 0;
  let totalActual = 0;
  let passCount = 0;

  for (const [uid, info] of entries) {
    const res = http.get(`${BASE_URL}/users/${uid}/wallet`, {
      tags: { name: "검증_teardown" },
    });
    if (res.status !== 200) continue;

    const finalBalance = JSON.parse(res.body).balance;
    const actualDelta = finalBalance - info.initialBalance;
    const expectedDelta = info.expectedCharges * CHARGE_AMOUNT;
    const actualCharges = actualDelta / CHARGE_AMOUNT;
    const ok = actualDelta === expectedDelta;

    if (ok) passCount++;
    totalExpected += info.expectedCharges;
    totalActual += actualCharges;

    console.log(
      `  │ ${uid.slice(0, 12)}│` +
        `${pad(info.expectedCharges, 5)} │` +
        `${pad(actualCharges, 5)} │` +
        `${pad("+" + comma(expectedDelta), 13)} │` +
        `${pad("+" + comma(actualDelta), 13)} │` +
        ` ${ok ? "✅ 정상    " : "❌ 불일치  "} │`
    );
  }

  console.log("  ├──────────────┼──────┼──────┼──────────────┼──────────────┼────────────┤");
  console.log(
    `  │ 합계         │` +
      `${pad(totalExpected, 5)} │` +
      `${pad(totalActual, 5)} │` +
      `${pad("+" + comma(totalExpected * CHARGE_AMOUNT), 13)} │` +
      `${pad("+" + comma(totalActual * CHARGE_AMOUNT), 13)} │` +
      ` ${passCount}/${entries.length} 통과  │`
  );
  console.log("  └──────────────┴──────┴──────┴──────────────┴──────────────┴────────────┘");
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
    ["duplicate_charges", "중복결제 건수"],
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
  const duplicateCount = v("duplicate_charges", "count");
  const errorCount = v("server_errors", "count");
  const singleSuccessRate = v("single_success_rate", "rate");
  const balanceRate = v("balance_correct_rate", "rate");
  const noErrorRate = v("no_server_error_rate", "rate");

  // 응답 패턴 집계
  const p200_409 = v("pattern_200_409", "count");
  const p200_500 = v("pattern_200_500", "count");
  const p200_200 = v("pattern_200_200", "count");
  const pAllFail = v("pattern_all_non200", "count");
  const pOther = v("pattern_other", "count");
  const patternTotal = p200_409 + p200_500 + p200_200 + pAllFail + pOther;

  const patternPct = (n) =>
    patternTotal > 0 ? fmt((n / patternTotal) * 100, 1) + "%" : "0%";

  // 전체 판정
  const allGood = duplicateCount === 0 && errorCount === 0 && balanceRate > 0.95;
  const hasDuplicate = duplicateCount > 0;
  const hasErrors = errorCount > 0 && duplicateCount === 0;

  let verdictClass, verdictText;
  if (allGood) {
    verdictClass = "verdict-pass";
    verdictText = "PASS - 멱등성 보장, 중복결제 없음, 서버 에러 없음";
  } else if (hasDuplicate) {
    verdictClass = "verdict-fail";
    verdictText = `FAIL - 중복결제 ${comma(duplicateCount)}건 발생`;
  } else if (hasErrors) {
    verdictClass = "verdict-warn";
    verdictText = `WARN - 중복결제 없으나 서버 에러 ${comma(errorCount)}건 (미처리 예외)`;
  } else {
    verdictClass = "verdict-fail";
    verdictText = "FAIL - 잔액 정합성 미달";
  }

  const html = `<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>중복결제 시나리오 테스트 결과</title>
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

  <h1>중복결제 시나리오 테스트</h1>
  <p class="subtitle">gonza-payment · 멱등성 검증 · ${now} · 총 ${min}분 ${sec}초</p>

  <div class="verdict ${verdictClass}">
    ${verdictText}
  </div>

  <div class="scenario-box">
    <strong>테스트 시나리오</strong><br>
    1. 사용자별 충전 전 잔액 조회<br>
    2. 동일 <code>Idempotency-Key</code>로 <strong>${CONCURRENT}건 동시 요청</strong> (<code>http.batch</code>)<br>
    3. PG 모의 지연 <code>2,000ms</code> 동안 서버가 첫 요청을 처리하는 사이 재요청 도달<br>
    4. 충전 후 잔액 조회 &rarr; 기대값(+${comma(CHARGE_AMOUNT)}원)과 비교<br>
    5. 잔액이 기대보다 많으면 <strong class="red">중복결제</strong> 판정
  </div>

  <div class="grid" style="grid-template-columns:1fr 1fr;margin-bottom:2rem">
    <div class="card">
      <div class="card-label" style="margin-bottom:.8rem;font-size:.85rem">테스트 조건</div>
      <table class="info-table">
        <tr><td class="info-label">동시 요청 수</td><td>${CONCURRENT}건 (동일 Idempotency-Key)</td></tr>
        <tr><td class="info-label">VU 수</td><td>${VUS}명</td></tr>
        <tr><td class="info-label">VU당 반복</td><td>${ITERATIONS}회</td></tr>
        <tr><td class="info-label">총 테스트 케이스</td><td>${comma(totalIterations)}건</td></tr>
        <tr><td class="info-label">충전 금액</td><td>${comma(CHARGE_AMOUNT)}원 / 건</td></tr>
      </table>
    </div>
    <div class="card">
      <div class="card-label" style="margin-bottom:.8rem;font-size:.85rem">서버 설정</div>
      <table class="info-table">
        <tr><td class="info-label">PG 모의 지연</td><td>2,000ms</td></tr>
        <tr><td class="info-label">PG 모의 실패율</td><td>0%</td></tr>
        <tr><td class="info-label">멱등성 방어</td><td>DB UNIQUE(user_id, idempotency_key)</td></tr>
      </table>
    </div>
  </div>

  <div class="grid">
    <div class="card">
      <div class="card-label">중복결제</div>
      <div class="card-value ${duplicateCount === 0 ? "green" : "red"}">${comma(duplicateCount)}건</div>
      <div class="card-sub">잔액 기준 실제 중복 충전 횟수</div>
    </div>
    <div class="card">
      <div class="card-label">잔액 정합성</div>
      <div class="card-value ${balanceRate > 0.95 ? "green" : "red"}">${pct(balanceRate)}</div>
      <div class="card-sub">충전 전후 잔액 차이가 정확한 비율</div>
    </div>
    <div class="card">
      <div class="card-label">정확히 1건 성공</div>
      <div class="card-value ${singleSuccessRate > 0.95 ? "green" : "yellow"}">${pct(singleSuccessRate)}</div>
      <div class="card-sub">${CONCURRENT}건 중 200 응답이 정확히 1건</div>
    </div>
    <div class="card">
      <div class="card-label">5xx 에러 없음</div>
      <div class="card-value ${noErrorRate > 0.95 ? "green" : "red"}">${pct(noErrorRate)}</div>
      <div class="card-sub">미처리 예외(DataIntegrityViolation 등) 없음</div>
    </div>
  </div>

  <div class="section">
    <h2>응답 패턴 분포 (${comma(patternTotal)}건)</h2>
    <p style="font-size:.85rem;color:#94a3b8;margin-bottom:1rem">동시 ${CONCURRENT}건 요청 시 서버 응답 조합 분포</p>
    <div class="bar-container">
      ${p200_409 > 0 ? `<div class="bar-segment" style="width:${(p200_409/patternTotal*100)}%;background:#4ade80">${p200_409 > patternTotal * 0.05 ? comma(p200_409) : ""}</div>` : ""}
      ${p200_200 > 0 ? `<div class="bar-segment" style="width:${(p200_200/patternTotal*100)}%;background:#60a5fa">${p200_200 > patternTotal * 0.05 ? comma(p200_200) : ""}</div>` : ""}
      ${p200_500 > 0 ? `<div class="bar-segment" style="width:${(p200_500/patternTotal*100)}%;background:#f87171">${p200_500 > patternTotal * 0.05 ? comma(p200_500) : ""}</div>` : ""}
      ${pAllFail > 0 ? `<div class="bar-segment" style="width:${(pAllFail/patternTotal*100)}%;background:#a855f7">${pAllFail > patternTotal * 0.05 ? comma(pAllFail) : ""}</div>` : ""}
      ${pOther > 0   ? `<div class="bar-segment" style="width:${(pOther/patternTotal*100)}%;background:#64748b">${pOther > patternTotal * 0.05 ? comma(pOther) : ""}</div>` : ""}
    </div>
    <div class="bar-legend">
      <span><span class="dot" style="background:#4ade80"></span> 200+409 이상적 (${patternPct(p200_409)})</span>
      <span><span class="dot" style="background:#60a5fa"></span> 200+200 멱등반환 (${patternPct(p200_200)})</span>
      <span><span class="dot" style="background:#f87171"></span> 200+5xx 미처리예외 (${patternPct(p200_500)})</span>
      <span><span class="dot" style="background:#a855f7"></span> 전부 실패 (${patternPct(pAllFail)})</span>
      <span><span class="dot" style="background:#64748b"></span> 기타 (${patternPct(pOther)})</span>
    </div>

    <table style="margin-top:1.5rem">
      <thead><tr><th>응답 패턴</th><th>의미</th><th>건수</th><th>비율</th><th>판정</th></tr></thead>
      <tbody>
        <tr>
          <td><code>200, 409</code></td>
          <td>1건 성공 + 충돌 반환</td>
          <td>${comma(p200_409)}</td>
          <td>${patternPct(p200_409)}</td>
          <td><span class="badge badge-pass">이상적</span></td>
        </tr>
        <tr>
          <td><code>200, 200</code></td>
          <td>2건 모두 200 (멱등 반환 or 중복)</td>
          <td>${comma(p200_200)}</td>
          <td>${patternPct(p200_200)}</td>
          <td><span class="badge badge-warn">확인필요</span></td>
        </tr>
        <tr>
          <td><code>200, 5xx</code></td>
          <td>1건 성공 + 미처리 예외</td>
          <td>${comma(p200_500)}</td>
          <td>${patternPct(p200_500)}</td>
          <td><span class="badge badge-fail">버그</span></td>
        </tr>
        <tr>
          <td><code>전부 실패</code></td>
          <td>200 응답 없음</td>
          <td>${comma(pAllFail)}</td>
          <td>${patternPct(pAllFail)}</td>
          <td><span class="badge badge-fail">장애</span></td>
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
    <h2>수정 전후 비교 가이드</h2>
    <table>
      <thead><tr><th></th><th>수정 전 (현재)</th><th>수정 후 (목표)</th></tr></thead>
      <tbody>
        <tr>
          <td style="color:#94a3b8">주요 패턴</td>
          <td><code>200 + 500</code> <span class="badge badge-fail">버그</span></td>
          <td><code>200 + 409</code> <span class="badge badge-pass">이상적</span></td>
        </tr>
        <tr>
          <td style="color:#94a3b8">5xx 에러</td>
          <td class="red">다수 발생 (DataIntegrityViolationException)</td>
          <td class="green">0건</td>
        </tr>
        <tr>
          <td style="color:#94a3b8">중복결제</td>
          <td class="yellow">DB 제약조건이 방어 (0건 기대)</td>
          <td class="green">앱 레벨에서 방어 (0건)</td>
        </tr>
        <tr>
          <td style="color:#94a3b8">잔액 정합성</td>
          <td class="yellow">높음 (DB 제약 덕분)</td>
          <td class="green">100%</td>
        </tr>
        <tr>
          <td style="color:#94a3b8">전체 판정</td>
          <td><span class="badge badge-warn">WARN</span></td>
          <td><span class="badge badge-pass">PASS</span></td>
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
      API 서버 구동 중일 때 실시간 잔액을 조회하여 충전 전후 변화를 검증합니다
    </p>
    <div id="user-verify">
      <p style="color:#94a3b8" id="uv-status">잔액 조회 중...</p>
    </div>
  </div>

<script>
(function() {
  var userData = ${JSON.stringify(initialBalances)};
  var chargeAmount = ${CHARGE_AMOUNT};
  var container = document.getElementById('user-verify');
  var statusEl = document.getElementById('uv-status');
  var entries = Object.entries(userData);

  if (!entries.length) {
    statusEl.textContent = '초기 잔액 스냅샷이 없습니다. idem-snapshot 서비스가 정상 실행되었는지 확인하세요.';
    return;
  }

  function comma(n) {
    return String(Math.round(n)).replace(/\\B(?=(\\d{3})+(?!\\d))/g, ',');
  }

  async function verify() {
    var rows = [];
    var passCount = 0;
    var totalExpected = 0;
    var totalActual = 0;

    for (var i = 0; i < entries.length; i++) {
      var uid = entries[i][0];
      var info = entries[i][1];
      try {
        var res = await fetch('/api/users/' + uid + '/wallet');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        var data = await res.json();
        var finalBalance = data.balance;
        var actualDelta = finalBalance - info.initialBalance;
        var expectedDelta = info.expectedCharges * chargeAmount;
        var actualCharges = actualDelta / chargeAmount;
        var ok = actualDelta === expectedDelta;
        if (ok) passCount++;
        totalExpected += info.expectedCharges;
        totalActual += actualCharges;
        rows.push({
          uid: uid,
          expectedCharges: info.expectedCharges,
          actualCharges: actualCharges,
          initialBalance: info.initialBalance,
          finalBalance: finalBalance,
          expectedDelta: expectedDelta,
          actualDelta: actualDelta,
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
    renderTable(rows, passCount, entries.length, totalExpected, totalActual);
  }

  function renderTable(rows, passCount, total, totalExpected, totalActual) {
    var html = '<table>';
    html += '<thead><tr>';
    html += '<th>사용자</th><th>충전 시도</th><th>실제 충전</th>';
    html += '<th>기대 잔액변화</th><th>실제 잔액변화</th><th>판정</th>';
    html += '</tr></thead><tbody>';
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      var badge = r.ok
        ? '<span class="badge badge-pass">정상</span>'
        : '<span class="badge badge-fail">불일치</span>';
      html += '<tr>';
      html += '<td><code>' + r.uid.slice(0, 8) + '...</code></td>';
      html += '<td>' + r.expectedCharges + '건</td>';
      html += '<td class="' + (r.ok ? '' : 'red') + '">' + r.actualCharges + '건</td>';
      html += '<td>+' + comma(r.expectedDelta) + '원</td>';
      html += '<td class="' + (r.ok ? 'green' : 'red') + '">+' + comma(r.actualDelta) + '원</td>';
      html += '<td>' + badge + '</td>';
      html += '</tr>';
    }
    html += '<tr style="border-top:2px solid #334155;font-weight:600">';
    html += '<td>합계</td>';
    html += '<td>' + totalExpected + '건</td>';
    html += '<td>' + totalActual + '건</td>';
    html += '<td>+' + comma(totalExpected * chargeAmount) + '원</td>';
    html += '<td class="' + (totalActual === totalExpected ? 'green' : 'red') + '">+' + comma(totalActual * chargeAmount) + '원</td>';
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
  const footer = "\n\n  ✅ 리포트: http://localhost:19000/idempotency-report.html\n";

  return {
    stdout: summary + footer,
    "/scripts/results/idempotency-summary.json": JSON.stringify(data, null, 2),
    "/scripts/results/idempotency-report.html": html,
  };
}
