package com.loanrun.loanproduct.service;

import com.loanrun.common.contract.ProductQueryPort;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import com.loanrun.loanproduct.repository.CatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class LoanProductService implements ProductQueryPort {
    public record Option(String code,String label){}
    public record Institution(long institutionId,String name){}
    public record Options(List<Option> loanTypes,List<Institution> institutions){}
    public record Product(long productId,long institutionId,String name,LoanType loanType,List<RepaymentMethod> repaymentMethods,String source){}
    public record Products(List<Product> products){}
    private final CatalogRepository repository;
    public LoanProductService(CatalogRepository repository){this.repository=repository;}
    private LoanType type(String value){try{return value==null?null:LoanType.valueOf(value);}catch(IllegalArgumentException ex){throw ApiException.validation("loan_type","지원하지 않는 유형입니다.");}}
    private void form(String value){if(value!=null&&!"GENERAL".equals(value))throw ApiException.validation("loan_form","GENERAL만 지원합니다.");}
    private void ready(){if(!repository.ready())throw ApiException.unavailable();}
    public Options options(String type,String form){
        form(form);LoanType loanType=type(type);
        List<Option> options=List.of(new Option("CREDIT","신용대출"),new Option("MORTGAGE","주택담보대출"),new Option("JEONSE","전세자금대출"),
            new Option("STUDENT","학자금대출"),new Option("AUTO","자동차대출"),new Option("OTHER","기타"));
        if(loanType!=null&&!Set.of(LoanType.CREDIT,LoanType.MORTGAGE,LoanType.JEONSE).contains(loanType))return new Options(options,List.of());
        ready();
        List<Institution> institutions=repository.institutions(loanType).stream().map(i->new Institution(i.id(),i.name())).toList();
        return new Options(options,institutions);
    }
    public Products products(String type,String form,long institutionId){
        form(form);LoanType loanType=type(type);if(institutionId<=0)throw ApiException.validation("institution_id","양의 ID가 필요합니다.");ready();
        if(!repository.institutionExists(institutionId))throw new ApiException(HttpStatus.NOT_FOUND,"INSTITUTION_NOT_FOUND","금융기관을 찾을 수 없습니다.");
        return new Products(repository.products(institutionId,loanType).stream().map(p->new Product(p.productId(),p.institutionId(),p.name(),p.loanType(),p.repaymentMethods(),"CATALOG")).toList());
    }
    @Override public EligibleProduct findEligibleProduct(long productId){
        return repository.eligible(productId).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","조회한 경로 또는 리소스를 찾을 수 없습니다."));
    }
}
