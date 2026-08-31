package com.zonak.portal.bootstrap;

import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.admin.DianCertificateProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DianCertificateBootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DianCertificateBootstrapRunner.class);

    private final AdminPortalRepository adminPortalRepository;
    private final DianCertificateProvisioningService dianCertificateProvisioningService;

    public DianCertificateBootstrapRunner(
            AdminPortalRepository adminPortalRepository,
            DianCertificateProvisioningService dianCertificateProvisioningService
    ) {
        this.adminPortalRepository = adminPortalRepository;
        this.dianCertificateProvisioningService = dianCertificateProvisioningService;
    }

    @Override
    public void run(ApplicationArguments args) {
        var pending = adminPortalRepository.findCertificadosPendingDianConfigSync();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Sincronizando {} certificado(s) digital(es) pendientes hacia S3/dian_config", pending.size());
        for (AdminPortalRepository.CertificadoProvisionRow row : pending) {
            try {
                dianCertificateProvisioningService.provisionFromStoredCertificate(row);
            } catch (RuntimeException ex) {
                log.error("No fue posible provisionar certificado sociedad_id={}: {}", row.sociedadId(), ex.getMessage());
            }
        }
    }
}
