package com.zonak.portal.admin;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    private static final List<String> ALLOWED_ROLES = List.of("ADMIN", "OPERADOR", "CONSULTA");

    private final UserAdminRepository userAdminRepository;
    private final AdminPortalRepository adminPortalRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAdminController(
            UserAdminRepository userAdminRepository,
            AdminPortalRepository adminPortalRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userAdminRepository = userAdminRepository;
        this.adminPortalRepository = adminPortalRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/portal/admin/usuarios")
    public String listUsers(Model model) {
        model.addAttribute("usuarios", userAdminRepository.findUsers());
        model.addAttribute("sociedades", adminPortalRepository.findSociedades());
        model.addAttribute("roles", ALLOWED_ROLES);
        model.addAttribute("navModule", "configuracion");
        model.addAttribute("navActive", "usuarios");
        return "portal/admin/usuarios";
    }

    @PostMapping("/portal/admin/usuarios")
    public String saveUser(
            @RequestParam(required = false) String id,
            @RequestParam String username,
            @RequestParam(required = false) String password,
            @RequestParam String rol,
            @RequestParam(required = false) List<UUID> sociedadIds,
            RedirectAttributes redirectAttributes
    ) {
        String trimmedUsername = username == null ? "" : username.trim();
        if (!StringUtils.hasText(trimmedUsername)) {
            redirectAttributes.addFlashAttribute("error", "El usuario es obligatorio.");
            return "redirect:/portal/admin/usuarios";
        }
        if (!ALLOWED_ROLES.contains(rol)) {
            redirectAttributes.addFlashAttribute("error", "Rol inválido.");
            return "redirect:/portal/admin/usuarios";
        }

        UUID userId = StringUtils.hasText(id) ? UUID.fromString(id.trim()) : UUID.randomUUID();
        boolean isNew = !StringUtils.hasText(id);
        if (userAdminRepository.existsByUsername(trimmedUsername, isNew ? null : userId)) {
            redirectAttributes.addFlashAttribute("error", "Ya existe un usuario con ese nombre.");
            return "redirect:/portal/admin/usuarios";
        }

        if (isNew && !StringUtils.hasText(password)) {
            redirectAttributes.addFlashAttribute("error", "La contraseña es obligatoria para usuarios nuevos.");
            return "redirect:/portal/admin/usuarios";
        }

        String passwordHash = StringUtils.hasText(password) ? passwordEncoder.encode(password) : null;
        List<UUID> sociedades = sociedadIds == null
                ? List.of()
                : sociedadIds.stream().filter(s -> s != null).collect(Collectors.toList());

        try {
            userAdminRepository.saveUser(userId, trimmedUsername, passwordHash, rol, sociedades);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/portal/admin/usuarios";
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("error", "No se pudo guardar el usuario. Verifique datos únicos.");
            return "redirect:/portal/admin/usuarios";
        }

        redirectAttributes.addFlashAttribute("success", "Usuario guardado correctamente");
        return "redirect:/portal/admin/usuarios";
    }
}
