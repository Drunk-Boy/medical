package cn.xu.medical;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "cn.xu.medical")
@MapperScan("cn.xu.medical.**.mapper")
public class MedicalApplication {

    static void main(String[] args) {
        SpringApplication.run(MedicalApplication.class, args);
    }

    @Bean
    public ObjectMapper objectMapper() {
        // 基础实例
        return new ObjectMapper();
        // 如果需要更丰富的功能，可以添加：
        // .registerModule(new JavaTimeModule())
        // .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

}
