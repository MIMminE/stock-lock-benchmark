# Runbook

## Docker Compose

```bash
docker compose up --build -d
```

서비스 포트:

| Service | URL |
| --- | --- |
| App UI | http://localhost:18081 |
| Prometheus | http://localhost:19090 |
| Grafana | http://localhost:13000 |

## API

실험 시작:

```bash
curl -X POST http://localhost:18081/api/tests/start \
  -H "Content-Type: application/json" \
  -d '{"concurrency":50,"initialStock":20000,"backoffMillis":5,"maxRetriesPerSuccess":500,"targetSuccessCount":20000}'
```

상태 확인:

```bash
curl http://localhost:18081/api/tests/{testId}
```

완료 리포트 확인:

```bash
curl http://localhost:18081/api/tests/{testId}/report
```

리포트 파일은 컨테이너 또는 로컬 실행 기준 `build/reports/stockbench` 아래에 JSON/CSV로 저장된다.
