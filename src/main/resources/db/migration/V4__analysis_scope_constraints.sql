-- A user's different jobs and snapshots must not be cross-wired either.
ALTER TABLE analysis_snapshot ADD CONSTRAINT analysis_snapshot_job_id UNIQUE(user_id,job_id,id);
ALTER TABLE analysis_fact ADD CONSTRAINT analysis_fact_job_id UNIQUE(user_id,job_id,id);
ALTER TABLE analysis_fact ADD CONSTRAINT analysis_fact_snapshot_id UNIQUE(user_id,snapshot_id,id);
ALTER TABLE analysis_fact ADD CONSTRAINT analysis_fact_job_snapshot_fk FOREIGN KEY(user_id,job_id,snapshot_id) REFERENCES analysis_snapshot(user_id,job_id,id);
ALTER TABLE analysis_result ADD CONSTRAINT analysis_result_job_id UNIQUE(user_id,job_id,id);
ALTER TABLE analysis_result_evidence ADD COLUMN job_id uuid;
UPDATE analysis_result_evidence e SET job_id=r.job_id FROM analysis_result r WHERE r.id=e.result_id AND r.user_id=e.user_id;
ALTER TABLE analysis_result_evidence ALTER COLUMN job_id SET NOT NULL;
ALTER TABLE analysis_result_evidence ADD CONSTRAINT analysis_result_evidence_job_fk FOREIGN KEY(user_id,job_id,result_id) REFERENCES analysis_result(user_id,job_id,id) ON DELETE CASCADE;
ALTER TABLE analysis_result_evidence ADD CONSTRAINT analysis_result_evidence_snapshot_fk FOREIGN KEY(user_id,job_id,snapshot_id) REFERENCES analysis_snapshot(user_id,job_id,id);
ALTER TABLE analysis_result_evidence ADD CONSTRAINT analysis_result_evidence_fact_snapshot_fk FOREIGN KEY(user_id,snapshot_id,fact_id) REFERENCES analysis_fact(user_id,snapshot_id,id);
ALTER TABLE analysis_graph_item ADD CONSTRAINT analysis_graph_item_snapshot_fk FOREIGN KEY(user_id,job_id,snapshot_id) REFERENCES analysis_snapshot(user_id,job_id,id);
ALTER TABLE analysis_dependency ADD COLUMN job_id uuid;
UPDATE analysis_dependency d SET job_id=r.job_id FROM analysis_result r WHERE r.id=d.result_id AND r.user_id=d.user_id;
ALTER TABLE analysis_dependency ALTER COLUMN job_id SET NOT NULL;
ALTER TABLE analysis_dependency ADD CONSTRAINT analysis_dependency_job_fk FOREIGN KEY(user_id,job_id,result_id) REFERENCES analysis_result(user_id,job_id,id) ON DELETE CASCADE;
ALTER TABLE analysis_dependency ADD CONSTRAINT analysis_dependency_parent_job_fk FOREIGN KEY(user_id,job_id,depends_on_id) REFERENCES analysis_result(user_id,job_id,id);
