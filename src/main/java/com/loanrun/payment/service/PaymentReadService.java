package com.loanrun.payment.service;

import com.loanrun.payment.dto.PaymentDetail;
import com.loanrun.payment.dto.PaymentList;
import com.loanrun.payment.read.PaymentDetailSource;
import com.loanrun.payment.read.PaymentListFilter;
import com.loanrun.payment.repository.PaymentReadRepository;
import com.loanrun.loan.read.LoanSources.PaymentKind;
import com.loanrun.common.error.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentReadService {
    private final PaymentReadRepository payments;
    public PaymentReadService(PaymentReadRepository payments) { this.payments=payments; }

    public PaymentDetail detail(long userId,long paymentId) {
        return view(payments.detail(userId,paymentId));
    }

    public PaymentList list(long userId,String loanId,String from,String to,String type,String page,String pageSize) {
        var start=date(from);var end=date(to);
        if(start!=null&&end!=null&&start.isAfter(end))throw invalidRange();
        Long loan=loanId==null?null:positiveLong(loanId,"loan_id");
        int number=page==null?1:positiveInt(page,"page");
        int size=pageSize==null?20:positiveInt(pageSize,"page_size");
        if(size>100)throw ApiException.validation("page_size","1부터 100까지 입력해 주세요.");
        PaymentKind kind=null;
        if(type!=null) {
            try {kind=PaymentKind.valueOf(type);}
            catch(IllegalArgumentException ex){throw ApiException.validation("type","REGULAR 또는 EXTRA를 입력해 주세요.");}
        }
        var filter=new PaymentListFilter(loan,start,end,kind,number,size);
        var source=payments.list(userId,filter);
        Integer next=filter.offset()+size<source.count()&&number<Integer.MAX_VALUE?number+1:null;
        Integer previous=number>1?number-1:null;
        return new PaymentList(source.count(),number,size,next,previous,source.records().stream().map(this::view).toList(),
            new PaymentList.Totals(source.amount(),source.principal(),source.interest(),source.extra(),source.fee(),source.savingsUsed()));
    }

    private PaymentDetail view(PaymentDetailSource source) {
        var p=source.payment();
        var allocations=source.allocations().stream().map(a->new PaymentDetail.DueAllocation(a.dueItemId(),
            Math.addExact(a.principal(),a.interest()),Math.max(0,ChronoUnit.DAYS.between(a.dueDate(),p.paidAt())))).toList();
        var first=allocations.isEmpty()?null:allocations.getFirst();
        return new PaymentDetail(p.id(),p.loanId(),p.paidAt(),p.amount(),p.kind(),first==null?null:first.dueItemId(),
            p.regularPrincipal(),p.interest(),p.extraPrincipal(),p.createdAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            p.fee(),p.savingsUsed(),!p.paidAt().isBefore(source.snapshotDate()),first==null?null:first.delayDays(),allocations);
    }

    private static LocalDate date(String value) {
        if(value==null)return null;
        try {
            if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||value.startsWith("0000"))throw invalidRange();
            return LocalDate.parse(value);
        } catch(DateTimeParseException ex){throw invalidRange();}
    }
    private static ApiException invalidRange(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DATE_RANGE","조회 기간을 확인해 주세요.");}
    private static long positiveLong(String value,String field) {
        try {
            if(!value.matches("[0-9]+"))throw new NumberFormatException();
            long result=Long.parseLong(value);if(result<=0)throw new NumberFormatException();return result;
        } catch(NumberFormatException ex){throw ApiException.validation(field,"양의 정수를 입력해 주세요.");}
    }
    private static int positiveInt(String value,String field) {
        long number=positiveLong(value,field);
        if(number>Integer.MAX_VALUE)throw ApiException.validation(field,"지원 범위의 양의 정수를 입력해 주세요.");
        return (int)number;
    }
}
