
package renomake_backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/carrinho")
@CrossOrigin(origins = {
        "https://viniciosalvees.github.io",
        "http://localhost:5173"
})
public class CarrinhoController {

    private final JdbcTemplate jdbcTemplate;

    public CarrinhoController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ItemRequest(
            @NotNull Long idProduto,
            @NotNull @Min(1) Integer quantidade
    ) {}

    public record QuantidadeRequest(
            @NotNull @Min(1) Integer quantidade
    ) {}

    @GetMapping("/{idUsuario}")
    public ResponseEntity<?> listarCarrinho(
            @PathVariable Long idUsuario) {

        if (!usuarioExiste(idUsuario)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("mensagem", "Usuário não encontrado."));
        }

        String sql = """
            SELECT
                c.ID_CARRINHO AS "idCarrinho",
                p.ID_PRODUTO AS "idProduto",
                p.NOME AS "nome",
                p.PRECO AS "preco",
                p.MARCA AS "marca",
                c.QUANTIDADE AS "quantidade",
                p.PRECO * c.QUANTIDADE AS "subtotal",
                p.IMAGEM AS "imagem",
                p.QUANTIDADE_ESTOQUE AS "estoque"
            FROM CARRINHO c
            JOIN PRODUTOS p
                ON p.ID_PRODUTO = c.ID_PRODUTO
            WHERE c.ID_USUARIO = ?
            ORDER BY c.DATA_ADICAO DESC
            """;

        List<Map<String, Object>> itens =
                jdbcTemplate.queryForList(sql, idUsuario);

        return ResponseEntity.ok(itens);
    }

    @PostMapping("/{idUsuario}/itens")
    public ResponseEntity<?> adicionarProduto(
            @PathVariable Long idUsuario,
            @Valid @RequestBody ItemRequest dados) {

        if (!usuarioExiste(idUsuario)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("mensagem", "Usuário não encontrado."));
        }

        Integer produtoExiste = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM PRODUTOS
            WHERE ID_PRODUTO = ?
              AND ATIVO = 'S'
            """, Integer.class, dados.idProduto());

        if (produtoExiste == null || produtoExiste == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "mensagem",
                            "Produto não encontrado ou indisponível."
                    ));
        }

        jdbcTemplate.update("""
            MERGE INTO CARRINHO c
            USING (
                SELECT ? AS ID_USUARIO,
                       ? AS ID_PRODUTO,
                       ? AS QUANTIDADE
                FROM DUAL
            ) src
            ON (
                c.ID_USUARIO = src.ID_USUARIO
                AND c.ID_PRODUTO = src.ID_PRODUTO
            )
            WHEN MATCHED THEN
                UPDATE SET
                    c.QUANTIDADE = c.QUANTIDADE + src.QUANTIDADE
            WHEN NOT MATCHED THEN
                INSERT (
                    ID_USUARIO,
                    ID_PRODUTO,
                    QUANTIDADE,
                    DATA_ADICAO
                )
                VALUES (
                    src.ID_USUARIO,
                    src.ID_PRODUTO,
                    src.QUANTIDADE,
                    CURRENT_TIMESTAMP
                )
            """,
                idUsuario,
                dados.idProduto(),
                dados.quantidade()
        );

        return ResponseEntity.ok(Map.of(
                "mensagem", "Produto adicionado ao carrinho.",
                "idUsuario", idUsuario,
                "idProduto", dados.idProduto(),
                "quantidadeAdicionada", dados.quantidade()
        ));
    }

    @PutMapping("/{idUsuario}/itens/{idProduto}")
    public ResponseEntity<?> atualizarQuantidade(
            @PathVariable Long idUsuario,
            @PathVariable Long idProduto,
            @Valid @RequestBody QuantidadeRequest dados) {

        if (!usuarioExiste(idUsuario)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("mensagem", "Usuário não encontrado."));
        }

        int linhas = jdbcTemplate.update("""
            UPDATE CARRINHO
            SET QUANTIDADE = ?
            WHERE ID_USUARIO = ?
              AND ID_PRODUTO = ?
            """,
                dados.quantidade(),
                idUsuario,
                idProduto
        );

        if (linhas == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "mensagem",
                            "Produto não encontrado no carrinho."
                    ));
        }

        return ResponseEntity.ok(Map.of(
                "mensagem", "Quantidade atualizada.",
                "quantidade", dados.quantidade()
        ));
    }

    @DeleteMapping("/{idUsuario}/itens/{idProduto}")
    public ResponseEntity<?> removerProduto(
            @PathVariable Long idUsuario,
            @PathVariable Long idProduto) {

        int linhas = jdbcTemplate.update("""
            DELETE FROM CARRINHO
            WHERE ID_USUARIO = ?
              AND ID_PRODUTO = ?
            """,
                idUsuario,
                idProduto
        );

        if (linhas == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "mensagem",
                            "Produto não encontrado no carrinho."
                    ));
        }

        return ResponseEntity.ok(Map.of(
                "mensagem", "Produto removido do carrinho."
        ));
    }

    private boolean usuarioExiste(Long idUsuario) {
        Integer quantidade = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM USUARIOS
            WHERE ID_USUARIO = ?
            """,
                Integer.class,
                idUsuario
        );

        return quantidade != null && quantidade > 0;
    }
}
