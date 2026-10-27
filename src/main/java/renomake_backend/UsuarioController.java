
package renomake_backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/usuarios")
@CrossOrigin(origins = {
        "https://viniciosalvees.github.io",
        "http://localhost:5173"
}, allowCredentials = "true")
public class UsuarioController {

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    public UsuarioController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record CadastroRequest(
            @NotBlank
            @Size(max = 120)
            String nome,

            @NotBlank
            @Email
            @Size(max = 150)
            String email,

            @NotBlank
            @Size(min = 8, max = 100)
            String senha,

            @Size(max = 25)
            String telefone
    ) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 150) String email,
            @NotBlank @Size(max = 100) String senha
    ) {}

    private record UsuarioAuth(
            Long idUsuario,
            String nome,
            String email,
            String telefone,
            String senha
    ) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest dados,
            HttpServletRequest request) {
        String email = dados.email().trim().toLowerCase(Locale.ROOT);
        List<UsuarioAuth> usuarios = jdbcTemplate.query("""
                SELECT ID_USUARIO, NOME, EMAIL, TELEFONE, SENHA
                FROM USUARIOS
                WHERE LOWER(EMAIL) = ?
                """,
                (rs, rowNum) -> new UsuarioAuth(
                        rs.getLong("ID_USUARIO"),
                        rs.getString("NOME"),
                        rs.getString("EMAIL"),
                        rs.getString("TELEFONE"),
                        rs.getString("SENHA")
                ),
                email
        );

        if (usuarios.isEmpty() || !passwordEncoder.matches(
                dados.senha(), usuarios.get(0).senha())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("mensagem", "E-mail ou senha inválidos."));
        }

        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) oldSession.invalidate();
        UsuarioAuth usuario = usuarios.get(0);
        HttpSession session = request.getSession(true);
        session.setAttribute("idUsuario", usuario.idUsuario());
        return ResponseEntity.ok(Map.of(
                "idUsuario", usuario.idUsuario(),
                "nome", usuario.nome(),
                "email", usuario.email(),
                "telefone", usuario.telefone() == null ? "" : usuario.telefone()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Object idUsuario = session.getAttribute("idUsuario");
        if (!(idUsuario instanceof Number id)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var usuarios = jdbcTemplate.query("""
                SELECT ID_USUARIO, NOME, EMAIL, TELEFONE
                FROM USUARIOS
                WHERE ID_USUARIO = ?
                """,
                (rs, rowNum) -> Map.of(
                        "idUsuario", rs.getLong("ID_USUARIO"),
                        "nome", rs.getString("NOME"),
                        "email", rs.getString("EMAIL"),
                        "telefone", rs.getString("TELEFONE") == null ? "" : rs.getString("TELEFONE")
                ),
                id.longValue()
        );
        return usuarios.isEmpty()
                ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
                : ResponseEntity.ok(usuarios.get(0));
    }

    @PostMapping("/cadastro")
    public ResponseEntity<?> cadastrar(
            @Valid @RequestBody CadastroRequest dados,
            HttpServletRequest request) {

        String nome = dados.nome().trim();
        String email = dados.email().trim().toLowerCase(Locale.ROOT);
        String telefone = dados.telefone() == null
                || dados.telefone().isBlank()
                ? null
                : dados.telefone().trim();

        Integer existente = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USUARIOS WHERE LOWER(EMAIL) = ?",
                Integer.class,
                email
        );

        if (existente != null && existente > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "mensagem",
                            "Este e-mail já está cadastrado."
                    ));
        }

        String senhaHash = passwordEncoder.encode(dados.senha());

        try {
            jdbcTemplate.update("""
                INSERT INTO USUARIOS (
                    NOME,
                    EMAIL,
                    SENHA,
                    TELEFONE,
                    DATA_CADASTRO
                )
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                    nome,
                    email,
                    senhaHash,
                    telefone
            );

            Long idUsuario = jdbcTemplate.queryForObject(
                    "SELECT ID_USUARIO FROM USUARIOS WHERE EMAIL = ?",
                    Long.class,
                    email
            );

            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) oldSession.invalidate();
            HttpSession session = request.getSession(true);
            session.setAttribute("idUsuario", idUsuario);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of(
                            "mensagem", "Cadastro realizado com sucesso.",
                            "idUsuario", idUsuario,
                            "nome", nome,
                            "email", email
                    ));

        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "mensagem",
                            "Este e-mail já está cadastrado."
                    ));
        }
    }
}
