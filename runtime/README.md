# Stock Lock Benchmark Runtime

Docker Compose 기반 로컬 데모 런타임입니다.

```bash
docker compose up --build
```

서비스 URL:

| Service | URL |
| --- | --- |
| Benchmark Console | http://localhost:18081 |
| Health | http://localhost:18081/actuator/health |
| Prometheus | http://localhost:19090 |
| Grafana | http://localhost:13000 |
