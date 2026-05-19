# Stock Lock Benchmark

단일 재고 row에 주문 요청이 몰리는 핫스팟 상황을 만들고, Optimistic Lock과 Pessimistic Lock의 처리량, 지연시간, 재시도 비용을 비교한 Spring Boot 기반 실험 프로젝트입니다.

![Benchmark summary](docs/images/benchmark-summary.svg)

## 핵심 결과

동시성 50 기준으로 Pessimistic Lock은 Optimistic Lock보다 약 `2.92x` 높은 성공 처리량을 보였고, Optimistic Lock은 `187,185`회의 재시도를 만들었습니다. 동시성 100에서는 처리량 격차가 약 `3.55x`까지 벌어졌습니다.

이 결과는 “비관락이 항상 낫다”는 결론이 아니라, 단일 row에 쓰기 요청이 집중되는 재고 핫스팟에서는 낙관락의 재시도 비용이 빠르게 커질 수 있음을 보여줍니다.

## 직접 실행

```bash
docker compose up --build -d
```

실행 후 브라우저에서 확인합니다.

| Target | URL |
| --- | --- |
| 실험 UI | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

UI에서 동시성, 초기 재고, 목표 성공 건수, backoff, 최대 재시도 횟수를 입력하고 실험을 시작할 수 있습니다. 실험은 `optimistic -> reset -> pessimistic` 순서로 실행되며, 완료 후 JSON 리포트가 화면에 표시됩니다.

## API

```bash
curl -X POST http://localhost:8080/api/tests/start \
  -H "Content-Type: application/json" \
  -d '{"concurrency":50,"initialStock":20000,"backoffMillis":5,"maxRetriesPerSuccess":500,"targetSuccessCount":20000}'
```

```bash
curl http://localhost:8080/api/tests/{testId}
curl http://localhost:8080/api/tests/{testId}/report
```

## 구조

```text
src/main/java/.../domain        Stock aggregate
src/main/java/.../repository    JPA optimistic/pessimistic lock access
src/main/java/.../service       Benchmark runner and report generation
src/main/java/.../api           Test start/status/report API
src/main/resources/static       Browser UI
report                          Saved benchmark snapshots
grafana                         Provisioned dashboard
prometheus                      Scrape configuration
docs                            Report and runbook
```

## 기술 스택

- Java 17
- Spring Boot
- Spring Data JPA
- PostgreSQL
- Micrometer / Prometheus / Grafana
- Docker Compose

## 문서

- [Benchmark Report](docs/benchmark-report.md)
- [Runbook](docs/runbook.md)

## 검증

```bash
./gradlew test
```

CI는 GitHub Actions에서 동일한 테스트를 실행합니다.
