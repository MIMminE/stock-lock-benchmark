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

let pollTimer = null;

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
}

function setStatus(status) {
  const normalized = (status || "IDLE").toLowerCase();
  statusPill.textContent = status || "Idle";
  statusPill.className = `status ${normalized}`;
}
