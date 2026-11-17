package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.loanproduct.client.FssCatalogClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;

class FssCatalogClientTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"),ZoneId.of("Asia/Seoul"));
    final ObjectMapper mapper=new ObjectMapper();
    String response(String base,String options,int max,int page){return "{\"result\":{\"err_cd\":\"000\",\"baseList\":["+base+"],\"optionList\":["+options+"],\"max_page_no\":\""+max+"\",\"now_page_no\":\""+page+"\"}}";}
    String base(String code,String subtype){return "{\"dcls_month\":\"202611\",\"fin_co_no\":\"TESTBANK\",\"kor_co_nm\":\"가상은행\",\"fin_prdt_cd\":\""+code+"\",\"fin_prdt_nm\":\"가상 상품\",\"crdt_prdt_type\":\""+subtype+"\"}";}
    String option(String type,String month){return "{\"dcls_month\":\""+month+"\",\"fin_co_no\":\"TESTBANK\",\"fin_prdt_cd\":\"M1\",\"rpay_type\":\""+type+"\"}";}
    @Test void creditLineSubtypeIsExcludedEvenWhenProductNameLooksLikeOrdinaryCredit()throws Exception{
        var client=new FssCatalogClient(RestClient.create(),"",clock);
        var result=client.result(mapper.readTree(response(base("C1","1")+","+base("C2","2"),"",1,1)));
        var normalized=client.parse(result,LoanType.CREDIT,"creditLoanProductsSearch");
        assertThat(normalized).hasSize(1);assertThat(normalized.getFirst().code()).isEqualTo("C1");
        assertThat(normalized.getFirst().methods()).isEmpty();
    }
    @Test void mortgageRepaymentMethodsUseOnlyMatchingOfficialOptions()throws Exception{
        var client=new FssCatalogClient(RestClient.create(),"",clock);
        var result=client.result(mapper.readTree(response(base("M1",""),option("D","202611")+","+option("S","202611"),1,1)));
        assertThat(client.parse(result,LoanType.MORTGAGE,"mortgageLoanProductsSearch").getFirst().methods())
            .containsExactlyInAnyOrder(RepaymentMethod.EQUAL_PAYMENT,RepaymentMethod.EQUAL_PRINCIPAL,RepaymentMethod.BULLET);
        var unmatched=client.result(mapper.readTree(response(base("M1",""),option("D","202610"),1,1)));
        assertThat(client.parse(unmatched,LoanType.MORTGAGE,"mortgageLoanProductsSearch").getFirst().methods()).isEmpty();
    }
    @Test void paginationFetchesEveryPageBeforePublishingASnapshot(){
        var builder=RestClient.builder().baseUrl("https://finlife.fss.or.kr/finlifeapi");
        var server=MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo(containsString("creditLoanProductsSearch.json?auth=test-key&topFinGrpNo=020000&pageNo=1"))).andRespond(withSuccess(response(base("C1","1"),"",2,1),MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("creditLoanProductsSearch.json?auth=test-key&topFinGrpNo=020000&pageNo=2"))).andRespond(withSuccess(response(base("C2","2"),"",2,2),MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("mortgageLoanProductsSearch.json?auth=test-key&topFinGrpNo=020000&pageNo=1"))).andRespond(withSuccess(response(base("M1",""),option("D","202611"),1,1),MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("rentHouseLoanProductsSearch.json?auth=test-key&topFinGrpNo=020000&pageNo=1"))).andRespond(withSuccess(response(base("J1",""),"",1,1),MediaType.APPLICATION_JSON));
        assertThat(new FssCatalogClient(builder.build(),"test-key",clock).fetch().products()).hasSize(3);server.verify();
    }
    @Test void malformedAndUpstreamErrorsAreExplicitAndDoNotExposeAuthValues()throws Exception{
        var builder=RestClient.builder().baseUrl("https://finlife.fss.or.kr/finlifeapi");var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(()->new FssCatalogClient(builder.build(),"private-fixture-key",clock).fetch())
            .isInstanceOf(FssCatalogClient.Unavailable.class).hasMessage("FSS catalog unavailable").hasNoCause();server.verify();
        var client=new FssCatalogClient(RestClient.create(),"",clock);
        assertThatThrownBy(()->client.result(mapper.readTree("{\"result\":{\"err_cd\":\"020\"}}"))).isInstanceOf(FssCatalogClient.Unavailable.class);
        assertThatThrownBy(client::fetch).isInstanceOf(FssCatalogClient.Unavailable.class);
    }
    @Test void disclosureExpiryIsInclusiveInKstAndMissingCreditSubtypeIsNotAnEmptySuccess()throws Exception{
        var client=new FssCatalogClient(RestClient.create(),"",clock);
        var expired=(ObjectNode)mapper.readTree(base("OLD","1"));expired.put("dcls_end_day","20261116");
        var today=(ObjectNode)mapper.readTree(base("TODAY","1"));today.put("dcls_end_day","20261117");
        var result=client.result(mapper.readTree(response(expired+","+today,"",1,1)));
        assertThat(client.parse(result,LoanType.CREDIT,"creditLoanProductsSearch")).extracting(FssCatalogClient.Product::code).containsExactly("TODAY");
        var missing=(ObjectNode)mapper.readTree(base("MISSING","1"));missing.remove("crdt_prdt_type");
        var invalid=client.result(mapper.readTree(response(missing.toString(),"",1,1)));
        assertThatThrownBy(()->client.parse(invalid,LoanType.CREDIT,"creditLoanProductsSearch")).isInstanceOf(FssCatalogClient.Unavailable.class);
    }
    @Test void genuineEmptyResponsesAreAcceptedOnlyAfterAllCategoriesWereFetched(){
        var builder=RestClient.builder().baseUrl("https://finlife.fss.or.kr/finlifeapi");
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(3),anything()).andRespond(withSuccess(response("","",0,1),MediaType.APPLICATION_JSON));
        assertThat(new FssCatalogClient(builder.build(),"test-key",clock).fetch().products()).isEmpty();server.verify();
    }
    @Test void inconsistentPageNumbersAndExcessivePageCountsAbortWithoutPartialSnapshots(){
        for(String payload:new String[]{response("","",101,1),response("","",1,2)}){
            var builder=RestClient.builder().baseUrl("https://finlife.fss.or.kr/finlifeapi");
            var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(withSuccess(payload,MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->new FssCatalogClient(builder.build(),"test-key",clock).fetch()).isInstanceOf(FssCatalogClient.Unavailable.class);
            server.verify();
        }
    }
}
