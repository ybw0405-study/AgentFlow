-- V002 工作流定义与不可变版本（US-01）
CREATE TABLE workflow_definition (
  seq                BIGINT AUTO_INCREMENT UNIQUE,
  id                 CHAR(36)     NOT NULL,
  tenant_id          CHAR(36)     NOT NULL,
  name               VARCHAR(128) NOT NULL,
  description        VARCHAR(1024) NULL,
  status             ENUM('DRAFT','PUBLISHED','ARCHIVED') NOT NULL DEFAULT 'DRAFT',
  current_version_id CHAR(36)     NULL,
  draft_dsl          JSON         NULL,
  draft_checksum     CHAR(64)     NULL,
  draft_updated_at   DATETIME(3)  NULL,
  traffic_json       JSON         NULL,
  created_by         CHAR(36)     NOT NULL,
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at         DATETIME(3)  NULL,
  uq_name            VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_wf_tenant_name (tenant_id, uq_name),
  KEY idx_wf_tenant_status (tenant_id, status),
  KEY idx_wf_current_version (current_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_version (
  id            CHAR(36)    NOT NULL,
  definition_id CHAR(36)    NOT NULL,
  version_no    INT         NOT NULL,
  dsl_json      JSON        NOT NULL,
  dsl_checksum  CHAR(64)    NOT NULL,
  immutable     TINYINT(1)  NOT NULL DEFAULT 1,
  published_at  DATETIME(3) NULL,
  published_by  CHAR(36)    NULL,
  created_by    CHAR(36)    NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_wv_def_ver (definition_id, version_no),
  KEY idx_wv_def (definition_id),
  KEY idx_wv_checksum (dsl_checksum),
  CONSTRAINT fk_wv_def FOREIGN KEY (definition_id) REFERENCES workflow_definition(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER $$
CREATE TRIGGER trg_wv_no_update BEFORE UPDATE ON workflow_version FOR EACH ROW
BEGIN
  IF OLD.immutable = 1 AND (
       NOT (NEW.dsl_json <=> OLD.dsl_json)
       OR NEW.version_no <> OLD.version_no
       OR NEW.dsl_checksum <> OLD.dsl_checksum
       OR NEW.definition_id <> OLD.definition_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'WF-IMMUTABLE-001';
  END IF;
END$$
CREATE TRIGGER trg_wv_no_delete BEFORE DELETE ON workflow_version FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'WF-IMMUTABLE-001';
END$$
DELIMITER ;
