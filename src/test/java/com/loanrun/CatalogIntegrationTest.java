package com.loanrun;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.loanproduct.client.FssCatalogClient;
import com.loanrun.loanproduct.service.CatalogWriter;
import com.loanrun.common.contract.ProductQueryPort;
import com.loanrun.common.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.contract.RevisionPort;
import com.loanrun.common.contract.LoanQueryPort;
import com.loanrun.common.contract.PlanQueryPort;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.idempotency.IdempotencyService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"loanrun.demo-mode=true", "server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired IdempotencyService idempotency;
    @Autowired RevisionPort revisions;
    @Autowired TransactionTemplate transactions;

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private HttpResponse<String> request(HttpClient client, String method, String path, String body, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (origin != null) builder.header("Origin", origin);
        builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    private long userId(HttpClient browser) throws Exception {
        return json(request(browser, "GET", "/api/users/me/", null, null)).path("data").path("user_id").longValue();
    }

    @Autowired CatalogWriter catalogWriter;
    @Autowired ProductQueryPort productQueries;
    @Test void productCatalogDistinguishesUninitializedFromEmptyAndPreservesLastCacheOnFailure()throws Exception{
        var client=browser();
        assertThat(request(client,"GET","/api/loan-products/options/?loan_type=CREDIT",null,null).statusCode()).isEqualTo(503);
        assertThat(request(client,"GET","/api/loan-products/options/?loan_type=AUTO",null,null).statusCode()).isEqualTo(200);
        assertThat(request(client,"GET","/api/loan-products/options/?loan_form=LIMIT",null,null).statusCode()).isEqualTo(400);
        catalogWriter.replace(new FssCatalogClient.Snapshot(List.of(
            new FssCatalogClient.Product("FIXTURE-BANK","가상 은행","FIXTURE-CREDIT","가상 신용 상품",LoanType.CREDIT,List.of(),"https://example.invalid/fixture"),
            new FssCatalogClient.Product("FIXTURE-BANK","가상 은행","FIXTURE-JEONSE","가상 전세 상품",LoanType.JEONSE,List.of(RepaymentMethod.BULLET),"https://example.invalid/fixture"))));
        var options=json(request(client,"GET","/api/loan-products/options/?loan_type=CREDIT",null,null)).path("data");
        long institution=options.path("institutions").get(0).path("institution_id").longValue();
        assertThat(options.path("loan_types").size()).isEqualTo(6);
        var products=json(request(client,"GET","/api/loan-products/?institution_id="+institution+"&loan_type=CREDIT",null,null)).path("data").path("products");
        assertThat(products.size()).isEqualTo(1);assertThat(products.get(0).path("repayment_methods").isEmpty()).isTrue();
        long productId=products.get(0).path("product_id").longValue();assertThat(productQueries.findEligibleProduct(productId).name()).isEqualTo("가상 신용 상품");
        assertThat(request(client,"GET","/api/loan-products/?institution_id=999999",null,null).statusCode()).isEqualTo(404);
        assertThatThrownBy(()->catalogWriter.replace(new FssCatalogClient.Snapshot(List.of(
            new FssCatalogClient.Product("FIXTURE-BANK","가상 은행","BAD","마이너스한도대출",LoanType.CREDIT,List.of(),"https://example.invalid/fixture")))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(productQueries.findEligibleProduct(productId).name()).isEqualTo("가상 신용 상품");
        assertThatThrownBy(()->catalogWriter.replace(new FssCatalogClient.Snapshot(List.of(
            new FssCatalogClient.Product("FIXTURE-BANK","가상 은행","FIXTURE-CREDIT","변경되면 안 되는 상품",LoanType.CREDIT,List.of(),"https://example.invalid/fixture"),
            new FssCatalogClient.Product("BROKEN-BANK",null,"BROKEN","가상 상품",LoanType.CREDIT,List.of(),"https://example.invalid/fixture")))))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(productQueries.findEligibleProduct(productId).name()).isEqualTo("가상 신용 상품");
        catalogWriter.replace(new FssCatalogClient.Snapshot(List.of()));
        var empty=request(client,"GET","/api/loan-products/options/?loan_type=CREDIT",null,null);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(json(empty).path("data").path("institutions").isEmpty()).isTrue();
        assertThatThrownBy(()->productQueries.findEligibleProduct(productId)).isInstanceOf(ApiException.class);
        catalogWriter.replace(new FssCatalogClient.Snapshot(List.of(
            new FssCatalogClient.Product("FIXTURE-BANK","가상 은행","FIXTURE-CREDIT","가상 신용 상품",LoanType.CREDIT,List.of(),"https://example.invalid/fixture"))));
        assertThat(productQueries.findEligibleProduct(productId).productId()).isEqualTo(productId);
    }
}
