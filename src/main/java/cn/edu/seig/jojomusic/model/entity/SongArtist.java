package cn.edu.seig.jojomusic.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_song_artist")
public class SongArtist implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 歌曲 id。
     * 注意：tb_song_artist 是 (song_id, artist_id) 复合主键，MyBatis-Plus 不支持复合主键，
     * 此处仅将 song_id 标记为 @TableId(INPUT) 以消除 "Not found @TableId" 告警，
     * 业务代码均通过 QueryWrapper 按 song_id + artist_id 精确操作，不依赖 xxById 方法。
     */
    @TableId(value = "song_id", type = IdType.INPUT)
    private Long songId;

    @TableField("artist_id")
    private Long artistId;

    @TableField("credit_order")
    private Integer creditOrder;

    @TableField("is_primary")
    private Integer isPrimary;

    @TableField("credit_source")
    private String creditSource;
}
