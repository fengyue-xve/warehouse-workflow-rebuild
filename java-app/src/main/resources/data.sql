-- DML-only synthetic seed: shared by startup and transactional reset.
MERGE INTO demo_meta (id, revision) KEY(id) SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM demo_meta WHERE id=1);
INSERT INTO catalog SELECT 'DEMO-CUP-01','演示保温杯',12 WHERE NOT EXISTS (SELECT 1 FROM catalog WHERE sku='DEMO-CUP-01');
INSERT INTO catalog SELECT 'DEMO-LAMP-01','演示台灯',6 WHERE NOT EXISTS (SELECT 1 FROM catalog WHERE sku='DEMO-LAMP-01');
INSERT INTO catalog SELECT 'DEMO-BAG-01','演示收纳包',10 WHERE NOT EXISTS (SELECT 1 FROM catalog WHERE sku='DEMO-BAG-01');
INSERT INTO inventory SELECT 'DEMO-CUP-01',80,DATE '2026-10-19','synthetic-seed',NULL WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku='DEMO-CUP-01');
INSERT INTO inventory SELECT 'DEMO-LAMP-01',40,DATE '2026-10-19','synthetic-seed',NULL WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku='DEMO-LAMP-01');
INSERT INTO inventory SELECT 'DEMO-BAG-01',120,DATE '2026-10-19','synthetic-seed',NULL WHERE NOT EXISTS (SELECT 1 FROM inventory WHERE sku='DEMO-BAG-01');
INSERT INTO baseline_sample(sample_key,row_count,total_units,origin) SELECT 'synthetic-reference-1',3,240,'FIXTURE' WHERE NOT EXISTS (SELECT 1 FROM baseline_sample WHERE sample_key='synthetic-reference-1');
INSERT INTO baseline_sample(sample_key,row_count,total_units,origin) SELECT 'synthetic-reference-2',3,240,'FIXTURE' WHERE NOT EXISTS (SELECT 1 FROM baseline_sample WHERE sample_key='synthetic-reference-2');
INSERT INTO baseline_sample(sample_key,row_count,total_units,origin) SELECT 'synthetic-reference-3',3,240,'FIXTURE' WHERE NOT EXISTS (SELECT 1 FROM baseline_sample WHERE sample_key='synthetic-reference-3');
