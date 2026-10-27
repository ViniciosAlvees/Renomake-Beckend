
package renomake_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/checkout")
@CrossOrigin(origins = {
        "https://viniciosalvees.github.io",
        "http://localhost:5173"
})
public class CheckoutController {

    private static final String API_MERCADO_PAGO =
            "https://api.mercadopago.com/v1/orders";

    private static final String SITE_RENOMAKE =
            "https://viniciosalvees.github.io/Renomake/";

    private final JdbcTemplate jdbcTemplate;
    private final RestClient restClient;

    public CheckoutController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.restClient = RestClient.create();
    }

    private record Pedido(
            long idPedido,
            String status,
            BigDecimal valorTotal,
            String email
    ) {}

    private record ItemPedido(
            String nome,
            int quantidade,
            BigDecimal preco
    ) {}

    @PostMapping("/{idPedido}")
    public ResponseEntity<?> criarCheckout(
            @PathVariable Long idPedido) {

        String accessToken = System.getenv("MP_ACCESS_TOKEN");

        if (accessToken == null || accessToken.isBlank()) {
            return ResponseEntity.status(
                            HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "mensagem",
                            "Token do Mercado Pago não configurado no backend."
                    ));
        }

        List<Pedido> pedidos = jdbcTemplate.query("""
            SELECT
                p.ID_PEDIDO,
                p.STATUS,
                p.VALOR_TOTAL,
                u.EMAIL
            FROM PEDIDOS p
            JOIN USUARIOS u
                ON u.ID_USUARIO = p.ID_USUARIO
            WHERE p.ID_PEDIDO = ?
            """,
                (rs, rowNum) -> new Pedido(
                        rs.getLong("ID_PEDIDO"),
                        rs.getString("STATUS"),
                        rs.getBigDecimal("VALOR_TOTAL"),
                        rs.getString("EMAIL")
                ),
                idPedido
        );

        if (pedidos.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "mensagem", "Pedido não encontrado."
                    ));
        }

        Pedido pedido = pedidos.get(0);

        if (!"AGUARDANDO_PAGAMENTO".equalsIgnoreCase(
                pedido.status().trim())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "mensagem",
                            "O pedido não está aguardando pagamento."
                    ));
        }

        List<Map<String, Object>> pagamentos =
                jdbcTemplate.queryForList("""
                SELECT STATUS, CODIGO_TRANSACAO
                FROM PAGAMENTOS
                WHERE ID_PEDIDO = ?
                """,
                        idPedido
                );

        if (pagamentos.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "mensagem",
                            "O pedido não possui registro de pagamento."
                    ));
        }

        Map<String, Object> pagamento = pagamentos.get(0);
        Object statusPagamento = pagamento.get("STATUS");
        Object codigoExistente = pagamento.get("CODIGO_TRANSACAO");

        if (statusPagamento == null
                || !"PENDENTE".equalsIgnoreCase(
                statusPagamento.toString().trim())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "mensagem",
                            "O pagamento deste pedido não está pendente."
                    ));
        }

        try {
            // Se o checkout já foi criado, reutiliza a order existente.
            if (codigoExistente != null
                    && !codigoExistente.toString().isBlank()) {

                Map<String, Object> orderExistente = restClient.get()
                        .uri(API_MERCADO_PAGO + "/{id}",
                                codigoExistente.toString())
                        .header(
                                "Authorization",
                                "Bearer " + accessToken
                        )
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(new ParameterizedTypeReference<
                                Map<String, Object>>() {});

                if (orderExistente != null
                        && orderExistente.get("checkout_url") != null) {
                    return ResponseEntity.ok(Map.of(
                            "idPedido", idPedido,
                            "mercadoPagoOrderId",
                            codigoExistente.toString(),
                            "checkoutUrl",
                            orderExistente.get("checkout_url"),
                            "status", "AGUARDANDO_PAGAMENTO"
                    ));
                }

                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                                "mensagem",
                                "Já existe uma order vinculada. "
                                        + "Não foi possível recuperar o link."
                        ));
            }

            List<ItemPedido> itens = jdbcTemplate.query("""
                SELECT
                    pr.NOME,
                    i.QUANTIDADE,
                    i.PRECO_UNITARIO
                FROM ITENS_PEDIDO i
                JOIN PRODUTOS pr
                    ON pr.ID_PRODUTO = i.ID_PRODUTO
                WHERE i.ID_PEDIDO = ?
                """,
                    (rs, rowNum) -> new ItemPedido(
                            rs.getString("NOME"),
                            rs.getInt("QUANTIDADE"),
                            rs.getBigDecimal("PRECO_UNITARIO")
                    ),
                    idPedido
            );

            if (itens.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "mensagem",
                                "O pedido não possui itens."
                        ));
            }

            BigDecimal somaItens = BigDecimal.ZERO;
            List<Map<String, Object>> itensMercadoPago =
                    new ArrayList<>();

            for (ItemPedido item : itens) {
                BigDecimal preco = item.preco()
                        .setScale(2, RoundingMode.HALF_UP);

                somaItens = somaItens.add(
                        preco.multiply(
                                BigDecimal.valueOf(item.quantidade())
                        )
                );

                Map<String, Object> itemMp = new HashMap<>();
                itemMp.put("title", item.nome());
                itemMp.put("quantity", item.quantidade());
                itemMp.put("unit_price", preco.toPlainString());

                itensMercadoPago.add(itemMp);
            }

            BigDecimal total = pedido.valorTotal()
                    .setScale(2, RoundingMode.HALF_UP);

            if (somaItens.compareTo(total) != 0) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                                "mensagem",
                                "O total do pedido não corresponde à soma dos itens."
                        ));
            }

            Map<String, Object> pagador = Map.of(
                    "email", pedido.email()
            );

            Map<String, Object> configOnline = Map.of(
                    "success_url",
                    SITE_RENOMAKE + "?checkout=success&pedido=" + idPedido,
                    "failure_url",
                    SITE_RENOMAKE + "?checkout=failure&pedido=" + idPedido,
                    "pending_url",
                    SITE_RENOMAKE + "?checkout=pending&pedido=" + idPedido,
                    "auto_return", "approved"
            );

            Map<String, Object> configuracao = Map.of(
                    "online", configOnline
            );

            Map<String, Object> requisicao = new HashMap<>();
            requisicao.put("type", "online");
            requisicao.put("processing_mode", "manual");
            requisicao.put("capture_mode", "automatic_async");
            requisicao.put("total_amount", total.toPlainString());
            requisicao.put(
                    "external_reference",
                    "RENOMAKE-" + idPedido
            );
            requisicao.put(
                    "description",
                    "Pedido Renomake #" + idPedido
            );
            requisicao.put("payer", pagador);
            requisicao.put("items", itensMercadoPago);
            requisicao.put("config", configuracao);

            String chaveIdempotencia = UUID.nameUUIDFromBytes(
                    ("RENOMAKE-PEDIDO-" + idPedido)
                            .getBytes(StandardCharsets.UTF_8)
            ).toString();

            Map<String, Object> resposta = restClient.post()
                    .uri(API_MERCADO_PAGO)
                    .header(
                            "Authorization",
                            "Bearer " + accessToken
                    )
                    .header(
                            "X-Idempotency-Key",
                            chaveIdempotencia
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requisicao)
                    .retrieve()
                    .body(new ParameterizedTypeReference<
                            Map<String, Object>>() {});

            if (resposta == null
                    || resposta.get("id") == null
                    || resposta.get("checkout_url") == null) {
                return ResponseEntity.status(
                                HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "mensagem",
                                "O Mercado Pago não retornou a order "
                                        + "e o link de checkout esperados."
                        ));
            }

            String idMercadoPago =
                    resposta.get("id").toString();

            String checkoutUrl =
                    resposta.get("checkout_url").toString();

            if (!checkoutUrl.startsWith(
                    "https://www.mercadopago.com.br/")) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "mensagem",
                                "O Mercado Pago retornou um endereço de checkout "
                                        + "inesperado."
                        ));
            }

            int atualizados = jdbcTemplate.update("""
                UPDATE PAGAMENTOS
                SET METODO = 'CHECKOUT_PRO',
                    CODIGO_TRANSACAO = ?
                WHERE ID_PEDIDO = ?
                  AND STATUS = 'PENDENTE'
                  AND CODIGO_TRANSACAO IS NULL
                """,
                    idMercadoPago,
                    idPedido
            );

            if (atualizados != 1) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                                "mensagem",
                                "O registro do pagamento mudou durante "
                                        + "a criação do checkout. Confira o pedido."
                        ));
            }

            return ResponseEntity.ok(Map.of(
                    "mensagem", "Checkout criado. O pagamento continua pendente.",
                    "idPedido", idPedido,
                    "mercadoPagoOrderId", idMercadoPago,
                    "checkoutUrl", checkoutUrl,
                    "valorTotal", total,
                    "status", "AGUARDANDO_PAGAMENTO"
            ));

        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "mensagem",
                            "O Mercado Pago recusou a solicitação.",
                            "statusHttp",
                            e.getStatusCode().value(),
                            "detalhes",
                            e.getResponseBodyAsString().isBlank()
                                    ? "Sem detalhes adicionais."
                                    : e.getResponseBodyAsString()
                    ));

        } catch (RestClientException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "mensagem",
                            "Não foi possível conectar ao Mercado Pago. "
                                    + "Confira a conexão e tente novamente."
                    ));
        }
    }
}
