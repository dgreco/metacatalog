"""Creates a set of demo tables in the metacatalog Iceberg REST catalog with PyIceberg.

Requires a running stack (``make run-iceberg`` from the repository root). Idempotent:
an existing namespace or table is left alone, so re-running never duplicates data —
sample rows are appended only when a table is created by this run.

Usage::

    cd metacatalog-iceberg-catalog/pyiceberg-test
    uv run python create_tables.py [--namespace demo] [--drop]

``--drop`` purges the demo tables and drops the namespace first, recreating
everything from scratch. Configuration comes from the same environment variables
the smoke tests use (``ICEBERG_CATALOG_URI``, ``ICEBERG_S3_*``).
"""

import argparse
import os
from datetime import date, datetime

import pyarrow as pa
from pyiceberg.catalog import load_catalog
from pyiceberg.exceptions import NamespaceAlreadyExistsError, NoSuchNamespaceError
from pyiceberg.schema import Schema
from pyiceberg.types import (
    DateType,
    DoubleType,
    LongType,
    NestedField,
    StringType,
    TimestampType,
)

TABLES = {
    "trips": {
        "schema": Schema(
            NestedField(1, "id", LongType(), required=True),
            NestedField(2, "vendor", StringType(), required=False),
            NestedField(3, "amount", DoubleType(), required=False),
            NestedField(4, "started_at", TimestampType(), required=False),
        ),
        "rows": {
            "id": pa.array([1, 2, 3, 4, 5], type=pa.int64()),
            "vendor": pa.array(["alpha", "beta", "alpha", "gamma", "beta"]),
            "amount": pa.array([12.5, 7.0, 3.3, 99.9, 42.0]),
            "started_at": pa.array(
                [
                    datetime(2026, 8, 1, 8, 30),
                    datetime(2026, 8, 1, 9, 10),
                    datetime(2026, 8, 2, 17, 45),
                    datetime(2026, 8, 3, 11, 0),
                    datetime(2026, 8, 3, 22, 15),
                ],
                type=pa.timestamp("us"),
            ),
        },
    },
    "vendors": {
        "schema": Schema(
            NestedField(1, "code", StringType(), required=True),
            NestedField(2, "name", StringType(), required=False),
            NestedField(3, "country", StringType(), required=False),
        ),
        "rows": {
            "code": pa.array(["alpha", "beta", "gamma"]),
            "name": pa.array(["Alpha Cabs", "Beta Rides", "Gamma Mobility"]),
            "country": pa.array(["IT", "FR", "DE"]),
        },
    },
    "daily_revenue": {
        "schema": Schema(
            NestedField(1, "day", DateType(), required=True),
            NestedField(2, "vendor", StringType(), required=False),
            NestedField(3, "total", DoubleType(), required=False),
            NestedField(4, "trip_count", LongType(), required=False),
        ),
        "rows": {
            "day": pa.array(
                [date(2026, 8, 1), date(2026, 8, 2), date(2026, 8, 3)], type=pa.date32()
            ),
            "vendor": pa.array(["alpha", "alpha", "beta"]),
            "total": pa.array([19.5, 3.3, 141.9]),
            "trip_count": pa.array([2, 1, 2], type=pa.int64()),
        },
    },
}


def arrow_table(table_spec):
    """Builds the sample rows with the exact arrow schema the Iceberg schema implies."""
    iceberg_schema = table_spec["schema"]
    arrow_schema = pa.schema(
        [
            pa.field(
                field.name,
                table_spec["rows"][field.name].type,
                nullable=not field.required,
            )
            for field in iceberg_schema.fields
        ]
    )
    return pa.table(table_spec["rows"], schema=arrow_schema)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--namespace", default="demo", help="target namespace (default: demo)")
    parser.add_argument(
        "--drop", action="store_true", help="purge the demo tables and namespace first"
    )
    args = parser.parse_args()

    catalog = load_catalog(
        "metacatalog",
        **{
            "type": "rest",
            "uri": os.environ.get("ICEBERG_CATALOG_URI", "http://localhost:8181"),
            "s3.endpoint": os.environ.get("ICEBERG_S3_ENDPOINT", "http://localhost:9000"),
            "s3.access-key-id": os.environ.get("ICEBERG_S3_ACCESS_KEY", "rustfsadmin"),
            "s3.secret-access-key": os.environ.get("ICEBERG_S3_SECRET_KEY", "rustfsadmin"),
            "s3.region": "us-east-1",
        },
    )

    if args.drop:
        try:
            for _, name in catalog.list_tables(args.namespace):
                if name in TABLES:
                    catalog.purge_table(f"{args.namespace}.{name}")
                    print(f"dropped {args.namespace}.{name}")
            if not catalog.list_tables(args.namespace):
                catalog.drop_namespace(args.namespace)
                print(f"dropped namespace {args.namespace}")
        except NoSuchNamespaceError:
            pass

    try:
        catalog.create_namespace(args.namespace)
        print(f"created namespace {args.namespace}")
    except NamespaceAlreadyExistsError:
        print(f"namespace {args.namespace} already exists")

    for name, spec in TABLES.items():
        identifier = f"{args.namespace}.{name}"
        if catalog.table_exists(identifier):
            print(f"table {identifier} already exists, skipped")
            continue
        table = catalog.create_table(identifier, schema=spec["schema"])
        rows = arrow_table(spec)
        table.append(rows)
        print(f"created {identifier} with {rows.num_rows} rows")


if __name__ == "__main__":
    main()
