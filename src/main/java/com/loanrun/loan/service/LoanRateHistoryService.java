package com.loanrun.loan.service;

import com.loanrun.loan.dto.LoanRateHistory;
import com.loanrun.loan.read.LoanSourceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import org.springframework.stereotype.Service;

@Service
public class LoanRateHistoryService {
    private final LoanSourceRepository sources;
    public LoanRateHistoryService(LoanSourceRepository sources) { this.sources=sources; }

    public LoanRateHistory history(long userId,long loanId) {
        var entries=new ArrayList<LoanRateHistory.Entry>();
        BigDecimal previous=null;
        for(var rate:sources.rateHistory(userId,loanId)) {
            entries.add(new LoanRateHistory.Entry(rate.id(),rate.loanId(),rate.effectiveDate(),previous,rate.annualRate(),"MANUAL"));
            previous=rate.annualRate();
        }
        return new LoanRateHistory(loanId,entries);
    }
}
