package co.gov.redvital.donacion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableMethodSecurity
public class RedvitalDonacionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedvitalDonacionServiceApplication.class, args);
    }
}
