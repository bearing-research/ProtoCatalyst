# datafusion-server

A minimal [Apache DataFusion](https://datafusion.apache.org/) query server exposing an
[Arrow Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) endpoint. It backs
ProtoCatalyst's **DataFusion execution backend**
(`executor/src/main/scala/protocatalyst/executor/datafusion/DataFusionBackend.scala`): the JVM client
transpiles `ProtoLogicalPlan` → SQL (`SqlGenerator`) and sends it here over ADBC Flight SQL.

## Run

```sh
cd tools/datafusion-server
cargo run --release
# Starting DataFusion Flight SQL server on 0.0.0.0:50051
```

It listens on `0.0.0.0:50051` — matching `FlightSqlConfig.localhost()` (`grpc://localhost:50051`). The
DataFusion session starts **empty**; tables are registered by the client via `CREATE EXTERNAL TABLE`
(e.g. `DataFusionBackend.registerParquetTable`).

## Used by

- `executor` `DataFusionBackendSuite` — integration tests that `assume()`-skip when this server is not
  running (so they pass in CI, but exercise the real path when it is up).
- `FlightSqlConnectTest` — a connectivity smoke test:
  `sbt 'executor/Test/runMain protocatalyst.executor.datafusion.FlightSqlConnectTest'`.

## Requirements

Rust (stable, edition 2021). Versions are pinned in `Cargo.lock`: `datafusion 52`,
`datafusion-flight-sql-server 0.4`, `tokio 1`. (`target/` is gitignored.)
