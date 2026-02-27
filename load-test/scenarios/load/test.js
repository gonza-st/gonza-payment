import http from "k6/http";
import { check, sleep } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";
import { SharedArray } from "k6/data";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

// seed.sh에서 생성한 데이터 로드
const testData = JSON.parse(open("../../results/users.json"));
const userIds = new SharedArray("userIds", () => testData.userIds);
const productIds = new SharedArray("productIds", () => testData.productIds);

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080/api";
const HEADERS = { "Content-Type": "application/json" };

// 동적 VU 설정 (기본값 100)
const MAX_VUS = parseInt(__ENV.TARGET_VUS || "100");
const HALF_VUS = Math.ceil(MAX_VUS / 2);

// 부하 프로필 (TARGET_VUS에 비례하여 자동 조정)
export const options = {
  stages: [
    { duration: "30s", target: HALF_VUS }, // Ramp-up: 0 → 절반
    { duration: "2m", target: HALF_VUS },  // Sustained: 절반 유지
    { duration: "30s", target: MAX_VUS },  // Spike: 절반 → 최대
    { duration: "1m", target: MAX_VUS },   // Sustained High: 최대 유지
    { duration: "30s", target: 0 },        // Ramp-down: 최대 → 0
  ],
  httpTimeouts: {
    request: "10s", // 요청 타임아웃 10초 (기본 60초 → 메모리 절약)
  },
  thresholds: {
    http_req_failed: ["rate<0.10"], // HTTP 실패율 < 10%
    http_req_duration: [
      "p(95)<2000", // p95 < 2초
      "p(99)<5000", // p99 < 5초
    ],
  },
};

export default function () {
  // 1. 랜덤 사용자 선택
  const userId = userIds[Math.floor(Math.random() * userIds.length)];

  // 2. 포인트 충전 (10,000원)
  const chargeRes = http.post(
    `${BASE_URL}/wallets/${userId}/charges`,
    JSON.stringify({ amount: 10000 }),
    {
      headers: {
        ...HEADERS,
        "Idempotency-Key": uuidv4(),
      },
      tags: { name: "충전" },
    }
  );
  check(chargeRes, {
    "충전 성공 (200)": (r) => r.status === 200,
  });

  // 3. 랜덤 상품으로 기프티콘 구매
  const productId = productIds[Math.floor(Math.random() * productIds.length)];
  const purchaseRes = http.post(
    `${BASE_URL}/users/${userId}/gifticons`,
    JSON.stringify({ productId: productId }),
    { headers: HEADERS, tags: { name: "구매" } }
  );
  check(purchaseRes, {
    "구매 성공 (201)": (r) => r.status === 201,
  });

  // 4. 구매한 기프티콘 사용
  if (purchaseRes.status === 201) {
    const gifticonId = JSON.parse(purchaseRes.body).gifticonId;
    const consumeRes = http.post(
      `${BASE_URL}/users/${userId}/gifticons/${gifticonId}/consume`,
      null,
      { headers: HEADERS, tags: { name: "사용" } }
    );
    check(consumeRes, {
      "사용 성공 (200)": (r) => r.status === 200,
    });
  }

  sleep(0.5);
}

// k6 결과 데이터 → summary.json + report.html 자동 생성
export function handleSummary(data) {
  const m = data.metrics;
  const duration = data.state.testRunDurationMs;
  const min = Math.floor(duration / 60000);
  const sec = Math.floor((duration % 60000) / 1000);
  const now = new Date().toISOString().slice(0, 10);

  const v = (metric, stat) => {
    const val = m[metric] && m[metric].values[stat];
    return val !== undefined ? val : 0;
  };
  const fmt = (n, d = 2) => Number(n).toFixed(d);
  const comma = (n) => String(Math.round(Number(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ",");

  const checks = data.root_group.checks || [];
  const totalChecks = checks.reduce((s, c) => s + c.passes + c.fails, 0);
  const passedChecks = checks.reduce((s, c) => s + c.passes, 0);
  const checkRate = totalChecks > 0 ? ((passedChecks / totalChecks) * 100).toFixed(2) : "0";

  const thresholds = [];
  if (m.http_req_failed && m.http_req_failed.thresholds) {
    for (const [k, t] of Object.entries(m.http_req_failed.thresholds)) {
      thresholds.push({ name: "HTTP 실패율", cond: k, actual: fmt(v("http_req_failed", "rate") * 100) + "%", ok: t.ok });
    }
  }
  if (m.http_req_duration && m.http_req_duration.thresholds) {
    for (const [k, t] of Object.entries(m.http_req_duration.thresholds)) {
      const match = k.match(/p\((\d+)\)/);
      const pVal = match ? v("http_req_duration", `p(${match[1]})`) : 0;
      thresholds.push({ name: `응답시간 ${k.split("<")[0].trim()}`, cond: k, actual: fmt(pVal) + "ms", ok: t.ok });
    }
  }

  const timingRows = [
    ["총 응답시간", "http_req_duration"],
    ["서버 처리시간", "http_req_waiting"],
    ["요청 전송", "http_req_sending"],
    ["응답 수신", "http_req_receiving"],
    ["연결 대기", "http_req_blocked"],
    ["TCP 연결", "http_req_connecting"],
    ["반복 소요시간", "iteration_duration"],
  ];

  const html = `<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>부하 테스트 결과</title>
  <style>
    *{margin:0;padding:0;box-sizing:border-box}
    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0f172a;color:#e2e8f0;padding:2rem}
    h1{font-size:1.8rem;margin-bottom:.3rem}
    .subtitle{color:#94a3b8;margin-bottom:2rem;font-size:.9rem}
    .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:1rem;margin-bottom:2rem}
    .card{background:#1e293b;border-radius:12px;padding:1.5rem;border:1px solid #334155}
    .card-label{font-size:.75rem;color:#94a3b8;text-transform:uppercase;letter-spacing:.05em;margin-bottom:.5rem}
    .card-value{font-size:2rem;font-weight:700}
    .card-sub{font-size:.8rem;color:#64748b;margin-top:.25rem}
    .green{color:#4ade80}.blue{color:#60a5fa}.purple{color:#a78bfa}.yellow{color:#facc15}
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
    .checks-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:1rem}
    .check-card{background:#1e293b;border-radius:12px;padding:1.2rem 1.5rem;border:1px solid #334155;display:flex;justify-content:space-between;align-items:center}
    .check-name{font-weight:500}
    .check-count{font-size:.85rem;color:#94a3b8}
    .bar-bg{background:#334155;border-radius:9999px;height:8px;margin-top:1rem;overflow:hidden}
    .bar-fill{height:100%;border-radius:9999px;background:linear-gradient(90deg,#4ade80,#22d3ee)}
    .info-table td{border:none;padding:.3rem 1rem .3rem 0;font-size:.85rem}
    .info-label{color:#94a3b8}
  </style>
</head>
<body>

  <h1>부하 테스트 결과</h1>
  <p class="subtitle">gonza-payment · 포인트 충전 / 기프티콘 구매 · ${now} · 총 ${min}분 ${sec}초</p>

  <div class="grid" style="grid-template-columns:1fr 1fr;margin-bottom:2rem">
    <div class="card">
      <div class="card-label" style="margin-bottom:.8rem;font-size:.85rem">테스트 조건</div>
      <table class="info-table">
        <tr><td class="info-label">시나리오</td><td>충전 → 구매 → 사용 (VU당 순차, VU간 병렬)</td></tr>
        <tr><td class="info-label">사전 데이터</td><td>사용자 30,000명 / 상품 3개</td></tr>
        <tr><td class="info-label">부하 프로필</td><td>Ramp-up 0→50 (30s) → 유지 50 (2m) → 스파이크 50→100 (30s) → 고부하 100 (1m) → Ramp-down (30s)</td></tr>
        <tr><td class="info-label">도구</td><td>k6 (grafana/k6 Docker)</td></tr>
      </table>
    </div>
    <div class="card">
      <div class="card-label" style="margin-bottom:.8rem;font-size:.85rem">서버 스펙 (Docker)</div>
      <table class="info-table">
        <tr><td class="info-label">API 서버</td><td>Spring Boot 3.3 / JDK 17 (eclipse-temurin:17-jre)</td></tr>
        <tr><td class="info-label">데이터베이스</td><td>PostgreSQL 16</td></tr>
        <tr><td class="info-label">PG 모의 지연</td><td>0ms (비활성)</td></tr>
        <tr><td class="info-label">PG 모의 실패율</td><td>0% (비활성)</td></tr>
        <tr><td class="info-label">리소스 제한</td><td>제한 없음 (Docker 기본값)</td></tr>
      </table>
    </div>
  </div>

  <div class="grid">
    <div class="card">
      <div class="card-label">총 요청 수</div>
      <div class="card-value blue">${comma(v("http_reqs", "count"))}</div>
      <div class="card-sub">초당 ${fmt(v("http_reqs", "rate"), 0)}건</div>
    </div>
    <div class="card">
      <div class="card-label">반복 횟수</div>
      <div class="card-value purple">${comma(v("iterations", "count"))}</div>
      <div class="card-sub">초당 ${fmt(v("iterations", "rate"), 1)}회</div>
    </div>
    <div class="card">
      <div class="card-label">HTTP 실패율</div>
      <div class="card-value green">${fmt(v("http_req_failed", "rate") * 100)}%</div>
      <div class="card-sub">실패 ${comma(v("http_req_failed", "passes"))}건 / 전체 ${comma(v("http_reqs", "count"))}건</div>
    </div>
    <div class="card">
      <div class="card-label">최대 동시 사용자</div>
      <div class="card-value yellow">${v("vus_max", "max")}</div>
      <div class="card-sub">최소 ${v("vus", "min")} · 최대 ${v("vus_max", "max")}</div>
    </div>
    <div class="card">
      <div class="card-label">송신 데이터</div>
      <div class="card-value" style="color:#f472b6">${fmt(v("data_sent", "count") / 1024 / 1024, 1)} MB</div>
      <div class="card-sub">${fmt(v("data_sent", "rate") / 1024, 1)} KB/s</div>
    </div>
    <div class="card">
      <div class="card-label">수신 데이터</div>
      <div class="card-value" style="color:#38bdf8">${fmt(v("data_received", "count") / 1024 / 1024, 1)} MB</div>
      <div class="card-sub">${fmt(v("data_received", "rate") / 1024, 1)} KB/s</div>
    </div>
  </div>

  <div class="section">
    <h2>성공 기준</h2>
    <table>
      <thead><tr><th>지표</th><th>기준</th><th>실측값</th><th style="text-align:right">결과</th></tr></thead>
      <tbody>
${thresholds.map((t) => `        <tr><td>${t.name}</td><td>${t.cond}</td><td>${t.actual}</td><td style="text-align:right"><span class="badge ${t.ok ? "badge-pass" : "badge-fail"}">${t.ok ? "통과" : "실패"}</span></td></tr>`).join("\n")}
      </tbody>
    </table>
  </div>

  <div class="section">
    <h2>검증 항목</h2>
    <div class="checks-grid">
${checks.map((c) => {
  const total = c.passes + c.fails;
  const rate = total > 0 ? ((c.passes / total) * 100).toFixed(0) : "0";
  return `      <div class="check-card"><div><div class="check-name">${c.name}</div><div class="check-count">${comma(c.passes)} / ${comma(total)}</div></div><span class="badge ${c.fails === 0 ? "badge-pass" : "badge-fail"}">${rate}%</span></div>`;
}).join("\n")}
    </div>
    <div class="bar-bg" style="margin-top:1rem"><div class="bar-fill" style="width:${checkRate}%"></div></div>
    <div style="text-align:right;font-size:.8rem;color:#94a3b8;margin-top:.3rem">전체 ${comma(totalChecks)}건 중 ${comma(passedChecks)}건 통과</div>
  </div>

  <div class="section">
    <h2>응답 시간 상세 (ms)</h2>
    <table>
      <thead><tr><th>지표</th><th>평균</th><th>최소</th><th>중앙값</th><th>p(90)</th><th>p(95)</th><th>최대</th></tr></thead>
      <tbody>
${timingRows.map(([label, key]) => {
  if (!m[key]) return "";
  const vals = m[key].values;
  return `        <tr><td>${label}</td><td>${fmt(vals.avg)}</td><td>${fmt(vals.min)}</td><td>${fmt(vals.med)}</td><td>${fmt(vals["p(90)"])}</td><td>${fmt(vals["p(95)"])}</td><td>${fmt(vals.max)}</td></tr>`;
}).join("\n")}
      </tbody>
    </table>
  </div>

</body>
</html>`;

  const summary = textSummary(data, { indent: " ", enableColors: true });
  const footer = "\n\n  ✅ 리포트: http://localhost:19000/report.html\n";

  return {
    stdout: summary + footer,
    "/scripts/results/summary.json": JSON.stringify(data, null, 2),
    "/scripts/results/report.html": html,
  };
}
