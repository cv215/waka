package com.waka;

import com.waka.operations.infrastructure.LocalEnvironmentInitializer;
import com.waka.operations.infrastructure.OperationalEvents;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WakaApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WakaApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.addInitializers(new LocalEnvironmentInitializer());
        try {
            application.run(args);
            OperationalEvents.ready();
        } catch (RuntimeException failure) {
            OperationalEvents.startupFailed(failure);
            System.exit(1);
        }
    }
}
