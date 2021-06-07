CREATE TABLE `nickname`
(
    `nickname` varchar(20) COLLATE utf8mb4_bin NOT NULL COMMENT '名字',
    `qq`       bigint                          NOT NULL COMMENT 'qq号',
    PRIMARY KEY (`nickname`),
    UNIQUE KEY `nickname_UNIQUE` (`nickname`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

CREATE TABLE `pic_info`
(
    `filename`    varchar(128) COLLATE utf8mb4_bin NOT NULL,
    `qq`          bigint                           NOT NULL,
    `group`       bigint                           NOT NULL,
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`filename`),
    UNIQUE KEY `filename_UNIQUE` (`filename`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT ='黑历史图片的信息';