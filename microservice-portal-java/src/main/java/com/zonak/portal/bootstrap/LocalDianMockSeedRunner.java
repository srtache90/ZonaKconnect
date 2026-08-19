package com.zonak.portal.bootstrap;

import com.zonak.portal.admin.AdminPortalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalDianMockSeedRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalDianMockSeedRunner.class);

    private final AdminPortalRepository adminPortalRepository;

    public LocalDianMockSeedRunner(AdminPortalRepository adminPortalRepository) {
        this.adminPortalRepository = adminPortalRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        adminPortalRepository.ensureLocalDianMockPuntoVenta();
        log.info(
                "Punto de venta DIAN Mock listo para Sociedad Local Zona K (id={})",
                AdminPortalRepository.LOCAL_DIAN_MOCK_PUNTO_VENTA_ID
        );
    }
}
