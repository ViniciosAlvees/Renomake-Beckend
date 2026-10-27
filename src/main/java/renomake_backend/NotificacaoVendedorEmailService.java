package renomake_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NotificacaoVendedorEmailService {

    private static final Logger logger =
            LoggerFactory.getLogger(NotificacaoVendedorEmailService.class);

    private static final Locale LOCALE_BR = Locale.forLanguageTag("pt-BR");

    private static final DateTimeFormatter FORMATO_DATA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String emailVendedor;
    private final String emailRemetente;

    public NotificacaoVendedorEmailService(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${RENOMAKE_VENDEDOR_EMAIL:}") String emailVendedor,
            @Value("${MAIL_FROM:}") String emailRemetente) {

        this.jdbcTemplate = jdbcTemplate;
        this.mailSenderProvider = mailSenderProvider;
        this.emailVendedor = emailVendedor;
        this.emailRemetente = emailRemetente;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enviarNotificacaoPendente(long idPedido) {

        logger.info(
                "Iniciando envio da notificação do pedido {}.",
                idPedido
        );

        List<Map<String, Object>> notificacoes =
                jdbcTemplate.queryForList(
                        """
                        SELECT
                            ID_NOTIFICACAO,
                            ASSUNTO,
                            STATUS_ENVIO
                        FROM NOTIFICACOES_VENDEDOR
                        WHERE ID_PEDIDO = ?
                          AND TIPO = 'PAGAMENTO_APROVADO'
                        ORDER BY ID_NOTIFICACAO DESC
                        """,
                        idPedido
                );

        if (notificacoes.isEmpty()) {
            logger.warn(
                    "Nenhuma notificação encontrada para o pedido {}.",
                    idPedido
            );
            return;
        }

        Map<String, Object> notificacao = notificacoes.get(0);

        long idNotificacao =
                ((Number) notificacao.get("ID_NOTIFICACAO")).longValue();

        String status = valor(notificacao, "STATUS_ENVIO");

        if ("ENVIADA".equalsIgnoreCase(status)) {
            logger.info(
                    "A notificação {} já foi enviada.",
                    idNotificacao
            );
            return;
        }

        if (!"PENDENTE".equalsIgnoreCase(status)
                && !"ERRO".equalsIgnoreCase(status)) {
            logger.warn(
                    "Status {} não permite envio da notificação {}.",
                    status,
                    idNotificacao
            );
            return;
        }

        if (emailVendedor == null || emailVendedor.isBlank()
                || emailRemetente == null || emailRemetente.isBlank()) {

            registrarFalha(
                    idNotificacao,
                    "E-mail do remetente ou destinatário não configurado."
            );
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null) {
            registrarFalha(
                    idNotificacao,
                    "Serviço de e-mail indisponível."
            );
            return;
        }

        try {
            Map<String, Object> pedido = buscarPedido(idPedido);

            if (pedido == null) {
                registrarFalha(
                        idNotificacao,
                        "Pedido não encontrado no banco de dados."
                );
                return;
            }

            List<Map<String, Object>> itens = buscarItens(idPedido);
            List<Map<String, Object>> pagamentos =
                    buscarPagamento(idPedido);

            String assunto = valor(notificacao, "ASSUNTO");

            if ("Não informado".equals(assunto)) {
                assunto = "Pedido #" + idPedido
                        + " - Pagamento aprovado | Renomake";
            }

            SimpleMailMessage mensagem = new SimpleMailMessage();

            mensagem.setFrom(emailRemetente);
            mensagem.setTo(emailVendedor);
            mensagem.setSubject(assunto);
            mensagem.setText(
                    montarMensagem(pedido, itens, pagamentos)
            );

            mailSender.send(mensagem);

            int atualizadas = jdbcTemplate.update(
                    """
                    UPDATE NOTIFICACOES_VENDEDOR
                    SET DESTINO = ?,
                        STATUS_ENVIO = 'ENVIADA',
                        TENTATIVAS = TENTATIVAS + 1,
                        DATA_ENVIO = SYSTIMESTAMP
                    WHERE ID_NOTIFICACAO = ?
                      AND STATUS_ENVIO IN ('PENDENTE', 'ERRO')
                    """,
                    emailVendedor,
                    idNotificacao
            );

            if (atualizadas == 1) {
                logger.info(
                        "Notificação do pedido {} aceita pelo servidor SMTP.",
                        idPedido
                );
            } else {
                logger.warn(
                        "O SMTP aceitou a mensagem, mas o status da notificação {} não foi atualizado.",
                        idNotificacao
                );
            }

        } catch (MailException e) {
            registrarFalha(
                    idNotificacao,
                    "Falha no envio SMTP: "
                            + e.getClass().getSimpleName()
            );

            logger.error(
                    "Falha ao enviar e-mail da notificação do pedido {}.",
                    idPedido,
                    e
            );

        } catch (RuntimeException e) {
            registrarFalha(
                    idNotificacao,
                    "Falha ao montar os dados do pedido: "
                            + e.getClass().getSimpleName()
            );

            logger.error(
                    "Falha ao preparar a notificação do pedido {}.",
                    idPedido,
                    e
            );
        }
    }

    private Map<String, Object> buscarPedido(long idPedido) {

        List<Map<String, Object>> resultados =
                jdbcTemplate.queryForList(
                        """
                        SELECT
                            P.ID_PEDIDO,
                            P.DATA_PEDIDO,
                            P.STATUS AS STATUS_PEDIDO,
                            P.VALOR_TOTAL,
                            U.NOME AS NOME_CLIENTE,
                            U.EMAIL AS EMAIL_CLIENTE,
                            U.TELEFONE AS TELEFONE_CLIENTE,
                            E.CEP,
                            E.ESTADO,
                            E.CIDADE,
                            E.BAIRRO,
                            E.RUA,
                            E.NUMERO,
                            E.COMPLEMENTO
                        FROM PEDIDOS P
                        JOIN USUARIOS U
                          ON U.ID_USUARIO = P.ID_USUARIO
                        LEFT JOIN ENDERECOS E
                          ON E.ID_ENDERECO = P.ID_ENDERECO
                         AND E.ID_USUARIO = P.ID_USUARIO
                        WHERE P.ID_PEDIDO = ?
                        """,
                        idPedido
                );

        return resultados.isEmpty() ? null : resultados.get(0);
    }

    private List<Map<String, Object>> buscarItens(long idPedido) {

        return jdbcTemplate.queryForList(
                """
                SELECT
                    PR.NOME AS NOME_PRODUTO,
                    I.QUANTIDADE,
                    I.PRECO_UNITARIO
                FROM ITENS_PEDIDO I
                JOIN PRODUTOS PR
                  ON PR.ID_PRODUTO = I.ID_PRODUTO
                WHERE I.ID_PEDIDO = ?
                ORDER BY I.ID_ITEM
                """,
                idPedido
        );
    }

    private List<Map<String, Object>> buscarPagamento(long idPedido) {

        return jdbcTemplate.queryForList(
                """
                SELECT
                    METODO,
                    STATUS AS STATUS_PAGAMENTO,
                    VALOR AS VALOR_PAGAMENTO,
                    CODIGO_TRANSACAO,
                    DATA_PAGAMENTO
                FROM PAGAMENTOS
                WHERE ID_PEDIDO = ?
                ORDER BY DATA_PAGAMENTO DESC NULLS LAST,
                         ID_PAGAMENTO DESC
                FETCH FIRST 1 ROW ONLY
                """,
                idPedido
        );
    }

    private String montarMensagem(
            Map<String, Object> pedido,
            List<Map<String, Object>> itens,
            List<Map<String, Object>> pagamentos) {

        StringBuilder mensagem = new StringBuilder();

        mensagem.append("RENOMAKE - CONFIRMAÇÃO DE PEDIDO\n");
        mensagem.append("========================================\n\n");

        mensagem.append("Olá! Um pedido teve seu pagamento aprovado.\n\n");

        mensagem.append("DADOS DO PEDIDO\n");
        mensagem.append("----------------------------------------\n");
        mensagem.append("Número: #")
                .append(valor(pedido, "ID_PEDIDO"))
                .append("\n");
        mensagem.append("Data: ")
                .append(formatarData(pedido.get("DATA_PEDIDO")))
                .append("\n");
        mensagem.append("Status do pedido: ")
                .append(valor(pedido, "STATUS_PEDIDO"))
                .append("\n");
        mensagem.append("Valor total: ")
                .append(formatarMoeda(pedido.get("VALOR_TOTAL")))
                .append("\n\n");

        mensagem.append("DADOS DO CLIENTE\n");
        mensagem.append("----------------------------------------\n");
        mensagem.append("Nome: ")
                .append(valor(pedido, "NOME_CLIENTE"))
                .append("\n");
        mensagem.append("E-mail: ")
                .append(valor(pedido, "EMAIL_CLIENTE"))
                .append("\n");
        mensagem.append("Telefone: ")
                .append(valor(pedido, "TELEFONE_CLIENTE"))
                .append("\n\n");

        mensagem.append("ENDEREÇO DE ENTREGA\n");
        mensagem.append("----------------------------------------\n");
        mensagem.append(montarEndereco(pedido))
                .append("\n\n");

        mensagem.append("PRODUTOS DO PEDIDO\n");
        mensagem.append("----------------------------------------\n");

        BigDecimal subtotalItens = BigDecimal.ZERO;

        if (itens.isEmpty()) {
            mensagem.append("Nenhum item encontrado para este pedido.\n");
        } else {
            for (Map<String, Object> item : itens) {

                String nomeProduto = valor(item, "NOME_PRODUTO");
                BigDecimal quantidade = decimal(item.get("QUANTIDADE"));
                BigDecimal precoUnitario =
                        decimal(item.get("PRECO_UNITARIO"));

                BigDecimal subtotal =
                        precoUnitario.multiply(quantidade);

                subtotalItens = subtotalItens.add(subtotal);

                mensagem.append("Produto: ")
                        .append(nomeProduto)
                        .append("\n");

                mensagem.append("Quantidade: ")
                        .append(quantidade.stripTrailingZeros().toPlainString())
                        .append("\n");

                mensagem.append("Preço unitário: ")
                        .append(formatarMoeda(precoUnitario))
                        .append("\n");

                mensagem.append("Subtotal: ")
                        .append(formatarMoeda(subtotal))
                        .append("\n\n");
            }

            mensagem.append("Subtotal dos itens: ")
                    .append(formatarMoeda(subtotalItens))
                    .append("\n\n");
        }

        mensagem.append("DADOS DO PAGAMENTO\n");
        mensagem.append("----------------------------------------\n");

        if (pagamentos.isEmpty()) {
            mensagem.append("Nenhum registro de pagamento encontrado.\n");
        } else {
            Map<String, Object> pagamento = pagamentos.get(0);

            mensagem.append("Método: ")
                    .append(valor(pagamento, "METODO"))
                    .append("\n");

            mensagem.append("Status: ")
                    .append(valor(pagamento, "STATUS_PAGAMENTO"))
                    .append("\n");

            mensagem.append("Valor: ")
                    .append(formatarMoeda(
                            pagamento.get("VALOR_PAGAMENTO")
                    ))
                    .append("\n");

            mensagem.append("Código da transação: ")
                    .append(valor(pagamento, "CODIGO_TRANSACAO"))
                    .append("\n");

            mensagem.append("Data do pagamento: ")
                    .append(formatarData(
                            pagamento.get("DATA_PAGAMENTO")
                    ))
                    .append("\n");
        }

        mensagem.append("\n========================================\n");
        mensagem.append("Esta é uma notificação automática da Renomake.\n");

        return mensagem.toString();
    }

    private String montarEndereco(Map<String, Object> pedido) {

        List<String> linhas = new ArrayList<>();

        String rua = opcional(pedido, "RUA");
        String numero = opcional(pedido, "NUMERO");
        String complemento = opcional(pedido, "COMPLEMENTO");

        StringBuilder linhaRua = new StringBuilder();

        if (!rua.isBlank()) {
            linhaRua.append(rua);
        }

        if (!numero.isBlank()) {
            if (linhaRua.length() > 0) {
                linhaRua.append(", ");
            }

            linhaRua.append("nº ").append(numero);
        }

        if (!complemento.isBlank()) {
            if (linhaRua.length() > 0) {
                linhaRua.append(" - ");
            }

            linhaRua.append(complemento);
        }

        if (linhaRua.length() > 0) {
            linhas.add(linhaRua.toString());
        }

        String bairro = opcional(pedido, "BAIRRO");

        if (!bairro.isBlank()) {
            linhas.add("Bairro: " + bairro);
        }

        String cidade = opcional(pedido, "CIDADE");
        String estado = opcional(pedido, "ESTADO");

        if (!cidade.isBlank() || !estado.isBlank()) {
            String localidade = cidade;

            if (!estado.isBlank()) {
                localidade += cidade.isBlank() ? estado : " / " + estado;
            }

            linhas.add(localidade);
        }

        String cep = opcional(pedido, "CEP");

        if (!cep.isBlank()) {
            linhas.add("CEP: " + cep);
        }

        return linhas.isEmpty()
                ? "Endereço de entrega não informado."
                : String.join("\n", linhas);
    }

    private String valor(Map<String, Object> dados, String coluna) {

        Object valor = dados.get(coluna);

        if (valor == null || valor.toString().isBlank()) {
            return "Não informado";
        }

        return valor.toString().trim();
    }

    private String opcional(Map<String, Object> dados, String coluna) {

        Object valor = dados.get(coluna);

        return valor == null ? "" : valor.toString().trim();
    }

    private BigDecimal decimal(Object valor) {

        if (valor == null) {
            return BigDecimal.ZERO;
        }

        return new BigDecimal(valor.toString());
    }

    private String formatarMoeda(Object valor) {

        if (valor == null) {
            return "Não informado";
        }

        NumberFormat formato =
                NumberFormat.getCurrencyInstance(LOCALE_BR);

        return formato.format(decimal(valor));
    }

    private String formatarData(Object valor) {

        if (valor == null) {
            return "Não informado";
        }

        if (valor instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(FORMATO_DATA);
        }

        if (valor instanceof LocalDateTime data) {
            return data.format(FORMATO_DATA);
        }

        return valor.toString();
    }

    private void registrarFalha(
            long idNotificacao,
            String motivo) {

        String destino = emailVendedor == null
                || emailVendedor.isBlank()
                ? null
                : emailVendedor;

        int atualizadas = jdbcTemplate.update(
                """
                UPDATE NOTIFICACOES_VENDEDOR
                SET DESTINO = ?,
                    STATUS_ENVIO = 'ERRO',
                    TENTATIVAS = TENTATIVAS + 1
                WHERE ID_NOTIFICACAO = ?
                  AND STATUS_ENVIO IN ('PENDENTE', 'ERRO')
                """,
                destino,
                idNotificacao
        );

        logger.error(
                "Não foi possível enviar a notificação {}: {}. Registros atualizados: {}.",
                idNotificacao,
                motivo,
                atualizadas
        );
    }
}