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
@TableName("a_permission_audit")
public class PermissionAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String toolName;

    private String toolParams;

    private String decisionLevel;

    private String decision;

    private String reason;

    private String userResponse;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
