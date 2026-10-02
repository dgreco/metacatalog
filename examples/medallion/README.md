# Medallion lakehouse example

A bronze / silver / gold lakehouse modelled with nothing but the public API: one aggregate
(`acme_retail`), three layers, eight tables, and an access policy that follows the layers.

| File | What it is |
| --- | --- |
| `medallion-model.yaml` | Traits, entity types and mappings. Each layer has its own table contract: bronze tables declare their source and ingestion mode, silver tables a primary key, gold tables a business owner. |
| `medallion-instances.yaml` | The `acme_retail` lakehouse. Lineage between tables is plain `dependsOn`; the policy grants by the `layer` the mappings stamp on every physical resource. |
| `docker-compose.medallion.yml` | Registers the two derived resource types (`LakeZoneType`, `LakeTableType`) with the demo `stdout` task, which prints what it would provision instead of touching anything. |

## Running it

From the repository root:

```bash
docker compose -f docker-compose.yml -f examples/medallion/docker-compose.medallion.yml up --build -d

API=http://localhost:8080/metacatalog/v1
curl -u admin:admin -X POST -H 'Content-Type: application/octet-stream' \
     --data-binary @examples/medallion/medallion-model.yaml $API/bulk-creation
curl -u admin:admin -X POST -H 'Content-Type: application/octet-stream' \
     --data-binary @examples/medallion/medallion-instances.yaml $API/aggregate/yaml

LH=$(curl -s -u admin:admin "$API/entity?entityTypeName=LakehouseType" | jq -r '.[0].id')
curl -X POST -u admin:admin $API/aggregate/$LH/provision
curl -X POST -u admin:admin $API/aggregate/$LH/authorize
docker logs metacatalog-app | grep -E '\[(provisioning|authorizing)\]'
```

The mapping engine derives three zones (`acme_retail_bronze`, `…_silver`, `…_gold`) and eight
physical tables a second or so after the aggregate loads. Provisioning runs in four waves (the
bronze zone; then the silver zone with the bronze tables; then the gold zone with the silver tables;
then the gold tables), because a table's mapping references its zone and a zone's mapping references
the zone upstream of it. Unprovisioning runs the same graph backwards.

The tables carry `Aggregate` as well as `AggregateElement`, the same shape as the base demo's
output ports: provisioning collects derived resources from the `Aggregate` carriers of the tree, so
a pure leaf's physical twin would not be provisioned.
