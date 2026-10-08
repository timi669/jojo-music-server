package cn.edu.seig.vibemusic.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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

    @TableField("song_id")
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
