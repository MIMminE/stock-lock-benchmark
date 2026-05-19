const form = document.querySelector("#benchmark-form");
const runButton = document.querySelector("#run-button");
const statusPill = document.querySelector("#status-pill");
const testIdEl = document.querySelector("#test-id");
const phaseEl = document.querySelector("#current-phase");
const rawReport = document.querySelector("#raw-report");
const loadSampleButton = document.querySelector("#load-sample");

const throughputRatio = document.querySelector("#throughput-ratio");
const retryCount = document.querySelector("#retry-count");
const p99Delta = document.querySelector("#p99-delta");
const optimisticBar = document.querySelector("#optimistic-bar");
const pessimisticBar = document.querySelector("#pessimistic-bar");
const optimisticThroughput = document.querySelector("#optimistic-throughput");
const pessimisticThroughput = document.querySelector("#pessimistic-throughput");
const winnerText = document.querySelector("#winner-text");
const winnerDetail = document.querySelector("#winner-detail");
const riskText = document.querySelector("#risk-text");
const riskDetail = document.querySelector("#risk-detail");
const generateReportButton = document.querySelector("#generate-report");
const reportOutput = document.querySelector("#report-output");

let pollTimer = null;
let lastReport = null;

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  clearResult();
  setStatus("RUNNING");
  runButton.disabled = true;

  const payload = Object.fromEntries(new FormData(form).entries());
  for (const key of Object.keys(payload)) {
    payload[key] = Number(payload[key]);
  }

  try {
    const response = await fetch("/api/tests/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      throw new Error(`Start failed: HTTP ${response.status}`);
    }

    const body = await response.json();
    testIdEl.textContent = body.testId;
    rawReport.textContent = "실험 실행 중입니다. Optimistic phase 이후 Pessimistic phase가 이어집니다.";
    pollStatus(body.testId);
  } catch (error) {
    setStatus("FAILED");
    rawReport.textContent = error.message;
    runButton.disabled = false;
  }
});

loadSampleButton.addEventListener("click", async () => {
  const sample = {
    phases: [
      { phase: "optimistic", throughputPerSec: 135.686, totalRetries: 187185, successLatencyPercentiles: { p99: 1822 } },
      { phase: "pessimistic", throughputPerSec: 396.393, totalRetries: 0, successLatencyPercentiles: { p99: 633 } }
    ]
  };
  renderReport(sample);
  rawReport.textContent = JSON.stringify(sample, null, 2);
  setStatus("COMPLETED");
  phaseEl.textContent = "sample";
});

generateReportButton.addEventListener("click", () => {
  if (!lastReport) {
    reportOutput.textContent = "먼저 실험을 실행하거나 샘플 결과를 불러와야 보고서를 만들 수 있습니다.";
    return;
  }

  reportOutput.innerHTML = generateNarrativeReport(lastReport);
});

async function pollStatus(testId) {
  window.clearTimeout(pollTimer);

  try {
    const response = await fetch(`/api/tests/${testId}`);
    if (!response.ok) {
      throw new Error(`Status failed: HTTP ${response.status}`);
    }

    const status = await response.json();
    setStatus(status.status);
    phaseEl.textContent = status.currentPhase || "-";

    if (status.status === "COMPLETED") {
      await loadReport(testId);
      runButton.disabled = false;
      return;
    }

    if (status.status === "FAILED") {
      rawReport.textContent = status.message || "실험이 실패했습니다.";
      runButton.disabled = false;
      return;
    }

    pollTimer = window.setTimeout(() => pollStatus(testId), 1400);
  } catch (error) {
    setStatus("FAILED");
    rawReport.textContent = error.message;
    runButton.disabled = false;
  }
}

async function loadReport(testId) {
  const response = await fetch(`/api/tests/${testId}/report`);
  if (!response.ok) {
    rawReport.textContent = "실험은 완료됐지만 리포트 파일을 아직 읽지 못했습니다.";
    return;
  }

  const report = await response.json();
  renderReport(report);
  rawReport.textContent = JSON.stringify(report, null, 2);
}

function renderReport(report) {
  lastReport = report;
  const optimistic = report.phases?.find((phase) => phase.phase === "optimistic");
  const pessimistic = report.phases?.find((phase) => phase.phase === "pessimistic");

  if (!optimistic || !pessimistic) {
    return;
  }

  const ratio = pessimistic.throughputPerSec / optimistic.throughputPerSec;
  const p99Improvement = optimistic.successLatencyPercentiles.p99 / pessimistic.successLatencyPercentiles.p99;
  const maxThroughput = Math.max(optimistic.throughputPerSec, pessimistic.throughputPerSec);

  throughputRatio.textContent = `${ratio.toFixed(2)}x`;
  retryCount.textContent = optimistic.totalRetries.toLocaleString("ko-KR");
  p99Delta.textContent = `${p99Improvement.toFixed(2)}x`;

  optimisticThroughput.textContent = `${optimistic.throughputPerSec.toFixed(1)}/s`;
  pessimisticThroughput.textContent = `${pessimistic.throughputPerSec.toFixed(1)}/s`;
  optimisticBar.style.width = `${Math.max(4, optimistic.throughputPerSec / maxThroughput * 100)}%`;
  pessimisticBar.style.width = `${Math.max(4, pessimistic.throughputPerSec / maxThroughput * 100)}%`;

  winnerText.textContent = ratio >= 1
    ? "비관락이 처리량에서 우위"
    : "낙관락이 처리량에서 우위";
  winnerDetail.textContent = `비관락 처리량은 ${pessimistic.throughputPerSec.toFixed(1)}/s, 낙관락 처리량은 ${optimistic.throughputPerSec.toFixed(1)}/s로 관측됐습니다.`;

  riskText.textContent = optimistic.totalRetries > 0
    ? "재시도 비용 집중"
    : "재시도 비용 낮음";
  riskDetail.textContent = `낙관락 재시도 ${optimistic.totalRetries.toLocaleString("ko-KR")}회, p99 지연시간 개선 ${p99Improvement.toFixed(2)}x입니다.`;
}

function clearResult() {
  throughputRatio.textContent = "-";
  retryCount.textContent = "-";
  p99Delta.textContent = "-";
  optimisticThroughput.textContent = "-";
  pessimisticThroughput.textContent = "-";
  optimisticBar.style.width = "0";
  pessimisticBar.style.width = "0";
  testIdEl.textContent = "-";
  phaseEl.textContent = "-";
  winnerText.textContent = "결과 대기";
  winnerDetail.textContent = "실험 또는 샘플 결과를 불러오면 전략별 차이를 요약합니다.";
  riskText.textContent = "-";
  riskDetail.textContent = "재시도 비용, p99 지연시간, 처리량 차이를 함께 봅니다.";
  reportOutput.textContent = "아직 생성된 보고서가 없습니다.";
  lastReport = null;
}

function setStatus(status) {
  const normalized = (status || "IDLE").toLowerCase();
  statusPill.textContent = status || "Idle";
  statusPill.className = `status ${normalized}`;
}

function generateNarrativeReport(report) {
  const optimistic = report.phases?.find((phase) => phase.phase === "optimistic");
  const pessimistic = report.phases?.find((phase) => phase.phase === "pessimistic");

  if (!optimistic || !pessimistic) {
    return "<p>보고서 생성에 필요한 optimistic/pessimistic phase 결과가 부족합니다.</p>";
  }

  const ratio = pessimistic.throughputPerSec / optimistic.throughputPerSec;
  const p99Improvement = optimistic.successLatencyPercentiles.p99 / pessimistic.successLatencyPercentiles.p99;
  const retryCountText = optimistic.totalRetries.toLocaleString("ko-KR");

  return `
    <h3>재고 락 전략 벤치마크 결과</h3>
    <p>
      단일 재고 row에 주문 요청이 집중되는 조건에서 낙관락과 비관락을 비교했다.
      실험 결과 비관락은 ${pessimistic.throughputPerSec.toFixed(1)}/s,
      낙관락은 ${optimistic.throughputPerSec.toFixed(1)}/s의 처리량을 보였고,
      비관락이 약 ${ratio.toFixed(2)}배 높은 처리량을 기록했다.
    </p>
    <p>
      낙관락은 충돌 후 재시도를 통해 정합성을 맞추는 방식이기 때문에,
      단일 row 경합이 커질수록 재시도 비용이 누적된다.
      이번 결과에서도 낙관락 재시도는 ${retryCountText}회 발생했으며,
      p99 지연시간은 비관락 대비 약 ${p99Improvement.toFixed(2)}배 불리하게 나타났다.
    </p>
    <h4>결론</h4>
    <p>
      재고처럼 쓰기 요청이 특정 row에 몰리는 핫스팟 구간에서는 낙관락보다 비관락이 더 예측 가능한 처리량과 지연시간을 제공할 수 있다.
      다만 비관락은 락 대기와 트랜잭션 점유 시간이 커질 수 있으므로, 실제 서비스에서는 상품별 트래픽 분포와 재고 차감 경로를 함께 관측해야 한다.
    </p>
  `;
}
