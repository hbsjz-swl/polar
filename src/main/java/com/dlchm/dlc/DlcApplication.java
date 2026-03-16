package com.dlchm.dlc;

import com.dlchm.dlc.cli.DlcCli;
import com.dlchm.dlc.cli.DlcSetup;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * DLC - Local AI Coding Agent
 * 蒂爱嘉(北京)有限公司
 */
@SpringBootApplication
public class DlcApplication {

    public static void main(String[] args) {
        // Step 1: Ensure API config exists (interactive prompt if first run)
        DlcSetup.ensureConfigured();

        // Step 2: Boot Spring with config applied as system properties
        ConfigurableApplicationContext context = SpringApplication.run(DlcApplication.class, args);
        DlcCli cli = context.getBean(DlcCli.class);
        cli.run();
        context.close();
    }
}
