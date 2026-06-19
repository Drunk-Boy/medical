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
@TableName("a_mcp_server_config")
public class McpServerConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String configJson;
    private String scope;
    private Boolean enabled;
    private String status;
    private Integer toolCount;
    private LocalDateTime lastConnectedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
