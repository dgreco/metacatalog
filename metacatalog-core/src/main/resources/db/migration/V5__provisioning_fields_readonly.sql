-- Mark provisioningStatus and provisioningResult as readOnly in the ProvisionableResource trait.
--
-- These two properties are set by the provisioning procedure, not by the user. Marking them
-- readOnly in the trait's base_schema causes the derived schema of any entity type using
-- ProvisionableResource to carry readOnly on these properties, so the UI form renders them as
-- disabled inputs and JSON-Schema validators treat them as server-managed.

UPDATE trait
SET base_schema = '{
  "type": "object",
  "properties": {
    "provisioningStatus": {
      "type": "string",
      "enum": ["PROVISIONED", "UNPROVISIONED", "FAILED"],
      "readOnly": true
    },
    "provisioningResult": {
      "type": "string",
      "readOnly": true
    }
  }
}'
WHERE name = 'ProvisionableResource';
