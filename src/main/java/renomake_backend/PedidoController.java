package renomake_backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pedidos")
@CrossOrigin(origins = {
        "https://viniciosalvees.github.io",
        "http://localhost:5173"
})
public class PedidoController {

    private final JdbcTemplate jdbcTemplate;

    public PedidoController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record PedidoRequest(
            @NotNull @Positive Long idEndereco
    ) {}

    private record ItemCarrinho(
            Long idProduto,
            String nome,
            BigDecimal preco,
            int quantidade,
            int estoque,
            String ativo
    ) {}

    @PostMapping("/{idUsuario}")
    @Transactional
    public ResponseEntity<?> criarPedido(
            @PathVariable Long idUsuario,
            @Valid @RequestBody PedidoRequest dados) {

        Integer usuarioExiste = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM USUARIOS WHERE ID_USUARIO = ?",
                Integer.class,
                idUsuario
        );

        if (usuarioExiste == null || usuarioExiste == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "mensagem",
                            "Usuário não encontrado."
                    ));
        }

        Integer enderecoValido = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM ENDERECOS
            WHERE ID_ENDERECO = ?
              AND ID_USUARIO = ?
            """,
                Integer.class,
                dados.idEndereco(),
                idUsuario
        );

        if (enderecoValido == null || enderecoValido == 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "mensagem",
                            "Cadastre um endereço válido para este usuário."
                    ));
        }

        List<ItemCarrinho> itens = jdbcTemplate.query("""
            SELECT
                p.ID_PRODUTO,
                p.NOME,
                p.PRECO,
                c.QUANTIDADE,
                p.QUANTIDADE_ESTOQUE,
                p.ATIVO
            FROM CARRINHO c
            JOIN PRODUTOS p
                ON p.ID_PRODUTO = c.ID_PRODUTO
            WHERE c.ID_USUARIO = ?
            FOR UPDATE OF p.QUANTIDADE_ESTOQUE
            """,
                (rs, rowNum) -> new ItemCarrinho(
                        rs.getLong("ID_PRODUTO"),
                        rs.getString("NOME"),
                        rs.getBigDecimal("PRECO"),
                        rs.getInt("QUANTIDADE"),
                        rs.getInt("QUANTIDADE_ESTOQUE"),
                        rs.getString("ATIVO")
                ),
                idUsuario
        );

        if (itens.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "mensagem",
                            "O carrinho está vazio."
                    ));
        }

        BigDecimal valorTotal = BigDecimal.ZERO;

        for (ItemCarrinho item : itens) {
            if (item.ativo() == null
                    || !"S".equalsIgnoreCase(item.ativo().trim())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                                "mensagem",
                                "O produto " + item.nome()
                                        + " está indisponível."
                        ));
            }

            if (item.estoque() < item.quantidade()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                                "mensagem",
                                "Estoque insuficiente para: " + item.nome()
                        ));
            }

            valorTotal = valorTotal.add(
                    item.preco().multiply(
                            BigDecimal.valueOf(item.quantidade())
                    )
            );
        }

        // Valor final utilizado nas operações seguintes.
        final BigDecimal totalPedido = valorTotal;

        KeyHolder chavePedido = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO PEDIDOS (
                    ID_USUARIO,
                    ID_ENDERECO,
                    DATA_PEDIDO,
                    STATUS,
                    VALOR_TOTAL
                )
                VALUES (
                    ?, ?, CURRENT_TIMESTAMP,
                    'AGUARDANDO_PAGAMENTO', ?
                )
                """, new String[]{"ID_PEDIDO"});

            ps.setLong(1, idUsuario);
            ps.setLong(2, dados.idEndereco());
            ps.setBigDecimal(3, totalPedido);

            return ps;
        }, chavePedido);

        Number numeroPedido = chavePedido.getKey();

        if (numeroPedido == null) {
            throw new IllegalStateException(
                    "Não foi possível obter o ID do pedido."
            );
        }

        long idPedido = numeroPedido.longValue();

        for (ItemCarrinho item : itens) {
            jdbcTemplate.update("""
                INSERT INTO ITENS_PEDIDO (
                    ID_PEDIDO,
                    ID_PRODUTO,
                    QUANTIDADE,
                    PRECO_UNITARIO
                )
                VALUES (?, ?, ?, ?)
                """,
                    idPedido,
                    item.idProduto(),
                    item.quantidade(),
                    item.preco()
            );

            int estoqueAtualizado = jdbcTemplate.update("""
                UPDATE PRODUTOS
                SET QUANTIDADE_ESTOQUE =
                    QUANTIDADE_ESTOQUE - ?
                WHERE ID_PRODUTO = ?
                  AND QUANTIDADE_ESTOQUE >= ?
                  AND ATIVO = 'S'
                """,
                    item.quantidade(),
                    item.idProduto(),
                    item.quantidade()
            );

            if (estoqueAtualizado != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Não foi possível reservar o estoque de "
                                + item.nome() + "."
                );
            }
        }

        jdbcTemplate.update("""
            INSERT INTO PAGAMENTOS (
                ID_PEDIDO,
                METODO,
                STATUS,
                VALOR
            )
            VALUES (?, 'PENDENTE', 'PENDENTE', ?)
            """,
                idPedido,
                totalPedido
        );

        jdbcTemplate.update(
                "DELETE FROM CARRINHO WHERE ID_USUARIO = ?",
                idUsuario
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "mensagem",
                        "Pedido criado e aguardando pagamento.",
                        "idPedido",
                        idPedido,
                        "valorTotal",
                        totalPedido,
                        "status",
                        "AGUARDANDO_PAGAMENTO"
                ));
    }
}