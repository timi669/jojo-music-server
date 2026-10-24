-- Widen the artist introduction to hold full biographies, and record where the
-- avatar and introduction came from so the source stays auditable after a
-- profile-enrichment run.
--
-- Run against the intended database only after reviewing a backup.
-- Every statement is idempotent, so re-running is safe.

START TRANSACTION;

-- Introduction: the providers return multi-hundred-character biographies, so
-- make sure the column is TEXT rather than a bounded varchar.
ALTER TABLE `tb_artist`
  MODIFY COLUMN `introduction` TEXT
  COMMENT '歌手简介';

-- Source URLs: keep the originating page next to the value it produced.
-- Added conditionally, because MySQL 8 has no "ADD COLUMN IF NOT EXISTS".
SET @add_avatar_source := (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'tb_artist'
        AND COLUMN_NAME = 'avatar_source'
    ),
    'SELECT 1',
    'ALTER TABLE `tb_artist` ADD COLUMN `avatar_source` varchar(1000) NULL COMMENT ''歌手头像来源页面'''
  )
);
PREPARE stmt_avatar FROM @add_avatar_source;
EXECUTE stmt_avatar;
DEALLOCATE PREPARE stmt_avatar;

SET @add_introduction_source := (
  SELECT IF(
    EXISTS(
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'tb_artist'
        AND COLUMN_NAME = 'introduction_source'
    ),
    'SELECT 1',
    'ALTER TABLE `tb_artist` ADD COLUMN `introduction_source` varchar(1000) NULL COMMENT ''歌手简介来源页面'''
  )
);
PREPARE stmt_intro FROM @add_introduction_source;
EXECUTE stmt_intro;
DEALLOCATE PREPARE stmt_intro;

COMMIT;