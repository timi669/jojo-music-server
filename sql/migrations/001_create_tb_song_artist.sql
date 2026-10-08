-- Add a many-to-many artist-credit table while keeping tb_song.artist_id as
-- the legacy primary artist for compatibility with existing application code.
-- Run against the intended database only after reviewing a backup.

START TRANSACTION;

CREATE TABLE IF NOT EXISTS `tb_song_artist` (
  `song_id` bigint NOT NULL COMMENT '歌曲 id',
  `artist_id` bigint NOT NULL COMMENT '歌手 id',
  `credit_order` smallint unsigned NOT NULL COMMENT '音频艺人标签中的顺序',
  `is_primary` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否为旧 artist_id 主艺人',
  `credit_source` varchar(32) NOT NULL DEFAULT 'manual' COMMENT '关联来源',
  PRIMARY KEY (`song_id`, `artist_id`),
  UNIQUE KEY `uk_song_artist_credit_order` (`song_id`, `credit_order`),
  KEY `idx_song_artist_artist_id` (`artist_id`),
  CONSTRAINT `fk_song_artist_link_song`
    FOREIGN KEY (`song_id`) REFERENCES `tb_song` (`id`)
    ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_song_artist_link_artist`
    FOREIGN KEY (`artist_id`) REFERENCES `tb_artist` (`id`)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO `tb_song_artist` (`song_id`, `artist_id`, `credit_order`, `is_primary`, `credit_source`)
SELECT `id`, `artist_id`, 1, 1, 'legacy-primary'
FROM `tb_song`;

COMMIT;