package com.zonak.portal.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {
    private final PortalUserRepository portalUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtCookieService jwtCookieService;

    public AuthController(
            PortalUserRepository portalUserRepository,
            PasswordEncoder passwordEncoder,
            JwtCookieService jwtCookieService
    ) {
        this.portalUserRepository = portalUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtCookieService = jwtCookieService;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @PostMapping("/login")
    public String login(
            @RequestParam String username,
            @RequestParam String password,
            HttpServletResponse response,
            Model model
    ) {
        Optional<AuthenticatedUser> user = portalUserRepository.findByUsername(username);
        if (user.isEmpty() || !passwordEncoder.matches(password, user.get().passwordHash())) {
            model.addAttribute("error", "Usuario o contraseña inválidos");
            return "login";
        }

        jwtCookieService.writeAuthCookie(response, jwtCookieService.createToken(user.get()));
        return "redirect:/portal";
    }

    @PostMapping("/logout")
    public String logout(HttpServletResponse response) {
        jwtCookieService.clearAuthCookie(response);
        return "redirect:/login";
    }
}
