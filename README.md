# Renomake backend

API Java 21 / Spring Boot 4 para catálogo e pedidos, conectada ao Oracle FIAP. O frontend correspondente está no repositório [Renomake](https://github.com/ViniciosAlvees/Renomake).

## Configuração local

Use Java 21 e configure as variáveis no terminal, sem gravar credenciais em arquivos versionados:

```powershell
$env:DB_URL = "jdbc:oracle:thin:@oracle.fiap.com.br:1521:ORCL"
$env:DB_USERNAME = "<usuario Oracle>"
$env:DB_PASSWORD = "<senha Oracle>"
$env:MP_ACCESS_TOKEN = "<token privado do Mercado Pago>"
$env:MP_WEBHOOK_SECRET = "<segredo do webhook>"
$env:FRONTEND_URL = "http://localhost:5173/"
$env:SESSION_COOKIE_SECURE = "false"
$env:SESSION_COOKIE_SAME_SITE = "lax"
.\mvnw.cmd spring-boot:run
```

O arquivo `.env.example` contém somente nomes e exemplos não secretos. O Spring não carrega `.env` automaticamente; defina as variáveis no ambiente de execução. `DB_USERNAME` e `DB_PASSWORD` são obrigatórios para conectar ao Oracle. Os valores do Mercado Pago são obrigatórios para iniciar pagamentos e validar notificações.

Os scripts SQL anexados (`sql.renomake`) mostram 55 produtos com `QUANTIDADE_ESTOQUE = 0`; o script de carga também inicializa novos produtos em zero. Não aumente o estoque automaticamente: atualize a coluna com as quantidades reais antes de testar compras. O backend valida e reserva estoque em Oracle.

## Endpoints usados pelo frontend

- `GET /api/produtos`
- `POST /api/usuarios/cadastro`, `POST /api/usuarios/login`, `POST /api/usuarios/logout`, `GET /api/usuarios/me`
- `GET /api/enderecos`, `POST /api/enderecos`
- `GET /api/carrinho`, `POST /api/carrinho/itens`, `PUT` e `DELETE /api/carrinho/itens/{idProduto}`
- `POST /api/pedidos`, `GET /api/pedidos/{idPedido}`
- `POST /api/checkout/{idPedido}`
- `POST /api/webhooks/mercadopago`

As rotas de carrinho, endereço, pedido e checkout usam a sessão HTTP do backend; não aceite um `idUsuario` fornecido pelo navegador como identidade. O checkout envia o cliente para a página segura do Mercado Pago. O pedido só é marcado como pago após a validação da assinatura e do valor no webhook.

## Produção

O GitHub Pages não executa esta API Java. Para publicar o backend, use uma hospedagem de contêineres (por exemplo, Render) conectada ao repositório `Renomake-Beckend`, selecione o runtime Docker e use o `Dockerfile` da raiz. A aplicação escuta a porta definida pela variável `PORT` fornecida pela hospedagem.

Cadastre no painel da hospedagem, sem colocá-las no Git, as variáveis:

- `DB_URL`, `DB_USERNAME` e `DB_PASSWORD`
- `MP_ACCESS_TOKEN` e `MP_WEBHOOK_SECRET`
- `FRONTEND_URL=https://viniciosalvees.github.io/Renomake/`
- `SESSION_COOKIE_SECURE=true` e `SESSION_COOKIE_SAME_SITE=none`
- `MAIL_USERNAME` e `MAIL_PASSWORD` se o envio de e-mail estiver habilitado

Após o primeiro deploy, confirme que a API está acessível em `https://<dominio-backend>/api/produtos`. Configure `VITE_API_BASE_URL` com a origem HTTPS do serviço (sem `/api` no final) nas variáveis do GitHub Actions do repositório `Renomake` e execute novamente o workflow do GitHub Pages.

No painel do Mercado Pago, registre `https://<dominio-backend>/api/webhooks/mercadopago`.

**Importante:** o serviço hospedado precisa conseguir acessar o Oracle FIAP pela rede. Se a FIAP bloquear conexões externas ou exigir uma rede institucional, o deploy pode iniciar, mas a API não conseguirá consultar produtos nem registrar pedidos. Valide o acesso Oracle antes de considerar a integração operacional. Não use estoque zero para testar compras.

## Segurança de credenciais

Credenciais de banco chegaram a ser incluídas em configuração versionada anteriormente. Remova/rotacione a senha do Oracle no painel FIAP e não reutilize credenciais antigas. Os valores atuais foram movidos para variáveis de ambiente neste checkout.

## Verificação

```powershell
.\mvnw.cmd -DskipTests package
```
