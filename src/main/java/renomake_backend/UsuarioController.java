
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.Locale;

@RestController
@RequestMapping("/api/usuarios")
@CrossOrigin(origins = {
        "https://viniciosalvees.github.io",
        "http://localhost:5173"
})
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

    @PostMapping("/cadastro")
    public ResponseEntity<?> cadastrar(
            @Valid @RequestBody CadastroRequest dados) {

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
