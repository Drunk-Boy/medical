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
@TableName("a_skill_definition")
public class SkillDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String scope;
    private String filePath;
    private String frontmatterJson;
    private String bodyMd;
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
