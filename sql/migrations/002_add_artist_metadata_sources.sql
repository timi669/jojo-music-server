-- Preserve attribution links for automatically sourced artist images and biographies.
ALTER TABLE `tb_artist`
    MODIFY COLUMN `introduction` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '歌手简介',
    ADD COLUMN `avatar_source` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '歌手头像来源页面' AFTER `introduction`,
    ADD COLUMN `introduction_source` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '歌手简介来源页面' AFTER `avatar_source`;
