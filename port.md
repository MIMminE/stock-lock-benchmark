# 단일 Row 핫스팟 재고 차감 성능 실험 보고서 
**주제:** JPA 기반 낙관적 락(@Version) vs 비관적 락(SELECT … FOR UPDATE) 비교  
**핵심 키워드:** Hotspot, Lock Contention, Tail Latency, Retry/Backoff 비용, Throughput Saturation

---

## 0. 요약
단일 상품 재고(row) 1건에 대해 동시 다발적으로 차감을 수행하는 **핫스팟(Hotspot) 워크로드**에서, JPA의 **낙관적 락(@Version 기반 재시도)** 과 **비관적 락(SELECT FOR UPDATE 기반 락 대기)** 을 비교했다.

동시성을 5→10→30→50→100으로 증가시키며 측정한 결과:

- **낙관적 락은 동시성이 커질수록 충돌로 인한 재시도와 backoff가 누적**되어 처리량이 급격히 하락하고 지연(p95/p99)이 크게 악화되었다.
- **비관적 락은 재시도 없이 락 대기(큐잉)로 경합을 흡수**하면서, 동시성 100에서도 비교적 안정적인 처리량과 tail latency를 유지했다.
- 동시성 100 기준 처리량은 비관적 락이 낙관적 락 대비 **약 3.55배** 높았다.

---

## 1. 실험 목적
재고 차감과 같은 쓰기 작업은 “데이터 정합성”과 “성능” 사이에서 락 전략 선택이 중요하다.  
본 실험은 그중에서도 충돌 확률이 극단적으로 높은 **단일 row 핫스팟 업데이트**를 대상으로, 두 락 전략의 경합 처리 방식 차이가 성능에 미치는 영향을 정량적으로 관찰한다.

- 낙관적 락: 충돌을 허용하고 **커밋 시점에 버전 충돌 감지 → 재시도**로 수렴
- 비관적 락: 충돌을 줄이기 위해 **조회 시점에 row lock 선점 → 대기열로 직렬화**

---

## 2. 실험 환경 및 구현

### 2.1 기술 스택/구성
- Application: Spring + JPA(Hibernate)
- DB: PostgreSQL 16
- 실행: Docker Compose
- 모니터링: Prometheus + Grafana(구성은 포함했으나, 본 보고서는 애플리케이션 측정 JSON 기반 결과 중심)

### 2.2 락 구현 방식(JPA)
- **Optimistic Lock**
  - 엔티티에 `@Version` 필드 사용
  - 업데이트 경쟁 시 Hibernate가 버전 불일치를 감지하고 예외 발생(예: OptimisticLockException)
  - 애플리케이션에서 재시도 정책 적용
- **Pessimistic Lock**
  - 트랜잭션 내에서 대상 row를 `SELECT ... FOR UPDATE`로 조회(예: `@Lock(PESSIMISTIC_WRITE)`)
  - 동일 row에 대한 동시 갱신은 DB 락 대기로 직렬화

> 동일한 비즈니스 로직(재고 1 감소)을 유지한 상태에서 “락 전략만” 비교한다는 점에 의미가 있다.

---

## 3. 실험 설계

### 3.1 워크로드
- 단일 상품(단일 재고 row)에 대해 재고 차감 요청을 동시 발생
- 재고는 `initialStock=20000`에서 시작해 `targetSuccessCount=20000` 성공 시 종료(재고 소진)

### 3.2 파라미터(고정)
- `initialStock = 20000`
- `targetSuccessCount = 20000`
- `maxRetriesPerSuccess = 500`
- `backoffMillis = 5ms`

### 3.3 독립 변수
- `concurrency ∈ {5, 10, 30, 50, 100}`

### 3.4 측정 지표
- 처리량(throughputPerSec)
- 지연시간(avgSuccessLatencyMs, p50/p95/p99)
- 재시도 비용(totalRetries, avgAttemptsPerSuccess, totalBackoffMs)
- 실패 사유(failuresByReason)

#### 실패 `return_false` 해석
모든 케이스에서 `return_false`가 소량 발생했는데, 이는 재고 소진 시점에 이미 유입된 일부 요청이 “재고 부족”을 만나 정상적으로 실패한 것으로 해석했다.  
(시스템 오류라기보다는 테스트 종료 조건과 동시성 경쟁에 따른 자연 발생)

---

## 4. 결과

### 4.1 전체 결과 테이블
단위: duration=s, latency=ms

| Concurrency | Phase       | Duration(s) | Throughput (success/s) | Avg Success Lat | p95 (success) | p99 (success) | Avg Attempts/Success | Total Retries | Total Backoff(ms) |
|------------:|-------------|------------:|-----------------------:|----------------:|--------------:|--------------:|---------------------:|--------------:|------------------:|
|           5 | optimistic  |      53.540 |                373.552 |          12.741 |            69 |           244 |                2.232 |        24,668 |           123,340 |
|           5 | pessimistic |      41.752 |                479.019 |          10.104 |            32 |            68 |                1.000 |             0 |                 0 |
|          10 | optimistic  |      68.077 |                293.785 |          33.087 |            17 |           924 |                2.950 |        39,466 |           197,330 |
|          10 | pessimistic |      42.183 |                474.125 |          20.699 |           136 |           286 |                1.000 |             0 |                 0 |
|          30 | optimistic  |     127.798 |                156.497 |         190.758 |           635 |         1,013 |                9.092 |       162,138 |           810,690 |
|          30 | pessimistic |      45.129 |                443.174 |          66.970 |           205 |           322 |                1.000 |             0 |                 0 |
|          50 | optimistic  |     147.399 |                135.686 |         366.722 |         1,163 |         1,822 |               10.328 |       187,185 |           935,925 |
|          50 | pessimistic |      50.455 |                396.393 |         125.145 |           410 |           633 |                1.000 |             0 |                 0 |
|         100 | optimistic  |     178.095 |                112.300 |         880.411 |         2,886 |         4,677 |               11.942 |       221,179 |         1,105,895 |
|         100 | pessimistic |      50.144 |                398.851 |         248.201 |           756 |         1,206 |                1.000 |             0 |                 0 |

---

## 5. 분석

### 5.1 처리량 관점: 낙관적 락은 동시성 증가에 따라 급격히 비효율화
비관적 락 처리량이 낙관적 락 대비 얼마나 높은지(배수):

| Concurrency | $$\frac{TP_{pess}}{TP_{opt}}$$ |
|------------:|-------------------------------:|
|           5 |                          1.28× |
|          10 |                          1.61× |
|          30 |                          2.83× |
|          50 |                          2.92× |
|         100 |                          3.55× |

동시성 30을 넘어가며 격차가 급격히 커졌다. 이는 단일 row에 대한 update 충돌이 빈번해지는 구간에서 낙관적 락이 **재시도 중심의 비용 구조**를 갖기 때문이다.

### 5.2 지연/테일 관점: 낙관적 락은 tail latency가 빠르게 악화
동시성 100에서:
- optimistic p99 = 4677ms
- pessimistic p99 = 1206ms  
즉 $$\frac{4677}{1206}\approx 3.88$$ 로 낙관적 락의 tail이 크게 악화되었다.

또한 동시성 10에서 낙관락은 p95는 낮지만 p99가 큰데(17ms vs 924ms), 이는 “대부분 요청은 빠르나 일부 요청이 연속 충돌로 매우 길어지는” long tail 패턴이 나타났음을 의미한다.

### 5.3 재시도 비용: 성공 1건당 시도 횟수가 병목을 설명
낙관적 락의 `avgAttemptsPerSuccess`는 동시성 증가에 따라:
- 5 → 2.23
- 30 → 9.09
- 100 → 11.94  
로 상승했다.

단일 핫스팟에서는 충돌이 구조적으로 피하기 어려워, 재시도/백오프가 누적되면
- DB에는 불필요한 시도(업데이트 경쟁)가 증가하고
- 애플리케이션은 backoff 대기와 예외 처리 비용이 증가하며
- 결과적으로 throughput 하락 + tail latency 악화가 동시에 발생한다.

반면 비관적 락은 재시도 비용이 0이고, 락 대기로 경합을 흡수한다. 핫스팟에서 “충돌을 피할 수 없는” 상황이라면 이 방식이 더 안정적으로 동작할 수 있음을 수치로 확인했다.

---
## 6. 결론
본 실험은 단일 row 핫스팟 재고 차감에서:

- 낙관적 락(@Version)은 동시성이 커질수록 충돌→재시도→backoff 누적이 발생해 처리량이 감소하고 tail latency가 급격히 악화됨
- 비관적 락(SELECT FOR UPDATE)은 락 대기 기반 직렬화를 통해 재시도 비용을 제거하여, 높은 동시성에서도 더 높은 처리량과 더 낮은 tail latency를 기록함

을 보여준다. 특히 concurrency=100 기준 처리량은 비관적 락이 약 3.55배 높았다.
---

## 그래프 3종
1) Throughput vs Concurrency (Optimistic vs Pessimistic)  
2) p99 Latency vs Concurrency (Optimistic vs Pessimistic)  
3) AvgAttemptsPerSuccess vs Concurrency (Optimistic만)  
