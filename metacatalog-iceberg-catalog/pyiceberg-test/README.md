# metacatalog-iceberg-test

PyIceberg smoke tests for the metacatalog Iceberg REST catalog, managed with
[uv](https://docs.astral.sh/uv/). They drive a **running** catalog over HTTP with
Apache Iceberg's official Python client — the same protocol any Spark / Trino /
PyIceberg user exercises — creating namespaces and tables, appending real data,
scanning it back, renaming and dropping.

## Prerequisites

A running stack (from the repository root):

```bash
make run-iceberg          # or: make up-iceberg-d
```

This starts the Iceberg REST catalog on `http://localhost:8181` and the RustFS S3
warehouse on `http://localhost:9000` (see `docker-compose.iceberg.yml`).

## Run

```bash
cd metacatalog-iceberg-catalog/pyiceberg-test
uv run pytest
```

`uv run` creates the virtual environment and installs the dependencies on first
use; nothing else to set up.

## Demo tables

`create_tables.py` seeds the running catalog with a small set of demo tables
(`demo.trips`, `demo.vendors`, `demo.daily_revenue`), each with a few sample
rows written as real parquet to the S3 warehouse — handy for browsing the
catalog from the metacatalog UI, SPARQL, or another Iceberg client:

```bash
uv run python create_tables.py                 # create what is missing
uv run python create_tables.py --drop          # recreate from scratch
uv run python create_tables.py --namespace ns  # target another namespace
```

It is idempotent: existing tables are skipped and rows are only appended when a
table is created by that run, so re-running never duplicates data.

## Configuration

The defaults match the compose stack; override via environment variables when
testing another deployment:

| Variable | Default |
| --- | --- |
| `ICEBERG_CATALOG_URI` | `http://localhost:8181` |
| `ICEBERG_S3_ENDPOINT` | `http://localhost:9000` |
| `ICEBERG_S3_ACCESS_KEY` | `rustfsadmin` |
| `ICEBERG_S3_SECRET_KEY` | `rustfsadmin` |

The tests use their own `pyiceberg_smoke*` namespaces and clean up after
themselves, so they are safe to run against a stack holding other data.
