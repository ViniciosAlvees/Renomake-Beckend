package renomake_backend;

import tools.jackson.databind.JsonNode;
import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.webhook.WebhookSignatureValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks/mercadopago")
public class MercadoPagoWebhookController {

    private final JdbcTemplate jdbcTemplate;
    private final RestClient mercadoPagoClient;
    private final String webhookSecret;
    private final NotificacaoVendedorEmailService notificacaoEmailService;

    public MercadoPagoWebhookController(
            JdbcTemplate jdbcTemplate,
            NotificacaoVendedorEmailService notificacaoEmailService,
            @Value("${MP_ACCESS_TOKEN:}") String accessToken,
            @Value("${MP_WEBHOOK_SECRET:}") String webhookSecret) {

        this.jdbcTemplate = jdbcTemplate;
        this.notificacaoEmailService = notificacaoEmailService;
        this.webhookSecret = webhookSecret;

        this.mercadoPagoClient = RestClient.builder()
                .baseUrl("https://api.mercadopago.com")
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + accessToken
                )
                .build();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Void> receberNotificacao(
            @RequestParam(name = "data.id", required = false)
            String dataId,
            @RequestParam(name = "type", required = false)
            String type,
            @RequestHeader(name = "x-signature", required = false)
            String assinatura,
            @RequestHeader(name = "x-request-id", required = false)
            String requestId) {

        if (dataId == null || dataId.isBlank()
                || type == null || type.isBlank()
                || assinatura == null || requestId == null) {
            return ResponseEntity.badRequest().build();
        }

        if (!"order".equalsIgnoreCase(type)) {
            return ResponseEntity.ok().build();
        }

        if (webhookSecret == null || webhookSecret.isBlank()) {
            return ResponseEntity.status(
                    HttpStatus.SERVICE_UNAVAILABLE
            ).build();
        }

        try {
            WebhookSignatureValidator.validate(
                    assinatura,
                    requestId,
                    dataId,
                    webhookSecret
            );
        } catch (MPInvalidWebhookSignatureException e) {
            return ResponseEntity.status(
                    HttpStatus.UNAUTHORIZED
            ).build();
        }

        if (!dataId.matches("[A-Za-z0-9_-]+")) {
            return ResponseEntity.badRequest().build();
        }

        try {
            JsonNode order = mercadoPagoClient.get()
                    .uri("/v1/orders/{id}", dataId)
                    .retrieve()
                    .body(JsonNode.class);

            if (order == null) {
                return ResponseEntity.status(
                        HttpStatus.BAD_GATEWAY
                ).build();
            }

            String status = order.path("status").asText();
            String statusDetail = order.path("status_detail").asText();

            if (!"processed".equalsIgnoreCase(status)
                    || !"accredited".equalsIgnoreCase(statusDetail)) {
                return ResponseEntity.ok().build();
            }

            String referencia =
                    order.path("external_reference").asText();

            String valorPagoTexto =
                    order.path("total_paid_amount").asText();

            if (referencia.isBlank() || valorPagoTexto.isBlank()) {
                return ResponseEntity.unprocessableEntity().build();
            }

            BigDecimal valorPago = new BigDecimal(valorPagoTexto);

            List<Map<String, Object>> pagamentos =
                    jdbcTemplate.queryForList(
                            """
                            SELECT
                                p.ID_PEDIDO AS ID_PEDIDO,
                                p.VALOR_TOTAL AS VALOR_TOTAL,
                                pg.VALOR AS VALOR_PAGAMENTO
                            FROM PEDIDOS p
                            JOIN PAGAMENTOS pg
                                ON pg.ID_PEDIDO = p.ID_PEDIDO
                            WHERE pg.CODIGO_TRANSACAO = ?
                            """,
                            dataId
                    );

            if (pagamentos.size() != 1) {
                return ResponseEntity.unprocessableEntity().build();
            }

            Map<String, Object> pagamento = pagamentos.get(0);

            long idPedido = ((Number) pagamento.get("ID_PEDIDO"))
                    .longValue();

            BigDecimal valorPedido =
                    (BigDecimal) pagamento.get("VALOR_TOTAL");

            BigDecimal valorRegistrado =
                    (BigDecimal) pagamento.get("VALOR_PAGAMENTO");

            if (!referencia.equals("RENOMAKE-" + idPedido)
                    || valorPago.compareTo(valorPedido) != 0
                    || valorPago.compareTo(valorRegistrado) != 0) {
                return ResponseEntity.unprocessableEntity().build();
            }

            int pagamentosAtualizados = jdbcTemplate.update(
                    """
                    UPDATE PAGAMENTOS
                    SET STATUS = 'APROVADO',
                        DATA_PAGAMENTO = NVL(DATA_PAGAMENTO, SYSDATE)
                    WHERE ID_PEDIDO = ?
                      AND CODIGO_TRANSACAO = ?
                      AND VALOR = ?
                    """,
                    idPedido,
                    dataId,
                    valorPedido
            );

            int pedidosAtualizados = jdbcTemplate.update(
                    """
                    UPDATE PEDIDOS
                    SET STATUS = 'PAGO'
                    WHERE ID_PEDIDO = ?
                      AND VALOR_TOTAL = ?
                    """,
                    idPedido,
                    valorPedido
            );

            if (pagamentosAtualizados != 1
                    || pedidosAtualizados != 1) {
                throw new IllegalStateException(
                        "Não foi possível atualizar o pedido e o pagamento."
                );
            }

            jdbcTemplate.update(
                    """
                    INSERT INTO NOTIFICACOES_VENDEDOR (
                        ID_PEDIDO,
                        TIPO,
                        DESTINO,
                        ASSUNTO,
                        MENSAGEM,
                        STATUS_ENVIO,
                        TENTATIVAS,
                        DATA_CRIACAO
                    )
                    SELECT
                        ?,
                        'PAGAMENTO_APROVADO',
                        NULL,
                        ?,
                        ?,
                        'PENDENTE',
                        0,
                        SYSTIMESTAMP
                    FROM DUAL
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM NOTIFICACOES_VENDEDOR
                        WHERE ID_PEDIDO = ?
                          AND TIPO = 'PAGAMENTO_APROVADO'
                    )
                    """,
                    idPedido,
                    "Pagamento aprovado - pedido #" + idPedido,
                    "O pagamento do pedido #" + idPedido
                            + " foi aprovado no Mercado Pago. Valor: R$ "
                            + valorPago.toPlainString() + ".",
                    idPedido
            );

            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            notificacaoEmailService
                                    .enviarNotificacaoPendente(idPedido);
                        }
                    }
            );

            return ResponseEntity.ok().build();

        } catch (RestClientException e) {
            return ResponseEntity.status(
                    HttpStatus.BAD_GATEWAY
            ).build();
        }
    }
}