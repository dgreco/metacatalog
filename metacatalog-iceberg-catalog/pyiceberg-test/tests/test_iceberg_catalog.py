"""Smoke tests driving the metacatalog Iceberg REST catalog with PyIceberg.

They require a running catalog (``make run-iceberg`` from the repository root) and
use their own namespaces, cleaning up after themselves.
"""

import os
import uuid

import pyarrow as pa
import pytest
from pyiceberg.catalog import load_catalog
from pyiceberg.exceptions import NamespaceAlreadyExistsError, NoSuchTableError
from pyiceberg.schema import Schema
from pyiceberg.types import DoubleType, LongType, NestedField, StringType

SCHEMA = Schema(
    NestedField(1, "id", LongType(), required=True),
    NestedField(2, "vendor", StringType(), required=False),
    NestedField(3, "amount", DoubleType(), required=False),
)

ARROW_SCHEMA = pa.schema(
    [
        pa.field("id", pa.int64(), nullable=False),
        pa.field("vendor", pa.string()),
        pa.field("amount", pa.float64()),
    ]
)

ROWS = pa.table(
    {
        "id": pa.array([1, 2, 3, 4, 5], type=pa.int64()),
        "vendor": pa.array(["alpha", "beta", "alpha", "gamma", "beta"]),
        "amount": pa.array([12.5, 7.0, 3.3, 99.9, 42.0]),
    },
    schema=ARROW_SCHEMA,
)


@pytest.fixture(scope="session")
def catalog():
    return load_catalog(
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


@pytest.fixture
def namespace(catalog):
    """A unique namespace per test, dropped (with leftovers) afterwards."""
    name = f"pyiceberg_smoke_{uuid.uuid4().hex[:8]}"
    catalog.create_namespace(name)
    yield name
    for _, table in catalog.list_tables(name):
        catalog.purge_table(f"{name}.{table}")
    for child in catalog.list_namespaces(name):
        catalog.drop_namespace(child)
    catalog.drop_namespace(name)


def test_namespace_lifecycle(catalog, namespace):
    assert (namespace,) in catalog.list_namespaces()
    with pytest.raises(NamespaceAlreadyExistsError):
        catalog.create_namespace(namespace)

    # Properties round-trip (mutable dict: the client probes containsKey(null)).
    catalog.update_namespace_properties(namespace, updates=dict(owner="pyiceberg"))
    assert catalog.load_namespace_properties(namespace)["owner"] == "pyiceberg"
    catalog.update_namespace_properties(namespace, removals={"owner"})
    assert "owner" not in catalog.load_namespace_properties(namespace)

    # Nested namespaces are first-class.
    catalog.create_namespace((namespace, "nested"))
    assert catalog.list_namespaces(namespace) == [(namespace, "nested")]


def test_table_lifecycle_with_data(catalog, namespace):
    identifier = f"{namespace}.trips"
    table = catalog.create_table(identifier, schema=SCHEMA)
    assert catalog.table_exists(identifier)

    # Two snapshot commits with real parquet data, read back through a scan.
    table.append(ROWS)
    table.append(ROWS)
    table = catalog.load_table(identifier)
    assert len(table.metadata.snapshots) == 2
    scanned = table.scan().to_arrow()
    assert scanned.num_rows == 10
    assert sum(scanned.column("amount").to_pylist()) == pytest.approx(329.4)

    # A property commit and a schema-evolution commit through the REST protocol.
    with table.transaction() as tx:
        tx.set_properties({"purpose": "smoke"})
    with catalog.load_table(identifier).update_schema() as update:
        update.add_column("category", StringType())
    table = catalog.load_table(identifier)
    assert table.properties["purpose"] == "smoke"
    assert len(table.schema().columns) == 4
    assert len(table.metadata.schemas) == 2

    # Rename keeps the data; drop removes the table.
    renamed = f"{namespace}.trips_v2"
    catalog.rename_table(identifier, renamed)
    assert not catalog.table_exists(identifier)
    assert catalog.load_table(renamed).scan().to_arrow().num_rows == 10
    catalog.drop_table(renamed)
    assert not catalog.table_exists(renamed)
    with pytest.raises(NoSuchTableError):
        catalog.load_table(renamed)
