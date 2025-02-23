-- Traits ---
INSERT INTO trait (id, name, base_schema, father_id) VALUES ('00000000-0000-0000-0000-000000000100', 'Aggregate', '{"type": "object", "properties": {}}', NULL);
INSERT INTO trait (id, name, base_schema, father_id) VALUES ('00000000-0000-0000-0000-000000000200', 'AggregateElement', '{"type": "object", "properties": {}}', NULL);
INSERT INTO trait (id, name, base_schema, father_id) VALUES ('00000000-0000-0000-0000-000000000300', 'Provisionable', '{"type": "object", "properties": {}}', '00000000-0000-0000-0000-000000000100');
INSERT INTO trait (id, name, base_schema, father_id) VALUES ('00000000-0000-0000-0000-000000000400', 'ProvisionableResource', '{"type": "object", "properties": {"provisioningStatus": {"type": "string", "enum": ["PROVISIONED", "UNPROVISIONED", "FAILED"]}, "provisioningResult": {"type": "string"}}}', '00000000-0000-0000-0000-000000000200');

-- Relationships ---
INSERT INTO trait_relationship (id, source_id, target_id, relation_type) VALUES ('00000000-0000-0000-0000-000000000100','00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000200', 'HAS_PART');