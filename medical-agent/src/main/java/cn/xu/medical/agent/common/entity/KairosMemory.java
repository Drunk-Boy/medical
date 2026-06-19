package cn.xu.medical.agent.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("a_kairos_memory")
public class KairosMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String scope;
    private String scopeKey;
    private String memoryKey;
    private String content;
    private String tags;
    private Integer importance;
    private Long accessCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
