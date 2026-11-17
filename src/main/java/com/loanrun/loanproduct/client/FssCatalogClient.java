package com.loanrun.loanproduct.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.FinanceModels.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.web.client.RestClient;

/** Official FSS product disclosure; no borrower/session information is transmitted. */
public class FssCatalogClient {
    public record Product(String institutionCode,String institutionName,String code,String name,LoanType type,
        List<RepaymentMethod> methods,String sourceUrl) { public Product {methods=List.copyOf(methods);} }
    public record Snapshot(List<Product> products) {public Snapshot {products=List.copyOf(products);} }
    public static class Unavailable extends RuntimeException {public Unavailable(){super("FSS catalog unavailable");} }
    private final RestClient client;
    private final String key;
    private final Clock clock;
    public FssCatalogClient(RestClient client,String key,Clock clock){this.client=client;this.key=key;this.clock=clock;}

    public Snapshot fetch(){
        if(key==null||key.isBlank())throw new Unavailable();
        List<Product> products=new ArrayList<>();
        for(var category:Map.of(LoanType.CREDIT,"creditLoanProductsSearch",LoanType.MORTGAGE,"mortgageLoanProductsSearch",LoanType.JEONSE,"rentHouseLoanProductsSearch").entrySet()){
            String service=category.getValue();int page=1,max=1;
            do{
                int currentPage=page;
                JsonNode response;
                try{response=client.get().uri(builder->builder.path("/"+service+".json").queryParam("auth",key)
                    .queryParam("topFinGrpNo","020000").queryParam("pageNo",currentPage).build()).retrieve().body(JsonNode.class);}
                catch(RuntimeException ex){throw new Unavailable();} // Never propagate an exception containing auth query parameters.
                JsonNode result=result(response);
                max=positivePage(result,"max_page_no",true);
                if(max>100||positivePage(result,"now_page_no",false)!=page)throw new Unavailable();
                products.addAll(parse(result,category.getKey(),service));page++;
            }while(page<=max);
        }
        return new Snapshot(products);
    }
    private int positivePage(JsonNode result,String field,boolean allowZero){
        try{int value=Integer.parseInt(result.path(field).asText());if(value<(allowZero?0:1))throw new Unavailable();return value;}
        catch(NumberFormatException ex){throw new Unavailable();}
    }
    public JsonNode result(JsonNode response){
        if(response==null||!response.path("result").isObject())throw new Unavailable();
        var result=response.get("result");
        if(!"000".equals(result.path("err_cd").asText())||!result.path("baseList").isArray()||!result.path("optionList").isArray())throw new Unavailable();
        return result;
    }
    public List<Product> parse(JsonNode result,LoanType type,String service){
        List<Product> products=new ArrayList<>();
        for(var base:result.path("baseList")){
            String name=required(base,"fin_prdt_nm");
            String subtype=type==LoanType.CREDIT?required(base,"crdt_prdt_type"):"";
            String names=name+base.path("crdt_prdt_type_nm").asText("");
            if(names.contains("마이너스")||names.contains("한도대출")||names.contains("종합통장")||names.toLowerCase(Locale.ROOT).contains("overdraft")
                ||(type==LoanType.CREDIT&&"2".equals(subtype)))continue;
            if(type==LoanType.CREDIT&&!Set.of("1","3").contains(subtype))continue; // Unknown credit subtypes require explicit review.
            if(base.hasNonNull("dcls_end_day")){
                try{if(LocalDate.parse(base.get("dcls_end_day").asText(),DateTimeFormatter.BASIC_ISO_DATE).isBefore(LocalDate.now(clock)))continue;}
                catch(RuntimeException ex){throw new Unavailable();}
            }
            String institution=required(base,"fin_co_no"),code=required(base,"fin_prdt_cd"),month=required(base,"dcls_month");
            EnumSet<RepaymentMethod> methods=EnumSet.noneOf(RepaymentMethod.class);
            if(type!=LoanType.CREDIT)for(var option:result.get("optionList")){
                if(institution.equals(option.path("fin_co_no").asText())&&code.equals(option.path("fin_prdt_cd").asText())&&month.equals(option.path("dcls_month").asText())){
                    if("S".equals(option.path("rpay_type").asText()))methods.add(RepaymentMethod.BULLET);
                    if("D".equals(option.path("rpay_type").asText())){methods.add(RepaymentMethod.EQUAL_PAYMENT);methods.add(RepaymentMethod.EQUAL_PRINCIPAL);}
                }
            }
            products.add(new Product(institution,required(base,"kor_co_nm"),code,name,type,List.copyOf(methods),"https://finlife.fss.or.kr/finlifeapi/"+service+".json"));
        }
        return List.copyOf(products);
    }
    private String required(JsonNode node,String field){
        var value=node.get(field);if(value==null||!value.isTextual()||value.asText().isBlank())throw new Unavailable();return value.asText();
    }
}
