package com.zonak.portal.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class SupplierPointAdminController {
    private final SupplierDefaultPointRepository supplierDefaultPointRepository;
    private final AdminPortalRepository adminPortalRepository;

    public SupplierPointAdminController(
            SupplierDefaultPointRepository supplierDefaultPointRepository,
            AdminPortalRepository adminPortalRepository
    ) {
        this.supplierDefaultPointRepository = supplierDefaultPointRepository;
        this.adminPortalRepository = adminPortalRepository;
    }

    @GetMapping("/portal/admin/proveedores-punto")
    public String list(
            @RequestParam(required = false) String sociedadId,
            Model model
    ) {
        List<Sociedad> sociedades = adminPortalRepository.findSociedades();
        String selected = StringUtils.hasText(sociedadId)
                ? sociedadId
                : (sociedades.isEmpty() ? "" : sociedades.getFirst().id().toString());
        List<SupplierDefaultPoint> rows = selected.isBlank()
                ? List.of()
                : supplierDefaultPointRepository.findByCompany(UUID.fromString(selected));
        List<PuntoVenta> puntos = selected.isBlank()
                ? List.of()
                : adminPortalRepository.findPuntosVentaActivosBySociedades(List.of(UUID.fromString(selected)));

        model.addAttribute("sociedades", sociedades);
        model.addAttribute("selectedSociedadId", selected);
        model.addAttribute("rows", rows);
        model.addAttribute("puntosVenta", puntos);
        model.addAttribute("navModule", "configuracion");
        model.addAttribute("navActive", "proveedores-punto");
        return "portal/admin/proveedores-punto";
    }

    @PostMapping("/portal/admin/proveedores-punto")
    public String save(
            @RequestParam String sociedadId,
            @RequestParam String supplierNit,
            @RequestParam String emissionPointId,
            @RequestParam(required = false) String notes,
            RedirectAttributes redirectAttributes
    ) {
        try {
            supplierDefaultPointRepository.upsert(
                    UUID.fromString(sociedadId),
                    supplierNit,
                    UUID.fromString(emissionPointId),
                    notes
            );
            redirectAttributes.addFlashAttribute("success", "Asignación proveedor → punto guardada");
        } catch (IllegalArgumentException | DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage() != null ? ex.getMessage() : "No se pudo guardar");
        }
        return "redirect:/portal/admin/proveedores-punto?sociedadId=" + sociedadId;
    }

    @PostMapping("/portal/admin/proveedores-punto/desactivar")
    public String deactivate(
            @RequestParam String sociedadId,
            @RequestParam String id,
            RedirectAttributes redirectAttributes
    ) {
        supplierDefaultPointRepository.deactivate(UUID.fromString(id), UUID.fromString(sociedadId));
        redirectAttributes.addFlashAttribute("success", "Asignación desactivada");
        return "redirect:/portal/admin/proveedores-punto?sociedadId=" + sociedadId;
    }
}
