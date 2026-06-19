package cn.xu.medical.agent.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("a_context_snapshot")
public class ContextSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private Integer totalTokens;
    private Integer maxTokens;
    private BigDecimal usagePct;
    private String breakdownJson;
    private Boolean compacted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
