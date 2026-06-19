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
@TableName("a_agent_allow_rule")
public class AgentAllowRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pattern;
    private String toolName;
    private Integer priority;
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
