package org.quwuting.quwutingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QuwutingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuwutingServiceApplication.class, args);
    }

}
