package renomake_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/produtos")
@CrossOrigin(origins = {
        "https://viniciosalvees.github.io",
        "http://localhost:5173"
})
public class ProdutoController {

    private final JdbcTemplate jdbcTemplate;

    public ProdutoController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<Map<String, Object>> listarProdutos() {
        String sql = """
            SELECT
                p.ID_PRODUTO AS "id",
                p.CODIGO_CATALOGO AS "codigoCatalogo",
                p.NOME AS "nome",
                p.PRECO AS "preco",
                p.MARCA AS "marca",
                p.QUANTIDADE_ESTOQUE AS "quantidadeEstoque",
                p.IMAGEM AS "imagem",
                p.ATIVO AS "ativo",
                c.NOME AS "categoria"
            FROM PRODUTOS p
            LEFT JOIN CATEGORIAS c
                ON c.ID_CATEGORIA = p.ID_CATEGORIA
            WHERE p.ATIVO = 'S'
            ORDER BY p.ID_PRODUTO
            """;

        return jdbcTemplate.queryForList(sql);
    }
}