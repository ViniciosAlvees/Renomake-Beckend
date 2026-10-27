package renomake_backend;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/enderecos")
@CrossOrigin(origins = {
        "https://viniciosalvees.github.io",
        "http://localhost:5173"
}, allowCredentials = "true")
public class EnderecoController {

    private final JdbcTemplate jdbcTemplate;

    public EnderecoController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record EnderecoRequest(
            @NotBlank @Size(max = 9) String cep,
            @NotBlank @Size(max = 2) String estado,
            @NotBlank @Size(max = 100) String cidade,
            @NotBlank @Size(max = 100) String bairro,
            @NotBlank @Size(max = 150) String rua,
            @NotBlank @Size(max = 20) String numero,
            @Size(max = 100) String complemento
    ) {}

    @GetMapping
    public ResponseEntity<?> listar(HttpSession session) {
        Long idUsuario = usuarioAutenticado(session);
        if (idUsuario == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("mensagem", "Faça login para continuar."));
        }

        List<Map<String, Object>> enderecos = jdbcTemplate.queryForList("""
                SELECT ID_ENDERECO AS "idEndereco",
                       CEP AS "cep",
                       ESTADO AS "estado",
                       CIDADE AS "cidade",
                       BAIRRO AS "bairro",
                       RUA AS "rua",
                       NUMERO AS "numero",
                       COMPLEMENTO AS "complemento"
                FROM ENDERECOS
                WHERE ID_USUARIO = ?
                ORDER BY ID_ENDERECO DESC
                """, idUsuario);
        return ResponseEntity.ok(enderecos);
    }

    @PostMapping
    public ResponseEntity<?> criar(
            @Valid @RequestBody EnderecoRequest dados,
            HttpSession session) {
        Long idUsuario = usuarioAutenticado(session);
        if (idUsuario == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("mensagem", "Faça login para continuar."));
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ENDERECOS (
                        ID_USUARIO, CEP, ESTADO, CIDADE, BAIRRO,
                        RUA, NUMERO, COMPLEMENTO
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"ID_ENDERECO"});
            statement.setLong(1, idUsuario);
            statement.setString(2, dados.cep().trim());
            statement.setString(3, dados.estado().trim().toUpperCase());
            statement.setString(4, dados.cidade().trim());
            statement.setString(5, dados.bairro().trim());
            statement.setString(6, dados.rua().trim());
            statement.setString(7, dados.numero().trim());
            statement.setString(8, dados.complemento() == null
                    || dados.complemento().isBlank()
                    ? null
                    : dados.complemento().trim());
            return statement;
        }, keyHolder);

        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("Não foi possível obter o ID do endereço.");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "idEndereco", generatedId.longValue(),
                "mensagem", "Endereço cadastrado."
        ));
    }

    private Long usuarioAutenticado(HttpSession session) {
        Object idUsuario = session.getAttribute("idUsuario");
        return idUsuario instanceof Number id ? id.longValue() : null;
    }
}
