package com.zonak.portal.admin;

import com.zonak.portal.mail.MailReceptionSyncService;
import com.zonak.portal.security.SensitiveDataCryptoService;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AdminPortalController {
    private final AdminPortalRepository adminPortalRepository;
    private final SensitiveDataCryptoService cryptoService;
    private final MailReceptionSyncService mailReceptionSyncService;
    private final DianCertificateProvisioningService dianCertificateProvisioningService;
    private final DianResolutionClient dianResolutionClient;

    public AdminPortalController(
            AdminPortalRepository adminPortalRepository,
            SensitiveDataCryptoService cryptoService,
            MailReceptionSyncService mailReceptionSyncService,
            DianCertificateProvisioningService dianCertificateProvisioningService,
            DianResolutionClient dianResolutionClient
    ) {
        this.adminPortalRepository = adminPortalRepository;
        this.cryptoService = cryptoService;
        this.mailReceptionSyncService = mailReceptionSyncService;
        this.dianCertificateProvisioningService = dianCertificateProvisioningService;
        this.dianResolutionClient = dianResolutionClient;
    }

    @GetMapping("/portal/admin/sociedades")
    public String sociedades(Model model) {
        model.addAttribute("sociedades", adminPortalRepository.findSociedades());
        model.addAttribute("regimenFiscalOptions", DianRegimenFiscal.emisorOptions());
        model.addAttribute("form", new SociedadForm());
        model.addAttribute("navModule", "configuracion");
        model.addAttribute("navActive", "sociedades");
        return "portal/admin/sociedades";
    }

    @PostMapping("/portal/admin/sociedades")
    public String saveSociedad(
            @ModelAttribute SociedadForm form,
            RedirectAttributes redirectAttributes
    ) {
        if (!StringUtils.hasText(form.getRazonSocial()) || !StringUtils.hasText(form.getNit())) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Razón social y NIT son obligatorios."
            );
            return "redirect:/portal/admin/sociedades";
        }
        if (!DianRegimenFiscal.isValid(form.getDianRegimenFiscal())) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Responsabilidad fiscal (TaxLevelCode) inválida."
            );
            return "redirect:/portal/admin/sociedades";
        }

        UUID id = StringUtils.hasText(form.getId())
                ? UUID.fromString(form.getId().trim())
                : UUID.randomUUID();
        String passwordSmtpEnc = StringUtils.hasText(form.getPasswordSmtp())
                ? cryptoService.encrypt(form.getPasswordSmtp())
                : null;
        String passwordImapEnc = StringUtils.hasText(form.getPasswordImap())
                ? cryptoService.encrypt(form.getPasswordImap())
                : null;
        String dianSoftwarePinPlaintext = StringUtils.hasText(form.getDianSoftwarePin())
                ? form.getDianSoftwarePin().trim()
                : null;
        String dianSoftwarePinEnc = dianSoftwarePinPlaintext != null
                ? cryptoService.encrypt(dianSoftwarePinPlaintext)
                : null;

        String sapPasswordEnc = StringUtils.hasText(form.getSapPassword())
                ? cryptoService.encrypt(form.getSapPassword())
                : null;

        try {
            adminPortalRepository.saveSociedad(
                    id,
                    form.getRazonSocial().trim(),
                    form.getNit().trim(),
                    trimToNull(form.getApiKey()),
                    trimToNull(form.getCorreoEmision()),
                    trimToNull(form.getCorreoRecepcion()),
                    trimToNull(form.getHostSmtp()),
                    form.getPuertoSmtp(),
                    trimToNull(form.getUsuarioSmtp()),
                    passwordSmtpEnc,
                    trimToNull(form.getHostImap()),
                    form.getPuertoImap(),
                    trimToNull(form.getUsuarioImap()),
                    passwordImapEnc,
                    form.getDianAmbiente(),
                    DianRegimenFiscal.normalize(form.getDianRegimenFiscal()),
                    trimToNull(form.getDianSoftwareId()),
                    dianSoftwarePinEnc,
                    dianSoftwarePinPlaintext,
                    form.getIdEmpresa(),
                    trimToNull(form.getSapUsuario()),
                    sapPasswordEnc
            );
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("error", mapSociedadSaveError(ex));
            return "redirect:/portal/admin/sociedades";
        }

        redirectAttributes.addFlashAttribute("success", "Sociedad guardada correctamente");
        return "redirect:/portal/admin/sociedades";
    }

    @PostMapping(value = "/portal/admin/sociedades/{id}/probar-conexion", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> probarConexionImap(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(mailReceptionSyncService.testIncomingConnection(id));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    @PostMapping("/portal/admin/sociedades/eliminar")
    public String deleteSociedad(
            @RequestParam UUID id,
            RedirectAttributes redirectAttributes
    ) {
        Optional<String> error = adminPortalRepository.deleteSociedad(id);
        if (error.isPresent()) {
            redirectAttributes.addFlashAttribute("error", error.get());
        } else {
            redirectAttributes.addFlashAttribute("success", "Sociedad eliminada correctamente");
        }
        return "redirect:/portal/admin/sociedades";
    }

    @GetMapping("/portal/admin/certificados")
    public String certificados(Model model) {
        model.addAttribute("sociedades", adminPortalRepository.findSociedades());
        model.addAttribute("certificados", adminPortalRepository.findCertificados());
        model.addAttribute("navModule", "configuracion");
        model.addAttribute("navActive", "certificados");
        return "portal/admin/certificados";
    }

    @GetMapping("/portal/admin/puntos-venta")
    public String puntosVenta(Model model) {
        model.addAttribute("sociedades", adminPortalRepository.findSociedades());
        model.addAttribute("puntosVenta", adminPortalRepository.findPuntosVenta());
        model.addAttribute("form", new PuntoVentaForm());
        model.addAttribute("navModule", "configuracion");
        model.addAttribute("navActive", "puntos");
        return "portal/admin/puntos-venta";
    }

    @PostMapping("/portal/admin/puntos-venta")
    public String savePuntoVenta(
            @ModelAttribute PuntoVentaForm form,
            RedirectAttributes redirectAttributes
    ) {
        UUID id = StringUtils.hasText(form.getId())
                ? UUID.fromString(form.getId().trim())
                : UUID.randomUUID();
        long rangoDesde = form.getRangoDesde() != null ? form.getRangoDesde() : 1L;
        long rangoHasta = form.getRangoHasta() != null ? form.getRangoHasta() : rangoDesde;
        long numeroActual = form.getNumeroActual() != null ? form.getNumeroActual() : rangoDesde - 1;
        long numeroActualNc = form.getNumeroActualNc() != null ? form.getNumeroActualNc() : rangoDesde - 1;
        long numeroActualNd = form.getNumeroActualNd() != null ? form.getNumeroActualNd() : rangoDesde - 1;

        adminPortalRepository.savePuntoVenta(
                id,
                form.getSociedadId(),
                form.getCodigo(),
                form.getNombre(),
                form.getDireccion(),
                form.getPrefijo(),
                form.getResolucionDian(),
                form.getClaveTecnica(),
                rangoDesde,
                rangoHasta,
                numeroActual,
                form.getPrefijoNc(),
                numeroActualNc,
                form.getPrefijoNd(),
                numeroActualNd,
                form.getVigenciaDesde(),
                form.getVigenciaHasta(),
                form.isActivo()
        );
        redirectAttributes.addFlashAttribute("success", "Punto de venta guardado correctamente");
        return "redirect:/portal/admin/puntos-venta";
    }

    @GetMapping(value = "/portal/admin/api/dian/resoluciones", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> consultarResolucionesDian(
            @RequestParam UUID sociedadId,
            @RequestParam(required = false) String resolutionNumber,
            @RequestParam(required = false) String prefix
    ) {
        SociedadDianContext context = adminPortalRepository.findSociedadDianContext(sociedadId);
        if (context == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Sociedad no encontrada"));
        }

        try {
            return ResponseEntity.ok(
                    dianResolutionClient.consultarResoluciones(context, resolutionNumber, prefix)
            );
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(502).body(java.util.Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/portal/admin/certificados")
    public String uploadCertificado(
            @RequestParam UUID sociedadId,
            @RequestParam String alias,
            @RequestParam LocalDate validoHasta,
            @RequestParam String password,
            @RequestParam(defaultValue = "true") boolean activo,
            @RequestParam MultipartFile certificado,
            RedirectAttributes redirectAttributes
    ) throws Exception {
        if (certificado.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Debe cargar un archivo .pfx o .p12");
            return "redirect:/portal/admin/certificados";
        }

        String filename = certificado.getOriginalFilename() == null ? "" : certificado.getOriginalFilename().toLowerCase();
        if (!filename.endsWith(".pfx") && !filename.endsWith(".p12")) {
            redirectAttributes.addFlashAttribute("error", "Formato inválido. Solo se aceptan .pfx o .p12");
            return "redirect:/portal/admin/certificados";
        }

        String contenidoBase64 = Base64.getEncoder().encodeToString(certificado.getBytes());
        adminPortalRepository.saveCertificado(
                sociedadId,
                alias,
                cryptoService.encrypt(contenidoBase64),
                cryptoService.encrypt(password),
                validoHasta,
                activo
        );
        try {
            dianCertificateProvisioningService.provisionFromUpload(sociedadId, certificado.getBytes(), password);
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "Certificado guardado en BD, pero no se pudo publicar en S3/Secrets para emisión DIAN: "
                            + ex.getMessage()
            );
            return "redirect:/portal/admin/certificados";
        }
        redirectAttributes.addFlashAttribute("success", "Certificado digital guardado y vinculado para emisión DIAN");
        return "redirect:/portal/admin/certificados";
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String mapSociedadSaveError(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause() != null
                ? ex.getMostSpecificCause().getMessage()
                : ex.getMessage();
        if (message == null) {
            return "No se pudo guardar la sociedad. Verifique NIT y API Key únicos.";
        }
        if (message.contains("uq_sociedades_id_empresa") || message.contains("id_empresa")) {
            return "El idEmpresa SAP ya está asignado a otra sociedad.";
        }
        if (message.contains("sociedades_nit") || message.contains("(nit)")) {
            return "El NIT ya está registrado en otra sociedad.";
        }
        if (message.contains("companies") && message.contains("nit")) {
            return "El NIT ya existe en otra empresa del sistema.";
        }
        return "No se pudo guardar la sociedad. Verifique NIT y API Key únicos.";
    }

    public static class SociedadForm {
        private String id;
        private String razonSocial;
        private String nit;
        private String apiKey;
        private String correoEmision;
        private String correoRecepcion;
        private String hostSmtp;
        private Integer puertoSmtp = 587;
        private String usuarioSmtp;
        private String passwordSmtp;
        private String hostImap;
        private Integer puertoImap = 993;
        private String usuarioImap;
        private String passwordImap;
        private String dianAmbiente = "Habilitacion";
        private String dianRegimenFiscal = DianRegimenFiscal.DEFAULT;
        private String dianSoftwareId;
        private String dianSoftwarePin;
        private Integer idEmpresa;
        private String sapUsuario;
        private String sapPassword;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getRazonSocial() {
            return razonSocial;
        }

        public void setRazonSocial(String razonSocial) {
            this.razonSocial = razonSocial;
        }

        public String getNit() {
            return nit;
        }

        public void setNit(String nit) {
            this.nit = nit;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCorreoEmision() {
            return correoEmision;
        }

        public void setCorreoEmision(String correoEmision) {
            this.correoEmision = correoEmision;
        }

        public String getCorreoRecepcion() {
            return correoRecepcion;
        }

        public void setCorreoRecepcion(String correoRecepcion) {
            this.correoRecepcion = correoRecepcion;
        }

        public String getHostSmtp() {
            return hostSmtp;
        }

        public void setHostSmtp(String hostSmtp) {
            this.hostSmtp = hostSmtp;
        }

        public Integer getPuertoSmtp() {
            return puertoSmtp;
        }

        public void setPuertoSmtp(Integer puertoSmtp) {
            this.puertoSmtp = puertoSmtp;
        }

        public String getUsuarioSmtp() {
            return usuarioSmtp;
        }

        public void setUsuarioSmtp(String usuarioSmtp) {
            this.usuarioSmtp = usuarioSmtp;
        }

        public String getPasswordSmtp() {
            return passwordSmtp;
        }

        public void setPasswordSmtp(String passwordSmtp) {
            this.passwordSmtp = passwordSmtp;
        }

        public String getHostImap() {
            return hostImap;
        }

        public void setHostImap(String hostImap) {
            this.hostImap = hostImap;
        }

        public Integer getPuertoImap() {
            return puertoImap;
        }

        public void setPuertoImap(Integer puertoImap) {
            this.puertoImap = puertoImap;
        }

        public String getUsuarioImap() {
            return usuarioImap;
        }

        public void setUsuarioImap(String usuarioImap) {
            this.usuarioImap = usuarioImap;
        }

        public String getPasswordImap() {
            return passwordImap;
        }

        public void setPasswordImap(String passwordImap) {
            this.passwordImap = passwordImap;
        }

        public String getDianAmbiente() {
            return dianAmbiente;
        }

        public void setDianAmbiente(String dianAmbiente) {
            this.dianAmbiente = dianAmbiente;
        }

        public String getDianRegimenFiscal() {
            return dianRegimenFiscal;
        }

        public void setDianRegimenFiscal(String dianRegimenFiscal) {
            this.dianRegimenFiscal = dianRegimenFiscal;
        }

        public String getDianSoftwareId() {
            return dianSoftwareId;
        }

        public void setDianSoftwareId(String dianSoftwareId) {
            this.dianSoftwareId = dianSoftwareId;
        }

        public String getDianSoftwarePin() {
            return dianSoftwarePin;
        }

        public void setDianSoftwarePin(String dianSoftwarePin) {
            this.dianSoftwarePin = dianSoftwarePin;
        }

        public Integer getIdEmpresa() {
            return idEmpresa;
        }

        public void setIdEmpresa(Integer idEmpresa) {
            this.idEmpresa = idEmpresa;
        }

        public String getSapUsuario() {
            return sapUsuario;
        }

        public void setSapUsuario(String sapUsuario) {
            this.sapUsuario = sapUsuario;
        }

        public String getSapPassword() {
            return sapPassword;
        }

        public void setSapPassword(String sapPassword) {
            this.sapPassword = sapPassword;
        }
    }

    public static class PuntoVentaForm {
        private String id;
        private UUID sociedadId;
        private String codigo;
        private String nombre;
        private String direccion;
        private String prefijo;
        private String resolucionDian;
        private String claveTecnica;
        private Long rangoDesde = 1L;
        private Long rangoHasta;
        private Long numeroActual = 0L;
        private String prefijoNc = "NC";
        private Long numeroActualNc = 0L;
        private String prefijoNd = "ND";
        private Long numeroActualNd = 0L;
        private LocalDate vigenciaDesde;
        private LocalDate vigenciaHasta;
        private boolean activo = true;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public UUID getSociedadId() {
            return sociedadId;
        }

        public void setSociedadId(UUID sociedadId) {
            this.sociedadId = sociedadId;
        }

        public String getCodigo() {
            return codigo;
        }

        public void setCodigo(String codigo) {
            this.codigo = codigo;
        }

        public String getNombre() {
            return nombre;
        }

        public void setNombre(String nombre) {
            this.nombre = nombre;
        }

        public String getDireccion() {
            return direccion;
        }

        public void setDireccion(String direccion) {
            this.direccion = direccion;
        }

        public String getPrefijo() {
            return prefijo;
        }

        public void setPrefijo(String prefijo) {
            this.prefijo = prefijo;
        }

        public String getResolucionDian() {
            return resolucionDian;
        }

        public void setResolucionDian(String resolucionDian) {
            this.resolucionDian = resolucionDian;
        }

        public String getClaveTecnica() {
            return claveTecnica;
        }

        public void setClaveTecnica(String claveTecnica) {
            this.claveTecnica = claveTecnica;
        }

        public Long getRangoDesde() {
            return rangoDesde;
        }

        public void setRangoDesde(Long rangoDesde) {
            this.rangoDesde = rangoDesde;
        }

        public Long getRangoHasta() {
            return rangoHasta;
        }

        public void setRangoHasta(Long rangoHasta) {
            this.rangoHasta = rangoHasta;
        }

        public Long getNumeroActual() {
            return numeroActual;
        }

        public void setNumeroActual(Long numeroActual) {
            this.numeroActual = numeroActual;
        }

        public String getPrefijoNc() {
            return prefijoNc;
        }

        public void setPrefijoNc(String prefijoNc) {
            this.prefijoNc = prefijoNc;
        }

        public Long getNumeroActualNc() {
            return numeroActualNc;
        }

        public void setNumeroActualNc(Long numeroActualNc) {
            this.numeroActualNc = numeroActualNc;
        }

        public String getPrefijoNd() {
            return prefijoNd;
        }

        public void setPrefijoNd(String prefijoNd) {
            this.prefijoNd = prefijoNd;
        }

        public Long getNumeroActualNd() {
            return numeroActualNd;
        }

        public void setNumeroActualNd(Long numeroActualNd) {
            this.numeroActualNd = numeroActualNd;
        }

        public LocalDate getVigenciaDesde() {
            return vigenciaDesde;
        }

        public void setVigenciaDesde(LocalDate vigenciaDesde) {
            this.vigenciaDesde = vigenciaDesde;
        }

        public LocalDate getVigenciaHasta() {
            return vigenciaHasta;
        }

        public void setVigenciaHasta(LocalDate vigenciaHasta) {
            this.vigenciaHasta = vigenciaHasta;
        }

        public boolean isActivo() {
            return activo;
        }

        public void setActivo(boolean activo) {
            this.activo = activo;
        }
    }
}
